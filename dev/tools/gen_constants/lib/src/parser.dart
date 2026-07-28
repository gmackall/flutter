// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'package:analyzer/dart/analysis/analysis_context_collection.dart';
import 'package:analyzer/dart/analysis/results.dart';
import 'package:analyzer/dart/analysis/session.dart';
import 'package:analyzer/dart/constant/value.dart';
import 'package:analyzer/dart/element/element.dart';
import 'package:analyzer/diagnostic/diagnostic.dart';
import 'package:path/path.dart' as path;

import 'ir.dart';

/// Thrown when the source of truth contains something the generators cannot
/// faithfully reproduce.
///
/// This is always fatal. Skipping an unsupported constant would emit a native
/// file that still compiles but silently lacks a value, and the "regenerating
/// produces no diff" check would keep passing, so the drift would go unnoticed.
class UnsupportedConstantError implements Exception {
  UnsupportedConstantError(this.message);

  final String message;

  @override
  String toString() => 'UnsupportedConstantError: $message';
}

/// Parses the `static const` fields of every class declared in [filePath].
///
/// Constants are read from the resolved element model, so their values are
/// evaluated rather than copied as source text. That means a constant may be
/// written as any const expression Dart accepts — a reference to another
/// constant, or a concatenation — and the generators still receive a plain
/// value.
Future<List<ConstClass>> parseConstantsFile(String filePath) async {
  final String absolutePath = path.normalize(path.absolute(filePath));
  final collection = AnalysisContextCollection(includedPaths: <String>[absolutePath]);
  final AnalysisSession session = collection.contextFor(absolutePath).currentSession;
  final SomeResolvedUnitResult result = await session.getResolvedUnit(absolutePath);
  if (result is! ResolvedUnitResult) {
    throw UnsupportedConstantError(
      'Could not resolve $absolutePath: expected a ResolvedUnitResult, got ${result.runtimeType}.',
    );
  }
  final List<Diagnostic> errors = result.diagnostics
      .where((Diagnostic diagnostic) => diagnostic.severity == Severity.error)
      .toList();
  if (errors.isNotEmpty) {
    throw UnsupportedConstantError('$absolutePath has analysis errors: ${errors.join('\n')}');
  }

  final classes = <ConstClass>[];
  for (final ClassElement classElement in result.libraryElement.classes) {
    final fields = <ConstField>[];
    for (final FieldElement field in classElement.fields) {
      if (!field.isStatic || !field.isConst) {
        continue;
      }
      fields.add(
        ConstField(
          name: field.name ?? '',
          value: _evaluate(field, classElement.name ?? ''),
          documentation: _stripDocMarkers(field.documentationComment),
        ),
      );
    }
    if (fields.isEmpty) {
      continue;
    }
    classes.add(
      ConstClass(
        name: classElement.name ?? '',
        fields: fields,
        documentation: _stripDocMarkers(classElement.documentationComment),
      ),
    );
  }
  if (classes.isEmpty) {
    throw UnsupportedConstantError('$absolutePath declares no classes with static const fields.');
  }
  return classes;
}

/// Returns the evaluated value of [field], or throws if it is not a type every
/// generator can render.
Object _evaluate(FieldElement field, String className) {
  final qualifiedName = '$className.${field.name}';
  final DartObject? constant = field.computeConstantValue();
  if (constant == null) {
    throw UnsupportedConstantError('$qualifiedName has no value that can be evaluated at compile time.');
  }
  final String? stringValue = constant.toStringValue();
  if (stringValue != null) {
    return stringValue;
  }
  final int? intValue = constant.toIntValue();
  if (intValue != null) {
    return intValue;
  }
  final bool? boolValue = constant.toBoolValue();
  if (boolValue != null) {
    return boolValue;
  }
  throw UnsupportedConstantError(
    '$qualifiedName has unsupported type "${field.type.getDisplayString()}". '
    'Only String, int, and bool constants can be generated.',
  );
}

/// Removes the leading `///` (and one following space) from each line.
String? _stripDocMarkers(String? documentationComment) {
  if (documentationComment == null) {
    return null;
  }
  final List<String> lines = documentationComment
      .split('\n')
      .map((String line) {
        final String trimmed = line.trimLeft();
        if (!trimmed.startsWith('///')) {
          return trimmed;
        }
        final String withoutSlashes = trimmed.substring(3);
        return withoutSlashes.startsWith(' ') ? withoutSlashes.substring(1) : withoutSlashes;
      })
      .toList();
  return lines.join('\n').trim();
}
