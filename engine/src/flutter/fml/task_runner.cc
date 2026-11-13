// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#define FML_USED_ON_EMBEDDER

#include "flutter/fml/task_runner.h"
#include "flutter/fml/memory/task_runner_checker.h"

#include <utility>

#include "flutter/fml/logging.h"
#include "flutter/fml/message_loop.h"
#include "flutter/fml/message_loop_impl.h"
#include "flutter/fml/message_loop_task_queues.h"

namespace fml {

namespace {

// Tracks whether the current thread is actively executing a
// PreemptionAction::kCancelTask closure inside
// RunNowOrPostTaskAndWaitWithPreemption.
thread_local size_t tls_cancelable_preemptible_task_depth = 0;

class ScopedCancelablePreemptibleTaskDebugCheck {
 public:
  explicit ScopedCancelablePreemptibleTaskDebugCheck(PreemptionAction action)
      : active_(action == PreemptionAction::kCancelTask) {
    if (active_) {
      ++tls_cancelable_preemptible_task_depth;
    }
  }

  ~ScopedCancelablePreemptibleTaskDebugCheck() {
    if (active_) {
      --tls_cancelable_preemptible_task_depth;
    }
  }

 private:
  const bool active_;
};

// Single-writer-per-transition state machine for
// RunNowOrPostTaskAndWaitWithPreemption:
// - The target thread only ever transitions kPending -> kRunning -> kDone.
// - The waiting thread only ever transitions kPending -> kAbandoned.
enum class Phase {
  kPending,
  kRunning,
  kDone,
  kAbandoned,
};

struct PreemptibleTaskState {
  explicit PreemptibleTaskState(fml::closure task) : task(std::move(task)) {}

  Phase phase = Phase::kPending;
  fml::closure task;
};

}  // namespace

void TaskRunnerWaitPreemptor::BeginPreemption() {
  FML_DCHECK(tls_cancelable_preemptible_task_depth == 0)
      << "A PreemptionAction::kCancelTask closure must not enter a blocking "
         "synchronization wait on the target thread.";
  bool should_notify = false;
  {
    std::lock_guard lock(mutex_);
    ++preempt_count_;
    should_notify = (waiting_count_ > 0);
  }
  if (should_notify) {
    cv_.notify_all();
  }
}

void TaskRunnerWaitPreemptor::EndPreemption() {
  std::lock_guard lock(mutex_);
  FML_DCHECK(preempt_count_ > 0);
  --preempt_count_;
}

TaskRunnerWaitPreemptor* TaskRunnerWaitPreemptor::GetCurrentThreadPreemptor() {
  if (MessageLoop::IsInitializedForCurrentThread()) {
    return MessageLoop::GetCurrent().GetLoopImpl()->GetWaitPreemptor().get();
  }
  return nullptr;
}

TaskRunner::TaskRunner(fml::RefPtr<MessageLoopImpl> loop)
    : loop_(std::move(loop)),
      wait_preemptor_(loop_ ? loop_->GetWaitPreemptor()
                            : std::make_shared<TaskRunnerWaitPreemptor>()) {}

TaskRunner::~TaskRunner() = default;

void TaskRunner::PostTask(const fml::closure& task) {
  loop_->PostTask(task, fml::TimePoint::Now());
}

void TaskRunner::PostTaskForTime(const fml::closure& task,
                                 fml::TimePoint target_time) {
  loop_->PostTask(task, target_time);
}

void TaskRunner::PostDelayedTask(const fml::closure& task,
                                 fml::TimeDelta delay) {
  loop_->PostTask(task, fml::TimePoint::Now() + delay);
}

TaskQueueId TaskRunner::GetTaskQueueId() {
  FML_DCHECK(loop_);
  return loop_->GetTaskQueueId();
}

bool TaskRunner::RunsTasksOnCurrentThread() {
  if (!fml::MessageLoop::IsInitializedForCurrentThread()) {
    return false;
  }

  const auto current_queue_id = MessageLoop::GetCurrentTaskQueueId();
  const auto loop_queue_id = loop_->GetTaskQueueId();

  return TaskRunnerChecker::RunsOnTheSameThread(current_queue_id,
                                                loop_queue_id);
}

void TaskRunner::RunNowOrPostTask(const fml::RefPtr<fml::TaskRunner>& runner,
                                  const fml::closure& task) {
  FML_DCHECK(runner);
  if (runner->RunsTasksOnCurrentThread()) {
    task();
  } else {
    runner->PostTask(std::move(task));
  }
}

void TaskRunner::RunNowAndFlushMessages(
    const fml::RefPtr<fml::TaskRunner>& runner,
    const fml::closure& task) {
  FML_DCHECK(runner);
  if (runner->RunsTasksOnCurrentThread()) {
    task();
    // Post an empty task to make the UI MessageLoop run its task observers.
    runner->PostTask([] {});
  } else {
    runner->PostTask(std::move(task));
  }
}

PreemptibleWaitResult TaskRunner::RunNowOrPostTaskAndWaitWithPreemption(
    const fml::RefPtr<fml::TaskRunner>& runner,
    const fml::closure& task,
    PreemptionAction action) {
  FML_DCHECK(runner);
  if (runner->RunsTasksOnCurrentThread()) {
    ScopedCancelablePreemptibleTaskDebugCheck debug_scope(action);
    task();
    return PreemptibleWaitResult::kCompleted;
  }

  FML_DCHECK(runner->loop_)
      << "RunNowOrPostTaskAndWaitWithPreemption requires a MessageLoop-backed "
         "TaskRunner.";
  const auto& target_preemptor = runner->wait_preemptor_;
  auto s = std::make_shared<PreemptibleTaskState>(task);

  runner->PostTask([s, target_preemptor, action]() {
    fml::closure to_run;
    {
      std::lock_guard lock(target_preemptor->mutex_);
      if (s->phase == Phase::kAbandoned &&
          action == PreemptionAction::kCancelTask) {
        return;
      }
      if (s->phase == Phase::kPending) {
        s->phase = Phase::kRunning;
      }
      to_run = std::exchange(s->task, nullptr);
    }

    if (to_run) {
      ScopedCancelablePreemptibleTaskDebugCheck debug_scope(action);
      to_run();
      to_run = nullptr;
    }

    {
      std::lock_guard lock(target_preemptor->mutex_);
      if (s->phase == Phase::kRunning) {
        s->phase = Phase::kDone;
        target_preemptor->cv_.notify_all();
      }
    }
  });

  // Mark the calling thread's own preemptor (if distinct from the target) as
  // preempted while waiting on target_preemptor, without ever nesting two
  // TaskRunnerWaitPreemptor mutexes.
  TaskRunnerWaitPreemptor* caller_preemptor =
      TaskRunnerWaitPreemptor::GetCurrentThreadPreemptor();
  if (caller_preemptor == target_preemptor.get()) {
    caller_preemptor = nullptr;
  }
  ScopedTaskRunnerWaitPreemption caller_preempt_guard(caller_preemptor);

  std::unique_lock lock(target_preemptor->mutex_);
  ++target_preemptor->waiting_count_;
  target_preemptor->cv_.wait(lock, [&]() {
    return s->phase == Phase::kDone ||
           (target_preemptor->IsPreemptedLocked() &&
            (s->phase == Phase::kPending ||
             action == PreemptionAction::kRunAsynchronously));
  });
  --target_preemptor->waiting_count_;

  if (s->phase == Phase::kDone) {
    lock.unlock();
    return PreemptibleWaitResult::kCompleted;
  }

  fml::closure cancelled;
  if (s->phase == Phase::kPending) {
    s->phase = Phase::kAbandoned;
    if (action == PreemptionAction::kCancelTask) {
      cancelled = std::exchange(s->task, nullptr);
    }
  } else {
    FML_DCHECK(s->phase == Phase::kRunning &&
               action == PreemptionAction::kRunAsynchronously);
  }
  lock.unlock();
  cancelled = nullptr;
  return PreemptibleWaitResult::kPreempted;
}

}  // namespace fml
