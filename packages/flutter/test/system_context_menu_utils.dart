// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

/// @docImport 'package:flutter/widgets.dart';
library;

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart' show FlutterError;

/// Returns a [SystemContextMenuItemData] of the correct subclass given its
/// json data.
SystemContextMenuItemData systemContextMenuItemDataFromJson(Map<String, dynamic> json) {
  final type = json['type'] as String?;
  final title = json['title'] as String?;
  return switch (type) {
    'copy' => const SystemContextMenuItemDataCopy(),
    'cut' => const SystemContextMenuItemDataCut(),
    'paste' => const SystemContextMenuItemDataPaste(),
    'selectAll' => const SystemContextMenuItemDataSelectAll(),
    'searchWeb' => SystemContextMenuItemDataSearchWeb(title: title!),
    'share' => SystemContextMenuItemDataShare(title: title!),
    'lookUp' => SystemContextMenuItemDataLookUp(title: title!),
    'captureTextFromCamera' => const SystemContextMenuItemDataLiveText(),
    'custom' => SystemContextMenuItemDataCustom(
      id: json['id'] as String?,
      title: title!,
      onPressed: () {},
    ),
    _ => throw FlutterError('Invalid json for SystemContextMenuItemData.type $type.'),
  };
}
