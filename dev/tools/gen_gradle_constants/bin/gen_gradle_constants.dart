// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

// Generates Java and Kotlin constants from Dart sources of truth.
//
// What gets generated is listed in [sources]: a Dart source of truth, and the
// Java and/or Kotlin file generated from it. Add an entry there to share a new
// set of constants.
//
// A source of truth may contain nothing but top level `const` declarations of a
// type listed in [ConstantType]. Anything else is an error, so that a constant
// can never be silently dropped from the generated files.
//
// ## Usage
//
// ```
// dart dev/tools/gen_gradle_constants/bin/gen_gradle_constants.dart
// ```
//
// The path of every file written is printed, one per line; dev/bots/analyze.dart
// regenerates and then asks git whether any of them changed.

import 'dart:io';

import 'package:analyzer/dart/analysis/features.dart';
import 'package:analyzer/dart/analysis/results.dart';
import 'package:analyzer/dart/analysis/utilities.dart';
import 'package:analyzer/dart/ast/ast.dart';
import 'package:path/path.dart' as path;

/// The Dart sources of truth, and the files generated from each of them.
///
/// Paths are relative to the root of the repository, and use `/` as the
/// separator on every platform.
const List<ConstantsSource> sources = <ConstantsSource>[
  ConstantsSource(
    dartSource: 'packages/flutter_tools/lib/src/android/gradle_constants.dart',
    generates: <GeneratedFile>[
      GeneratedFile.kotlin(
        file: 'packages/flutter_tools/gradle/src/main/kotlin/GradleConstants.g.kt',
        package: 'com.flutter.gradle',
      ),
    ],
  ),
];

/// A Dart file of constants, and the files generated from it.
class ConstantsSource {
  const ConstantsSource({required this.dartSource, required this.generates});

  /// The Dart file that declares the constants.
  final String dartSource;

  /// The files generated from [dartSource]; one per target language.
  final List<GeneratedFile> generates;
}

/// A file generated from a [ConstantsSource].
class GeneratedFile {
  // Nothing generates Java today, but a source of truth can name a Java file,
  // a Kotlin file, or both.
  // ignore: unreachable_from_main
  const GeneratedFile.java({required this.file, required this.package})
    : language = TargetLanguage.java;

  const GeneratedFile.kotlin({required this.file, required this.package})
    : language = TargetLanguage.kotlin;

  /// The file to generate.
  final String file;

  /// The Java or Kotlin package that [file] declares.
  final String package;

  /// The language [file] is written in.
  final TargetLanguage language;
}

/// A language constants can be generated into.
enum TargetLanguage { java, kotlin }

/// The Dart types a shared constant may have, and what each is called in the
/// target languages.
enum ConstantType {
  string(dart: 'String', java: 'String', kotlin: 'String'),
  // Dart makes no guarantee narrower than 64 bits for `int`.
  integer(dart: 'int', java: 'long', kotlin: 'Long'),
  float(dart: 'double', java: 'double', kotlin: 'Double'),
  boolean(dart: 'bool', java: 'boolean', kotlin: 'Boolean');

  const ConstantType({required this.dart, required this.java, required this.kotlin});

  /// The name of this type in Dart, as written in a source of truth.
  final String dart;

  /// The name of this type in Java.
  final String java;

  /// The name of this type in Kotlin.
  final String kotlin;

  /// The type named [dart], or null if that type cannot be shared.
  static ConstantType? fromDart(String dart) {
    for (final ConstantType type in values) {
      if (type.dart == dart) {
        return type;
      }
    }
    return null;
  }

  String nameIn(TargetLanguage language) => switch (language) {
    TargetLanguage.java => java,
    TargetLanguage.kotlin => kotlin,
  };
}

/// A single `const` declaration read out of a source of truth.
class Constant {
  const Constant({required this.name, required this.type, required this.value});

  final String name;
  final ConstantType type;

  /// The Dart value; a [String], [int], [double], or [bool], per [type].
  final Object value;
}

Future<void> main() async {
  for (final ConstantsSource source in sources) {
    final List<Constant> constants = _parseConstants(source.dartSource);
    for (final GeneratedFile generated in source.generates) {
      File(_resolve(generated.file)).writeAsStringSync(_render(source, generated, constants));
      stdout.writeln(generated.file);
    }
  }
}

/// Reads the `const` declarations out of the source of truth at [dartSource].
List<Constant> _parseConstants(String dartSource) {
  final ParseStringResult result = parseFile(
    path: _resolve(dartSource),
    featureSet: FeatureSet.latestLanguageVersion(),
  );

  Never fail(int offset, String message) {
    final int line = result.lineInfo.getLocation(offset).lineNumber;
    stderr.writeln('$dartSource:$line: $message');
    exit(1);
  }

  final constants = <Constant>[];
  for (final CompilationUnitMember member in result.unit.declarations) {
    if (member is! TopLevelVariableDeclaration || !member.variables.isConst) {
      fail(member.offset, 'a source of truth may only declare top level `const` values.');
    }
    final TypeAnnotation? annotation = member.variables.type;
    if (annotation is! NamedType) {
      fail(member.offset, 'a shared constant must be declared with an explicit type.');
    }
    final ConstantType? type = ConstantType.fromDart(annotation.name.lexeme);
    if (type == null) {
      fail(
        annotation.offset,
        '${annotation.name.lexeme} cannot be shared. Supported types are '
        '${ConstantType.values.map((ConstantType type) => type.dart).join(', ')}.',
      );
    }
    for (final VariableDeclaration variable in member.variables.variables) {
      final Expression? initializer = variable.initializer;
      final Object? value = switch (initializer) {
        SimpleStringLiteral() => initializer.value,
        BooleanLiteral() => initializer.value,
        IntegerLiteral() => initializer.value,
        DoubleLiteral() => initializer.value,
        _ => null,
      };
      if (value == null) {
        fail(variable.offset, '${variable.name.lexeme} must be set to a literal value.');
      }
      constants.add(Constant(name: variable.name.lexeme, type: type, value: value));
    }
  }

  if (constants.isEmpty) {
    stderr.writeln('$dartSource declares no constants.');
    exit(1);
  }
  return constants;
}

/// Renders [constants] as the contents of [generated].
String _render(ConstantsSource source, GeneratedFile generated, List<Constant> constants) {
  final TargetLanguage language = generated.language;
  final buffer = StringBuffer()
    ..writeln('// Copyright 2014 The Flutter Authors. All rights reserved.')
    ..writeln('// Use of this source code is governed by a BSD-style license that can be')
    ..writeln('// found in the LICENSE file.')
    ..writeln('// Generated from ${source.dartSource}')
    ..writeln('// by dev/tools/gen_gradle_constants, do not edit directly.')
    ..writeln();

  switch (language) {
    case TargetLanguage.kotlin:
      buffer
        ..writeln('package ${generated.package}')
        ..writeln();
      for (final constant in constants) {
        buffer.writeln(
          'const val ${constant.name}: ${constant.type.nameIn(language)} = '
          '${_literal(language, constant)}',
        );
      }
    case TargetLanguage.java:
      buffer
        ..writeln('package ${generated.package};')
        ..writeln()
        // In Java the constants have to live inside a class named for the file.
        ..writeln('public class ${path.basenameWithoutExtension(generated.file)} {')
        ..writeln();
      for (final constant in constants) {
        buffer.writeln(
          '  public static final ${constant.type.nameIn(language)} '
          '${constant.name} = ${_literal(language, constant)};',
        );
      }
      buffer.writeln('}');
  }
  return buffer.toString();
}

/// Renders the value of [constant] as a [language] literal.
String _literal(TargetLanguage language, Constant constant) => switch (constant.type) {
  ConstantType.string => _stringLiteral(language, constant.value as String),
  ConstantType.integer => '${constant.value}L',
  ConstantType.float || ConstantType.boolean => '${constant.value}',
};

String _stringLiteral(TargetLanguage language, String value) {
  final buffer = StringBuffer('"');
  for (final int rune in value.runes) {
    switch (rune) {
      case 0x5c: // backslash
        buffer.write(r'\\');
      case 0x22: // double quote
        buffer.write(r'\"');
      case 0x0a: // line feed
        buffer.write(r'\n');
      case 0x0d: // carriage return
        buffer.write(r'\r');
      case 0x09: // tab
        buffer.write(r'\t');
      // `$` opens a template in Kotlin, but is an ordinary character in Java.
      case 0x24 when language == TargetLanguage.kotlin:
        buffer.write(r'\$');
      default:
        buffer.writeCharCode(rune);
    }
  }
  return (buffer..write('"')).toString();
}

/// The root of the repository, from the location of this script in
/// `<root>/dev/tools/gen_gradle_constants/bin`.
final String _repoRoot = path.normalize(
  path.join(path.dirname(path.fromUri(Platform.script)), '..', '..', '..', '..'),
);

/// Turns a repository relative, `/` separated path into an absolute one.
String _resolve(String repoRelativePath) =>
    path.join(_repoRoot, path.joinAll(path.posix.split(repoRelativePath)));
