// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugin.editing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewStructure;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import androidx.annotation.Nullable;
import androidx.core.view.inputmethod.EditorInfoCompat;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.flutter.embedding.android.KeyboardManager;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterJNI;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.flags.FlutterEngineFlags;
import io.flutter.embedding.engine.systemchannels.ScribeChannel;
import io.flutter.embedding.engine.systemchannels.TextInputChannel;
import io.flutter.plugin.platform.PlatformViewsController;
import io.flutter.plugin.platform.PlatformViewsController2;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAutofillManager;
import org.robolectric.shadows.ShadowInputMethodManager;

@Config(
    shadows = {
      TextInputPluginSyncTest.RecordingImm.class,
      TextInputPluginSyncTest.RecordingAfm.class
    })
@RunWith(AndroidJUnit4.class)
public class TextInputPluginSyncTest {
  private final Context ctx = ApplicationProvider.getApplicationContext();
  private RecordingImm imm;
  private RecordingAfm afm;
  private View view;
  private TextInputChannel textInputChannel;
  private ScribeChannel scribeChannel;
  private PlatformViewsController platformViewsController;
  private PlatformViewsController2 platformViewsController2;
  private KeyboardManager keyboardManager;
  private FakeSyncTextInputBridge fakeBridge;

  @Before
  public void setUp() {
    imm = Shadow.extract(ctx.getSystemService(Context.INPUT_METHOD_SERVICE));
    imm.resetRecording();
    afm = Shadow.extract(ctx.getSystemService(AutofillManager.class));
    afm.resetRecording();
    view = new View(ctx);
    textInputChannel = spy(new TextInputChannel(mock(DartExecutor.class)));
    scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    platformViewsController = mock(PlatformViewsController.class);
    platformViewsController2 = mock(PlatformViewsController2.class);
    keyboardManager = mock(KeyboardManager.class);
    fakeBridge = new FakeSyncTextInputBridge();
  }

  private TextInputPlugin createPlugin() {
    return new TextInputPlugin(
        view,
        textInputChannel,
        scribeChannel,
        platformViewsController,
        platformViewsController2,
        fakeBridge);
  }

  private static TextInputChannel.Configuration plainConfig(boolean obscureText) {
    return new TextInputChannel.Configuration(
        obscureText,
        true,
        true,
        true,
        false,
        TextInputChannel.TextCapitalization.NONE,
        new TextInputChannel.InputType(TextInputChannel.TextInputType.TEXT, false, false, false),
        EditorInfo.IME_ACTION_DONE,
        null,
        null,
        null,
        null,
        null);
  }

  private static TextInputChannel.Configuration autofillConfig(
      String id, String initialText, @Nullable TextInputChannel.Configuration[] fields) {
    final TextInputChannel.Configuration.Autofill autofill =
        new TextInputChannel.Configuration.Autofill(
            id,
            new String[] {View.AUTOFILL_HINT_USERNAME},
            "hint",
            new TextInputChannel.TextEditState(initialText, 0, 0, -1, -1));
    return new TextInputChannel.Configuration(
        false,
        true,
        true,
        true,
        false,
        TextInputChannel.TextCapitalization.NONE,
        new TextInputChannel.InputType(TextInputChannel.TextInputType.TEXT, false, false, false),
        EditorInfo.IME_ACTION_DONE,
        null,
        autofill,
        null,
        fields,
        null);
  }

  @Test
  public void syncModeEnabledCreatesFlutterInputConnection_disabledFallsBackToAdaptor() {
    fakeBridge.enabled = true;
    fakeBridge.setClientState(1, "hello", 2, 5, -1, -1);
    final TextInputPlugin syncPlugin = createPlugin();
    syncPlugin.setTextInputClient(1, plainConfig(false));
    assertEquals(1, imm.restartCount);

    final EditorInfo attrs = new EditorInfo();
    final InputConnection conn = syncPlugin.createInputConnection(view, keyboardManager, attrs);
    assertTrue(conn instanceof FlutterInputConnection);
    assertEquals(2, attrs.initialSelStart);
    assertEquals(5, attrs.initialSelEnd);
    assertEquals("he", EditorInfoCompat.getInitialTextBeforeCursor(attrs, 10, 0).toString());
    assertEquals("llo", EditorInfoCompat.getInitialSelectedText(attrs, 0).toString());

    // Kill-switch disabled -> legacy InputConnectionAdaptor.
    fakeBridge.enabled = false;
    final TextInputPlugin legacyPlugin = createPlugin();
    legacyPlugin.setTextInputClient(2, plainConfig(false));
    final EditorInfo legacyAttrs = new EditorInfo();
    final InputConnection legacyConn =
        legacyPlugin.createInputConnection(view, keyboardManager, legacyAttrs);
    assertTrue(legacyConn instanceof InputConnectionAdaptor);
  }

  @Test
  public void createInputConnection_omitsInitialSurroundingSubTextWhenObscured() {
    fakeBridge.setClientState(1, "s3cr3t", 6, 6, -1, -1);
    final TextInputPlugin plugin = createPlugin();
    plugin.setTextInputClient(1, plainConfig(true));

    final EditorInfo attrs = new EditorInfo();
    final InputConnection conn = plugin.createInputConnection(view, keyboardManager, attrs);
    assertTrue(conn instanceof FlutterInputConnection);
    assertEquals(6, attrs.initialSelStart);
    assertEquals(6, attrs.initialSelEnd);
    assertNull(EditorInfoCompat.getInitialTextBeforeCursor(attrs, 10, 0));
  }

  @Test
  public void normalCommitText_usesOEditSizeFastPathWithoutGetFullState() {
    fakeBridge.setClientState(1, "hello", 5, 5, -1, -1);
    final TextInputPlugin plugin = createPlugin();
    plugin.setTextInputClient(1, plainConfig(false));
    final FlutterInputConnection conn =
        (FlutterInputConnection)
            plugin.createInputConnection(view, keyboardManager, new EditorInfo());
    assertNotNull(conn);
    fakeBridge.recordedOpcodes.clear();

    assertTrue(conn.commitText("!", 1));
    assertEquals(1, fakeBridge.countOpcode(SyncTextInputProtocol.OP_COMMIT_TEXT));
    assertEquals(0, fakeBridge.countOpcode(SyncTextInputProtocol.OP_GET_FULL_STATE));
    assertEquals(1, imm.selectionUpdates.size());
    assertEquals("6,6,-1,-1", imm.selectionUpdates.get(0));

    // Enabling ONLY CURSOR_UPDATE_MONITOR uses OP_GET_CURSOR_ANCHOR_STATE without OP_GET_FULL_STATE.
    conn.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR);
    fakeBridge.recordedOpcodes.clear();
    imm.lastCursorAnchorInfo = null;
    assertTrue(conn.commitText("?", 1));
    assertEquals(1, fakeBridge.countOpcode(SyncTextInputProtocol.OP_GET_CURSOR_ANCHOR_STATE));
    assertEquals(0, fakeBridge.countOpcode(SyncTextInputProtocol.OP_GET_FULL_STATE));
    assertNotNull(imm.lastCursorAnchorInfo);
    assertEquals(7, imm.lastCursorAnchorInfo.getSelectionStart());

    // Enabling extracted text + cursor monitoring queries OP_GET_FULL_STATE ONCE per commit.
    final ExtractedTextRequest req = new ExtractedTextRequest();
    req.token = 9;
    conn.getExtractedText(req, InputConnection.GET_EXTRACTED_TEXT_MONITOR);
    fakeBridge.recordedOpcodes.clear();
    imm.extractedUpdates.clear();
    imm.lastCursorAnchorInfo = null;

    assertTrue(conn.commitText("!", 1));
    assertEquals(1, fakeBridge.countOpcode(SyncTextInputProtocol.OP_GET_FULL_STATE));
    assertEquals(1, imm.extractedUpdates.size());
    assertEquals("hello!?!", imm.extractedUpdates.get(0).text.toString());
    assertNotNull(imm.lastCursorAnchorInfo);
    assertEquals(8, imm.lastCursorAnchorInfo.getSelectionStart());
  }

  @Test
  public void divergedUpdatesSelectionWithoutRestart_composingChangedSchedulesDeferredRestart() {
    fakeBridge.setClientState(1, "12", 2, 2, -1, -1);
    final TextInputPlugin plugin = createPlugin();
    plugin.setTextInputClient(1, plainConfig(false));
    final FlutterInputConnection conn =
        (FlutterInputConnection)
            plugin.createInputConnection(view, keyboardManager, new EditorInfo());
    assertNotNull(conn);
    imm.resetRecording();

    // Non-composing formatter rejection -> RESP_FLAG_DIVERGED updates selection without restartInput.
    fakeBridge.forceNextDiverged = true;
    fakeBridge.nextPostCommitTextOverride = "12";
    fakeBridge.nextPostCommitSelStart = 2;
    fakeBridge.nextPostCommitSelEnd = 2;
    fakeBridge.nextPostCommitCompStart = -1;
    fakeBridge.nextPostCommitCompEnd = -1;

    assertTrue(conn.commitText("a", 1));
    assertEquals(0, imm.restartCount);
    assertEquals(1, imm.selectionUpdates.size());
    assertEquals("2,2,-1,-1", imm.selectionUpdates.get(0));
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(0, imm.restartCount);

    // Framework alters composing -> RESP_FLAG_COMPOSING_CHANGED_BY_FRAMEWORK schedules restartInput.
    imm.resetRecording();
    fakeBridge.forceNextComposingChangedByFramework = true;
    assertTrue(conn.setComposingText("3", 1));
    assertEquals(0, imm.restartCount);
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(1, imm.restartCount);

    // Client changed inside callback -> suppresses stale updateSelection & restartInput.
    imm.resetRecording();
    fakeBridge.forceNextClientChanged = true;
    assertTrue(conn.commitText("4", 1));
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(0, imm.restartCount);
    assertEquals(0, imm.selectionUpdates.size());
  }

  @Test
  public void reentrantDestructiveCallsDuringImeCallback_areDeferredAndCancelledByEngineRestart() {
    fakeBridge.setClientState(1, "abc", 3, 3, -1, -1);
    final TextInputPlugin plugin = createPlugin();
    plugin.setTextInputClient(1, plainConfig(false));
    final FlutterInputConnection conn =
        (FlutterInputConnection)
            plugin.createInputConnection(view, keyboardManager, new EditorInfo());
    assertNotNull(conn);
    imm.resetRecording();

    // Simulate Dart calling setTextInputClient(2) synchronously inside commitText callback.
    final AtomicBoolean reentered = new AtomicBoolean(false);
    fakeBridge.interceptor =
        (opcode, clientId, request) -> {
          if (opcode == SyncTextInputProtocol.OP_COMMIT_TEXT && !reentered.getAndSet(true)) {
            plugin.setTextInputClient(2, plainConfig(false));
            plugin.showTextInput(view);
          }
          return null;
        };

    assertTrue(conn.commitText("d", 1));
    // Deferred: restartInput/showSoftInput must not have fired synchronously.
    assertEquals(0, imm.restartCount);
    assertEquals(0, imm.showSoftInputCount);

    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(1, imm.restartCount);
    assertEquals(1, imm.showSoftInputCount);

    // Now test cancellation across engine restart: queue clearClient during IME callback, then
    // trigger onPreEngineRestart before Looper drains.
    fakeBridge.setClientState(2, "xy", 2, 2, -1, -1);
    final FlutterInputConnection conn2 =
        (FlutterInputConnection)
            plugin.createInputConnection(view, keyboardManager, new EditorInfo());
    assertNotNull(conn2);
    imm.resetRecording();

    fakeBridge.interceptor =
        (opcode, clientId, request) -> {
          if (opcode == SyncTextInputProtocol.OP_COMMIT_TEXT) {
            plugin.clearTextInputClient();
            plugin.showTextInput(view);
          }
          return null;
        };
    assertTrue(conn2.commitText("z", 1));
    assertEquals(0, imm.showSoftInputCount);

    // Engine restarts -> bumps generation, cancels pending runnables, invalidates conn2.
    plugin.getEngineLifecycleListenerForTesting().onPreEngineRestart();
    assertTrue(conn2.isClosed());
    assertEquals(1, imm.restartCount);

    shadowOf(Looper.getMainLooper()).idle();
    // Stale deferred showTextInput must NOT execute after engine restart.
    assertEquals(0, imm.showSoftInputCount);
    assertNull(plugin.createInputConnection(view, keyboardManager, new EditorInfo()));
  }

  @Test
  public void fiveArgConstructor_lifecycleWiredViaFlutterJNIAndDestroyingOldPluginPreservesNewListener() {
    final Set<FlutterEngine.EngineLifecycleListener> listeners = new HashSet<>();
    final FlutterJNI mockJni = mock(FlutterJNI.class);
    org.mockito.Mockito.doAnswer(
            inv -> {
              listeners.add(inv.getArgument(0));
              return null;
            })
        .when(mockJni)
        .addEngineLifecycleListener(any());
    org.mockito.Mockito.doAnswer(
            inv -> {
              listeners.remove(inv.getArgument(0));
              return null;
            })
        .when(mockJni)
        .removeEngineLifecycleListener(any());
    final TextInputChannel channelWithJni =
        spy(new TextInputChannel(mock(DartExecutor.class), mockJni));

    fakeBridge.setClientState(1, "hello", 5, 5, -1, -1);
    final TextInputPlugin plugin1 =
        new TextInputPlugin(
            view,
            channelWithJni,
            scribeChannel,
            platformViewsController,
            platformViewsController2,
            fakeBridge);
    plugin1.setTextInputClient(1, plainConfig(false));
    final FlutterInputConnection conn =
        (FlutterInputConnection)
            plugin1.createInputConnection(view, keyboardManager, new EditorInfo());
    assertNotNull(conn);
    assertSame(plugin1.getSyncEditingStateListenerForTesting(), fakeBridge.editingStateListener);

    // Attach a newer plugin on the same bridge (e.g., cached engine re-attached to a new view).
    final TextInputPlugin plugin2 =
        new TextInputPlugin(
            view,
            channelWithJni,
            scribeChannel,
            platformViewsController,
            platformViewsController2,
            fakeBridge);
    assertSame(plugin2.getSyncEditingStateListenerForTesting(), fakeBridge.editingStateListener);

    // Destroying the older plugin must NOT clobber plugin2's registered editing-state listener.
    plugin1.destroy();
    assertTrue(conn.isClosed());
    assertSame(plugin2.getSyncEditingStateListenerForTesting(), fakeBridge.editingStateListener);

    // Engine teardown iterating listeners must notify remaining listener cleanly without CME.
    for (FlutterEngine.EngineLifecycleListener listener : new ArrayList<>(listeners)) {
      listener.onEngineWillDestroy();
    }
    assertNull(fakeBridge.editingStateListener);
    assertFalse(plugin2.isSyncModeActive());
  }

  @Test
  public void programmaticEditingStateNotification_handlesStaleSelectionAndRestartCases() {
    fakeBridge.setClientState(1, "hello", 5, 5, -1, -1);
    final TextInputPlugin plugin = createPlugin();
    plugin.setTextInputClient(1, plainConfig(false));
    final FlutterInputConnection conn =
        (FlutterInputConnection)
            plugin.createInputConnection(view, keyboardManager, new EditorInfo());
    assertNotNull(conn);
    imm.resetRecording();

    final FlutterJNI.SyncTextInputEditingStateListener listener =
        plugin.getSyncEditingStateListenerForTesting();

    // Stale client ID returns false.
    assertFalse(listener.onSyncTextInputEditingState(999, 0, 0, 0, -1, -1));

    // Selection change -> updateSelection, no restartInput.
    assertTrue(listener.onSyncTextInputEditingState(1, 0, 2, 4, -1, -1));
    assertEquals(1, imm.selectionUpdates.size());
    assertEquals("2,4,-1,-1", imm.selectionUpdates.get(0));
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(0, imm.restartCount);

    // Text changed with unchanged selection -> updateSelection without disruptive restartInput.
    imm.resetRecording();
    assertTrue(
        listener.onSyncTextInputEditingState(
            1, SyncTextInputProtocol.NOTIFY_FLAG_TEXT_CHANGED, 2, 4, -1, -1));
    assertEquals(1, imm.selectionUpdates.size());
    assertEquals("2,4,-1,-1", imm.selectionUpdates.get(0));
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(0, imm.restartCount);

    // Composing changed -> posts deferred restartInput.
    imm.resetRecording();
    assertTrue(
        listener.onSyncTextInputEditingState(
            1, SyncTextInputProtocol.NOTIFY_FLAG_COMPOSING_CHANGED, 0, 0, 0, 3));
    assertEquals(0, imm.restartCount);
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(1, imm.restartCount);
  }

  @Test
  public void channelSetEditingStateRightAfterSetClient_doesNotDoubleRestart() {
    fakeBridge.setClientState(1, "hello", 5, 5, -1, -1);
    final TextInputPlugin plugin = createPlugin();
    plugin.setTextInputClient(1, plainConfig(false));
    plugin.createInputConnection(view, keyboardManager, new EditorInfo());
    assertEquals(1, imm.restartCount);

    // Initial setEditingState arriving over the channel after setClient must not trigger a second
    // restartInput.
    plugin.setTextInputEditingState(
        view, new TextInputChannel.TextEditState("hello", 5, 5, -1, -1));
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(1, imm.restartCount);
  }

  @Test
  public void leakedBatchAndComposingAcrossCreateInputConnection_unwindsCleanly() {
    fakeBridge.setClientState(1, "hello", 5, 5, -1, -1);
    final TextInputPlugin plugin = createPlugin();
    plugin.setTextInputClient(1, plainConfig(false));
    final FlutterInputConnection ic1 =
        (FlutterInputConnection)
            plugin.createInputConnection(view, keyboardManager, new EditorInfo());
    assertNotNull(ic1);

    // Open a batch and set composing text on ic1 without closing it.
    assertTrue(ic1.beginBatchEdit());
    assertTrue(ic1.setComposingText(" world", 1));
    assertEquals(1, fakeBridge.batchDepth);
    assertEquals(5, fakeBridge.compStart);
    assertEquals(11, fakeBridge.compEnd);

    // Android calls createInputConnection directly (e.g. after restartInput) without calling
    // ic1.closeConnection(). Plugin must send OP_CLOSE_CONNECTION, commit the leaked batch, and
    // clear composing before creating ic2.
    fakeBridge.recordedOpcodes.clear();
    final EditorInfo attrs2 = new EditorInfo();
    final FlutterInputConnection ic2 =
        (FlutterInputConnection) plugin.createInputConnection(view, keyboardManager, attrs2);
    assertNotNull(ic2);
    assertTrue(ic1.isClosed());
    assertEquals(1, fakeBridge.countOpcode(SyncTextInputProtocol.OP_CLOSE_CONNECTION));
    assertEquals(0, fakeBridge.batchDepth);
    assertEquals("hello world", fakeBridge.text);
    assertEquals(-1, fakeBridge.compStart);
    assertEquals(-1, fakeBridge.compEnd);
    assertEquals(11, attrs2.initialSelStart);
    assertEquals(11, attrs2.initialSelEnd);

    // Fresh batch on ic2 commits normally.
    assertTrue(ic2.beginBatchEdit());
    assertTrue(ic2.commitText("!", 1));
    assertFalse(ic2.endBatchEdit());
    assertEquals("hello world!", fakeBridge.text);
  }

  @Test
  public void inCallbackProgrammaticNotification_notOverwrittenByOuterMutationResponse() {
    fakeBridge.setClientState(1, "ab", 2, 2, -1, -1);
    final TextInputPlugin plugin = createPlugin();
    plugin.setTextInputClient(1, plainConfig(false));
    final FlutterInputConnection conn =
        (FlutterInputConnection)
            plugin.createInputConnection(view, keyboardManager, new EditorInfo());
    assertNotNull(conn);
    imm.resetRecording();

    // Simulate a microtask / in-callback programmatic update firing inside commitText:
    // commitText itself sees post-commit selection (3, 3), then a flushed microtask calls
    // onSyncTextInputEditingState with (10, 10). The outer commitText response must NOT clobber
    // (10, 10) back to (3, 3).
    fakeBridge.interceptor =
        (opcode, clientId, request) -> {
          if (opcode == SyncTextInputProtocol.OP_COMMIT_TEXT) {
            plugin
                .getSyncEditingStateListenerForTesting()
                .onSyncTextInputEditingState(1, 0, 10, 10, -1, -1);
          }
          return null;
        };

    assertTrue(conn.commitText("c", 1));
    assertEquals(1, imm.selectionUpdates.size());
    assertEquals("10,10,-1,-1", imm.selectionUpdates.get(0));
  }

  @Test
  public void staleChannelSetEditingStateInSyncMode_requeriesLiveStateAndDoesNotRollBack() {
    fakeBridge.setClientState(1, "hello", 5, 5, -1, -1);
    final TextInputPlugin plugin = createPlugin();
    plugin.setTextInputClient(1, plainConfig(false));
    final FlutterInputConnection conn =
        (FlutterInputConnection)
            plugin.createInputConnection(view, keyboardManager, new EditorInfo());
    assertNotNull(conn);

    // Sync edit advances live state to "hello!" (6, 6).
    assertTrue(conn.commitText("!", 1));
    imm.resetRecording();

    // Stale async channel setTextInputEditingState arrives with older ("hello", 5, 5).
    // Sync mode must ignore the stale channel payload and re-query live state (6, 6) without
    // rolling IMM back to (5, 5) or calling restartInput.
    plugin.setTextInputEditingState(
        view, new TextInputChannel.TextEditState("hello", 5, 5, -1, -1));
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(0, imm.selectionUpdates.size());
    assertEquals(0, imm.restartCount);
  }

  @Test
  public void setComposingRegionNonDivergedAndNoOpMutation_skipRedundantUpdateSelection() {
    fakeBridge.setClientState(1, "hello", 5, 5, -1, -1);
    final TextInputPlugin plugin = createPlugin();
    plugin.setTextInputClient(1, plainConfig(false));
    final FlutterInputConnection conn =
        (FlutterInputConnection)
            plugin.createInputConnection(view, keyboardManager, new EditorInfo());
    assertNotNull(conn);

    conn.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR);
    imm.resetRecording();
    fakeBridge.recordedOpcodes.clear();

    // Non-diverged setComposingRegion must NOT echo updateSelection back to IMM, yet must still
    // notify active CursorAnchorInfo monitors using OP_GET_CURSOR_ANCHOR_STATE.
    assertTrue(conn.setComposingRegion(0, 5));
    assertEquals(0, imm.selectionUpdates.size());
    assertEquals(0, imm.restartCount);
    assertEquals(1, fakeBridge.countOpcode(SyncTextInputProtocol.OP_GET_CURSOR_ANCHOR_STATE));
    assertEquals(0, fakeBridge.countOpcode(SyncTextInputProtocol.OP_GET_FULL_STATE));
    assertNotNull(imm.lastCursorAnchorInfo);
    assertEquals("hello", imm.lastCursorAnchorInfo.getComposingText().toString());

    // Pure no-op setSelection(5, 5) must NOT trigger updateSelection or cursor/extracted queries.
    imm.resetRecording();
    fakeBridge.recordedOpcodes.clear();
    assertTrue(conn.setSelection(5, 5));
    assertEquals(0, imm.selectionUpdates.size());
    assertEquals(0, fakeBridge.countOpcode(SyncTextInputProtocol.OP_GET_CURSOR_ANCHOR_STATE));
    assertEquals(0, fakeBridge.countOpcode(SyncTextInputProtocol.OP_GET_FULL_STATE));
  }

  @Test
  public void killSwitchDisabled_preEngineRestartDoesNotTouchLegacyState() {
    fakeBridge.enabled = false;
    final TextInputPlugin legacyPlugin = createPlugin();
    legacyPlugin.setTextInputClient(7, plainConfig(false));
    imm.resetRecording();

    // With sync mode never enabled, onPreEngineRestart must be a no-op on legacy state.
    legacyPlugin.getEngineLifecycleListenerForTesting().onPreEngineRestart();
    assertEquals(0, imm.restartCount);
    assertNotNull(legacyPlugin.createInputConnection(view, keyboardManager, new EditorInfo()));
  }

  @Test
  public void platformViews_virtualAndPhysicalDisplayRoutingPreservedInSyncMode() {
    final TextInputPlugin plugin = createPlugin();
    final View pvView = mock(View.class);
    final InputConnection pvConnection = mock(InputConnection.class);
    when(platformViewsController.getPlatformViewById(7)).thenReturn(pvView);
    when(pvView.onCreateInputConnection(any())).thenReturn(pvConnection);

    // Activate via TextInputChannel handler (simulated via setTextInputClient first, then PV).
    plugin.setTextInputClient(1, plainConfig(false));
    final FlutterInputConnection syncConn =
        (FlutterInputConnection)
            plugin.createInputConnection(view, keyboardManager, new EditorInfo());
    assertNotNull(syncConn);

    // Switch to virtual-display platform view via channel method handler.
    final ArgumentCaptor<TextInputChannel.TextInputMethodHandler> handlerCaptor =
        ArgumentCaptor.forClass(TextInputChannel.TextInputMethodHandler.class);
    verify(textInputChannel).setTextInputMethodHandler(handlerCaptor.capture());
    handlerCaptor.getValue().setPlatformViewClient(7, true);
    assertTrue(syncConn.isClosed());

    final InputConnection ic1 =
        plugin.createInputConnection(view, keyboardManager, new EditorInfo());
    assertSame(pvConnection, ic1);

    // Locking input connection returns cached pvConnection without querying platform view again.
    plugin.lockPlatformViewInputConnection();
    final InputConnection ic2 =
        plugin.createInputConnection(view, keyboardManager, new EditorInfo());
    assertSame(pvConnection, ic2);
    verify(pvView, times(1)).onCreateInputConnection(any());

    plugin.unlockPlatformViewInputConnection();

    // Physical display platform view returns null from FlutterView.createInputConnection.
    handlerCaptor.getValue().setPlatformViewClient(8, false);
    assertNull(plugin.createInputConnection(view, keyboardManager, new EditorInfo()));
  }

  @Test
  public void autofillInSyncMode_queriesLiveStateAndDispatchesCurrentAndSiblingFields() {
    final TextInputChannel.Configuration sibling = autofillConfig("password_id", "old_pw", null);
    final TextInputChannel.Configuration current =
        autofillConfig("user_id", "initial_user", new TextInputChannel.Configuration[] {sibling});
    // Include current itself in fields list as Flutter does.
    final TextInputChannel.Configuration withFields =
        autofillConfig(
            "user_id", "initial_user", new TextInputChannel.Configuration[] {current, sibling});

    fakeBridge.setClientState(1, "live_typed_user", 15, 15, -1, -1);
    final TextInputPlugin plugin = createPlugin();
    plugin.setTextInputClient(1, withFields);

    // Set non-null lastClientRect via setEditableSizeAndTransform.
    final ArgumentCaptor<TextInputChannel.TextInputMethodHandler> handlerCaptor =
        ArgumentCaptor.forClass(TextInputChannel.TextInputMethodHandler.class);
    verify(textInputChannel).setTextInputMethodHandler(handlerCaptor.capture());
    handlerCaptor
        .getValue()
        .setEditableSizeAndTransform(
            100.0,
            40.0,
            new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1});

    // onProvideAutofillVirtualStructure must read live Dart text via OP_GET_FULL_STATE.
    final ViewStructure parent = mock(ViewStructure.class);
    final ViewStructure child0 = mock(ViewStructure.class);
    final ViewStructure child1 = mock(ViewStructure.class);
    when(parent.getAutofillId()).thenReturn(mock(AutofillId.class));
    when(parent.newChild(0)).thenReturn(child0);
    when(parent.newChild(1)).thenReturn(child1);

    plugin.onProvideAutofillVirtualStructure(parent, 0);
    assertTrue(fakeBridge.countOpcode(SyncTextInputProtocol.OP_GET_FULL_STATE) >= 1);

    final ArgumentCaptor<AutofillValue> valCaptor0 = ArgumentCaptor.forClass(AutofillValue.class);
    final ArgumentCaptor<AutofillValue> valCaptor1 = ArgumentCaptor.forClass(AutofillValue.class);
    verify(child0).setAutofillValue(valCaptor0.capture());
    verify(child1).setAutofillValue(valCaptor1.capture());
    final String v0 = valCaptor0.getValue().getTextValue().toString();
    final String v1 = valCaptor1.getValue().getTextValue().toString();
    assertTrue(
        "Current field must report live Dart text",
        v0.equals("live_typed_user") || v1.equals("live_typed_user"));

    // 1. Autofill current client only -> sync OP_AUTOFILL_CURRENT_CLIENT, no empty
    // updateEditingStateWithTag.
    final SparseArray<AutofillValue> currentOnly = new SparseArray<>();
    currentOnly.put("user_id".hashCode(), AutofillValue.forText("alice"));
    plugin.autofill(currentOnly);
    assertEquals("alice", fakeBridge.text);
    assertEquals(1, fakeBridge.countOpcode(SyncTextInputProtocol.OP_AUTOFILL_CURRENT_CLIENT));
    verify(textInputChannel, never()).updateEditingStateWithTag(anyInt(), any());

    // 2. Autofill both current client and sibling field -> sync for current, tag map for sibling.
    final SparseArray<AutofillValue> both = new SparseArray<>();
    both.put("user_id".hashCode(), AutofillValue.forText("bob"));
    both.put("password_id".hashCode(), AutofillValue.forText("hunter2"));
    plugin.autofill(both);
    assertEquals("bob", fakeBridge.text);

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<HashMap<String, TextInputChannel.TextEditState>> mapCaptor =
        ArgumentCaptor.forClass(HashMap.class);
    verify(textInputChannel, times(1)).updateEditingStateWithTag(eq(1), mapCaptor.capture());
    assertEquals(1, mapCaptor.getValue().size());
    assertEquals("hunter2", mapCaptor.getValue().get("password_id").text);
  }

  @Test
  public void createInputConnection_microtaskComposingChangeDuringQueryInitialStateSchedulesRestart() {
    final TextInputPlugin plugin = createPlugin();
    fakeBridge.setClientState(1, "hello", 5, 5, 0, 5);
    plugin.setTextInputClient(1, plainConfig(false));
    imm.resetRecording();

    final boolean[] fired = new boolean[] {false};
    fakeBridge.interceptor =
        (opcode, clientId, req) -> {
          if (opcode == SyncTextInputProtocol.OP_QUERY_INITIAL_STATE && !fired[0]) {
            fired[0] = true;
            // Simulate an inline microtask flushed during OP_QUERY_INITIAL_STATE that sets a
            // composing span via FFI notification.
            plugin
                .getSyncEditingStateListenerForTesting()
                .onSyncTextInputEditingState(
                    clientId, SyncTextInputProtocol.NOTIFY_FLAG_COMPOSING_CHANGED, 5, 5, 0, 5);
          }
          return null;
        };

    final EditorInfo attrs = new EditorInfo();
    final InputConnection ic = plugin.createInputConnection(view, keyboardManager, attrs);
    assertTrue(ic instanceof FlutterInputConnection);
    assertEquals(0, imm.restartCount);

    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(
        "Composing-driven restart queued during queryInitialState must not be dropped",
        1,
        imm.restartCount);
  }

  @Test
  public void createInputConnection_microtaskSelectionUpdateDuringQueryInitialStateRefreshesEditorInfo() {
    final TextInputPlugin plugin = createPlugin();
    fakeBridge.setClientState(1, "hello", 2, 2, -1, -1);
    plugin.setTextInputClient(1, plainConfig(false));
    imm.resetRecording();

    final boolean[] fired = new boolean[] {false};
    fakeBridge.interceptor =
        (opcode, clientId, req) -> {
          if (opcode == SyncTextInputProtocol.OP_QUERY_INITIAL_STATE && !fired[0]) {
            fired[0] = true;
            // Simulate an inline microtask updating text/selection after the first initialState
            // snapshot was encoded: return the pre-microtask snapshot while updating fakeBridge to
            // the post-microtask state and invoking the FFI notify listener.
            final byte[] preMicrotask =
                FakeSyncTextInputBridge.encodeResponse(
                    SyncTextInputProtocol.STATUS_OK, 0, 2, 2, -1, -1, 0, true, "hello");
            fakeBridge.setClientState(1, "hello!", 6, 6, -1, -1);
            plugin
                .getSyncEditingStateListenerForTesting()
                .onSyncTextInputEditingState(
                    clientId, SyncTextInputProtocol.NOTIFY_FLAG_TEXT_CHANGED, 6, 6, -1, -1);
            return preMicrotask;
          }
          return null;
        };

    final EditorInfo attrs = new EditorInfo();
    final InputConnection ic = plugin.createInputConnection(view, keyboardManager, attrs);
    assertTrue(ic instanceof FlutterInputConnection);
    assertEquals(6, attrs.initialSelStart);
    assertEquals(6, attrs.initialSelEnd);
    assertEquals(2, fakeBridge.countOpcode(SyncTextInputProtocol.OP_QUERY_INITIAL_STATE));

    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(0, imm.restartCount);
    assertTrue(imm.selectionUpdates.isEmpty());
  }

  @Test
  public void enableSyncTextInputFlag_registeredPrivatelyWithExpectedDefaults() throws Exception {
    final Field flagField = FlutterEngineFlags.class.getDeclaredField("ENABLE_SYNC_TEXT_INPUT");
    assertTrue(
        "ENABLE_SYNC_TEXT_INPUT must stay private (no new public API)",
        Modifier.isPrivate(flagField.getModifiers()));
    final FlutterEngineFlags.Flag flag =
        FlutterEngineFlags.getFlagByEngineArgument("--enable-android-sync-text-input=");
    assertNotNull(flag);
    assertTrue(FlutterEngineFlags.ALL_FLAGS.contains(flag));
    assertEquals("io.flutter.embedding.android.EnableSyncTextInput", flag.metadataKey);
    assertEquals("--enable-android-sync-text-input=", flag.engineArgument);
    assertTrue(flag.allowedInRelease);
  }

  @Implements(InputMethodManager.class)
  public static class RecordingImm extends ShadowInputMethodManager {
    int restartCount = 0;
    int showSoftInputCount = 0;
    final List<String> selectionUpdates = new ArrayList<>();
    final List<ExtractedText> extractedUpdates = new ArrayList<>();
    @Nullable CursorAnchorInfo lastCursorAnchorInfo;

    void resetRecording() {
      restartCount = 0;
      showSoftInputCount = 0;
      selectionUpdates.clear();
      extractedUpdates.clear();
      lastCursorAnchorInfo = null;
    }

    @Implementation
    public void restartInput(View view) {
      restartCount++;
    }

    @Implementation
    public boolean showSoftInput(View view, int flags) {
      showSoftInputCount++;
      return true;
    }

    @Implementation
    public void updateSelection(
        View view, int selStart, int selEnd, int candidatesStart, int candidatesEnd) {
      selectionUpdates.add(
          selStart + "," + selEnd + "," + candidatesStart + "," + candidatesEnd);
    }

    @Implementation
    public void updateExtractedText(View view, int token, ExtractedText text) {
      extractedUpdates.add(text);
    }

    @Implementation
    public void updateCursorAnchorInfo(View view, CursorAnchorInfo info) {
      lastCursorAnchorInfo = info;
    }
  }

  @Implements(AutofillManager.class)
  public static class RecordingAfm extends ShadowAutofillManager {
    final List<String> valueChanges = new ArrayList<>();

    void resetRecording() {
      valueChanges.clear();
    }

    @Implementation
    public void notifyValueChanged(View view, int virtualId, AutofillValue value) {
      if (value != null && value.getTextValue() != null) {
        valueChanges.add(value.getTextValue().toString());
      }
    }
  }
}
