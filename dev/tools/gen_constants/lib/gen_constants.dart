// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

/// Generates native copies of constants that are declared once in Dart.
///
/// A Dart class of `static const` fields is the source of truth. The constants
/// are read from the resolved element model as evaluated values, then handed to
/// a [LanguageGenerator] per target language.
library;

export 'src/ir.dart';
export 'src/kotlin_generator.dart';
export 'src/language_generator.dart';
export 'src/parser.dart';
