// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugin.editing;

import static io.flutter.Build.API_LEVELS;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.util.SparseArray;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewStructure;
import android.view.ViewTreeObserver;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.view.inputmethod.BaseInputConnection;
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
import io.flutter.embedding.engine.systemchannels.PlatformChannel;
import io.flutter.embedding.engine.systemchannels.ScribeChannel;
import io.flutter.embedding.engine.systemchannels.TextInputChannel;
import io.flutter.embedding.engine.systemchannels.TextInputChannel.TextEditState;
import io.flutter.plugin.platform.PlatformViewsController;
import io.flutter.plugin.platform.PlatformViewsController2;
import io.flutter.util.ViewUtils;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Android implementation of the text input plugin. */
public class TextInputPlugin implements ListenableEditingState.EditingStateWatcher {
  private static final String TAG = "TextInputPlugin";
  private static final int MENU_ITEM_ID_SEARCH_WEB = 0x10000002;
  private static final int MENU_ITEM_ID_CUSTOM_BASE = 0x10000100;

  @NonNull private final View mView;
  @NonNull private final InputMethodManager mImm;
  @NonNull private final AutofillManager afm;
  @NonNull private final ScribeChannel scribeChannel;
  @NonNull private final TextInputChannel textInputChannel;
  @Nullable private final PlatformChannel platformChannel;
  @Nullable private final PlatformChannel.SystemContextMenuHandler systemContextMenuHandler;
  @NonNull private InputTarget inputTarget = new InputTarget(InputTarget.Type.NO_TARGET, 0);
  @Nullable private TextInputChannel.Configuration configuration;
  @Nullable private SparseArray<TextInputChannel.Configuration> autofillConfiguration;
  @NonNull private ListenableEditingState mEditable;
  private boolean mRestartInputPending;
  @Nullable private InputConnection lastInputConnection;
  @NonNull private PlatformViewsController platformViewsController;
  @NonNull private PlatformViewsController2 platformViewsController2;
  @Nullable private Rect lastClientRect;
  private ImeSyncDeferringInsetsCallback imeSyncCallback;
  @Nullable private ActionMode systemContextMenuActionMode;
  @Nullable private PlatformChannel.SystemContextMenuRequest activeSystemContextMenuRequest;
  private int systemContextMenuClientId = -1;
  @Nullable private Integer systemContextMenuSessionId;

  @Nullable
  private ViewTreeObserver.OnGlobalFocusChangeListener systemContextMenuFocusChangeListener;

  @Nullable private ViewTreeObserver systemContextMenuViewTreeObserver;

  private final Map<String, Integer> customCallbackToMenuItemId = new HashMap<>();
  private final SparseArray<String> customMenuItemCallbackIds = new SparseArray<>();
  private int nextCustomMenuItemId = MENU_ITEM_ID_CUSTOM_BASE;

  // Initialize the "last seen" text editing values to a non-null value.
  private TextEditState mLastKnownFrameworkTextEditingState;

  // When true following calls to createInputConnection will return the cached lastInputConnection
  // if the input
  // target is a platform view. See the comments on lockPlatformViewInputConnection for more
  // details.
  private boolean isInputConnectionLocked;

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
        null);
  }

  @SuppressLint("NewApi")
  public TextInputPlugin(
      @NonNull View view,
      @NonNull TextInputChannel textInputChannel,
      @NonNull ScribeChannel scribeChannel,
      @NonNull PlatformViewsController platformViewsController,
      @NonNull PlatformViewsController2 platformViewsController2,
      @Nullable PlatformChannel platformChannel) {
    mView = view;
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
                mImm.restartInput(mView);
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
    this.platformChannel = platformChannel;
    if (this.platformChannel != null) {
      this.systemContextMenuHandler =
          new PlatformChannel.SystemContextMenuHandler() {
            @Override
            public void showSystemContextMenu(
                @NonNull PlatformChannel.SystemContextMenuRequest request) {
              TextInputPlugin.this.showSystemContextMenu(request);
            }

            @Override
            public void hideSystemContextMenu() {
              TextInputPlugin.this.hideSystemContextMenu();
            }
          };
      this.platformChannel.setSystemContextMenuHandler(this.systemContextMenuHandler);
    } else {
      this.systemContextMenuHandler = null;
    }

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

  @Nullable
  @VisibleForTesting
  ActionMode getSystemContextMenuActionMode() {
    return systemContextMenuActionMode;
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

  /**
   * Detaches the text input plugin from the platform views controller.
   *
   * <p>The TextInputPlugin instance should not be used after calling this.
   */
  @SuppressLint("NewApi")
  public void destroy() {
    dismissSystemContextMenu(true);
    if (platformChannel != null && systemContextMenuHandler != null) {
      platformChannel.clearSystemContextMenuHandler(systemContextMenuHandler);
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
    dismissSystemContextMenu(true);
    // Call notifyViewExited on the previous field.
    notifyViewExited();
    this.configuration = configuration;
    inputTarget = new InputTarget(InputTarget.Type.FRAMEWORK_CLIENT, client);

    mEditable.removeEditingStateListener(this);
    mEditable =
        new ListenableEditingState(
            configuration.autofill != null ? configuration.autofill.editState : null, mView);
    updateAutofillConfigurationIfNeeded(configuration);

    // setTextInputClient will be followed by a call to setTextInputEditingState.
    // Do a restartInput at that time.
    mRestartInputPending = true;
    unlockPlatformViewInputConnection();
    lastClientRect = null;
    mEditable.addEditingStateListener(this);
  }

  @VisibleForTesting
  void setPlatformViewTextInputClient(int platformViewId, boolean usesVirtualDisplay) {
    dismissSystemContextMenu(true);
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
    dismissSystemContextMenu(true);
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

    // Send the KeyEvent as an IME KeyEvent. If the input connection is an
    // InputConnectionAdaptor then call its handleKeyEvent method (because
    // this method will be called by the keyboard manager, and
    // InputConnectionAdaptor#sendKeyEvent forwards the key event back to the
    // keyboard manager).
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
        child.setAutofillValue(AutofillValue.forText(mEditable));
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
        // Autofilling the current client is the same as handling user input
        // from the virtual keyboard. Setting the editable to newState and an
        // update will be sent to the framework.
        mEditable.setEditingState(newState);
      } else {
        editingValues.put(autofill.uniqueIdentifier, newState);
      }
    }
    textInputChannel.updateEditingStateWithTag(inputTarget.id, editingValues);
  }
  // -------- End: Autofill -------

  // -------- Start: System Context Menu -------
  private static final class SelectionBounds {
    final int length;
    final boolean hasValidSelection;
    final int selMin;
    final int selMax;

    private SelectionBounds(int length, boolean hasValidSelection, int selMin, int selMax) {
      this.length = length;
      this.hasValidSelection = hasValidSelection;
      this.selMin = selMin;
      this.selMax = selMax;
    }

    @NonNull
    static SelectionBounds from(@NonNull Editable editable) {
      final int length = editable.length();
      final int rawSelStart = Selection.getSelectionStart(editable);
      final int rawSelEnd = Selection.getSelectionEnd(editable);
      final boolean hasValidSelection = rawSelStart >= 0 && rawSelEnd >= 0;
      final int selStart = Math.min(length, Math.max(0, rawSelStart));
      final int selEnd = Math.min(length, Math.max(0, rawSelEnd));
      return new SelectionBounds(
          length, hasValidSelection, Math.min(selStart, selEnd), Math.max(selStart, selEnd));
    }

    boolean hasNonEmptySelection(boolean obscure) {
      return hasValidSelection && selMin != selMax && !obscure;
    }
  }

  @VisibleForTesting
  void showSystemContextMenu(@NonNull PlatformChannel.SystemContextMenuRequest request) {
    if (inputTarget.type != InputTarget.Type.FRAMEWORK_CLIENT) {
      Log.e(
          TAG,
          "Currently, the system context menu can only be shown for an active framework text input"
              + " connection.");
      if (systemContextMenuActionMode != null) {
        dismissSystemContextMenu(false);
      }
      notifySystemContextMenuDismissed(-1, request.sessionId);
      return;
    }

    if (!hasSupportedSystemContextMenuItems(request)) {
      if (systemContextMenuActionMode != null) {
        dismissSystemContextMenu(false);
      }
      notifySystemContextMenuDismissed(inputTarget.id, request.sessionId);
      return;
    }

    activeSystemContextMenuRequest = request;
    systemContextMenuSessionId = request.sessionId;
    if (systemContextMenuActionMode != null) {
      systemContextMenuActionMode.invalidate();
      return;
    }

    systemContextMenuClientId = inputTarget.id;
    ActionMode mode =
        mView.startActionMode(new SystemContextMenuCallback(), ActionMode.TYPE_FLOATING);
    if (mode == null) {
      final int failedClientId = systemContextMenuClientId;
      final Integer failedSessionId = systemContextMenuSessionId;
      clearSystemContextMenuState();
      notifySystemContextMenuDismissed(failedClientId, failedSessionId);
      return;
    }
    systemContextMenuActionMode = mode;
    registerSystemContextMenuFocusListener();
  }

  @VisibleForTesting
  void hideSystemContextMenu() {
    dismissSystemContextMenu(false);
  }

  private void registerSystemContextMenuFocusListener() {
    if (systemContextMenuFocusChangeListener != null) {
      return;
    }
    ViewTreeObserver observer = mView.getViewTreeObserver();
    if (observer == null || !observer.isAlive()) {
      return;
    }
    systemContextMenuViewTreeObserver = observer;
    systemContextMenuFocusChangeListener =
        (oldFocus, newFocus) -> {
          if (systemContextMenuActionMode != null && newFocus != null && newFocus != mView) {
            dismissSystemContextMenu(true);
          }
        };
    observer.addOnGlobalFocusChangeListener(systemContextMenuFocusChangeListener);
  }

  private void unregisterSystemContextMenuFocusListener() {
    if (systemContextMenuFocusChangeListener == null) {
      return;
    }
    ViewTreeObserver observer =
        systemContextMenuViewTreeObserver != null && systemContextMenuViewTreeObserver.isAlive()
            ? systemContextMenuViewTreeObserver
            : mView.getViewTreeObserver();
    if (observer != null && observer.isAlive()) {
      observer.removeOnGlobalFocusChangeListener(systemContextMenuFocusChangeListener);
    }
    systemContextMenuViewTreeObserver = null;
    systemContextMenuFocusChangeListener = null;
  }

  private void clearSystemContextMenuState() {
    unregisterSystemContextMenuFocusListener();
    systemContextMenuActionMode = null;
    activeSystemContextMenuRequest = null;
    systemContextMenuClientId = -1;
    systemContextMenuSessionId = null;
    customCallbackToMenuItemId.clear();
    customMenuItemCallbackIds.clear();
    nextCustomMenuItemId = MENU_ITEM_ID_CUSTOM_BASE;
  }

  private void notifySystemContextMenuDismissed(int clientId, @Nullable Integer sessionId) {
    if (platformChannel != null) {
      platformChannel.onDismissSystemContextMenu(clientId, sessionId);
    }
  }

  private void dismissSystemContextMenu(boolean notifyFramework) {
    final ActionMode mode = systemContextMenuActionMode;
    if (mode == null) {
      return;
    }
    final int dismissedClientId = systemContextMenuClientId;
    final Integer dismissedSessionId = systemContextMenuSessionId;
    clearSystemContextMenuState();
    mode.finish();
    if (notifyFramework) {
      notifySystemContextMenuDismissed(dismissedClientId, dismissedSessionId);
    }
  }

  private boolean hasSupportedSystemContextMenuItems(
      @NonNull PlatformChannel.SystemContextMenuRequest request) {
    if (request.items == null) {
      final SelectionBounds bounds = SelectionBounds.from(mEditable);
      final boolean obscure = configuration != null && configuration.obscureText;
      final boolean hasSelection = bounds.hasNonEmptySelection(obscure);
      final boolean canPaste = bounds.hasValidSelection && clipboardHasStrings();
      final boolean canSelectAll =
          bounds.length > 0
              && !(bounds.hasValidSelection
                  && bounds.selMin == 0
                  && bounds.selMax == bounds.length);
      return hasSelection || canPaste || canSelectAll;
    }

    for (PlatformChannel.SystemContextMenuItem item : request.items) {
      switch (item.type) {
        case "cut":
        case "copy":
        case "paste":
        case "selectAll":
        case "share":
        case "searchWeb":
          return true;
        case "custom":
          if (item.title != null && !item.title.isEmpty() && item.id != null) {
            return true;
          }
          break;
        default:
          break;
      }
    }
    return false;
  }

  private void populateSystemContextMenu(@NonNull Menu menu) {
    customMenuItemCallbackIds.clear();
    final PlatformChannel.SystemContextMenuRequest request = activeSystemContextMenuRequest;
    if (request == null) {
      return;
    }

    int order = 0;

    if (request.items == null) {
      final SelectionBounds bounds = SelectionBounds.from(mEditable);
      final boolean obscure = configuration != null && configuration.obscureText;
      if (bounds.hasNonEmptySelection(obscure)) {
        menu.add(Menu.NONE, android.R.id.cut, order++, android.R.string.cut)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(Menu.NONE, android.R.id.copy, order++, android.R.string.copy)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
      }
      if (bounds.hasValidSelection && clipboardHasStrings()) {
        menu.add(Menu.NONE, android.R.id.paste, order++, android.R.string.paste)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
      }
      if (bounds.length > 0
          && !(bounds.hasValidSelection && bounds.selMin == 0 && bounds.selMax == bounds.length)) {
        menu.add(Menu.NONE, android.R.id.selectAll, order++, android.R.string.selectAll)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
      }
      return;
    }

    // Note: Items are added in the order requested by the framework (matching
    // EditableTextState.contextMenuButtonItems) rather than Editor's MENU_ITEM_ORDER_* constants.
    for (PlatformChannel.SystemContextMenuItem item : request.items) {
      switch (item.type) {
        case "cut":
          menu.add(Menu.NONE, android.R.id.cut, order++, android.R.string.cut)
              .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
          break;
        case "copy":
          menu.add(Menu.NONE, android.R.id.copy, order++, android.R.string.copy)
              .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
          break;
        case "paste":
          menu.add(Menu.NONE, android.R.id.paste, order++, android.R.string.paste)
              .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
          break;
        case "selectAll":
          menu.add(Menu.NONE, android.R.id.selectAll, order++, android.R.string.selectAll)
              .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
          break;
        case "share":
          {
            // Dart's IOSSystemContextMenuItemShare always supplies a localized title.
            CharSequence title = item.title != null && !item.title.isEmpty() ? item.title : "Share";
            menu.add(Menu.NONE, android.R.id.shareText, order++, title)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            break;
          }
        case "searchWeb":
          {
            CharSequence title =
                item.title != null && !item.title.isEmpty()
                    ? item.title
                    : mView.getContext().getString(android.R.string.search_go);
            menu.add(Menu.NONE, MENU_ITEM_ID_SEARCH_WEB, order++, title)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            break;
          }
        case "custom":
          if (item.title != null && !item.title.isEmpty() && item.id != null) {
            Integer existingId = customCallbackToMenuItemId.get(item.id);
            final int customItemId;
            if (existingId != null) {
              customItemId = existingId;
            } else {
              customItemId = nextCustomMenuItemId++;
              customCallbackToMenuItemId.put(item.id, customItemId);
            }
            customMenuItemCallbackIds.put(customItemId, item.id);
            menu.add(Menu.NONE, customItemId, order++, item.title)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
          }
          break;
        default:
          // Platform-specific items not supported on Android (e.g. lookUp, captureTextFromCamera)
          // are intentionally ignored.
          break;
      }
    }
  }

  private static boolean hasComposingRegion(@NonNull Editable editable) {
    final int composingStart = BaseInputConnection.getComposingSpanStart(editable);
    final int composingEnd = BaseInputConnection.getComposingSpanEnd(editable);
    return composingStart != -1 && composingEnd != -1 && composingStart != composingEnd;
  }

  private boolean handleSystemContextMenuAction(@NonNull ActionMode mode, @NonNull MenuItem item) {
    final int itemId = item.getItemId();
    final SelectionBounds bounds = SelectionBounds.from(mEditable);
    final boolean obscure = configuration != null && configuration.obscureText;

    if (itemId == android.R.id.paste) {
      // Read clipboard synchronously while the ActionMode click callback is active.
      final String textToPaste = readPlainTextFromClipboard(mView.getContext());
      mode.finish();
      if (bounds.hasValidSelection && textToPaste != null && !textToPaste.isEmpty()) {
        final boolean hadComposing = hasComposingRegion(mEditable);
        mEditable.beginBatchEdit();
        BaseInputConnection.removeComposingSpans(mEditable);
        mEditable.replace(bounds.selMin, bounds.selMax, textToPaste);
        final int newCursor = bounds.selMin + textToPaste.length();
        Selection.setSelection(mEditable, newCursor, newCursor);
        mEditable.endBatchEdit();
        if (hadComposing) {
          mImm.restartInput(mView);
        }
      }
      return true;
    } else if (itemId == android.R.id.cut) {
      if (bounds.hasNonEmptySelection(obscure)) {
        writeClipboardText(mEditable.subSequence(bounds.selMin, bounds.selMax).toString());
        mode.finish();
        final boolean hadComposing = hasComposingRegion(mEditable);
        mEditable.beginBatchEdit();
        BaseInputConnection.removeComposingSpans(mEditable);
        mEditable.delete(bounds.selMin, bounds.selMax);
        Selection.setSelection(mEditable, bounds.selMin, bounds.selMin);
        mEditable.endBatchEdit();
        if (hadComposing) {
          mImm.restartInput(mView);
        }
      } else {
        mode.finish();
      }
      return true;
    } else if (itemId == android.R.id.copy) {
      if (bounds.hasNonEmptySelection(obscure)) {
        writeClipboardText(mEditable.subSequence(bounds.selMin, bounds.selMax).toString());
        mode.finish();
        mEditable.beginBatchEdit();
        Selection.setSelection(mEditable, bounds.selMax, bounds.selMax);
        mEditable.endBatchEdit();
      } else {
        mode.finish();
      }
      return true;
    } else if (itemId == android.R.id.selectAll) {
      final PlatformChannel.SystemContextMenuRequest request = activeSystemContextMenuRequest;
      if (bounds.length > 0) {
        mEditable.beginBatchEdit();
        Selection.setSelection(mEditable, 0, bounds.length);
        mEditable.endBatchEdit();
        if (request != null && request.items == null) {
          mode.invalidate();
        }
      } else {
        mode.finish();
      }
      return true;
    } else if (itemId == android.R.id.shareText) {
      final String selectedText = !obscure ? getSelectedText(bounds) : null;
      mode.finish();
      if (selectedText != null && !selectedText.isEmpty()) {
        performShareAction(selectedText);
      }
      return true;
    } else if (itemId == MENU_ITEM_ID_SEARCH_WEB) {
      final String selectedText = !obscure ? getSelectedText(bounds) : null;
      mode.finish();
      if (selectedText != null && !selectedText.isEmpty()) {
        performSearchWebAction(selectedText);
      }
      return true;
    } else {
      final String callbackId = customMenuItemCallbackIds.get(itemId);
      if (callbackId != null) {
        if (platformChannel != null
            && inputTarget.type == InputTarget.Type.FRAMEWORK_CLIENT
            && systemContextMenuClientId >= 0) {
          platformChannel.onPerformCustomAction(
              systemContextMenuClientId, callbackId, systemContextMenuSessionId);
        }
        mode.finish();
        return true;
      }
    }

    return false;
  }

  @Nullable
  private String getSelectedText(@NonNull SelectionBounds bounds) {
    if (!bounds.hasValidSelection || bounds.selMin == bounds.selMax) {
      return null;
    }
    return mEditable.subSequence(bounds.selMin, bounds.selMax).toString();
  }

  private boolean clipboardHasStrings() {
    ClipboardManager clipboard =
        (ClipboardManager) mView.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
    if (clipboard == null || !clipboard.hasPrimaryClip()) {
      return false;
    }
    ClipDescription description = clipboard.getPrimaryClipDescription();
    if (description == null || !description.hasMimeType("text/*")) {
      return false;
    }
    // Pure raw URI clips (ClipData.newRawUri) report only MIMETYPE_TEXT_URILIST and have no
    // ContentResolver text stream for readPlainTextFromClipboard to read.
    if (description.getMimeTypeCount() == 1
        && ClipDescription.MIMETYPE_TEXT_URILIST.equals(description.getMimeType(0))) {
      return false;
    }
    return true;
  }

  private void writeClipboardText(@NonNull String text) {
    ClipboardManager clipboard =
        (ClipboardManager) mView.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
    if (clipboard != null) {
      clipboard.setPrimaryClip(ClipData.newPlainText("text label?", text));
    }
  }

  /**
   * Reads plain text from the first item of the primary clip, matching {@code PlatformPlugin}'s
   * {@code Clipboard.getData(kTextPlain)} semantics: only {@code item.getText()} or {@code
   * content://} URIs offering a {@code text/*} stream are coerced, and any styled {@link
   * CharSequence} spans (including {@code SPAN_COMPOSING}) are stripped via {@link
   * CharSequence#toString()}.
   *
   * <p>Note: For {@code content://} URIs, {@code ContentResolver} is queried synchronously on the
   * UI thread while the {@link ActionMode} click authorization window is active.
   */
  @Nullable
  static String readPlainTextFromClipboard(@NonNull Context context) {
    return readPlainTextFromClipboard(context, /* fallbackToCoerceToText= */ false);
  }

  /**
   * Reads plain text from the clipboard for IME-initiated {@link
   * InputConnection#performContextMenuAction(int)} pastes using a single {@link
   * ClipboardManager#getPrimaryClip()} call.
   *
   * <p>Prefers {@code PlatformPlugin}'s strict {@code content://} text-stream check and falls back
   * to {@link ClipData.Item#coerceToText(Context)} for non-{@code content://} URI or {@link Intent}
   * clips so existing IME paste behavior is preserved.
   */
  @Nullable
  static String readPlainTextFromClipboardForIme(@NonNull Context context) {
    return readPlainTextFromClipboard(context, /* fallbackToCoerceToText= */ true);
  }

  @Nullable
  private static String readPlainTextFromClipboard(
      @NonNull Context context, boolean fallbackToCoerceToText) {
    ClipboardManager clipboard =
        (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    if (clipboard == null || !clipboard.hasPrimaryClip()) {
      return null;
    }
    CharSequence itemText = null;
    try {
      ClipData clip = clipboard.getPrimaryClip();
      if (clip == null || clip.getItemCount() == 0) {
        return null;
      }
      // Match Flutter's Clipboard.getData semantics by reading item 0.
      ClipData.Item item = clip.getItemAt(0);
      itemText = item.getText();
      if (itemText == null) {
        if (fallbackToCoerceToText) {
          itemText = item.coerceToText(context);
        } else {
          Uri itemUri = item.getUri();
          if (itemUri == null || !"content".equals(itemUri.getScheme())) {
            return null;
          }
          AssetFileDescriptor assetFileDescriptor =
              context.getContentResolver().openTypedAssetFileDescriptor(itemUri, "text/*", null);
          itemText = item.coerceToText(context);
          if (assetFileDescriptor != null) {
            assetFileDescriptor.close();
          }
        }
      }
      return itemText != null ? itemText.toString() : null;
    } catch (SecurityException e) {
      Log.w(TAG, "Attempted to get clipboard data that requires additional permission(s).", e);
      return null;
    } catch (FileNotFoundException e) {
      Log.w(TAG, "Clipboard text was unable to be received from content URI.");
      return null;
    } catch (IOException e) {
      Log.w(TAG, "Failed to close AssetFileDescriptor while trying to read text from URI.", e);
      return itemText != null ? itemText.toString() : null;
    }
  }

  private void performShareAction(@NonNull String text) {
    Context context = mView.getContext();
    Activity activity = ViewUtils.getActivity(context);
    Intent intent = new Intent(Intent.ACTION_SEND);
    intent.setType("text/plain");
    intent.putExtra(Intent.EXTRA_TEXT, text);
    Intent chooser = Intent.createChooser(intent, null);
    Context launchContext = activity != null ? activity : context;
    if (activity == null) {
      chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
    try {
      launchContext.startActivity(chooser);
    } catch (ActivityNotFoundException | SecurityException e) {
      Log.w(TAG, "Unable to launch share activity.", e);
    }
  }

  private void performSearchWebAction(@NonNull String query) {
    Context context = mView.getContext();
    Activity activity = ViewUtils.getActivity(context);
    Intent intent = new Intent(Intent.ACTION_WEB_SEARCH);
    intent.putExtra(SearchManager.QUERY, query);
    Context launchContext = activity != null ? activity : context;
    if (activity == null) {
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
    try {
      launchContext.startActivity(intent);
    } catch (ActivityNotFoundException | SecurityException e) {
      Log.w(TAG, "Unable to launch web search activity.", e);
    }
  }

  private class SystemContextMenuCallback extends ActionMode.Callback2 {
    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
      populateSystemContextMenu(menu);
      if (menu.size() == 0) {
        return false;
      }
      systemContextMenuActionMode = mode;
      return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
      menu.clear();
      populateSystemContextMenu(menu);
      return menu.size() > 0;
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
      return handleSystemContextMenuAction(mode, item);
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
      if (systemContextMenuActionMode == mode) {
        final int dismissedClientId = systemContextMenuClientId;
        final Integer dismissedSessionId = systemContextMenuSessionId;
        clearSystemContextMenuState();
        notifySystemContextMenuDismissed(dismissedClientId, dismissedSessionId);
      }
    }

    @Override
    public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
      final PlatformChannel.SystemContextMenuRequest request = activeSystemContextMenuRequest;
      if (request == null) {
        super.onGetContentRect(mode, view, outRect);
        return;
      }
      final float density = view.getResources().getDisplayMetrics().density;
      final int left = (int) Math.floor(request.targetRect.x * density);
      final int top = (int) Math.floor(request.targetRect.y * density);
      final int right =
          Math.max(
              left + 1,
              (int) Math.ceil((request.targetRect.x + request.targetRect.width) * density));
      final int bottom =
          Math.max(
              top + 1,
              (int) Math.ceil((request.targetRect.y + request.targetRect.height) * density));
      outRect.set(left, top, right, bottom);
    }
  }
  // -------- End: System Context Menu -------
}
