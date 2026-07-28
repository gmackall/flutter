// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Generates the native copies of constants declared once in Dart.
//
// Usage (from anywhere; paths are resolved relative to this script):
//
//   dart --enable-asserts dev/tools/gen_constants/bin/gen_constants.dart
//
// A CI check in dev/bots/analyze.dart fails if running this produces a diff.

import 'dart:io';

import 'package:gen_constants/gen_constants.dart';
import 'package:path/path.dart' as path;

/// The repo-relative path of this script, named in every generated banner.
const String _generatorPath = 'dev/tools/gen_constants/bin/gen_constants.dart';

/// Each Dart source of truth, and the native files generated from it.
///
/// Add an entry here to bring another set of constants under generation.
List<_GenerationTarget> _targets() => <_GenerationTarget>[
  _GenerationTarget(
    sourceOfTruthPath: path.join(
      'packages',
      'flutter_tools',
      'lib',
      'src',
      'android',
      'gradle_properties.dart',
    ),
    generators: <LanguageGenerator>[
      KotlinGenerator(
        outputPath: path.join(
          'packages',
          'flutter_tools',
          'gradle',
          'src',
          'main',
          'kotlin',
          'GradleProperties.kt',
        ),
        package: 'com.flutter.gradle',
        sourceOfTruthPath: 'packages/flutter_tools/lib/src/android/gradle_properties.dart',
        generatorPath: _generatorPath,
      ),
    ],
  ),
];

Future<void> main() async {
  final String root = _repoRoot();
  for (final _GenerationTarget target in _targets()) {
    final String sourcePath = path.join(root, target.sourceOfTruthPath);
    final List<ConstClass> classes = await parseConstantsFile(sourcePath);
    if (classes.length != 1) {
      stderr.writeln(
        'Expected exactly one class of constants in ${target.sourceOfTruthPath}, '
        'found ${classes.length}.',
      );
      exit(1);
    }
    for (final LanguageGenerator generator in target.generators) {
      final String outputPath = path.join(root, generator.outputPath);
      File(outputPath)
        ..parent.createSync(recursive: true)
        ..writeAsStringSync(generator.generate(classes.single));
      stdout.writeln('Wrote ${generator.outputPath}');
    }
  }
}

class _GenerationTarget {
  const _GenerationTarget({required this.sourceOfTruthPath, required this.generators});

  final String sourceOfTruthPath;
  final List<LanguageGenerator> generators;
}

/// Resolves the repository root from this script's location
/// (`<root>/dev/tools/gen_constants/bin/gen_constants.dart`).
String _repoRoot() {
  final String binDirectory = path.dirname(Platform.script.toFilePath());
  return path.normalize(path.join(binDirectory, '..', '..', '..', '..'));
}
