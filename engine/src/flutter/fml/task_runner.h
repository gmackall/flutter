// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_FML_TASK_RUNNER_H_
#define FLUTTER_FML_TASK_RUNNER_H_

#include <condition_variable>
#include <memory>
#include <mutex>

#include "flutter/fml/closure.h"
#include "flutter/fml/logging.h"
#include "flutter/fml/macros.h"
#include "flutter/fml/memory/ref_counted.h"
#include "flutter/fml/memory/ref_ptr.h"
#include "flutter/fml/message_loop_task_queues.h"
#include "flutter/fml/time/time_point.h"

namespace fml {

class MessageLoopImpl;
class TaskRunner;

/// Result of a preemptible synchronous task wait on a target \p TaskRunner.
enum class PreemptibleWaitResult {
  /// The task ran to completion on the target task runner.
  kCompleted,
  /// The wait was preempted because the target task runner's thread was in (or
  /// entered) a blocking synchronization wait.
  kPreempted,
};

/// Behavior when a synchronous wait on a target \p TaskRunner is preempted.
enum class PreemptionAction {
  /// If preempted before the task begins executing on the target task runner,
  /// cancel the task so it does not run when the target thread unblocks.
  ///
  /// Because the task is guaranteed never to execute after
  /// |RunNowOrPostTaskAndWaitWithPreemption| returns, the task closure may
  /// safely capture stack variables from the calling frame by reference.
  ///
  /// CONTRACT: A |kCancelTask| closure must not itself enter a blocking
  /// synchronization wait on the target thread (enforced via |FML_DCHECK| in
  /// debug builds).
  kCancelTask,
  /// If preempted, stop waiting on the calling thread immediately, but still
  /// execute the task asynchronously on the target task runner once it
  /// unblocks.
  ///
  /// WARNING: Because the task may execute after
  /// |RunNowOrPostTaskAndWaitWithPreemption| returns |kPreempted|, the task
  /// closure MUST NOT capture stack variables from the calling frame by
  /// reference.
  kRunAsynchronously,
};

/// Coordinates preemption of blocking waits from a non-main/worker thread
/// (such as the raster thread) onto a target MessageLoop-backed task runner
/// (typically the main/platform thread) whenever that target thread enters a
/// blocking synchronization wait.
class TaskRunnerWaitPreemptor {
 public:
  TaskRunnerWaitPreemptor() = default;
  ~TaskRunnerWaitPreemptor() = default;

  void BeginPreemption();

  void EndPreemption();

  bool IsPreempted() const {
    std::lock_guard lock(mutex_);
    return preempt_count_ > 0;
  }

  bool HasWaiters() const {
    std::lock_guard lock(mutex_);
    return waiting_count_ > 0;
  }

  static TaskRunnerWaitPreemptor* GetCurrentThreadPreemptor();

 private:
  friend class TaskRunner;

  bool IsPreemptedLocked() const { return preempt_count_ > 0; }

  mutable std::mutex mutex_;
  std::condition_variable cv_;
  size_t preempt_count_ = 0;
  size_t waiting_count_ = 0;

  FML_DISALLOW_COPY_AND_ASSIGN(TaskRunnerWaitPreemptor);
};

/// An interface over the ability to schedule tasks on a \p TaskRunner.
class BasicTaskRunner {
 public:
  /// Schedules \p task to be executed on the TaskRunner's associated event
  /// loop.
  virtual void PostTask(const fml::closure& task) = 0;
};

/// The object for scheduling tasks on a \p fml::MessageLoop.
///
/// Typically there is one \p TaskRunner associated with each thread.  When one
/// wants to execute an operation on that thread they post a task to the
/// TaskRunner.
///
/// \see fml::MessageLoop
class TaskRunner : public fml::RefCountedThreadSafe<TaskRunner>,
                   public BasicTaskRunner {
 public:
  virtual ~TaskRunner();

  virtual void PostTask(const fml::closure& task) override;

  virtual void PostTaskForTime(const fml::closure& task,
                               fml::TimePoint target_time);

  /// Schedules a task to be run on the MessageLoop after the time \p delay has
  /// passed.
  /// \note There is latency between when the task is schedule and actually
  /// executed so that the actual execution time is: now + delay +
  /// message_loop_latency, where message_loop_latency is undefined and could be
  /// tens of milliseconds.
  virtual void PostDelayedTask(const fml::closure& task, fml::TimeDelta delay);

  /// Returns \p true when the current executing thread's TaskRunner matches
  /// this instance.
  virtual bool RunsTasksOnCurrentThread();

  /// Returns the unique identifier associated with the TaskRunner.
  /// \see fml::MessageLoopTaskQueues
  ///
  /// Will be TaskQueueId::kInvalid for embedder supplied task runners
  /// that are not associated with a task queue.
  virtual TaskQueueId GetTaskQueueId();

  /// Executes the \p task directly if the TaskRunner \p runner is the
  /// TaskRunner associated with the current executing thread.
  static void RunNowOrPostTask(const fml::RefPtr<fml::TaskRunner>& runner,
                               const fml::closure& task);

  /// Like RunNowOrPostTask, except that if the task can be immediately
  /// executed, an empty task will still be posted to the runner afterwards.
  ///
  /// This is used to ensure that messages posted to Dart from the platform
  /// thread always flush the Dart event loop.
  static void RunNowAndFlushMessages(const fml::RefPtr<fml::TaskRunner>& runner,
                                     const fml::closure& task);

  /// Runs |task| synchronously on |target_task_runner| (inline if already on
  /// |target_task_runner|, or by posting and waiting), UNLESS
  /// |target_task_runner|'s thread is currently in (or enters) a blocking
  /// synchronization wait, in which case the wait is immediately preempted
  /// and returns |PreemptibleWaitResult::kPreempted|.
  ///
  /// Intended for use by non-main threads (such as the raster thread) when
  /// posting a task to and waiting on the main/platform thread. While the
  /// calling thread is blocked waiting for |target_task_runner|, the calling
  /// thread's own |TaskRunnerWaitPreemptor| (if any) is also marked as
  /// preempted so transitive multi-thread wait cycles cannot deadlock.
  ///
  /// CONTRACT: Every caller MUST handle |PreemptibleWaitResult::kPreempted|
  /// and MUST be able to safely continue execution on the calling thread
  /// without the return value or side effects of |task|. When |action| is
  /// |PreemptionAction::kCancelTask|, |task| must not enter a blocking
  /// synchronization wait on the target thread.
  [[nodiscard]] static PreemptibleWaitResult
  RunNowOrPostTaskAndWaitWithPreemption(
      const fml::RefPtr<fml::TaskRunner>& target_task_runner,
      const fml::closure& task,
      PreemptionAction action = PreemptionAction::kCancelTask);

  const std::shared_ptr<TaskRunnerWaitPreemptor>& GetWaitPreemptor() const {
    return wait_preemptor_;
  }

 protected:
  explicit TaskRunner(fml::RefPtr<MessageLoopImpl> loop);

 private:
  fml::RefPtr<MessageLoopImpl> loop_;
  std::shared_ptr<TaskRunnerWaitPreemptor> wait_preemptor_;

  FML_FRIEND_MAKE_REF_COUNTED(TaskRunner);
  FML_FRIEND_REF_COUNTED_THREAD_SAFE(TaskRunner);
  FML_DISALLOW_COPY_AND_ASSIGN(TaskRunner);
};

/// RAII scope guard that marks the current thread's (or a specified)
/// |TaskRunnerWaitPreemptor| as currently in a blocking synchronization wait,
/// preempting any synchronous wait targeting that task runner via
/// |TaskRunner::RunNowOrPostTaskAndWaitWithPreemption|.
class ScopedTaskRunnerWaitPreemption {
 public:
  ScopedTaskRunnerWaitPreemption()
      : ScopedTaskRunnerWaitPreemption(
            TaskRunnerWaitPreemptor::GetCurrentThreadPreemptor()) {}

  explicit ScopedTaskRunnerWaitPreemption(TaskRunnerWaitPreemptor* preemptor)
      : preemptor_(preemptor) {
    if (preemptor_) {
      preemptor_->BeginPreemption();
    }
  }

  ~ScopedTaskRunnerWaitPreemption() {
    if (preemptor_) {
      preemptor_->EndPreemption();
    }
  }

 private:
  TaskRunnerWaitPreemptor* preemptor_ = nullptr;

  FML_DISALLOW_COPY_AND_ASSIGN(ScopedTaskRunnerWaitPreemption);
};

}  // namespace fml

#endif  // FLUTTER_FML_TASK_RUNNER_H_
