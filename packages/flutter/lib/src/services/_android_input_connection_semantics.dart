// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:math' as math;

import 'package:characters/characters.dart';
import 'package:flutter/foundation.dart';

import 'text_editing_delta.dart';

/// Result of applying a single Android `InputConnection` mutation to a
/// [TextEditingValue].
@immutable
class AndroidImeEditResult {
  /// Creates an [AndroidImeEditResult].
  const AndroidImeEditResult({
    required this.value,
    required this.textChanged,
    required this.stateChanged,
    required this.deltas,
    this.handled = true,
    this.extractedText,
  });

  /// The updated editing value after applying the operation.
  final TextEditingValue value;

  /// Whether the underlying text content changed.
  final bool textChanged;

  /// Whether text, selection, or composing changed.
  final bool stateChanged;

  /// Granular [TextEditingDelta]s describing the edit.
  final List<TextEditingDelta> deltas;

  /// Whether the operation succeeded (`false` when an operation cannot be
  /// applied, such as negative `replaceText` bounds or invalid selection for
  /// `moveCursor`/`insertKeyCharacter`; note `deleteSurroundingTextInCodePoints`
  /// returns `handled: true` on an unpaired-surrogate abort to match AOSP).
  final bool handled;

  /// Optional extracted text for clipboard operations (cut/copy).
  final String? extractedText;
}

/// Surrounding text slice computed for Android's `getSurroundingText`.
@immutable
class AndroidSurroundingText {
  /// Creates an [AndroidSurroundingText].
  const AndroidSurroundingText({
    required this.text,
    required this.selectionStart,
    required this.selectionEnd,
    required this.offset,
  });

  /// Extracted substring around the selection.
  final String text;

  /// Selection start relative to [text].
  final int selectionStart;

  /// Selection end relative to [text].
  final int selectionEnd;

  /// Starting offset of [text] within the full editing value.
  final int offset;
}

/// Pure Dart implementation of Android `BaseInputConnection` and
/// `TextUtils.getCapsMode` semantics used by synchronous Android text input.
///
/// Keeping these semantics directly in Dart eliminates the mirrored Java
/// `Editable` / `ListenableEditingState` buffer while preserving Android IME
/// behavior (including composing ranges, cursor placement, code-point deletion
/// surrogate checks, and `TextEditingDelta` generation).
abstract final class AndroidInputConnectionSemantics {
  /// Android `TextUtils.CAP_MODE_CHARACTERS`.
  static const int capModeCharacters = 0x00001000;

  /// Android `TextUtils.CAP_MODE_WORDS`.
  static const int capModeWords = 0x00002000;

  /// Android `TextUtils.CAP_MODE_SENTENCES`.
  static const int capModeSentences = 0x00004000;

  /// Cursor movement directions matching `SyncTextInputProtocol.DIR_*`.
  static const int dirLeft = 0;

  /// Move cursor right by one grapheme cluster.
  static const int dirRight = 1;

  /// Move cursor up by one newline-delimited line.
  static const int dirUp = 2;

  /// Move cursor down by one newline-delimited line.
  static const int dirDown = 3;

  static bool _isHighSurrogate(int codeUnit) => codeUnit >= 0xD800 && codeUnit <= 0xDBFF;

  static bool _isLowSurrogate(int codeUnit) => codeUnit >= 0xDC00 && codeUnit <= 0xDFFF;

  /// Returns the normalized, clamped, non-collapsed composing bounds `(start, end)`
  /// for [value], or `(-1, -1)` when no non-empty composing region is active.
  static (int, int) orderedComposing(TextEditingValue value) {
    final TextRange composing = value.composing;
    if (!composing.isValid || composing.isCollapsed) {
      return (-1, -1);
    }
    final int len = value.text.length;
    final int a = composing.start.clamp(0, len);
    final int b = composing.end.clamp(0, len);
    if (a == b) {
      return (-1, -1);
    }
    return a < b ? (a, b) : (b, a);
  }

  /// Compares two [TextEditingValue]s across the fields representable on
  /// Android (text, selection validity/offsets, and normalized composing span),
  /// ignoring Flutter-only [TextSelection.affinity] and
  /// [TextSelection.isDirectional].
  static bool androidStateEquals(TextEditingValue a, TextEditingValue b) {
    if (a.text != b.text) {
      return false;
    }
    if (a.selection.isValid != b.selection.isValid) {
      return false;
    }
    if (a.selection.isValid &&
        (a.selection.baseOffset != b.selection.baseOffset ||
            a.selection.extentOffset != b.selection.extentOffset)) {
      return false;
    }
    return orderedComposing(a) == orderedComposing(b);
  }

  /// Adjusts an active composing span across an in-place replacement of
  /// `[replaceStart, replaceEnd)` with [replacementLength] UTF-16 code units,
  /// matching Android `SpannableStringBuilder` `SPAN_EXCLUSIVE_EXCLUSIVE`
  /// semantics.
  static TextRange _adjustComposingAfterReplace(
    TextRange composing,
    int oldTextLength,
    int replaceStart,
    int replaceEnd,
    int replacementLength,
  ) {
    if (!composing.isValid || composing.isCollapsed) {
      return TextRange.empty;
    }
    final int rawA = composing.start.clamp(0, oldTextLength);
    final int rawB = composing.end.clamp(0, oldTextLength);
    final int cs = math.min(rawA, rawB);
    final int ce = math.max(rawA, rawB);
    if (cs == ce) {
      return TextRange.empty;
    }
    final int delta = replacementLength - (replaceEnd - replaceStart);
    if (replaceEnd <= cs) {
      return TextRange(start: cs + delta, end: ce + delta);
    }
    if (replaceStart >= ce) {
      return TextRange(start: cs, end: ce);
    }
    if (replaceStart <= cs && replaceEnd >= ce) {
      return TextRange.empty;
    }
    final int newCs = cs <= replaceStart
        ? cs
        : (cs < replaceEnd ? replaceStart + replacementLength : cs + delta);
    final int newCe = ce <= replaceStart ? ce : (ce < replaceEnd ? replaceStart : ce + delta);
    if (newCe <= newCs) {
      return TextRange.empty;
    }
    return TextRange(start: newCs, end: newCe);
  }

  static TextEditingDelta _buildDelta({
    required String oldText,
    required int replaceStart,
    required int replaceEnd,
    required String replacementText,
    required TextSelection newSelection,
    required TextRange newComposing,
  }) {
    if (replaceStart == -1 && replaceEnd == -1) {
      return TextEditingDeltaNonTextUpdate(
        oldText: oldText,
        selection: newSelection,
        composing: newComposing,
      );
    }

    final String newText = oldText.replaceRange(replaceStart, replaceEnd, replacementText);
    if (oldText == newText) {
      return TextEditingDeltaNonTextUpdate(
        oldText: oldText,
        selection: newSelection,
        composing: newComposing,
      );
    }

    final int destLen = replaceEnd - replaceStart;
    final int srcLen = replacementText.length;
    final bool isDeletionGreaterThanOne = (destLen - srcLen) > 1;
    final bool isDeletingByReplacingWithEmpty = replacementText.isEmpty;
    final bool isReplacedByShorter = isDeletionGreaterThanOne && srcLen < destLen;
    final bool isInsertingInsideComposingRegion = replaceStart + srcLen > replaceEnd;
    final bool isDeletingInsideComposingRegion =
        !isReplacedByShorter &&
        !isDeletingByReplacingWithEmpty &&
        replaceStart + srcLen < replaceEnd;

    final String newComposingText;
    final String originalComposingText;
    if (isDeletingByReplacingWithEmpty || isDeletingInsideComposingRegion || isReplacedByShorter) {
      newComposingText = replacementText;
      originalComposingText = oldText.substring(replaceStart, replaceStart + srcLen);
    } else {
      newComposingText = replacementText.substring(0, destLen);
      originalComposingText = oldText.substring(replaceStart, replaceEnd);
    }

    final isOriginalComposingRegionTextChanged = originalComposingText != newComposingText;

    if ((isDeletingByReplacingWithEmpty || isDeletingInsideComposingRegion) &&
        !isOriginalComposingRegionTextChanged) {
      final int actualStart = isDeletionGreaterThanOne ? replaceStart : replaceEnd - 1;
      return TextEditingDeltaDeletion(
        oldText: oldText,
        deletedRange: TextRange(start: actualStart, end: replaceEnd),
        selection: newSelection,
        composing: newComposing,
      );
    }

    if ((replaceStart == replaceEnd || isInsertingInsideComposingRegion) &&
        !isOriginalComposingRegionTextChanged) {
      return TextEditingDeltaInsertion(
        oldText: oldText,
        textInserted: replacementText.substring(destLen),
        insertionOffset: replaceEnd,
        selection: newSelection,
        composing: newComposing,
      );
    }

    return TextEditingDeltaReplacement(
      oldText: oldText,
      replacementText: replacementText,
      replacedRange: TextRange(start: replaceStart, end: replaceEnd),
      selection: newSelection,
      composing: newComposing,
    );
  }

  /// Applies Android `commitText(text, newCursorPosition)` or
  /// `setComposingText(text, newCursorPosition)`.
  static AndroidImeEditResult replaceComposingOrSelection(
    TextEditingValue value, {
    required String text,
    required int newCursorPosition,
    required bool composing,
  }) {
    final String oldText = value.text;
    final int oldLen = oldText.length;

    int a;
    int b;
    final (int ca, int cb) = orderedComposing(value);
    if (ca != -1 && cb != -1) {
      a = ca;
      b = cb;
    } else if (value.selection.isValid) {
      a = value.selection.start.clamp(0, oldLen);
      b = value.selection.end.clamp(0, oldLen);
    } else {
      a = 0;
      b = 0;
    }

    final String newText = oldText.replaceRange(a, b, text);
    final TextRange newComposing = composing && text.isNotEmpty
        ? TextRange(start: a, end: a + text.length)
        : TextRange.empty;

    final int rawCursor = newCursorPosition > 0
        ? a + text.length + newCursorPosition - 1
        : a + newCursorPosition;
    final int cursor = rawCursor.clamp(0, newText.length);
    final newSelection = TextSelection.collapsed(offset: cursor);

    final newValue = TextEditingValue(
      text: newText,
      selection: newSelection,
      composing: newComposing,
    );
    final textChanged = newText != oldText;
    final bool stateChanged = !androidStateEquals(newValue, value);
    final deltas = stateChanged
        ? <TextEditingDelta>[
            _buildDelta(
              oldText: oldText,
              replaceStart: a,
              replaceEnd: b,
              replacementText: text,
              newSelection: newSelection,
              newComposing: newComposing,
            ),
          ]
        : const <TextEditingDelta>[];

    return AndroidImeEditResult(
      value: newValue,
      textChanged: textChanged,
      stateChanged: stateChanged,
      deltas: deltas,
    );
  }

  /// Applies Android `commitText(text, newCursorPosition)`.
  static AndroidImeEditResult commitText(
    TextEditingValue value,
    String text,
    int newCursorPosition,
  ) {
    return replaceComposingOrSelection(
      value,
      text: text,
      newCursorPosition: newCursorPosition,
      composing: false,
    );
  }

  /// Applies Android `setComposingText(text, newCursorPosition)`.
  ///
  /// Matches `InputConnectionAdaptor`: empty composing text commits and clears
  /// the composing region rather than leaving a zero-length composing span.
  static AndroidImeEditResult setComposingText(
    TextEditingValue value,
    String text,
    int newCursorPosition,
  ) {
    return replaceComposingOrSelection(
      value,
      text: text,
      newCursorPosition: newCursorPosition,
      composing: text.isNotEmpty,
    );
  }

  /// Applies Android `setComposingRegion(start, end)`.
  static AndroidImeEditResult setComposingRegion(TextEditingValue value, int start, int end) {
    final int len = value.text.length;
    var a = start;
    var b = end;
    if (a > b) {
      final tmp = a;
      a = b;
      b = tmp;
    }
    a = a.clamp(0, len);
    b = b.clamp(0, len);

    final TextRange newComposing = a != b ? TextRange(start: a, end: b) : TextRange.empty;
    final TextEditingValue newValue = value.copyWith(composing: newComposing);
    final bool stateChanged = !androidStateEquals(newValue, value);
    final deltas = stateChanged
        ? <TextEditingDelta>[
            TextEditingDeltaNonTextUpdate(
              oldText: value.text,
              selection: newValue.selection,
              composing: newComposing,
            ),
          ]
        : const <TextEditingDelta>[];
    return AndroidImeEditResult(
      value: newValue,
      textChanged: false,
      stateChanged: stateChanged,
      deltas: deltas,
    );
  }

  /// Applies Android `finishComposingText()`.
  static AndroidImeEditResult finishComposingText(TextEditingValue value) {
    if (!value.composing.isValid || value.composing.isCollapsed) {
      return AndroidImeEditResult(
        value: value,
        textChanged: false,
        stateChanged: false,
        deltas: const <TextEditingDelta>[],
      );
    }
    final TextEditingValue newValue = value.copyWith(composing: TextRange.empty);
    return AndroidImeEditResult(
      value: newValue,
      textChanged: false,
      stateChanged: true,
      deltas: <TextEditingDelta>[
        TextEditingDeltaNonTextUpdate(
          oldText: value.text,
          selection: newValue.selection,
          composing: TextRange.empty,
        ),
      ],
    );
  }

  /// Applies Android `setSelection(start, end)`.
  ///
  /// Matches `BaseInputConnection.setSelection`: out-of-bounds indices are
  /// ignored without mutating state.
  static AndroidImeEditResult setSelection(TextEditingValue value, int start, int end) {
    final int len = value.text.length;
    if (start < 0 || end < 0 || start > len || end > len) {
      return AndroidImeEditResult(
        value: value,
        textChanged: false,
        stateChanged: false,
        deltas: const <TextEditingDelta>[],
      );
    }
    final newSelection = TextSelection(baseOffset: start, extentOffset: end);
    final TextEditingValue newValue = value.copyWith(selection: newSelection);
    final bool stateChanged = !androidStateEquals(newValue, value);
    final deltas = stateChanged
        ? <TextEditingDelta>[
            TextEditingDeltaNonTextUpdate(
              oldText: value.text,
              selection: newSelection,
              composing: newValue.composing,
            ),
          ]
        : const <TextEditingDelta>[];
    return AndroidImeEditResult(
      value: newValue,
      textChanged: false,
      stateChanged: stateChanged,
      deltas: deltas,
    );
  }

  /// Applies Android API 34 `replaceText(start, end, text, newCursorPosition)`.
  static AndroidImeEditResult replaceText(
    TextEditingValue value,
    int start,
    int end,
    String text,
    int newCursorPosition,
  ) {
    if (start < 0 || end < 0) {
      return AndroidImeEditResult(
        value: value,
        textChanged: false,
        stateChanged: false,
        deltas: const <TextEditingDelta>[],
        handled: false,
      );
    }
    final String oldText = value.text;
    final int len = oldText.length;
    final int a = math.min(start, end).clamp(0, len);
    final int b = math.max(start, end).clamp(0, len);

    final String newText = oldText.replaceRange(a, b, text);
    final int rawCursor = newCursorPosition > 0
        ? a + text.length + newCursorPosition - 1
        : a + newCursorPosition;
    final int cursor = rawCursor.clamp(0, newText.length);
    final newSelection = TextSelection.collapsed(offset: cursor);

    final newValue = TextEditingValue(text: newText, selection: newSelection);
    final textChanged = newText != oldText;
    final bool stateChanged = !androidStateEquals(newValue, value);
    final deltas = stateChanged
        ? <TextEditingDelta>[
            _buildDelta(
              oldText: oldText,
              replaceStart: a,
              replaceEnd: b,
              replacementText: text,
              newSelection: newSelection,
              newComposing: TextRange.empty,
            ),
          ]
        : const <TextEditingDelta>[];
    return AndroidImeEditResult(
      value: newValue,
      textChanged: textChanged,
      stateChanged: stateChanged,
      deltas: deltas,
    );
  }

  static AndroidImeEditResult _deleteSurroundingRanges(
    TextEditingValue value, {
    required int beforeStart,
    required int beforeEnd,
    required int afterStart,
    required int afterEnd,
  }) {
    final bool hasBefore = beforeEnd > beforeStart;
    final bool hasAfter = afterEnd > afterStart;
    if (!hasBefore && !hasAfter) {
      return AndroidImeEditResult(
        value: value,
        textChanged: false,
        stateChanged: false,
        deltas: const <TextEditingDelta>[],
      );
    }

    String currentText = value.text;
    TextSelection currentSelection = value.selection;
    final (int ca, int cb) = orderedComposing(value);
    TextRange currentComposing = (ca != -1 && cb != -1)
        ? TextRange(start: ca, end: cb)
        : TextRange.empty;
    final deltas = <TextEditingDelta>[];

    // Match BaseInputConnection.deleteSurroundingText: delete the range after
    // the selection/composing first (leaving selection/composing indices valid),
    // then delete the range before and shift indices left.
    if (hasAfter) {
      final oldText = currentText;
      currentText = currentText.replaceRange(afterStart, afterEnd, '');
      deltas.add(
        TextEditingDeltaDeletion(
          oldText: oldText,
          deletedRange: TextRange(start: afterStart, end: afterEnd),
          selection: currentSelection,
          composing: currentComposing,
        ),
      );
    }

    if (hasBefore) {
      final oldText = currentText;
      final int deletedCount = beforeEnd - beforeStart;
      currentText = currentText.replaceRange(beforeStart, beforeEnd, '');
      currentSelection = TextSelection(
        baseOffset: currentSelection.baseOffset - deletedCount,
        extentOffset: currentSelection.extentOffset - deletedCount,
        affinity: currentSelection.affinity,
        isDirectional: currentSelection.isDirectional,
      );
      if (currentComposing.isValid && !currentComposing.isCollapsed) {
        currentComposing = TextRange(
          start: currentComposing.start - deletedCount,
          end: currentComposing.end - deletedCount,
        );
      }
      deltas.add(
        TextEditingDeltaDeletion(
          oldText: oldText,
          deletedRange: TextRange(start: beforeStart, end: beforeEnd),
          selection: currentSelection,
          composing: currentComposing,
        ),
      );
    }

    final newValue = TextEditingValue(
      text: currentText,
      selection: currentSelection,
      composing: currentComposing,
    );
    return AndroidImeEditResult(
      value: newValue,
      textChanged: true,
      stateChanged: true,
      deltas: deltas,
    );
  }

  /// Applies Android `deleteSurroundingText(beforeLength, afterLength)`.
  ///
  /// Preserves both the active selection and any composing region, matching
  /// `BaseInputConnection.deleteSurroundingText`.
  static AndroidImeEditResult deleteSurroundingText(
    TextEditingValue value,
    int beforeLength,
    int afterLength,
  ) {
    if (!value.selection.isValid) {
      return AndroidImeEditResult(
        value: value,
        textChanged: false,
        stateChanged: false,
        deltas: const <TextEditingDelta>[],
      );
    }

    final int len = value.text.length;
    int a = value.selection.start.clamp(0, len);
    int b = value.selection.end.clamp(0, len);
    final (int ca, int cb) = orderedComposing(value);
    if (ca != -1 && cb != -1) {
      if (ca < a) {
        a = ca;
      }
      if (cb > b) {
        b = cb;
      }
    }

    final safeBefore = beforeLength > 0 ? beforeLength : 0;
    final safeAfter = afterLength > 0 ? afterLength : 0;
    final int beforeStart = math.max(0, a - safeBefore);
    final int afterEnd = math.min(len, b + safeAfter);

    return _deleteSurroundingRanges(
      value,
      beforeStart: beforeStart,
      beforeEnd: a,
      afterStart: b,
      afterEnd: afterEnd,
    );
  }

  /// Applies Android `deleteSurroundingTextInCodePoints(beforeLength, afterLength)`.
  ///
  /// Matches `BaseInputConnection.deleteSurroundingTextInCodePoints`, including
  /// aborting without modifying text when encountering an unpaired UTF-16
  /// surrogate in either deletion window.
  static AndroidImeEditResult deleteSurroundingTextInCodePoints(
    TextEditingValue value,
    int beforeLength,
    int afterLength,
  ) {
    if (!value.selection.isValid) {
      return AndroidImeEditResult(
        value: value,
        textChanged: false,
        stateChanged: false,
        deltas: const <TextEditingDelta>[],
      );
    }

    final String text = value.text;
    final int len = text.length;
    int a = value.selection.start.clamp(0, len);
    int b = value.selection.end.clamp(0, len);
    final (int ca, int cb) = orderedComposing(value);
    if (ca != -1 && cb != -1) {
      if (ca < a) {
        a = ca;
      }
      if (cb > b) {
        b = cb;
      }
    }

    final safeBefore = beforeLength > 0 ? beforeLength : 0;
    final safeAfter = afterLength > 0 ? afterLength : 0;

    var beforeStart = a;
    if (safeBefore > 0) {
      var pos = a;
      var remaining = safeBefore;
      while (remaining > 0 && pos > 0) {
        final int unit = text.codeUnitAt(pos - 1);
        if (_isLowSurrogate(unit)) {
          if (pos - 2 >= 0 && _isHighSurrogate(text.codeUnitAt(pos - 2))) {
            pos -= 2;
            remaining--;
          } else {
            return AndroidImeEditResult(
              value: value,
              textChanged: false,
              stateChanged: false,
              deltas: const <TextEditingDelta>[],
            );
          }
        } else if (_isHighSurrogate(unit)) {
          return AndroidImeEditResult(
            value: value,
            textChanged: false,
            stateChanged: false,
            deltas: const <TextEditingDelta>[],
          );
        } else {
          pos -= 1;
          remaining--;
        }
      }
      beforeStart = pos;
    }

    var afterEnd = b;
    if (safeAfter > 0) {
      var pos = b;
      var remaining = safeAfter;
      while (remaining > 0 && pos < len) {
        final int unit = text.codeUnitAt(pos);
        if (_isHighSurrogate(unit)) {
          if (pos + 1 < len && _isLowSurrogate(text.codeUnitAt(pos + 1))) {
            pos += 2;
            remaining--;
          } else {
            return AndroidImeEditResult(
              value: value,
              textChanged: false,
              stateChanged: false,
              deltas: const <TextEditingDelta>[],
            );
          }
        } else if (_isLowSurrogate(unit)) {
          return AndroidImeEditResult(
            value: value,
            textChanged: false,
            stateChanged: false,
            deltas: const <TextEditingDelta>[],
          );
        } else {
          pos += 1;
          remaining--;
        }
      }
      afterEnd = pos;
    }

    return _deleteSurroundingRanges(
      value,
      beforeStart: beforeStart,
      beforeEnd: a,
      afterStart: b,
      afterEnd: afterEnd,
    );
  }

  /// Applies Android `getTextBeforeCursor(n, flags)`.
  static String? getTextBeforeCursor(TextEditingValue value, int n) {
    if (n < 0) {
      return null;
    }
    final int len = value.text.length;
    final int a = value.selection.isValid ? value.selection.start.clamp(0, len) : 0;
    final int start = math.max(0, a - n);
    return value.text.substring(start, a);
  }

  /// Applies Android `getTextAfterCursor(n, flags)`.
  static String? getTextAfterCursor(TextEditingValue value, int n) {
    if (n < 0) {
      return null;
    }
    final int len = value.text.length;
    final int b = value.selection.isValid ? value.selection.end.clamp(0, len) : 0;
    final int end = math.min(len, b + n);
    return value.text.substring(b, end);
  }

  /// Applies Android `getSelectedText(flags)`.
  static String? getSelectedText(TextEditingValue value) {
    if (!value.selection.isValid || value.selection.isCollapsed) {
      return null;
    }
    final int len = value.text.length;
    final int a = value.selection.start.clamp(0, len);
    final int b = value.selection.end.clamp(0, len);
    if (a == b) {
      return null;
    }
    return value.text.substring(a, b);
  }

  /// Applies Android API 31+ `getSurroundingText(beforeLength, afterLength, flags)`.
  static AndroidSurroundingText? getSurroundingText(
    TextEditingValue value,
    int beforeLength,
    int afterLength,
  ) {
    if (beforeLength < 0 || afterLength < 0 || !value.selection.isValid) {
      return null;
    }
    final int len = value.text.length;
    final int selStart = value.selection.start.clamp(0, len);
    final int selEnd = value.selection.end.clamp(0, len);
    final int startOffset = math.max(0, selStart - beforeLength);
    final int endOffset = math.min(len, selEnd + afterLength);
    return AndroidSurroundingText(
      text: value.text.substring(startOffset, endOffset),
      selectionStart: selStart - startOffset,
      selectionEnd: selEnd - startOffset,
      offset: startOffset,
    );
  }

  static bool _isStartPunctuation(int c) {
    // Unicode Ps (Open_Punctuation) characters matching Java Character.START_PUNCTUATION.
    // Note: Pi (INITIAL_QUOTE_PUNCTUATION) such as « ‘ “ ‹ are intentionally excluded because
    // Android's TextUtils.getCapsMode checks Character.getType(c) == START_PUNCTUATION.
    return c == 0x28 || // (
        c == 0x5B || // [
        c == 0x7B || // {
        c == 0x201A || // ‚
        c == 0x201E || // „
        c == 0x3008 || // 〈
        c == 0x300A || // 《
        c == 0x300C || // 「
        c == 0x300E || // 『
        c == 0x3010 || // 【
        c == 0xFF08 || // （
        c == 0xFF3B || // ［
        c == 0xFF5B; // ｛
  }

  static bool _isEndPunctuation(int c) {
    // Unicode Pe (Close_Punctuation) characters matching Java Character.END_PUNCTUATION.
    // Note: Pf (FINAL_QUOTE_PUNCTUATION) such as » ’ ” › are intentionally excluded to match
    // Android's TextUtils.getCapsMode.
    return c == 0x29 || // )
        c == 0x5D || // ]
        c == 0x7D || // }
        c == 0x3009 || // 〉
        c == 0x300B || // 》
        c == 0x300D || // 」
        c == 0x300F || // 』
        c == 0x3011 || // 】
        c == 0xFF09 || // ）
        c == 0xFF3D || // ］
        c == 0xFF5D; // ｝
  }

  static bool _isLetter(int c) {
    // ASCII + Latin-1/Extended + IPA letters.
    if ((c >= 0x41 && c <= 0x5A) || (c >= 0x61 && c <= 0x7A)) {
      return true;
    }
    if (c >= 0x00C0 && c <= 0x02AF && c != 0x00D7 && c != 0x00F7) {
      return true;
    }
    // Greek & Coptic (excluding punctuation/symbols).
    if ((c >= 0x0370 && c <= 0x0373) ||
        (c >= 0x0376 && c <= 0x0377) ||
        (c >= 0x037B && c <= 0x037D) ||
        c == 0x037F ||
        c == 0x0386 ||
        (c >= 0x0388 && c <= 0x038A) ||
        c == 0x038C ||
        (c >= 0x038E && c <= 0x03A1) ||
        (c >= 0x03A3 && c <= 0x03F5) ||
        (c >= 0x03F7 && c <= 0x03FF)) {
      return true;
    }
    // Cyrillic & Cyrillic Supplement.
    if ((c >= 0x0400 && c <= 0x0481) || (c >= 0x048A && c <= 0x052F)) {
      return true;
    }
    // Armenian, Hebrew, Arabic, Devanagari, Latin/Greek Extended.
    if ((c >= 0x0531 && c <= 0x0556) ||
        (c >= 0x0560 && c <= 0x0588) ||
        (c >= 0x05D0 && c <= 0x05EA) ||
        (c >= 0x0620 && c <= 0x064A) ||
        (c >= 0x066E && c <= 0x06D3) ||
        (c >= 0x0904 && c <= 0x0939) ||
        (c >= 0x1E00 && c <= 0x1EFF) ||
        (c >= 0x1F00 && c <= 0x1FFF)) {
      return true;
    }
    // Hiragana, Katakana, CJK Unified Ideographs (+ Ext A), Hangul Syllables.
    if ((c >= 0x3041 && c <= 0x3096) ||
        (c >= 0x30A1 && c <= 0x30FA) ||
        (c >= 0x3400 && c <= 0x4DBF) ||
        (c >= 0x4E00 && c <= 0x9FFF) ||
        (c >= 0xAC00 && c <= 0xD7A3)) {
      return true;
    }
    return false;
  }

  /// Port of Android's `BaseInputConnection.getCursorCapsMode` /
  /// `TextUtils.getCapsMode`.
  static int getCursorCapsMode(TextEditingValue value, int reqModes) {
    if (!value.selection.isValid || reqModes == 0) {
      return 0;
    }
    final String text = value.text;
    final int off = value.selection.start.clamp(0, text.length);

    var mode = 0;
    if ((reqModes & capModeCharacters) != 0) {
      mode |= capModeCharacters;
    }
    if ((reqModes & (capModeWords | capModeSentences)) == 0) {
      return mode;
    }

    // Back over allowed opening punctuation.
    var i = off;
    while (i > 0) {
      final int c = text.codeUnitAt(i - 1);
      if (c != 0x22 /* " */ && c != 0x27 /* ' */ && !_isStartPunctuation(c)) {
        break;
      }
      i--;
    }

    // Start of paragraph, with optional whitespace.
    var j = i;
    while (j > 0) {
      final int c = text.codeUnitAt(j - 1);
      if (c != 0x20 /* ' ' */ && c != 0x09 /* '\t' */ ) {
        break;
      }
      j--;
    }
    if (j == 0 || text.codeUnitAt(j - 1) == 0x0A /* '\n' */ ) {
      return mode | capModeWords;
    }

    if ((reqModes & capModeSentences) == 0) {
      if (i != j) {
        mode |= capModeWords;
      }
      return mode;
    }

    // There must be at least one space/tab after sentence punctuation.
    if (i == j) {
      return mode;
    }

    // Back over closing punctuation.
    while (j > 0) {
      final int c = text.codeUnitAt(j - 1);
      if (c != 0x22 /* " */ && c != 0x27 /* ' */ && !_isEndPunctuation(c)) {
        break;
      }
      j--;
    }

    if (j > 0) {
      final int c = text.codeUnitAt(j - 1);
      if (c == 0x2E /* . */ || c == 0x3F /* ? */ || c == 0x21 /* ! */ ) {
        // Do not capitalize after abbreviations such as "e.g.".
        if (c == 0x2E /* . */ ) {
          for (int k = j - 2; k >= 0; k--) {
            final int prev = text.codeUnitAt(k);
            if (prev == 0x2E /* . */ ) {
              return mode;
            }
            if (!_isLetter(prev)) {
              break;
            }
          }
        }
        return mode | capModeSentences;
      }
    }

    return mode;
  }

  /// Applies Android `performContextMenuAction(android.R.id.selectAll)`.
  static AndroidImeEditResult selectAll(TextEditingValue value) {
    return setSelection(value, 0, value.text.length);
  }

  /// Applies Android `performContextMenuAction(android.R.id.cut)`.
  static AndroidImeEditResult cut(TextEditingValue value) {
    if (!value.selection.isValid || value.selection.isCollapsed) {
      return AndroidImeEditResult(
        value: value,
        textChanged: false,
        stateChanged: false,
        deltas: const <TextEditingDelta>[],
      );
    }
    final String oldText = value.text;
    final int len = oldText.length;
    final int selMin = value.selection.start.clamp(0, len);
    final int selMax = value.selection.end.clamp(0, len);
    final String cutText = oldText.substring(selMin, selMax);
    final String newText = oldText.replaceRange(selMin, selMax, '');
    final newSelection = TextSelection.collapsed(offset: selMin);
    final TextRange newComposing = _adjustComposingAfterReplace(
      value.composing,
      len,
      selMin,
      selMax,
      0,
    );
    final newValue = TextEditingValue(
      text: newText,
      selection: newSelection,
      composing: newComposing,
    );
    return AndroidImeEditResult(
      value: newValue,
      textChanged: true,
      stateChanged: true,
      deltas: <TextEditingDelta>[
        TextEditingDeltaDeletion(
          oldText: oldText,
          deletedRange: TextRange(start: selMin, end: selMax),
          selection: newSelection,
          composing: newComposing,
        ),
      ],
      extractedText: cutText,
    );
  }

  /// Applies Android `performContextMenuAction(android.R.id.copy)`.
  static AndroidImeEditResult copy(TextEditingValue value) {
    return AndroidImeEditResult(
      value: value,
      textChanged: false,
      stateChanged: false,
      deltas: const <TextEditingDelta>[],
      extractedText: getSelectedText(value),
    );
  }

  /// Applies Android `performContextMenuAction(android.R.id.paste)`.
  static AndroidImeEditResult paste(TextEditingValue value, String textToPaste) {
    final String oldText = value.text;
    final int len = oldText.length;
    final int selMin = value.selection.isValid ? value.selection.start.clamp(0, len) : 0;
    final int selMax = value.selection.isValid ? value.selection.end.clamp(0, len) : 0;
    final String newText = oldText.replaceRange(selMin, selMax, textToPaste);
    final int newOffset = selMin + textToPaste.length;
    final newSelection = TextSelection.collapsed(offset: newOffset);
    final TextRange newComposing = _adjustComposingAfterReplace(
      value.composing,
      len,
      selMin,
      selMax,
      textToPaste.length,
    );
    final newValue = TextEditingValue(
      text: newText,
      selection: newSelection,
      composing: newComposing,
    );
    final textChanged = newText != oldText;
    final bool stateChanged = !androidStateEquals(newValue, value);
    final deltas = stateChanged
        ? <TextEditingDelta>[
            _buildDelta(
              oldText: oldText,
              replaceStart: selMin,
              replaceEnd: selMax,
              replacementText: textToPaste,
              newSelection: newSelection,
              newComposing: newComposing,
            ),
          ]
        : const <TextEditingDelta>[];
    return AndroidImeEditResult(
      value: newValue,
      textChanged: textChanged,
      stateChanged: stateChanged,
      deltas: deltas,
    );
  }

  /// Inserts a printable character produced by a hardware or fallback key event
  /// directly at the selection, matching `InputConnectionAdaptor.handleKeyEvent`
  /// (`mEditable.replace(selMin, selMax, text)`) without clobbering an active
  /// composing region outside the selection.
  static AndroidImeEditResult insertKeyCharacter(TextEditingValue value, String text) {
    if (!value.selection.isValid || text.isEmpty) {
      return AndroidImeEditResult(
        value: value,
        textChanged: false,
        stateChanged: false,
        deltas: const <TextEditingDelta>[],
        handled: false,
      );
    }
    final String oldText = value.text;
    final int len = oldText.length;
    final int selMin = value.selection.start.clamp(0, len);
    final int selMax = value.selection.end.clamp(0, len);
    final String newText = oldText.replaceRange(selMin, selMax, text);
    final int newOffset = selMin + text.length;
    final newSelection = TextSelection.collapsed(offset: newOffset);
    final TextRange newComposing = _adjustComposingAfterReplace(
      value.composing,
      len,
      selMin,
      selMax,
      text.length,
    );
    final newValue = TextEditingValue(
      text: newText,
      selection: newSelection,
      composing: newComposing,
    );
    final textChanged = newText != oldText;
    final bool stateChanged = !androidStateEquals(newValue, value);
    final deltas = stateChanged
        ? <TextEditingDelta>[
            _buildDelta(
              oldText: oldText,
              replaceStart: selMin,
              replaceEnd: selMax,
              replacementText: text,
              newSelection: newSelection,
              newComposing: newComposing,
            ),
          ]
        : const <TextEditingDelta>[];
    return AndroidImeEditResult(
      value: newValue,
      textChanged: textChanged,
      stateChanged: stateChanged,
      deltas: deltas,
    );
  }

  /// Applies Android `autofill` to the currently focused client.
  static AndroidImeEditResult autofill(TextEditingValue value, String newText) {
    final String oldText = value.text;
    final newSelection = TextSelection.collapsed(offset: newText.length);
    final newValue = TextEditingValue(text: newText, selection: newSelection);
    final textChanged = newText != oldText;
    final bool stateChanged = !androidStateEquals(newValue, value);
    final deltas = stateChanged
        ? <TextEditingDelta>[
            _buildDelta(
              oldText: oldText,
              replaceStart: 0,
              replaceEnd: oldText.length,
              replacementText: newText,
              newSelection: newSelection,
              newComposing: TextRange.empty,
            ),
          ]
        : const <TextEditingDelta>[];
    return AndroidImeEditResult(
      value: newValue,
      textChanged: textChanged,
      stateChanged: stateChanged,
      deltas: deltas,
    );
  }

  static int _graphemeOffsetBefore(String text, int offset) {
    if (offset <= 0) {
      return 0;
    }
    final range = CharacterRange.at(text, offset.clamp(0, text.length));
    if (!range.moveBack()) {
      return 0;
    }
    return range.stringBeforeLength;
  }

  static int _graphemeOffsetAfter(String text, int offset) {
    if (offset >= text.length) {
      return text.length;
    }
    final range = CharacterRange.at(text, offset.clamp(0, text.length));
    if (!range.moveNext()) {
      return text.length;
    }
    return range.stringBeforeLength + range.current.length;
  }

  static int _snapOffSurrogatePair(String text, int offset) {
    if (offset > 0 &&
        offset < text.length &&
        _isLowSurrogate(text.codeUnitAt(offset)) &&
        _isHighSurrogate(text.codeUnitAt(offset - 1))) {
      return offset - 1;
    }
    return offset;
  }

  static int _verticalLineOffset(String text, int offset, {required bool up}) {
    final int clamped = offset.clamp(0, text.length);
    final int prevNewline = clamped == 0 ? -1 : text.lastIndexOf('\n', clamped - 1);
    final int curLineStart = prevNewline + 1;
    final int col = clamped - curLineStart;

    if (up) {
      if (curLineStart == 0) {
        return 0;
      }
      final int prevLineEnd = curLineStart - 1;
      final int prevPrevNewline = prevLineEnd == 0 ? -1 : text.lastIndexOf('\n', prevLineEnd - 1);
      final int prevLineStart = prevPrevNewline + 1;
      return _snapOffSurrogatePair(text, math.min(prevLineStart + col, prevLineEnd));
    } else {
      final int nextNewline = text.indexOf('\n', clamped);
      if (nextNewline == -1) {
        return text.length;
      }
      final int nextLineStart = nextNewline + 1;
      final int nextLineEndRaw = text.indexOf('\n', nextLineStart);
      final int nextLineEnd = nextLineEndRaw == -1 ? text.length : nextLineEndRaw;
      return _snapOffSurrogatePair(text, math.min(nextLineStart + col, nextLineEnd));
    }
  }

  /// Applies DPAD cursor movement matching `InputConnectionAdaptor.handleKeyEvent`.
  static AndroidImeEditResult moveCursor(
    TextEditingValue value, {
    required int direction,
    required bool extendSelection,
  }) {
    if (!value.selection.isValid) {
      return AndroidImeEditResult(
        value: value,
        textChanged: false,
        stateChanged: false,
        deltas: const <TextEditingDelta>[],
        handled: false,
      );
    }
    final String text = value.text;
    final int base = value.selection.baseOffset.clamp(0, text.length);
    final int extent = value.selection.extentOffset.clamp(0, text.length);
    final int newExtent = switch (direction) {
      dirLeft => _graphemeOffsetBefore(text, extent),
      dirRight => _graphemeOffsetAfter(text, extent),
      dirUp => _verticalLineOffset(text, extent, up: true),
      dirDown => _verticalLineOffset(text, extent, up: false),
      _ => extent,
    };

    final bool shouldCollapse = base == extent && !extendSelection;
    final newSelection = shouldCollapse
        ? TextSelection.collapsed(offset: newExtent)
        : TextSelection(baseOffset: base, extentOffset: newExtent);

    final TextEditingValue newValue = value.copyWith(selection: newSelection);
    final bool stateChanged = !androidStateEquals(newValue, value);
    final deltas = stateChanged
        ? <TextEditingDelta>[
            TextEditingDeltaNonTextUpdate(
              oldText: text,
              selection: newSelection,
              composing: newValue.composing,
            ),
          ]
        : const <TextEditingDelta>[];
    return AndroidImeEditResult(
      value: newValue,
      textChanged: false,
      stateChanged: stateChanged,
      deltas: deltas,
    );
  }
}
