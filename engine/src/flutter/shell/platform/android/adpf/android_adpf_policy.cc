// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/adpf/android_adpf_policy.h"

#include <algorithm>
#include <cctype>
#include <cstdlib>

#include "flutter/fml/build_config.h"

#if FML_OS_ANDROID
#include <sys/system_properties.h>
#endif  // FML_OS_ANDROID

namespace flutter {

namespace {

#if FML_OS_ANDROID
std::string ReadSystemProperty(const char* name) {
  char value[PROP_VALUE_MAX] = {};
  if (__system_property_get(name, value) <= 0) {
    return "";
  }
  return AndroidDeviceIdentity::Normalize(value);
}
#endif  // FML_OS_ANDROID

// A constrained predicate must equal a non-empty identity field.
bool FieldMatches(std::string_view predicate, std::string_view field) {
  if (predicate.empty()) {
    return true;
  }
  return !field.empty() && predicate == field;
}

bool PrefixMatches(std::string_view predicate, std::string_view field) {
  if (predicate.empty()) {
    return true;
  }
  return !field.empty() && field.starts_with(predicate);
}

}  // namespace

AutomaticAdpfPolicy AutomaticAdpfPolicy::FromSettings(
    const Settings& settings) {
  AutomaticAdpfPolicy policy;
  policy.enabled =
      settings.enable_android_adpf.value_or(kAndroidAdpfEnabledByDefault);
  policy.ignore_denylist = settings.android_adpf_ignore_denylist;
  return policy;
}

std::string AndroidDeviceIdentity::Normalize(std::string_view value) {
  auto is_space = [](unsigned char c) { return std::isspace(c) != 0; };
  while (!value.empty() && is_space(value.front())) {
    value.remove_prefix(1);
  }
  while (!value.empty() && is_space(value.back())) {
    value.remove_suffix(1);
  }
  std::string result(value);
  std::transform(result.begin(), result.end(), result.begin(),
                 [](unsigned char c) { return std::tolower(c); });
  return result;
}

AndroidDeviceIdentity AndroidDeviceIdentity::FromSystemProperties() {
  AndroidDeviceIdentity identity;
#if FML_OS_ANDROID
  identity.manufacturer = ReadSystemProperty("ro.product.manufacturer");
  identity.brand = ReadSystemProperty("ro.product.brand");
  identity.device = ReadSystemProperty("ro.product.device");
  identity.model = ReadSystemProperty("ro.product.model");
  identity.build_id = ReadSystemProperty("ro.build.id");
  identity.api_level =
      std::atoi(ReadSystemProperty("ro.build.version.sdk").c_str());
#endif  // FML_OS_ANDROID
  return identity;
}

bool AndroidAdpfDenylistEntry::IsScoped() const {
  return !manufacturer.empty() || !brand.empty() || !device.empty() ||
         !model.empty();
}

bool AndroidAdpfDenylistEntry::Matches(
    const AndroidDeviceIdentity& identity) const {
  if (!IsScoped()) {
    return false;
  }
  if (min_api_level > 0 && identity.api_level < min_api_level) {
    return false;
  }
  if (max_api_level > 0 &&
      (identity.api_level == 0 || identity.api_level > max_api_level)) {
    return false;
  }
  return FieldMatches(manufacturer, identity.manufacturer) &&
         FieldMatches(brand, identity.brand) &&
         FieldMatches(device, identity.device) &&
         FieldMatches(model, identity.model) &&
         PrefixMatches(build_id_prefix, identity.build_id);
}

const std::vector<AndroidAdpfDenylistEntry>& GetAndroidAdpfDenylist() {
  // Entries are added only for reproducible vendor defects, with a tracking
  // issue that records the reproduction, the evidence that disabling this
  // integration resolves the regression, and the retest condition for
  // removal. Match the narrowest reliable combination. Example:
  //
  //   {
  //       .id = "acme-widget-36",
  //       .tracking_issue = "https://github.com/flutter/flutter/issues/NNNNN",
  //       .manufacturer = "acme",
  //       .device = "widget",
  //       .min_api_level = 36,
  //       .max_api_level = 36,
  //   },
  static const std::vector<AndroidAdpfDenylistEntry> entries = {};
  return entries;
}

std::optional<std::string_view> MatchAndroidAdpfDenylist(
    const AndroidDeviceIdentity& identity,
    const std::vector<AndroidAdpfDenylistEntry>& entries) {
  for (const auto& entry : entries) {
    if (entry.Matches(identity)) {
      return entry.id;
    }
  }
  return std::nullopt;
}

}  // namespace flutter
