// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'ir.dart';

/// Renders a [ConstClass] into one target language.
///
/// Implementations must build literals from [ConstField.value] using their own
/// language's quoting and escaping rules. Copying Dart source text across is
/// not safe: Dart's single-quoted strings are `Char` literals in Kotlin, and
/// `$` means interpolation in both languages but needs escaping differently.
abstract class LanguageGenerator {
  /// The repo-relative path of the file this generator writes.
  String get outputPath;

  /// Renders the complete contents of [outputPath].
  String generate(ConstClass constClass);
}
