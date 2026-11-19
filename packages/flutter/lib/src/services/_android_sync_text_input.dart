// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:math' as math;
import 'dart:typed_data';

import 'package:flutter/foundation.dart';

import '_android_input_connection_semantics.dart';
import '_android_sync_text_input_bridge.dart';
import 'text_editing_delta.dart';
import 'text_input.dart' show DeltaTextInputClient, TextInputClient, TextInputConfiguration;

/// Delegate bridging [AndroidSyncTextInput] to `TextInput`'s internal state
/// without expanding the public `package:flutter/services.dart` API.
abstract class AndroidSyncTextInputDelegate {
  /// The active client ID, or `null` when no client is attached.
  int? get currentClientId;

  /// The active [TextInputClient], or `null` when no client is attached.
  TextInputClient? get currentClient;

  /// The active [TextInputConfiguration], or `null` when no client is attached.
  TextInputConfiguration? get currentConfiguration;

  /// Delivers a full [TextEditingValue] update to the attached client (and any
  /// secondary text input controls).
  void dispatchEditingValue(TextEditingValue value);

  /// Delivers granular [TextEditingDelta]s to an attached
  /// [DeltaTextInputClient].
  void dispatchEditingValueWithDeltas(List<TextEditingDelta> deltas);
}

/// Wire protocol constants matching `SyncTextInputProtocol.java`.
abstract final class AndroidSyncTextInputProtocol {
  /// Maximum binary payload size (16 MiB).
  static const int maxPayloadBytes = 16 * 1024 * 1024;

  /// Fixed request header size in bytes (`opcode`, `clientId`).
  static const int requestHeaderBytes = 8;

  /// Fixed response header size in bytes (7 `int32` fields).
  static const int responseHeaderBytes = 28;

  /// Query initial state for `EditorInfo` during `createInputConnection`.
  static const int opQueryInitialState = 1;

  /// Begin an IME batch edit.
  static const int opBeginBatchEdit = 2;

  /// End an IME batch edit (committing when outermost).
  static const int opEndBatchEdit = 3;

  /// Commit text at composing region or selection.
  static const int opCommitText = 4;

  /// Set or clear composing text.
  static const int opSetComposingText = 5;

  /// Set composing region indices.
  static const int opSetComposingRegion = 6;

  /// Finish active composing text.
  static const int opFinishComposingText = 7;

  /// Delete surrounding UTF-16 code units.
  static const int opDeleteSurroundingText = 8;

  /// Delete surrounding Unicode code points.
  static const int opDeleteSurroundingTextInCodePoints = 9;

  /// Set selection start/end indices.
  static const int opSetSelection = 10;

  /// Replace an explicit text range (Android API 34+).
  static const int opReplaceText = 11;

  /// Query text before the cursor.
  static const int opGetTextBeforeCursor = 12;

  /// Query text after the cursor.
  static const int opGetTextAfterCursor = 13;

  /// Query currently selected text.
  static const int opGetSelectedText = 14;

  /// Query surrounding text window (Android API 31+).
  static const int opGetSurroundingText = 15;

  /// Query cursor auto-capitalization mode.
  static const int opGetCursorCapsMode = 16;

  /// Query extracted text for fullscreen/monitoring IMEs.
  static const int opGetExtractedText = 17;

  /// Query full live text/selection/composing state on demand.
  static const int opGetFullState = 18;

  /// Close active connection and unwind any open batch edit.
  static const int opCloseConnection = 19;

  /// Move cursor via DPAD key event.
  static const int opMoveCursor = 20;

  /// Cut selected text.
  static const int opCut = 21;

  /// Copy selected text.
  static const int opCopy = 22;

  /// Paste clipboard text.
  static const int opPaste = 23;

  /// Select all text.
  static const int opSelectAll = 24;

  /// Apply Android autofill to the currently focused client.
  static const int opAutofillCurrentClient = 25;

  /// Insert a printable character from a hardware/fallback key event at the
  /// selection without clobbering an active composing region.
  static const int opInsertKeyCharacter = 26;

  /// Query selection/composing indices plus only the composing substring (if
  /// any) for `CursorAnchorInfo` updates without copying the full document.
  static const int opGetCursorAnchorState = 27;

  /// Request handled successfully.
  static const int statusOk = 1;

  /// Request client ID does not match the active client.
  static const int statusStaleClient = 2;

  /// Request could not be handled (for example unpaired surrogate abort).
  static const int statusNotHandled = 3;

  /// Malformed request or unhandled exception.
  static const int statusError = 4;

  /// Response bit: mutation committed to the Dart client.
  static const int respFlagCommitted = 1 << 0;

  /// Response bit: text, selection, or composing changed.
  static const int respFlagStateChanged = 1 << 1;

  /// Response bit: text content changed.
  static const int respFlagTextChanged = 1 << 2;

  /// Response bit: post-commit value diverged from proposed IME edit.
  static const int respFlagDiverged = 1 << 3;

  /// Response bit: framework modified or cleared active composing text.
  static const int respFlagComposingChangedByFramework = 1 << 4;

  /// Response bit: attached client changed or closed during callback.
  static const int respFlagClientChanged = 1 << 5;

  /// Response bit: IME batch edit is still active.
  static const int respFlagBatchActive = 1 << 6;

  /// Response bit: UTF-16 string payload follows the 28-byte header.
  static const int respFlagHasText = 1 << 7;

  /// Dart -> Android programmatic notification bit: text content changed.
  static const int notifyFlagTextChanged = 1 << 0;

  /// Dart -> Android programmatic notification bit: composing region changed.
  static const int notifyFlagComposingChanged = 1 << 1;
}

/// Framework-side coordinator for synchronous Android text input.
///
/// Owns lazy native bridge registration, binary request decoding/encoding,
/// IME batch edit accumulation, reentrancy suppression, and post-commit
/// formatter divergence detection so Dart remains the single source of truth.
class AndroidSyncTextInput {
  /// Creates an [AndroidSyncTextInput] backed by [bridge].
  AndroidSyncTextInput({AndroidSyncTextInputBridge? bridge})
    : _bridge = bridge ?? createAndroidSyncTextInputBridge();

  static const int _initialSubTextPaddingChars = 2048;
  static const int _maxInitialSelectionChars = 4096;

  /// Singleton instance used by `TextInput`.
  static final AndroidSyncTextInput instance = AndroidSyncTextInput();

  AndroidSyncTextInputBridge _bridge;

  /// The bound delegate (set once by `TextInput`).
  AndroidSyncTextInputDelegate? delegate;

  bool _registered = false;
  bool _permanentlyUnsupported = false;

  // Fallback editing value for custom TextInputClients that return null from
  // currentTextEditingValue, plus last value reported to Android for accurate
  // programmatic diffing.
  int? _trackedClientId;
  TextEditingValue? _lastClientEditingValue;
  TextEditingValue? _lastReportedEditingValue;

  // Active IME batch edit state (only mutated during synchronous IME calls).
  int? _batchClientId;
  int _batchDepth = 0;
  TextEditingValue? _batchPreValue;
  TextEditingValue? _batchWorkingValue;
  List<TextEditingDelta> _batchDeltas = <TextEditingDelta>[];
  bool _batchTextChanged = false;
  bool _batchDirty = false;

  // Reentrancy guard suppressing redundant platform setEditingState echoes
  // while Dart is synchronously executing inside an IME mutation callback.
  int _inSyncImeCallbackDepth = 0;
  int? _inSyncImeClientId;

  /// Whether synchronous Android text input is currently registered and active.
  bool get isActive => _registered;

  /// Whether Dart is currently executing inside a synchronous IME callback for
  /// [clientId].
  bool isInSyncImeCallbackFor(int clientId) =>
      _inSyncImeCallbackDepth > 0 && _inSyncImeClientId == clientId;

  /// Replaces the underlying [AndroidSyncTextInputBridge] for tests and resets
  /// registration/session state.
  @visibleForTesting
  void debugSetBridgeForTesting(AndroidSyncTextInputBridge? bridge) {
    if (_registered) {
      _bridge.unregisterHandler();
    }
    _bridge = bridge ?? createAndroidSyncTextInputBridge();
    _registered = false;
    _permanentlyUnsupported = false;
    _resetClientState();
  }

  /// Resets session state for tests without replacing the bridge.
  @visibleForTesting
  void debugResetStateForTesting() {
    _resetClientState();
  }

  /// Directly invokes the binary request handler for unit/widget tests.
  @visibleForTesting
  Uint8List? handleBinaryRequestForTesting(Uint8List requestBytes) =>
      _handleBinaryRequest(requestBytes);

  /// Attempts lazy registration with the Android native bridge.
  ///
  /// Safe to call on every `TextInput.attach`: caches permanent unsupported
  /// status and retries if the engine reports `retryLater` (for example while
  /// `mergeAfterLaunch` has not yet completed).
  bool ensureRegistered() {
    if (_registered) {
      return true;
    }
    if (_permanentlyUnsupported) {
      return false;
    }
    final int status = _bridge.registerHandler(_handleBinaryRequest);
    if (status == AndroidSyncImeSupportStatus.supported) {
      _registered = true;
      return true;
    }
    if (status == AndroidSyncImeSupportStatus.unsupported) {
      _permanentlyUnsupported = true;
    }
    return false;
  }

  /// Called when a new client attaches to `TextInput`.
  void onClientAttached(int clientId, TextInputClient client) {
    if (!ensureRegistered()) {
      return;
    }
    _resetBatch();
    _trackedClientId = clientId;
    final TextEditingValue? initial = client.currentTextEditingValue;
    _lastClientEditingValue = initial;
    _lastReportedEditingValue = initial;
  }

  /// Called when the current client detaches from `TextInput`.
  void onClientDetached() {
    _resetClientState();
  }

  void _rebaseBatchIfActive(int clientId, TextEditingValue value) {
    if (_batchDepth > 0 && _batchClientId == clientId && !isInSyncImeCallbackFor(clientId)) {
      _batchPreValue = value;
      _batchWorkingValue = value;
      _batchDeltas = <TextEditingDelta>[];
      _batchTextChanged = false;
      _batchDirty = false;
    }
  }

  /// Records the latest editing value supplied via `setEditingState` so custom
  /// [TextInputClient]s that do not override `currentTextEditingValue` still
  /// have an accurate value on the Dart side.
  void recordClientEditingState(int clientId, TextEditingValue value) {
    if (_trackedClientId != clientId) {
      _trackedClientId = clientId;
      _lastReportedEditingValue = null;
    }
    _lastClientEditingValue = value;
    _rebaseBatchIfActive(clientId, value);
  }

  /// Synchronously notifies Android of an out-of-band programmatic editing
  /// state update (for example `controller.text = ...`).
  ///
  /// Returns `true` iff the Android embedder accepted and handled the update
  /// for [clientId]. Returns `false` if sync mode is inactive or Android does
  /// not currently hold [clientId] as its active framework client (including
  /// when `TestTextInput` intercepts `SystemChannels.textInput`).
  bool notifyProgrammaticEditingState(int clientId, TextEditingValue value) {
    if (!_registered) {
      return false;
    }
    _rebaseBatchIfActive(clientId, value);
    final TextEditingValue? previous = _trackedClientId == clientId
        ? _lastReportedEditingValue
        : null;
    final bool textChanged = previous != null && previous.text != value.text;
    final bool composingChanged = previous != null && _composingRegionChanged(previous, value);

    var flags = 0;
    if (textChanged) {
      flags |= AndroidSyncTextInputProtocol.notifyFlagTextChanged;
    }
    if (composingChanged) {
      flags |= AndroidSyncTextInputProtocol.notifyFlagComposingChanged;
    }

    final bool handled = _bridge.notifyEditingState(
      clientId: clientId,
      flags: flags,
      selectionStart: value.selection.baseOffset,
      selectionEnd: value.selection.extentOffset,
      composingStart: value.composing.start,
      composingEnd: value.composing.end,
    );
    if (handled) {
      _trackedClientId = clientId;
      _lastClientEditingValue = value;
      _lastReportedEditingValue = value;
    }
    return handled;
  }

  void _resetClientState() {
    _resetBatch();
    _trackedClientId = null;
    _lastClientEditingValue = null;
    _lastReportedEditingValue = null;
  }

  void _resetBatch() {
    _batchClientId = null;
    _batchDepth = 0;
    _batchPreValue = null;
    _batchWorkingValue = null;
    _batchDeltas = <TextEditingDelta>[];
    _batchTextChanged = false;
    _batchDirty = false;
  }

  TextEditingValue _readLiveValue(int clientId, TextInputClient client) {
    if (_batchDepth > 0 && _batchClientId == clientId && _batchWorkingValue != null) {
      return _batchWorkingValue!;
    }
    final TextEditingValue? live = client.currentTextEditingValue;
    if (live != null) {
      return live;
    }
    if (_trackedClientId == clientId && _lastClientEditingValue != null) {
      return _lastClientEditingValue!;
    }
    return TextEditingValue.empty;
  }

  static bool _composingRegionChanged(TextEditingValue before, TextEditingValue after) {
    if (!before.composing.isValid || before.composing.isCollapsed) {
      return false;
    }
    final int beforeStart = before.composing.start.clamp(0, before.text.length);
    final int beforeEnd = before.composing.end.clamp(0, before.text.length);
    final int beforeLen = beforeEnd - beforeStart;
    if (beforeLen <= 0) {
      return false;
    }
    if (!after.composing.isValid || after.composing.isCollapsed) {
      return true;
    }
    final int afterStart = after.composing.start.clamp(0, after.text.length);
    final int afterEnd = after.composing.end.clamp(0, after.text.length);
    if (afterEnd - afterStart != beforeLen) {
      return true;
    }
    for (var i = 0; i < beforeLen; i++) {
      if (before.text.codeUnitAt(beforeStart + i) != after.text.codeUnitAt(afterStart + i)) {
        return true;
      }
    }
    return false;
  }

  Uint8List _encodeResponse({
    required int status,
    int flags = 0,
    int selectionStart = -1,
    int selectionEnd = -1,
    int composingStart = -1,
    int composingEnd = -1,
    int intResult = 0,
    bool includeTextField = false,
    String? text,
  }) {
    final int effectiveFlags = includeTextField
        ? (flags | AndroidSyncTextInputProtocol.respFlagHasText)
        : flags;
    final int textBytes = includeTextField ? (text == null ? 4 : 4 + text.length * 2) : 0;
    final int totalBytes = AndroidSyncTextInputProtocol.responseHeaderBytes + textBytes;
    if (totalBytes > AndroidSyncTextInputProtocol.maxPayloadBytes) {
      return _encodeResponse(status: AndroidSyncTextInputProtocol.statusError);
    }

    final bytes = Uint8List(totalBytes);
    final data = ByteData.sublistView(bytes);
    data.setInt32(0, status, Endian.little);
    data.setInt32(4, effectiveFlags, Endian.little);
    data.setInt32(8, selectionStart, Endian.little);
    data.setInt32(12, selectionEnd, Endian.little);
    data.setInt32(16, composingStart, Endian.little);
    data.setInt32(20, composingEnd, Endian.little);
    data.setInt32(24, intResult, Endian.little);

    if (includeTextField) {
      if (text == null) {
        data.setInt32(28, -1, Endian.little);
      } else {
        final int charLen = text.length;
        data.setInt32(28, charLen, Endian.little);
        var offset = 32;
        for (var i = 0; i < charLen; i++) {
          data.setUint16(offset, text.codeUnitAt(i), Endian.little);
          offset += 2;
        }
      }
    }
    return bytes;
  }

  Uint8List _encodeValueResponse(
    TextEditingValue value, {
    int status = AndroidSyncTextInputProtocol.statusOk,
    int flags = 0,
    int intResult = 0,
    bool includeTextField = false,
    String? text,
  }) {
    return _encodeResponse(
      status: status,
      flags: flags,
      selectionStart: value.selection.baseOffset,
      selectionEnd: value.selection.extentOffset,
      composingStart: value.composing.start,
      composingEnd: value.composing.end,
      intResult: intResult,
      includeTextField: includeTextField,
      text: text,
    );
  }

  static (String?, int) _readUtf16String(ByteData data, int offset) {
    if (offset + 4 > data.lengthInBytes) {
      throw const FormatException('Truncated UTF-16 string length.');
    }
    final int charLen = data.getInt32(offset, Endian.little);
    final int bodyOffset = offset + 4;
    if (charLen == -1) {
      return (null, bodyOffset);
    }
    if (charLen < 0 || bodyOffset + charLen * 2 > data.lengthInBytes) {
      throw const FormatException('Invalid UTF-16 string length.');
    }
    if (charLen == 0) {
      return ('', bodyOffset);
    }
    final codeUnits = Uint16List(charLen);
    var pos = bodyOffset;
    for (var i = 0; i < charLen; i++) {
      codeUnits[i] = data.getUint16(pos, Endian.little);
      pos += 2;
    }
    return (String.fromCharCodes(codeUnits), pos);
  }

  Uint8List _applyMutation(
    int clientId,
    TextInputClient client,
    AndroidImeEditResult Function(TextEditingValue current) apply,
  ) {
    final bool inBatch = _batchDepth > 0 && _batchClientId == clientId;
    final TextEditingValue current = _readLiveValue(clientId, client);
    final AndroidImeEditResult result = apply(current);
    if (!result.handled) {
      return _encodeValueResponse(current, status: AndroidSyncTextInputProtocol.statusNotHandled);
    }

    if (inBatch) {
      _batchWorkingValue = result.value;
      _batchTextChanged |= result.textChanged;
      _batchDirty |= result.stateChanged;
      if (result.deltas.isNotEmpty) {
        _batchDeltas.addAll(result.deltas);
      }
      int flags = AndroidSyncTextInputProtocol.respFlagBatchActive;
      if (result.stateChanged) {
        flags |= AndroidSyncTextInputProtocol.respFlagStateChanged;
      }
      if (result.textChanged) {
        flags |= AndroidSyncTextInputProtocol.respFlagTextChanged;
      }
      return _encodeValueResponse(
        result.value,
        flags: flags,
        includeTextField: result.extractedText != null,
        text: result.extractedText,
      );
    }

    return _commitMutation(
      clientId: clientId,
      client: client,
      preValue: current,
      proposedValue: result.value,
      textChanged: result.textChanged,
      stateChanged: result.stateChanged,
      deltas: result.deltas,
      extractedText: result.extractedText,
    );
  }

  Uint8List _commitMutation({
    required int clientId,
    required TextInputClient client,
    required TextEditingValue preValue,
    required TextEditingValue proposedValue,
    required bool textChanged,
    required bool stateChanged,
    required List<TextEditingDelta> deltas,
    String? extractedText,
  }) {
    final AndroidSyncTextInputDelegate activeDelegate = delegate!;
    if (!stateChanged) {
      return _encodeValueResponse(
        preValue,
        flags: AndroidSyncTextInputProtocol.respFlagCommitted,
        includeTextField: extractedText != null,
        text: extractedText,
      );
    }

    _trackedClientId = clientId;
    _lastClientEditingValue = proposedValue;

    final int? previousInSyncClientId = _inSyncImeClientId;
    _inSyncImeCallbackDepth++;
    _inSyncImeClientId = clientId;
    try {
      final bool useDeltas =
          (activeDelegate.currentConfiguration?.enableDeltaModel ?? false) &&
          client is DeltaTextInputClient;
      if (useDeltas && deltas.isNotEmpty) {
        activeDelegate.dispatchEditingValueWithDeltas(deltas);
      } else {
        activeDelegate.dispatchEditingValue(proposedValue);
      }
    } finally {
      _inSyncImeCallbackDepth--;
      _inSyncImeClientId = _inSyncImeCallbackDepth > 0 ? previousInSyncClientId : null;
    }

    final bool clientChanged =
        activeDelegate.currentClientId != clientId ||
        !identical(activeDelegate.currentClient, client);
    if (clientChanged) {
      _resetBatch();
      int flags =
          AndroidSyncTextInputProtocol.respFlagCommitted |
          AndroidSyncTextInputProtocol.respFlagStateChanged |
          AndroidSyncTextInputProtocol.respFlagClientChanged;
      if (textChanged) {
        flags |= AndroidSyncTextInputProtocol.respFlagTextChanged;
      }
      return _encodeValueResponse(
        proposedValue,
        flags: flags,
        includeTextField: extractedText != null,
        text: extractedText,
      );
    }

    final TextEditingValue postValue =
        client.currentTextEditingValue ?? _lastClientEditingValue ?? proposedValue;
    _lastClientEditingValue = postValue;
    _lastReportedEditingValue = postValue;

    final bool diverged = !AndroidInputConnectionSemantics.androidStateEquals(
      postValue,
      proposedValue,
    );
    final bool composingChangedByFramework = _composingRegionChanged(proposedValue, postValue);
    final finalTextChanged = postValue.text != preValue.text;
    final bool finalStateChanged = !AndroidInputConnectionSemantics.androidStateEquals(
      postValue,
      preValue,
    );

    int flags = AndroidSyncTextInputProtocol.respFlagCommitted;
    if (finalStateChanged) {
      flags |= AndroidSyncTextInputProtocol.respFlagStateChanged;
    }
    if (finalTextChanged) {
      flags |= AndroidSyncTextInputProtocol.respFlagTextChanged;
    }
    if (diverged) {
      flags |= AndroidSyncTextInputProtocol.respFlagDiverged;
    }
    if (composingChangedByFramework) {
      flags |= AndroidSyncTextInputProtocol.respFlagComposingChangedByFramework;
    }

    return _encodeValueResponse(
      postValue,
      flags: flags,
      includeTextField: extractedText != null,
      text: extractedText,
    );
  }

  Uint8List? _handleBinaryRequest(Uint8List requestBytes) {
    if (requestBytes.length < AndroidSyncTextInputProtocol.requestHeaderBytes) {
      return _encodeResponse(status: AndroidSyncTextInputProtocol.statusError);
    }
    final data = ByteData.sublistView(requestBytes);
    final int opcode = data.getInt32(0, Endian.little);
    final int clientId = data.getInt32(4, Endian.little);

    final AndroidSyncTextInputDelegate? activeDelegate = delegate;
    if (activeDelegate == null) {
      return _encodeResponse(status: AndroidSyncTextInputProtocol.statusStaleClient);
    }

    final int? activeClientId = activeDelegate.currentClientId;
    final TextInputClient? client = activeDelegate.currentClient;
    if (activeClientId == null || client == null || activeClientId != clientId) {
      if (_batchClientId == clientId) {
        _resetBatch();
      }
      return _encodeResponse(status: AndroidSyncTextInputProtocol.statusStaleClient);
    }

    try {
      return _dispatchOpcode(opcode, clientId, client, data);
    } catch (exception, stack) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: exception,
          stack: stack,
          library: 'services library',
          context: ErrorDescription(
            'during synchronous Android text input request (opcode $opcode)',
          ),
        ),
      );
      return _encodeResponse(status: AndroidSyncTextInputProtocol.statusError);
    }
  }

  Uint8List _closeOrUnwindConnection(int clientId, TextInputClient client) {
    if (_batchDepth > 0 && _batchClientId == clientId) {
      final TextEditingValue preValue = _batchPreValue!;
      TextEditingValue workingValue = _batchWorkingValue!;
      final List<TextEditingDelta> deltas = _batchDeltas;
      bool dirty = _batchDirty;
      final bool textChanged = _batchTextChanged;
      _resetBatch();
      if (workingValue.composing.isValid && !workingValue.composing.isCollapsed) {
        final AndroidImeEditResult finishResult =
            AndroidInputConnectionSemantics.finishComposingText(workingValue);
        workingValue = finishResult.value;
        dirty |= finishResult.stateChanged;
        if (finishResult.deltas.isNotEmpty) {
          deltas.addAll(finishResult.deltas);
        }
      }
      return _commitMutation(
        clientId: clientId,
        client: client,
        preValue: preValue,
        proposedValue: workingValue,
        textChanged: textChanged,
        stateChanged: dirty,
        deltas: deltas,
      );
    }
    _resetBatch();
    final TextEditingValue live = _readLiveValue(clientId, client);
    if (live.composing.isValid && !live.composing.isCollapsed) {
      return _applyMutation(clientId, client, AndroidInputConnectionSemantics.finishComposingText);
    }
    return _encodeValueResponse(live);
  }

  Uint8List _dispatchOpcode(int opcode, int clientId, TextInputClient client, ByteData data) {
    const int payloadOffset = AndroidSyncTextInputProtocol.requestHeaderBytes;
    switch (opcode) {
      case AndroidSyncTextInputProtocol.opQueryInitialState:
        // Defensively unwind any leaked batch and finish any active composing
        // span so a fresh InputConnection starts clean even if Android did not
        // deliver closeConnection() on the previous instance.
        _closeOrUnwindConnection(clientId, client);
        final AndroidSyncTextInputDelegate activeDelegate = delegate!;
        if (activeDelegate.currentClientId != clientId ||
            !identical(activeDelegate.currentClient, client)) {
          return _encodeResponse(status: AndroidSyncTextInputProtocol.statusStaleClient);
        }
        final TextEditingValue value = _readLiveValue(clientId, client);
        _trackedClientId = clientId;
        _lastClientEditingValue = value;
        _lastReportedEditingValue = value;
        String? subText;
        var subTextOffset = 0;
        if (value.selection.isValid) {
          final int len = value.text.length;
          final int selMin = value.selection.start.clamp(0, len);
          final int selMax = value.selection.end.clamp(0, len);
          if (selMax - selMin <= _maxInitialSelectionChars) {
            final int windowStart = math.max(0, selMin - _initialSubTextPaddingChars);
            final int windowEnd = math.min(len, selMax + _initialSubTextPaddingChars);
            subText = value.text.substring(windowStart, windowEnd);
            subTextOffset = windowStart;
          }
        }
        return _encodeValueResponse(
          value,
          intResult: subTextOffset,
          includeTextField: subText != null,
          text: subText,
        );

      case AndroidSyncTextInputProtocol.opBeginBatchEdit:
        if (_batchDepth == 0 || _batchClientId != clientId) {
          final TextEditingValue live = _readLiveValue(clientId, client);
          _batchClientId = clientId;
          _batchDepth = 1;
          _batchPreValue = live;
          _batchWorkingValue = live;
          _batchDeltas = <TextEditingDelta>[];
          _batchTextChanged = false;
          _batchDirty = false;
        } else {
          _batchDepth++;
        }
        return _encodeValueResponse(
          _batchWorkingValue!,
          flags: AndroidSyncTextInputProtocol.respFlagBatchActive,
        );

      case AndroidSyncTextInputProtocol.opEndBatchEdit:
        if (_batchDepth <= 0 || _batchClientId != clientId) {
          return _encodeValueResponse(_readLiveValue(clientId, client));
        }
        _batchDepth--;
        if (_batchDepth > 0) {
          return _encodeValueResponse(
            _batchWorkingValue!,
            flags: AndroidSyncTextInputProtocol.respFlagBatchActive,
          );
        }
        final TextEditingValue preValue = _batchPreValue!;
        final TextEditingValue workingValue = _batchWorkingValue!;
        final List<TextEditingDelta> deltas = _batchDeltas;
        final bool dirty = _batchDirty;
        final bool textChanged = _batchTextChanged;
        _resetBatch();
        return _commitMutation(
          clientId: clientId,
          client: client,
          preValue: preValue,
          proposedValue: workingValue,
          textChanged: textChanged,
          stateChanged: dirty,
          deltas: deltas,
        );

      case AndroidSyncTextInputProtocol.opCloseConnection:
        return _closeOrUnwindConnection(clientId, client);

      case AndroidSyncTextInputProtocol.opCommitText:
        final (String? text, int nextOffset) = _readUtf16String(data, payloadOffset);
        if (nextOffset + 4 > data.lengthInBytes) {
          return _encodeResponse(status: AndroidSyncTextInputProtocol.statusError);
        }
        final int newCursorPos = data.getInt32(nextOffset, Endian.little);
        return _applyMutation(
          clientId,
          client,
          (TextEditingValue current) =>
              AndroidInputConnectionSemantics.commitText(current, text ?? '', newCursorPos),
        );

      case AndroidSyncTextInputProtocol.opSetComposingText:
        final (String? text, int nextOffset) = _readUtf16String(data, payloadOffset);
        if (nextOffset + 4 > data.lengthInBytes) {
          return _encodeResponse(status: AndroidSyncTextInputProtocol.statusError);
        }
        final int newCursorPos = data.getInt32(nextOffset, Endian.little);
        return _applyMutation(
          clientId,
          client,
          (TextEditingValue current) =>
              AndroidInputConnectionSemantics.setComposingText(current, text ?? '', newCursorPos),
        );

      case AndroidSyncTextInputProtocol.opSetComposingRegion:
        if (payloadOffset + 8 > data.lengthInBytes) {
          return _encodeResponse(status: AndroidSyncTextInputProtocol.statusError);
        }
        final int start = data.getInt32(payloadOffset, Endian.little);
        final int end = data.getInt32(payloadOffset + 4, Endian.little);
        return _applyMutation(
          clientId,
          client,
          (TextEditingValue current) =>
              AndroidInputConnectionSemantics.setComposingRegion(current, start, end),
        );

      case AndroidSyncTextInputProtocol.opFinishComposingText:
        return _applyMutation(
          clientId,
          client,
          AndroidInputConnectionSemantics.finishComposingText,
        );

      case AndroidSyncTextInputProtocol.opDeleteSurroundingText:
        if (payloadOffset + 8 > data.lengthInBytes) {
          return _encodeResponse(status: AndroidSyncTextInputProtocol.statusError);
        }
        final int before = data.getInt32(payloadOffset, Endian.little);
        final int after = data.getInt32(payloadOffset + 4, Endian.little);
        return _applyMutation(
          clientId,
          client,
          (TextEditingValue current) =>
              AndroidInputConnectionSemantics.deleteSurroundingText(current, before, after),
        );

      case AndroidSyncTextInputProtocol.opDeleteSurroundingTextInCodePoints:
        if (payloadOffset + 8 > data.lengthInBytes) {
          return _encodeResponse(status: AndroidSyncTextInputProtocol.statusError);
        }
        final int before = data.getInt32(payloadOffset, Endian.little);
        final int after = data.getInt32(payloadOffset + 4, Endian.little);
        return _applyMutation(
          clientId,
          client,
          (TextEditingValue current) =>
              AndroidInputConnectionSemantics.deleteSurroundingTextInCodePoints(
                current,
                before,
                after,
              ),
        );

      case AndroidSyncTextInputProtocol.opSetSelection:
        if (payloadOffset + 8 > data.lengthInBytes) {
          return _encodeResponse(status: AndroidSyncTextInputProtocol.statusError);
        }
        final int start = data.getInt32(payloadOffset, Endian.little);
        final int end = data.getInt32(payloadOffset + 4, Endian.little);
        return _applyMutation(
          clientId,
          client,
          (TextEditingValue current) =>
              AndroidInputConnectionSemantics.setSelection(current, start, end),
        );

      case AndroidSyncTextInputProtocol.opReplaceText:
        if (payloadOffset + 8 > data.lengthInBytes) {
          return _encodeResponse(status: AndroidSyncTextInputProtocol.statusError);
        }
        final int start = data.getInt32(payloadOffset, Endian.little);
        final int end = data.getInt32(payloadOffset + 4, Endian.little);
        final (String? text, int nextOffset) = _readUtf16String(data, payloadOffset + 8);
        if (nextOffset + 4 > data.lengthInBytes) {
          return _encodeResponse(status: AndroidSyncTextInputProtocol.statusError);
        }
        final int newCursorPos = data.getInt32(nextOffset, Endian.little);
        return _applyMutation(
          clientId,
          client,
          (TextEditingValue current) => AndroidInputConnectionSemantics.replaceText(
            current,
            start,
            end,
            text ?? '',
            newCursorPos,
          ),
        );

      case AndroidSyncTextInputProtocol.opGetTextBeforeCursor:
        if (payloadOffset + 8 > data.lengthInBytes) {
          return _encodeResponse(status: AndroidSyncTextInputProtocol.statusError);
        }
        final int n = data.getInt32(payloadOffset, Endian.little);
        final TextEditingValue value = _readLiveValue(clientId, client);
        final String? before = AndroidInputConnectionSemantics.getTextBeforeCursor(value, n);
        if (before == null) {
          return _encodeValueResponse(value, status: AndroidSyncTextInputProtocol.statusNotHandled);
        }
        return _encodeValueResponse(value, includeTextField: true, text: before);

      case AndroidSyncTextInputProtocol.opGetTextAfterCursor:
        if (payloadOffset + 8 > data.lengthInBytes) {
          return _encodeResponse(status: AndroidSyncTextInputProtocol.statusError);
        }
        final int n = data.getInt32(payloadOffset, Endian.little);
        final TextEditingValue value = _readLiveValue(clientId, client);
        final String? after = AndroidInputConnectionSemantics.getTextAfterCursor(value, n);
        if (after == null) {
          return _encodeValueResponse(value, status: AndroidSyncTextInputProtocol.statusNotHandled);
        }
        return _encodeValueResponse(value, includeTextField: true, text: after);

      case AndroidSyncTextInputProtocol.opGetSelectedText:
        if (payloadOffset + 4 > data.lengthInBytes) {
          return _encodeResponse(status: AndroidSyncTextInputProtocol.statusError);
        }
        final TextEditingValue value = _readLiveValue(clientId, client);
        final String? selected = AndroidInputConnectionSemantics.getSelectedText(value);
        return _encodeValueResponse(value, includeTextField: true, text: selected);

      case AndroidSyncTextInputProtocol.opGetSurroundingText:
        if (payloadOffset + 12 > data.lengthInBytes) {
          return _encodeResponse(status: AndroidSyncTextInputProtocol.statusError);
        }
        final int beforeLen = data.getInt32(payloadOffset, Endian.little);
        final int afterLen = data.getInt32(payloadOffset + 4, Endian.little);
        final TextEditingValue value = _readLiveValue(clientId, client);
        final AndroidSurroundingText? surrounding =
            AndroidInputConnectionSemantics.getSurroundingText(value, beforeLen, afterLen);
        if (surrounding == null) {
          return _encodeValueResponse(value, status: AndroidSyncTextInputProtocol.statusNotHandled);
        }
        return _encodeResponse(
          status: AndroidSyncTextInputProtocol.statusOk,
          selectionStart: surrounding.selectionStart,
          selectionEnd: surrounding.selectionEnd,
          composingStart: value.composing.start,
          composingEnd: value.composing.end,
          intResult: surrounding.offset,
          includeTextField: true,
          text: surrounding.text,
        );

      case AndroidSyncTextInputProtocol.opGetCursorCapsMode:
        if (payloadOffset + 4 > data.lengthInBytes) {
          return _encodeResponse(status: AndroidSyncTextInputProtocol.statusError);
        }
        final int reqModes = data.getInt32(payloadOffset, Endian.little);
        final TextEditingValue value = _readLiveValue(clientId, client);
        final int caps = AndroidInputConnectionSemantics.getCursorCapsMode(value, reqModes);
        return _encodeValueResponse(value, intResult: caps);

      case AndroidSyncTextInputProtocol.opGetExtractedText:
        if (payloadOffset + 4 > data.lengthInBytes) {
          return _encodeResponse(status: AndroidSyncTextInputProtocol.statusError);
        }
        final TextEditingValue value = _readLiveValue(clientId, client);
        return _encodeValueResponse(value, includeTextField: true, text: value.text);

      case AndroidSyncTextInputProtocol.opGetFullState:
        final TextEditingValue value = _readLiveValue(clientId, client);
        return _encodeValueResponse(value, includeTextField: true, text: value.text);

      case AndroidSyncTextInputProtocol.opMoveCursor:
        if (payloadOffset + 8 > data.lengthInBytes) {
          return _encodeResponse(status: AndroidSyncTextInputProtocol.statusError);
        }
        final int dir = data.getInt32(payloadOffset, Endian.little);
        final extendSelection = data.getInt32(payloadOffset + 4, Endian.little) != 0;
        return _applyMutation(
          clientId,
          client,
          (TextEditingValue current) => AndroidInputConnectionSemantics.moveCursor(
            current,
            direction: dir,
            extendSelection: extendSelection,
          ),
        );

      case AndroidSyncTextInputProtocol.opCut:
        return _applyMutation(clientId, client, AndroidInputConnectionSemantics.cut);

      case AndroidSyncTextInputProtocol.opCopy:
        final TextEditingValue value = _readLiveValue(clientId, client);
        final AndroidImeEditResult copyResult = AndroidInputConnectionSemantics.copy(value);
        return _encodeValueResponse(
          value,
          includeTextField: copyResult.extractedText != null,
          text: copyResult.extractedText,
        );

      case AndroidSyncTextInputProtocol.opPaste:
        final (String? text, _) = _readUtf16String(data, payloadOffset);
        return _applyMutation(
          clientId,
          client,
          (TextEditingValue current) => AndroidInputConnectionSemantics.paste(current, text ?? ''),
        );

      case AndroidSyncTextInputProtocol.opSelectAll:
        return _applyMutation(clientId, client, AndroidInputConnectionSemantics.selectAll);

      case AndroidSyncTextInputProtocol.opAutofillCurrentClient:
        final (String? text, _) = _readUtf16String(data, payloadOffset);
        return _applyMutation(
          clientId,
          client,
          (TextEditingValue current) =>
              AndroidInputConnectionSemantics.autofill(current, text ?? ''),
        );

      case AndroidSyncTextInputProtocol.opInsertKeyCharacter:
        final (String? text, _) = _readUtf16String(data, payloadOffset);
        return _applyMutation(
          clientId,
          client,
          (TextEditingValue current) =>
              AndroidInputConnectionSemantics.insertKeyCharacter(current, text ?? ''),
        );

      case AndroidSyncTextInputProtocol.opGetCursorAnchorState:
        final TextEditingValue value = _readLiveValue(clientId, client);
        final (int ca, int cb) = AndroidInputConnectionSemantics.orderedComposing(value);
        if (ca != -1 && cb != -1) {
          return _encodeResponse(
            status: AndroidSyncTextInputProtocol.statusOk,
            selectionStart: value.selection.baseOffset,
            selectionEnd: value.selection.extentOffset,
            composingStart: ca,
            composingEnd: cb,
            includeTextField: true,
            text: value.text.substring(ca, cb),
          );
        }
        return _encodeResponse(
          status: AndroidSyncTextInputProtocol.statusOk,
          selectionStart: value.selection.baseOffset,
          selectionEnd: value.selection.extentOffset,
        );

      default:
        return _encodeResponse(status: AndroidSyncTextInputProtocol.statusNotHandled);
    }
  }
}
