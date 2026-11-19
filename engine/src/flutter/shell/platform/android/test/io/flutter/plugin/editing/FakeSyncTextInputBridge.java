// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugin.editing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.flutter.embedding.engine.FlutterJNI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * In-process test double for {@link SyncTextInputBridge} that mirrors Dart's binary protocol and
 * {@code AndroidInputConnectionSemantics} for Robolectric tests.
 */
final class FakeSyncTextInputBridge implements SyncTextInputBridge {
  interface DispatchInterceptor {
    /**
     * Optional hook called before normal handling. Return non-null byte[] to override the response
     * directly, or null to fall through to default state handling (while still allowing side
     * effects such as reentrant calls into {@link TextInputPlugin}).
     */
    @Nullable
    byte[] beforeDispatch(int opcode, int clientId, @NonNull ByteBuffer request);
  }

  boolean enabled = true;
  int activeClientId = -1;
  @NonNull String text = "";
  int selStart = -1;
  int selEnd = -1;
  int compStart = -1;
  int compEnd = -1;

  int batchDepth = 0;
  @Nullable String batchPreText;
  int batchPreSelStart = -1;
  int batchPreSelEnd = -1;
  int batchPreCompStart = -1;
  int batchPreCompEnd = -1;
  boolean batchTextChanged = false;
  boolean batchDirty = false;

  boolean forceNextDiverged = false;
  boolean forceNextComposingChangedByFramework = false;
  boolean forceNextClientChanged = false;
  @Nullable String nextPostCommitTextOverride;
  int nextPostCommitSelStart = Integer.MIN_VALUE;
  int nextPostCommitSelEnd = Integer.MIN_VALUE;
  int nextPostCommitCompStart = Integer.MIN_VALUE;
  int nextPostCommitCompEnd = Integer.MIN_VALUE;

  @Nullable DispatchInterceptor interceptor;
  @Nullable FlutterJNI.SyncTextInputEditingStateListener editingStateListener;
  final List<Integer> recordedOpcodes = new ArrayList<>();

  void setClientState(
      int clientId, @NonNull String text, int selStart, int selEnd, int compStart, int compEnd) {
    this.activeClientId = clientId;
    this.text = text;
    this.selStart = selStart;
    this.selEnd = selEnd;
    this.compStart = compStart;
    this.compEnd = compEnd;
    resetBatch();
  }

  void resetBatch() {
    batchDepth = 0;
    batchPreText = null;
    batchTextChanged = false;
    batchDirty = false;
  }

  int countOpcode(int opcode) {
    int count = 0;
    for (int op : recordedOpcodes) {
      if (op == opcode) {
        count++;
      }
    }
    return count;
  }

  @Override
  public boolean isSyncTextInputEnabled() {
    return enabled;
  }

  @Override
  public void setEditingStateListener(
      @Nullable FlutterJNI.SyncTextInputEditingStateListener listener) {
    this.editingStateListener = listener;
  }

  @Override
  public void clearEditingStateListener(
      @NonNull FlutterJNI.SyncTextInputEditingStateListener listener) {
    if (this.editingStateListener == listener) {
      this.editingStateListener = null;
    }
  }

  @Nullable
  @Override
  public byte[] dispatchSyncTextInput(@NonNull ByteBuffer request, int length) {
    if (!enabled || length < SyncTextInputProtocol.REQUEST_HEADER_BYTES) {
      return null;
    }
    final ByteBuffer view = request.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    view.position(0);
    view.limit(length);
    final int opcode = view.getInt();
    final int clientId = view.getInt();
    recordedOpcodes.add(opcode);

    if (interceptor != null) {
      final byte[] overridden = interceptor.beforeDispatch(opcode, clientId, view.duplicate().order(ByteOrder.LITTLE_ENDIAN));
      if (overridden != null) {
        return overridden;
      }
    }

    if (activeClientId != clientId) {
      return encodeResponse(
          SyncTextInputProtocol.STATUS_STALE_CLIENT, 0, -1, -1, -1, -1, 0, false, null);
    }

    switch (opcode) {
      case SyncTextInputProtocol.OP_QUERY_INITIAL_STATE:
        return handleQueryInitialState();
      case SyncTextInputProtocol.OP_BEGIN_BATCH_EDIT:
        if (batchDepth == 0) {
          batchPreText = text;
          batchPreSelStart = selStart;
          batchPreSelEnd = selEnd;
          batchPreCompStart = compStart;
          batchPreCompEnd = compEnd;
          batchTextChanged = false;
          batchDirty = false;
        }
        batchDepth++;
        return encodeStateResponse(SyncTextInputProtocol.RESP_FLAG_BATCH_ACTIVE, 0, false, null);
      case SyncTextInputProtocol.OP_END_BATCH_EDIT:
        if (batchDepth <= 0) {
          return encodeStateResponse(0, 0, false, null);
        }
        batchDepth--;
        if (batchDepth > 0) {
          return encodeStateResponse(SyncTextInputProtocol.RESP_FLAG_BATCH_ACTIVE, 0, false, null);
        }
        return finishBatchCommit();
      case SyncTextInputProtocol.OP_CLOSE_CONNECTION:
        return closeOrUnwindConnection();
      case SyncTextInputProtocol.OP_COMMIT_TEXT:
        {
          final String inserted = readUtf16String(view);
          final int newCursorPos = view.getInt();
          return applyReplaceComposingOrSelection(inserted != null ? inserted : "", newCursorPos, false);
        }
      case SyncTextInputProtocol.OP_SET_COMPOSING_TEXT:
        {
          final String composing = readUtf16String(view);
          final int newCursorPos = view.getInt();
          final String safe = composing != null ? composing : "";
          return applyReplaceComposingOrSelection(safe, newCursorPos, !safe.isEmpty());
        }
      case SyncTextInputProtocol.OP_SET_COMPOSING_REGION:
        return applySetComposingRegion(view.getInt(), view.getInt());
      case SyncTextInputProtocol.OP_FINISH_COMPOSING_TEXT:
        return applyFinishComposingText();
      case SyncTextInputProtocol.OP_DELETE_SURROUNDING_TEXT:
        return applyDeleteSurroundingText(view.getInt(), view.getInt());
      case SyncTextInputProtocol.OP_DELETE_SURROUNDING_TEXT_IN_CODE_POINTS:
        return applyDeleteSurroundingTextInCodePoints(view.getInt(), view.getInt());
      case SyncTextInputProtocol.OP_SET_SELECTION:
        return applySetSelection(view.getInt(), view.getInt());
      case SyncTextInputProtocol.OP_REPLACE_TEXT:
        {
          final int start = view.getInt();
          final int end = view.getInt();
          final String replacement = readUtf16String(view);
          final int newCursorPos = view.getInt();
          return applyReplaceText(start, end, replacement != null ? replacement : "", newCursorPos);
        }
      case SyncTextInputProtocol.OP_GET_TEXT_BEFORE_CURSOR:
        {
          final int n = view.getInt();
          if (n < 0) {
            return encodeResponse(
                SyncTextInputProtocol.STATUS_NOT_HANDLED, 0, -1, -1, -1, -1, 0, false, null);
          }
          final int a = validSelStart();
          final int start = Math.max(0, a - n);
          return encodeStateResponse(0, 0, true, text.substring(start, a));
        }
      case SyncTextInputProtocol.OP_GET_TEXT_AFTER_CURSOR:
        {
          final int n = view.getInt();
          if (n < 0) {
            return encodeResponse(
                SyncTextInputProtocol.STATUS_NOT_HANDLED, 0, -1, -1, -1, -1, 0, false, null);
          }
          final int b = validSelEnd();
          final int end = Math.min(text.length(), b + n);
          return encodeStateResponse(0, 0, true, text.substring(b, end));
        }
      case SyncTextInputProtocol.OP_GET_SELECTED_TEXT:
        {
          if (selStart < 0 || selEnd < 0 || selStart == selEnd) {
            return encodeStateResponse(0, 0, true, null);
          }
          final int a = validSelStart();
          final int b = validSelEnd();
          return encodeStateResponse(0, 0, true, text.substring(a, b));
        }
      case SyncTextInputProtocol.OP_GET_SURROUNDING_TEXT:
        {
          final int before = view.getInt();
          final int after = view.getInt();
          if (before < 0 || after < 0 || selStart < 0 || selEnd < 0) {
            return encodeResponse(
                SyncTextInputProtocol.STATUS_NOT_HANDLED, 0, -1, -1, -1, -1, 0, false, null);
          }
          final int a = validSelStart();
          final int b = validSelEnd();
          final int startOffset = Math.max(0, a - before);
          final int endOffset = Math.min(text.length(), b + after);
          return encodeResponse(
              SyncTextInputProtocol.STATUS_OK,
              SyncTextInputProtocol.RESP_FLAG_HAS_TEXT,
              a - startOffset,
              b - startOffset,
              compStart,
              compEnd,
              startOffset,
              true,
              text.substring(startOffset, endOffset));
        }
      case SyncTextInputProtocol.OP_GET_CURSOR_CAPS_MODE:
        {
          final int reqModes = view.getInt();
          final int caps = computeCapsMode(text, selStart >= 0 ? validSelStart() : -1, reqModes);
          return encodeStateResponse(0, caps, false, null);
        }
      case SyncTextInputProtocol.OP_GET_EXTRACTED_TEXT:
      case SyncTextInputProtocol.OP_GET_FULL_STATE:
        return encodeStateResponse(0, 0, true, text);
      case SyncTextInputProtocol.OP_GET_CURSOR_ANCHOR_STATE:
        {
          if (compStart >= 0 && compEnd > compStart) {
            final int ca = clamp(compStart, 0, text.length());
            final int cb = clamp(compEnd, 0, text.length());
            if (cb > ca) {
              return encodeResponse(
                  SyncTextInputProtocol.STATUS_OK,
                  SyncTextInputProtocol.RESP_FLAG_HAS_TEXT,
                  selStart,
                  selEnd,
                  ca,
                  cb,
                  0,
                  true,
                  text.substring(ca, cb));
            }
          }
          return encodeResponse(
              SyncTextInputProtocol.STATUS_OK,
              0,
              selStart,
              selEnd,
              -1,
              -1,
              0,
              false,
              null);
        }
      case SyncTextInputProtocol.OP_INSERT_KEY_CHARACTER:
        {
          final String inserted = readUtf16String(view);
          if (selStart < 0 || selEnd < 0 || inserted == null || inserted.isEmpty()) {
            return encodeResponse(
                SyncTextInputProtocol.STATUS_NOT_HANDLED, 0, -1, -1, -1, -1, 0, false, null);
          }
          final String oldText = text;
          final int a = validSelStart();
          final int b = validSelEnd();
          adjustComposingAfterReplace(oldText.length(), a, b, inserted.length());
          text = oldText.substring(0, a) + inserted + oldText.substring(b);
          selStart = a + inserted.length();
          selEnd = selStart;
          return finalizeMutation(!text.equals(oldText), true, null);
        }
      case SyncTextInputProtocol.OP_SELECT_ALL:
        return applySetSelection(0, text.length());
      case SyncTextInputProtocol.OP_COPY:
        {
          if (selStart < 0 || selEnd < 0 || selStart == selEnd) {
            return encodeStateResponse(0, 0, true, null);
          }
          return encodeStateResponse(0, 0, true, text.substring(validSelStart(), validSelEnd()));
        }
      case SyncTextInputProtocol.OP_CUT:
        {
          if (selStart < 0 || selEnd < 0 || selStart == selEnd) {
            return encodeStateResponse(SyncTextInputProtocol.RESP_FLAG_COMMITTED, 0, true, null);
          }
          final int a = validSelStart();
          final int b = validSelEnd();
          final String cut = text.substring(a, b);
          adjustComposingAfterReplace(text.length(), a, b, 0);
          text = text.substring(0, a) + text.substring(b);
          selStart = a;
          selEnd = a;
          return finalizeMutation(true, true, cut);
        }
      case SyncTextInputProtocol.OP_PASTE:
        {
          final String pasted = readUtf16String(view);
          final String safe = pasted != null ? pasted : "";
          final int a = selStart >= 0 ? validSelStart() : 0;
          final int b = selEnd >= 0 ? validSelEnd() : 0;
          final String oldText = text;
          adjustComposingAfterReplace(oldText.length(), a, b, safe.length());
          text = oldText.substring(0, a) + safe + oldText.substring(b);
          selStart = a + safe.length();
          selEnd = selStart;
          return finalizeMutation(!text.equals(oldText), true, null);
        }
      case SyncTextInputProtocol.OP_AUTOFILL_CURRENT_CLIENT:
        {
          final String filled = readUtf16String(view);
          final String safe = filled != null ? filled : "";
          final String oldText = text;
          text = safe;
          selStart = safe.length();
          selEnd = safe.length();
          compStart = -1;
          compEnd = -1;
          return finalizeMutation(!text.equals(oldText), true, null);
        }
      case SyncTextInputProtocol.OP_MOVE_CURSOR:
        {
          final int dir = view.getInt();
          final boolean extend = view.getInt() != 0;
          if (selStart < 0 || selEnd < 0) {
            return encodeResponse(
                SyncTextInputProtocol.STATUS_NOT_HANDLED, 0, -1, -1, -1, -1, 0, false, null);
          }
          final int base = clamp(selStart, 0, text.length());
          final int extent = clamp(selEnd, 0, text.length());
          int newExtent = extent;
          if (dir == SyncTextInputProtocol.DIR_LEFT) {
            newExtent = Math.max(0, extent - 1);
          } else if (dir == SyncTextInputProtocol.DIR_RIGHT) {
            newExtent = Math.min(text.length(), extent + 1);
          } else if (dir == SyncTextInputProtocol.DIR_UP) {
            newExtent = verticalLineOffset(text, extent, true);
          } else if (dir == SyncTextInputProtocol.DIR_DOWN) {
            newExtent = verticalLineOffset(text, extent, false);
          }
          final boolean changed = (newExtent != selEnd) || (!extend && base != extent && newExtent != selStart);
          if (base == extent && !extend) {
            selStart = newExtent;
            selEnd = newExtent;
          } else {
            selStart = base;
            selEnd = newExtent;
          }
          return finalizeMutation(false, changed, null);
        }
      default:
        return encodeResponse(
            SyncTextInputProtocol.STATUS_NOT_HANDLED, 0, -1, -1, -1, -1, 0, false, null);
    }
  }

  private byte[] closeOrUnwindConnection() {
    if (batchDepth > 0) {
      batchDepth = 0;
      if (compStart >= 0 && compEnd > compStart) {
        compStart = -1;
        compEnd = -1;
        batchDirty = true;
      }
      return finishBatchCommit();
    }
    if (compStart >= 0 && compEnd > compStart) {
      compStart = -1;
      compEnd = -1;
      return buildCommittedResponse(false, true, null);
    }
    return encodeStateResponse(0, 0, false, null);
  }

  private void adjustComposingAfterReplace(
      int oldLen, int replaceStart, int replaceEnd, int replacementLen) {
    if (compStart < 0 || compEnd <= compStart) {
      compStart = -1;
      compEnd = -1;
      return;
    }
    final int cs = clamp(Math.min(compStart, compEnd), 0, oldLen);
    final int ce = clamp(Math.max(compStart, compEnd), 0, oldLen);
    if (cs == ce) {
      compStart = -1;
      compEnd = -1;
      return;
    }
    final int delta = replacementLen - (replaceEnd - replaceStart);
    if (replaceEnd <= cs) {
      compStart = cs + delta;
      compEnd = ce + delta;
      return;
    }
    if (replaceStart >= ce) {
      compStart = cs;
      compEnd = ce;
      return;
    }
    if (replaceStart <= cs && replaceEnd >= ce) {
      compStart = -1;
      compEnd = -1;
      return;
    }
    final int newCs =
        cs <= replaceStart ? cs : (cs < replaceEnd ? replaceStart + replacementLen : cs + delta);
    final int newCe =
        ce <= replaceStart ? ce : (ce < replaceEnd ? replaceStart : ce + delta);
    if (newCe <= newCs) {
      compStart = -1;
      compEnd = -1;
    } else {
      compStart = newCs;
      compEnd = newCe;
    }
  }

  private byte[] handleQueryInitialState() {
    closeOrUnwindConnection();
    final int len = text.length();
    final int a = selStart >= 0 ? validSelStart() : 0;
    final int b = selEnd >= 0 ? validSelEnd() : 0;
    final int subStart = Math.max(0, a - 2048);
    final int subEnd = Math.min(len, b + 2048);
    return encodeResponse(
        SyncTextInputProtocol.STATUS_OK,
        SyncTextInputProtocol.RESP_FLAG_HAS_TEXT,
        selStart,
        selEnd,
        compStart,
        compEnd,
        subStart,
        true,
        text.substring(subStart, subEnd));
  }

  private byte[] applyReplaceComposingOrSelection(
      @NonNull String inserted, int newCursorPosition, boolean composing) {
    final String oldText = text;
    final int oldSelStart = selStart;
    final int oldSelEnd = selEnd;
    final int oldCompStart = compStart;
    final int oldCompEnd = compEnd;

    int a;
    int b;
    if (compStart >= 0 && compEnd >= 0 && compStart != compEnd) {
      a = clamp(Math.min(compStart, compEnd), 0, oldText.length());
      b = clamp(Math.max(compStart, compEnd), 0, oldText.length());
    } else if (selStart >= 0 && selEnd >= 0) {
      a = clamp(Math.min(selStart, selEnd), 0, oldText.length());
      b = clamp(Math.max(selStart, selEnd), 0, oldText.length());
    } else {
      a = 0;
      b = 0;
    }

    text = oldText.substring(0, a) + inserted + oldText.substring(b);
    if (composing && !inserted.isEmpty()) {
      compStart = a;
      compEnd = a + inserted.length();
    } else {
      compStart = -1;
      compEnd = -1;
    }
    final int rawCursor =
        newCursorPosition > 0 ? a + inserted.length() + newCursorPosition - 1 : a + newCursorPosition;
    final int cursor = clamp(rawCursor, 0, text.length());
    selStart = cursor;
    selEnd = cursor;

    final boolean textChanged = !text.equals(oldText);
    final boolean stateChanged =
        textChanged
            || selStart != oldSelStart
            || selEnd != oldSelEnd
            || compStart != oldCompStart
            || compEnd != oldCompEnd;
    return finalizeMutation(textChanged, stateChanged, null);
  }

  private byte[] applySetComposingRegion(int start, int end) {
    final int oldCompStart = compStart;
    final int oldCompEnd = compEnd;
    final int a = clamp(Math.min(start, end), 0, text.length());
    final int b = clamp(Math.max(start, end), 0, text.length());
    if (a != b) {
      compStart = a;
      compEnd = b;
    } else {
      compStart = -1;
      compEnd = -1;
    }
    return finalizeMutation(false, compStart != oldCompStart || compEnd != oldCompEnd, null);
  }

  private byte[] applyFinishComposingText() {
    if (compStart < 0 && compEnd < 0) {
      return finalizeMutation(false, false, null);
    }
    compStart = -1;
    compEnd = -1;
    return finalizeMutation(false, true, null);
  }

  private byte[] applySetSelection(int start, int end) {
    if (start < 0 || end < 0 || start > text.length() || end > text.length()) {
      return finalizeMutation(false, false, null);
    }
    final boolean changed = start != selStart || end != selEnd;
    selStart = start;
    selEnd = end;
    return finalizeMutation(false, changed, null);
  }

  private byte[] applyReplaceText(int start, int end, @NonNull String inserted, int newCursorPosition) {
    if (start < 0 || end < 0) {
      return encodeResponse(
          SyncTextInputProtocol.STATUS_NOT_HANDLED, 0, -1, -1, -1, -1, 0, false, null);
    }
    final String oldText = text;
    final int a = clamp(Math.min(start, end), 0, oldText.length());
    final int b = clamp(Math.max(start, end), 0, oldText.length());
    text = oldText.substring(0, a) + inserted + oldText.substring(b);
    compStart = -1;
    compEnd = -1;
    final int rawCursor =
        newCursorPosition > 0 ? a + inserted.length() + newCursorPosition - 1 : a + newCursorPosition;
    final int cursor = clamp(rawCursor, 0, text.length());
    selStart = cursor;
    selEnd = cursor;
    return finalizeMutation(!text.equals(oldText), true, null);
  }

  private byte[] applyDeleteSurroundingText(int beforeLength, int afterLength) {
    if (selStart < 0 || selEnd < 0) {
      return finalizeMutation(false, false, null);
    }
    int a = validSelStart();
    int b = validSelEnd();
    if (compStart >= 0 && compEnd > compStart) {
      final int ca = clamp(Math.min(compStart, compEnd), 0, text.length());
      final int cb = clamp(Math.max(compStart, compEnd), 0, text.length());
      if (ca < a) a = ca;
      if (cb > b) b = cb;
    }
    final int beforeStart = Math.max(0, a - Math.max(0, beforeLength));
    final int afterEnd = Math.min(text.length(), b + Math.max(0, afterLength));
    return deleteRanges(beforeStart, a, b, afterEnd);
  }

  private byte[] applyDeleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
    if (selStart < 0 || selEnd < 0) {
      return finalizeMutation(false, false, null);
    }
    int a = validSelStart();
    int b = validSelEnd();
    if (compStart >= 0 && compEnd > compStart) {
      final int ca = clamp(Math.min(compStart, compEnd), 0, text.length());
      final int cb = clamp(Math.max(compStart, compEnd), 0, text.length());
      if (ca < a) a = ca;
      if (cb > b) b = cb;
    }
    int pos = a;
    int rem = Math.max(0, beforeLength);
    while (rem > 0 && pos > 0) {
      final char c = text.charAt(pos - 1);
      if (Character.isLowSurrogate(c)) {
        if (pos - 2 >= 0 && Character.isHighSurrogate(text.charAt(pos - 2))) {
          pos -= 2;
          rem--;
        } else {
          return finalizeMutation(false, false, null);
        }
      } else if (Character.isHighSurrogate(c)) {
        return finalizeMutation(false, false, null);
      } else {
        pos--;
        rem--;
      }
    }
    final int beforeStart = pos;

    pos = b;
    rem = Math.max(0, afterLength);
    while (rem > 0 && pos < text.length()) {
      final char c = text.charAt(pos);
      if (Character.isHighSurrogate(c)) {
        if (pos + 1 < text.length() && Character.isLowSurrogate(text.charAt(pos + 1))) {
          pos += 2;
          rem--;
        } else {
          return finalizeMutation(false, false, null);
        }
      } else if (Character.isLowSurrogate(c)) {
        return finalizeMutation(false, false, null);
      } else {
        pos++;
        rem--;
      }
    }
    final int afterEnd = pos;
    return deleteRanges(beforeStart, a, b, afterEnd);
  }

  private byte[] deleteRanges(int beforeStart, int beforeEnd, int afterStart, int afterEnd) {
    final boolean hasBefore = beforeEnd > beforeStart;
    final boolean hasAfter = afterEnd > afterStart;
    if (!hasBefore && !hasAfter) {
      return finalizeMutation(false, false, null);
    }
    if (hasAfter) {
      text = text.substring(0, afterStart) + text.substring(afterEnd);
    }
    if (hasBefore) {
      final int deleted = beforeEnd - beforeStart;
      text = text.substring(0, beforeStart) + text.substring(beforeEnd);
      selStart -= deleted;
      selEnd -= deleted;
      if (compStart >= 0 && compEnd > compStart) {
        compStart -= deleted;
        compEnd -= deleted;
      } else {
        compStart = -1;
        compEnd = -1;
      }
    }
    return finalizeMutation(true, true, null);
  }

  private byte[] finalizeMutation(boolean textChanged, boolean stateChanged, @Nullable String extracted) {
    if (batchDepth > 0) {
      batchTextChanged |= textChanged;
      batchDirty |= stateChanged;
      return encodeStateResponse(
          SyncTextInputProtocol.RESP_FLAG_BATCH_ACTIVE, 0, extracted != null, extracted);
    }
    return buildCommittedResponse(textChanged, stateChanged, extracted);
  }

  private byte[] finishBatchCommit() {
    final boolean dirty = batchDirty;
    final boolean textChanged = batchTextChanged;
    resetBatch();
    return buildCommittedResponse(textChanged, dirty, null);
  }

  private byte[] buildCommittedResponse(
      boolean textChanged, boolean stateChanged, @Nullable String extracted) {
    if (nextPostCommitTextOverride != null) {
      text = nextPostCommitTextOverride;
      nextPostCommitTextOverride = null;
    }
    if (nextPostCommitSelStart != Integer.MIN_VALUE) {
      selStart = nextPostCommitSelStart;
      selEnd = nextPostCommitSelEnd;
      compStart = nextPostCommitCompStart;
      compEnd = nextPostCommitCompEnd;
      nextPostCommitSelStart = Integer.MIN_VALUE;
    }

    int flags = SyncTextInputProtocol.RESP_FLAG_COMMITTED;
    if (stateChanged) {
      flags |= SyncTextInputProtocol.RESP_FLAG_STATE_CHANGED;
    }
    if (textChanged) {
      flags |= SyncTextInputProtocol.RESP_FLAG_TEXT_CHANGED;
    }
    if (forceNextDiverged) {
      flags |= SyncTextInputProtocol.RESP_FLAG_DIVERGED;
      forceNextDiverged = false;
    }
    if (forceNextComposingChangedByFramework) {
      flags |= SyncTextInputProtocol.RESP_FLAG_COMPOSING_CHANGED_BY_FRAMEWORK;
      forceNextComposingChangedByFramework = false;
    }
    if (forceNextClientChanged) {
      flags |= SyncTextInputProtocol.RESP_FLAG_CLIENT_CHANGED;
      forceNextClientChanged = false;
    }
    return encodeStateResponse(flags, 0, extracted != null, extracted);
  }

  private int validSelStart() {
    return clamp(Math.min(selStart, selEnd), 0, text.length());
  }

  private int validSelEnd() {
    return clamp(Math.max(selStart, selEnd), 0, text.length());
  }

  private static int clamp(int v, int min, int max) {
    return Math.max(min, Math.min(max, v));
  }

  private static int verticalLineOffset(@NonNull String s, int offset, boolean up) {
    final int clamped = clamp(offset, 0, s.length());
    final int prevNl = clamped == 0 ? -1 : s.lastIndexOf('\n', clamped - 1);
    final int curStart = prevNl + 1;
    final int col = clamped - curStart;
    if (up) {
      if (curStart == 0) {
        return 0;
      }
      final int prevEnd = curStart - 1;
      final int prevPrevNl = prevEnd == 0 ? -1 : s.lastIndexOf('\n', prevEnd - 1);
      return Math.min(prevPrevNl + 1 + col, prevEnd);
    } else {
      final int nextNl = s.indexOf('\n', clamped);
      if (nextNl == -1) {
        return s.length();
      }
      final int nextStart = nextNl + 1;
      final int nextEndRaw = s.indexOf('\n', nextStart);
      final int nextEnd = nextEndRaw == -1 ? s.length() : nextEndRaw;
      return Math.min(nextStart + col, nextEnd);
    }
  }

  /**
   * Direct mirror of {@code AndroidInputConnectionSemantics.getCursorCapsMode} for differential
   * verification against Android's real {@link android.text.TextUtils#getCapsMode}.
   */
  static int computeCapsMode(@NonNull String text, int off, int reqModes) {
    if (off < 0 || reqModes == 0) {
      return 0;
    }
    final int clampedOff = clamp(off, 0, text.length());
    final int capChars = 0x1000;
    final int capWords = 0x2000;
    final int capSentences = 0x4000;

    int mode = 0;
    if ((reqModes & capChars) != 0) {
      mode |= capChars;
    }
    if ((reqModes & (capWords | capSentences)) == 0) {
      return mode;
    }

    int i = clampedOff;
    while (i > 0) {
      final char c = text.charAt(i - 1);
      if (c != '"' && c != '\'' && !isStartPunctuation(c)) {
        break;
      }
      i--;
    }

    int j = i;
    while (j > 0) {
      final char c = text.charAt(j - 1);
      if (c != ' ' && c != '\t') {
        break;
      }
      j--;
    }
    if (j == 0 || text.charAt(j - 1) == '\n') {
      return mode | capWords;
    }

    if ((reqModes & capSentences) == 0) {
      if (i != j) {
        mode |= capWords;
      }
      return mode;
    }

    if (i == j) {
      return mode;
    }

    while (j > 0) {
      final char c = text.charAt(j - 1);
      if (c != '"' && c != '\'' && !isEndPunctuation(c)) {
        break;
      }
      j--;
    }

    if (j > 0) {
      final char c = text.charAt(j - 1);
      if (c == '.' || c == '?' || c == '!') {
        if (c == '.') {
          for (int k = j - 2; k >= 0; k--) {
            final char prev = text.charAt(k);
            if (prev == '.') {
              return mode;
            }
            if (!isLetter(prev)) {
              break;
            }
          }
        }
        return mode | capSentences;
      }
    }
    return mode;
  }

  private static boolean isStartPunctuation(char c) {
    return c == '('
        || c == '['
        || c == '{'
        || c == '\u201A'
        || c == '\u201E'
        || c == '\u3008'
        || c == '\u300A'
        || c == '\u300C'
        || c == '\u300E'
        || c == '\u3010'
        || c == '\uFF08'
        || c == '\uFF3B'
        || c == '\uFF5B';
  }

  private static boolean isEndPunctuation(char c) {
    return c == ')'
        || c == ']'
        || c == '}'
        || c == '\u3009'
        || c == '\u300B'
        || c == '\u300D'
        || c == '\u300F'
        || c == '\u3011'
        || c == '\uFF09'
        || c == '\uFF3D'
        || c == '\uFF5D';
  }

  private static boolean isLetter(char c) {
    if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
      return true;
    }
    if (c >= 0x00C0 && c <= 0x02AF && c != 0x00D7 && c != 0x00F7) {
      return true;
    }
    if ((c >= 0x0370 && c <= 0x0373)
        || (c >= 0x0376 && c <= 0x0377)
        || (c >= 0x037B && c <= 0x037D)
        || c == 0x037F
        || c == 0x0386
        || (c >= 0x0388 && c <= 0x038A)
        || c == 0x038C
        || (c >= 0x038E && c <= 0x03A1)
        || (c >= 0x03A3 && c <= 0x03F5)
        || (c >= 0x03F7 && c <= 0x03FF)) {
      return true;
    }
    if ((c >= 0x0400 && c <= 0x0481) || (c >= 0x048A && c <= 0x052F)) {
      return true;
    }
    return (c >= 0x0531 && c <= 0x0556)
        || (c >= 0x0560 && c <= 0x0588)
        || (c >= 0x05D0 && c <= 0x05EA)
        || (c >= 0x0620 && c <= 0x064A)
        || (c >= 0x066E && c <= 0x06D3)
        || (c >= 0x0904 && c <= 0x0939)
        || (c >= 0x1E00 && c <= 0x1EFF)
        || (c >= 0x1F00 && c <= 0x1FFF)
        || (c >= 0x3041 && c <= 0x3096)
        || (c >= 0x30A1 && c <= 0x30FA)
        || (c >= 0x3400 && c <= 0x4DBF)
        || (c >= 0x4E00 && c <= 0x9FFF)
        || (c >= 0xAC00 && c <= 0xD7A3);
  }

  @Nullable
  private static String readUtf16String(@NonNull ByteBuffer buf) {
    final int len = buf.getInt();
    if (len < 0) {
      return null;
    }
    final char[] chars = new char[len];
    for (int i = 0; i < len; i++) {
      chars[i] = buf.getChar();
    }
    return new String(chars);
  }

  private byte[] encodeStateResponse(
      int flags, int intResult, boolean includeText, @Nullable String textPayload) {
    return encodeResponse(
        SyncTextInputProtocol.STATUS_OK,
        flags,
        selStart,
        selEnd,
        compStart,
        compEnd,
        intResult,
        includeText,
        textPayload);
  }

  static byte[] encodeResponse(
      int status,
      int flags,
      int selStart,
      int selEnd,
      int compStart,
      int compEnd,
      int intResult,
      boolean includeText,
      @Nullable String textPayload) {
    int finalFlags = flags;
    int extraBytes = 0;
    if (includeText) {
      finalFlags |= SyncTextInputProtocol.RESP_FLAG_HAS_TEXT;
      extraBytes = 4 + (textPayload != null ? textPayload.length() * 2 : 0);
    }
    final byte[] out = new byte[SyncTextInputProtocol.RESPONSE_HEADER_BYTES + extraBytes];
    final ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
    buf.putInt(status);
    buf.putInt(finalFlags);
    buf.putInt(selStart);
    buf.putInt(selEnd);
    buf.putInt(compStart);
    buf.putInt(compEnd);
    buf.putInt(intResult);
    if (includeText) {
      if (textPayload == null) {
        buf.putInt(-1);
      } else {
        buf.putInt(textPayload.length());
        for (int i = 0; i < textPayload.length(); i++) {
          buf.putChar(textPayload.charAt(i));
        }
      }
    }
    return out;
  }
}
