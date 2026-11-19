// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugin.editing;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
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
import android.view.inputmethod.TextBoundsInfoResult;
import android.view.inputmethod.TextSnapshot;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.flutter.embedding.engine.systemchannels.TextInputChannel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowInputMethodManager;

@Config(shadows = {FlutterInputConnectionTest.RecordingImm.class})
@RunWith(AndroidJUnit4.class)
public class FlutterInputConnectionTest {
  private final Context ctx = ApplicationProvider.getApplicationContext();
  private RecordingImm imm;
  private View view;
  private TextInputChannel textInputChannel;
  private InputConnectionAdaptor.KeyboardDelegate keyboardDelegate;
  private FakeSyncTextInputBridge fakeBridge;
  private TestHost host;

  private static final int CLIENT_ID = 42;
  private static final int ENGINE_GEN = 1;

  @Before
  public void setUp() {
    imm = Shadow.extract(ctx.getSystemService(Context.INPUT_METHOD_SERVICE));
    imm.resetRecording();
    view = new View(ctx);
    textInputChannel = mock(TextInputChannel.class);
    keyboardDelegate = mock(InputConnectionAdaptor.KeyboardDelegate.class);
    fakeBridge = new FakeSyncTextInputBridge();
    fakeBridge.setClientState(CLIENT_ID, "", 0, 0, -1, -1);
    host = new TestHost(fakeBridge);
  }

  private FlutterInputConnection createConnection(EditorInfo editorInfo, boolean obscureText) {
    final FlutterInputConnection conn =
        new FlutterInputConnection(
            view,
            CLIENT_ID,
            ENGINE_GEN,
            textInputChannel,
            keyboardDelegate,
            editorInfo,
            obscureText,
            host);
    host.activeConnection = conn;
    return conn;
  }

  private FlutterInputConnection createDefaultConnection() {
    final EditorInfo info = new EditorInfo();
    info.inputType = InputType.TYPE_CLASS_TEXT;
    info.imeOptions = EditorInfo.IME_ACTION_DONE;
    return createConnection(info, false);
  }

  private BaseInputConnection createReferenceConnection(
      SpannableStringBuilder editable, int selStart, int selEnd, int compStart, int compEnd) {
    Selection.setSelection(editable, selStart, selEnd);
    final BaseInputConnection base =
        new BaseInputConnection(view, true) {
          @Override
          public Editable getEditable() {
            return editable;
          }
        };
    if (compStart >= 0 && compEnd > compStart) {
      base.setComposingRegion(compStart, compEnd);
    }
    return base;
  }

  private void assertMatchesEditable(SpannableStringBuilder expected) {
    assertEquals(expected.toString(), fakeBridge.text);
    assertEquals(Selection.getSelectionStart(expected), fakeBridge.selStart);
    assertEquals(Selection.getSelectionEnd(expected), fakeBridge.selEnd);
    assertEquals(BaseInputConnection.getComposingSpanStart(expected), fakeBridge.compStart);
    assertEquals(BaseInputConnection.getComposingSpanEnd(expected), fakeBridge.compEnd);
  }

  // ---- Reflection Completeness ----

  @Test
  public void implementsEveryInputConnectionMethodExplicitly() throws NoSuchMethodException {
    final Method[] interfaceMethods = InputConnection.class.getMethods();
    assertTrue("Expected SDK InputConnection methods", interfaceMethods.length >= 30);
    for (Method m : interfaceMethods) {
      final Method declared =
          FlutterInputConnection.class.getDeclaredMethod(m.getName(), m.getParameterTypes());
      assertNotNull(
          "FlutterInputConnection must explicitly declare " + m.getName(), declared);
    }
  }

  // ---- Differential Tests vs Real Android BaseInputConnection & TextUtils ----

  @Test
  public void differential_commitAndComposingOperations() {
    final SpannableStringBuilder refEditable = new SpannableStringBuilder("hello world");
    final BaseInputConnection ref = createReferenceConnection(refEditable, 5, 5, -1, -1);
    fakeBridge.setClientState(CLIENT_ID, "hello world", 5, 5, -1, -1);
    final FlutterInputConnection dut = createDefaultConnection();

    // Commit at cursor with newCursorPosition = 1.
    assertEquals(ref.commitText(",", 1), dut.commitText(",", 1));
    assertMatchesEditable(refEditable);

    // Set composing text, replace it, then clear with empty string.
    assertEquals(ref.setComposingText(" foo", 1), dut.setComposingText(" foo", 1));
    assertMatchesEditable(refEditable);

    assertEquals(ref.setComposingText(" bar", 1), dut.setComposingText(" bar", 1));
    assertMatchesEditable(refEditable);

    assertEquals(ref.setComposingText("", 1), dut.setComposingText("", 1));
    assertMatchesEditable(refEditable);

    // Set composing region (reversed + clamped), then finish composing.
    assertEquals(ref.setComposingRegion(5, 0), dut.setComposingRegion(5, 0));
    assertMatchesEditable(refEditable);

    assertEquals(ref.finishComposingText(), dut.finishComposingText());
    assertMatchesEditable(refEditable);

    // Commit with negative cursor position and zero cursor position.
    assertEquals(ref.commitText("XYZ", 0), dut.commitText("XYZ", 0));
    assertMatchesEditable(refEditable);

    assertEquals(ref.commitText("AB", -2), dut.commitText("AB", -2));
    assertMatchesEditable(refEditable);
  }

  @Test
  public void differential_deleteSurroundingTextPreservesComposing() {
    final SpannableStringBuilder refEditable = new SpannableStringBuilder("abcdefghi");
    final BaseInputConnection ref = createReferenceConnection(refEditable, 4, 5, 3, 6);
    fakeBridge.setClientState(CLIENT_ID, "abcdefghi", 4, 5, 3, 6);
    final FlutterInputConnection dut = createDefaultConnection();

    // Delete 2 chars before and 2 chars after: composing [3,6) ("def") must stay intact.
    assertEquals(ref.deleteSurroundingText(2, 2), dut.deleteSurroundingText(2, 2));
    assertMatchesEditable(refEditable);
    assertEquals("adefi", fakeBridge.text);
  }

  @Test
  public void differential_deleteSurroundingTextInCodePointsAndUnpairedSurrogates() {
    final String emojiText = "a\uD83D\uDE00b\uD83D\uDE80c"; // a😀b🚀c
    final SpannableStringBuilder refEditable = new SpannableStringBuilder(emojiText);
    final BaseInputConnection ref = createReferenceConnection(refEditable, 3, 4, -1, -1); // "b"
    fakeBridge.setClientState(CLIENT_ID, emojiText, 3, 4, -1, -1);
    final FlutterInputConnection dut = createDefaultConnection();

    assertEquals(
        ref.deleteSurroundingTextInCodePoints(1, 1), dut.deleteSurroundingTextInCodePoints(1, 1));
    assertMatchesEditable(refEditable);
    assertEquals("abc", fakeBridge.text);

    // Unpaired high surrogate before cursor matches AOSP BaseInputConnection: aborts edit without
    // mutating text and returns true (connection remains valid).
    final String unpaired = "a\uD83Db";
    final SpannableStringBuilder refUnpaired = new SpannableStringBuilder(unpaired);
    final BaseInputConnection ref2 = createReferenceConnection(refUnpaired, 2, 2, -1, -1);
    fakeBridge.setClientState(CLIENT_ID, unpaired, 2, 2, -1, -1);
    assertEquals(
        ref2.deleteSurroundingTextInCodePoints(1, 0),
        dut.deleteSurroundingTextInCodePoints(1, 0));
    assertTrue(dut.deleteSurroundingTextInCodePoints(1, 0));
    assertMatchesEditable(refUnpaired);
    assertEquals(unpaired, fakeBridge.text);
  }

  @Test
  public void differential_selectionReplaceAndReadQueries() {
    final SpannableStringBuilder refEditable = new SpannableStringBuilder("hello flutter world");
    final BaseInputConnection ref = createReferenceConnection(refEditable, 6, 13, -1, -1);
    fakeBridge.setClientState(CLIENT_ID, "hello flutter world", 6, 13, -1, -1);
    final FlutterInputConnection dut = createDefaultConnection();

    assertEquals(ref.getSelectedText(0).toString(), dut.getSelectedText(0).toString());
    assertEquals(
        ref.getTextBeforeCursor(4, 0).toString(), dut.getTextBeforeCursor(4, 0).toString());
    assertEquals(
        ref.getTextAfterCursor(4, 0).toString(), dut.getTextAfterCursor(4, 0).toString());
    assertNull(dut.getTextBeforeCursor(-1, 0));
    assertNull(dut.getTextAfterCursor(-1, 0));

    // SurroundingText (normal and negative args).
    final SurroundingText refSurr = ref.getSurroundingText(3, 3, 0);
    final SurroundingText dutSurr = dut.getSurroundingText(3, 3, 0);
    assertNotNull(refSurr);
    assertNotNull(dutSurr);
    assertEquals(refSurr.getText().toString(), dutSurr.getText().toString());
    assertEquals(refSurr.getSelectionStart(), dutSurr.getSelectionStart());
    assertEquals(refSurr.getSelectionEnd(), dutSurr.getSelectionEnd());
    assertEquals(refSurr.getOffset(), dutSurr.getOffset());
    assertNull(dut.getSurroundingText(-1, 3, 0));
    assertNull(dut.getSurroundingText(3, -1, 0));

    // Out-of-bounds setSelection leaves state untouched.
    dut.setSelection(-1, 5);
    assertEquals(6, fakeBridge.selStart);
    assertEquals(13, fakeBridge.selEnd);
    dut.setSelection(0, 999);
    assertEquals(6, fakeBridge.selStart);
    assertEquals(13, fakeBridge.selEnd);

    // Valid setSelection + replaceText (API 34).
    assertEquals(ref.setSelection(13, 6), dut.setSelection(13, 6));
    assertMatchesEditable(refEditable);

    assertEquals(
        ref.replaceText(6, 13, "android", 1, null), dut.replaceText(6, 13, "android", 1, null));
    assertMatchesEditable(refEditable);
  }

  @Test
  public void differential_getCursorCapsModeMatchesAndroidTextUtils() {
    final String[] texts = {
      "",
      "hello",
      "hello ",
      "hello. ",
      "hello! ",
      "hello? ",
      "hello.\n",
      "hello.\n  ",
      "hello. \"",
      "hello. (",
      "hello.\" ",
      "e.g. ",
      "Dr. ",
      "a.b. ",
      "line1\nline2",
      "  \"",
      "word\t",
      // Non-Latin scripts and Unicode Pi/Pf quote characters (must match Android TextUtils).
      "т.е. ",
      "Привет. ",
      "π.χ. ",
      "hello. «",
      "hello. \u201C",
      "hello.\u201D ",
      "hello.» ",
    };
    final int[] reqModes = {
      0,
      TextUtils.CAP_MODE_CHARACTERS,
      TextUtils.CAP_MODE_WORDS,
      TextUtils.CAP_MODE_SENTENCES,
      TextUtils.CAP_MODE_CHARACTERS | TextUtils.CAP_MODE_WORDS,
      TextUtils.CAP_MODE_WORDS | TextUtils.CAP_MODE_SENTENCES,
      TextUtils.CAP_MODE_CHARACTERS | TextUtils.CAP_MODE_WORDS | TextUtils.CAP_MODE_SENTENCES,
    };

    final FlutterInputConnection dut = createDefaultConnection();
    for (String text : texts) {
      for (int off = 0; off <= text.length(); off++) {
        fakeBridge.setClientState(CLIENT_ID, text, off, off, -1, -1);
        for (int req : reqModes) {
          final int expected = TextUtils.getCapsMode(text, off, req);
          final int actual = dut.getCursorCapsMode(req);
          assertEquals(
              "Mismatch for text=\"" + text + "\" off=" + off + " req=" + req, expected, actual);
        }
      }
    }
  }

  @Test
  public void takeSnapshot_populatesSurroundingComposingAndCapsMode() {
    fakeBridge.setClientState(CLIENT_ID, "Hello. world", 12, 12, 7, 12);
    final FlutterInputConnection dut = createDefaultConnection();

    final TextSnapshot snap = dut.takeSnapshot();
    assertNotNull(snap);
    assertEquals("Hello. world", snap.getSurroundingText().getText().toString());
    assertEquals(7, snap.getCompositionStart());
    assertEquals(12, snap.getCompositionEnd());
    assertEquals(
        TextUtils.getCapsMode(
            "Hello. world",
            12,
            TextUtils.CAP_MODE_CHARACTERS
                | TextUtils.CAP_MODE_WORDS
                | TextUtils.CAP_MODE_SENTENCES),
        snap.getCursorCapsMode());

    // Empty composing region normalizes to (-1, -1).
    fakeBridge.setClientState(CLIENT_ID, "Hello. ", 7, 7, -1, -1);
    final TextSnapshot snap2 = dut.takeSnapshot();
    assertNotNull(snap2);
    assertEquals(-1, snap2.getCompositionStart());
    assertEquals(-1, snap2.getCompositionEnd());
  }

  // ---- Monitoring & Context Menu & Clipboard ----

  @Test
  public void extractedTextAndCursorUpdates_monitoringTogglesAndSends() {
    fakeBridge.setClientState(CLIENT_ID, "abc", 1, 3, 0, 3);
    final FlutterInputConnection dut = createDefaultConnection();

    assertFalse(dut.isMonitoringExtractedText());
    final ExtractedTextRequest req = new ExtractedTextRequest();
    req.token = 7;
    final ExtractedText extracted =
        dut.getExtractedText(req, InputConnection.GET_EXTRACTED_TEXT_MONITOR);
    assertNotNull(extracted);
    assertEquals("abc", extracted.text.toString());
    assertEquals(1, extracted.selectionStart);
    assertEquals(3, extracted.selectionEnd);
    assertTrue(dut.isMonitoringExtractedText());

    // Turning off monitoring clears request.
    dut.getExtractedText(req, 0);
    assertFalse(dut.isMonitoringExtractedText());

    // requestCursorUpdates 1-arg IMMEDIATE + MONITOR uses OP_GET_CURSOR_ANCHOR_STATE without
    // OP_GET_FULL_STATE.
    fakeBridge.recordedOpcodes.clear();
    assertFalse(dut.isMonitoringCursorUpdates());
    assertTrue(
        dut.requestCursorUpdates(
            InputConnection.CURSOR_UPDATE_IMMEDIATE | InputConnection.CURSOR_UPDATE_MONITOR));
    assertTrue(dut.isMonitoringCursorUpdates());
    assertEquals(1, fakeBridge.countOpcode(SyncTextInputProtocol.OP_GET_CURSOR_ANCHOR_STATE));
    assertEquals(0, fakeBridge.countOpcode(SyncTextInputProtocol.OP_GET_FULL_STATE));
    assertNotNull(imm.lastCursorAnchorInfo);
    assertEquals(1, imm.lastCursorAnchorInfo.getSelectionStart());
    assertEquals(3, imm.lastCursorAnchorInfo.getSelectionEnd());
    assertEquals("abc", imm.lastCursorAnchorInfo.getComposingText().toString());

    // 2-arg API 33 requestCursorUpdates: filter=0 delegates, non-zero returns false.
    assertFalse(dut.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR, 1));
    assertTrue(dut.requestCursorUpdates(0, 0));
    assertFalse(dut.isMonitoringCursorUpdates());
  }

  @Test
  public void performContextMenuAction_respectsObscureTextFlagAndClipboard() {
    final ClipboardManager clipboard =
        (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(ClipData.newPlainText("init", "initial"));

    // 1. obscureText=true blocks cut & copy.
    fakeBridge.setClientState(CLIENT_ID, "secret", 0, 6, -1, -1);
    final EditorInfo obscuredInfo = new EditorInfo();
    obscuredInfo.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
    final FlutterInputConnection obscuredConn = createConnection(obscuredInfo, true);
    assertFalse(obscuredConn.performContextMenuAction(android.R.id.copy));
    assertFalse(obscuredConn.performContextMenuAction(android.R.id.cut));
    assertEquals("secret", fakeBridge.text);
    assertEquals("initial", clipboard.getPrimaryClip().getItemAt(0).getText().toString());

    // 2. obscureText=false with VISIBLE_PASSWORD (enableSuggestions: false) ALLOWS copy/cut/paste.
    final EditorInfo noSuggestionsInfo = new EditorInfo();
    noSuggestionsInfo.inputType =
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
    final FlutterInputConnection visibleConn = createConnection(noSuggestionsInfo, false);
    assertTrue(visibleConn.performContextMenuAction(android.R.id.selectAll));
    assertEquals(0, fakeBridge.selStart);
    assertEquals(6, fakeBridge.selEnd);

    assertTrue(visibleConn.performContextMenuAction(android.R.id.copy));
    assertEquals("secret", clipboard.getPrimaryClip().getItemAt(0).getText().toString());

    assertTrue(visibleConn.performContextMenuAction(android.R.id.cut));
    assertEquals("", fakeBridge.text);

    assertTrue(visibleConn.performContextMenuAction(android.R.id.paste));
    assertEquals("secret", fakeBridge.text);
  }

  // ---- commitContent Permission & Stream Lifecycle ----

  @Test
  public void commitContent_requestsPermissionOnceClosesStreamAndReleasesPermission() {
    final ContentResolver resolver = ctx.getContentResolver();
    final ShadowContentResolver shadowResolver = Shadow.extract(resolver);
    final Uri uri = Uri.parse("content://example.provider/image.png");
    final byte[] payload = "png-bytes".getBytes(StandardCharsets.UTF_8);
    final AtomicBoolean streamClosed = new AtomicBoolean(false);
    shadowResolver.registerInputStream(
        uri,
        new ByteArrayInputStream(payload) {
          @Override
          public void close() throws IOException {
            streamClosed.set(true);
            super.close();
          }
        });

    final InputContentInfo contentInfo = mock(InputContentInfo.class);
    when(contentInfo.getDescription())
        .thenReturn(new ClipDescription("img", new String[] {"image/png"}));
    when(contentInfo.getContentUri()).thenReturn(uri);

    final FlutterInputConnection dut = createDefaultConnection();

    // Missing permission grant flag returns false without touching contentInfo.
    assertFalse(dut.commitContent(contentInfo, 0, null));
    verify(contentInfo, never()).requestPermission();

    // Valid call requests permission once, closes stream, releases permission, sends channel msg.
    assertTrue(
        dut.commitContent(
            contentInfo, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null));
    verify(contentInfo, times(1)).requestPermission();
    verify(contentInfo, times(1)).releasePermission();
    assertTrue("InputStream must be closed", streamClosed.get());

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Map<String, Object>> mapCaptor = ArgumentCaptor.forClass(Map.class);
    verify(textInputChannel, times(1)).commitContent(eq(CLIENT_ID), mapCaptor.capture());
    assertEquals("image/png", mapCaptor.getValue().get("mimeType"));
    assertEquals(uri.toString(), mapCaptor.getValue().get("uri"));
    assertArrayEquals(payload, (byte[]) mapCaptor.getValue().get("data"));
  }

  @Test
  public void commitContent_releasesPermissionAndClosesStreamOnIoError() {
    final ContentResolver resolver = ctx.getContentResolver();
    final ShadowContentResolver shadowResolver = Shadow.extract(resolver);
    final Uri uri = Uri.parse("content://example.provider/broken.png");
    final AtomicBoolean streamClosed = new AtomicBoolean(false);
    shadowResolver.registerInputStream(
        uri,
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("boom");
          }

          @Override
          public void close() {
            streamClosed.set(true);
          }
        });

    final InputContentInfo contentInfo = mock(InputContentInfo.class);
    when(contentInfo.getDescription())
        .thenReturn(new ClipDescription("img", new String[] {"image/png"}));
    when(contentInfo.getContentUri()).thenReturn(uri);

    final FlutterInputConnection dut = createDefaultConnection();
    assertFalse(
        dut.commitContent(
            contentInfo, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null));
    verify(contentInfo, times(1)).requestPermission();
    verify(contentInfo, times(1)).releasePermission();
    assertTrue("InputStream must be closed on IOException", streamClosed.get());
    verify(textInputChannel, never()).commitContent(eq(CLIENT_ID), any());
  }

  @Test
  public void commitContent_returnsFalseWhenRequestPermissionThrows() {
    final InputContentInfo contentInfo = mock(InputContentInfo.class);
    doThrow(new SecurityException("denied")).when(contentInfo).requestPermission();

    final FlutterInputConnection dut = createDefaultConnection();
    assertFalse(
        dut.commitContent(
            contentInfo, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null));
    verify(contentInfo, never()).releasePermission();
  }

  // ---- KeyEvents, EditorActions, PrivateCommands, SDK 34 Stubs ----

  @Test
  public void handleKeyEvent_dpadEnterPrintableAndDeadKey() {
    fakeBridge.setClientState(CLIENT_ID, "ab\ncd", 4, 4, -1, -1);
    final EditorInfo singleLineInfo = new EditorInfo();
    singleLineInfo.inputType = InputType.TYPE_CLASS_TEXT;
    singleLineInfo.imeOptions = EditorInfo.IME_ACTION_SEARCH;
    final FlutterInputConnection dut = createConnection(singleLineInfo, false);

    // DPAD LEFT/RIGHT/UP/DOWN.
    assertTrue(dut.handleKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)));
    assertEquals(3, fakeBridge.selStart);
    assertEquals(3, fakeBridge.selEnd);

    assertTrue(dut.handleKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP)));
    assertEquals(0, fakeBridge.selStart);

    assertTrue(dut.handleKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)));
    assertEquals(3, fakeBridge.selStart);

    // Shift + DPAD RIGHT extends selection.
    final KeyEvent shiftRight =
        new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0, KeyEvent.META_SHIFT_ON);
    assertTrue(dut.handleKeyEvent(shiftRight));
    assertEquals(3, fakeBridge.selStart);
    assertEquals(4, fakeBridge.selEnd);

    // Single-line Enter triggers performEditorAction.
    assertTrue(dut.handleKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)));
    verify(textInputChannel, times(1)).search(CLIENT_ID);

    // ACTION_UP is ignored.
    assertFalse(dut.handleKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A)));

    // Supplementary Unicode code point (😀 = U+1F600) inserts via OP_INSERT_KEY_CHARACTER.
    final KeyEvent emojiEvent = mock(KeyEvent.class);
    when(emojiEvent.getAction()).thenReturn(KeyEvent.ACTION_DOWN);
    when(emojiEvent.getKeyCode()).thenReturn(KeyEvent.KEYCODE_UNKNOWN);
    when(emojiEvent.getUnicodeChar()).thenReturn(0x1F600);
    assertTrue(dut.handleKeyEvent(emojiEvent));
    assertTrue(fakeBridge.text.contains("\uD83D\uDE00"));

    // Dead key combining accent is buffered and combined with the following character (^ + e -> ê).
    fakeBridge.setClientState(CLIENT_ID, "", 0, 0, -1, -1);
    final KeyEvent deadKey = mock(KeyEvent.class);
    when(deadKey.getAction()).thenReturn(KeyEvent.ACTION_DOWN);
    when(deadKey.getKeyCode()).thenReturn(KeyEvent.KEYCODE_UNKNOWN);
    when(deadKey.getUnicodeChar()).thenReturn(KeyCharacterMap.COMBINING_ACCENT | '^');
    assertTrue(dut.handleKeyEvent(deadKey));
    assertEquals("", fakeBridge.text);

    final KeyEvent baseChar = mock(KeyEvent.class);
    when(baseChar.getAction()).thenReturn(KeyEvent.ACTION_DOWN);
    when(baseChar.getKeyCode()).thenReturn(KeyEvent.KEYCODE_E);
    when(baseChar.getUnicodeChar()).thenReturn((int) 'e');
    assertTrue(dut.handleKeyEvent(baseChar));
    assertEquals("\u00EA", fakeBridge.text);
  }

  @Test
  public void handleKeyEvent_multilineEnterAndPrintableKeyPreserveComposingAndRejectInvalidSel() {
    final EditorInfo multiLineInfo = new EditorInfo();
    multiLineInfo.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
    multiLineInfo.imeOptions = EditorInfo.IME_ACTION_NONE;
    final FlutterInputConnection dut = createConnection(multiLineInfo, false);

    // Multiline Enter inserts '\n' via OP_INSERT_KEY_CHARACTER without wiping active composing.
    fakeBridge.setClientState(CLIENT_ID, "hello", 5, 5, 0, 5);
    fakeBridge.recordedOpcodes.clear();
    final KeyEvent enterEvent = mock(KeyEvent.class);
    when(enterEvent.getAction()).thenReturn(KeyEvent.ACTION_DOWN);
    when(enterEvent.getKeyCode()).thenReturn(KeyEvent.KEYCODE_ENTER);
    when(enterEvent.getUnicodeChar()).thenReturn((int) '\n');
    assertTrue(dut.handleKeyEvent(enterEvent));
    assertEquals(1, fakeBridge.countOpcode(SyncTextInputProtocol.OP_INSERT_KEY_CHARACTER));
    assertEquals(0, fakeBridge.countOpcode(SyncTextInputProtocol.OP_COMMIT_TEXT));
    assertEquals("hello\n", fakeBridge.text);
    assertEquals(0, fakeBridge.compStart);
    assertEquals(5, fakeBridge.compEnd);

    // Invalid selection (-1, -1) returns false, matching InputConnectionAdaptor.handleKeyEvent.
    fakeBridge.setClientState(CLIENT_ID, "hello", -1, -1, -1, -1);
    final KeyEvent aEvent = mock(KeyEvent.class);
    when(aEvent.getAction()).thenReturn(KeyEvent.ACTION_DOWN);
    when(aEvent.getKeyCode()).thenReturn(KeyEvent.KEYCODE_A);
    when(aEvent.getUnicodeChar()).thenReturn((int) 'a');
    assertFalse(dut.handleKeyEvent(aEvent));
    assertEquals("hello", fakeBridge.text);
  }

  @Test
  public void editorActionsPrivateCommandSendKeyEventAndApi34Stubs() {
    final FlutterInputConnection dut = createDefaultConnection();

    assertTrue(dut.performEditorAction(EditorInfo.IME_ACTION_NONE));
    verify(textInputChannel).newline(CLIENT_ID);
    assertTrue(dut.performEditorAction(EditorInfo.IME_ACTION_UNSPECIFIED));
    verify(textInputChannel).unspecifiedAction(CLIENT_ID);
    assertTrue(dut.performEditorAction(EditorInfo.IME_ACTION_GO));
    verify(textInputChannel).go(CLIENT_ID);
    assertTrue(dut.performEditorAction(EditorInfo.IME_ACTION_SEARCH));
    verify(textInputChannel).search(CLIENT_ID);
    assertTrue(dut.performEditorAction(EditorInfo.IME_ACTION_SEND));
    verify(textInputChannel).send(CLIENT_ID);
    assertTrue(dut.performEditorAction(EditorInfo.IME_ACTION_NEXT));
    verify(textInputChannel).next(CLIENT_ID);
    assertTrue(dut.performEditorAction(EditorInfo.IME_ACTION_PREVIOUS));
    verify(textInputChannel).previous(CLIENT_ID);
    assertTrue(dut.performEditorAction(EditorInfo.IME_ACTION_DONE));
    verify(textInputChannel).done(CLIENT_ID);

    final Bundle bundle = new Bundle();
    assertTrue(dut.performPrivateCommand("com.test.ACTION", bundle));
    verify(textInputChannel).performPrivateCommand(CLIENT_ID, "com.test.ACTION", bundle);

    final KeyEvent keyEvent = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A);
    when(keyboardDelegate.handleEvent(keyEvent)).thenReturn(true);
    assertTrue(dut.sendKeyEvent(keyEvent));
    verify(keyboardDelegate).handleEvent(keyEvent);

    // Unsupported stubs match BaseInputConnection defaults.
    final BaseInputConnection baseRef = new BaseInputConnection(view, true);
    assertFalse(dut.commitCompletion(null));
    assertFalse(dut.commitCorrection(null));
    assertEquals(baseRef.reportFullscreenMode(true), dut.reportFullscreenMode(true));
    assertTrue(dut.reportFullscreenMode(true));
    assertFalse(dut.performSpellCheck());
    assertEquals(baseRef.setImeConsumesInput(true), dut.setImeConsumesInput(true));
    assertFalse(dut.setImeConsumesInput(true));
    assertFalse(dut.previewHandwritingGesture(mock(PreviewableHandwritingGesture.class), null));
    assertNull(dut.getHandler());

    final AtomicInteger gestureResult = new AtomicInteger(-1);
    dut.performHandwritingGesture(
        mock(HandwritingGesture.class), Runnable::run, gestureResult::set);
    assertEquals(InputConnection.HANDWRITING_GESTURE_RESULT_UNSUPPORTED, gestureResult.get());

    final AtomicReference<TextBoundsInfoResult> boundsResult = new AtomicReference<>();
    dut.requestTextBoundsInfo(new RectF(), Runnable::run, boundsResult::set);
    assertNotNull(boundsResult.get());
    assertEquals(TextBoundsInfoResult.CODE_UNSUPPORTED, boundsResult.get().getResultCode());
  }

  // ---- Batching, Leaked Batch Unwind & Closed Connection Guards ----

  @Test
  public void nestedBatchEdit_andLeakedBatchUnwoundOnCloseConnection() {
    fakeBridge.setClientState(CLIENT_ID, "", 0, 0, -1, -1);
    final FlutterInputConnection dut = createDefaultConnection();

    assertTrue(dut.beginBatchEdit());
    assertTrue(dut.beginBatchEdit());
    assertTrue(dut.commitText("a", 1));
    assertTrue(dut.commitText("b", 1));
    assertTrue(
        "Mutations inside an open batch must not be marked committed",
        host.mutationResponses.stream().noneMatch(SyncTextInputProtocol.Response::isCommitted));
    // Still inside batch (depth=2), endBatchEdit returns true when depth remains > 0.
    assertTrue(dut.endBatchEdit());
    // Final endBatchEdit returns false and commits.
    assertFalse(dut.endBatchEdit());
    assertEquals("ab", fakeBridge.text);
    assertTrue(
        host.mutationResponses.get(host.mutationResponses.size() - 1).isCommitted());

    // Leaked batch: open batch, commit text, then closeConnection() without endBatchEdit().
    host.mutationResponses.clear();
    assertTrue(dut.beginBatchEdit());
    assertTrue(dut.commitText("c", 1));
    dut.closeConnection();

    assertTrue(dut.isClosed());
    assertEquals("abc", fakeBridge.text);
    assertEquals(0, fakeBridge.batchDepth);
    assertTrue(
        "Leaked batch must commit on closeConnection",
        host.mutationResponses.stream().anyMatch(SyncTextInputProtocol.Response::isCommitted));
    assertTrue(host.closedConnections.contains(dut));

    // After close, all methods safely return false / null / 0 without dispatching.
    final int opCountAfterClose = fakeBridge.recordedOpcodes.size();
    assertFalse(dut.commitText("x", 1));
    assertFalse(dut.beginBatchEdit());
    assertFalse(dut.endBatchEdit());
    assertNull(dut.getTextBeforeCursor(5, 0));
    assertNull(dut.getTextAfterCursor(5, 0));
    assertNull(dut.getSelectedText(0));
    assertNull(dut.getSurroundingText(5, 5, 0));
    assertNull(dut.takeSnapshot());
    assertNull(dut.getExtractedText(new ExtractedTextRequest(), 0));
    assertEquals(0, dut.getCursorCapsMode(TextUtils.CAP_MODE_WORDS));
    assertFalse(dut.performContextMenuAction(android.R.id.selectAll));
    assertFalse(dut.performEditorAction(EditorInfo.IME_ACTION_DONE));
    assertFalse(dut.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE));
    assertEquals(opCountAfterClose, fakeBridge.recordedOpcodes.size());
  }

  // ---- Golden Binary Wire-Format Parity & Buffer Pool & Host Contracts ----

  @Test
  public void goldenBinaryWireFormat_matchesDartCodecExactBytes() {
    // Exact byte vectors shared 1-for-1 with
    // packages/flutter/test/services/android_sync_text_input_test.dart.
    final byte[] expectedCommitReq =
        new byte[] {
          0x04, 0x00, 0x00, 0x00, // opcode = OP_COMMIT_TEXT (4)
          0x2A, 0x00, 0x00, 0x00, // clientId = 42
          0x03, 0x00, 0x00, 0x00, // UTF-16 code unit len = 3
          0x61, 0x00, // 'a'
          0x3D, (byte) 0xD8, // high surrogate 0xD83D
          0x00, (byte) 0xDE, // low surrogate 0xDE00
          0x01, 0x00, 0x00, 0x00, // newCursorPosition = 1
        };
    final byte[] goldenCommitResp =
        new byte[] {
          0x01, 0x00, 0x00, 0x00, // STATUS_OK
          0x07, 0x00, 0x00, 0x00, // COMMITTED | STATE_CHANGED | TEXT_CHANGED
          0x04, 0x00, 0x00, 0x00, // selStart = 4
          0x04, 0x00, 0x00, 0x00, // selEnd = 4
          (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, // compStart = -1
          (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, // compEnd = -1
          0x00, 0x00, 0x00, 0x00, // intResult = 0
        };

    final byte[] expectedSetComposingRegionReq =
        new byte[] {
          0x06, 0x00, 0x00, 0x00, // opcode = OP_SET_COMPOSING_REGION (6)
          0x2A, 0x00, 0x00, 0x00, // clientId = 42
          0x01, 0x00, 0x00, 0x00, // start = 1
          0x04, 0x00, 0x00, 0x00, // end = 4
        };
    final byte[] goldenSetComposingRegionResp =
        new byte[] {
          0x01, 0x00, 0x00, 0x00, // STATUS_OK
          0x03, 0x00, 0x00, 0x00, // COMMITTED | STATE_CHANGED
          0x04, 0x00, 0x00, 0x00, // selStart = 4
          0x04, 0x00, 0x00, 0x00, // selEnd = 4
          0x01, 0x00, 0x00, 0x00, // compStart = 1
          0x04, 0x00, 0x00, 0x00, // compEnd = 4
          0x00, 0x00, 0x00, 0x00, // intResult = 0
        };

    final byte[] expectedCursorAnchorReq =
        new byte[] {
          0x1B, 0x00, 0x00, 0x00, // opcode = OP_GET_CURSOR_ANCHOR_STATE (27)
          0x2A, 0x00, 0x00, 0x00, // clientId = 42
        };
    final byte[] goldenCursorAnchorResp =
        new byte[] {
          0x01, 0x00, 0x00, 0x00, // STATUS_OK
          (byte) 0x80, 0x00, 0x00, 0x00, // RESP_FLAG_HAS_TEXT
          0x04, 0x00, 0x00, 0x00, // selStart = 4
          0x04, 0x00, 0x00, 0x00, // selEnd = 4
          0x01, 0x00, 0x00, 0x00, // compStart = 1
          0x04, 0x00, 0x00, 0x00, // compEnd = 4
          0x00, 0x00, 0x00, 0x00, // intResult = 0
          0x03, 0x00, 0x00, 0x00, // charLen = 3 (composing substring only)
          0x61, 0x00, 0x3D, (byte) 0xD8, 0x00, (byte) 0xDE, // "a\uD83D\uDE00"
        };

    final byte[] expectedInsertKeyCharReq =
        new byte[] {
          0x1A, 0x00, 0x00, 0x00, // opcode = OP_INSERT_KEY_CHARACTER (26)
          0x2A, 0x00, 0x00, 0x00, // clientId = 42
          0x01, 0x00, 0x00, 0x00, // charLen = 1
          0x0A, 0x00, // '\n'
        };
    final byte[] goldenInsertKeyCharResp =
        new byte[] {
          0x01, 0x00, 0x00, 0x00, // STATUS_OK
          0x07, 0x00, 0x00, 0x00, // COMMITTED | STATE_CHANGED | TEXT_CHANGED
          0x05, 0x00, 0x00, 0x00, // selStart = 5
          0x05, 0x00, 0x00, 0x00, // selEnd = 5
          0x01, 0x00, 0x00, 0x00, // compStart = 1
          0x04, 0x00, 0x00, 0x00, // compEnd = 4
          0x00, 0x00, 0x00, 0x00, // intResult = 0
        };

    final byte[] expectedSurroundingReq =
        new byte[] {
          0x0F, 0x00, 0x00, 0x00, // opcode = OP_GET_SURROUNDING_TEXT (15)
          0x2A, 0x00, 0x00, 0x00, // clientId = 42
          0x02, 0x00, 0x00, 0x00, // before = 2
          0x01, 0x00, 0x00, 0x00, // after = 1
          0x00, 0x00, 0x00, 0x00, // flags = 0
        };
    final byte[] goldenSurroundingResp =
        new byte[] {
          0x01, 0x00, 0x00, 0x00, // STATUS_OK
          (byte) 0x80, 0x00, 0x00, 0x00, // RESP_FLAG_HAS_TEXT
          0x02, 0x00, 0x00, 0x00, // relSelStart = 2
          0x02, 0x00, 0x00, 0x00, // relSelEnd = 2
          0x01, 0x00, 0x00, 0x00, // compStart = 1
          0x04, 0x00, 0x00, 0x00, // compEnd = 4
          0x03, 0x00, 0x00, 0x00, // offset = 3
          0x02, 0x00, 0x00, 0x00, // charLen = 2
          0x00, (byte) 0xDE, 0x0A, 0x00, // "\uDE00\n"
        };

    final byte[] expectedReplaceTextReq =
        new byte[] {
          0x0B, 0x00, 0x00, 0x00, // opcode = OP_REPLACE_TEXT (11)
          0x2A, 0x00, 0x00, 0x00, // clientId = 42
          0x00, 0x00, 0x00, 0x00, // start = 0
          0x01, 0x00, 0x00, 0x00, // end = 1
          0x01, 0x00, 0x00, 0x00, // charLen = 1
          0x48, 0x00, // 'H'
          0x01, 0x00, 0x00, 0x00, // newCursorPosition = 1
        };
    final byte[] goldenReplaceTextResp =
        new byte[] {
          0x01, 0x00, 0x00, 0x00, // STATUS_OK
          0x07, 0x00, 0x00, 0x00, // COMMITTED | STATE_CHANGED | TEXT_CHANGED
          0x01, 0x00, 0x00, 0x00, // selStart = 1
          0x01, 0x00, 0x00, 0x00, // selEnd = 1
          (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, // compStart = -1
          (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, // compEnd = -1
          0x00, 0x00, 0x00, 0x00, // intResult = 0
        };

    final byte[] expectedCloseReq =
        new byte[] {
          0x13, 0x00, 0x00, 0x00, // opcode = OP_CLOSE_CONNECTION (19)
          0x2A, 0x00, 0x00, 0x00, // clientId = 42
        };
    final byte[] goldenCloseResp =
        new byte[] {
          0x01, 0x00, 0x00, 0x00, // STATUS_OK
          0x00, 0x00, 0x00, 0x00, // flags = 0
          0x01, 0x00, 0x00, 0x00, // selStart = 1
          0x01, 0x00, 0x00, 0x00, // selEnd = 1
          (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, // compStart = -1
          (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, // compEnd = -1
          0x00, 0x00, 0x00, 0x00, // intResult = 0
        };

    final List<byte[]> capturedReqs = new ArrayList<>();
    final List<byte[]> scriptedResps = new ArrayList<>();
    scriptedResps.add(goldenCommitResp);
    scriptedResps.add(goldenSetComposingRegionResp);
    scriptedResps.add(goldenCursorAnchorResp);
    scriptedResps.add(goldenInsertKeyCharResp);
    scriptedResps.add(goldenSurroundingResp);
    scriptedResps.add(goldenReplaceTextResp);
    scriptedResps.add(goldenCloseResp);
    final AtomicInteger step = new AtomicInteger(0);
    fakeBridge.interceptor =
        (opcode, clientId, req) -> {
          final byte[] copy = new byte[req.limit()];
          final ByteBuffer dup = req.duplicate();
          dup.position(0);
          dup.get(copy);
          capturedReqs.add(copy);
          return scriptedResps.get(step.getAndIncrement());
        };

    final EditorInfo multiLineInfo = new EditorInfo();
    multiLineInfo.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
    final FlutterInputConnection dut = createConnection(multiLineInfo, false);

    assertTrue(dut.commitText("a\uD83D\uDE00", 1));
    assertTrue(dut.setComposingRegion(1, 4));
    assertTrue(dut.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE));
    assertNotNull(imm.lastCursorAnchorInfo);
    assertEquals("a\uD83D\uDE00", imm.lastCursorAnchorInfo.getComposingText().toString());
    assertEquals(1, imm.lastCursorAnchorInfo.getComposingTextStart());

    final KeyEvent enterEvent = mock(KeyEvent.class);
    when(enterEvent.getAction()).thenReturn(KeyEvent.ACTION_DOWN);
    when(enterEvent.getKeyCode()).thenReturn(KeyEvent.KEYCODE_ENTER);
    when(enterEvent.getUnicodeChar()).thenReturn((int) '\n');
    assertTrue(dut.handleKeyEvent(enterEvent));

    final SurroundingText surr = dut.getSurroundingText(2, 1, 0);
    assertNotNull(surr);
    assertEquals("\uDE00\n", surr.getText().toString());
    assertEquals(2, surr.getSelectionStart());
    assertEquals(2, surr.getSelectionEnd());
    assertEquals(3, surr.getOffset());

    assertTrue(dut.replaceText(0, 1, "H", 1, null));
    dut.closeConnection();

    assertEquals(7, capturedReqs.size());
    assertArrayEquals(expectedCommitReq, capturedReqs.get(0));
    assertArrayEquals(expectedSetComposingRegionReq, capturedReqs.get(1));
    assertArrayEquals(expectedCursorAnchorReq, capturedReqs.get(2));
    assertArrayEquals(expectedInsertKeyCharReq, capturedReqs.get(3));
    assertArrayEquals(expectedSurroundingReq, capturedReqs.get(4));
    assertArrayEquals(expectedReplaceTextReq, capturedReqs.get(5));
    assertArrayEquals(expectedCloseReq, capturedReqs.get(6));
  }

  @Test
  public void setComposingRegion_setsSuppressSelectionUpdateFlagOnHost() {
    fakeBridge.setClientState(CLIENT_ID, "hello", 5, 5, -1, -1);
    final FlutterInputConnection dut = createDefaultConnection();

    assertTrue(dut.setComposingRegion(0, 5));
    assertTrue(host.lastSuppressSelectionUpdate);

    assertTrue(dut.commitText("!", 1));
    assertFalse(host.lastSuppressSelectionUpdate);
  }

  @Test
  public void directBufferPool_skipsUndersizedBuffersWithoutDiscardingThem() {
    final SyncTextInputProtocol.DirectBufferPool pool = new SyncTextInputProtocol.DirectBufferPool();
    final ByteBuffer small = pool.acquire(16);
    assertEquals(512, small.capacity());
    pool.release(small);

    // Acquiring a larger buffer must leave the 512-byte buffer in the pool rather than discarding it.
    final ByteBuffer large = pool.acquire(1024);
    assertTrue(large.capacity() >= 1024);
    final ByteBuffer reusedSmall = pool.acquire(16);
    assertEquals("Original 512-byte pooled buffer must be reused", small, reusedSmall);
    pool.release(large);
    pool.release(reusedSmall);
  }

  private static final class TestHost implements FlutterInputConnection.Host {
    private final FakeSyncTextInputBridge bridge;
    private final SyncTextInputProtocol.DirectBufferPool pool =
        new SyncTextInputProtocol.DirectBufferPool();
    @Nullable FlutterInputConnection activeConnection;
    final List<SyncTextInputProtocol.Response> mutationResponses = new ArrayList<>();
    final List<FlutterInputConnection> closedConnections = new ArrayList<>();
    int imeDepth = 0;
    long syncNotifySeq = 0;
    boolean lastSuppressSelectionUpdate = false;

    TestHost(FakeSyncTextInputBridge bridge) {
      this.bridge = bridge;
    }

    @Override
    public boolean isConnectionActive(@NonNull FlutterInputConnection connection) {
      return activeConnection == connection;
    }

    @Override
    public boolean isSyncModeActive() {
      return bridge.isSyncTextInputEnabled();
    }

    @Override
    public void enterImeCallback() {
      imeDepth++;
    }

    @Override
    public void exitImeCallback() {
      imeDepth--;
    }

    @Override
    public long getSyncNotifySeq() {
      return syncNotifySeq;
    }

    @NonNull
    @Override
    public SyncTextInputProtocol.DirectBufferPool getRequestBufferPool() {
      return pool;
    }

    @Nullable
    @Override
    public SyncTextInputProtocol.Response dispatchSyncRequest(
        @NonNull ByteBuffer buffer, int length) {
      return SyncTextInputProtocol.decodeResponse(bridge.dispatchSyncTextInput(buffer, length));
    }

    @Override
    public void onMutationResponse(
        @NonNull FlutterInputConnection connection,
        @NonNull SyncTextInputProtocol.Response response,
        long notifySeqBefore,
        boolean suppressSelectionUpdate) {
      lastSuppressSelectionUpdate = suppressSelectionUpdate;
      mutationResponses.add(response);
    }

    @Override
    public void onConnectionClosed(@NonNull FlutterInputConnection connection) {
      closedConnections.add(connection);
      if (activeConnection == connection) {
        activeConnection = null;
      }
    }
  }

  @Implements(InputMethodManager.class)
  public static class RecordingImm extends ShadowInputMethodManager {
    @Nullable CursorAnchorInfo lastCursorAnchorInfo;

    void resetRecording() {
      lastCursorAnchorInfo = null;
    }

    @Implementation
    public void updateCursorAnchorInfo(View view, CursorAnchorInfo cursorAnchorInfo) {
      this.lastCursorAnchorInfo = cursorAnchorInfo;
    }
  }
}
