// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugin.editing;

import static io.flutter.Build.API_LEVELS;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.LocaleList;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewStructure;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.inputmethod.EditorInfoCompat;
import io.flutter.Log;
import io.flutter.embedding.android.KeyboardManager;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterJNI;
import io.flutter.embedding.engine.systemchannels.ScribeChannel;
import io.flutter.embedding.engine.systemchannels.TextInputChannel;
import io.flutter.embedding.engine.systemchannels.TextInputChannel.TextEditState;
import io.flutter.plugin.platform.PlatformViewsController;
import io.flutter.plugin.platform.PlatformViewsController2;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;

/** Android implementation of the text input plugin. */
public class TextInputPlugin implements ListenableEditingState.EditingStateWatcher {
  private static final String TAG = "TextInputPlugin";

  @NonNull private final View mView;
  @NonNull private final InputMethodManager mImm;
  @NonNull private final AutofillManager afm;
  @NonNull private final ScribeChannel scribeChannel;
  @NonNull private final TextInputChannel textInputChannel;
  @NonNull private final Handler mainHandler = new Handler(Looper.getMainLooper());
  @Nullable private final FlutterJNI lifecycleFlutterJNI;
  @Nullable private final SyncTextInputBridge syncBridge;
  @NonNull private final SyncTextInputProtocol.DirectBufferPool syncRequestBufferPool =
      new SyncTextInputProtocol.DirectBufferPool();
  @NonNull private InputTarget inputTarget = new InputTarget(InputTarget.Type.NO_TARGET, 0);
  @Nullable private TextInputChannel.Configuration configuration;
  @Nullable private SparseArray<TextInputChannel.Configuration> autofillConfiguration;
  @NonNull private ListenableEditingState mEditable;
  private boolean mRestartInputPending;
  @Nullable private InputConnection lastInputConnection;
  @Nullable private FlutterInputConnection activeSyncInputConnection;
  @NonNull private PlatformViewsController platformViewsController;
  @NonNull private PlatformViewsController2 platformViewsController2;
  @Nullable private Rect lastClientRect;
  private ImeSyncDeferringInsetsCallback imeSyncCallback;

  // Sync-mode lifecycle and reentrancy state.
  private boolean destroyed = false;
  private boolean syncModeEverActive = false;
  private int engineGeneration = 0;
  private int inImeCallbackDepth = 0;
  private long syncNotifySeq = 0;
  private boolean pendingSelectionUpdate = false;
  private boolean pendingMonitorUpdate = false;
  private int pendingSelStart = -1;
  private int pendingSelEnd = -1;
  private int pendingCompStart = -1;
  private int pendingCompEnd = -1;
  private boolean pendingTextChanged = false;
  private boolean pendingRestartInput = false;
  private int pendingRestartClientId = 0;
  private int lastReportedSelStart = Integer.MIN_VALUE;
  private int lastReportedSelEnd = Integer.MIN_VALUE;
  private int lastReportedCompStart = Integer.MIN_VALUE;
  private int lastReportedCompEnd = Integer.MIN_VALUE;

  // Initialize the "last seen" text editing values to a non-null value.
  private TextEditState mLastKnownFrameworkTextEditingState;

  // When true following calls to createInputConnection will return the cached lastInputConnection
  // if the input
  // target is a platform view. See the comments on lockPlatformViewInputConnection for more
  // details.
  private boolean isInputConnectionLocked;

  private final FlutterEngine.EngineLifecycleListener engineLifecycleListener =
      new FlutterEngine.EngineLifecycleListener() {
        @Override
        public void onPreEngineRestart() {
          handlePreEngineRestart();
        }

        @Override
        public void onEngineWillDestroy() {
          // Invoked from FlutterJNI.detachFromNativeAndReleaseResources() before native teardown.
          handleEngineWillDestroy();
        }
      };

  private final FlutterJNI.SyncTextInputEditingStateListener syncEditingStateListener =
      new FlutterJNI.SyncTextInputEditingStateListener() {
        @Override
        public boolean onSyncTextInputEditingState(
            int clientId,
            int flags,
            int selectionStart,
            int selectionEnd,
            int composingStart,
            int composingEnd) {
          return handleSyncTextInputEditingState(
              clientId, flags, selectionStart, selectionEnd, composingStart, composingEnd);
        }
      };

  private final FlutterInputConnection.Host syncInputConnectionHost =
      new FlutterInputConnection.Host() {
        @Override
        public boolean isConnectionActive(@NonNull FlutterInputConnection connection) {
          return !destroyed
              && activeSyncInputConnection == connection
              && engineGeneration == connection.getEngineGeneration()
              && inputTarget.type == InputTarget.Type.FRAMEWORK_CLIENT
              && inputTarget.id == connection.getClientId();
        }

        @Override
        public boolean isSyncModeActive() {
          return TextInputPlugin.this.isSyncModeActive();
        }

        @Override
        public void enterImeCallback() {
          TextInputPlugin.this.enterImeCallback();
        }

        @Override
        public void exitImeCallback() {
          TextInputPlugin.this.exitImeCallback();
        }

        @Override
        public long getSyncNotifySeq() {
          return syncNotifySeq;
        }

        @NonNull
        @Override
        public SyncTextInputProtocol.DirectBufferPool getRequestBufferPool() {
          return syncRequestBufferPool;
        }

        @Nullable
        @Override
        public SyncTextInputProtocol.Response dispatchSyncRequest(
            @NonNull ByteBuffer buffer, int length) {
          return TextInputPlugin.this.dispatchSyncRequest(buffer, length);
        }

        @Override
        public void onMutationResponse(
            @NonNull FlutterInputConnection connection,
            @NonNull SyncTextInputProtocol.Response response,
            long notifySeqBefore,
            boolean suppressSelectionUpdate) {
          TextInputPlugin.this.handleSyncMutationResponse(
              connection, response, notifySeqBefore, suppressSelectionUpdate);
        }

        @Override
        public void onConnectionClosed(@NonNull FlutterInputConnection connection) {
          if (activeSyncInputConnection == connection) {
            activeSyncInputConnection = null;
          }
        }
      };

  @SuppressLint("NewApi")
  public TextInputPlugin(
      @NonNull View view,
      @NonNull TextInputChannel textInputChannel,
      @NonNull ScribeChannel scribeChannel,
      @NonNull PlatformViewsController platformViewsController,
      @NonNull PlatformViewsController2 platformViewsController2) {
    this(
        view,
        textInputChannel,
        scribeChannel,
        platformViewsController,
        platformViewsController2,
        createSyncBridge(textInputChannel));
  }

  @VisibleForTesting
  @SuppressLint("NewApi")
  TextInputPlugin(
      @NonNull View view,
      @NonNull TextInputChannel textInputChannel,
      @NonNull ScribeChannel scribeChannel,
      @NonNull PlatformViewsController platformViewsController,
      @NonNull PlatformViewsController2 platformViewsController2,
      @Nullable SyncTextInputBridge syncBridge) {
    mView = view;
    this.syncBridge = syncBridge;
    final FlutterJNI channelJni = textInputChannel.getFlutterJNI();
    if (channelJni != null) {
      this.lifecycleFlutterJNI = channelJni;
      channelJni.addEngineLifecycleListener(engineLifecycleListener);
    } else {
      this.lifecycleFlutterJNI = null;
    }
    if (syncBridge != null) {
      syncBridge.setEditingStateListener(syncEditingStateListener);
    }

    // Create a default object.
    mEditable = new ListenableEditingState(null, mView);
    mImm = (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    if (Build.VERSION.SDK_INT >= API_LEVELS.API_26) {
      afm = view.getContext().getSystemService(AutofillManager.class);
    } else {
      afm = null;
    }

    // Sets up syncing ime insets with the framework, allowing
    // the Flutter view to grow and shrink to accommodate Android
    // controlled keyboard animations.
    if (Build.VERSION.SDK_INT >= API_LEVELS.API_30) {
      imeSyncCallback = new ImeSyncDeferringInsetsCallback(view);
      imeSyncCallback.install();

      // When the IME is hidden, we need to restart the input method manager to accomodate
      // some keyboards like the Samsung keyboard that may be caching old state.
      imeSyncCallback.setImeVisibilityListener(
          new ImeSyncDeferringInsetsCallback.ImeVisibilityListener() {
            @Override
            public void onImeVisibilityChanged(boolean visible) {
              if (!visible) {
                if (inImeCallbackDepth > 0) {
                  postIfSameGeneration(() -> mImm.restartInput(mView));
                } else {
                  mImm.restartInput(mView);
                }
              }
            }
          });
    }

    this.textInputChannel = textInputChannel;
    textInputChannel.setTextInputMethodHandler(
        new TextInputChannel.TextInputMethodHandler() {
          @Override
          public void show() {
            showTextInput(mView);
          }

          @Override
          public void hide() {
            if (inputTarget.type == InputTarget.Type.PHYSICAL_DISPLAY_PLATFORM_VIEW) {
              notifyViewExited();
            } else {
              hideTextInput(mView);
            }
          }

          @Override
          public void requestAutofill() {
            notifyViewEntered();
          }

          @Override
          public void finishAutofillContext(boolean shouldSave) {
            if (Build.VERSION.SDK_INT < API_LEVELS.API_26 || afm == null) {
              return;
            }
            if (shouldSave) {
              afm.commit();
            } else {
              afm.cancel();
            }
          }

          @Override
          public void setClient(
              int textInputClientId, TextInputChannel.Configuration configuration) {
            setTextInputClient(textInputClientId, configuration);
          }

          @Override
          public void setPlatformViewClient(int platformViewId, boolean usesVirtualDisplay) {
            setPlatformViewTextInputClient(platformViewId, usesVirtualDisplay);
          }

          @Override
          public void setEditingState(TextInputChannel.TextEditState editingState) {
            setTextInputEditingState(mView, editingState);
          }

          @Override
          public void setEditableSizeAndTransform(double width, double height, double[] transform) {
            saveEditableSizeAndTransform(width, height, transform);
          }

          @Override
          public void clearClient() {
            clearTextInputClient();
          }

          @Override
          public void sendAppPrivateCommand(String action, Bundle data) {
            sendTextInputAppPrivateCommand(action, data);
          }
        });

    textInputChannel.requestExistingInputState();

    this.scribeChannel = scribeChannel;

    this.platformViewsController = platformViewsController;
    this.platformViewsController.attachTextInputPlugin(this);
    this.platformViewsController2 = platformViewsController2;
    this.platformViewsController2.attachTextInputPlugin(this);
  }

  @NonNull
  public InputMethodManager getInputMethodManager() {
    return mImm;
  }

  @VisibleForTesting
  Editable getEditable() {
    return mEditable;
  }

  @VisibleForTesting
  ImeSyncDeferringInsetsCallback getImeSyncCallback() {
    return imeSyncCallback;
  }

  /**
   * Use the current platform view input connection until unlockPlatformViewInputConnection is
   * called.
   *
   * <p>The current input connection instance is cached and any following call to @{link
   * createInputConnection} returns the cached connection until unlockPlatformViewInputConnection is
   * called.
   *
   * <p>This is a no-op if the current input target isn't a platform view.
   *
   * <p>This is used to preserve an input connection when moving a platform view from one virtual
   * display to another.
   */
  public void lockPlatformViewInputConnection() {
    if (inputTarget.type == InputTarget.Type.VIRTUAL_DISPLAY_PLATFORM_VIEW) {
      isInputConnectionLocked = true;
    }
  }

  /**
   * Unlocks the input connection.
   *
   * <p>See also: @{link lockPlatformViewInputConnection}.
   */
  public void unlockPlatformViewInputConnection() {
    if (inputTarget.type == InputTarget.Type.VIRTUAL_DISPLAY_PLATFORM_VIEW) {
      isInputConnectionLocked = false;
    }
  }

  @Nullable
  private static SyncTextInputBridge createSyncBridge(@NonNull TextInputChannel textInputChannel) {
    final FlutterJNI jni = textInputChannel.getFlutterJNI();
    return jni != null ? new SyncTextInputBridge.FlutterJNIBridge(jni) : null;
  }

  boolean isSyncModeActive() {
    final boolean active = !destroyed && syncBridge != null && syncBridge.isSyncTextInputEnabled();
    if (active) {
      syncModeEverActive = true;
    }
    return active;
  }

  @VisibleForTesting
  FlutterEngine.EngineLifecycleListener getEngineLifecycleListenerForTesting() {
    return engineLifecycleListener;
  }

  @VisibleForTesting
  FlutterJNI.SyncTextInputEditingStateListener getSyncEditingStateListenerForTesting() {
    return syncEditingStateListener;
  }

  private void closeActiveSyncConnection(boolean notifyDart) {
    final FlutterInputConnection conn = activeSyncInputConnection;
    if (conn == null) {
      return;
    }
    activeSyncInputConnection = null;
    if (notifyDart && !conn.isClosed() && isSyncModeActive()) {
      enterImeCallback();
      try {
        final ByteBuffer buf =
            syncRequestBufferPool.acquire(SyncTextInputProtocol.REQUEST_HEADER_BYTES);
        try {
          SyncTextInputProtocol.putHeader(
              buf, SyncTextInputProtocol.OP_CLOSE_CONNECTION, conn.getClientId());
          final SyncTextInputProtocol.Response response = dispatchSyncRequest(buf, buf.position());
          if (response != null && response.isOk() && response.isCommitted()) {
            pendingTextChanged |= response.isTextChanged();
          }
        } finally {
          syncRequestBufferPool.release(buf);
        }
      } finally {
        conn.markClosedLocally();
        exitImeCallback();
      }
    } else {
      conn.markClosedLocally();
    }
  }

  private void clearPendingImeUpdates() {
    pendingSelectionUpdate = false;
    pendingMonitorUpdate = false;
    pendingSelStart = -1;
    pendingSelEnd = -1;
    pendingCompStart = -1;
    pendingCompEnd = -1;
    pendingTextChanged = false;
    pendingRestartInput = false;
    pendingRestartClientId = 0;
  }

  private void resetLastReportedSelection() {
    lastReportedSelStart = Integer.MIN_VALUE;
    lastReportedSelEnd = Integer.MIN_VALUE;
    lastReportedCompStart = Integer.MIN_VALUE;
    lastReportedCompEnd = Integer.MIN_VALUE;
  }

  private void updateLastReportedSelection(
      int selStart, int selEnd, int compStart, int compEnd) {
    lastReportedSelStart = selStart;
    lastReportedSelEnd = selEnd;
    if (compStart >= 0 && compEnd >= 0 && compStart != compEnd) {
      lastReportedCompStart = Math.min(compStart, compEnd);
      lastReportedCompEnd = Math.max(compStart, compEnd);
    } else {
      lastReportedCompStart = -1;
      lastReportedCompEnd = -1;
    }
  }

  private void postIfSameGeneration(@NonNull Runnable action) {
    final int gen = engineGeneration;
    mainHandler.post(
        () -> {
          if (!destroyed && engineGeneration == gen) {
            action.run();
          }
        });
  }

  private void postRestartInputForClient(int clientId) {
    final int gen = engineGeneration;
    mainHandler.post(
        () -> {
          if (!destroyed
              && engineGeneration == gen
              && inputTarget.type == InputTarget.Type.FRAMEWORK_CLIENT
              && inputTarget.id == clientId) {
            mImm.restartInput(mView);
          }
        });
  }

  private void enterImeCallback() {
    inImeCallbackDepth++;
  }

  private void exitImeCallback() {
    if (inImeCallbackDepth <= 0) {
      return;
    }
    inImeCallbackDepth--;
    if (inImeCallbackDepth == 0) {
      flushPendingImeUpdates();
    }
  }

  @Nullable
  private SyncTextInputProtocol.Response dispatchSyncRequest(
      @NonNull ByteBuffer buffer, int length) {
    if (destroyed || syncBridge == null) {
      return null;
    }
    final byte[] raw = syncBridge.dispatchSyncTextInput(buffer, length);
    return SyncTextInputProtocol.decodeResponse(raw);
  }

  @Nullable
  private SyncTextInputProtocol.Response queryInitialState(int clientId) {
    final ByteBuffer buf =
        syncRequestBufferPool.acquire(SyncTextInputProtocol.REQUEST_HEADER_BYTES);
    try {
      SyncTextInputProtocol.putHeader(buf, SyncTextInputProtocol.OP_QUERY_INITIAL_STATE, clientId);
      return dispatchSyncRequest(buf, buf.position());
    } finally {
      syncRequestBufferPool.release(buf);
    }
  }

  @Nullable
  private SyncTextInputProtocol.Response queryFullState(int clientId) {
    final ByteBuffer buf =
        syncRequestBufferPool.acquire(SyncTextInputProtocol.REQUEST_HEADER_BYTES);
    try {
      SyncTextInputProtocol.putHeader(buf, SyncTextInputProtocol.OP_GET_FULL_STATE, clientId);
      return dispatchSyncRequest(buf, buf.position());
    } finally {
      syncRequestBufferPool.release(buf);
    }
  }

  @Nullable
  private SyncTextInputProtocol.Response queryCursorAnchorState(int clientId) {
    final ByteBuffer buf =
        syncRequestBufferPool.acquire(SyncTextInputProtocol.REQUEST_HEADER_BYTES);
    try {
      SyncTextInputProtocol.putHeader(
          buf, SyncTextInputProtocol.OP_GET_CURSOR_ANCHOR_STATE, clientId);
      return dispatchSyncRequest(buf, buf.position());
    } finally {
      syncRequestBufferPool.release(buf);
    }
  }

  @Nullable
  private SyncTextInputProtocol.Response dispatchAutofillCurrentClient(
      int clientId, @NonNull String text) {
    final int strBytes = SyncTextInputProtocol.encodedStringBytes(text);
    if (strBytes < 0) {
      return null;
    }
    final ByteBuffer buf =
        syncRequestBufferPool.acquire(SyncTextInputProtocol.REQUEST_HEADER_BYTES + strBytes);
    try {
      SyncTextInputProtocol.putHeader(
          buf, SyncTextInputProtocol.OP_AUTOFILL_CURRENT_CLIENT, clientId);
      SyncTextInputProtocol.putUtf16String(buf, text);
      return dispatchSyncRequest(buf, buf.position());
    } finally {
      syncRequestBufferPool.release(buf);
    }
  }

  private void handleSyncMutationResponse(
      @Nullable FlutterInputConnection connection,
      @NonNull SyncTextInputProtocol.Response response,
      long notifySeqBefore,
      boolean suppressSelectionUpdate) {
    if (!response.isCommitted() || response.isBatchActive()) {
      return;
    }
    if (response.isClientChanged()) {
      // The framework switched focus or cleared the client inside the edit callback; the
      // incoming setClient / clearClient message will reset the IMM cleanly.
      return;
    }
    if (!response.isStateChanged()
        && !response.isDiverged()
        && !response.isComposingChangedByFramework()) {
      return;
    }

    pendingTextChanged |= response.isTextChanged();

    if (response.isComposingChangedByFramework()) {
      pendingRestartInput = true;
      pendingRestartClientId =
          connection != null ? connection.getClientId() : inputTarget.id;
    } else if (!suppressSelectionUpdate || response.isDiverged()) {
      if (syncNotifySeq == notifySeqBefore) {
        pendingSelectionUpdate = true;
        pendingSelStart = response.selectionStart;
        pendingSelEnd = response.selectionEnd;
        pendingCompStart = response.composingStart;
        pendingCompEnd = response.composingEnd;
      }
    } else {
      pendingMonitorUpdate = true;
    }
  }

  private void flushPendingImeUpdates() {
    final boolean restartInput = pendingRestartInput;
    final int restartClientId = pendingRestartClientId;
    final boolean selectionUpdate = pendingSelectionUpdate;
    final boolean monitorUpdate = pendingMonitorUpdate;
    final int selStart = pendingSelStart;
    final int selEnd = pendingSelEnd;
    final int compStart = pendingCompStart;
    final int compEnd = pendingCompEnd;
    final boolean textChanged = pendingTextChanged;

    clearPendingImeUpdates();

    if (destroyed
        || inputTarget.type != InputTarget.Type.FRAMEWORK_CLIENT
        || (!restartInput && !selectionUpdate && !textChanged && !monitorUpdate)) {
      return;
    }

    final FlutterInputConnection activeConn = activeSyncInputConnection;
    final boolean needAutofill = textChanged && needsAutofill();
    final boolean needExtracted =
        !restartInput && activeConn != null && activeConn.isMonitoringExtractedText();
    final boolean needCursor =
        !restartInput && activeConn != null && activeConn.isMonitoringCursorUpdates();

    SyncTextInputProtocol.Response fullState = null;
    SyncTextInputProtocol.Response cursorAnchorState = null;
    if (needAutofill || needExtracted) {
      fullState = queryFullState(inputTarget.id);
    } else if (needCursor) {
      cursorAnchorState = queryCursorAnchorState(inputTarget.id);
    }

    if (needAutofill && fullState != null && fullState.isOk() && fullState.text != null) {
      notifyValueChanged(fullState.text);
    }

    if (activeConn == null) {
      return;
    }

    if (restartInput) {
      postRestartInputForClient(restartClientId);
      return;
    }

    if (selectionUpdate) {
      updateLastReportedSelection(selStart, selEnd, compStart, compEnd);
      mImm.updateSelection(mView, selStart, selEnd, compStart, compEnd);
    }

    if (fullState != null && fullState.isOk()) {
      if (needExtracted) {
        activeConn.sendExtractedTextUpdate(fullState);
      }
      if (needCursor) {
        activeConn.sendCursorAnchorUpdate(fullState, /*textIsFullDocument=*/ true);
      }
    } else if (needCursor && cursorAnchorState != null && cursorAnchorState.isOk()) {
      activeConn.sendCursorAnchorUpdate(cursorAnchorState, /*textIsFullDocument=*/ false);
    }
  }

  private boolean handleSyncTextInputEditingState(
      int clientId,
      int flags,
      int selectionStart,
      int selectionEnd,
      int composingStart,
      int composingEnd) {
    if (!isSyncModeActive()
        || inputTarget.type != InputTarget.Type.FRAMEWORK_CLIENT
        || inputTarget.id != clientId) {
      return false;
    }

    syncNotifySeq++;
    final boolean textChanged = (flags & SyncTextInputProtocol.NOTIFY_FLAG_TEXT_CHANGED) != 0;
    final boolean composingChanged =
        (flags & SyncTextInputProtocol.NOTIFY_FLAG_COMPOSING_CHANGED) != 0;
    final boolean needsRestart = composingChanged;

    if (inImeCallbackDepth > 0) {
      pendingTextChanged |= textChanged;
      if (needsRestart) {
        pendingRestartInput = true;
        pendingRestartClientId = clientId;
      } else {
        pendingSelectionUpdate = true;
        pendingSelStart = selectionStart;
        pendingSelEnd = selectionEnd;
        pendingCompStart = composingStart;
        pendingCompEnd = composingEnd;
      }
      return true;
    }

    final FlutterInputConnection activeConn = activeSyncInputConnection;
    final boolean needAutofill = textChanged && needsAutofill();
    final boolean needExtracted =
        !needsRestart && activeConn != null && activeConn.isMonitoringExtractedText();
    final boolean needCursor =
        !needsRestart && activeConn != null && activeConn.isMonitoringCursorUpdates();

    SyncTextInputProtocol.Response fullState = null;
    SyncTextInputProtocol.Response cursorAnchorState = null;
    if (needAutofill || needExtracted) {
      fullState = queryFullState(clientId);
    } else if (needCursor) {
      cursorAnchorState = queryCursorAnchorState(clientId);
    }

    if (needAutofill && fullState != null && fullState.isOk() && fullState.text != null) {
      notifyValueChanged(fullState.text);
    }

    if (activeConn == null) {
      return true;
    }

    if (needsRestart) {
      Log.i(TAG, "Composing region changed by the framework. Restarting the input method.");
      postRestartInputForClient(clientId);
      return true;
    }

    updateLastReportedSelection(selectionStart, selectionEnd, composingStart, composingEnd);
    mImm.updateSelection(mView, selectionStart, selectionEnd, composingStart, composingEnd);

    if (fullState != null && fullState.isOk()) {
      if (needExtracted) {
        activeConn.sendExtractedTextUpdate(fullState);
      }
      if (needCursor) {
        activeConn.sendCursorAnchorUpdate(fullState, /*textIsFullDocument=*/ true);
      }
    } else if (needCursor && cursorAnchorState != null && cursorAnchorState.isOk()) {
      activeConn.sendCursorAnchorUpdate(cursorAnchorState, /*textIsFullDocument=*/ false);
    }
    return true;
  }

  private void handlePreEngineRestart() {
    engineGeneration++;
    mainHandler.removeCallbacksAndMessages(null);
    if (!syncModeEverActive && activeSyncInputConnection == null) {
      return;
    }
    closeActiveSyncConnection(/*notifyDart=*/ false);
    clearPendingImeUpdates();
    resetLastReportedSelection();
    mEditable.removeEditingStateListener(this);
    notifyViewExited();
    configuration = null;
    updateAutofillConfigurationIfNeeded(null);
    inputTarget = new InputTarget(InputTarget.Type.NO_TARGET, 0);
    lastInputConnection = null;
    mLastKnownFrameworkTextEditingState = null;
    mRestartInputPending = false;
    unlockPlatformViewInputConnection();
    lastClientRect = null;
    if (inImeCallbackDepth > 0) {
      postIfSameGeneration(() -> mImm.restartInput(mView));
    } else {
      mImm.restartInput(mView);
    }
  }

  private void handleEngineWillDestroy() {
    destroyed = true;
    engineGeneration++;
    mainHandler.removeCallbacksAndMessages(null);
    if (syncBridge != null) {
      syncBridge.clearEditingStateListener(syncEditingStateListener);
    }
    closeActiveSyncConnection(/*notifyDart=*/ false);
    clearPendingImeUpdates();
    resetLastReportedSelection();
  }

  /**
   * Detaches the text input plugin from the platform views controller.
   *
   * <p>The TextInputPlugin instance should not be used after calling this.
   */
  @SuppressLint("NewApi")
  public void destroy() {
    handleEngineWillDestroy();
    if (lifecycleFlutterJNI != null) {
      lifecycleFlutterJNI.removeEngineLifecycleListener(engineLifecycleListener);
    }
    platformViewsController.detachTextInputPlugin();
    platformViewsController2.detachTextInputPlugin();
    textInputChannel.setTextInputMethodHandler(null);
    notifyViewExited();
    mEditable.removeEditingStateListener(this);
    if (imeSyncCallback != null) {
      imeSyncCallback.remove();
    }
  }

  private static int inputTypeFromTextInputType(
      TextInputChannel.InputType type,
      boolean obscureText,
      boolean autocorrect,
      boolean enableSuggestions,
      boolean enableIMEPersonalizedLearning,
      TextInputChannel.TextCapitalization textCapitalization) {
    if (type.type == TextInputChannel.TextInputType.DATETIME) {
      return InputType.TYPE_CLASS_DATETIME;
    } else if (type.type == TextInputChannel.TextInputType.NUMBER) {
      int textType = InputType.TYPE_CLASS_NUMBER;
      if (type.isSigned) {
        textType |= InputType.TYPE_NUMBER_FLAG_SIGNED;
      }
      if (type.isDecimal) {
        textType |= InputType.TYPE_NUMBER_FLAG_DECIMAL;
      }
      if (type.isPassword) {
        textType |= InputType.TYPE_NUMBER_VARIATION_PASSWORD;
      }
      return textType;
    } else if (type.type == TextInputChannel.TextInputType.PHONE) {
      return InputType.TYPE_CLASS_PHONE;
    } else if (type.type == TextInputChannel.TextInputType.NONE) {
      return InputType.TYPE_NULL;
    }

    int textType = InputType.TYPE_CLASS_TEXT;
    if (type.type == TextInputChannel.TextInputType.MULTILINE) {
      textType |= InputType.TYPE_TEXT_FLAG_MULTI_LINE;
    } else if (type.type == TextInputChannel.TextInputType.EMAIL_ADDRESS
        || type.type == TextInputChannel.TextInputType.TWITTER) {
      textType |= InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
    } else if (type.type == TextInputChannel.TextInputType.URL
        || type.type == TextInputChannel.TextInputType.WEB_SEARCH) {
      textType |= InputType.TYPE_TEXT_VARIATION_URI;
    } else if (type.type == TextInputChannel.TextInputType.VISIBLE_PASSWORD) {
      textType |= InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
    } else if (type.type == TextInputChannel.TextInputType.NAME) {
      textType |= InputType.TYPE_TEXT_VARIATION_PERSON_NAME;
    } else if (type.type == TextInputChannel.TextInputType.POSTAL_ADDRESS) {
      textType |= InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS;
    }

    if (obscureText) {
      // Note: both required. Some devices ignore TYPE_TEXT_FLAG_NO_SUGGESTIONS.
      textType |= InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
      textType |= InputType.TYPE_TEXT_VARIATION_PASSWORD;
    } else {
      if (autocorrect) textType |= InputType.TYPE_TEXT_FLAG_AUTO_CORRECT;
      if (!enableSuggestions) {
        // Note: both required. Some devices ignore TYPE_TEXT_FLAG_NO_SUGGESTIONS.
        textType |= InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        textType |= InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
      }
    }

    if (textCapitalization == TextInputChannel.TextCapitalization.CHARACTERS) {
      textType |= InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS;
    } else if (textCapitalization == TextInputChannel.TextCapitalization.WORDS) {
      textType |= InputType.TYPE_TEXT_FLAG_CAP_WORDS;
    } else if (textCapitalization == TextInputChannel.TextCapitalization.SENTENCES) {
      textType |= InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
    }

    return textType;
  }

  @Nullable
  public InputConnection createInputConnection(
      @NonNull View view, @NonNull KeyboardManager keyboardManager, @NonNull EditorInfo outAttrs) {
    if (inputTarget.type == InputTarget.Type.NO_TARGET) {
      closeActiveSyncConnection(/*notifyDart=*/ true);
      lastInputConnection = null;
      return null;
    }

    if (inputTarget.type == InputTarget.Type.PHYSICAL_DISPLAY_PLATFORM_VIEW) {
      return null;
    }

    if (inputTarget.type == InputTarget.Type.VIRTUAL_DISPLAY_PLATFORM_VIEW) {
      if (isInputConnectionLocked) {
        return lastInputConnection;
      }
      lastInputConnection =
          platformViewsController
              .getPlatformViewById(inputTarget.id)
              .onCreateInputConnection(outAttrs);
      return lastInputConnection;
    }

    outAttrs.inputType =
        inputTypeFromTextInputType(
            configuration.inputType,
            configuration.obscureText,
            configuration.autocorrect,
            configuration.enableSuggestions,
            configuration.enableIMEPersonalizedLearning,
            configuration.textCapitalization);
    outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN;

    if (Build.VERSION.SDK_INT >= API_LEVELS.API_26
        && !configuration.enableIMEPersonalizedLearning) {
      outAttrs.imeOptions |= EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
    }

    int enterAction;
    if (configuration.inputAction == null) {
      // If an explicit input action isn't set, then default to none for multi-line fields
      // and done for single line fields.
      enterAction =
          (InputType.TYPE_TEXT_FLAG_MULTI_LINE & outAttrs.inputType) != 0
              ? EditorInfo.IME_ACTION_NONE
              : EditorInfo.IME_ACTION_DONE;
    } else {
      enterAction = configuration.inputAction;
    }
    if (configuration.actionLabel != null) {
      outAttrs.actionLabel = configuration.actionLabel;
      outAttrs.actionId = enterAction;
    }
    outAttrs.imeOptions |= enterAction;

    if (configuration.hintLocales != null) {
      outAttrs.hintLocales = new LocaleList(configuration.hintLocales);
    }

    if (configuration.contentCommitMimeTypes != null) {
      String[] imgTypeString = configuration.contentCommitMimeTypes;
      EditorInfoCompat.setContentMimeTypes(outAttrs, imgTypeString);
    }

    if (Build.VERSION.SDK_INT >= API_LEVELS.API_34) {
      EditorInfoCompat.setStylusHandwritingEnabled(outAttrs, true);
    }
    // TODO(justinmc): Scribe stylus gestures should be supported here via
    // outAttrs.setSupportedHandwritingGestures and
    // outAttrs.setSupportedHandwritingGesturePreviews.
    // https://github.com/flutter/flutter/issues/156018

    if (isSyncModeActive()) {
      mEditable.removeEditingStateListener(this);
      final boolean obscureText =
          configuration.obscureText
              || (configuration.inputType != null && configuration.inputType.isPassword);
      SyncTextInputProtocol.Response initialState;
      boolean restartAfterCreate = false;
      int restartAfterCreateClientId = 0;
      enterImeCallback();
      try {
        closeActiveSyncConnection(/*notifyDart=*/ true);
        // Drop any stale selection/restart updates queued by closing the prior connection;
        // EditorInfo populated from queryInitialState below carries the authoritative state.
        pendingSelectionUpdate = false;
        pendingMonitorUpdate = false;
        pendingRestartInput = false;
        pendingRestartClientId = 0;
        final long notifySeqBeforeQuery = syncNotifySeq;
        initialState = queryInitialState(inputTarget.id);
        if (syncNotifySeq != notifySeqBeforeQuery) {
          // A microtask flushed during queryInitialState updated the framework value after
          // initialState was encoded. Capture any composing-driven restart so we post it once the
          // new InputConnection is installed (flushPendingImeUpdates exits early while
          // activeSyncInputConnection is null); otherwise do one bounded re-query so EditorInfo
          // carries the newer text/selection.
          if (pendingRestartInput) {
            restartAfterCreate = true;
            restartAfterCreateClientId = pendingRestartClientId;
            pendingRestartInput = false;
            pendingRestartClientId = 0;
          } else {
            final SyncTextInputProtocol.Response refreshed = queryInitialState(inputTarget.id);
            if (refreshed != null && refreshed.isOk()) {
              initialState = refreshed;
            }
          }
          pendingSelectionUpdate = false;
          pendingMonitorUpdate = false;
        }
      } finally {
        exitImeCallback();
      }
      if (initialState != null && initialState.isOk()) {
        outAttrs.initialSelStart = initialState.selectionStart;
        outAttrs.initialSelEnd = initialState.selectionEnd;
        updateLastReportedSelection(
            initialState.selectionStart,
            initialState.selectionEnd,
            initialState.composingStart,
            initialState.composingEnd);
        if (!obscureText
            && initialState.hasText()
            && initialState.text != null
            && initialState.intResult >= 0) {
          EditorInfoCompat.setInitialSurroundingSubText(
              outAttrs, initialState.text, initialState.intResult);
        }
      } else {
        outAttrs.initialSelStart = -1;
        outAttrs.initialSelEnd = -1;
        resetLastReportedSelection();
      }
      final FlutterInputConnection connection =
          new FlutterInputConnection(
              view,
              inputTarget.id,
              engineGeneration,
              textInputChannel,
              keyboardManager,
              outAttrs,
              obscureText,
              syncInputConnectionHost);
      activeSyncInputConnection = connection;
      lastInputConnection = connection;
      if (restartAfterCreate) {
        postRestartInputForClient(restartAfterCreateClientId);
      }
      return connection;
    }

    InputConnectionAdaptor connection =
        new InputConnectionAdaptor(
            view,
            inputTarget.id,
            textInputChannel,
            scribeChannel,
            keyboardManager,
            mEditable,
            outAttrs);
    outAttrs.initialSelStart = mEditable.getSelectionStart();
    outAttrs.initialSelEnd = mEditable.getSelectionEnd();

    lastInputConnection = connection;
    return lastInputConnection;
  }

  @Nullable
  public InputConnection getLastInputConnection() {
    return lastInputConnection;
  }

  /**
   * Returns whether the {@link InputMethodManager} should treat the Flutter view as a text editor.
   *
   * <p>Backs {@link View#onCheckIsTextEditor()} on the Flutter view. The framework sets its {@code
   * IS_TEXT_EDITOR} start-input flag from that callback, and some devices (Android TV builds with
   * {@code config_preventImeStartupUnlessTextEditor}) refuse to bind an IME to a view that does not
   * report itself as a text editor.
   *
   * <p>This is {@code true} while a framework text input client with a non-{@link
   * TextInputChannel.TextInputType#NONE} input type is attached, and while a virtual display
   * platform view is the input target (its input connection is proxied through the Flutter view).
   */
  public boolean isTextEditor() {
    switch (inputTarget.type) {
      case FRAMEWORK_CLIENT:
        return configuration != null
            && configuration.inputType != null
            && configuration.inputType.type != TextInputChannel.TextInputType.NONE;
      case VIRTUAL_DISPLAY_PLATFORM_VIEW:
        return true;
      default:
        return false;
    }
  }

  /**
   * Clears a platform view text input client if it is the current input target.
   *
   * <p>This is called when a platform view is disposed to make sure we're not hanging to a stale
   * input connection.
   */
  public void clearPlatformViewClient(int platformViewId) {
    if ((inputTarget.type == InputTarget.Type.VIRTUAL_DISPLAY_PLATFORM_VIEW
            || inputTarget.type == InputTarget.Type.PHYSICAL_DISPLAY_PLATFORM_VIEW)
        && inputTarget.id == platformViewId) {
      inputTarget = new InputTarget(InputTarget.Type.NO_TARGET, 0);
      notifyViewExited();
      mImm.hideSoftInputFromWindow(mView.getApplicationWindowToken(), 0);
      mImm.restartInput(mView);
      mRestartInputPending = false;
    }
  }

  public void sendTextInputAppPrivateCommand(@NonNull String action, @NonNull Bundle data) {
    mImm.sendAppPrivateCommand(mView, action, data);
  }

  @VisibleForTesting
  void showTextInput(View view) {
    if (inImeCallbackDepth > 0) {
      postIfSameGeneration(() -> showTextInput(view));
      return;
    }
    if (configuration == null
        || configuration.inputType == null
        || configuration.inputType.type != TextInputChannel.TextInputType.NONE) {
      view.requestFocus();
      mImm.showSoftInput(view, 0);
    } else {
      hideTextInput(view);
    }
  }

  private void hideTextInput(View view) {
    if (inImeCallbackDepth > 0) {
      postIfSameGeneration(() -> hideTextInput(view));
      return;
    }
    notifyViewExited();
    // Note: when a virtual display is used, a race condition may lead to us hiding the keyboard
    // here just after a platform view has shown it.
    // This can only potentially happen when switching focus from a Flutter text field to a platform
    // view's text
    // field(by text field here I mean anything that keeps the keyboard open).
    // See: https://github.com/flutter/flutter/issues/34169
    mImm.hideSoftInputFromWindow(view.getApplicationWindowToken(), 0);
  }

  @VisibleForTesting
  void setTextInputClient(int client, TextInputChannel.Configuration configuration) {
    if (inImeCallbackDepth > 0) {
      postIfSameGeneration(() -> setTextInputClient(client, configuration));
      return;
    }
    if (destroyed) {
      return;
    }
    // Close any active sync connection first so any flushed batch notifies autofill before exit.
    closeActiveSyncConnection(/*notifyDart=*/ true);
    // Call notifyViewExited on the previous field.
    notifyViewExited();
    clearPendingImeUpdates();
    resetLastReportedSelection();
    this.configuration = configuration;
    inputTarget = new InputTarget(InputTarget.Type.FRAMEWORK_CLIENT, client);

    mEditable.removeEditingStateListener(this);
    mEditable =
        new ListenableEditingState(
            configuration.autofill != null ? configuration.autofill.editState : null, mView);
    updateAutofillConfigurationIfNeeded(configuration);

    unlockPlatformViewInputConnection();
    lastClientRect = null;
    if (isSyncModeActive()) {
      mLastKnownFrameworkTextEditingState = null;
      // In sync mode Dart is the single source of truth and createInputConnection queries initial
      // state directly, so restartInput immediately instead of waiting for setEditingState.
      mRestartInputPending = false;
      mImm.restartInput(mView);
    } else {
      // setTextInputClient will be followed by a call to setTextInputEditingState.
      // Do a restartInput at that time.
      mRestartInputPending = true;
      mEditable.addEditingStateListener(this);
    }
  }

  private void setPlatformViewTextInputClient(int platformViewId, boolean usesVirtualDisplay) {
    closeActiveSyncConnection(/*notifyDart=*/ true);
    clearPendingImeUpdates();
    resetLastReportedSelection();
    if (usesVirtualDisplay) {
      // We need to make sure that the Flutter view is focused so that no imm operations get short
      // circuited.
      // Not asking for focus here specifically manifested in a bug on API 28 devices where the
      // platform view's request to show a keyboard was ignored.
      mView.requestFocus();
      inputTarget = new InputTarget(InputTarget.Type.VIRTUAL_DISPLAY_PLATFORM_VIEW, platformViewId);
      mImm.restartInput(mView);
      mRestartInputPending = false;
    } else {
      inputTarget =
          new InputTarget(InputTarget.Type.PHYSICAL_DISPLAY_PLATFORM_VIEW, platformViewId);
      lastInputConnection = null;
    }
  }

  private static boolean composingChanged(
      TextInputChannel.TextEditState before, TextInputChannel.TextEditState after) {
    final int composingRegionLength = before.composingEnd - before.composingStart;
    if (composingRegionLength != after.composingEnd - after.composingStart) {
      return true;
    }
    for (int index = 0; index < composingRegionLength; index++) {
      if (before.text.charAt(index + before.composingStart)
          != after.text.charAt(index + after.composingStart)) {
        return true;
      }
    }
    return false;
  }

  // Called by the text input channel to update the text input plugin with the
  // latest TextEditState from the framework.
  @VisibleForTesting
  void setTextInputEditingState(View view, TextInputChannel.TextEditState state) {
    if (isSyncModeActive()) {
      // In sync mode Dart is the single source of truth, and async TextInput.setEditingState
      // messages may arrive stale (e.g. a fallback queued during focus before setClient ran).
      // Treat this channel call as a hint and re-query Dart's live selection/composing state,
      // reporting to IMM only if it differs from what was last reported.
      if (inputTarget.type == InputTarget.Type.FRAMEWORK_CLIENT
          && activeSyncInputConnection != null) {
        enterImeCallback();
        try {
          final long notifySeqBefore = syncNotifySeq;
          final SyncTextInputProtocol.Response live = queryCursorAnchorState(inputTarget.id);
          if (syncNotifySeq == notifySeqBefore
              && live != null
              && live.isOk()
              && (live.selectionStart != lastReportedSelStart
                  || live.selectionEnd != lastReportedSelEnd
                  || live.composingStart != lastReportedCompStart
                  || live.composingEnd != lastReportedCompEnd)) {
            handleSyncTextInputEditingState(
                inputTarget.id,
                /*flags=*/ 0,
                live.selectionStart,
                live.selectionEnd,
                live.composingStart,
                live.composingEnd);
          }
        } finally {
          exitImeCallback();
        }
      }
      return;
    }

    if (!mRestartInputPending
        && mLastKnownFrameworkTextEditingState != null
        && mLastKnownFrameworkTextEditingState.hasComposing()) {
      // Also restart input if the framework (or the developer) decides to
      // change the composing region by itself (which is discouraged). Many IMEs
      // don't expect editors to commit composing text, so a restart is needed
      // to reset their internal states.
      mRestartInputPending = composingChanged(mLastKnownFrameworkTextEditingState, state);
      if (mRestartInputPending) {
        Log.i(TAG, "Composing region changed by the framework. Restarting the input method.");
      }
    }

    mLastKnownFrameworkTextEditingState = state;
    mEditable.setEditingState(state);

    // Restart if needed. Restarting will also update the selection.
    if (mRestartInputPending) {
      mImm.restartInput(view);
      mRestartInputPending = false;
    }
  }

  private interface MinMax {
    void inspect(double x, double y);
  }

  private void saveEditableSizeAndTransform(double width, double height, double[] matrix) {
    final double[] minMax = new double[4]; // minX, maxX, minY, maxY.
    final boolean isAffine = matrix[3] == 0 && matrix[7] == 0 && matrix[15] == 1;
    minMax[0] = minMax[1] = matrix[12] / matrix[15]; // minX and maxX.
    minMax[2] = minMax[3] = matrix[13] / matrix[15]; // minY and maxY.

    final MinMax finder =
        new MinMax() {
          @Override
          public void inspect(double x, double y) {
            final double w = isAffine ? 1 : 1 / (matrix[3] * x + matrix[7] * y + matrix[15]);
            final double tx = (matrix[0] * x + matrix[4] * y + matrix[12]) * w;
            final double ty = (matrix[1] * x + matrix[5] * y + matrix[13]) * w;

            if (tx < minMax[0]) {
              minMax[0] = tx;
            } else if (tx > minMax[1]) {
              minMax[1] = tx;
            }

            if (ty < minMax[2]) {
              minMax[2] = ty;
            } else if (ty > minMax[3]) {
              minMax[3] = ty;
            }
          }
        };

    finder.inspect(width, 0);
    finder.inspect(width, height);
    finder.inspect(0, height);
    final Float density = mView.getContext().getResources().getDisplayMetrics().density;
    lastClientRect =
        new Rect(
            (int) (minMax[0] * density),
            (int) (minMax[2] * density),
            (int) Math.ceil(minMax[1] * density),
            (int) Math.ceil(minMax[3] * density));
  }

  @VisibleForTesting
  void clearTextInputClient() {
    if (inImeCallbackDepth > 0) {
      postIfSameGeneration(this::clearTextInputClient);
      return;
    }
    if (inputTarget.type == InputTarget.Type.VIRTUAL_DISPLAY_PLATFORM_VIEW) {
      // This only applies to platform views that use a virtual display.
      // Focus changes in the framework tree have no guarantees on the order focus nodes are
      // notified. A node that lost focus may be notified before or after a node that gained focus.
      // When moving the focus from a Flutter text field to an AndroidView, it is possible that the
      // Flutter text field's focus node will be notified that it lost focus after the AndroidView
      // was notified that it gained focus. When this happens the text field will send a
      // clearTextInput command which we ignore.
      // By doing this we prevent the framework from clearing a platform view input client (the only
      // way to do so is to set a new framework text client). I don't see an obvious use case for
      // "clearing" a platform view's text input client, and it may be error prone as we don't know
      // how the platform view manages the input connection and we probably shouldn't interfere.
      // If we ever want to allow the framework to clear a platform view text client we should
      // probably consider changing the focus manager such that focus nodes that lost focus are
      // notified before focus nodes that gained focus as part of the same focus event.
      return;
    }
    closeActiveSyncConnection(/*notifyDart=*/ true);
    clearPendingImeUpdates();
    resetLastReportedSelection();
    mEditable.removeEditingStateListener(this);
    notifyViewExited();
    configuration = null;
    updateAutofillConfigurationIfNeeded(null);
    inputTarget = new InputTarget(InputTarget.Type.NO_TARGET, 0);
    unlockPlatformViewInputConnection();
    lastClientRect = null;
    // When the IME is hidden, we need to restart the input method manager to accomodate
    // some keyboards like the Samsung keyboard that may be caching old state.
    WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(mView);
    if (insets != null && !insets.isVisible(WindowInsetsCompat.Type.ime())) {
      mImm.restartInput(mView);
    }
  }

  private static class InputTarget {
    enum Type {
      NO_TARGET,
      // InputConnection is managed by the TextInputPlugin, and events are forwarded to the Flutter
      // framework.
      FRAMEWORK_CLIENT,
      // InputConnection is managed by a platform view that is presented on a virtual display.
      VIRTUAL_DISPLAY_PLATFORM_VIEW,
      // InputConnection is managed by a platform view that is embedded in the activity's view
      // hierarchy. This view hierarchy is displayed in a physical display within the aplication
      // display area.
      PHYSICAL_DISPLAY_PLATFORM_VIEW,
    }

    public InputTarget(@NonNull Type type, int id) {
      this.type = type;
      this.id = id;
    }

    @NonNull Type type;
    // The ID of the input target.
    //
    // For framework clients this is the framework input connection client ID.
    // For platform views this is the platform view's ID.
    int id;
  }

  // -------- Start: KeyboardManager Synchronous Responder -------
  public boolean handleKeyEvent(@NonNull KeyEvent keyEvent) {
    if (!getInputMethodManager().isAcceptingText() || lastInputConnection == null) {
      return false;
    }

    // Send the KeyEvent as an IME KeyEvent. If the input connection is a
    // FlutterInputConnection or InputConnectionAdaptor then call its handleKeyEvent
    // method (because this method will be called by the keyboard manager, and
    // sendKeyEvent forwards the key event back to the keyboard manager).
    if (lastInputConnection instanceof FlutterInputConnection) {
      return ((FlutterInputConnection) lastInputConnection).handleKeyEvent(keyEvent);
    }
    return (lastInputConnection instanceof InputConnectionAdaptor)
        ? ((InputConnectionAdaptor) lastInputConnection).handleKeyEvent(keyEvent)
        : lastInputConnection.sendKeyEvent(keyEvent);
  }
  // -------- End: KeyboardManager Synchronous Responder -------

  // -------- Start: ListenableEditingState watcher implementation -------

  @Override
  public void didChangeEditingState(
      boolean textChanged, boolean selectionChanged, boolean composingRegionChanged) {
    if (textChanged) {
      // Notify the autofill manager of the value change.
      notifyValueChanged(mEditable.toString());
    }

    final int selectionStart = mEditable.getSelectionStart();
    final int selectionEnd = mEditable.getSelectionEnd();
    final int composingStart = mEditable.getComposingStart();
    final int composingEnd = mEditable.getComposingEnd();

    final ArrayList<TextEditingDelta> batchTextEditingDeltas =
        mEditable.extractBatchTextEditingDeltas();
    final boolean skipFrameworkUpdate =
        // The framework needs to send its editing state first.
        mLastKnownFrameworkTextEditingState == null
            || (mEditable.toString().equals(mLastKnownFrameworkTextEditingState.text)
                && selectionStart == mLastKnownFrameworkTextEditingState.selectionStart
                && selectionEnd == mLastKnownFrameworkTextEditingState.selectionEnd
                && composingStart == mLastKnownFrameworkTextEditingState.composingStart
                && composingEnd == mLastKnownFrameworkTextEditingState.composingEnd);
    if (!skipFrameworkUpdate) {
      Log.v(TAG, "send EditingState to flutter: " + mEditable.toString());

      if (configuration.enableDeltaModel) {
        textInputChannel.updateEditingStateWithDeltas(inputTarget.id, batchTextEditingDeltas);
        mEditable.clearBatchDeltas();
      } else {
        textInputChannel.updateEditingState(
            inputTarget.id,
            mEditable.toString(),
            selectionStart,
            selectionEnd,
            composingStart,
            composingEnd);
      }
      mLastKnownFrameworkTextEditingState =
          new TextEditState(
              mEditable.toString(), selectionStart, selectionEnd, composingStart, composingEnd);
    } else {
      // Don't accumulate deltas if they are not sent to the framework.
      mEditable.clearBatchDeltas();
    }
  }

  // -------- End: ListenableEditingState watcher implementation -------

  // -------- Start: Autofill -------
  // ### Setup and provide the initial text values and hints.
  //
  // The TextInputConfiguration used to set up the current client is also used for populating
  // "AutofillVirtualStructure" when requested by the autofill manager (AFM), See
  // #onProvideAutofillVirtualStructure.
  //
  // ### Keep the AFM updated
  //
  // The autofill session connected to the AFM keeps a copy of the current state for each reported
  // field in "AutofillVirtualStructure" (instead of holding a reference to those fields), so the
  // AFM needs to be notified when text changes if the client was part of the
  // "AutofillVirtualStructure" previously reported to the AFM. This step is essential for
  // triggering autofill save. This is done in #didChangeEditingState by calling
  // #notifyValueChanged.
  //
  // Additionally when the text input plugin receives a new TextInputConfiguration,
  // AutofillManager#notifyValueChanged will be called on all the autofillable fields contained in
  // the TextInputConfiguration, in case some of them are tracked by the session and their values
  // have changed. However if the value of an unfocused EditableText is changed in the framework,
  // such change will not be sent to the text input plugin until the next TextInput.attach call.
  private boolean needsAutofill() {
    return autofillConfiguration != null;
  }

  private void notifyViewEntered() {
    if (Build.VERSION.SDK_INT < API_LEVELS.API_26 || afm == null || !needsAutofill()) {
      return;
    }

    final String triggerIdentifier = configuration.autofill.uniqueIdentifier;
    final int[] offset = new int[2];
    mView.getLocationOnScreen(offset);
    Rect rect = new Rect(lastClientRect);
    rect.offset(offset[0], offset[1]);
    afm.notifyViewEntered(mView, triggerIdentifier.hashCode(), rect);
  }

  private void notifyViewExited() {
    if (Build.VERSION.SDK_INT < API_LEVELS.API_26
        || afm == null
        || configuration == null
        || configuration.autofill == null
        || !needsAutofill()) {
      return;
    }

    final String triggerIdentifier = configuration.autofill.uniqueIdentifier;
    afm.notifyViewExited(mView, triggerIdentifier.hashCode());
  }

  private void notifyValueChanged(String newValue) {
    if (Build.VERSION.SDK_INT < API_LEVELS.API_26 || afm == null || !needsAutofill()) {
      return;
    }

    final String triggerIdentifier = configuration.autofill.uniqueIdentifier;
    afm.notifyValueChanged(mView, triggerIdentifier.hashCode(), AutofillValue.forText(newValue));
  }

  private void updateAutofillConfigurationIfNeeded(TextInputChannel.Configuration configuration) {
    if (Build.VERSION.SDK_INT < API_LEVELS.API_26) {
      return;
    }

    if (configuration == null || configuration.autofill == null) {
      // Disables autofill if the configuration doesn't have an autofill field.
      autofillConfiguration = null;
      return;
    }

    final TextInputChannel.Configuration[] configurations = configuration.fields;
    autofillConfiguration = new SparseArray<>();

    if (configurations == null) {
      autofillConfiguration.put(configuration.autofill.uniqueIdentifier.hashCode(), configuration);
    } else {
      for (TextInputChannel.Configuration config : configurations) {
        TextInputChannel.Configuration.Autofill autofill = config.autofill;
        if (autofill != null) {
          autofillConfiguration.put(autofill.uniqueIdentifier.hashCode(), config);
          afm.notifyValueChanged(
              mView,
              autofill.uniqueIdentifier.hashCode(),
              AutofillValue.forText(autofill.editState.text));
        }
      }
    }
  }

  public void onProvideAutofillVirtualStructure(@NonNull ViewStructure structure, int flags) {
    if (Build.VERSION.SDK_INT < API_LEVELS.API_26 || !needsAutofill()) {
      return;
    }

    final String triggerIdentifier = configuration.autofill.uniqueIdentifier;
    final AutofillId parentId = structure.getAutofillId();
    for (int i = 0; i < autofillConfiguration.size(); i++) {
      final int autofillId = autofillConfiguration.keyAt(i);
      final TextInputChannel.Configuration config = autofillConfiguration.valueAt(i);
      final TextInputChannel.Configuration.Autofill autofill = config.autofill;
      if (autofill == null) {
        continue;
      }

      structure.addChildCount(1);
      final ViewStructure child = structure.newChild(i);
      child.setAutofillId(parentId, autofillId);
      // Don't set hints when there's none.
      // See https://github.com/flutter/flutter/issues/98505.
      if (autofill.hints.length > 0) {
        child.setAutofillHints(autofill.hints);
      }
      child.setAutofillType(View.AUTOFILL_TYPE_TEXT);
      child.setVisibility(View.VISIBLE);
      if (autofill.hintText != null) {
        child.setHint(autofill.hintText);
      }

      // For some autofill services, only visible input fields are eligible for autofill.
      // Reports the real size of the child if it's the current client, or 1x1 if we don't
      // know the real dimensions of the child.
      if (triggerIdentifier.hashCode() == autofillId && lastClientRect != null) {
        child.setDimens(
            lastClientRect.left,
            lastClientRect.top,
            0,
            0,
            lastClientRect.width(),
            lastClientRect.height());
        if (isSyncModeActive() && inputTarget.type == InputTarget.Type.FRAMEWORK_CLIENT) {
          final SyncTextInputProtocol.Response fullState = queryFullState(inputTarget.id);
          final String currentText =
              fullState != null && fullState.isOk() && fullState.text != null
                  ? fullState.text
                  : (autofill.editState != null ? autofill.editState.text : "");
          child.setAutofillValue(AutofillValue.forText(currentText));
        } else {
          child.setAutofillValue(AutofillValue.forText(mEditable));
        }
      } else {
        child.setDimens(0, 0, 0, 0, 1, 1);
        child.setAutofillValue(AutofillValue.forText(autofill.editState.text));
      }
    }
  }

  public void autofill(@NonNull SparseArray<AutofillValue> values) {
    if (Build.VERSION.SDK_INT < API_LEVELS.API_26) {
      return;
    }

    if (configuration == null || autofillConfiguration == null || configuration.autofill == null) {
      return;
    }

    final TextInputChannel.Configuration.Autofill currentAutofill = configuration.autofill;
    final HashMap<String, TextInputChannel.TextEditState> editingValues = new HashMap<>();
    final boolean syncActive =
        isSyncModeActive() && inputTarget.type == InputTarget.Type.FRAMEWORK_CLIENT;
    for (int i = 0; i < values.size(); i++) {
      int virtualId = values.keyAt(i);

      final TextInputChannel.Configuration config = autofillConfiguration.get(virtualId);
      if (config == null || config.autofill == null) {
        continue;
      }

      final TextInputChannel.Configuration.Autofill autofill = config.autofill;
      final String value = values.valueAt(i).getTextValue().toString();
      final TextInputChannel.TextEditState newState =
          new TextInputChannel.TextEditState(value, value.length(), value.length(), -1, -1);

      if (autofill.uniqueIdentifier.equals(currentAutofill.uniqueIdentifier)) {
        if (syncActive) {
          enterImeCallback();
          try {
            final long notifySeqBefore = syncNotifySeq;
            final SyncTextInputProtocol.Response response =
                dispatchAutofillCurrentClient(inputTarget.id, value);
            if (response != null && response.isOk()) {
              handleSyncMutationResponse(
                  activeSyncInputConnection,
                  response,
                  notifySeqBefore,
                  /*suppressSelectionUpdate=*/ false);
            } else {
              editingValues.put(autofill.uniqueIdentifier, newState);
            }
          } finally {
            exitImeCallback();
          }
        } else {
          // Autofilling the current client is the same as handling user input
          // from the virtual keyboard. Setting the editable to newState and an
          // update will be sent to the framework.
          mEditable.setEditingState(newState);
        }
      } else {
        editingValues.put(autofill.uniqueIdentifier, newState);
      }
    }
    if (!syncActive || !editingValues.isEmpty()) {
      textInputChannel.updateEditingStateWithTag(inputTarget.id, editingValues);
    }
  }
  // -------- End: Autofill -------
}
