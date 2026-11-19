// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:typed_data';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter/src/services/_android_input_connection_semantics.dart';
import 'package:flutter/src/services/_android_sync_text_input.dart';
import 'package:flutter/src/services/_android_sync_text_input_bridge.dart';
import 'package:flutter_test/flutter_test.dart';

class _NotifyCall {
  _NotifyCall({
    required this.clientId,
    required this.flags,
    required this.selectionStart,
    required this.selectionEnd,
    required this.composingStart,
    required this.composingEnd,
  });

  final int clientId;
  final int flags;
  final int selectionStart;
  final int selectionEnd;
  final int composingStart;
  final int composingEnd;
}

class _FakeBridge implements AndroidSyncTextInputBridge {
  int registerResult = AndroidSyncImeSupportStatus.supported;
  int registerCallCount = 0;
  int unregisterCallCount = 0;
  bool notifyAcceptResult = true;
  AndroidSyncImeRequestHandler? registeredHandler;
  final List<_NotifyCall> notifyCalls = <_NotifyCall>[];

  @override
  int registerHandler(AndroidSyncImeRequestHandler handler) {
    registerCallCount++;
    if (registerResult == AndroidSyncImeSupportStatus.supported) {
      registeredHandler = handler;
    }
    return registerResult;
  }

  @override
  void unregisterHandler() {
    unregisterCallCount++;
    registeredHandler = null;
  }

  @override
  bool notifyEditingState({
    required int clientId,
    required int flags,
    required int selectionStart,
    required int selectionEnd,
    required int composingStart,
    required int composingEnd,
  }) {
    notifyCalls.add(
      _NotifyCall(
        clientId: clientId,
        flags: flags,
        selectionStart: selectionStart,
        selectionEnd: selectionEnd,
        composingStart: composingStart,
        composingEnd: composingEnd,
      ),
    );
    return notifyAcceptResult;
  }
}

class _DecodedResponse {
  _DecodedResponse({
    required this.status,
    required this.flags,
    required this.selectionStart,
    required this.selectionEnd,
    required this.composingStart,
    required this.composingEnd,
    required this.intResult,
    required this.hasText,
    required this.text,
  });

  final int status;
  final int flags;
  final int selectionStart;
  final int selectionEnd;
  final int composingStart;
  final int composingEnd;
  final int intResult;
  final bool hasText;
  final String? text;

  bool get isOk => status == AndroidSyncTextInputProtocol.statusOk;
  bool get isCommitted => (flags & AndroidSyncTextInputProtocol.respFlagCommitted) != 0;
  bool get isStateChanged => (flags & AndroidSyncTextInputProtocol.respFlagStateChanged) != 0;
  bool get isTextChanged => (flags & AndroidSyncTextInputProtocol.respFlagTextChanged) != 0;
  bool get isDiverged => (flags & AndroidSyncTextInputProtocol.respFlagDiverged) != 0;
  bool get isComposingChangedByFramework =>
      (flags & AndroidSyncTextInputProtocol.respFlagComposingChangedByFramework) != 0;
  bool get isClientChanged => (flags & AndroidSyncTextInputProtocol.respFlagClientChanged) != 0;
  bool get isBatchActive => (flags & AndroidSyncTextInputProtocol.respFlagBatchActive) != 0;
}

Uint8List _encodeNoArgs(int opcode, int clientId) {
  final out = Uint8List(8);
  final data = ByteData.sublistView(out);
  data.setInt32(0, opcode, Endian.little);
  data.setInt32(4, clientId, Endian.little);
  return out;
}

Uint8List _encodeOneInt(int opcode, int clientId, int a0) {
  final out = Uint8List(12);
  final data = ByteData.sublistView(out);
  data.setInt32(0, opcode, Endian.little);
  data.setInt32(4, clientId, Endian.little);
  data.setInt32(8, a0, Endian.little);
  return out;
}

Uint8List _encodeTwoInts(int opcode, int clientId, int a0, int a1) {
  final out = Uint8List(16);
  final data = ByteData.sublistView(out);
  data.setInt32(0, opcode, Endian.little);
  data.setInt32(4, clientId, Endian.little);
  data.setInt32(8, a0, Endian.little);
  data.setInt32(12, a1, Endian.little);
  return out;
}

Uint8List _encodeThreeInts(int opcode, int clientId, int a0, int a1, int a2) {
  final out = Uint8List(20);
  final data = ByteData.sublistView(out);
  data.setInt32(0, opcode, Endian.little);
  data.setInt32(4, clientId, Endian.little);
  data.setInt32(8, a0, Endian.little);
  data.setInt32(12, a1, Endian.little);
  data.setInt32(16, a2, Endian.little);
  return out;
}

Uint8List _encodeStringAndInt(int opcode, int clientId, String text, int a0) {
  final out = Uint8List(8 + 4 + text.length * 2 + 4);
  final data = ByteData.sublistView(out);
  data.setInt32(0, opcode, Endian.little);
  data.setInt32(4, clientId, Endian.little);
  data.setInt32(8, text.length, Endian.little);
  var offset = 12;
  for (var i = 0; i < text.length; i++) {
    data.setUint16(offset, text.codeUnitAt(i), Endian.little);
    offset += 2;
  }
  data.setInt32(offset, a0, Endian.little);
  return out;
}

Uint8List _encodeString(int opcode, int clientId, String text) {
  final out = Uint8List(8 + 4 + text.length * 2);
  final data = ByteData.sublistView(out);
  data.setInt32(0, opcode, Endian.little);
  data.setInt32(4, clientId, Endian.little);
  data.setInt32(8, text.length, Endian.little);
  var offset = 12;
  for (var i = 0; i < text.length; i++) {
    data.setUint16(offset, text.codeUnitAt(i), Endian.little);
    offset += 2;
  }
  return out;
}

Uint8List _encodeReplaceText(int clientId, int start, int end, String text, int newCursorPos) {
  final out = Uint8List(8 + 8 + 4 + text.length * 2 + 4);
  final data = ByteData.sublistView(out);
  data.setInt32(0, AndroidSyncTextInputProtocol.opReplaceText, Endian.little);
  data.setInt32(4, clientId, Endian.little);
  data.setInt32(8, start, Endian.little);
  data.setInt32(12, end, Endian.little);
  data.setInt32(16, text.length, Endian.little);
  var offset = 20;
  for (var i = 0; i < text.length; i++) {
    data.setUint16(offset, text.codeUnitAt(i), Endian.little);
    offset += 2;
  }
  data.setInt32(offset, newCursorPos, Endian.little);
  return out;
}

_DecodedResponse _decodeResponse(Uint8List? raw) {
  expect(raw, isNotNull);
  final Uint8List bytes = raw!;
  expect(bytes.length, greaterThanOrEqualTo(AndroidSyncTextInputProtocol.responseHeaderBytes));
  final data = ByteData.sublistView(bytes);
  final int status = data.getInt32(0, Endian.little);
  final int flags = data.getInt32(4, Endian.little);
  final int selStart = data.getInt32(8, Endian.little);
  final int selEnd = data.getInt32(12, Endian.little);
  final int compStart = data.getInt32(16, Endian.little);
  final int compEnd = data.getInt32(20, Endian.little);
  final int intResult = data.getInt32(24, Endian.little);
  final hasText = (flags & AndroidSyncTextInputProtocol.respFlagHasText) != 0;
  String? text;
  if (hasText) {
    final int len = data.getInt32(28, Endian.little);
    if (len >= 0) {
      final units = Uint16List(len);
      for (var i = 0; i < len; i++) {
        units[i] = data.getUint16(32 + i * 2, Endian.little);
      }
      text = String.fromCharCodes(units);
    }
  }
  return _DecodedResponse(
    status: status,
    flags: flags,
    selectionStart: selStart,
    selectionEnd: selEnd,
    composingStart: compStart,
    composingEnd: compEnd,
    intResult: intResult,
    hasText: hasText,
    text: text,
  );
}

class _DeltaRecordingClient with TextInputClient implements DeltaTextInputClient {
  _DeltaRecordingClient([this._value = TextEditingValue.empty]);

  TextEditingValue _value;
  final List<TextEditingDelta> receivedDeltas = <TextEditingDelta>[];

  @override
  TextEditingValue? get currentTextEditingValue => _value;

  @override
  AutofillScope? get currentAutofillScope => null;

  @override
  void updateEditingValue(TextEditingValue value) {
    _value = value;
  }

  @override
  void updateEditingValueWithDeltas(List<TextEditingDelta> textEditingDeltas) {
    receivedDeltas.addAll(textEditingDeltas);
    for (final delta in textEditingDeltas) {
      _value = delta.apply(_value);
    }
  }

  @override
  void performAction(TextInputAction action) {}

  @override
  void performPrivateCommand(String action, Map<String, dynamic> data) {}

  @override
  void updateFloatingCursor(RawFloatingCursorPoint point) {}

  @override
  void showAutocorrectionPromptRect(int start, int end) {}

  @override
  void connectionClosed() {}
}

class _NullValueClient with TextInputClient {
  final List<TextEditingValue> receivedValues = <TextEditingValue>[];

  @override
  TextEditingValue? get currentTextEditingValue => null;

  @override
  AutofillScope? get currentAutofillScope => null;

  @override
  void updateEditingValue(TextEditingValue value) {
    receivedValues.add(value);
  }

  @override
  void performAction(TextInputAction action) {}

  @override
  void performPrivateCommand(String action, Map<String, dynamic> data) {}

  @override
  void updateFloatingCursor(RawFloatingCursorPoint point) {}

  @override
  void showAutocorrectionPromptRect(int start, int end) {}

  @override
  void connectionClosed() {}
}

class _ThrowingClient with TextInputClient {
  @override
  TextEditingValue? get currentTextEditingValue => TextEditingValue.empty;

  @override
  AutofillScope? get currentAutofillScope => null;

  @override
  void updateEditingValue(TextEditingValue value) {
    throw StateError('intentional callback error');
  }

  @override
  void performAction(TextInputAction action) {}

  @override
  void performPrivateCommand(String action, Map<String, dynamic> data) {}

  @override
  void updateFloatingCursor(RawFloatingCursorPoint point) {}

  @override
  void showAutocorrectionPromptRect(int start, int end) {}

  @override
  void connectionClosed() {}
}

class _RecordingInputControl with TextInputControl {
  final List<TextEditingValue> values = <TextEditingValue>[];

  @override
  void setEditingState(TextEditingValue value) {
    values.add(value);
  }
}

void main() {
  group('AndroidInputConnectionSemantics', () {
    test('commitText, setComposingText, setComposingRegion, finishComposingText and deltas', () {
      const initial = TextEditingValue(
        text: 'hello',
        selection: TextSelection.collapsed(offset: 5),
      );

      final AndroidImeEditResult r1 = AndroidInputConnectionSemantics.commitText(initial, '!', 1);
      expect(r1.value.text, 'hello!');
      expect(r1.value.selection, const TextSelection.collapsed(offset: 6));
      expect(r1.textChanged, isTrue);
      expect(r1.deltas.single, isA<TextEditingDeltaInsertion>());
      expect(r1.deltas.single.apply(initial), r1.value);

      final AndroidImeEditResult r2 = AndroidInputConnectionSemantics.setComposingText(
        r1.value,
        ' world',
        1,
      );
      expect(r2.value.text, 'hello! world');
      expect(r2.value.composing, const TextRange(start: 6, end: 12));
      expect(r2.deltas.single.apply(r1.value), r2.value);

      // Replacing composing region produces TextEditingDeltaReplacement.
      final AndroidImeEditResult r3 = AndroidInputConnectionSemantics.setComposingText(
        r2.value,
        ' dart',
        1,
      );
      expect(r3.value.text, 'hello! dart');
      expect(r3.value.composing, const TextRange(start: 6, end: 11));
      expect(r3.deltas.single, isA<TextEditingDeltaReplacement>());
      expect(r3.deltas.single.apply(r2.value), r3.value);

      // Empty composing text commits and clears composing span.
      final AndroidImeEditResult r4 = AndroidInputConnectionSemantics.setComposingText(
        r3.value,
        '',
        1,
      );
      expect(r4.value.text, 'hello!');
      expect(r4.value.composing, TextRange.empty);
      expect(r4.deltas.single, isA<TextEditingDeltaDeletion>());
      expect(r4.deltas.single.apply(r3.value), r4.value);

      // setComposingRegion reversed + clamped, then finishComposingText.
      final AndroidImeEditResult r5 = AndroidInputConnectionSemantics.setComposingRegion(
        r4.value,
        99,
        1,
      );
      expect(r5.value.composing, const TextRange(start: 1, end: 6));
      expect(r5.deltas.single, isA<TextEditingDeltaNonTextUpdate>());
      expect(r5.deltas.single.apply(r4.value), r5.value);

      final AndroidImeEditResult r6 = AndroidInputConnectionSemantics.finishComposingText(r5.value);
      expect(r6.value.composing, TextRange.empty);
      expect(r6.deltas.single.apply(r5.value), r6.value);
    });

    test('deleteSurroundingText preserves active composing region', () {
      const initial = TextEditingValue(
        text: 'abcdefghi',
        selection: TextSelection(baseOffset: 4, extentOffset: 5),
        composing: TextRange(start: 3, end: 6),
      );
      final AndroidImeEditResult res = AndroidInputConnectionSemantics.deleteSurroundingText(
        initial,
        2,
        2,
      );
      expect(res.value.text, 'adefi');
      expect(res.value.selection, const TextSelection(baseOffset: 2, extentOffset: 3));
      expect(res.value.composing, const TextRange(start: 1, end: 4));

      // Applying the emitted deltas sequentially reproduces the exact end value.
      var applied = initial;
      for (final TextEditingDelta delta in res.deltas) {
        applied = delta.apply(applied);
      }
      expect(applied, res.value);
    });

    test('deleteSurroundingTextInCodePoints handles surrogates and aborts on unpaired surrogates', () {
      const emoji = TextEditingValue(
        text: 'a\uD83D\uDE00b\uD83D\uDE80c',
        selection: TextSelection(baseOffset: 3, extentOffset: 4),
      );
      final AndroidImeEditResult res =
          AndroidInputConnectionSemantics.deleteSurroundingTextInCodePoints(emoji, 1, 1);
      expect(res.handled, isTrue);
      expect(res.value.text, 'abc');
      expect(res.value.selection, const TextSelection(baseOffset: 1, extentOffset: 2));

      // Unpaired high surrogate before cursor -> matches AOSP BaseInputConnection: no-op, handled.
      const unpairedBefore = TextEditingValue(
        text: 'a\uD83Db',
        selection: TextSelection.collapsed(offset: 2),
      );
      final AndroidImeEditResult failBefore =
          AndroidInputConnectionSemantics.deleteSurroundingTextInCodePoints(unpairedBefore, 1, 0);
      expect(failBefore.handled, isTrue);
      expect(failBefore.stateChanged, isFalse);
      expect(failBefore.value, unpairedBefore);

      // Unpaired low surrogate after cursor -> matches AOSP BaseInputConnection: no-op, handled.
      const unpairedAfter = TextEditingValue(
        text: 'a\uDE00b',
        selection: TextSelection.collapsed(offset: 1),
      );
      final AndroidImeEditResult failAfter =
          AndroidInputConnectionSemantics.deleteSurroundingTextInCodePoints(unpairedAfter, 0, 1);
      expect(failAfter.handled, isTrue);
      expect(failAfter.stateChanged, isFalse);
      expect(failAfter.value, unpairedAfter);
    });

    test('moveCursor handles grapheme clusters horizontally and newline lines vertically', () {
      // Family emoji ZWJ grapheme cluster: 👨‍👩‍👧‍👦 (11 UTF-16 code units).
      const family = '\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66';
      const initial = TextEditingValue(
        text: 'x${family}y',
        selection: TextSelection.collapsed(offset: 1),
      );
      final AndroidImeEditResult right = AndroidInputConnectionSemantics.moveCursor(
        initial,
        direction: AndroidInputConnectionSemantics.dirRight,
        extendSelection: false,
      );
      expect(right.value.selection, const TextSelection.collapsed(offset: 1 + family.length));

      final AndroidImeEditResult left = AndroidInputConnectionSemantics.moveCursor(
        right.value,
        direction: AndroidInputConnectionSemantics.dirLeft,
        extendSelection: false,
      );
      expect(left.value.selection, const TextSelection.collapsed(offset: 1));

      // Multi-line vertical movement preserves column and clamps to shorter lines.
      const multiline = TextEditingValue(
        text: '12345\nab\n12345',
        selection: TextSelection.collapsed(offset: 4),
      );
      final AndroidImeEditResult down1 = AndroidInputConnectionSemantics.moveCursor(
        multiline,
        direction: AndroidInputConnectionSemantics.dirDown,
        extendSelection: false,
      );
      expect(down1.value.selection, const TextSelection.collapsed(offset: 8)); // end of "ab"

      final AndroidImeEditResult down2 = AndroidInputConnectionSemantics.moveCursor(
        down1.value,
        direction: AndroidInputConnectionSemantics.dirDown,
        extendSelection: false,
      );
      expect(down2.value.selection, const TextSelection.collapsed(offset: 11)); // col 2 on line 3

      final AndroidImeEditResult up = AndroidInputConnectionSemantics.moveCursor(
        down2.value,
        direction: AndroidInputConnectionSemantics.dirUp,
        extendSelection: true,
      );
      expect(up.value.selection, const TextSelection(baseOffset: 11, extentOffset: 8));
    });

    test('getCursorCapsMode matches Android TextUtils rules', () {
      const int allCaps =
          AndroidInputConnectionSemantics.capModeCharacters |
          AndroidInputConnectionSemantics.capModeWords |
          AndroidInputConnectionSemantics.capModeSentences;

      expect(
        AndroidInputConnectionSemantics.getCursorCapsMode(
          const TextEditingValue(selection: TextSelection.collapsed(offset: 0)),
          AndroidInputConnectionSemantics.capModeSentences,
        ),
        AndroidInputConnectionSemantics.capModeWords,
      );
      expect(
        AndroidInputConnectionSemantics.getCursorCapsMode(
          const TextEditingValue(text: 'Hello. ', selection: TextSelection.collapsed(offset: 7)),
          AndroidInputConnectionSemantics.capModeSentences,
        ),
        AndroidInputConnectionSemantics.capModeSentences,
      );
      // Abbreviation "e.g. " must not trigger sentence caps.
      expect(
        AndroidInputConnectionSemantics.getCursorCapsMode(
          const TextEditingValue(text: 'e.g. ', selection: TextSelection.collapsed(offset: 5)),
          AndroidInputConnectionSemantics.capModeSentences,
        ),
        0,
      );
      expect(
        AndroidInputConnectionSemantics.getCursorCapsMode(
          const TextEditingValue(text: 'hello', selection: TextSelection.collapsed(offset: 3)),
          allCaps,
        ),
        AndroidInputConnectionSemantics.capModeCharacters,
      );
      // Cyrillic/Greek abbreviations and Unicode Pi/Pf quote parity with Android TextUtils.
      expect(
        AndroidInputConnectionSemantics.getCursorCapsMode(
          const TextEditingValue(text: 'т.е. ', selection: TextSelection.collapsed(offset: 5)),
          AndroidInputConnectionSemantics.capModeSentences,
        ),
        0,
      );
      expect(
        AndroidInputConnectionSemantics.getCursorCapsMode(
          const TextEditingValue(text: 'Привет. ', selection: TextSelection.collapsed(offset: 8)),
          AndroidInputConnectionSemantics.capModeSentences,
        ),
        AndroidInputConnectionSemantics.capModeSentences,
      );
      expect(
        AndroidInputConnectionSemantics.getCursorCapsMode(
          const TextEditingValue(text: 'π.χ. ', selection: TextSelection.collapsed(offset: 5)),
          AndroidInputConnectionSemantics.capModeSentences,
        ),
        0,
      );
      expect(
        AndroidInputConnectionSemantics.getCursorCapsMode(
          const TextEditingValue(text: 'hello. «', selection: TextSelection.collapsed(offset: 8)),
          AndroidInputConnectionSemantics.capModeSentences,
        ),
        0,
      );
      expect(
        AndroidInputConnectionSemantics.getCursorCapsMode(
          const TextEditingValue(
            text: 'hello.\u201D ',
            selection: TextSelection.collapsed(offset: 8),
          ),
          AndroidInputConnectionSemantics.capModeSentences,
        ),
        0,
      );
    });

    test('deleteSurroundingText ignores collapsed composing spans without OOB/negative shift', () {
      for (final collapsedComposing in <TextRange>[
        const TextRange.collapsed(0),
        const TextRange.collapsed(5),
      ]) {
        final initial = TextEditingValue(
          text: 'abcdef',
          selection: const TextSelection.collapsed(offset: 3),
          composing: collapsedComposing,
        );
        final AndroidImeEditResult r1 = AndroidInputConnectionSemantics.deleteSurroundingText(
          initial,
          2,
          2,
        );
        expect(r1.value.text, 'af');
        expect(r1.value.selection, const TextSelection.collapsed(offset: 1));
        expect(r1.value.composing, TextRange.empty);

        final AndroidImeEditResult r2 =
            AndroidInputConnectionSemantics.deleteSurroundingTextInCodePoints(initial, 2, 2);
        expect(r2.value.text, 'af');
        expect(r2.value.selection, const TextSelection.collapsed(offset: 1));
        expect(r2.value.composing, TextRange.empty);
      }
    });

    test('insertKeyCharacter, cut, and paste preserve/adjust active composing spans', () {
      // Insert newline at end of composing -> preserves composing [0, 5).
      const atEnd = TextEditingValue(
        text: 'hello',
        selection: TextSelection.collapsed(offset: 5),
        composing: TextRange(start: 0, end: 5),
      );
      final AndroidImeEditResult rEnd = AndroidInputConnectionSemantics.insertKeyCharacter(
        atEnd,
        '\n',
      );
      expect(rEnd.handled, isTrue);
      expect(rEnd.value.text, 'hello\n');
      expect(rEnd.value.selection, const TextSelection.collapsed(offset: 6));
      expect(rEnd.value.composing, const TextRange(start: 0, end: 5));

      // Insert inside composing -> expands composing.
      const inside = TextEditingValue(
        text: 'helo',
        selection: TextSelection.collapsed(offset: 2),
        composing: TextRange(start: 0, end: 4),
      );
      final AndroidImeEditResult rIn = AndroidInputConnectionSemantics.insertKeyCharacter(
        inside,
        'l',
      );
      expect(rIn.value.text, 'hello');
      expect(rIn.value.composing, const TextRange(start: 0, end: 5));

      // Insert before composing -> shifts composing right.
      const before = TextEditingValue(
        text: 'hello',
        selection: TextSelection.collapsed(offset: 0),
        composing: TextRange(start: 0, end: 5),
      );
      final AndroidImeEditResult rBefore = AndroidInputConnectionSemantics.insertKeyCharacter(
        before,
        '>',
      );
      expect(rBefore.value.text, '>hello');
      expect(rBefore.value.composing, const TextRange(start: 1, end: 6));

      // Invalid selection returns handled: false.
      const invalidSel = TextEditingValue(text: 'hello');
      final AndroidImeEditResult rInvalid = AndroidInputConnectionSemantics.insertKeyCharacter(
        invalidSel,
        'a',
      );
      expect(rInvalid.handled, isFalse);

      // Cut before disjoint composing shifts composing left; paste before shifts right.
      const cutBefore = TextEditingValue(
        text: 'abHELLO',
        selection: TextSelection(baseOffset: 0, extentOffset: 2),
        composing: TextRange(start: 2, end: 7),
      );
      final AndroidImeEditResult cutRes = AndroidInputConnectionSemantics.cut(cutBefore);
      expect(cutRes.value.text, 'HELLO');
      expect(cutRes.value.composing, const TextRange(start: 0, end: 5));

      final AndroidImeEditResult pasteRes = AndroidInputConnectionSemantics.paste(
        cutRes.value,
        'XYZ',
      );
      expect(pasteRes.value.text, 'XYZHELLO');
      expect(pasteRes.value.composing, const TextRange(start: 3, end: 8));
    });

    test('moveCursor vertical movement snaps off UTF-16 surrogate pair midpoints', () {
      // Line 1: 'ab' (col 1 = offset 1), Line 2: '😀x' (offset 3 = high surrogate, 4 = low surrogate).
      const text = 'ab\n\uD83D\uDE00x';
      const initial = TextEditingValue(text: text, selection: TextSelection.collapsed(offset: 1));
      final AndroidImeEditResult down = AndroidInputConnectionSemantics.moveCursor(
        initial,
        direction: AndroidInputConnectionSemantics.dirDown,
        extendSelection: false,
      );
      // Must snap to 3 (start of 😀), never 4 (middle of surrogate pair).
      expect(down.value.selection, const TextSelection.collapsed(offset: 3));
    });
  });

  group('AndroidSyncTextInput integration', () {
    late _FakeBridge fakeBridge;

    setUp(() {
      fakeBridge = _FakeBridge();
      AndroidSyncTextInput.instance.debugSetBridgeForTesting(fakeBridge);
    });

    tearDown(() {
      AndroidSyncTextInput.instance.debugSetBridgeForTesting(null);
      TextInput.setInputControl(null);
    });

    testWidgets('lazy registration retries after retryLater and caches unsupported', (
      WidgetTester tester,
    ) async {
      // 1. retryLater -> retries on next attach.
      fakeBridge.registerResult = AndroidSyncImeSupportStatus.retryLater;
      final client = _DeltaRecordingClient();
      final TextInputConnection c1 = TextInput.attach(client, const TextInputConfiguration());
      expect(AndroidSyncTextInput.instance.isActive, isFalse);
      expect(fakeBridge.registerCallCount, 1);
      c1.close();

      fakeBridge.registerResult = AndroidSyncImeSupportStatus.supported;
      final TextInputConnection c2 = TextInput.attach(client, const TextInputConfiguration());
      expect(AndroidSyncTextInput.instance.isActive, isTrue);
      expect(fakeBridge.registerCallCount, 2);
      c2.close();

      // 2. unsupported -> cached permanently without retrying.
      final unsupportedBridge = _FakeBridge()
        ..registerResult = AndroidSyncImeSupportStatus.unsupported;
      AndroidSyncTextInput.instance.debugSetBridgeForTesting(unsupportedBridge);

      final TextInputConnection c3 = TextInput.attach(client, const TextInputConfiguration());
      c3.close();
      final TextInputConnection c4 = TextInput.attach(client, const TextInputConfiguration());
      c4.close();
      expect(AndroidSyncTextInput.instance.isActive, isFalse);
      expect(unsupportedBridge.registerCallCount, 1);
    });

    testWidgets('TextField typing, formatter rejection, rewriting, and programmatic updates', (
      WidgetTester tester,
    ) async {
      final controller = TextEditingController(text: '12');
      addTearDown(controller.dispose);
      final focusNode = FocusNode();
      addTearDown(focusNode.dispose);

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: TextField(
              controller: controller,
              focusNode: focusNode,
              inputFormatters: <TextInputFormatter>[FilteringTextInputFormatter.digitsOnly],
            ),
          ),
        ),
      );

      await tester.tap(find.byType(TextField));
      await tester.pump();

      expect(AndroidSyncTextInput.instance.isActive, isTrue);
      final int clientId = AndroidSyncTextInput.instance.delegate!.currentClientId!;

      // Initial state query returns bounded initial text & selection.
      final _DecodedResponse initResp = _decodeResponse(
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeNoArgs(AndroidSyncTextInputProtocol.opQueryInitialState, clientId),
        ),
      );
      expect(initResp.isOk, isTrue);
      expect(initResp.text, '12');
      expect(initResp.intResult, 0);

      // Valid digit commit -> updates controller synchronously, NOT diverged, suppresses echo.
      fakeBridge.notifyCalls.clear();
      final _DecodedResponse digitResp = _decodeResponse(
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeStringAndInt(AndroidSyncTextInputProtocol.opCommitText, clientId, '3', 1),
        ),
      );
      expect(digitResp.isOk, isTrue);
      expect(digitResp.isCommitted, isTrue);
      expect(digitResp.isTextChanged, isTrue);
      expect(digitResp.isDiverged, isFalse);
      expect(controller.text, '123');
      expect(
        fakeBridge.notifyCalls,
        isEmpty,
        reason: 'Edits inside sync IME callbacks must not echo notifyEditingState back to Android',
      );

      // Rejected non-digit commit -> formatter strips 'a' -> RESP_FLAG_DIVERGED set.
      final _DecodedResponse rejectedResp = _decodeResponse(
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeStringAndInt(AndroidSyncTextInputProtocol.opCommitText, clientId, 'a', 1),
        ),
      );
      expect(rejectedResp.isOk, isTrue);
      expect(rejectedResp.isCommitted, isTrue);
      expect(rejectedResp.isDiverged, isTrue);
      expect(controller.text, '123');

      // Composing text stripped by digitsOnly formatter -> RESP_FLAG_COMPOSING_CHANGED_BY_FRAMEWORK.
      final _DecodedResponse composingRejectedResp = _decodeResponse(
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeStringAndInt(AndroidSyncTextInputProtocol.opSetComposingText, clientId, 'abc', 1),
        ),
      );
      expect(composingRejectedResp.isOk, isTrue);
      expect(composingRejectedResp.isDiverged, isTrue);
      expect(composingRejectedResp.isComposingChangedByFramework, isTrue);
      expect(controller.text, '123');

      // Programmatic controller.text update notifies Android synchronously with textChanged bit.
      fakeBridge.notifyCalls.clear();
      controller.value = const TextEditingValue(
        text: '999',
        selection: TextSelection.collapsed(offset: 3),
      );
      await tester.pump();
      expect(fakeBridge.notifyCalls, hasLength(1));
      expect(fakeBridge.notifyCalls.single.clientId, clientId);
      expect(
        fakeBridge.notifyCalls.single.flags & AndroidSyncTextInputProtocol.notifyFlagTextChanged,
        isNonZero,
      );
      expect(fakeBridge.notifyCalls.single.selectionStart, 3);
      expect(fakeBridge.notifyCalls.single.selectionEnd, 3);
    });

    testWidgets('DeltaTextInputClient receives granular deltas directly and across batches', (
      WidgetTester tester,
    ) async {
      final client = _DeltaRecordingClient(
        const TextEditingValue(text: 'hi', selection: TextSelection.collapsed(offset: 2)),
      );
      final TextInputConnection connection = TextInput.attach(
        client,
        const TextInputConfiguration(enableDeltaModel: true),
      );
      addTearDown(connection.close);
      final int clientId = AndroidSyncTextInput.instance.delegate!.currentClientId!;

      // Nested batch: commitText + setComposingText -> committed as a single batch on endBatchEdit.
      _DecodedResponse r = _decodeResponse(
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeNoArgs(AndroidSyncTextInputProtocol.opBeginBatchEdit, clientId),
        ),
      );
      expect(r.isBatchActive, isTrue);

      r = _decodeResponse(
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeNoArgs(AndroidSyncTextInputProtocol.opBeginBatchEdit, clientId),
        ),
      );
      expect(r.isBatchActive, isTrue);

      r = _decodeResponse(
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeStringAndInt(AndroidSyncTextInputProtocol.opCommitText, clientId, ' ', 1),
        ),
      );
      expect(r.isBatchActive, isTrue);
      expect(client.receivedDeltas, isEmpty);

      r = _decodeResponse(
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeStringAndInt(AndroidSyncTextInputProtocol.opSetComposingText, clientId, 'ok', 1),
        ),
      );
      expect(r.isBatchActive, isTrue);
      expect(client.receivedDeltas, isEmpty);

      // Inner endBatchEdit keeps batch open.
      r = _decodeResponse(
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeNoArgs(AndroidSyncTextInputProtocol.opEndBatchEdit, clientId),
        ),
      );
      expect(r.isBatchActive, isTrue);
      expect(client.receivedDeltas, isEmpty);

      // Outer endBatchEdit commits both deltas at once.
      r = _decodeResponse(
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeNoArgs(AndroidSyncTextInputProtocol.opEndBatchEdit, clientId),
        ),
      );
      expect(r.isCommitted, isTrue);
      expect(r.isBatchActive, isFalse);
      expect(client.receivedDeltas, hasLength(2));
      expect(client.currentTextEditingValue!.text, 'hi ok');

      // Leaked batch unwound & committed by opCloseConnection.
      client.receivedDeltas.clear();
      AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
        _encodeNoArgs(AndroidSyncTextInputProtocol.opBeginBatchEdit, clientId),
      );
      AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
        _encodeStringAndInt(AndroidSyncTextInputProtocol.opCommitText, clientId, '!', 1),
      );
      expect(client.receivedDeltas, isEmpty);

      final _DecodedResponse closeResp = _decodeResponse(
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeNoArgs(AndroidSyncTextInputProtocol.opCloseConnection, clientId),
        ),
      );
      expect(closeResp.isCommitted, isTrue);
      expect(client.receivedDeltas, hasLength(1));
      expect(client.currentTextEditingValue!.text, 'hi !');
    });

    testWidgets('OTP focus switch and Navigator.pop inside onChanged set respFlagClientChanged', (
      WidgetTester tester,
    ) async {
      final f1 = FocusNode();
      final f2 = FocusNode();
      addTearDown(f1.dispose);
      addTearDown(f2.dispose);
      final c1 = TextEditingController();
      final c2 = TextEditingController();
      addTearDown(c1.dispose);
      addTearDown(c2.dispose);

      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: Column(
              children: <Widget>[
                TextField(
                  controller: c1,
                  focusNode: f1,
                  onChanged: (String v) {
                    if (v.length == 1) {
                      f2.requestFocus();
                    }
                  },
                ),
                TextField(controller: c2, focusNode: f2),
              ],
            ),
          ),
        ),
      );

      await tester.tap(find.byType(TextField).first);
      await tester.pump();
      final int firstClientId = AndroidSyncTextInput.instance.delegate!.currentClientId!;

      final _DecodedResponse otpResp = _decodeResponse(
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeStringAndInt(AndroidSyncTextInputProtocol.opCommitText, firstClientId, '7', 1),
        ),
      );
      await tester.pump();
      expect(otpResp.isOk, isTrue);
      expect(c1.text, '7');
      expect(f2.hasFocus, isTrue);

      // Direct synchronous connection close inside updateEditingValue sets respFlagClientChanged.
      late TextInputConnection selfClosingConn;
      final selfClosingClient = _DeltaRecordingClient();
      final closingWrapper = _CallbackClient(
        onUpdate: (TextEditingValue v) {
          selfClosingClient.updateEditingValue(v);
          selfClosingConn.close();
        },
      );
      selfClosingConn = TextInput.attach(closingWrapper, const TextInputConfiguration());
      final int closingId = AndroidSyncTextInput.instance.delegate!.currentClientId!;

      final _DecodedResponse closedResp = _decodeResponse(
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeStringAndInt(AndroidSyncTextInputProtocol.opCommitText, closingId, 'x', 1),
        ),
      );
      expect(closedResp.isOk, isTrue);
      expect(closedResp.isClientChanged, isTrue);
    });

    testWidgets(
      'TestTextInput fallback when notifyEditingState returns false and custom TextInputControl',
      (WidgetTester tester) async {
        // Simulate TestTextInput intercepting TextInput.setClient so Android returns false from
        // notifyEditingState -> framework must still send TextInput.setEditingState over the channel.
        fakeBridge.notifyAcceptResult = false;
        final client = _DeltaRecordingClient();
        final TextInputConnection conn = TextInput.attach(client, const TextInputConfiguration());
        addTearDown(conn.close);

        const expectedValue = TextEditingValue(
          text: 'from_test',
          selection: TextSelection.collapsed(offset: 9),
        );
        conn.setEditingState(expectedValue);
        expect(tester.testTextInput.editingState?['text'], 'from_test');

        // Custom TextInputControl also receives updates without crashing.
        final customControl = _RecordingInputControl();
        TextInput.setInputControl(customControl);
        final int clientId = AndroidSyncTextInput.instance.delegate!.currentClientId!;
        fakeBridge.notifyAcceptResult = true;
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeStringAndInt(AndroidSyncTextInputProtocol.opCommitText, clientId, '!', 1),
        );
        expect(customControl.values, isNotEmpty);
      },
    );

    testWidgets('null-value client fallback, all read/edit opcodes, and error reporting', (
      WidgetTester tester,
    ) async {
      final nullClient = _NullValueClient();
      final TextInputConnection conn = TextInput.attach(nullClient, const TextInputConfiguration());
      addTearDown(conn.close);
      conn.setEditingState(
        const TextEditingValue(
          text: 'abcdef',
          selection: TextSelection(baseOffset: 2, extentOffset: 4),
        ),
      );
      final int clientId = AndroidSyncTextInput.instance.delegate!.currentClientId!;

      // Read opcodes still work via _lastClientEditingValue fallback.
      final _DecodedResponse before = _decodeResponse(
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeTwoInts(AndroidSyncTextInputProtocol.opGetTextBeforeCursor, clientId, 2, 0),
        ),
      );
      expect(before.text, 'ab');

      final _DecodedResponse after = _decodeResponse(
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeTwoInts(AndroidSyncTextInputProtocol.opGetTextAfterCursor, clientId, 2, 0),
        ),
      );
      expect(after.text, 'ef');

      final _DecodedResponse selected = _decodeResponse(
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeOneInt(AndroidSyncTextInputProtocol.opGetSelectedText, clientId, 0),
        ),
      );
      expect(selected.text, 'cd');

      final _DecodedResponse surrounding = _decodeResponse(
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeThreeInts(AndroidSyncTextInputProtocol.opGetSurroundingText, clientId, 1, 1, 0),
        ),
      );
      expect(surrounding.text, 'bcde');
      expect(surrounding.selectionStart, 1);
      expect(surrounding.selectionEnd, 3);
      expect(surrounding.intResult, 1);

      // Cut, paste, replaceText, autofill, stale client.
      final _DecodedResponse cut = _decodeResponse(
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeNoArgs(AndroidSyncTextInputProtocol.opCut, clientId),
        ),
      );
      expect(cut.text, 'cd');
      expect(nullClient.receivedValues.last.text, 'abef');

      final _DecodedResponse paste = _decodeResponse(
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeString(AndroidSyncTextInputProtocol.opPaste, clientId, 'XY'),
        ),
      );
      expect(paste.isCommitted, isTrue);
      expect(nullClient.receivedValues.last.text, 'abXYef');

      final _DecodedResponse replaced = _decodeResponse(
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeReplaceText(clientId, 2, 4, 'Z', 1),
        ),
      );
      expect(replaced.isCommitted, isTrue);
      expect(nullClient.receivedValues.last.text, 'abZef');

      final _DecodedResponse autofilled = _decodeResponse(
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeString(AndroidSyncTextInputProtocol.opAutofillCurrentClient, clientId, 'auto'),
        ),
      );
      expect(autofilled.isCommitted, isTrue);
      expect(nullClient.receivedValues.last.text, 'auto');

      final _DecodedResponse stale = _decodeResponse(
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeNoArgs(AndroidSyncTextInputProtocol.opGetFullState, clientId + 999),
        ),
      );
      expect(stale.status, AndroidSyncTextInputProtocol.statusStaleClient);

      // Exception inside client callback is caught, reported to FlutterError.onError, and returns
      // statusError cleanly.
      final FlutterExceptionHandler? prevHandler = FlutterError.onError;
      FlutterErrorDetails? capturedError;
      FlutterError.onError = (FlutterErrorDetails details) {
        capturedError = details;
      };
      addTearDown(() => FlutterError.onError = prevHandler);

      final TextInputConnection throwingConn = TextInput.attach(
        _ThrowingClient(),
        const TextInputConfiguration(),
      );
      addTearDown(throwingConn.close);
      final int throwingId = AndroidSyncTextInput.instance.delegate!.currentClientId!;
      final _DecodedResponse errResp = _decodeResponse(
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeStringAndInt(AndroidSyncTextInputProtocol.opCommitText, throwingId, 'boom', 1),
        ),
      );
      expect(errResp.status, AndroidSyncTextInputProtocol.statusError);
      expect(capturedError, isNotNull);
      expect(capturedError!.exception, isA<StateError>());
    });

    testWidgets('golden binary wire-frame byte parity with Java SyncTextInputProtocol', (
      WidgetTester tester,
    ) async {
      // Exact byte vectors shared 1-for-1 with FlutterInputConnectionTest.java.
      const fixedClientId = 42;
      final client = _DeltaRecordingClient(
        const TextEditingValue(
          text: 'hi',
          selection: TextSelection(baseOffset: 1, extentOffset: 2),
        ),
      );
      final sync = AndroidSyncTextInput(bridge: fakeBridge);
      sync.delegate = _FixedIdDelegate(clientId: fixedClientId, client: client);
      expect(sync.ensureRegistered(), isTrue);

      Uint8List bytes(List<int> list) => Uint8List.fromList(list);

      // 1. OP_QUERY_INITIAL_STATE
      expect(
        sync.handleBinaryRequestForTesting(
          bytes(<int>[0x01, 0x00, 0x00, 0x00, 0x2A, 0x00, 0x00, 0x00]),
        ),
        bytes(<int>[
          0x01, 0x00, 0x00, 0x00, // STATUS_OK
          0x80, 0x00, 0x00, 0x00, // RESP_FLAG_HAS_TEXT
          0x01, 0x00, 0x00, 0x00, // selStart = 1
          0x02, 0x00, 0x00, 0x00, // selEnd = 2
          0xFF, 0xFF, 0xFF, 0xFF, // compStart = -1
          0xFF, 0xFF, 0xFF, 0xFF, // compEnd = -1
          0x00, 0x00, 0x00, 0x00, // subTextOffset = 0
          0x02, 0x00, 0x00, 0x00, // charLen = 2
          0x68, 0x00, 0x69, 0x00, // "hi"
        ]),
      );

      // 2. OP_COMMIT_TEXT ("a\uD83D\uDE00", 1)
      expect(
        sync.handleBinaryRequestForTesting(
          bytes(<int>[
            0x04,
            0x00,
            0x00,
            0x00,
            0x2A,
            0x00,
            0x00,
            0x00,
            0x03,
            0x00,
            0x00,
            0x00,
            0x61,
            0x00,
            0x3D,
            0xD8,
            0x00,
            0xDE,
            0x01,
            0x00,
            0x00,
            0x00,
          ]),
        ),
        bytes(<int>[
          0x01,
          0x00,
          0x00,
          0x00,
          0x07,
          0x00,
          0x00,
          0x00,
          0x04,
          0x00,
          0x00,
          0x00,
          0x04,
          0x00,
          0x00,
          0x00,
          0xFF,
          0xFF,
          0xFF,
          0xFF,
          0xFF,
          0xFF,
          0xFF,
          0xFF,
          0x00,
          0x00,
          0x00,
          0x00,
        ]),
      );

      // 3. OP_SET_COMPOSING_REGION (1, 4)
      expect(
        sync.handleBinaryRequestForTesting(
          bytes(<int>[
            0x06,
            0x00,
            0x00,
            0x00,
            0x2A,
            0x00,
            0x00,
            0x00,
            0x01,
            0x00,
            0x00,
            0x00,
            0x04,
            0x00,
            0x00,
            0x00,
          ]),
        ),
        bytes(<int>[
          0x01,
          0x00,
          0x00,
          0x00,
          0x03,
          0x00,
          0x00,
          0x00,
          0x04,
          0x00,
          0x00,
          0x00,
          0x04,
          0x00,
          0x00,
          0x00,
          0x01,
          0x00,
          0x00,
          0x00,
          0x04,
          0x00,
          0x00,
          0x00,
          0x00,
          0x00,
          0x00,
          0x00,
        ]),
      );

      // 4. OP_GET_CURSOR_ANCHOR_STATE -> returns composing substring ONLY.
      expect(
        sync.handleBinaryRequestForTesting(
          bytes(<int>[0x1B, 0x00, 0x00, 0x00, 0x2A, 0x00, 0x00, 0x00]),
        ),
        bytes(<int>[
          0x01,
          0x00,
          0x00,
          0x00,
          0x80,
          0x00,
          0x00,
          0x00,
          0x04,
          0x00,
          0x00,
          0x00,
          0x04,
          0x00,
          0x00,
          0x00,
          0x01,
          0x00,
          0x00,
          0x00,
          0x04,
          0x00,
          0x00,
          0x00,
          0x00,
          0x00,
          0x00,
          0x00,
          0x03,
          0x00,
          0x00,
          0x00,
          0x61,
          0x00,
          0x3D,
          0xD8,
          0x00,
          0xDE,
        ]),
      );

      // 5. OP_INSERT_KEY_CHARACTER ("\n") -> preserves composing [1, 4).
      expect(
        sync.handleBinaryRequestForTesting(
          bytes(<int>[
            0x1A,
            0x00,
            0x00,
            0x00,
            0x2A,
            0x00,
            0x00,
            0x00,
            0x01,
            0x00,
            0x00,
            0x00,
            0x0A,
            0x00,
          ]),
        ),
        bytes(<int>[
          0x01,
          0x00,
          0x00,
          0x00,
          0x07,
          0x00,
          0x00,
          0x00,
          0x05,
          0x00,
          0x00,
          0x00,
          0x05,
          0x00,
          0x00,
          0x00,
          0x01,
          0x00,
          0x00,
          0x00,
          0x04,
          0x00,
          0x00,
          0x00,
          0x00,
          0x00,
          0x00,
          0x00,
        ]),
      );

      // 6. OP_GET_SURROUNDING_TEXT (2, 1, 0)
      expect(
        sync.handleBinaryRequestForTesting(
          bytes(<int>[
            0x0F,
            0x00,
            0x00,
            0x00,
            0x2A,
            0x00,
            0x00,
            0x00,
            0x02,
            0x00,
            0x00,
            0x00,
            0x01,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
          ]),
        ),
        bytes(<int>[
          0x01,
          0x00,
          0x00,
          0x00,
          0x80,
          0x00,
          0x00,
          0x00,
          0x02,
          0x00,
          0x00,
          0x00,
          0x02,
          0x00,
          0x00,
          0x00,
          0x01,
          0x00,
          0x00,
          0x00,
          0x04,
          0x00,
          0x00,
          0x00,
          0x03,
          0x00,
          0x00,
          0x00,
          0x02,
          0x00,
          0x00,
          0x00,
          0x00,
          0xDE,
          0x0A,
          0x00,
        ]),
      );

      // 7. OP_REPLACE_TEXT (0, 1, "H", 1)
      expect(
        sync.handleBinaryRequestForTesting(
          bytes(<int>[
            0x0B,
            0x00,
            0x00,
            0x00,
            0x2A,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x01,
            0x00,
            0x00,
            0x00,
            0x01,
            0x00,
            0x00,
            0x00,
            0x48,
            0x00,
            0x01,
            0x00,
            0x00,
            0x00,
          ]),
        ),
        bytes(<int>[
          0x01,
          0x00,
          0x00,
          0x00,
          0x07,
          0x00,
          0x00,
          0x00,
          0x01,
          0x00,
          0x00,
          0x00,
          0x01,
          0x00,
          0x00,
          0x00,
          0xFF,
          0xFF,
          0xFF,
          0xFF,
          0xFF,
          0xFF,
          0xFF,
          0xFF,
          0x00,
          0x00,
          0x00,
          0x00,
        ]),
      );

      // 8. OP_CLOSE_CONNECTION
      expect(
        sync.handleBinaryRequestForTesting(
          bytes(<int>[0x13, 0x00, 0x00, 0x00, 0x2A, 0x00, 0x00, 0x00]),
        ),
        bytes(<int>[
          0x01,
          0x00,
          0x00,
          0x00,
          0x00,
          0x00,
          0x00,
          0x00,
          0x01,
          0x00,
          0x00,
          0x00,
          0x01,
          0x00,
          0x00,
          0x00,
          0xFF,
          0xFF,
          0xFF,
          0xFF,
          0xFF,
          0xFF,
          0xFF,
          0xFF,
          0x00,
          0x00,
          0x00,
          0x00,
        ]),
      );
    });

    testWidgets(
      'opCloseConnection and opQueryInitialState unwind leaked batches, clear composing, and mid-batch programmatic edits rebase',
      (WidgetTester tester) async {
        final client = _DeltaRecordingClient(
          const TextEditingValue(
            text: 'hello',
            selection: TextSelection.collapsed(offset: 5),
            composing: TextRange(start: 0, end: 5),
          ),
        );
        final TextInputConnection conn = TextInput.attach(client, const TextInputConfiguration());
        addTearDown(conn.close);
        final int clientId = AndroidSyncTextInput.instance.delegate!.currentClientId!;

        // 1. opCloseConnection outside a batch finishes active composing.
        final _DecodedResponse closeResp = _decodeResponse(
          AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
            _encodeNoArgs(AndroidSyncTextInputProtocol.opCloseConnection, clientId),
          ),
        );
        expect(closeResp.isCommitted, isTrue);
        expect(client.currentTextEditingValue!.composing, TextRange.empty);

        // 2. opQueryInitialState defensively unwinds a leaked batch AND clears composing.
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeNoArgs(AndroidSyncTextInputProtocol.opBeginBatchEdit, clientId),
        );
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeStringAndInt(AndroidSyncTextInputProtocol.opSetComposingText, clientId, '!', 1),
        );
        final _DecodedResponse initResp = _decodeResponse(
          AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
            _encodeNoArgs(AndroidSyncTextInputProtocol.opQueryInitialState, clientId),
          ),
        );
        expect(initResp.isOk, isTrue);
        expect(initResp.text, 'hello!');
        expect(initResp.composingStart, -1);
        expect(initResp.composingEnd, -1);
        expect(client.currentTextEditingValue!.text, 'hello!');
        expect(client.currentTextEditingValue!.composing, TextRange.empty);

        // 3. Programmatic setEditingState mid-batch rebases open batch working value.
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeNoArgs(AndroidSyncTextInputProtocol.opBeginBatchEdit, clientId),
        );
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeStringAndInt(AndroidSyncTextInputProtocol.opCommitText, clientId, 'X', 1),
        );
        const rebased = TextEditingValue(
          text: 'reset',
          selection: TextSelection.collapsed(offset: 5),
        );
        client.updateEditingValue(rebased);
        conn.setEditingState(rebased);
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeStringAndInt(AndroidSyncTextInputProtocol.opCommitText, clientId, 'Y', 1),
        );
        final _DecodedResponse endResp = _decodeResponse(
          AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
            _encodeNoArgs(AndroidSyncTextInputProtocol.opEndBatchEdit, clientId),
          ),
        );
        expect(endResp.isCommitted, isTrue);
        expect(client.currentTextEditingValue!.text, 'resetY');
      },
    );

    testWidgets(
      'upstream affinity, isDirectional, updateConfig, opGetCursorAnchorState, and unsupported attach guard',
      (WidgetTester tester) async {
        // 1. Client preserving TextAffinity.upstream and isDirectional: true must NOT set diverged.
        final affinityClient = _AffinityPreservingClient(
          const TextEditingValue(
            text: 'hello',
            selection: TextSelection(
              baseOffset: 5,
              extentOffset: 5,
              affinity: TextAffinity.upstream,
              isDirectional: true,
            ),
          ),
        );
        final TextInputConnection affConn = TextInput.attach(
          affinityClient,
          const TextInputConfiguration(),
        );
        addTearDown(affConn.close);
        final int affId = AndroidSyncTextInput.instance.delegate!.currentClientId!;

        final _DecodedResponse affResp = _decodeResponse(
          AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
            _encodeStringAndInt(AndroidSyncTextInputProtocol.opCommitText, affId, '!', 1),
          ),
        );
        expect(affResp.isOk, isTrue);
        expect(affResp.isCommitted, isTrue);
        expect(affResp.isDiverged, isFalse);

        // No-op setSelection with different Flutter-only affinity must NOT flag stateChanged.
        final _DecodedResponse noopSel = _decodeResponse(
          AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
            _encodeTwoInts(AndroidSyncTextInputProtocol.opSetSelection, affId, 6, 6),
          ),
        );
        expect(noopSel.isStateChanged, isFalse);

        // opGetCursorAnchorState returns NO text when not composing.
        final _DecodedResponse noCompAnchor = _decodeResponse(
          AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
            _encodeNoArgs(AndroidSyncTextInputProtocol.opGetCursorAnchorState, affId),
          ),
        );
        expect(noCompAnchor.isOk, isTrue);
        expect(noCompAnchor.hasText, isFalse);
        affConn.close();

        // 2. TextInputConnection.updateConfig updates delegate.currentConfiguration dynamically.
        final deltaClient = _DeltaRecordingClient(
          const TextEditingValue(text: 'hi', selection: TextSelection.collapsed(offset: 2)),
        );
        final TextInputConnection deltaConn = TextInput.attach(
          deltaClient,
          const TextInputConfiguration(),
        );
        addTearDown(deltaConn.close);
        expect(
          AndroidSyncTextInput.instance.delegate!.currentConfiguration!.enableDeltaModel,
          isFalse,
        );
        deltaConn.updateConfig(const TextInputConfiguration(enableDeltaModel: true));
        expect(
          AndroidSyncTextInput.instance.delegate!.currentConfiguration!.enableDeltaModel,
          isTrue,
        );
        final int deltaId = AndroidSyncTextInput.instance.delegate!.currentClientId!;
        AndroidSyncTextInput.instance.handleBinaryRequestForTesting(
          _encodeStringAndInt(AndroidSyncTextInputProtocol.opCommitText, deltaId, '!', 1),
        );
        expect(deltaClient.receivedDeltas, hasLength(1));
        deltaConn.close();

        // 3. When unsupported, onClientAttached must NOT read client.currentTextEditingValue.
        final unsupportedBridge = _FakeBridge()
          ..registerResult = AndroidSyncImeSupportStatus.unsupported;
        AndroidSyncTextInput.instance.debugSetBridgeForTesting(unsupportedBridge);
        final countingClient = _ValueGetterCountingClient();
        final TextInputConnection unsupConn = TextInput.attach(
          countingClient,
          const TextInputConfiguration(),
        );
        addTearDown(unsupConn.close);
        expect(countingClient.getterCalls, 0);
      },
    );
  });
}

class _FixedIdDelegate implements AndroidSyncTextInputDelegate {
  _FixedIdDelegate({required this.clientId, required this.client});

  final int clientId;
  final TextInputClient client;

  @override
  int? get currentClientId => clientId;

  @override
  TextInputClient? get currentClient => client;

  @override
  TextInputConfiguration? get currentConfiguration => const TextInputConfiguration();

  @override
  void dispatchEditingValue(TextEditingValue value) {
    client.updateEditingValue(value);
  }

  @override
  void dispatchEditingValueWithDeltas(List<TextEditingDelta> deltas) {
    (client as DeltaTextInputClient).updateEditingValueWithDeltas(deltas);
  }
}

class _AffinityPreservingClient with TextInputClient {
  _AffinityPreservingClient(this._value);

  TextEditingValue _value;

  @override
  TextEditingValue? get currentTextEditingValue => _value;

  @override
  AutofillScope? get currentAutofillScope => null;

  @override
  void updateEditingValue(TextEditingValue value) {
    _value = value.copyWith(
      selection: value.selection.copyWith(affinity: TextAffinity.upstream, isDirectional: true),
    );
  }

  @override
  void performAction(TextInputAction action) {}

  @override
  void performPrivateCommand(String action, Map<String, dynamic> data) {}

  @override
  void updateFloatingCursor(RawFloatingCursorPoint point) {}

  @override
  void showAutocorrectionPromptRect(int start, int end) {}

  @override
  void connectionClosed() {}
}

class _ValueGetterCountingClient with TextInputClient {
  int getterCalls = 0;

  @override
  TextEditingValue? get currentTextEditingValue {
    getterCalls++;
    return TextEditingValue.empty;
  }

  @override
  AutofillScope? get currentAutofillScope => null;

  @override
  void updateEditingValue(TextEditingValue value) {}

  @override
  void performAction(TextInputAction action) {}

  @override
  void performPrivateCommand(String action, Map<String, dynamic> data) {}

  @override
  void updateFloatingCursor(RawFloatingCursorPoint point) {}

  @override
  void showAutocorrectionPromptRect(int start, int end) {}

  @override
  void connectionClosed() {}
}

class _CallbackClient with TextInputClient {
  _CallbackClient({required this.onUpdate});

  final ValueChanged<TextEditingValue> onUpdate;
  TextEditingValue _value = TextEditingValue.empty;

  @override
  TextEditingValue? get currentTextEditingValue => _value;

  @override
  AutofillScope? get currentAutofillScope => null;

  @override
  void updateEditingValue(TextEditingValue value) {
    _value = value;
    onUpdate(value);
  }

  @override
  void performAction(TextInputAction action) {}

  @override
  void performPrivateCommand(String action, Map<String, dynamic> data) {}

  @override
  void updateFloatingCursor(RawFloatingCursorPoint point) {}

  @override
  void showAutocorrectionPromptRect(int start, int end) {}

  @override
  void connectionClosed() {}
}
