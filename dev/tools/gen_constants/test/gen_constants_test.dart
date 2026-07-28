// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:io';

import 'package:gen_constants/gen_constants.dart';
import 'package:test/test.dart';

KotlinGenerator _generator() => KotlinGenerator(
  outputPath: 'out/Generated.kt',
  package: 'com.example',
  sourceOfTruthPath: 'lib/constants.dart',
  generatorPath: 'bin/gen_constants.dart',
);

void main() {
  group('ConstField', () {
    test('converts camelCase to SCREAMING_SNAKE_CASE', () {
      expect(
        const ConstField(name: 'localEngineRepo', value: 'x').screamingSnakeName,
        'LOCAL_ENGINE_REPO',
      );
      expect(const ConstField(name: 'target', value: 'x').screamingSnakeName, 'TARGET');
      expect(const ConstField(name: 'splitPerAbi', value: 'x').screamingSnakeName, 'SPLIT_PER_ABI');
    });
  });

  group('KotlinGenerator', () {
    test('renders each supported type with its Kotlin type', () {
      final String kotlin = _generator().generate(
        const ConstClass(
          name: 'Values',
          fields: <ConstField>[
            ConstField(name: 'someString', value: 'hello'),
            ConstField(name: 'someInt', value: 7),
            ConstField(name: 'someBool', value: true),
          ],
        ),
      );
      expect(kotlin, contains('const val SOME_STRING: String = "hello"'));
      expect(kotlin, contains('const val SOME_INT: Int = 7'));
      expect(kotlin, contains('const val SOME_BOOL: Boolean = true'));
    });

    test('emits the license header, banner, and package', () {
      final String kotlin = _generator().generate(
        const ConstClass(
          name: 'Values',
          fields: <ConstField>[ConstField(name: 'a', value: 'b')],
        ),
      );
      expect(kotlin, startsWith('// Copyright 2014 The Flutter Authors.'));
      expect(kotlin, contains('DO NOT EDIT'));
      expect(kotlin, contains('lib/constants.dart'));
      expect(kotlin, contains('package com.example'));
      expect(kotlin, endsWith('}\n'));
    });

    test('escapes characters that are special in Kotlin but not in Dart', () {
      // A Dart value containing a dollar sign, a double quote, and a backslash.
      // Copying Dart source text across would produce invalid or wrong Kotlin.
      final String kotlin = _generator().generate(
        const ConstClass(
          name: 'Values',
          fields: <ConstField>[ConstField(name: 'tricky', value: r'a$b"c\d')],
        ),
      );
      expect(kotlin, contains(r'const val TRICKY: String = "a\$b\"c\\d"'));
    });

    test('renders doc comments as KDoc', () {
      final String kotlin = _generator().generate(
        const ConstClass(
          name: 'Values',
          documentation: 'Class level docs.',
          fields: <ConstField>[
            ConstField(name: 'a', value: 'b', documentation: 'One line.'),
            ConstField(name: 'c', value: 'd', documentation: 'First line.\nSecond line.'),
          ],
        ),
      );
      expect(kotlin, contains('/** Class level docs. */'));
      expect(kotlin, contains('    /** One line. */'));
      expect(kotlin, contains('    /**\n     * First line.\n     * Second line.\n     */'));
    });
  });

  group('parseConstantsFile', () {
    late Directory tempDir;

    setUp(() {
      tempDir = Directory.systemTemp.createTempSync('gen_constants_test');
    });

    tearDown(() {
      tempDir.deleteSync(recursive: true);
    });

    File writeSource(String contents) {
      final file = File('${tempDir.path}${Platform.pathSeparator}constants.dart');
      file.writeAsStringSync(contents);
      return file;
    }

    test('reads evaluated values, not source text', () async {
      // The value is a const expression, not a literal, and uses a single-quoted
      // Dart string. Both are handled because the element model evaluates it.
      final File source = writeSource(r'''
class Values {
  static const String prefix = 'io.flutter.';
  static const String full = '${prefix}Thing';
  static const int count = 42;
  static const bool enabled = false;
}
''');
      final List<ConstClass> classes = await parseConstantsFile(source.path);
      expect(classes, hasLength(1));
      final ConstClass values = classes.single;
      expect(values.name, 'Values');
      expect(
        values.fields.map((ConstField f) => f.value).toList(),
        <Object>['io.flutter.', 'io.flutter.Thing', 42, false],
      );
    });

    test('captures doc comments with the markers stripped', () async {
      final File source = writeSource('''
/// Some values.
class Values {
  /// The name.
  static const String name = 'n';
}
''');
      final List<ConstClass> classes = await parseConstantsFile(source.path);
      expect(classes.single.documentation, 'Some values.');
      expect(classes.single.fields.single.documentation, 'The name.');
    });

    test('throws on an unsupported type rather than skipping it', () async {
      final File source = writeSource('''
class Values {
  static const List<String> items = <String>['a'];
}
''');
      await expectLater(
        parseConstantsFile(source.path),
        throwsA(
          isA<UnsupportedConstantError>().having(
            (UnsupportedConstantError e) => e.message,
            'message',
            allOf(contains('Values.items'), contains('unsupported type')),
          ),
        ),
      );
    });

    test('ignores non-static and non-const fields', () async {
      final File source = writeSource('''
class Values {
  static const String kept = 'k';
  static String notConst = 'n';
  final String instanceField = 'i';
}
''');
      final List<ConstClass> classes = await parseConstantsFile(source.path);
      expect(classes.single.fields.map((ConstField f) => f.name), <String>['kept']);
    });
  });
}
