// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_SHELL_PLATFORM_ANDROID_ADPF_ANDROID_ADPF_POLICY_H_
#define FLUTTER_SHELL_PLATFORM_ANDROID_ADPF_ANDROID_ADPF_POLICY_H_

#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "flutter/common/settings.h"

namespace flutter {

//------------------------------------------------------------------------------
/// @brief      The minimum Android API level at which the complete automatic
///             graphics-pipeline contract (`ASessionCreationConfig`, surface
///             association, automatic CPU and GPU timing) is declared.
///
constexpr int kAndroidAdpfMinimumApiLevel = 36;

//------------------------------------------------------------------------------
/// @brief      Whether automatic ADPF sessions are created when the
///             application expresses no preference. The integration ships
///             behind an experimental opt-in until the acceptance criteria in
///             `docs/engine/Android-Automatic-ADPF.md` are met.
///
constexpr bool kAndroidAdpfEnabledByDefault = false;

//------------------------------------------------------------------------------
/// @brief      The application-level policy for automatic ADPF, resolved from
///             engine settings. A disable request always wins; an enable
///             request never bypasses missing platform support.
///
struct AutomaticAdpfPolicy {
  /// Whether the integration may create sessions at all.
  bool enabled = kAndroidAdpfEnabledByDefault;

  /// Developer override that skips the compiled device denylist. Only
  /// meaningful with `enabled`. Never set from application manifests in
  /// release builds.
  bool ignore_denylist = false;

  static AutomaticAdpfPolicy FromSettings(const Settings& settings);
};

//------------------------------------------------------------------------------
/// @brief      The identity of the device the engine runs on, as used by the
///             denylist. All strings are normalized: trimmed and lower-cased.
///             Fields the platform did not report are empty.
///
struct AndroidDeviceIdentity {
  std::string manufacturer;  // ro.product.manufacturer
  std::string brand;         // ro.product.brand
  std::string device;        // ro.product.device
  std::string model;         // ro.product.model
  std::string build_id;      // ro.build.id
  int api_level = 0;         // ro.build.version.sdk

  static AndroidDeviceIdentity FromSystemProperties();

  static std::string Normalize(std::string_view value);
};

//------------------------------------------------------------------------------
/// @brief      One reviewable exclusion for a demonstrated vendor defect.
///
///             Every string predicate is compared against the normalized
///             identity field for equality (`build_id_prefix` by prefix). An
///             empty predicate is unconstrained. A constrained predicate never
///             matches an identity field the platform left empty, so missing
///             metadata cannot match every device. API-level bounds are
///             inclusive; zero means unbounded.
///
///             An entry must constrain at least one of `manufacturer`, `brand`,
///             `device` or `model`; see `IsScoped()`.
///
struct AndroidAdpfDenylistEntry {
  /// A stable identifier reported in diagnostics, e.g. "vendor-device-36".
  std::string_view id;
  /// The tracking issue that holds the reproduction, the evidence that
  /// disabling this integration resolves the regression, and the removal
  /// condition.
  std::string_view tracking_issue;

  std::string_view manufacturer;
  std::string_view brand;
  std::string_view device;
  std::string_view model;
  std::string_view build_id_prefix;
  int min_api_level = 0;
  int max_api_level = 0;

  bool IsScoped() const;

  bool Matches(const AndroidDeviceIdentity& identity) const;
};

//------------------------------------------------------------------------------
/// @brief      The compiled denylist distributed with the engine.
///
const std::vector<AndroidAdpfDenylistEntry>& GetAndroidAdpfDenylist();

//------------------------------------------------------------------------------
/// @brief      Finds the first entry matching `identity`.
///
/// @return     The matching entry's `id`, or nullopt.
///
std::optional<std::string_view> MatchAndroidAdpfDenylist(
    const AndroidDeviceIdentity& identity,
    const std::vector<AndroidAdpfDenylistEntry>& entries);

}  // namespace flutter

#endif  // FLUTTER_SHELL_PLATFORM_ANDROID_ADPF_ANDROID_ADPF_POLICY_H_
