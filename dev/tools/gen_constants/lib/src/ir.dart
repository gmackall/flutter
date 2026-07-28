// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

/// A language-agnostic description of the constants parsed from a Dart source
/// of truth.
///
/// Values are the *evaluated* constant values (a [String], [int], or [bool]),
/// never the source text they were written as. Rendering a literal is each
/// language generator's job, so that quoting and escaping follow the rules of
/// the target language rather than Dart's.
library;

/// A single `static const` field.
class ConstField {
  const ConstField({required this.name, required this.value, this.documentation});

  /// The Dart field name, e.g. `localEngineRepo`.
  ///
  /// Generators derive their own casing from this.
  final String name;

  /// The evaluated value: a [String], [int], or [bool].
  final Object value;

  /// The Dart doc comment, with `///` markers stripped, or null if undocumented.
  final String? documentation;

  /// [name] converted to SCREAMING_SNAKE_CASE, e.g. `LOCAL_ENGINE_REPO`.
  String get screamingSnakeName {
    final buffer = StringBuffer();
    for (var i = 0; i < name.length; i++) {
      final String character = name[i];
      final bool isUpper =
          character.toUpperCase() == character && character.toLowerCase() != character;
      if (isUpper && i > 0) {
        buffer.write('_');
      }
      buffer.write(character.toUpperCase());
    }
    return buffer.toString();
  }
}

/// A class of `static const` fields.
class ConstClass {
  const ConstClass({required this.name, required this.fields, this.documentation});

  /// The Dart class name, reused verbatim as the generated container's name.
  final String name;

  /// The declared fields, in declaration order.
  final List<ConstField> fields;

  /// The Dart doc comment, with `///` markers stripped, or null if undocumented.
  final String? documentation;
}
