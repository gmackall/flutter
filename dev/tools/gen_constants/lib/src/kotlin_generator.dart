// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'ir.dart';
import 'language_generator.dart';

/// Renders a [ConstClass] as a Kotlin `object` of `const val`s.
class KotlinGenerator implements LanguageGenerator {
  KotlinGenerator({
    required this.outputPath,
    required this.package,
    required this.sourceOfTruthPath,
    required this.generatorPath,
  });

  @override
  final String outputPath;

  /// The Kotlin package declaration, e.g. `com.flutter.gradle`.
  final String package;

  /// The repo-relative path of the Dart file this was generated from.
  final String sourceOfTruthPath;

  /// The repo-relative path of the generator, so readers know what to re-run.
  final String generatorPath;

  @override
  String generate(ConstClass constClass) {
    final buffer = StringBuffer()
      ..writeln('// Copyright 2014 The Flutter Authors. All rights reserved.')
      ..writeln('// Use of this source code is governed by a BSD-style license that can be')
      ..writeln('// found in the LICENSE file.')
      ..writeln('//')
      ..writeln('// DO NOT EDIT. Generated from')
      ..writeln('//   $sourceOfTruthPath')
      ..writeln('// by $generatorPath.')
      ..writeln('// Edit the Dart file and re-run the generator; a CI check enforces that doing')
      ..writeln('// so produces no diff.')
      ..writeln()
      ..writeln('package $package')
      ..writeln();
    final String? classDoc = _kdoc(constClass.documentation, '');
    if (classDoc != null) {
      buffer.write(classDoc);
    }
    buffer.writeln('object ${constClass.name} {');
    for (var i = 0; i < constClass.fields.length; i++) {
      final ConstField field = constClass.fields[i];
      if (i > 0) {
        buffer.writeln();
      }
      final String? fieldDoc = _kdoc(field.documentation, '    ');
      if (fieldDoc != null) {
        buffer.write(fieldDoc);
      }
      buffer.writeln(
        '    const val ${field.screamingSnakeName}: ${_type(field.value)} = ${_literal(field.value)}',
      );
    }
    buffer.writeln('}');
    return buffer.toString();
  }

  /// The Kotlin type name for [value].
  static String _type(Object value) => switch (value) {
    String() => 'String',
    int() => 'Int',
    bool() => 'Boolean',
    _ => throw StateError('Unsupported constant type ${value.runtimeType}.'),
  };

  /// Renders [value] as a Kotlin literal.
  static String _literal(Object value) => switch (value) {
    final String string => '"${_escape(string)}"',
    final int number => '$number',
    final bool boolean => '$boolean',
    _ => throw StateError('Unsupported constant type ${value.runtimeType}.'),
  };

  /// Escapes [value] for a Kotlin double-quoted string literal.
  ///
  /// Kotlin interpolates `$`, so it is escaped even though Dart's evaluated
  /// value contains it literally.
  static String _escape(String value) {
    final buffer = StringBuffer();
    for (final int rune in value.runes) {
      buffer.write(switch (String.fromCharCode(rune)) {
        r'\' => r'\\',
        '"' => r'\"',
        r'$' => r'\$',
        '\n' => r'\n',
        '\r' => r'\r',
        '\t' => r'\t',
        final String character => character,
      });
    }
    return buffer.toString();
  }

  /// Renders [documentation] as a KDoc comment indented by [indent].
  static String? _kdoc(String? documentation, String indent) {
    if (documentation == null || documentation.isEmpty) {
      return null;
    }
    final List<String> lines = documentation.split('\n');
    if (lines.length == 1) {
      return '$indent/** ${lines.single} */\n';
    }
    final buffer = StringBuffer('$indent/**\n');
    for (final line in lines) {
      buffer.writeln(line.isEmpty ? '$indent *' : '$indent * $line');
    }
    buffer.writeln('$indent */');
    return buffer.toString();
  }
}
