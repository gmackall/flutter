// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugin.editing;

import static io.flutter.Build.API_LEVELS;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.text.InputType;
import android.text.TextUtils;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.HandwritingGesture;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.PreviewableHandwritingGesture;
import android.view.inputmethod.SurroundingText;
import android.view.inputmethod.TextAttribute;
import android.view.inputmethod.TextBoundsInfoResult;
import android.view.inputmethod.TextSnapshot;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.view.inputmethod.InputConnectionCompat;
import io.flutter.Log;
import io.flutter.embedding.engine.systemchannels.TextInputChannel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Stateless Android {@link InputConnection} backed synchronously by Dart as the single source of
 * truth when running on Android's merged platform/UI thread.
 *
 * <p>Unlike {@link InputConnectionAdaptor}, this class does not extend {@link
 * android.view.inputmethod.BaseInputConnection} and maintains no mirrored {@link
 * android.text.Editable} or {@link android.text.DynamicLayout} in Java. Every read and edit is
 * dispatched synchronously to Dart through {@link Host#dispatchSyncRequest(ByteBuffer, int)}.
 */
final class FlutterInputConnection implements InputConnection {
  private static final String TAG = "FlutterInputConnection";
  private static final int TAKE_SNAPSHOT_MAX_CHARS = 1024;
  private static final int CONTENT_STREAM_BLOCK_BYTES = 64 * 1024;

  /** Host callbacks owned by {@link TextInputPlugin}. */
  interface Host {
    boolean isConnectionActive(@NonNull FlutterInputConnection connection);

    boolean isSyncModeActive();

    void enterImeCallback();

    void exitImeCallback();

    long getSyncNotifySeq();

    @NonNull
    SyncTextInputProtocol.DirectBufferPool getRequestBufferPool();

    @Nullable
    SyncTextInputProtocol.Response dispatchSyncRequest(@NonNull ByteBuffer buffer, int length);

    void onMutationResponse(
        @NonNull FlutterInputConnection connection,
        @NonNull SyncTextInputProtocol.Response response,
        long notifySeqBefore,
        boolean suppressSelectionUpdate);

    void onConnectionClosed(@NonNull FlutterInputConnection connection);
  }

  @NonNull private final View flutterView;
  private final int clientId;
  private final int engineGeneration;
  @NonNull private final TextInputChannel textInputChannel;
  @NonNull private final InputConnectionAdaptor.KeyboardDelegate keyboardDelegate;
  @NonNull private final EditorInfo editorInfo;
  private final boolean obscureText;
  @NonNull private final Host host;
  @NonNull private final InputMethodManager imm;

  private boolean closed = false;
  private int batchEditDepth = 0;
  private int combiningAccent = 0;
  @Nullable private ExtractedTextRequest extractRequest;
  private boolean monitorCursorUpdates = false;
  @Nullable private CursorAnchorInfo.Builder cursorAnchorInfoBuilder;

  FlutterInputConnection(
      @NonNull View flutterView,
      int clientId,
      int engineGeneration,
      @NonNull TextInputChannel textInputChannel,
      @NonNull InputConnectionAdaptor.KeyboardDelegate keyboardDelegate,
      @NonNull EditorInfo editorInfo,
      boolean obscureText,
      @NonNull Host host) {
    this.flutterView = flutterView;
    this.clientId = clientId;
    this.engineGeneration = engineGeneration;
    this.textInputChannel = textInputChannel;
    this.keyboardDelegate = keyboardDelegate;
    this.editorInfo = editorInfo;
    this.obscureText = obscureText;
    this.host = host;
    this.imm =
        (InputMethodManager) flutterView.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
  }

  int getClientId() {
    return clientId;
  }

  int getEngineGeneration() {
    return engineGeneration;
  }

  boolean isClosed() {
    return closed;
  }

  boolean isMonitoringExtractedText() {
    return !closed && extractRequest != null;
  }

  boolean isMonitoringCursorUpdates() {
    return !closed && monitorCursorUpdates;
  }

  /**
   * Marks this connection closed locally without dispatching back into Dart (used during engine
   * restart/destruction or when Dart already replaced/cleared the client).
   */
  void markClosedLocally() {
    closed = true;
    batchEditDepth = 0;
    combiningAccent = 0;
    extractRequest = null;
    monitorCursorUpdates = false;
  }

  void sendExtractedTextUpdate(@NonNull SyncTextInputProtocol.Response fullState) {
    final ExtractedTextRequest request = extractRequest;
    if (closed || request == null) {
      return;
    }
    imm.updateExtractedText(flutterView, request.token, buildExtractedText(fullState));
  }

  void sendCursorAnchorUpdate(
      @NonNull SyncTextInputProtocol.Response state, boolean textIsFullDocument) {
    if (closed || !monitorCursorUpdates) {
      return;
    }
    imm.updateCursorAnchorInfo(
        flutterView, buildCursorAnchorInfo(state, textIsFullDocument));
  }

  private boolean isUsable() {
    return !closed && host.isConnectionActive(this);
  }

  private boolean isUsableWithBridge() {
    return isUsable() && host.isSyncModeActive();
  }

  @Nullable
  private SyncTextInputProtocol.Response dispatchNoArgs(int opcode) {
    final SyncTextInputProtocol.DirectBufferPool pool = host.getRequestBufferPool();
    final ByteBuffer buf = pool.acquire(SyncTextInputProtocol.REQUEST_HEADER_BYTES);
    try {
      SyncTextInputProtocol.putHeader(buf, opcode, clientId);
      return host.dispatchSyncRequest(buf, buf.position());
    } finally {
      pool.release(buf);
    }
  }

  @Nullable
  private SyncTextInputProtocol.Response dispatchOneInt(int opcode, int arg0) {
    final SyncTextInputProtocol.DirectBufferPool pool = host.getRequestBufferPool();
    final ByteBuffer buf = pool.acquire(SyncTextInputProtocol.REQUEST_HEADER_BYTES + 4);
    try {
      SyncTextInputProtocol.putHeader(buf, opcode, clientId);
      buf.putInt(arg0);
      return host.dispatchSyncRequest(buf, buf.position());
    } finally {
      pool.release(buf);
    }
  }

  @Nullable
  private SyncTextInputProtocol.Response dispatchTwoInts(int opcode, int arg0, int arg1) {
    final SyncTextInputProtocol.DirectBufferPool pool = host.getRequestBufferPool();
    final ByteBuffer buf = pool.acquire(SyncTextInputProtocol.REQUEST_HEADER_BYTES + 8);
    try {
      SyncTextInputProtocol.putHeader(buf, opcode, clientId);
      buf.putInt(arg0);
      buf.putInt(arg1);
      return host.dispatchSyncRequest(buf, buf.position());
    } finally {
      pool.release(buf);
    }
  }

  @Nullable
  private SyncTextInputProtocol.Response dispatchThreeInts(
      int opcode, int arg0, int arg1, int arg2) {
    final SyncTextInputProtocol.DirectBufferPool pool = host.getRequestBufferPool();
    final ByteBuffer buf = pool.acquire(SyncTextInputProtocol.REQUEST_HEADER_BYTES + 12);
    try {
      SyncTextInputProtocol.putHeader(buf, opcode, clientId);
      buf.putInt(arg0);
      buf.putInt(arg1);
      buf.putInt(arg2);
      return host.dispatchSyncRequest(buf, buf.position());
    } finally {
      pool.release(buf);
    }
  }

  @Nullable
  private SyncTextInputProtocol.Response dispatchString(int opcode, @NonNull CharSequence text) {
    final int strBytes = SyncTextInputProtocol.encodedStringBytes(text);
    if (strBytes < 0) {
      return null;
    }
    final SyncTextInputProtocol.DirectBufferPool pool = host.getRequestBufferPool();
    final ByteBuffer buf = pool.acquire(SyncTextInputProtocol.REQUEST_HEADER_BYTES + strBytes);
    try {
      SyncTextInputProtocol.putHeader(buf, opcode, clientId);
      SyncTextInputProtocol.putUtf16String(buf, text);
      return host.dispatchSyncRequest(buf, buf.position());
    } finally {
      pool.release(buf);
    }
  }

  @Nullable
  private SyncTextInputProtocol.Response dispatchStringAndInt(
      int opcode, @NonNull CharSequence text, int arg) {
    final int strBytes = SyncTextInputProtocol.encodedStringBytes(text);
    if (strBytes < 0) {
      return null;
    }
    final SyncTextInputProtocol.DirectBufferPool pool = host.getRequestBufferPool();
    final ByteBuffer buf = pool.acquire(SyncTextInputProtocol.REQUEST_HEADER_BYTES + strBytes + 4);
    try {
      SyncTextInputProtocol.putHeader(buf, opcode, clientId);
      SyncTextInputProtocol.putUtf16String(buf, text);
      buf.putInt(arg);
      return host.dispatchSyncRequest(buf, buf.position());
    } finally {
      pool.release(buf);
    }
  }

  @Nullable
  private SyncTextInputProtocol.Response dispatchReplaceText(
      int start, int end, @NonNull CharSequence text, int newCursorPosition) {
    final int strBytes = SyncTextInputProtocol.encodedStringBytes(text);
    if (strBytes < 0) {
      return null;
    }
    final SyncTextInputProtocol.DirectBufferPool pool = host.getRequestBufferPool();
    final ByteBuffer buf =
        pool.acquire(SyncTextInputProtocol.REQUEST_HEADER_BYTES + 8 + strBytes + 4);
    try {
      SyncTextInputProtocol.putHeader(buf, SyncTextInputProtocol.OP_REPLACE_TEXT, clientId);
      buf.putInt(start);
      buf.putInt(end);
      SyncTextInputProtocol.putUtf16String(buf, text);
      buf.putInt(newCursorPosition);
      return host.dispatchSyncRequest(buf, buf.position());
    } finally {
      pool.release(buf);
    }
  }

  private boolean finishMutation(
      @Nullable SyncTextInputProtocol.Response response,
      long notifySeqBefore,
      boolean suppressSelectionUpdate) {
    if (response == null || !response.isOk()) {
      return false;
    }
    host.onMutationResponse(this, response, notifySeqBefore, suppressSelectionUpdate);
    return true;
  }

  @NonNull
  private ExtractedText buildExtractedText(@NonNull SyncTextInputProtocol.Response state) {
    final ExtractedText extracted = new ExtractedText();
    extracted.startOffset = 0;
    extracted.partialStartOffset = -1;
    extracted.partialEndOffset = -1;
    extracted.selectionStart = state.selectionStart;
    extracted.selectionEnd = state.selectionEnd;
    extracted.text = state.text != null ? state.text : "";
    return extracted;
  }

  @NonNull
  private CursorAnchorInfo buildCursorAnchorInfo(
      @NonNull SyncTextInputProtocol.Response state, boolean textIsFullDocument) {
    if (cursorAnchorInfoBuilder == null) {
      cursorAnchorInfoBuilder = new CursorAnchorInfo.Builder();
    } else {
      cursorAnchorInfoBuilder.reset();
    }
    cursorAnchorInfoBuilder.setSelectionRange(state.selectionStart, state.selectionEnd);
    final int composingStart = state.composingStart;
    final int composingEnd = state.composingEnd;
    if (composingStart >= 0 && composingEnd > composingStart && state.text != null) {
      if (textIsFullDocument) {
        if (composingEnd <= state.text.length()) {
          cursorAnchorInfoBuilder.setComposingText(
              composingStart, state.text.subSequence(composingStart, composingEnd));
        } else {
          cursorAnchorInfoBuilder.setComposingText(-1, "");
        }
      } else if (state.text.length() == composingEnd - composingStart) {
        cursorAnchorInfoBuilder.setComposingText(composingStart, state.text);
      } else {
        cursorAnchorInfoBuilder.setComposingText(-1, "");
      }
    } else {
      cursorAnchorInfoBuilder.setComposingText(-1, "");
    }
    return cursorAnchorInfoBuilder.build();
  }

  @RequiresApi(API_LEVELS.API_31)
  @Nullable
  private static SurroundingText buildSurroundingText(
      @Nullable SyncTextInputProtocol.Response response) {
    if (response == null || !response.isOk() || !response.hasText() || response.text == null) {
      return null;
    }
    final int offset = response.intResult;
    final int textLen = response.text.length();
    final int relSelStart = response.selectionStart;
    final int relSelEnd = response.selectionEnd;
    if (offset < 0
        || relSelStart < 0
        || relSelEnd < 0
        || relSelStart > textLen
        || relSelEnd > textLen) {
      return null;
    }
    return new SurroundingText(response.text, relSelStart, relSelEnd, offset);
  }

  // -------- Start: InputConnection implementation (all 38 SDK 37 methods) --------

  @Override
  public boolean beginBatchEdit() {
    if (!isUsable()) {
      return false;
    }
    host.enterImeCallback();
    try {
      final SyncTextInputProtocol.Response response =
          dispatchNoArgs(SyncTextInputProtocol.OP_BEGIN_BATCH_EDIT);
      if (response == null || !response.isOk()) {
        return false;
      }
      batchEditDepth++;
      return true;
    } finally {
      host.exitImeCallback();
    }
  }

  @Override
  public boolean endBatchEdit() {
    if (!isUsable() || batchEditDepth <= 0) {
      return false;
    }
    host.enterImeCallback();
    try {
      batchEditDepth--;
      final long notifySeqBefore = host.getSyncNotifySeq();
      final SyncTextInputProtocol.Response response =
          dispatchNoArgs(SyncTextInputProtocol.OP_END_BATCH_EDIT);
      if (response != null && response.isOk()) {
        host.onMutationResponse(this, response, notifySeqBefore, /*suppressSelectionUpdate=*/ false);
      }
      return batchEditDepth > 0;
    } finally {
      host.exitImeCallback();
    }
  }

  @Override
  public void closeConnection() {
    if (closed) {
      return;
    }
    final boolean shouldNotifyDart = host.isConnectionActive(this) && host.isSyncModeActive();
    host.enterImeCallback();
    try {
      if (shouldNotifyDart) {
        final long notifySeqBefore = host.getSyncNotifySeq();
        final SyncTextInputProtocol.Response response =
            dispatchNoArgs(SyncTextInputProtocol.OP_CLOSE_CONNECTION);
        if (response != null && response.isOk() && response.isCommitted()) {
          host.onMutationResponse(
              this, response, notifySeqBefore, /*suppressSelectionUpdate=*/ true);
        }
      }
    } finally {
      markClosedLocally();
      host.onConnectionClosed(this);
      host.exitImeCallback();
    }
  }

  @Override
  public boolean clearMetaKeyStates(int states) {
    return isUsableWithBridge();
  }

  @Override
  public boolean commitCompletion(@Nullable CompletionInfo text) {
    return false;
  }

  @Override
  public boolean commitCorrection(@Nullable CorrectionInfo correctionInfo) {
    return false;
  }

  @Override
  public boolean commitText(@Nullable CharSequence text, int newCursorPosition) {
    if (!isUsable()) {
      return false;
    }
    final CharSequence safeText = text != null ? text : "";
    host.enterImeCallback();
    try {
      final long notifySeqBefore = host.getSyncNotifySeq();
      return finishMutation(
          dispatchStringAndInt(
              SyncTextInputProtocol.OP_COMMIT_TEXT, safeText, newCursorPosition),
          notifySeqBefore,
          /*suppressSelectionUpdate=*/ false);
    } finally {
      host.exitImeCallback();
    }
  }

  @RequiresApi(API_LEVELS.API_33)
  @Override
  public boolean commitText(
      @Nullable CharSequence text, int newCursorPosition, @Nullable TextAttribute textAttribute) {
    return commitText(text, newCursorPosition);
  }

  @Override
  public boolean deleteSurroundingText(int beforeLength, int afterLength) {
    if (!isUsable()) {
      return false;
    }
    host.enterImeCallback();
    try {
      final long notifySeqBefore = host.getSyncNotifySeq();
      return finishMutation(
          dispatchTwoInts(
              SyncTextInputProtocol.OP_DELETE_SURROUNDING_TEXT, beforeLength, afterLength),
          notifySeqBefore,
          /*suppressSelectionUpdate=*/ false);
    } finally {
      host.exitImeCallback();
    }
  }

  @Override
  public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
    if (!isUsable()) {
      return false;
    }
    host.enterImeCallback();
    try {
      final long notifySeqBefore = host.getSyncNotifySeq();
      return finishMutation(
          dispatchTwoInts(
              SyncTextInputProtocol.OP_DELETE_SURROUNDING_TEXT_IN_CODE_POINTS,
              beforeLength,
              afterLength),
          notifySeqBefore,
          /*suppressSelectionUpdate=*/ false);
    } finally {
      host.exitImeCallback();
    }
  }

  @Override
  public boolean finishComposingText() {
    if (!isUsable()) {
      return false;
    }
    host.enterImeCallback();
    try {
      final long notifySeqBefore = host.getSyncNotifySeq();
      return finishMutation(
          dispatchNoArgs(SyncTextInputProtocol.OP_FINISH_COMPOSING_TEXT),
          notifySeqBefore,
          /*suppressSelectionUpdate=*/ false);
    } finally {
      host.exitImeCallback();
    }
  }

  @Override
  public int getCursorCapsMode(int reqModes) {
    if (!isUsable() || reqModes == 0) {
      return 0;
    }
    host.enterImeCallback();
    try {
      final SyncTextInputProtocol.Response response =
          dispatchOneInt(SyncTextInputProtocol.OP_GET_CURSOR_CAPS_MODE, reqModes);
      if (response == null || !response.isOk()) {
        return 0;
      }
      return response.intResult;
    } finally {
      host.exitImeCallback();
    }
  }

  @Nullable
  @Override
  public ExtractedText getExtractedText(@Nullable ExtractedTextRequest request, int flags) {
    if (!isUsable()) {
      return null;
    }
    final boolean textMonitor = (flags & GET_EXTRACTED_TEXT_MONITOR) != 0;

    host.enterImeCallback();
    try {
      final SyncTextInputProtocol.Response response =
          dispatchOneInt(SyncTextInputProtocol.OP_GET_EXTRACTED_TEXT, flags);
      if (response == null || !response.isOk() || !response.hasText()) {
        return null;
      }
      if (textMonitor == (extractRequest == null)) {
        Log.d(TAG, "The input method toggled text monitoring " + (textMonitor ? "on" : "off"));
      }
      extractRequest = textMonitor ? request : null;
      return buildExtractedText(response);
    } finally {
      host.exitImeCallback();
    }
  }

  @Nullable
  @Override
  public Handler getHandler() {
    return null;
  }

  @Nullable
  @Override
  public CharSequence getSelectedText(int flags) {
    if (!isUsable()) {
      return null;
    }
    host.enterImeCallback();
    try {
      final SyncTextInputProtocol.Response response =
          dispatchOneInt(SyncTextInputProtocol.OP_GET_SELECTED_TEXT, flags);
      if (response == null || !response.isOk() || !response.hasText()) {
        return null;
      }
      return response.text;
    } finally {
      host.exitImeCallback();
    }
  }

  @RequiresApi(API_LEVELS.API_31)
  @Nullable
  @Override
  public SurroundingText getSurroundingText(int beforeLength, int afterLength, int flags) {
    // Intentionally return null rather than throwing IllegalArgumentException on negative lengths
    // to stay defensive against buggy third-party IMEs.
    if (!isUsable() || beforeLength < 0 || afterLength < 0) {
      return null;
    }
    host.enterImeCallback();
    try {
      final SyncTextInputProtocol.Response response =
          dispatchThreeInts(
              SyncTextInputProtocol.OP_GET_SURROUNDING_TEXT, beforeLength, afterLength, flags);
      return buildSurroundingText(response);
    } finally {
      host.exitImeCallback();
    }
  }

  @Nullable
  @Override
  public CharSequence getTextAfterCursor(int n, int flags) {
    if (!isUsable() || n < 0) {
      return null;
    }
    host.enterImeCallback();
    try {
      final SyncTextInputProtocol.Response response =
          dispatchTwoInts(SyncTextInputProtocol.OP_GET_TEXT_AFTER_CURSOR, n, flags);
      if (response == null || !response.isOk() || !response.hasText()) {
        return null;
      }
      return response.text != null ? response.text : "";
    } finally {
      host.exitImeCallback();
    }
  }

  @Nullable
  @Override
  public CharSequence getTextBeforeCursor(int n, int flags) {
    if (!isUsable() || n < 0) {
      return null;
    }
    host.enterImeCallback();
    try {
      final SyncTextInputProtocol.Response response =
          dispatchTwoInts(SyncTextInputProtocol.OP_GET_TEXT_BEFORE_CURSOR, n, flags);
      if (response == null || !response.isOk() || !response.hasText()) {
        return null;
      }
      return response.text != null ? response.text : "";
    } finally {
      host.exitImeCallback();
    }
  }

  @Override
  public boolean performContextMenuAction(int id) {
    if (!isUsable()) {
      return false;
    }
    host.enterImeCallback();
    try {
      if (id == android.R.id.selectAll) {
        final long notifySeqBefore = host.getSyncNotifySeq();
        return finishMutation(
            dispatchNoArgs(SyncTextInputProtocol.OP_SELECT_ALL),
            notifySeqBefore,
            /*suppressSelectionUpdate=*/ false);
      } else if (id == android.R.id.cut) {
        if (obscureText) {
          return false;
        }
        final long notifySeqBefore = host.getSyncNotifySeq();
        final SyncTextInputProtocol.Response response =
            dispatchNoArgs(SyncTextInputProtocol.OP_CUT);
        if (response == null || !response.isOk()) {
          return false;
        }
        if (response.hasText() && response.text != null && !response.text.isEmpty()) {
          final ClipboardManager clipboard =
              (ClipboardManager)
                  flutterView.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
          if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("text label?", response.text));
          }
        }
        host.onMutationResponse(this, response, notifySeqBefore, /*suppressSelectionUpdate=*/ false);
        return true;
      } else if (id == android.R.id.copy) {
        if (obscureText) {
          return false;
        }
        final SyncTextInputProtocol.Response response =
            dispatchNoArgs(SyncTextInputProtocol.OP_COPY);
        if (response == null || !response.isOk()) {
          return false;
        }
        if (response.hasText() && response.text != null && !response.text.isEmpty()) {
          final ClipboardManager clipboard =
              (ClipboardManager)
                  flutterView.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
          if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("text label?", response.text));
          }
        }
        return true;
      } else if (id == android.R.id.paste) {
        if (!host.isSyncModeActive()) {
          return false;
        }
        final ClipboardManager clipboard =
            (ClipboardManager)
                flutterView.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
          final ClipData clip = clipboard.getPrimaryClip();
          if (clip != null && clip.getItemCount() > 0) {
            final CharSequence textToPaste = clip.getItemAt(0).coerceToText(flutterView.getContext());
            if (textToPaste != null) {
              final long notifySeqBefore = host.getSyncNotifySeq();
              return finishMutation(
                  dispatchString(SyncTextInputProtocol.OP_PASTE, textToPaste),
                  notifySeqBefore,
                  /*suppressSelectionUpdate=*/ false);
            }
          }
        }
        return true;
      }
      return false;
    } finally {
      host.exitImeCallback();
    }
  }

  @Override
  public boolean performEditorAction(int actionCode) {
    if (!isUsableWithBridge()) {
      return false;
    }
    switch (actionCode) {
      case EditorInfo.IME_ACTION_NONE:
        textInputChannel.newline(clientId);
        break;
      case EditorInfo.IME_ACTION_UNSPECIFIED:
        textInputChannel.unspecifiedAction(clientId);
        break;
      case EditorInfo.IME_ACTION_GO:
        textInputChannel.go(clientId);
        break;
      case EditorInfo.IME_ACTION_SEARCH:
        textInputChannel.search(clientId);
        break;
      case EditorInfo.IME_ACTION_SEND:
        textInputChannel.send(clientId);
        break;
      case EditorInfo.IME_ACTION_NEXT:
        textInputChannel.next(clientId);
        break;
      case EditorInfo.IME_ACTION_PREVIOUS:
        textInputChannel.previous(clientId);
        break;
      default:
      case EditorInfo.IME_ACTION_DONE:
        textInputChannel.done(clientId);
        break;
    }
    return true;
  }

  @RequiresApi(API_LEVELS.API_34)
  @Override
  public void performHandwritingGesture(
      @NonNull HandwritingGesture gesture,
      @Nullable Executor executor,
      @Nullable IntConsumer consumer) {
    if (executor != null && consumer != null) {
      executor.execute(() -> consumer.accept(HANDWRITING_GESTURE_RESULT_UNSUPPORTED));
    }
  }

  @Override
  public boolean performPrivateCommand(@Nullable String action, @Nullable Bundle data) {
    if (!isUsableWithBridge()) {
      return false;
    }
    textInputChannel.performPrivateCommand(clientId, action, data);
    return true;
  }

  @RequiresApi(API_LEVELS.API_31)
  @Override
  public boolean performSpellCheck() {
    return false;
  }

  @RequiresApi(API_LEVELS.API_34)
  @Override
  public boolean previewHandwritingGesture(
      @NonNull PreviewableHandwritingGesture gesture,
      @Nullable CancellationSignal cancellationSignal) {
    return false;
  }

  @RequiresApi(API_LEVELS.API_34)
  @Override
  public boolean replaceText(
      int start,
      int end,
      @NonNull CharSequence text,
      int newCursorPosition,
      @Nullable TextAttribute textAttribute) {
    if (!isUsable()) {
      return false;
    }
    final CharSequence safeText = text != null ? text : "";
    host.enterImeCallback();
    try {
      final long notifySeqBefore = host.getSyncNotifySeq();
      return finishMutation(
          dispatchReplaceText(start, end, safeText, newCursorPosition),
          notifySeqBefore,
          /*suppressSelectionUpdate=*/ false);
    } finally {
      host.exitImeCallback();
    }
  }

  @Override
  public boolean reportFullscreenMode(boolean enabled) {
    return true;
  }

  @Override
  public boolean requestCursorUpdates(int cursorUpdateMode) {
    if (!isUsableWithBridge()) {
      return false;
    }
    host.enterImeCallback();
    try {
      if ((cursorUpdateMode & CURSOR_UPDATE_IMMEDIATE) != 0) {
        final SyncTextInputProtocol.Response cursorState =
            dispatchNoArgs(SyncTextInputProtocol.OP_GET_CURSOR_ANCHOR_STATE);
        if (cursorState != null && cursorState.isOk()) {
          imm.updateCursorAnchorInfo(
              flutterView, buildCursorAnchorInfo(cursorState, /*textIsFullDocument=*/ false));
        }
      }

      final boolean updated = (cursorUpdateMode & CURSOR_UPDATE_MONITOR) != 0;
      if (updated != monitorCursorUpdates) {
        Log.d(TAG, "The input method toggled cursor monitoring " + (updated ? "on" : "off"));
      }
      monitorCursorUpdates = updated;
      return true;
    } finally {
      host.exitImeCallback();
    }
  }

  @RequiresApi(API_LEVELS.API_33)
  @Override
  public boolean requestCursorUpdates(int cursorUpdateMode, int cursorUpdateFilter) {
    if (cursorUpdateFilter == 0) {
      return requestCursorUpdates(cursorUpdateMode);
    }
    return false;
  }

  @RequiresApi(API_LEVELS.API_34)
  @Override
  public void requestTextBoundsInfo(
      @NonNull RectF bounds,
      @NonNull Executor executor,
      @NonNull Consumer<TextBoundsInfoResult> consumer) {
    if (executor != null && consumer != null) {
      executor.execute(
          () -> consumer.accept(new TextBoundsInfoResult(TextBoundsInfoResult.CODE_UNSUPPORTED)));
    }
  }

  @Override
  public boolean sendKeyEvent(@Nullable KeyEvent event) {
    if (event == null || !isUsableWithBridge()) {
      return false;
    }
    return keyboardDelegate.handleEvent(event);
  }

  @Override
  public boolean setComposingRegion(int start, int end) {
    if (!isUsable()) {
      return false;
    }
    host.enterImeCallback();
    try {
      final long notifySeqBefore = host.getSyncNotifySeq();
      return finishMutation(
          dispatchTwoInts(SyncTextInputProtocol.OP_SET_COMPOSING_REGION, start, end),
          notifySeqBefore,
          /*suppressSelectionUpdate=*/ true);
    } finally {
      host.exitImeCallback();
    }
  }

  @RequiresApi(API_LEVELS.API_33)
  @Override
  public boolean setComposingRegion(int start, int end, @Nullable TextAttribute textAttribute) {
    return setComposingRegion(start, end);
  }

  @Override
  public boolean setComposingText(@Nullable CharSequence text, int newCursorPosition) {
    if (!isUsable()) {
      return false;
    }
    final CharSequence safeText = text != null ? text : "";
    host.enterImeCallback();
    try {
      final long notifySeqBefore = host.getSyncNotifySeq();
      return finishMutation(
          dispatchStringAndInt(
              SyncTextInputProtocol.OP_SET_COMPOSING_TEXT, safeText, newCursorPosition),
          notifySeqBefore,
          /*suppressSelectionUpdate=*/ false);
    } finally {
      host.exitImeCallback();
    }
  }

  @RequiresApi(API_LEVELS.API_33)
  @Override
  public boolean setComposingText(
      @Nullable CharSequence text, int newCursorPosition, @Nullable TextAttribute textAttribute) {
    return setComposingText(text, newCursorPosition);
  }

  @RequiresApi(API_LEVELS.API_31)
  @Override
  public boolean setImeConsumesInput(boolean imeConsumesInput) {
    return false;
  }

  @Override
  public boolean setSelection(int start, int end) {
    if (!isUsable()) {
      return false;
    }
    host.enterImeCallback();
    try {
      final long notifySeqBefore = host.getSyncNotifySeq();
      return finishMutation(
          dispatchTwoInts(SyncTextInputProtocol.OP_SET_SELECTION, start, end),
          notifySeqBefore,
          /*suppressSelectionUpdate=*/ false);
    } finally {
      host.exitImeCallback();
    }
  }

  @RequiresApi(API_LEVELS.API_33)
  @Nullable
  @Override
  public TextSnapshot takeSnapshot() {
    if (!isUsable()) {
      return null;
    }
    host.enterImeCallback();
    try {
      final SyncTextInputProtocol.Response surroundingResponse =
          dispatchThreeInts(
              SyncTextInputProtocol.OP_GET_SURROUNDING_TEXT,
              TAKE_SNAPSHOT_MAX_CHARS,
              TAKE_SNAPSHOT_MAX_CHARS,
              0);
      final SurroundingText surroundingText = buildSurroundingText(surroundingResponse);
      if (surroundingText == null) {
        return null;
      }

      int compStart = surroundingResponse.composingStart;
      int compEnd = surroundingResponse.composingEnd;
      if (compStart < 0 || compEnd < 0 || compStart == compEnd) {
        compStart = -1;
        compEnd = -1;
      } else if (compEnd < compStart) {
        final int tmp = compStart;
        compStart = compEnd;
        compEnd = tmp;
      }

      final int capsMask =
          TextUtils.CAP_MODE_CHARACTERS
              | TextUtils.CAP_MODE_WORDS
              | TextUtils.CAP_MODE_SENTENCES;
      final SyncTextInputProtocol.Response capsResponse =
          dispatchOneInt(SyncTextInputProtocol.OP_GET_CURSOR_CAPS_MODE, capsMask);
      final int capsMode =
          capsResponse != null && capsResponse.isOk() ? capsResponse.intResult : 0;

      return new TextSnapshot(surroundingText, compStart, compEnd, capsMode);
    } finally {
      host.exitImeCallback();
    }
  }

  @Override
  public boolean commitContent(
      @Nullable InputContentInfo inputContentInfo, int flags, @Nullable Bundle opts) {
    if (!isUsableWithBridge() || inputContentInfo == null) {
      return false;
    }
    if (Build.VERSION.SDK_INT < API_LEVELS.API_25
        || (flags & InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) == 0) {
      return false;
    }
    try {
      inputContentInfo.requestPermission();
    } catch (Exception e) {
      return false;
    }

    try {
      if (inputContentInfo.getDescription() == null
          || inputContentInfo.getDescription().getMimeTypeCount() <= 0) {
        return false;
      }
      final Uri uri = inputContentInfo.getContentUri();
      final String mimeType = inputContentInfo.getDescription().getMimeType(0);
      if (uri == null) {
        return false;
      }
      final Context context = flutterView.getContext();
      try (InputStream is = context.getContentResolver().openInputStream(uri)) {
        if (is == null) {
          return false;
        }
        final byte[] data = readStreamFully(is, CONTENT_STREAM_BLOCK_BYTES);
        final Map<String, Object> obj = new HashMap<>();
        obj.put("mimeType", mimeType);
        obj.put("data", data);
        obj.put("uri", uri.toString());
        textInputChannel.commitContent(clientId, obj);
        return true;
      } catch (IOException | SecurityException ex) {
        return false;
      }
    } finally {
      inputContentInfo.releasePermission();
    }
  }

  // -------- End: InputConnection implementation --------

  /**
   * Handles hardware or fallback soft keyboard events routed back from {@link
   * io.flutter.embedding.android.KeyboardManager} via {@link TextInputPlugin#handleKeyEvent}.
   */
  boolean handleKeyEvent(@NonNull KeyEvent event) {
    if (!isUsableWithBridge()) {
      return false;
    }
    if (event.getAction() != KeyEvent.ACTION_DOWN) {
      return false;
    }
    final int keyCode = event.getKeyCode();
    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
      combiningAccent = 0;
      return dispatchMoveCursor(SyncTextInputProtocol.DIR_LEFT, event.isShiftPressed());
    } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
      combiningAccent = 0;
      return dispatchMoveCursor(SyncTextInputProtocol.DIR_RIGHT, event.isShiftPressed());
    } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
      combiningAccent = 0;
      return dispatchMoveCursor(SyncTextInputProtocol.DIR_UP, event.isShiftPressed());
    } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
      combiningAccent = 0;
      return dispatchMoveCursor(SyncTextInputProtocol.DIR_DOWN, event.isShiftPressed());
    } else if ((keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
        && (InputType.TYPE_TEXT_FLAG_MULTI_LINE & editorInfo.inputType) == 0) {
      combiningAccent = 0;
      return performEditorAction(editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION);
    } else {
      int unicodeChar = event.getUnicodeChar();
      if (unicodeChar == 0) {
        if (!KeyEvent.isModifierKey(keyCode)) {
          combiningAccent = 0;
        }
        return false;
      }
      if ((unicodeChar & KeyCharacterMap.COMBINING_ACCENT) != 0) {
        combiningAccent = unicodeChar & KeyCharacterMap.COMBINING_ACCENT_MASK;
        return true;
      }
      if (combiningAccent != 0) {
        final int combined = KeyCharacterMap.getDeadChar(combiningAccent, unicodeChar);
        combiningAccent = 0;
        if (combined != 0) {
          unicodeChar = combined;
        }
      }
      final String text = new String(Character.toChars(unicodeChar));
      return dispatchInsertKeyCharacter(text);
    }
  }

  private boolean dispatchInsertKeyCharacter(@NonNull String text) {
    host.enterImeCallback();
    try {
      final long notifySeqBefore = host.getSyncNotifySeq();
      return finishMutation(
          dispatchString(SyncTextInputProtocol.OP_INSERT_KEY_CHARACTER, text),
          notifySeqBefore,
          /*suppressSelectionUpdate=*/ false);
    } finally {
      host.exitImeCallback();
    }
  }

  private boolean dispatchMoveCursor(int direction, boolean extendSelection) {
    host.enterImeCallback();
    try {
      final long notifySeqBefore = host.getSyncNotifySeq();
      final SyncTextInputProtocol.Response response =
          dispatchTwoInts(
              SyncTextInputProtocol.OP_MOVE_CURSOR, direction, extendSelection ? 1 : 0);
      if (response == null || !response.isOk()) {
        return false;
      }
      host.onMutationResponse(this, response, notifySeqBefore, /*suppressSelectionUpdate=*/ false);
      return true;
    } finally {
      host.exitImeCallback();
    }
  }

  @NonNull
  private static byte[] readStreamFully(@NonNull InputStream is, int blockSize) throws IOException {
    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    final byte[] buffer = new byte[blockSize];
    int len;
    while ((len = is.read(buffer)) != -1) {
      baos.write(buffer, 0, len);
    }
    return baos.toByteArray();
  }
}
