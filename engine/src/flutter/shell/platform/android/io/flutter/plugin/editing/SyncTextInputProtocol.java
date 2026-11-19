// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugin.editing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Little-endian binary wire protocol shared between Android's {@link FlutterInputConnection} /
 * {@link TextInputPlugin} and Dart's {@code _android_sync_text_input.dart}.
 *
 * <p>Strings are encoded as raw UTF-16 code units ({@code int32 length}, where {@code -1} denotes
 * {@code null}, followed by {@code length * 2} little-endian bytes) to avoid UTF-8 transcoding
 * overhead and preserve unpaired surrogates identically across Java and Dart.
 */
final class SyncTextInputProtocol {
  private SyncTextInputProtocol() {}

  // Must match kMaxSyncImePayloadBytes in android_sync_text_input_bridge.h.
  static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
  static final int REQUEST_HEADER_BYTES = 8;
  static final int RESPONSE_HEADER_BYTES = 28;

  // Request opcodes (must stay in lockstep with _android_sync_text_input.dart).
  static final int OP_QUERY_INITIAL_STATE = 1;
  static final int OP_BEGIN_BATCH_EDIT = 2;
  static final int OP_END_BATCH_EDIT = 3;
  static final int OP_COMMIT_TEXT = 4;
  static final int OP_SET_COMPOSING_TEXT = 5;
  static final int OP_SET_COMPOSING_REGION = 6;
  static final int OP_FINISH_COMPOSING_TEXT = 7;
  static final int OP_DELETE_SURROUNDING_TEXT = 8;
  static final int OP_DELETE_SURROUNDING_TEXT_IN_CODE_POINTS = 9;
  static final int OP_SET_SELECTION = 10;
  static final int OP_REPLACE_TEXT = 11;
  static final int OP_GET_TEXT_BEFORE_CURSOR = 12;
  static final int OP_GET_TEXT_AFTER_CURSOR = 13;
  static final int OP_GET_SELECTED_TEXT = 14;
  static final int OP_GET_SURROUNDING_TEXT = 15;
  static final int OP_GET_CURSOR_CAPS_MODE = 16;
  static final int OP_GET_EXTRACTED_TEXT = 17;
  static final int OP_GET_FULL_STATE = 18;
  static final int OP_CLOSE_CONNECTION = 19;
  static final int OP_MOVE_CURSOR = 20;
  static final int OP_CUT = 21;
  static final int OP_COPY = 22;
  static final int OP_PASTE = 23;
  static final int OP_SELECT_ALL = 24;
  static final int OP_AUTOFILL_CURRENT_CLIENT = 25;
  static final int OP_INSERT_KEY_CHARACTER = 26;
  static final int OP_GET_CURSOR_ANCHOR_STATE = 27;

  // Response status codes.
  static final int STATUS_OK = 1;
  static final int STATUS_STALE_CLIENT = 2;
  static final int STATUS_NOT_HANDLED = 3;
  static final int STATUS_ERROR = 4;

  // Response flag bits.
  static final int RESP_FLAG_COMMITTED = 1 << 0;
  static final int RESP_FLAG_STATE_CHANGED = 1 << 1;
  static final int RESP_FLAG_TEXT_CHANGED = 1 << 2;
  static final int RESP_FLAG_DIVERGED = 1 << 3;
  static final int RESP_FLAG_COMPOSING_CHANGED_BY_FRAMEWORK = 1 << 4;
  static final int RESP_FLAG_CLIENT_CHANGED = 1 << 5;
  static final int RESP_FLAG_BATCH_ACTIVE = 1 << 6;
  static final int RESP_FLAG_HAS_TEXT = 1 << 7;

  // Dart -> Android programmatic notification flag bits.
  static final int NOTIFY_FLAG_TEXT_CHANGED = 1 << 0;
  static final int NOTIFY_FLAG_COMPOSING_CHANGED = 1 << 1;

  // Cursor movement directions for OP_MOVE_CURSOR.
  static final int DIR_LEFT = 0;
  static final int DIR_RIGHT = 1;
  static final int DIR_UP = 2;
  static final int DIR_DOWN = 3;

  /**
   * Reentrancy-safe LIFO pool of small direct request buffers owned by a single {@link
   * TextInputPlugin} on the UI thread. Nested IME callbacks pop separate slots so inner requests
   * never overwrite an outer request in flight.
   */
  static final class DirectBufferPool {
    private static final int POOL_CAPACITY = 4;
    private static final int DEFAULT_BUFFER_BYTES = 512;
    private static final int MAX_POOLED_BUFFER_BYTES = 4096;

    private final ByteBuffer[] slots = new ByteBuffer[POOL_CAPACITY];
    private int count = 0;

    @NonNull
    ByteBuffer acquire(int minBytes) {
      final int needed = Math.max(minBytes, REQUEST_HEADER_BYTES);
      if (count > 0 && needed <= MAX_POOLED_BUFFER_BYTES) {
        for (int i = count - 1; i >= 0; i--) {
          if (slots[i].capacity() >= needed) {
            final ByteBuffer candidate = slots[i];
            System.arraycopy(slots, i + 1, slots, i, count - 1 - i);
            slots[--count] = null;
            candidate.clear();
            candidate.order(ByteOrder.LITTLE_ENDIAN);
            return candidate;
          }
        }
      }
      final int allocBytes =
          needed <= DEFAULT_BUFFER_BYTES ? DEFAULT_BUFFER_BYTES : needed;
      return ByteBuffer.allocateDirect(allocBytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    void release(@NonNull ByteBuffer buffer) {
      if (count < POOL_CAPACITY && buffer.capacity() <= MAX_POOLED_BUFFER_BYTES) {
        buffer.clear();
        slots[count++] = buffer;
      }
    }
  }

  /** Decoded synchronous response frame from Dart. */
  static final class Response {
    final int status;
    final int flags;
    final int selectionStart;
    final int selectionEnd;
    final int composingStart;
    final int composingEnd;
    final int intResult;
    @Nullable final String text;

    Response(
        int status,
        int flags,
        int selectionStart,
        int selectionEnd,
        int composingStart,
        int composingEnd,
        int intResult,
        @Nullable String text) {
      this.status = status;
      this.flags = flags;
      this.selectionStart = selectionStart;
      this.selectionEnd = selectionEnd;
      this.composingStart = composingStart;
      this.composingEnd = composingEnd;
      this.intResult = intResult;
      this.text = text;
    }

    boolean isOk() {
      return status == STATUS_OK;
    }

    boolean isCommitted() {
      return (flags & RESP_FLAG_COMMITTED) != 0;
    }

    boolean isStateChanged() {
      return (flags & RESP_FLAG_STATE_CHANGED) != 0;
    }

    boolean isTextChanged() {
      return (flags & RESP_FLAG_TEXT_CHANGED) != 0;
    }

    boolean isDiverged() {
      return (flags & RESP_FLAG_DIVERGED) != 0;
    }

    boolean isComposingChangedByFramework() {
      return (flags & RESP_FLAG_COMPOSING_CHANGED_BY_FRAMEWORK) != 0;
    }

    boolean isClientChanged() {
      return (flags & RESP_FLAG_CLIENT_CHANGED) != 0;
    }

    boolean isBatchActive() {
      return (flags & RESP_FLAG_BATCH_ACTIVE) != 0;
    }

    boolean hasText() {
      return (flags & RESP_FLAG_HAS_TEXT) != 0;
    }
  }

  static int encodedStringBytes(@Nullable CharSequence text) {
    if (text == null) {
      return 4;
    }
    final int len = text.length();
    if (len > (MAX_PAYLOAD_BYTES - 32) / 2) {
      return -1;
    }
    return 4 + len * 2;
  }

  static void putHeader(@NonNull ByteBuffer buf, int opcode, int clientId) {
    buf.putInt(opcode);
    buf.putInt(clientId);
  }

  static void putUtf16String(@NonNull ByteBuffer buf, @Nullable CharSequence text) {
    if (text == null) {
      buf.putInt(-1);
      return;
    }
    final int len = text.length();
    buf.putInt(len);
    for (int i = 0; i < len; i++) {
      buf.putChar(text.charAt(i));
    }
  }

  @Nullable
  static Response decodeResponse(@Nullable byte[] raw) {
    if (raw == null || raw.length < RESPONSE_HEADER_BYTES || raw.length > MAX_PAYLOAD_BYTES) {
      return null;
    }
    final ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
    final int status = buf.getInt();
    final int flags = buf.getInt();
    final int selectionStart = buf.getInt();
    final int selectionEnd = buf.getInt();
    final int composingStart = buf.getInt();
    final int composingEnd = buf.getInt();
    final int intResult = buf.getInt();

    String text = null;
    if ((flags & RESP_FLAG_HAS_TEXT) != 0) {
      if (buf.remaining() < 4) {
        return null;
      }
      final int charLen = buf.getInt();
      if (charLen == -1) {
        text = null;
      } else if (charLen < 0 || (long) charLen * 2L > buf.remaining()) {
        return null;
      } else if (charLen == 0) {
        text = "";
      } else {
        final char[] chars = new char[charLen];
        for (int i = 0; i < charLen; i++) {
          chars[i] = buf.getChar();
        }
        text = new String(chars);
      }
    }

    return new Response(
        status, flags, selectionStart, selectionEnd, composingStart, composingEnd, intResult, text);
  }
}
