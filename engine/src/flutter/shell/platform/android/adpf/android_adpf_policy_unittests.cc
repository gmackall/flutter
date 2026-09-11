// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/adpf/android_adpf_policy.h"

#include "gtest/gtest.h"

namespace flutter {
namespace testing {

namespace {

AndroidDeviceIdentity MakeIdentity() {
  AndroidDeviceIdentity identity;
  identity.manufacturer = "acme";
  identity.brand = "acme";
  identity.device = "widget";
  identity.model = "widget pro";
  identity.build_id = "ap4a.250105.002";
  identity.api_level = 36;
  return identity;
}

AndroidAdpfDenylistEntry MakeEntry() {
  return AndroidAdpfDenylistEntry{
      .id = "acme-widget-36",
      .tracking_issue = "https://github.com/flutter/flutter/issues/0",
      .manufacturer = "acme",
      .device = "widget",
      .min_api_level = 36,
      .max_api_level = 36,
  };
}

}  // namespace

TEST(AutomaticAdpfPolicy, DefaultsFollowRolloutStage) {
  Settings settings;
  auto policy = AutomaticAdpfPolicy::FromSettings(settings);
  EXPECT_EQ(policy.enabled, kAndroidAdpfEnabledByDefault);
  EXPECT_FALSE(policy.ignore_denylist);
}

TEST(AutomaticAdpfPolicy, ExplicitSettingsWin) {
  Settings settings;
  settings.enable_android_adpf = true;
  settings.android_adpf_ignore_denylist = true;
  auto policy = AutomaticAdpfPolicy::FromSettings(settings);
  EXPECT_TRUE(policy.enabled);
  EXPECT_TRUE(policy.ignore_denylist);

  settings.enable_android_adpf = false;
  EXPECT_FALSE(AutomaticAdpfPolicy::FromSettings(settings).enabled);
}

TEST(AndroidDeviceIdentity, NormalizesCaseAndWhitespace) {
  EXPECT_EQ(AndroidDeviceIdentity::Normalize("  Acme Widget\t"), "acme widget");
  EXPECT_EQ(AndroidDeviceIdentity::Normalize(""), "");
}

TEST(AndroidAdpfDenylist, MatchesExactNormalizedIdentifiers) {
  auto identity = MakeIdentity();
  auto entry = MakeEntry();
  EXPECT_TRUE(entry.Matches(identity));

  auto match = MatchAndroidAdpfDenylist(identity, {entry});
  ASSERT_TRUE(match.has_value());
  EXPECT_EQ(match.value(), "acme-widget-36");
}

TEST(AndroidAdpfDenylist, DoesNotMatchNeighboringDevices) {
  auto entry = MakeEntry();

  auto other_device = MakeIdentity();
  other_device.device = "widget2";
  EXPECT_FALSE(entry.Matches(other_device));

  auto other_manufacturer = MakeIdentity();
  other_manufacturer.manufacturer = "acme corp";
  EXPECT_FALSE(entry.Matches(other_manufacturer));
}

TEST(AndroidAdpfDenylist, DoesNotMatchNeighboringBuilds) {
  auto entry = MakeEntry();

  auto older = MakeIdentity();
  older.api_level = 35;
  EXPECT_FALSE(entry.Matches(older));

  auto newer = MakeIdentity();
  newer.api_level = 37;
  EXPECT_FALSE(entry.Matches(newer));

  auto open_ended = MakeEntry();
  open_ended.max_api_level = 0;
  EXPECT_TRUE(open_ended.Matches(newer));
  EXPECT_FALSE(open_ended.Matches(older));
}

TEST(AndroidAdpfDenylist, BuildIdPrefixIsBounded) {
  auto entry = MakeEntry();
  entry.build_id_prefix = "ap4a.250105";
  EXPECT_TRUE(entry.Matches(MakeIdentity()));

  auto other_build = MakeIdentity();
  other_build.build_id = "ap4a.250205.001";
  EXPECT_FALSE(entry.Matches(other_build));

  auto missing_build = MakeIdentity();
  missing_build.build_id.clear();
  EXPECT_FALSE(entry.Matches(missing_build));
}

TEST(AndroidAdpfDenylist, MissingMetadataNeverMatches) {
  auto entry = MakeEntry();

  auto no_device = MakeIdentity();
  no_device.device.clear();
  EXPECT_FALSE(entry.Matches(no_device));

  auto no_manufacturer = MakeIdentity();
  no_manufacturer.manufacturer.clear();
  EXPECT_FALSE(entry.Matches(no_manufacturer));

  auto no_api_level = MakeIdentity();
  no_api_level.api_level = 0;
  EXPECT_FALSE(entry.Matches(no_api_level));

  AndroidDeviceIdentity empty;
  EXPECT_FALSE(entry.Matches(empty));
}

TEST(AndroidAdpfDenylist, UnscopedEntriesMatchNothing) {
  AndroidAdpfDenylistEntry unscoped{
      .id = "everything-36",
      .tracking_issue = "https://github.com/flutter/flutter/issues/0",
      .min_api_level = 36,
  };
  EXPECT_FALSE(unscoped.IsScoped());
  EXPECT_FALSE(unscoped.Matches(MakeIdentity()));
  EXPECT_FALSE(
      MatchAndroidAdpfDenylist(MakeIdentity(), {unscoped}).has_value());
}

TEST(AndroidAdpfDenylist, FirstMatchingRuleWins) {
  auto broad = MakeEntry();
  broad.id = "acme-all";
  broad.device = {};
  auto narrow = MakeEntry();
  auto match = MatchAndroidAdpfDenylist(MakeIdentity(), {narrow, broad});
  ASSERT_TRUE(match.has_value());
  EXPECT_EQ(match.value(), "acme-widget-36");
}

TEST(AndroidAdpfDenylist, CompiledEntriesAreReviewable) {
  for (const auto& entry : GetAndroidAdpfDenylist()) {
    EXPECT_TRUE(entry.IsScoped()) << entry.id;
    EXPECT_FALSE(entry.id.empty());
    EXPECT_FALSE(entry.tracking_issue.empty()) << entry.id;
    EXPECT_EQ(entry.manufacturer,
              AndroidDeviceIdentity::Normalize(entry.manufacturer))
        << entry.id;
    EXPECT_EQ(entry.device, AndroidDeviceIdentity::Normalize(entry.device))
        << entry.id;
    EXPECT_EQ(entry.model, AndroidDeviceIdentity::Normalize(entry.model))
        << entry.id;
  }
}

}  // namespace testing
}  // namespace flutter
