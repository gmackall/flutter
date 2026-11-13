// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/fml/synchronization/sync_switch.h"

#include <algorithm>
#include <mutex>

#include "flutter/fml/task_runner.h"

namespace fml {

SyncSwitch::Handlers& SyncSwitch::Handlers::SetIfTrue(
    const std::function<void()>& handler) {
  true_handler = handler;
  return *this;
}

SyncSwitch::Handlers& SyncSwitch::Handlers::SetIfFalse(
    const std::function<void()>& handler) {
  false_handler = handler;
  return *this;
}

SyncSwitch::SyncSwitch(bool value) : value_(value) {}

void SyncSwitch::Execute(const SyncSwitch::Handlers& handlers) const {
  std::shared_lock<std::shared_mutex> lock(mutex_, std::try_to_lock);
  if (!lock.owns_lock()) {
    ScopedTaskRunnerWaitPreemption preempt_guard;
    lock.lock();
  }
  if (value_) {
    handlers.true_handler();
  } else {
    handlers.false_handler();
  }
}

void SyncSwitch::SetSwitch(bool value) {
  {
    std::unique_lock<std::shared_mutex> lock(mutex_, std::try_to_lock);
    if (!lock.owns_lock()) {
      ScopedTaskRunnerWaitPreemption preempt_guard;
      lock.lock();
    }
    value_ = value;
  }
  for (Observer* observer : observers_) {
    observer->OnSyncSwitchUpdate(value);
  }
}

void SyncSwitch::AddObserver(Observer* observer) const {
  std::unique_lock<std::shared_mutex> lock(mutex_, std::try_to_lock);
  if (!lock.owns_lock()) {
    ScopedTaskRunnerWaitPreemption preempt_guard;
    lock.lock();
  }
  if (std::find(observers_.begin(), observers_.end(), observer) ==
      observers_.end()) {
    observers_.push_back(observer);
  }
}

void SyncSwitch::RemoveObserver(Observer* observer) const {
  std::unique_lock<std::shared_mutex> lock(mutex_, std::try_to_lock);
  if (!lock.owns_lock()) {
    ScopedTaskRunnerWaitPreemption preempt_guard;
    lock.lock();
  }
  observers_.erase(std::remove(observers_.begin(), observers_.end(), observer),
                   observers_.end());
}
}  // namespace fml
