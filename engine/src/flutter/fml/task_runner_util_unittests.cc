// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#define FML_USED_ON_EMBEDDER

#include <atomic>
#include <memory>
#include <thread>

#include "flutter/fml/message_loop.h"
#include "flutter/fml/message_loop_impl.h"
#include "flutter/fml/synchronization/count_down_latch.h"
#include "flutter/fml/synchronization/sync_switch.h"
#include "flutter/fml/synchronization/waitable_event.h"
#include "flutter/fml/task_runner.h"
#include "flutter/fml/task_runner_util.h"
#include "flutter/fml/thread.h"
#include "gtest/gtest.h"

namespace fml {
namespace testing {

TEST(TaskRunnerUtilTests, WrapperBasicTaskRunnerPostTask) {
  fml::Thread thread;

  WrapperBasicTaskRunner wrapper(thread.GetTaskRunner());

  std::thread::id wrapper_thread_id;
  wrapper.PostTask([&]() { wrapper_thread_id = std::this_thread::get_id(); });

  thread.Join();

  EXPECT_NE(wrapper_thread_id, std::this_thread::get_id());
}

TEST(TaskRunnerUtilTests, ConditionalBasicTaskRunnerPostTask) {
  fml::Thread thread;
  std::atomic_bool active = true;
  ConditionalBasicTaskRunner runner(thread.GetTaskRunner(),
                                    [&active]() -> bool { return active; });

  fml::AutoResetWaitableEvent latch;
  std::atomic_bool task1_called = false;
  runner.PostTask([&]() {
    task1_called.store(true);
    latch.Signal();
  });
  latch.Wait();

  active.store(false);

  std::atomic_bool task2_called = false;
  runner.PostTask([&]() { task2_called.store(true); });

  thread.GetTaskRunner()->PostTask([&]() { latch.Signal(); });
  latch.Wait();

  thread.Join();

  EXPECT_TRUE(task1_called.load());
  EXPECT_FALSE(task2_called.load());
}

TEST(TaskRunnerPreemptionTests, RunsInlineWhenAlreadyOnTargetThread) {
  fml::Thread platform_thread("platform");
  const auto platform_runner = platform_thread.GetTaskRunner();

  fml::AutoResetWaitableEvent done;
  bool task_ran = false;
  PreemptibleWaitResult result = PreemptibleWaitResult::kPreempted;

  platform_runner->PostTask([&]() {
    result = TaskRunner::RunNowOrPostTaskAndWaitWithPreemption(
        platform_runner, [&]() { task_ran = true; });
    done.Signal();
  });
  done.Wait();

  EXPECT_EQ(result, PreemptibleWaitResult::kCompleted);
  EXPECT_TRUE(task_ran);
}

TEST(TaskRunnerPreemptionTests, CompletesCrossThreadWhenNotPreempted) {
  fml::Thread platform_thread("platform");
  fml::Thread raster_thread("raster");
  const auto platform_runner = platform_thread.GetTaskRunner();
  const auto raster_runner = raster_thread.GetTaskRunner();

  fml::AutoResetWaitableEvent done;
  std::thread::id executing_thread_id;
  std::thread::id platform_thread_id;
  platform_runner->PostTask(
      [&]() { platform_thread_id = std::this_thread::get_id(); });

  PreemptibleWaitResult result = PreemptibleWaitResult::kPreempted;
  raster_runner->PostTask([&]() {
    result = TaskRunner::RunNowOrPostTaskAndWaitWithPreemption(
        platform_runner,
        [&]() { executing_thread_id = std::this_thread::get_id(); });
    done.Signal();
  });
  done.Wait();

  EXPECT_EQ(result, PreemptibleWaitResult::kCompleted);
  EXPECT_EQ(executing_thread_id, platform_thread_id);
}

TEST(TaskRunnerPreemptionTests,
     AutomaticPreemptionViaWaitableEventCancelsPendingTask) {
  fml::Thread platform_thread("platform");
  fml::Thread raster_thread("raster");
  const auto platform_runner = platform_thread.GetTaskRunner();
  const auto raster_runner = raster_thread.GetTaskRunner();

  fml::AutoResetWaitableEvent raster_ready;
  fml::AutoResetWaitableEvent release_raster;
  fml::AutoResetWaitableEvent all_done;

  std::atomic_bool cancelled_task_ran{false};
  PreemptibleWaitResult wait_result = PreemptibleWaitResult::kCompleted;

  raster_runner->PostTask([&]() {
    raster_ready.Signal();
    release_raster.Wait();

    // Target (platform) thread is currently blocked inside WaitableEvent::Wait
    // waiting for a subsequent raster task. This wait must be immediately
    // preempted and the task must be cancelled.
    wait_result = TaskRunner::RunNowOrPostTaskAndWaitWithPreemption(
        platform_runner, [&]() { cancelled_task_ran.store(true); },
        PreemptionAction::kCancelTask);
  });
  raster_ready.Wait();

  platform_runner->PostTask([&]() {
    // Platform thread blocks on a raster task using AutoResetWaitableEvent
    // (without any manual ScopedTaskRunnerWaitPreemption).
    fml::AutoResetWaitableEvent raster_latch;
    raster_runner->PostTask([&]() { raster_latch.Signal(); });
    raster_latch.Wait();
    all_done.Signal();
  });

  while (!platform_runner->GetWaitPreemptor()->IsPreempted()) {
    std::this_thread::yield();
  }
  release_raster.Signal();
  all_done.Wait();

  // Drain any remaining tasks on the platform thread.
  fml::AutoResetWaitableEvent drained;
  platform_runner->PostTask([&]() { drained.Signal(); });
  drained.Wait();

  EXPECT_EQ(wait_result, PreemptibleWaitResult::kPreempted);
  EXPECT_FALSE(cancelled_task_ran.load());
}

TEST(TaskRunnerPreemptionTests,
     AutomaticPreemptionViaCountDownLatchWakesInFlightWaitAndCancelsTask) {
  fml::Thread platform_thread("platform");
  fml::Thread raster_thread("raster");
  const auto platform_runner = platform_thread.GetTaskRunner();
  const auto raster_runner = raster_thread.GetTaskRunner();

  fml::AutoResetWaitableEvent platform_occupied;
  fml::AutoResetWaitableEvent all_done;
  std::atomic_bool cancelled_task_ran{false};
  PreemptibleWaitResult wait_result = PreemptibleWaitResult::kCompleted;

  platform_runner->PostTask([&]() {
    platform_occupied.Signal();
    while (!platform_runner->GetWaitPreemptor()->HasWaiters()) {
      std::this_thread::yield();
    }

    // Platform thread enters CountDownLatch::Wait() targeting the raster
    // thread while the raster thread is asleep inside
    // RunNowOrPostTaskAndWaitWithPreemption.
    fml::CountDownLatch latch(1);
    raster_runner->PostTask([&]() { latch.CountDown(); });
    latch.Wait();
    all_done.Signal();
  });
  platform_occupied.Wait();

  auto captured_token = std::make_shared<int>(42);
  std::weak_ptr<int> weak_token = captured_token;
  bool token_expired_on_return = false;
  raster_runner->PostTask([&]() {
    fml::closure task = [token = std::move(captured_token),
                         &cancelled_task_ran]() {
      (void)token;
      cancelled_task_ran.store(true);
    };
    wait_result = TaskRunner::RunNowOrPostTaskAndWaitWithPreemption(
        platform_runner, task, PreemptionAction::kCancelTask);
    task = nullptr;
    token_expired_on_return = weak_token.expired();
  });

  all_done.Wait();
  EXPECT_TRUE(token_expired_on_return);

  fml::AutoResetWaitableEvent drained;
  platform_runner->PostTask([&]() { drained.Signal(); });
  drained.Wait();

  EXPECT_EQ(wait_result, PreemptibleWaitResult::kPreempted);
  EXPECT_FALSE(cancelled_task_ran.load());
}

TEST(TaskRunnerPreemptionTests,
     RunAsynchronouslyActionExecutesTaskAfterTargetThreadUnblocks) {
  fml::Thread platform_thread("platform");
  fml::Thread raster_thread("raster");
  const auto platform_runner = platform_thread.GetTaskRunner();
  const auto raster_runner = raster_thread.GetTaskRunner();

  fml::AutoResetWaitableEvent platform_occupied;
  fml::AutoResetWaitableEvent all_done;
  auto async_task_ran = std::make_shared<std::atomic_bool>(false);
  PreemptibleWaitResult wait_result = PreemptibleWaitResult::kCompleted;

  platform_runner->PostTask([&]() {
    platform_occupied.Signal();
    while (!platform_runner->GetWaitPreemptor()->HasWaiters()) {
      std::this_thread::yield();
    }

    fml::ManualResetWaitableEvent raster_event;
    raster_runner->PostTask([&]() { raster_event.Signal(); });
    raster_event.Wait();
    all_done.Signal();
  });
  platform_occupied.Wait();

  raster_runner->PostTask([&]() {
    wait_result = TaskRunner::RunNowOrPostTaskAndWaitWithPreemption(
        platform_runner, [async_task_ran]() { async_task_ran->store(true); },
        PreemptionAction::kRunAsynchronously);
  });

  all_done.Wait();

  // Drain the platform thread queue; the kRunAsynchronously task must run.
  fml::AutoResetWaitableEvent drained;
  platform_runner->PostTask([&]() { drained.Signal(); });
  drained.Wait();

  EXPECT_EQ(wait_result, PreemptibleWaitResult::kPreempted);
  EXPECT_TRUE(async_task_ran->load());
}

TEST(TaskRunnerPreemptionTests,
     ContendedSyncSwitchSetSwitchPreemptsInFlightWait) {
  fml::Thread platform_thread("platform");
  fml::Thread raster_thread("raster");
  const auto platform_runner = platform_thread.GetTaskRunner();
  const auto raster_runner = raster_thread.GetTaskRunner();

  fml::SyncSwitch sync_switch(false);
  fml::AutoResetWaitableEvent platform_occupied;
  fml::AutoResetWaitableEvent all_done;
  std::atomic_bool cancelled_task_ran{false};
  PreemptibleWaitResult wait_result = PreemptibleWaitResult::kCompleted;

  platform_runner->PostTask([&]() {
    platform_occupied.Signal();
    while (!platform_runner->GetWaitPreemptor()->HasWaiters()) {
      std::this_thread::yield();
    }

    // Platform thread attempts to acquire unique lock on sync_switch while
    // raster_thread holds the shared lock in sync_switch.Execute() and is
    // waiting on platform_runner. Contended SetSwitch must enter preemption
    // and break the deadlock.
    sync_switch.SetSwitch(true);
    all_done.Signal();
  });
  platform_occupied.Wait();

  raster_runner->PostTask([&]() {
    sync_switch.Execute(fml::SyncSwitch::Handlers().SetIfFalse([&]() {
      wait_result = TaskRunner::RunNowOrPostTaskAndWaitWithPreemption(
          platform_runner, [&]() { cancelled_task_ran.store(true); },
          PreemptionAction::kCancelTask);
    }));
  });

  all_done.Wait();

  fml::AutoResetWaitableEvent drained;
  platform_runner->PostTask([&]() { drained.Signal(); });
  drained.Wait();

  EXPECT_EQ(wait_result, PreemptibleWaitResult::kPreempted);
  EXPECT_FALSE(cancelled_task_ran.load());

  bool switch_value = false;
  sync_switch.Execute(fml::SyncSwitch::Handlers().SetIfTrue(
      [&]() { switch_value = true; }));
  EXPECT_TRUE(switch_value);
}

TEST(TaskRunnerPreemptionTests,
     AlreadySignaledWaitableEventDoesNotPreemptConcurrentWait) {
  fml::Thread platform_thread("platform");
  fml::Thread raster_thread("raster");
  const auto platform_runner = platform_thread.GetTaskRunner();
  const auto raster_runner = raster_thread.GetTaskRunner();

  fml::AutoResetWaitableEvent platform_occupied;
  fml::AutoResetWaitableEvent raster_done;
  std::atomic_bool task_ran{false};
  PreemptibleWaitResult wait_result = PreemptibleWaitResult::kPreempted;

  platform_runner->PostTask([&]() {
    platform_occupied.Signal();
    while (!platform_runner->GetWaitPreemptor()->HasWaiters()) {
      std::this_thread::yield();
    }

    // Waiting on already-signaled events on the platform thread must NOT pulse
    // preemption or cancel the in-flight wait from the raster thread.
    fml::AutoResetWaitableEvent auto_event;
    auto_event.Signal();
    auto_event.Wait();
    auto_event.Signal();
    EXPECT_FALSE(auto_event.WaitWithTimeout(fml::TimeDelta::FromSeconds(1)));

    fml::ManualResetWaitableEvent manual_event;
    manual_event.Signal();
    manual_event.Wait();
    EXPECT_FALSE(manual_event.WaitWithTimeout(fml::TimeDelta::FromSeconds(1)));

    fml::CountDownLatch zero_latch(0);
    zero_latch.Wait();

    EXPECT_FALSE(platform_runner->GetWaitPreemptor()->IsPreempted());
  });
  platform_occupied.Wait();

  raster_runner->PostTask([&]() {
    wait_result = TaskRunner::RunNowOrPostTaskAndWaitWithPreemption(
        platform_runner, [&]() { task_ran.store(true); },
        PreemptionAction::kCancelTask);
    raster_done.Signal();
  });

  raster_done.Wait();
  EXPECT_EQ(wait_result, PreemptibleWaitResult::kCompleted);
  EXPECT_TRUE(task_ran.load());
}

TEST(TaskRunnerPreemptionTests,
     MultipleTaskRunnersWrappingSameMessageLoopShareWaitPreemptor) {
  fml::Thread thread("shared_loop_thread");
  const auto primary_runner = thread.GetTaskRunner();

  fml::AutoResetWaitableEvent done;
  primary_runner->PostTask([&]() {
    EXPECT_EQ(primary_runner->GetWaitPreemptor().get(),
              TaskRunnerWaitPreemptor::GetCurrentThreadPreemptor());
    auto loop_impl = fml::MessageLoopImpl::Create();
    auto runner_a = fml::MakeRefCounted<fml::TaskRunner>(loop_impl);
    auto runner_b = fml::MakeRefCounted<fml::TaskRunner>(loop_impl);
    EXPECT_EQ(runner_a->GetWaitPreemptor(), runner_b->GetWaitPreemptor());
    EXPECT_EQ(runner_a->GetWaitPreemptor(), loop_impl->GetWaitPreemptor());
    loop_impl->DoTerminate();
    done.Signal();
  });
  done.Wait();
}

TEST(TaskRunnerPreemptionTests, TransitivePreemptionAcrossThreeThreads) {
  fml::Thread thread_1("thread_1");
  fml::Thread thread_2("thread_2");
  fml::Thread thread_3("thread_3");
  const auto runner_1 = thread_1.GetTaskRunner();
  const auto runner_2 = thread_2.GetTaskRunner();
  const auto runner_3 = thread_3.GetTaskRunner();

  fml::AutoResetWaitableEvent t1_occupied;
  fml::AutoResetWaitableEvent all_done;
  std::atomic_bool t3_finished{false};
  std::atomic_bool t3_to_t2_task_ran{false};
  std::atomic_bool t2_to_t1_task_ran{false};
  PreemptibleWaitResult t3_wait_result = PreemptibleWaitResult::kCompleted;
  PreemptibleWaitResult t2_wait_result = PreemptibleWaitResult::kPreempted;

  // T1 is busy while T2 waits on T1 via RunNowOrPostTaskAndWaitWithPreemption.
  // Because T2 is blocked inside RunNowOrPostTaskAndWaitWithPreemption, T2's
  // own preemptor is marked preempted, so when T3 calls
  // RunNowOrPostTaskAndWaitWithPreemption(runner_2), T3's wait on T2 is
  // immediately preempted even though T1 itself has not entered a WaitableEvent.
  runner_1->PostTask([&]() {
    t1_occupied.Signal();
    while (!runner_1->GetWaitPreemptor()->HasWaiters()) {
      std::this_thread::yield();
    }
    EXPECT_TRUE(runner_2->GetWaitPreemptor()->IsPreempted());

    runner_3->PostTask([&]() {
      t3_wait_result = TaskRunner::RunNowOrPostTaskAndWaitWithPreemption(
          runner_2, [&]() { t3_to_t2_task_ran.store(true); },
          PreemptionAction::kCancelTask);
      t3_finished.store(true);
    });
    while (!t3_finished.load()) {
      std::this_thread::yield();
    }
  });
  t1_occupied.Wait();

  runner_2->PostTask([&]() {
    t2_wait_result = TaskRunner::RunNowOrPostTaskAndWaitWithPreemption(
        runner_1, [&]() { t2_to_t1_task_ran.store(true); },
        PreemptionAction::kCancelTask);
    all_done.Signal();
  });

  all_done.Wait();

  fml::AutoResetWaitableEvent t2_drained;
  runner_2->PostTask([&]() { t2_drained.Signal(); });
  t2_drained.Wait();

  EXPECT_EQ(t3_wait_result, PreemptibleWaitResult::kPreempted);
  EXPECT_FALSE(t3_to_t2_task_ran.load());
  EXPECT_EQ(t2_wait_result, PreemptibleWaitResult::kCompleted);
  EXPECT_TRUE(t2_to_t1_task_ran.load());
}

TEST(TaskRunnerPreemptionTests,
     RunAsynchronouslyPreemptsEvenAfterTaskStartsRunning) {
  fml::Thread platform_thread("platform");
  fml::Thread raster_thread("raster");
  const auto platform_runner = platform_thread.GetTaskRunner();
  const auto raster_runner = raster_thread.GetTaskRunner();

  fml::AutoResetWaitableEvent unblock_platform_task;
  fml::AutoResetWaitableEvent raster_wait_returned;
  fml::AutoResetWaitableEvent platform_task_finished;
  PreemptibleWaitResult wait_result = PreemptibleWaitResult::kCompleted;

  // Raster posts a kRunAsynchronously task to Platform. Once that task starts
  // running on Platform (Phase::kRunning) and enters a blocking WaitableEvent,
  // Raster must immediately wake up with kPreempted rather than deadlocking.
  raster_runner->PostTask([&]() {
    wait_result = TaskRunner::RunNowOrPostTaskAndWaitWithPreemption(
        platform_runner,
        [&]() {
          unblock_platform_task.Wait();
          platform_task_finished.Signal();
        },
        PreemptionAction::kRunAsynchronously);
    raster_wait_returned.Signal();
  });

  raster_wait_returned.Wait();
  EXPECT_EQ(wait_result, PreemptibleWaitResult::kPreempted);

  unblock_platform_task.Signal();
  platform_task_finished.Wait();
}

}  // namespace testing
}  // namespace fml
