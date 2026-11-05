package io.flutter.plugin.editing;

import static io.flutter.Build.API_LEVELS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.AdditionalMatchers.gt;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNotNull;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.provider.Settings;
import android.text.InputType;
import android.text.Selection;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewStructure;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import androidx.core.view.inputmethod.EditorInfoCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.flutter.embedding.android.FlutterView;
import io.flutter.embedding.android.KeyboardManager;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterJNI;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.loader.FlutterLoader;
import io.flutter.embedding.engine.renderer.FlutterRenderer;
import io.flutter.embedding.engine.systemchannels.PlatformChannel;
import io.flutter.embedding.engine.systemchannels.ScribeChannel;
import io.flutter.embedding.engine.systemchannels.TextInputChannel;
import io.flutter.embedding.engine.systemchannels.TextInputChannel.TextEditState;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.JSONMethodCodec;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.platform.PlatformViewsController;
import io.flutter.plugin.platform.PlatformViewsController2;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAutofillManager;
import org.robolectric.shadows.ShadowBuild;
import org.robolectric.shadows.ShadowInputMethodManager;

@Config(shadows = {TextInputPluginTest.TestImm.class, TextInputPluginTest.TestAfm.class})
@RunWith(AndroidJUnit4.class)
public class TextInputPluginTest {
  private final Context ctx = ApplicationProvider.getApplicationContext();
  @Mock FlutterJNI mockFlutterJni;
  @Mock FlutterLoader mockFlutterLoader;

  @Before
  public void setUp() {
    MockitoAnnotations.openMocks(this);
    when(mockFlutterJni.isAttached()).thenReturn(true);
  }

  // Verifies the method and arguments for a captured method call.
  private void verifyMethodCall(ByteBuffer buffer, String methodName, String[] expectedArgs)
      throws JSONException {
    buffer.rewind();
    MethodCall methodCall = JSONMethodCodec.INSTANCE.decodeMethodCall(buffer);
    assertEquals(methodName, methodCall.method);
    if (expectedArgs != null) {
      JSONArray args = methodCall.arguments();
      assertEquals(expectedArgs.length, args.length());
      for (int i = 0; i < args.length(); i++) {
        assertEquals(expectedArgs[i], args.get(i).toString());
      }
    }
  }

  private static void sendToBinaryMessageHandler(
      BinaryMessenger.BinaryMessageHandler binaryMessageHandler, String method, Object args) {
    MethodCall methodCall = new MethodCall(method, args);
    ByteBuffer encodedMethodCall = JSONMethodCodec.INSTANCE.encodeMethodCall(methodCall);
    binaryMessageHandler.onMessage(
        (ByteBuffer) encodedMethodCall.flip(), mock(BinaryMessenger.BinaryReply.class));
  }

  @SuppressWarnings("deprecation")
  // DartExecutor.send is deprecated.
  @Test
  public void textInputPlugin_RequestsReattachOnCreation() throws JSONException {
    // Initialize a general TextInputPlugin.
    InputMethodSubtype inputMethodSubtype = mock(InputMethodSubtype.class);
    TestImm testImm = Shadow.extract(ctx.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setCurrentInputMethodSubtype(inputMethodSubtype);
    View testView = new View(ctx);

    FlutterJNI mockFlutterJni = mock(FlutterJNI.class);
    DartExecutor dartExecutor = spy(new DartExecutor(mockFlutterJni, mock(AssetManager.class)));
    TextInputChannel textInputChannel = new TextInputChannel(dartExecutor);
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));

    ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<ByteBuffer> bufferCaptor = ArgumentCaptor.forClass(ByteBuffer.class);

    verify(dartExecutor, times(1)).send(channelCaptor.capture(), bufferCaptor.capture(), isNull());
    assertEquals("flutter/textinput", channelCaptor.getValue());
    verifyMethodCall(bufferCaptor.getValue(), "TextInputClient.requestExistingInputState", null);
  }

  @Test
  public void setTextInputEditingState_doesNotInvokeUpdateEditingState() {
    // Initialize a general TextInputPlugin.
    InputMethodSubtype inputMethodSubtype = mock(InputMethodSubtype.class);
    TestImm testImm = Shadow.extract(ctx.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setCurrentInputMethodSubtype(inputMethodSubtype);
    View testView = new View(ctx);
    TextInputChannel textInputChannel = spy(new TextInputChannel(mock(DartExecutor.class)));
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            false,
            TextInputChannel.TextCapitalization.NONE,
            null,
            null,
            null,
            null,
            null,
            null,
            null));

    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("initial input from framework", 0, 0, -1, -1));
    assertEquals("initial input from framework", textInputPlugin.getEditable().toString());

    verify(textInputChannel, times(0))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());

    textInputPlugin.setTextInputEditingState(
        testView,
        new TextInputChannel.TextEditState("more update from the framework", 1, 2, -1, -1));

    assertEquals("more update from the framework", textInputPlugin.getEditable().toString());
    verify(textInputChannel, times(0))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());
  }

  @Test
  public void setTextInputEditingState_willNotThrowWithoutSetTextInputClient() {
    // Initialize a general TextInputPlugin.
    InputMethodSubtype inputMethodSubtype = mock(InputMethodSubtype.class);
    TestImm testImm = Shadow.extract(ctx.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setCurrentInputMethodSubtype(inputMethodSubtype);
    View testView = new View(ctx);
    TextInputChannel textInputChannel = spy(new TextInputChannel(mock(DartExecutor.class)));
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));

    // Here's no textInputPlugin.setTextInputClient()
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("initial input from framework", 0, 0, -1, -1));
    assertEquals("initial input from framework", textInputPlugin.getEditable().toString());
  }

  @Test
  public void setTextInputEditingState_doesNotInvokeUpdateEditingStateWithDeltas() {
    // Initialize a general TextInputPlugin.
    InputMethodSubtype inputMethodSubtype = mock(InputMethodSubtype.class);
    TestImm testImm = Shadow.extract(ctx.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setCurrentInputMethodSubtype(inputMethodSubtype);
    View testView = new View(ctx);
    TextInputChannel textInputChannel = spy(new TextInputChannel(mock(DartExecutor.class)));
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            true, // Enable delta model.
            TextInputChannel.TextCapitalization.NONE,
            null,
            null,
            null,
            null,
            null,
            null,
            null));

    textInputPlugin.setTextInputEditingState(
        testView,
        new TextInputChannel.TextEditState("receiving initial input from framework", 0, 0, -1, -1));
    assertEquals(
        "receiving initial input from framework", textInputPlugin.getEditable().toString());

    verify(textInputChannel, times(0)).updateEditingStateWithDeltas(anyInt(), any());

    textInputPlugin.setTextInputEditingState(
        testView,
        new TextInputChannel.TextEditState(
            "receiving more updates from the framework", 1, 2, -1, -1));

    assertEquals(
        "receiving more updates from the framework", textInputPlugin.getEditable().toString());
    verify(textInputChannel, times(0)).updateEditingStateWithDeltas(anyInt(), any());
  }

  @Test
  public void textEditingDelta_TestUpdateEditingValueWithDeltasIsNotInvokedWhenDeltaModelDisabled()
      throws NullPointerException {
    // Initialize a general TextInputPlugin.
    InputMethodSubtype inputMethodSubtype = mock(InputMethodSubtype.class);
    TestImm testImm = Shadow.extract(ctx.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setCurrentInputMethodSubtype(inputMethodSubtype);
    View testView = new View(ctx);
    EditorInfo outAttrs = new EditorInfo();
    outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
    TextInputChannel textInputChannel = spy(new TextInputChannel(mock(DartExecutor.class)));
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    CharSequence newText = "I do not fear computers. I fear the lack of them.";

    // Change InputTarget to FRAMEWORK_CLIENT.
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            false, // Delta model is disabled.
            TextInputChannel.TextCapitalization.NONE,
            new TextInputChannel.InputType(
                TextInputChannel.TextInputType.TEXT, false, false, false),
            null,
            null,
            null,
            null,
            null,
            null));

    // There's a pending restart since we initialized the text input client. Flush that now.
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("", 0, 0, -1, -1));
    verify(textInputChannel, times(0)).updateEditingStateWithDeltas(anyInt(), any());
    verify(textInputChannel, times(0))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());
    assertEquals(
        0,
        ((ListenableEditingState) textInputPlugin.getEditable())
            .extractBatchTextEditingDeltas()
            .size());

    InputConnection inputConnection =
        textInputPlugin.createInputConnection(testView, mock(KeyboardManager.class), outAttrs);

    inputConnection.beginBatchEdit();
    verify(textInputChannel, times(0)).updateEditingStateWithDeltas(anyInt(), any());
    verify(textInputChannel, times(0))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());
    inputConnection.setComposingText(newText, newText.length());
    verify(textInputChannel, times(0)).updateEditingStateWithDeltas(anyInt(), any());
    verify(textInputChannel, times(0))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());
    inputConnection.endBatchEdit();

    assertEquals(
        0,
        ((ListenableEditingState) textInputPlugin.getEditable())
            .extractBatchTextEditingDeltas()
            .size());

    verify(textInputChannel, times(0)).updateEditingStateWithDeltas(anyInt(), any());
    verify(textInputChannel, times(1))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());

    inputConnection.beginBatchEdit();

    verify(textInputChannel, times(0)).updateEditingStateWithDeltas(anyInt(), any());
    verify(textInputChannel, times(1))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());

    inputConnection.endBatchEdit();

    assertEquals(
        0,
        ((ListenableEditingState) textInputPlugin.getEditable())
            .extractBatchTextEditingDeltas()
            .size());

    verify(textInputChannel, times(0)).updateEditingStateWithDeltas(anyInt(), any());
    verify(textInputChannel, times(1))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());

    inputConnection.beginBatchEdit();

    verify(textInputChannel, times(0)).updateEditingStateWithDeltas(anyInt(), any());
    verify(textInputChannel, times(1))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());

    // Selection changes so this will trigger an update to the framework through
    // updateEditingStateWithDeltas after the batch edit has completed and notified all listeners
    // of the editing state.
    inputConnection.setSelection(3, 4);
    assertEquals(3, Selection.getSelectionStart(textInputPlugin.getEditable()));
    assertEquals(4, Selection.getSelectionEnd(textInputPlugin.getEditable()));

    verify(textInputChannel, times(0)).updateEditingStateWithDeltas(anyInt(), any());
    verify(textInputChannel, times(1))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());

    verify(textInputChannel, times(0)).updateEditingStateWithDeltas(anyInt(), any());
    verify(textInputChannel, times(1))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());

    inputConnection.endBatchEdit();

    verify(textInputChannel, times(0)).updateEditingStateWithDeltas(anyInt(), any());
    verify(textInputChannel, times(2))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());
  }

  @Test
  public void textEditingDelta_TestUpdateEditingValueIsNotInvokedWhenDeltaModelEnabled()
      throws NullPointerException {
    // Initialize a general TextInputPlugin.
    InputMethodSubtype inputMethodSubtype = mock(InputMethodSubtype.class);
    TestImm testImm = Shadow.extract(ctx.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setCurrentInputMethodSubtype(inputMethodSubtype);
    View testView = new View(ctx);
    EditorInfo outAttrs = new EditorInfo();
    outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
    TextInputChannel textInputChannel = spy(new TextInputChannel(mock(DartExecutor.class)));
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    CharSequence newText = "I do not fear computers. I fear the lack of them.";
    final TextEditingDelta expectedDelta =
        new TextEditingDelta("", 0, 0, newText, newText.length(), newText.length(), 0, 49);

    // Change InputTarget to FRAMEWORK_CLIENT.
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            true, // Enable delta model.
            TextInputChannel.TextCapitalization.NONE,
            new TextInputChannel.InputType(
                TextInputChannel.TextInputType.TEXT, false, false, false),
            null,
            null,
            null,
            null,
            null,
            null));

    // There's a pending restart since we initialized the text input client. Flush that now.
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("", 0, 0, -1, -1));
    verify(textInputChannel, times(0)).updateEditingStateWithDeltas(anyInt(), any());
    verify(textInputChannel, times(0))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());
    assertEquals(
        0,
        ((ListenableEditingState) textInputPlugin.getEditable())
            .extractBatchTextEditingDeltas()
            .size());

    InputConnection inputConnection =
        textInputPlugin.createInputConnection(testView, mock(KeyboardManager.class), outAttrs);

    inputConnection.beginBatchEdit();
    verify(textInputChannel, times(0)).updateEditingStateWithDeltas(anyInt(), any());
    verify(textInputChannel, times(0))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());
    inputConnection.setComposingText(newText, newText.length());
    final ArrayList<TextEditingDelta> actualDeltas =
        ((ListenableEditingState) textInputPlugin.getEditable()).extractBatchTextEditingDeltas();
    assertEquals(2, actualDeltas.size());
    final TextEditingDelta delta = actualDeltas.get(1);
    verify(textInputChannel, times(0)).updateEditingStateWithDeltas(anyInt(), any());
    verify(textInputChannel, times(0))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());
    inputConnection.endBatchEdit();

    assertEquals(
        0,
        ((ListenableEditingState) textInputPlugin.getEditable())
            .extractBatchTextEditingDeltas()
            .size());

    // Verify delta is what we expect.
    assertEquals(expectedDelta.getOldText(), delta.getOldText());
    assertEquals(expectedDelta.getDeltaText(), delta.getDeltaText());
    assertEquals(expectedDelta.getDeltaStart(), delta.getDeltaStart());
    assertEquals(expectedDelta.getDeltaEnd(), delta.getDeltaEnd());
    assertEquals(expectedDelta.getNewSelectionStart(), delta.getNewSelectionStart());
    assertEquals(expectedDelta.getNewSelectionEnd(), delta.getNewSelectionEnd());
    assertEquals(expectedDelta.getNewComposingStart(), delta.getNewComposingStart());
    assertEquals(expectedDelta.getNewComposingEnd(), delta.getNewComposingEnd());

    verify(textInputChannel, times(1)).updateEditingStateWithDeltas(anyInt(), any());
    verify(textInputChannel, times(0))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());

    inputConnection.beginBatchEdit();

    verify(textInputChannel, times(1)).updateEditingStateWithDeltas(anyInt(), any());
    verify(textInputChannel, times(0))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());

    inputConnection.endBatchEdit();

    assertEquals(
        0,
        ((ListenableEditingState) textInputPlugin.getEditable())
            .extractBatchTextEditingDeltas()
            .size());

    verify(textInputChannel, times(1)).updateEditingStateWithDeltas(anyInt(), any());
    verify(textInputChannel, times(0))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());

    inputConnection.beginBatchEdit();

    verify(textInputChannel, times(1)).updateEditingStateWithDeltas(anyInt(), any());
    verify(textInputChannel, times(0))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());

    // Selection changes so this will trigger an update to the framework through
    // updateEditingStateWithDeltas after the batch edit has completed and notified all listeners
    // of the editing state.
    inputConnection.setSelection(3, 4);
    assertEquals(3, Selection.getSelectionStart(textInputPlugin.getEditable()));
    assertEquals(4, Selection.getSelectionEnd(textInputPlugin.getEditable()));

    verify(textInputChannel, times(1)).updateEditingStateWithDeltas(anyInt(), any());
    verify(textInputChannel, times(0))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());

    verify(textInputChannel, times(1)).updateEditingStateWithDeltas(anyInt(), any());
    verify(textInputChannel, times(0))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());

    inputConnection.endBatchEdit();

    verify(textInputChannel, times(2)).updateEditingStateWithDeltas(anyInt(), any());
    verify(textInputChannel, times(0))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());
  }

  @Test
  public void textEditingDelta_TestDeltaIsCreatedWhenComposingTextSetIsInserting()
      throws NullPointerException {
    // Initialize a general TextInputPlugin.
    InputMethodSubtype inputMethodSubtype = mock(InputMethodSubtype.class);
    TestImm testImm = Shadow.extract(ctx.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setCurrentInputMethodSubtype(inputMethodSubtype);
    View testView = new View(ctx);
    EditorInfo outAttrs = new EditorInfo();
    outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
    TextInputChannel textInputChannel = spy(new TextInputChannel(mock(DartExecutor.class)));
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    CharSequence newText = "I do not fear computers. I fear the lack of them.";
    final TextEditingDelta expectedDelta =
        new TextEditingDelta("", 0, 0, newText, newText.length(), newText.length(), 0, 49);

    // Change InputTarget to FRAMEWORK_CLIENT.
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            true, // Enable delta model.
            TextInputChannel.TextCapitalization.NONE,
            new TextInputChannel.InputType(
                TextInputChannel.TextInputType.TEXT, false, false, false),
            null,
            null,
            null,
            null,
            null,
            null));

    // There's a pending restart since we initialized the text input client. Flush that now.
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("", 0, 0, -1, -1));
    verify(textInputChannel, times(0)).updateEditingStateWithDeltas(anyInt(), any());
    assertEquals(
        0,
        ((ListenableEditingState) textInputPlugin.getEditable())
            .extractBatchTextEditingDeltas()
            .size());

    InputConnection inputConnection =
        textInputPlugin.createInputConnection(testView, mock(KeyboardManager.class), outAttrs);

    inputConnection.beginBatchEdit();
    verify(textInputChannel, times(0)).updateEditingStateWithDeltas(anyInt(), any());
    inputConnection.setComposingText(newText, newText.length());
    final ArrayList<TextEditingDelta> actualDeltas =
        ((ListenableEditingState) textInputPlugin.getEditable()).extractBatchTextEditingDeltas();
    assertEquals(2, actualDeltas.size());
    final TextEditingDelta delta = actualDeltas.get(1);
    verify(textInputChannel, times(0)).updateEditingStateWithDeltas(anyInt(), any());
    inputConnection.endBatchEdit();

    assertEquals(
        0,
        ((ListenableEditingState) textInputPlugin.getEditable())
            .extractBatchTextEditingDeltas()
            .size());

    // Verify delta is what we expect.
    assertEquals(expectedDelta.getOldText(), delta.getOldText());
    assertEquals(expectedDelta.getDeltaText(), delta.getDeltaText());
    assertEquals(expectedDelta.getDeltaStart(), delta.getDeltaStart());
    assertEquals(expectedDelta.getDeltaEnd(), delta.getDeltaEnd());
    assertEquals(expectedDelta.getNewSelectionStart(), delta.getNewSelectionStart());
    assertEquals(expectedDelta.getNewSelectionEnd(), delta.getNewSelectionEnd());
    assertEquals(expectedDelta.getNewComposingStart(), delta.getNewComposingStart());
    assertEquals(expectedDelta.getNewComposingEnd(), delta.getNewComposingEnd());

    verify(textInputChannel, times(1)).updateEditingStateWithDeltas(anyInt(), any());

    inputConnection.beginBatchEdit();

    verify(textInputChannel, times(1)).updateEditingStateWithDeltas(anyInt(), any());

    inputConnection.endBatchEdit();

    assertEquals(
        0,
        ((ListenableEditingState) textInputPlugin.getEditable())
            .extractBatchTextEditingDeltas()
            .size());

    verify(textInputChannel, times(1)).updateEditingStateWithDeltas(anyInt(), any());

    inputConnection.beginBatchEdit();

    verify(textInputChannel, times(1)).updateEditingStateWithDeltas(anyInt(), any());

    // Selection changes so this will trigger an update to the framework through
    // updateEditingStateWithDeltas after the batch edit has completed and notified all listeners
    // of the editing state.
    inputConnection.setSelection(3, 4);
    assertEquals(3, Selection.getSelectionStart(textInputPlugin.getEditable()));
    assertEquals(4, Selection.getSelectionEnd(textInputPlugin.getEditable()));

    verify(textInputChannel, times(1)).updateEditingStateWithDeltas(anyInt(), any());

    verify(textInputChannel, times(1)).updateEditingStateWithDeltas(anyInt(), any());

    inputConnection.endBatchEdit();

    verify(textInputChannel, times(2)).updateEditingStateWithDeltas(anyInt(), any());
  }

  @Test
  public void textEditingDelta_TestDeltaIsCreatedWhenComposingTextSetIsDeleting()
      throws NullPointerException {
    // Initialize a general TextInputPlugin.
    InputMethodSubtype inputMethodSubtype = mock(InputMethodSubtype.class);
    TestImm testImm = Shadow.extract(ctx.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setCurrentInputMethodSubtype(inputMethodSubtype);
    View testView = new View(ctx);
    EditorInfo outAttrs = new EditorInfo();
    outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
    TextInputChannel textInputChannel = spy(new TextInputChannel(mock(DartExecutor.class)));
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    CharSequence newText = "I do not fear computers. I fear the lack of them.";
    final TextEditingDelta expectedDelta =
        new TextEditingDelta(
            newText, 0, 49, "I do not fear computers. I fear the lack of them", 48, 48, 0, 48);

    // Change InputTarget to FRAMEWORK_CLIENT.
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            true, // Enable delta model.
            TextInputChannel.TextCapitalization.NONE,
            new TextInputChannel.InputType(
                TextInputChannel.TextInputType.TEXT, false, false, false),
            null,
            null,
            null,
            null,
            null,
            null));

    // There's a pending restart since we initialized the text input client. Flush that now.
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState(newText.toString(), 49, 49, 0, 49));
    verify(textInputChannel, times(0)).updateEditingStateWithDeltas(anyInt(), any());
    assertEquals(
        0,
        ((ListenableEditingState) textInputPlugin.getEditable())
            .extractBatchTextEditingDeltas()
            .size());

    InputConnection inputConnection =
        textInputPlugin.createInputConnection(testView, mock(KeyboardManager.class), outAttrs);

    inputConnection.beginBatchEdit();
    verify(textInputChannel, times(0)).updateEditingStateWithDeltas(anyInt(), any());
    inputConnection.setComposingText("I do not fear computers. I fear the lack of them", 48);
    final ArrayList<TextEditingDelta> actualDeltas =
        ((ListenableEditingState) textInputPlugin.getEditable()).extractBatchTextEditingDeltas();
    final TextEditingDelta delta = actualDeltas.get(1);
    System.out.println(delta.getDeltaText());
    verify(textInputChannel, times(0)).updateEditingStateWithDeltas(anyInt(), any());
    inputConnection.endBatchEdit();

    assertEquals(
        0,
        ((ListenableEditingState) textInputPlugin.getEditable())
            .extractBatchTextEditingDeltas()
            .size());

    // Verify delta is what we expect.
    assertEquals(expectedDelta.getOldText(), delta.getOldText());
    assertEquals(expectedDelta.getDeltaText(), delta.getDeltaText());
    assertEquals(expectedDelta.getDeltaStart(), delta.getDeltaStart());
    assertEquals(expectedDelta.getDeltaEnd(), delta.getDeltaEnd());
    assertEquals(expectedDelta.getNewSelectionStart(), delta.getNewSelectionStart());
    assertEquals(expectedDelta.getNewSelectionEnd(), delta.getNewSelectionEnd());
    assertEquals(expectedDelta.getNewComposingStart(), delta.getNewComposingStart());
    assertEquals(expectedDelta.getNewComposingEnd(), delta.getNewComposingEnd());

    verify(textInputChannel, times(1)).updateEditingStateWithDeltas(anyInt(), any());

    inputConnection.beginBatchEdit();

    verify(textInputChannel, times(1)).updateEditingStateWithDeltas(anyInt(), any());

    inputConnection.endBatchEdit();

    assertEquals(
        0,
        ((ListenableEditingState) textInputPlugin.getEditable())
            .extractBatchTextEditingDeltas()
            .size());

    verify(textInputChannel, times(1)).updateEditingStateWithDeltas(anyInt(), any());

    inputConnection.beginBatchEdit();

    verify(textInputChannel, times(1)).updateEditingStateWithDeltas(anyInt(), any());

    // Selection changes so this will trigger an update to the framework through
    // updateEditingStateWithDeltas after the batch edit has completed and notified all listeners
    // of the editing state.
    inputConnection.setSelection(3, 4);
    assertEquals(3, Selection.getSelectionStart(textInputPlugin.getEditable()));
    assertEquals(4, Selection.getSelectionEnd(textInputPlugin.getEditable()));

    verify(textInputChannel, times(1)).updateEditingStateWithDeltas(anyInt(), any());

    verify(textInputChannel, times(1)).updateEditingStateWithDeltas(anyInt(), any());

    inputConnection.endBatchEdit();

    verify(textInputChannel, times(2)).updateEditingStateWithDeltas(anyInt(), any());
  }

  @Test
  public void textEditingDelta_TestDeltaIsCreatedWhenComposingTextSetIsReplacing()
      throws NullPointerException {
    // Initialize a general TextInputPlugin.
    InputMethodSubtype inputMethodSubtype = mock(InputMethodSubtype.class);
    TestImm testImm = Shadow.extract(ctx.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setCurrentInputMethodSubtype(inputMethodSubtype);
    View testView = new View(ctx);
    EditorInfo outAttrs = new EditorInfo();
    outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
    TextInputChannel textInputChannel = spy(new TextInputChannel(mock(DartExecutor.class)));
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    CharSequence newText = "helfo";
    final TextEditingDelta expectedDelta = new TextEditingDelta(newText, 0, 5, "hello", 5, 5, 0, 5);

    // Change InputTarget to FRAMEWORK_CLIENT.
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            true, // Enable delta model.
            TextInputChannel.TextCapitalization.NONE,
            new TextInputChannel.InputType(
                TextInputChannel.TextInputType.TEXT, false, false, false),
            null,
            null,
            null,
            null,
            null,
            null));

    // There's a pending restart since we initialized the text input client. Flush that now.
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState(newText.toString(), 5, 5, 0, 5));
    verify(textInputChannel, times(0)).updateEditingStateWithDeltas(anyInt(), any());
    assertEquals(
        0,
        ((ListenableEditingState) textInputPlugin.getEditable())
            .extractBatchTextEditingDeltas()
            .size());

    InputConnection inputConnection =
        textInputPlugin.createInputConnection(testView, mock(KeyboardManager.class), outAttrs);

    inputConnection.beginBatchEdit();
    verify(textInputChannel, times(0)).updateEditingStateWithDeltas(anyInt(), any());
    inputConnection.setComposingText("hello", 5);
    final ArrayList<TextEditingDelta> actualDeltas =
        ((ListenableEditingState) textInputPlugin.getEditable()).extractBatchTextEditingDeltas();
    final TextEditingDelta delta = actualDeltas.get(1);
    System.out.println(delta.getDeltaText());
    verify(textInputChannel, times(0)).updateEditingStateWithDeltas(anyInt(), any());
    inputConnection.endBatchEdit();

    assertEquals(
        0,
        ((ListenableEditingState) textInputPlugin.getEditable())
            .extractBatchTextEditingDeltas()
            .size());

    // Verify delta is what we expect.
    assertEquals(expectedDelta.getOldText(), delta.getOldText());
    assertEquals(expectedDelta.getDeltaText(), delta.getDeltaText());
    assertEquals(expectedDelta.getDeltaStart(), delta.getDeltaStart());
    assertEquals(expectedDelta.getDeltaEnd(), delta.getDeltaEnd());
    assertEquals(expectedDelta.getNewSelectionStart(), delta.getNewSelectionStart());
    assertEquals(expectedDelta.getNewSelectionEnd(), delta.getNewSelectionEnd());
    assertEquals(expectedDelta.getNewComposingStart(), delta.getNewComposingStart());
    assertEquals(expectedDelta.getNewComposingEnd(), delta.getNewComposingEnd());

    verify(textInputChannel, times(1)).updateEditingStateWithDeltas(anyInt(), any());

    inputConnection.beginBatchEdit();

    verify(textInputChannel, times(1)).updateEditingStateWithDeltas(anyInt(), any());

    inputConnection.endBatchEdit();

    assertEquals(
        0,
        ((ListenableEditingState) textInputPlugin.getEditable())
            .extractBatchTextEditingDeltas()
            .size());

    verify(textInputChannel, times(1)).updateEditingStateWithDeltas(anyInt(), any());

    inputConnection.beginBatchEdit();

    verify(textInputChannel, times(1)).updateEditingStateWithDeltas(anyInt(), any());

    // Selection changes so this will trigger an update to the framework through
    // updateEditingStateWithDeltas after the batch edit has completed and notified all listeners
    // of the editing state.
    inputConnection.setSelection(3, 4);
    assertEquals(3, Selection.getSelectionStart(textInputPlugin.getEditable()));
    assertEquals(4, Selection.getSelectionEnd(textInputPlugin.getEditable()));

    verify(textInputChannel, times(1)).updateEditingStateWithDeltas(anyInt(), any());

    verify(textInputChannel, times(1)).updateEditingStateWithDeltas(anyInt(), any());

    inputConnection.endBatchEdit();

    verify(textInputChannel, times(2)).updateEditingStateWithDeltas(anyInt(), any());
  }

  @Test
  public void inputConnectionAdaptor_RepeatFilter() throws NullPointerException {
    // Initialize a general TextInputPlugin.
    InputMethodSubtype inputMethodSubtype = mock(InputMethodSubtype.class);
    TestImm testImm = Shadow.extract(ctx.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setCurrentInputMethodSubtype(inputMethodSubtype);
    View testView = new View(ctx);
    EditorInfo outAttrs = new EditorInfo();
    outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
    TextInputChannel textInputChannel = spy(new TextInputChannel(mock(DartExecutor.class)));
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));

    // Change InputTarget to FRAMEWORK_CLIENT.
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            false,
            TextInputChannel.TextCapitalization.NONE,
            new TextInputChannel.InputType(
                TextInputChannel.TextInputType.TEXT, false, false, false),
            null,
            null,
            null,
            null,
            null,
            null));

    // There's a pending restart since we initialized the text input client. Flush that now.
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("", 0, 0, -1, -1));
    verify(textInputChannel, times(0))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());

    InputConnectionAdaptor inputConnectionAdaptor =
        (InputConnectionAdaptor)
            textInputPlugin.createInputConnection(testView, mock(KeyboardManager.class), outAttrs);

    inputConnectionAdaptor.beginBatchEdit();
    verify(textInputChannel, times(0))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());
    inputConnectionAdaptor.setComposingText("I do not fear computers. I fear the lack of them.", 1);
    verify(textInputChannel, times(0))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());
    inputConnectionAdaptor.endBatchEdit();
    verify(textInputChannel, times(1))
        .updateEditingState(
            anyInt(),
            eq("I do not fear computers. I fear the lack of them."),
            eq(49),
            eq(49),
            eq(0),
            eq(49));

    inputConnectionAdaptor.beginBatchEdit();

    verify(textInputChannel, times(1))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());

    inputConnectionAdaptor.endBatchEdit();

    verify(textInputChannel, times(1))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());

    inputConnectionAdaptor.beginBatchEdit();

    verify(textInputChannel, times(1))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());

    inputConnectionAdaptor.setSelection(3, 4);
    assertEquals(3, Selection.getSelectionStart(textInputPlugin.getEditable()));
    assertEquals(4, Selection.getSelectionEnd(textInputPlugin.getEditable()));

    verify(textInputChannel, times(1))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());

    verify(textInputChannel, times(1))
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());

    inputConnectionAdaptor.endBatchEdit();

    verify(textInputChannel, times(1))
        .updateEditingState(
            anyInt(),
            eq("I do not fear computers. I fear the lack of them."),
            eq(3),
            eq(4),
            eq(0),
            eq(49));
  }

  @Test
  public void setTextInputEditingState_doesNotRestartWhenTextIsIdentical() {
    // Initialize a general TextInputPlugin.
    InputMethodSubtype inputMethodSubtype = mock(InputMethodSubtype.class);
    TestImm testImm = Shadow.extract(ctx.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setCurrentInputMethodSubtype(inputMethodSubtype);
    View testView = new View(ctx);
    TextInputChannel textInputChannel = new TextInputChannel(mock(DartExecutor.class));
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            false,
            TextInputChannel.TextCapitalization.NONE,
            null,
            null,
            null,
            null,
            null,
            null,
            null));
    // There's a pending restart since we initialized the text input client. Flush that now.
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("", 0, 0, -1, -1));

    // Move the cursor.
    assertEquals(1, testImm.getRestartCount(testView));
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("", 0, 0, -1, -1));

    // Verify that we haven't restarted the input.
    assertEquals(1, testImm.getRestartCount(testView));
  }

  @Test
  public void setTextInputEditingState_alwaysSetEditableWhenDifferent() {
    // Initialize a general TextInputPlugin.
    InputMethodSubtype inputMethodSubtype = mock(InputMethodSubtype.class);
    TestImm testImm = Shadow.extract(ctx.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setCurrentInputMethodSubtype(inputMethodSubtype);
    View testView = new View(ctx);
    TextInputChannel textInputChannel = new TextInputChannel(mock(DartExecutor.class));
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            false,
            TextInputChannel.TextCapitalization.NONE,
            null,
            null,
            null,
            null,
            null,
            null,
            null));
    // There's a pending restart since we initialized the text input client. Flush that now. With
    // changed text, we should
    // always set the Editable contents.
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("hello", 0, 0, -1, -1));
    assertEquals(1, testImm.getRestartCount(testView));
    assertEquals("hello", textInputPlugin.getEditable().toString());

    // No pending restart, set Editable contents anyways.
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("Shibuyawoo", 0, 0, -1, -1));
    assertEquals(1, testImm.getRestartCount(testView));
    assertEquals("Shibuyawoo", textInputPlugin.getEditable().toString());
  }

  // See https://github.com/flutter/flutter/issues/29341 and
  // https://github.com/flutter/flutter/issues/31512
  // All modern Samsung keyboards are affected including non-korean languages and thus
  // need the restart.
  // Update: many other keyboards need this too:
  // https://github.com/flutter/flutter/issues/78827
  @SuppressWarnings("deprecation") // InputMethodSubtype
  @Test
  public void setTextInputEditingState_restartsIMEOnlyWhenFrameworkChangesComposingRegion() {
    // Initialize a TextInputPlugin that needs to be always restarted.
    InputMethodSubtype inputMethodSubtype =
        new InputMethodSubtype(0, 0, /*locale=*/ "en", "", "", false, false);
    TestImm testImm = Shadow.extract(ctx.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setCurrentInputMethodSubtype(inputMethodSubtype);
    View testView = new View(ctx);
    TextInputChannel textInputChannel = new TextInputChannel(mock(DartExecutor.class));
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            false,
            TextInputChannel.TextCapitalization.NONE,
            new TextInputChannel.InputType(
                TextInputChannel.TextInputType.TEXT, false, false, false),
            null,
            null,
            null,
            null,
            null,
            null));
    // There's a pending restart since we initialized the text input client. Flush that now.
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("", 0, 0, -1, -1));
    assertEquals(1, testImm.getRestartCount(testView));
    InputConnection connection =
        textInputPlugin.createInputConnection(
            testView, mock(KeyboardManager.class), new EditorInfo());
    connection.setComposingText("POWERRRRR", 1);

    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("UNLIMITED POWERRRRR", 0, 0, 10, 19));
    // Does not restart since the composing text is not changed.
    assertEquals(1, testImm.getRestartCount(testView));

    connection.finishComposingText();
    // Does not restart since the composing text is committed by the IME.
    assertEquals(1, testImm.getRestartCount(testView));

    // Does not restart since the composing text is changed by the IME.
    connection.setComposingText("POWERRRRR", 1);
    assertEquals(1, testImm.getRestartCount(testView));

    // The framework tries to commit the composing region.
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("POWERRRRR", 0, 0, -1, -1));

    // Verify that we've restarted the input.
    assertEquals(2, testImm.getRestartCount(testView));
  }

  @Test
  public void TextEditState_throwsOnInvalidStatesReceived() {
    // Index OOB:
    assertThrows(IndexOutOfBoundsException.class, () -> new TextEditState("", 0, -9, -1, -1));
    assertThrows(IndexOutOfBoundsException.class, () -> new TextEditState("", -9, 0, -1, -1));
    assertThrows(IndexOutOfBoundsException.class, () -> new TextEditState("", 0, 1, -1, -1));
    assertThrows(IndexOutOfBoundsException.class, () -> new TextEditState("", 1, 0, -1, -1));
    assertThrows(IndexOutOfBoundsException.class, () -> new TextEditState("Text", 0, 0, 1, 5));
    assertThrows(IndexOutOfBoundsException.class, () -> new TextEditState("Text", 0, 0, 5, 1));
    assertThrows(IndexOutOfBoundsException.class, () -> new TextEditState("Text", 0, 0, 5, 5));

    // Invalid Selections:
    assertThrows(IndexOutOfBoundsException.class, () -> new TextEditState("", -1, -2, -1, -1));
    assertThrows(IndexOutOfBoundsException.class, () -> new TextEditState("", -2, -1, -1, -1));
    assertThrows(IndexOutOfBoundsException.class, () -> new TextEditState("", -9, -9, -1, -1));

    // Invalid Composing Ranges:
    assertThrows(IndexOutOfBoundsException.class, () -> new TextEditState("Text", 0, 0, -9, -1));
    assertThrows(IndexOutOfBoundsException.class, () -> new TextEditState("Text", 0, 0, -1, -9));
    assertThrows(IndexOutOfBoundsException.class, () -> new TextEditState("Text", 0, 0, -9, -9));
    assertThrows(IndexOutOfBoundsException.class, () -> new TextEditState("Text", 0, 0, 2, 1));

    // Valid values (does not throw):
    // Nothing selected/composing:
    TextEditState state = new TextEditState("", -1, -1, -1, -1);
    assertEquals("", state.text);
    assertEquals(-1, state.selectionStart);
    assertEquals(-1, state.selectionEnd);
    assertEquals(-1, state.composingStart);
    assertEquals(-1, state.composingEnd);
    // Collapsed selection.
    state = new TextEditState("x", 0, 0, 0, 1);
    assertEquals(0, state.selectionStart);
    assertEquals(0, state.selectionEnd);
    // Reversed Selection.
    state = new TextEditState("REEEE", 4, 2, -1, -1);
    assertEquals(4, state.selectionStart);
    assertEquals(2, state.selectionEnd);
    // A collapsed selection and composing range.
    state = new TextEditState("text", 0, 0, 0, 0);
    assertEquals("text", state.text);
    assertEquals(0, state.selectionStart);
    assertEquals(0, state.selectionEnd);
    assertEquals(0, state.composingStart);
    assertEquals(0, state.composingEnd);
  }

  @Test
  public void setTextInputEditingState_nullInputMethodSubtype() {
    TestImm testImm = Shadow.extract(ctx.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setCurrentInputMethodSubtype(null);

    View testView = new View(ctx);
    TextInputChannel textInputChannel = new TextInputChannel(mock(DartExecutor.class));
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            false,
            TextInputChannel.TextCapitalization.NONE,
            null,
            null,
            null,
            null,
            null,
            null,
            null));
    // There's a pending restart since we initialized the text input client. Flush that now.
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("", 0, 0, -1, -1));
    assertEquals(1, testImm.getRestartCount(testView));
  }

  @Test
  public void imeVisibilityListener_restartsImmWhenIMEHidden() {
    // Initialize a general TextInputPlugin.
    InputMethodSubtype inputMethodSubtype = mock(InputMethodSubtype.class);
    TestImm testImm = Shadow.extract(ctx.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setCurrentInputMethodSubtype(inputMethodSubtype);
    View testView = new View(ctx);
    TextInputChannel textInputChannel = new TextInputChannel(mock(DartExecutor.class));
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            false,
            TextInputChannel.TextCapitalization.NONE,
            null,
            null,
            null,
            null,
            null,
            null,
            null));
    // There's a pending restart since we initialized the text input client. Flush that now.
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("", 0, 0, -1, -1));
    assertEquals(1, testImm.getRestartCount(testView));

    // Imm restarts when IME is hidden.
    ImeSyncDeferringInsetsCallback imeSyncCallback = textInputPlugin.getImeSyncCallback();
    imeSyncCallback.getImeVisibilityListener().onImeVisibilityChanged(false);
    assertEquals(2, testImm.getRestartCount(testView));
  }

  @Test
  public void clearTextInputClient_restartsImmWhenIMEHidden() {
    try (ActivityScenario<Activity> scenario = ActivityScenario.launch(Activity.class)) {
      scenario.onActivity(
          activity -> {
            FlutterView testView = spy(new FlutterView(activity));

            InputMethodSubtype inputMethodSubtype = mock(InputMethodSubtype.class);
            TestImm testImm = Shadow.extract(ctx.getSystemService(Context.INPUT_METHOD_SERVICE));
            testImm.setCurrentInputMethodSubtype(inputMethodSubtype);
            TextInputChannel textInputChannel = new TextInputChannel(mock(DartExecutor.class));
            ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
            TextInputPlugin textInputPlugin =
                new TextInputPlugin(
                    testView,
                    textInputChannel,
                    scribeChannel,
                    mock(PlatformViewsController.class),
                    mock(PlatformViewsController2.class));
            textInputPlugin.setTextInputClient(
                0,
                new TextInputChannel.Configuration(
                    false,
                    false,
                    true,
                    true,
                    false,
                    TextInputChannel.TextCapitalization.NONE,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));
            ImeSyncDeferringInsetsCallback imeSyncCallback = textInputPlugin.getImeSyncCallback();
            FlutterEngine flutterEngine =
                spy(new FlutterEngine(ctx, mockFlutterLoader, mockFlutterJni));
            FlutterRenderer flutterRenderer = spy(new FlutterRenderer(mockFlutterJni));
            when(flutterEngine.getRenderer()).thenReturn(flutterRenderer);
            assertEquals(0, testImm.getRestartCount(testView));
            // FlutterView restarts the input method to inform the Android framework that an engine
            // has
            // been attached.
            testView.attachToFlutterEngine(flutterEngine);
            assertEquals(1, testImm.getRestartCount(testView));

            ArgumentCaptor<FlutterRenderer.ViewportMetrics> viewportMetricsCaptor =
                ArgumentCaptor.forClass(FlutterRenderer.ViewportMetrics.class);

            WindowInsets.Builder builder = new WindowInsets.Builder();

            // There's a pending restart since we initialized the text input client. Flush that now.
            textInputPlugin.setTextInputEditingState(
                testView, new TextInputChannel.TextEditState("", 0, 0, -1, -1));
            assertEquals(2, testImm.getRestartCount(testView));

            // Set the initial insets and verify that they were set and the bottom view inset is
            // correct.
            builder.setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 0));
            when(testView.getRootWindowInsets()).thenReturn(builder.build());
            imeSyncCallback.getInsetsListener().onApplyWindowInsets(testView, builder.build());
            verify(flutterRenderer, atLeast(1)).setViewportMetrics(viewportMetricsCaptor.capture());
            assertEquals(0, viewportMetricsCaptor.getValue().viewInsetBottom);

            // Imm restarts when clearTextInputClient is called while the IME is hidden.
            textInputPlugin.clearTextInputClient();
            assertEquals(3, testImm.getRestartCount(testView));
          });
    }
  }

  @Test
  public void destroy_clearTextInputMethodHandler() {
    View testView = new View(ctx);
    TextInputChannel textInputChannel = spy(new TextInputChannel(mock(DartExecutor.class)));
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    verify(textInputChannel, times(1)).setTextInputMethodHandler(isNotNull());
    textInputPlugin.destroy();
    verify(textInputChannel, times(1)).setTextInputMethodHandler(isNull());
  }

  @SuppressWarnings("deprecation")
  // DartExecutor.send is deprecated.
  private void verifyInputConnection(TextInputChannel.TextInputType textInputType)
      throws JSONException {
    TestImm testImm = Shadow.extract(ctx.getSystemService(Context.INPUT_METHOD_SERVICE));
    FlutterJNI mockFlutterJni = mock(FlutterJNI.class);
    View testView = new View(ctx);
    DartExecutor dartExecutor = spy(new DartExecutor(mockFlutterJni, mock(AssetManager.class)));
    TextInputChannel textInputChannel = new TextInputChannel(dartExecutor);
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            false,
            TextInputChannel.TextCapitalization.NONE,
            new TextInputChannel.InputType(textInputType, false, false, false),
            null,
            null,
            null,
            null,
            null,
            null));
    // There's a pending restart since we initialized the text input client. Flush that now.
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("", 0, 0, -1, -1));

    ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<ByteBuffer> bufferCaptor = ArgumentCaptor.forClass(ByteBuffer.class);
    verify(dartExecutor, times(1)).send(channelCaptor.capture(), bufferCaptor.capture(), isNull());
    assertEquals("flutter/textinput", channelCaptor.getValue());
    verifyMethodCall(bufferCaptor.getValue(), "TextInputClient.requestExistingInputState", null);
    InputConnectionAdaptor connection =
        (InputConnectionAdaptor)
            textInputPlugin.createInputConnection(
                testView, mock(KeyboardManager.class), new EditorInfo());

    connection.handleKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
    verify(dartExecutor, times(2)).send(channelCaptor.capture(), bufferCaptor.capture(), isNull());
    assertEquals("flutter/textinput", channelCaptor.getValue());
    verifyMethodCall(
        bufferCaptor.getValue(),
        "TextInputClient.performAction",
        new String[] {"0", "TextInputAction.done"});
    connection.handleKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));

    connection.handleKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_NUMPAD_ENTER));
    verify(dartExecutor, times(3)).send(channelCaptor.capture(), bufferCaptor.capture(), isNull());
    assertEquals("flutter/textinput", channelCaptor.getValue());
    verifyMethodCall(
        bufferCaptor.getValue(),
        "TextInputClient.performAction",
        new String[] {"0", "TextInputAction.done"});
  }

  @Test
  public void inputConnection_createsActionFromEnter() throws JSONException {
    verifyInputConnection(TextInputChannel.TextInputType.TEXT);
  }

  @Test
  public void inputConnection_respondsToKeyEvents_textInputTypeNone() throws JSONException {
    verifyInputConnection(TextInputChannel.TextInputType.NONE);
  }

  @SuppressWarnings("deprecation") // InputMethodSubtype
  @Test
  public void inputConnection_finishComposingTextUpdatesIMM() throws JSONException {
    ShadowBuild.setManufacturer("samsung");
    InputMethodSubtype inputMethodSubtype =
        new InputMethodSubtype(0, 0, /*locale=*/ "en", "", "", false, false);
    Settings.Secure.putString(
        ctx.getContentResolver(),
        Settings.Secure.DEFAULT_INPUT_METHOD,
        "com.sec.android.inputmethod/.SamsungKeypad");
    TestImm testImm = Shadow.extract(ctx.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setCurrentInputMethodSubtype(inputMethodSubtype);
    FlutterJNI mockFlutterJni = mock(FlutterJNI.class);
    View testView = new View(ctx);
    DartExecutor dartExecutor = spy(new DartExecutor(mockFlutterJni, mock(AssetManager.class)));
    TextInputChannel textInputChannel = new TextInputChannel(dartExecutor);
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            false,
            TextInputChannel.TextCapitalization.NONE,
            new TextInputChannel.InputType(
                TextInputChannel.TextInputType.TEXT, false, false, false),
            null,
            null,
            null,
            null,
            null,
            null));
    // There's a pending restart since we initialized the text input client. Flush that now.
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("text", 0, 0, -1, -1));
    InputConnection connection =
        textInputPlugin.createInputConnection(
            testView, mock(KeyboardManager.class), new EditorInfo());

    connection.requestCursorUpdates(
        InputConnection.CURSOR_UPDATE_MONITOR | InputConnection.CURSOR_UPDATE_IMMEDIATE);

    connection.finishComposingText();

    assertEquals(-1, testImm.getLastCursorAnchorInfo().getComposingTextStart());
    assertEquals(0, testImm.getLastCursorAnchorInfo().getComposingText().length());
  }

  @Test
  public void inputConnection_textInputTypeNone() {
    View testView = new View(ctx);
    DartExecutor dartExecutor = mock(DartExecutor.class);
    TextInputChannel textInputChannel = new TextInputChannel(dartExecutor);
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            false,
            TextInputChannel.TextCapitalization.NONE,
            new TextInputChannel.InputType(
                TextInputChannel.TextInputType.NONE, false, false, false),
            null,
            null,
            null,
            null,
            null,
            null));

    InputConnection connection =
        textInputPlugin.createInputConnection(
            testView, mock(KeyboardManager.class), new EditorInfo());
    assertNotNull(connection);
  }

  @Test
  public void showTextInput_textInputTypeNone() {
    TestImm testImm = Shadow.extract(ctx.getSystemService(Context.INPUT_METHOD_SERVICE));
    View testView = new View(ctx);
    DartExecutor dartExecutor = mock(DartExecutor.class);
    TextInputChannel textInputChannel = new TextInputChannel(dartExecutor);
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            false,
            TextInputChannel.TextCapitalization.NONE,
            new TextInputChannel.InputType(
                TextInputChannel.TextInputType.NONE, false, false, false),
            null,
            null,
            null,
            null,
            null,
            null));

    textInputPlugin.showTextInput(testView);
    assertFalse(testImm.isSoftInputVisible());
  }

  @Test
  public void showTextInput_textInputTypeWebSearch() {
    TestImm testImm = Shadow.extract(ctx.getSystemService(Context.INPUT_METHOD_SERVICE));
    View testView = new View(ctx);
    DartExecutor dartExecutor = mock(DartExecutor.class);
    TextInputChannel textInputChannel = new TextInputChannel(dartExecutor);
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            false,
            TextInputChannel.TextCapitalization.NONE,
            new TextInputChannel.InputType(
                TextInputChannel.TextInputType.WEB_SEARCH, false, false, false),
            null,
            null,
            null,
            null,
            null,
            null));

    EditorInfo editorInfo = new EditorInfo();
    InputConnection connection =
        textInputPlugin.createInputConnection(testView, mock(KeyboardManager.class), editorInfo);

    assertEquals(
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, editorInfo.inputType);
  }

  @Test
  public void showTextInput_textInputTypeTwitter() {
    TestImm testImm = Shadow.extract(ctx.getSystemService(Context.INPUT_METHOD_SERVICE));
    View testView = new View(ctx);
    DartExecutor dartExecutor = mock(DartExecutor.class);
    TextInputChannel textInputChannel = new TextInputChannel(dartExecutor);
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            false,
            TextInputChannel.TextCapitalization.NONE,
            new TextInputChannel.InputType(
                TextInputChannel.TextInputType.TWITTER, false, false, false),
            null,
            null,
            null,
            null,
            null,
            null));

    EditorInfo editorInfo = new EditorInfo();
    InputConnection connection =
        textInputPlugin.createInputConnection(testView, mock(KeyboardManager.class), editorInfo);

    assertEquals(
        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        editorInfo.inputType);
  }

  @Test
  public void inputConnection_textInputTypeMultilineAndSuggestionsDisabled() {
    // Regression test for https://github.com/flutter/flutter/issues/71679.
    View testView = new View(ctx);
    DartExecutor dartExecutor = mock(DartExecutor.class);
    TextInputChannel textInputChannel = new TextInputChannel(dartExecutor);
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            false, // Disable suggestions.
            true,
            false,
            TextInputChannel.TextCapitalization.NONE,
            new TextInputChannel.InputType(
                TextInputChannel.TextInputType.MULTILINE, false, false, false),
            null,
            null,
            null,
            null,
            null,
            null));

    EditorInfo editorInfo = new EditorInfo();
    InputConnection connection =
        textInputPlugin.createInputConnection(testView, mock(KeyboardManager.class), editorInfo);

    assertEquals(
        InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        editorInfo.inputType);
  }

  @Config(minSdk = API_LEVELS.API_34)
  @TargetApi(API_LEVELS.API_34)
  @Test
  public void inputConnection_setsStylusHandwritingAvailable() {
    View testView = new View(ctx);
    DartExecutor dartExecutor = mock(DartExecutor.class);
    TextInputChannel textInputChannel = new TextInputChannel(dartExecutor);
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            false,
            TextInputChannel.TextCapitalization.NONE,
            new TextInputChannel.InputType(
                TextInputChannel.TextInputType.MULTILINE, false, false, false),
            null,
            null,
            null,
            null,
            null,
            null));

    EditorInfo editorInfo = new EditorInfo();
    InputConnection connection =
        textInputPlugin.createInputConnection(testView, mock(KeyboardManager.class), editorInfo);

    assertTrue(EditorInfoCompat.isStylusHandwritingEnabled(editorInfo));
  }

  @Config(sdk = API_LEVELS.API_32)
  @TargetApi(API_LEVELS.API_32)
  @Test
  public void inputConnection_doesNotCallSetsStylusHandwritingAvailableWhenAPILevelUnsupported() {
    View testView = new View(ctx);
    DartExecutor dartExecutor = mock(DartExecutor.class);
    TextInputChannel textInputChannel = new TextInputChannel(dartExecutor);
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            false,
            TextInputChannel.TextCapitalization.NONE,
            new TextInputChannel.InputType(
                TextInputChannel.TextInputType.MULTILINE, false, false, false),
            null,
            null,
            null,
            null,
            null,
            null));

    EditorInfo editorInfo = new EditorInfo();
    InputConnection connection =
        textInputPlugin.createInputConnection(testView, mock(KeyboardManager.class), editorInfo);

    assertFalse(EditorInfoCompat.isStylusHandwritingEnabled(editorInfo));
  }

  // -------- Start: Autofill Tests -------
  @Test
  public void autofill_enabledByDefault() {
    if (Build.VERSION.SDK_INT < API_LEVELS.API_26) {
      return;
    }
    FlutterView testView = new FlutterView(ctx);
    TextInputChannel textInputChannel = new TextInputChannel(mock(DartExecutor.class));
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    final TextInputChannel.Configuration.Autofill autofill =
        new TextInputChannel.Configuration.Autofill(
            "1", new String[] {}, null, new TextInputChannel.TextEditState("", 0, 0, -1, -1));

    final TextInputChannel.Configuration config =
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            false,
            TextInputChannel.TextCapitalization.NONE,
            null,
            null,
            null,
            autofill,
            null,
            null,
            null);

    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            false,
            TextInputChannel.TextCapitalization.NONE,
            null,
            null,
            null,
            autofill,
            null,
            new TextInputChannel.Configuration[] {config},
            null));

    final ViewStructure viewStructure = mock(ViewStructure.class);
    final ViewStructure[] children = {mock(ViewStructure.class), mock(ViewStructure.class)};

    when(viewStructure.newChild(anyInt()))
        .thenAnswer(invocation -> children[(int) invocation.getArgument(0)]);

    textInputPlugin.onProvideAutofillVirtualStructure(viewStructure, 0);

    verify(viewStructure).newChild(0);

    verify(children[0]).setAutofillId(any(), eq("1".hashCode()));
    // The flutter application sends an empty hint list, don't set hints.
    verify(children[0], never()).setAutofillHints(aryEq(new String[] {}));
    verify(children[0]).setDimens(anyInt(), anyInt(), anyInt(), anyInt(), gt(0), gt(0));
  }

  @Test
  public void autofill_canBeDisabled() {
    if (Build.VERSION.SDK_INT < API_LEVELS.API_26) {
      return;
    }
    FlutterView testView = new FlutterView(ctx);
    TextInputChannel textInputChannel = new TextInputChannel(mock(DartExecutor.class));
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    final TextInputChannel.Configuration.Autofill autofill =
        new TextInputChannel.Configuration.Autofill(
            "1", new String[] {}, null, new TextInputChannel.TextEditState("", 0, 0, -1, -1));

    final TextInputChannel.Configuration config =
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            false,
            TextInputChannel.TextCapitalization.NONE,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    textInputPlugin.setTextInputClient(0, config);

    final ViewStructure viewStructure = mock(ViewStructure.class);

    textInputPlugin.onProvideAutofillVirtualStructure(viewStructure, 0);

    verify(viewStructure, times(0)).newChild(anyInt());
  }

  @Test
  public void autofill_hintText() {
    if (Build.VERSION.SDK_INT < API_LEVELS.API_26) {
      return;
    }
    FlutterView testView = new FlutterView(ctx);
    TextInputChannel textInputChannel = new TextInputChannel(mock(DartExecutor.class));
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    final TextInputChannel.Configuration.Autofill autofill =
        new TextInputChannel.Configuration.Autofill(
            "1",
            new String[] {},
            "placeholder",
            new TextInputChannel.TextEditState("", 0, 0, -1, -1));

    final TextInputChannel.Configuration config =
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            false,
            TextInputChannel.TextCapitalization.NONE,
            null,
            null,
            null,
            autofill,
            null,
            null,
            null);

    textInputPlugin.setTextInputClient(0, config);

    final ViewStructure viewStructure = mock(ViewStructure.class);
    final ViewStructure[] children = {mock(ViewStructure.class), mock(ViewStructure.class)};

    when(viewStructure.newChild(anyInt()))
        .thenAnswer(invocation -> children[(int) invocation.getArgument(0)]);

    textInputPlugin.onProvideAutofillVirtualStructure(viewStructure, 0);
    verify(children[0]).setHint("placeholder");
  }

  @Config(minSdk = API_LEVELS.API_26)
  @SuppressWarnings("deprecation")
  @Test
  public void autofill_onProvideVirtualViewStructure() {
    if (Build.VERSION.SDK_INT < API_LEVELS.API_26) {
      return;
    }
    try (ActivityScenario<Activity> scenario = ActivityScenario.launch(Activity.class)) {
      scenario.onActivity(
          activity -> {
            FlutterView testView = new FlutterView(activity);
            TextInputChannel textInputChannel = new TextInputChannel(mock(DartExecutor.class));
            ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
            TextInputPlugin textInputPlugin =
                new TextInputPlugin(
                    testView,
                    textInputChannel,
                    scribeChannel,
                    mock(PlatformViewsController.class),
                    mock(PlatformViewsController2.class));
            final TextInputChannel.Configuration.Autofill autofill1 =
                new TextInputChannel.Configuration.Autofill(
                    "1",
                    new String[] {"HINT1"},
                    "placeholder1",
                    new TextInputChannel.TextEditState("", 0, 0, -1, -1));
            final TextInputChannel.Configuration.Autofill autofill2 =
                new TextInputChannel.Configuration.Autofill(
                    "2",
                    new String[] {"HINT2", "EXTRA"},
                    null,
                    new TextInputChannel.TextEditState("", 0, 0, -1, -1));

            final TextInputChannel.Configuration config1 =
                new TextInputChannel.Configuration(
                    false,
                    false,
                    true,
                    true,
                    false,
                    TextInputChannel.TextCapitalization.NONE,
                    null,
                    null,
                    null,
                    autofill1,
                    null,
                    null,
                    null);
            final TextInputChannel.Configuration config2 =
                new TextInputChannel.Configuration(
                    false,
                    false,
                    true,
                    true,
                    false,
                    TextInputChannel.TextCapitalization.NONE,
                    null,
                    null,
                    null,
                    autofill2,
                    null,
                    null,
                    null);

            textInputPlugin.setTextInputClient(
                0,
                new TextInputChannel.Configuration(
                    false,
                    false,
                    true,
                    true,
                    false,
                    TextInputChannel.TextCapitalization.NONE,
                    null,
                    null,
                    null,
                    autofill1,
                    null,
                    new TextInputChannel.Configuration[] {config1, config2},
                    null));

            final ViewStructure viewStructure = mock(ViewStructure.class);
            final ViewStructure[] children = {mock(ViewStructure.class), mock(ViewStructure.class)};

            when(viewStructure.newChild(anyInt()))
                .thenAnswer(invocation -> children[(int) invocation.getArgument(0)]);

            textInputPlugin.onProvideAutofillVirtualStructure(viewStructure, 0);

            verify(viewStructure).newChild(0);
            verify(viewStructure).newChild(1);

            verify(children[0]).setAutofillId(any(), eq("1".hashCode()));
            verify(children[0]).setAutofillHints(aryEq(new String[] {"HINT1"}));
            verify(children[0]).setDimens(anyInt(), anyInt(), anyInt(), anyInt(), gt(0), gt(0));
            verify(children[0]).setHint("placeholder1");

            verify(children[1]).setAutofillId(any(), eq("2".hashCode()));
            verify(children[1]).setAutofillHints(aryEq(new String[] {"HINT2", "EXTRA"}));
            verify(children[1]).setDimens(anyInt(), anyInt(), anyInt(), anyInt(), gt(0), gt(0));
            verify(children[1], times(0)).setHint(any());
          });
    }
  }

  @Config(minSdk = API_LEVELS.API_26)
  @Test
  public void autofill_onProvideVirtualViewStructure_singular_textfield() {
    if (Build.VERSION.SDK_INT < API_LEVELS.API_26) {
      return;
    }
    try (ActivityScenario<Activity> scenario = ActivityScenario.launch(Activity.class)) {
      scenario.onActivity(
          activity -> {
            FlutterView testView = new FlutterView(activity);
            TextInputChannel textInputChannel = new TextInputChannel(mock(DartExecutor.class));
            ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
            TextInputPlugin textInputPlugin =
                new TextInputPlugin(
                    testView,
                    textInputChannel,
                    scribeChannel,
                    mock(PlatformViewsController.class),
                    mock(PlatformViewsController2.class));
            final TextInputChannel.Configuration.Autofill autofill =
                new TextInputChannel.Configuration.Autofill(
                    "1",
                    new String[] {"HINT1"},
                    "placeholder",
                    new TextInputChannel.TextEditState("", 0, 0, -1, -1));

            // Autofill should still work without AutofillGroup.
            textInputPlugin.setTextInputClient(
                0,
                new TextInputChannel.Configuration(
                    false,
                    false,
                    true,
                    true,
                    false,
                    TextInputChannel.TextCapitalization.NONE,
                    null,
                    null,
                    null,
                    autofill,
                    null,
                    null,
                    null));

            final ViewStructure viewStructure = mock(ViewStructure.class);
            final ViewStructure[] children = {mock(ViewStructure.class)};

            when(viewStructure.newChild(anyInt()))
                .thenAnswer(invocation -> children[(int) invocation.getArgument(0)]);

            textInputPlugin.onProvideAutofillVirtualStructure(viewStructure, 0);

            verify(viewStructure).newChild(0);

            verify(children[0]).setAutofillId(any(), eq("1".hashCode()));
            verify(children[0]).setAutofillHints(aryEq(new String[] {"HINT1"}));
            verify(children[0]).setHint("placeholder");
            // Verifies that the child has a non-zero size.
            verify(children[0]).setDimens(anyInt(), anyInt(), anyInt(), anyInt(), gt(0), gt(0));
          });
    }
  }

  @Config(minSdk = API_LEVELS.API_26)
  @Test
  public void autofill_testLifeCycle() {
    if (Build.VERSION.SDK_INT < API_LEVELS.API_26) {
      return;
    }

    TestAfm testAfm = Shadow.extract(ctx.getSystemService(AutofillManager.class));
    try (ActivityScenario<Activity> scenario = ActivityScenario.launch(Activity.class)) {
      scenario.onActivity(
          activity -> {
            FlutterView testView = new FlutterView(activity);
            TextInputChannel textInputChannel = new TextInputChannel(mock(DartExecutor.class));
            ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
            TextInputPlugin textInputPlugin =
                new TextInputPlugin(
                    testView,
                    textInputChannel,
                    scribeChannel,
                    mock(PlatformViewsController.class),
                    mock(PlatformViewsController2.class));

            // Set up an autofill scenario with 2 fields.
            final TextInputChannel.Configuration.Autofill autofill1 =
                new TextInputChannel.Configuration.Autofill(
                    "1",
                    new String[] {"HINT1"},
                    "placeholder1",
                    new TextInputChannel.TextEditState("", 0, 0, -1, -1));
            final TextInputChannel.Configuration.Autofill autofill2 =
                new TextInputChannel.Configuration.Autofill(
                    "2",
                    new String[] {"HINT2", "EXTRA"},
                    null,
                    new TextInputChannel.TextEditState("", 0, 0, -1, -1));

            final TextInputChannel.Configuration config1 =
                new TextInputChannel.Configuration(
                    false,
                    false,
                    true,
                    true,
                    false,
                    TextInputChannel.TextCapitalization.NONE,
                    null,
                    null,
                    null,
                    autofill1,
                    null,
                    null,
                    null);
            final TextInputChannel.Configuration config2 =
                new TextInputChannel.Configuration(
                    false,
                    false,
                    true,
                    true,
                    false,
                    TextInputChannel.TextCapitalization.NONE,
                    null,
                    null,
                    null,
                    autofill2,
                    null,
                    null,
                    null);

            // Set client. This should call notifyViewExited on the FlutterView if the previous
            // client is
            // also eligible for autofill.
            final TextInputChannel.Configuration autofillConfiguration =
                new TextInputChannel.Configuration(
                    false,
                    false,
                    true,
                    true,
                    false,
                    TextInputChannel.TextCapitalization.NONE,
                    null,
                    null,
                    null,
                    autofill1,
                    null,
                    new TextInputChannel.Configuration[] {config1, config2},
                    null);

            textInputPlugin.setTextInputClient(0, autofillConfiguration);

            // notifyViewExited should not be called as this is the first client we set.
            assertEquals(testAfm.empty, testAfm.exitId);

            // The framework updates the text, call notifyValueChanged.
            textInputPlugin.setTextInputEditingState(
                testView, new TextInputChannel.TextEditState("new text", -1, -1, -1, -1));
            assertEquals("new text", testAfm.changeString);
            assertEquals("1".hashCode(), testAfm.changeVirtualId);

            // The input method updates the text, call notifyValueChanged.
            testAfm.resetStates();
            final KeyboardManager mockKeyboardManager = mock(KeyboardManager.class);
            InputConnectionAdaptor adaptor =
                new InputConnectionAdaptor(
                    testView,
                    0,
                    mock(TextInputChannel.class),
                    mock(ScribeChannel.class),
                    mockKeyboardManager,
                    (ListenableEditingState) textInputPlugin.getEditable(),
                    new EditorInfo());
            adaptor.commitText("input from IME ", 1);

            assertEquals("input from IME new text", testAfm.changeString);
            assertEquals("1".hashCode(), testAfm.changeVirtualId);

            // notifyViewExited should be called on the previous client.
            testAfm.resetStates();
            textInputPlugin.setTextInputClient(
                1,
                new TextInputChannel.Configuration(
                    false,
                    false,
                    true,
                    true,
                    false,
                    TextInputChannel.TextCapitalization.NONE,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));

            assertEquals("1".hashCode(), testAfm.exitId);

            // TextInputPlugin#clearTextInputClient calls notifyViewExited.
            testAfm.resetStates();
            textInputPlugin.setTextInputClient(3, autofillConfiguration);
            assertEquals(testAfm.empty, testAfm.exitId);
            textInputPlugin.clearTextInputClient();
            assertEquals("1".hashCode(), testAfm.exitId);

            // TextInputPlugin#destroy calls notifyViewExited.
            testAfm.resetStates();
            textInputPlugin.setTextInputClient(4, autofillConfiguration);
            assertEquals(testAfm.empty, testAfm.exitId);
            textInputPlugin.destroy();
            assertEquals("1".hashCode(), testAfm.exitId);
          });
    }
  }

  @Config(minSdk = API_LEVELS.API_26)
  @SuppressWarnings("deprecation")
  @Test
  public void autofill_testAutofillUpdatesTheFramework() {
    if (Build.VERSION.SDK_INT < API_LEVELS.API_26) {
      return;
    }

    TestAfm testAfm = Shadow.extract(ctx.getSystemService(AutofillManager.class));
    try (ActivityScenario<Activity> scenario = ActivityScenario.launch(Activity.class)) {
      scenario.onActivity(
          activity -> {
            FlutterView testView = new FlutterView(activity);
            TextInputChannel textInputChannel = spy(new TextInputChannel(mock(DartExecutor.class)));
            ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
            TextInputPlugin textInputPlugin =
                new TextInputPlugin(
                    testView,
                    textInputChannel,
                    scribeChannel,
                    mock(PlatformViewsController.class),
                    mock(PlatformViewsController2.class));

            // Set up an autofill scenario with 2 fields.
            final TextInputChannel.Configuration.Autofill autofill1 =
                new TextInputChannel.Configuration.Autofill(
                    "1",
                    new String[] {"HINT1"},
                    null,
                    new TextInputChannel.TextEditState("field 1", 0, 0, -1, -1));
            final TextInputChannel.Configuration.Autofill autofill2 =
                new TextInputChannel.Configuration.Autofill(
                    "2",
                    new String[] {"HINT2", "EXTRA"},
                    null,
                    new TextInputChannel.TextEditState("field 2", 0, 0, -1, -1));

            final TextInputChannel.Configuration config1 =
                new TextInputChannel.Configuration(
                    false,
                    false,
                    true,
                    true,
                    false,
                    TextInputChannel.TextCapitalization.NONE,
                    null,
                    null,
                    null,
                    autofill1,
                    null,
                    null,
                    null);
            final TextInputChannel.Configuration config2 =
                new TextInputChannel.Configuration(
                    false,
                    false,
                    true,
                    true,
                    false,
                    TextInputChannel.TextCapitalization.NONE,
                    null,
                    null,
                    null,
                    autofill2,
                    null,
                    null,
                    null);

            final TextInputChannel.Configuration autofillConfiguration =
                new TextInputChannel.Configuration(
                    false,
                    false,
                    true,
                    true,
                    false,
                    TextInputChannel.TextCapitalization.NONE,
                    null,
                    null,
                    null,
                    autofill1,
                    null,
                    new TextInputChannel.Configuration[] {config1, config2},
                    null);

            textInputPlugin.setTextInputClient(0, autofillConfiguration);
            textInputPlugin.setTextInputEditingState(
                testView, new TextInputChannel.TextEditState("", 0, 0, -1, -1));

            final SparseArray<AutofillValue> autofillValues = new SparseArray();
            autofillValues.append("1".hashCode(), AutofillValue.forText("focused field"));
            autofillValues.append("2".hashCode(), AutofillValue.forText("unfocused field"));

            // Autofill both fields.
            textInputPlugin.autofill(autofillValues);

            // Verify the Editable has been updated.
            assertEquals("focused field", textInputPlugin.getEditable().toString());

            // The autofill value of the focused field is sent via updateEditingState.
            verify(textInputChannel, times(1))
                .updateEditingState(anyInt(), eq("focused field"), eq(13), eq(13), eq(-1), eq(-1));

            final ArgumentCaptor<HashMap> mapCaptor = ArgumentCaptor.forClass(HashMap.class);

            verify(textInputChannel, times(1))
                .updateEditingStateWithTag(anyInt(), mapCaptor.capture());
            final TextInputChannel.TextEditState editState =
                (TextInputChannel.TextEditState) mapCaptor.getValue().get("2");
            assertEquals("unfocused field", editState.text);
          });
    }
  }

  @Config(minSdk = API_LEVELS.API_26)
  @Test
  public void autofill_doesNotCrashAfterClearClientCall() {
    if (Build.VERSION.SDK_INT < API_LEVELS.API_26) {
      return;
    }
    FlutterView testView = new FlutterView(ctx);
    TextInputChannel textInputChannel = spy(new TextInputChannel(mock(DartExecutor.class)));
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    // Set up an autofill scenario with 2 fields.
    final TextInputChannel.Configuration.Autofill autofillConfig =
        new TextInputChannel.Configuration.Autofill(
            "1",
            new String[] {"HINT1"},
            "placeholder1",
            new TextInputChannel.TextEditState("", 0, 0, -1, -1));
    final TextInputChannel.Configuration config =
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            false,
            TextInputChannel.TextCapitalization.NONE,
            null,
            null,
            null,
            autofillConfig,
            null,
            null,
            null);

    textInputPlugin.setTextInputClient(0, config);
    textInputPlugin.setTextInputEditingState(
        testView, new TextInputChannel.TextEditState("", 0, 0, -1, -1));
    textInputPlugin.clearTextInputClient();

    final SparseArray<AutofillValue> autofillValues = new SparseArray();
    autofillValues.append("1".hashCode(), AutofillValue.forText("focused field"));
    autofillValues.append("2".hashCode(), AutofillValue.forText("unfocused field"));

    // Autofill both fields.
    textInputPlugin.autofill(autofillValues);

    verify(textInputChannel, never()).updateEditingStateWithTag(anyInt(), any());
    verify(textInputChannel, never())
        .updateEditingState(anyInt(), any(), anyInt(), anyInt(), anyInt(), anyInt());
  }

  @Config(minSdk = API_LEVELS.API_26)
  @Test
  public void autofill_testSetTextInputClientUpdatesSideFields() {
    if (Build.VERSION.SDK_INT < API_LEVELS.API_26) {
      return;
    }

    TestAfm testAfm = Shadow.extract(ctx.getSystemService(AutofillManager.class));
    try (ActivityScenario<Activity> scenario = ActivityScenario.launch(Activity.class)) {
      scenario.onActivity(
          activity -> {
            FlutterView testView = new FlutterView(activity);
            TextInputChannel textInputChannel = new TextInputChannel(mock(DartExecutor.class));
            ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
            TextInputPlugin textInputPlugin =
                new TextInputPlugin(
                    testView,
                    textInputChannel,
                    scribeChannel,
                    mock(PlatformViewsController.class),
                    mock(PlatformViewsController2.class));

            // Set up an autofill scenario with 2 fields.
            final TextInputChannel.Configuration.Autofill autofill1 =
                new TextInputChannel.Configuration.Autofill(
                    "1",
                    new String[] {"HINT1"},
                    "null",
                    new TextInputChannel.TextEditState("", 0, 0, -1, -1));
            final TextInputChannel.Configuration.Autofill autofill2 =
                new TextInputChannel.Configuration.Autofill(
                    "2",
                    new String[] {"HINT2", "EXTRA"},
                    "null",
                    new TextInputChannel.TextEditState(
                        "Unfocused fields need love like everything does", 0, 0, -1, -1));

            final TextInputChannel.Configuration config1 =
                new TextInputChannel.Configuration(
                    false,
                    false,
                    true,
                    true,
                    false,
                    TextInputChannel.TextCapitalization.NONE,
                    null,
                    null,
                    null,
                    autofill1,
                    null,
                    null,
                    null);
            final TextInputChannel.Configuration config2 =
                new TextInputChannel.Configuration(
                    false,
                    false,
                    true,
                    true,
                    false,
                    TextInputChannel.TextCapitalization.NONE,
                    null,
                    null,
                    null,
                    autofill2,
                    null,
                    null,
                    null);

            final TextInputChannel.Configuration autofillConfiguration =
                new TextInputChannel.Configuration(
                    false,
                    false,
                    true,
                    true,
                    false,
                    TextInputChannel.TextCapitalization.NONE,
                    null,
                    null,
                    null,
                    autofill1,
                    null,
                    new TextInputChannel.Configuration[] {config1, config2},
                    null);

            textInputPlugin.setTextInputClient(0, autofillConfiguration);

            // notifyValueChanged should be called for unfocused fields.
            assertEquals("2".hashCode(), testAfm.changeVirtualId);
            assertEquals("Unfocused fields need love like everything does", testAfm.changeString);
          });
    }
  }
  // -------- End: Autofill Tests -------

  @SuppressWarnings("deprecation")
  // setMessageHandler is deprecated.
  @Test
  public void respondsToInputChannelMessages() {
    ArgumentCaptor<BinaryMessenger.BinaryMessageHandler> binaryMessageHandlerCaptor =
        ArgumentCaptor.forClass(BinaryMessenger.BinaryMessageHandler.class);
    DartExecutor mockBinaryMessenger = mock(DartExecutor.class);
    TextInputChannel.TextInputMethodHandler mockHandler =
        mock(TextInputChannel.TextInputMethodHandler.class);
    TextInputChannel textInputChannel = new TextInputChannel(mockBinaryMessenger);

    textInputChannel.setTextInputMethodHandler(mockHandler);

    verify(mockBinaryMessenger, times(1))
        .setMessageHandler(any(String.class), binaryMessageHandlerCaptor.capture());

    BinaryMessenger.BinaryMessageHandler binaryMessageHandler =
        binaryMessageHandlerCaptor.getValue();

    sendToBinaryMessageHandler(binaryMessageHandler, "TextInput.requestAutofill", null);
    verify(mockHandler, times(1)).requestAutofill();

    sendToBinaryMessageHandler(binaryMessageHandler, "TextInput.finishAutofillContext", true);
    verify(mockHandler, times(1)).finishAutofillContext(true);

    sendToBinaryMessageHandler(binaryMessageHandler, "TextInput.finishAutofillContext", false);
    verify(mockHandler, times(1)).finishAutofillContext(false);
  }

  @SuppressWarnings("deprecation")
  // setMessageHandler is deprecated.
  @Test
  public void sendAppPrivateCommand_dataIsEmpty() throws JSONException {
    ArgumentCaptor<BinaryMessenger.BinaryMessageHandler> binaryMessageHandlerCaptor =
        ArgumentCaptor.forClass(BinaryMessenger.BinaryMessageHandler.class);
    DartExecutor mockBinaryMessenger = mock(DartExecutor.class);
    TextInputChannel textInputChannel = new TextInputChannel(mockBinaryMessenger);
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));

    EventHandler mockEventHandler = mock(EventHandler.class);
    TestImm testImm = Shadow.extract(ctx.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setEventHandler(mockEventHandler);

    View testView = new View(ctx);
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));

    verify(mockBinaryMessenger, times(1))
        .setMessageHandler(any(String.class), binaryMessageHandlerCaptor.capture());

    JSONObject arguments = new JSONObject();
    arguments.put("action", "actionCommand");
    arguments.put("data", "");

    BinaryMessenger.BinaryMessageHandler binaryMessageHandler =
        binaryMessageHandlerCaptor.getValue();
    sendToBinaryMessageHandler(binaryMessageHandler, "TextInput.sendAppPrivateCommand", arguments);
    verify(mockEventHandler, times(1))
        .sendAppPrivateCommand(any(View.class), eq("actionCommand"), eq(null));
  }

  @SuppressWarnings("deprecation")
  // setMessageHandler is deprecated.
  @Test
  public void sendAppPrivateCommand_hasData() throws JSONException {
    ArgumentCaptor<BinaryMessenger.BinaryMessageHandler> binaryMessageHandlerCaptor =
        ArgumentCaptor.forClass(BinaryMessenger.BinaryMessageHandler.class);
    DartExecutor mockBinaryMessenger = mock(DartExecutor.class);
    TextInputChannel textInputChannel = new TextInputChannel(mockBinaryMessenger);
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));

    EventHandler mockEventHandler = mock(EventHandler.class);
    TestImm testImm = Shadow.extract(ctx.getSystemService(Context.INPUT_METHOD_SERVICE));
    testImm.setEventHandler(mockEventHandler);

    View testView = new View(ctx);
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));

    verify(mockBinaryMessenger, times(1))
        .setMessageHandler(any(String.class), binaryMessageHandlerCaptor.capture());

    JSONObject arguments = new JSONObject();
    arguments.put("action", "actionCommand");
    arguments.put("data", "actionData");

    ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
    BinaryMessenger.BinaryMessageHandler binaryMessageHandler =
        binaryMessageHandlerCaptor.getValue();
    sendToBinaryMessageHandler(binaryMessageHandler, "TextInput.sendAppPrivateCommand", arguments);
    verify(mockEventHandler, times(1))
        .sendAppPrivateCommand(any(View.class), eq("actionCommand"), bundleCaptor.capture());
    assertEquals("actionData", bundleCaptor.getValue().getCharSequence("data"));
  }

  @Test
  @TargetApi(API_LEVELS.API_30)
  @Config(sdk = API_LEVELS.API_30)
  @SuppressWarnings("deprecation")
  // getWindowSystemUiVisibility, SYSTEM_UI_FLAG_LAYOUT_STABLE.
  // flutter#133074 tracks migration work.
  public void ime_windowInsetsSync_notLaidOutBehindNavigation_excludesNavigationBars() {
    try (ActivityScenario<Activity> scenario = ActivityScenario.launch(Activity.class)) {
      scenario.onActivity(
          activity -> {
            FlutterView testView = spy(new FlutterView(activity));
            when(testView.getWindowSystemUiVisibility())
                .thenReturn(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

            TextInputChannel textInputChannel = new TextInputChannel(mock(DartExecutor.class));
            ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
            TextInputPlugin textInputPlugin =
                new TextInputPlugin(
                    testView,
                    textInputChannel,
                    scribeChannel,
                    mock(PlatformViewsController.class),
                    mock(PlatformViewsController2.class));
            ImeSyncDeferringInsetsCallback imeSyncCallback = textInputPlugin.getImeSyncCallback();
            FlutterEngine flutterEngine =
                spy(new FlutterEngine(ctx, mockFlutterLoader, mockFlutterJni));
            FlutterRenderer flutterRenderer = spy(new FlutterRenderer(mockFlutterJni));
            when(flutterEngine.getRenderer()).thenReturn(flutterRenderer);
            testView.attachToFlutterEngine(flutterEngine);

            WindowInsetsAnimation animation = mock(WindowInsetsAnimation.class);
            when(animation.getTypeMask()).thenReturn(WindowInsets.Type.ime());

            List<WindowInsetsAnimation> animationList = new ArrayList();
            animationList.add(animation);

            ArgumentCaptor<FlutterRenderer.ViewportMetrics> viewportMetricsCaptor =
                ArgumentCaptor.forClass(FlutterRenderer.ViewportMetrics.class);

            WindowInsets.Builder builder = new WindowInsets.Builder();

            // Set the initial insets and verify that they were set and the bottom view inset is
            // correct
            imeSyncCallback.getInsetsListener().onApplyWindowInsets(testView, builder.build());

            verify(flutterRenderer, atLeast(1)).setViewportMetrics(viewportMetricsCaptor.capture());
            assertEquals(0, viewportMetricsCaptor.getValue().viewInsetBottom);

            // Call onPrepare and set the lastWindowInsets - these should be stored for the end of
            // the
            // animation instead of being applied immediately
            imeSyncCallback.getAnimationCallback().onPrepare(animation);
            builder.setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 100));
            builder.setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 0));
            imeSyncCallback.getInsetsListener().onApplyWindowInsets(testView, builder.build());

            verify(flutterRenderer, atLeast(1)).setViewportMetrics(viewportMetricsCaptor.capture());
            assertEquals(0, viewportMetricsCaptor.getValue().viewInsetBottom);

            // Call onStart and apply new insets - these should be ignored completely
            imeSyncCallback.getAnimationCallback().onStart(animation, null);
            builder.setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 50));
            builder.setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 40));
            imeSyncCallback.getInsetsListener().onApplyWindowInsets(testView, builder.build());

            verify(flutterRenderer, atLeast(1)).setViewportMetrics(viewportMetricsCaptor.capture());
            assertEquals(0, viewportMetricsCaptor.getValue().viewInsetBottom);

            // Progress the animation and ensure that the navigation bar insets have been subtracted
            // from the IME insets
            builder.setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 25));
            builder.setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 40));
            imeSyncCallback.getAnimationCallback().onProgress(builder.build(), animationList);

            verify(flutterRenderer, atLeast(1)).setViewportMetrics(viewportMetricsCaptor.capture());
            assertEquals(0, viewportMetricsCaptor.getValue().viewInsetBottom);

            builder.setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 50));
            builder.setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 40));
            imeSyncCallback.getAnimationCallback().onProgress(builder.build(), animationList);

            verify(flutterRenderer, atLeast(1)).setViewportMetrics(viewportMetricsCaptor.capture());
            assertEquals(10, viewportMetricsCaptor.getValue().viewInsetBottom);

            // End the animation and ensure that the bottom insets match the lastWindowInsets that
            // we set
            // during onPrepare
            imeSyncCallback.getAnimationCallback().onEnd(animation);

            verify(flutterRenderer, atLeast(1)).setViewportMetrics(viewportMetricsCaptor.capture());
            assertEquals(100, viewportMetricsCaptor.getValue().viewInsetBottom);
          });
    }
  }

  @Test
  @TargetApi(API_LEVELS.API_35)
  @Config(minSdk = API_LEVELS.API_35)
  @SuppressWarnings("deprecation")
  // getWindowSystemUiVisibility, SYSTEM_UI_FLAG_LAYOUT_STABLE.
  // flutter#133074 tracks migration work.
  public void ime_windowInsetsSync_notLaidOutBehindNavigation_post15_includesNavigationBars() {
    try (ActivityScenario<Activity> scenario = ActivityScenario.launch(Activity.class)) {
      scenario.onActivity(
          activity -> {
            FlutterView testView = spy(new FlutterView(activity));
            when(testView.getWindowSystemUiVisibility())
                .thenReturn(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

            TextInputChannel textInputChannel = new TextInputChannel(mock(DartExecutor.class));
            ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
            TextInputPlugin textInputPlugin =
                new TextInputPlugin(
                    testView,
                    textInputChannel,
                    scribeChannel,
                    mock(PlatformViewsController.class),
                    mock(PlatformViewsController2.class));
            ImeSyncDeferringInsetsCallback imeSyncCallback = textInputPlugin.getImeSyncCallback();
            FlutterEngine flutterEngine =
                spy(new FlutterEngine(ctx, mockFlutterLoader, mockFlutterJni));
            FlutterRenderer flutterRenderer = spy(new FlutterRenderer(mockFlutterJni));
            when(flutterEngine.getRenderer()).thenReturn(flutterRenderer);
            testView.attachToFlutterEngine(flutterEngine);

            WindowInsetsAnimation animation = mock(WindowInsetsAnimation.class);
            when(animation.getTypeMask()).thenReturn(WindowInsets.Type.ime());

            List<WindowInsetsAnimation> animationList = new ArrayList();
            animationList.add(animation);

            ArgumentCaptor<FlutterRenderer.ViewportMetrics> viewportMetricsCaptor =
                ArgumentCaptor.forClass(FlutterRenderer.ViewportMetrics.class);

            WindowInsets.Builder builder = new WindowInsets.Builder();

            // Set the initial insets and verify that they were set and the bottom view inset is
            // correct
            imeSyncCallback.getInsetsListener().onApplyWindowInsets(testView, builder.build());

            verify(flutterRenderer, atLeast(1)).setViewportMetrics(viewportMetricsCaptor.capture());
            assertEquals(0, viewportMetricsCaptor.getValue().viewInsetBottom);

            // Call onPrepare and set the lastWindowInsets - these should be stored for the end of
            // the
            // animation instead of being applied immediately
            imeSyncCallback.getAnimationCallback().onPrepare(animation);
            builder.setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 100));
            builder.setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 0));
            imeSyncCallback.getInsetsListener().onApplyWindowInsets(testView, builder.build());

            verify(flutterRenderer, atLeast(1)).setViewportMetrics(viewportMetricsCaptor.capture());
            assertEquals(0, viewportMetricsCaptor.getValue().viewInsetBottom);

            // Call onStart and apply new insets - these should be ignored completely
            imeSyncCallback.getAnimationCallback().onStart(animation, null);
            builder.setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 50));
            builder.setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 40));
            imeSyncCallback.getInsetsListener().onApplyWindowInsets(testView, builder.build());

            verify(flutterRenderer, atLeast(1)).setViewportMetrics(viewportMetricsCaptor.capture());
            assertEquals(0, viewportMetricsCaptor.getValue().viewInsetBottom);

            // Progress the animation and ensure that the navigation bar insets have not been
            // subtracted from the IME insets
            builder.setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 25));
            builder.setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 40));
            imeSyncCallback.getAnimationCallback().onProgress(builder.build(), animationList);

            verify(flutterRenderer, atLeast(1)).setViewportMetrics(viewportMetricsCaptor.capture());
            assertEquals(25, viewportMetricsCaptor.getValue().viewInsetBottom);

            builder.setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 50));
            builder.setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 40));
            imeSyncCallback.getAnimationCallback().onProgress(builder.build(), animationList);

            verify(flutterRenderer, atLeast(1)).setViewportMetrics(viewportMetricsCaptor.capture());
            assertEquals(50, viewportMetricsCaptor.getValue().viewInsetBottom);

            // End the animation and ensure that the bottom insets match the lastWindowInsets that
            // we set
            // during onPrepare
            imeSyncCallback.getAnimationCallback().onEnd(animation);

            verify(flutterRenderer, atLeast(1)).setViewportMetrics(viewportMetricsCaptor.capture());
            assertEquals(100, viewportMetricsCaptor.getValue().viewInsetBottom);
          });
    }
  }

  @Test
  @TargetApi(API_LEVELS.API_30)
  @Config(sdk = API_LEVELS.API_30)
  @SuppressWarnings("deprecation")
  // getWindowSystemUiVisibility
  // flutter#133074 tracks migration work.
  public void ime_windowInsetsSync_laidOutBehindNavigation_includesNavigationBars() {
    try (ActivityScenario<Activity> scenario = ActivityScenario.launch(Activity.class)) {
      scenario.onActivity(
          activity -> {
            FlutterView testView = spy(new FlutterView(activity));
            when(testView.getWindowSystemUiVisibility())
                .thenReturn(
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

            TextInputChannel textInputChannel = new TextInputChannel(mock(DartExecutor.class));
            ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
            TextInputPlugin textInputPlugin =
                new TextInputPlugin(
                    testView,
                    textInputChannel,
                    scribeChannel,
                    mock(PlatformViewsController.class),
                    mock(PlatformViewsController2.class));
            ImeSyncDeferringInsetsCallback imeSyncCallback = textInputPlugin.getImeSyncCallback();
            FlutterEngine flutterEngine =
                spy(new FlutterEngine(ctx, mockFlutterLoader, mockFlutterJni));
            FlutterRenderer flutterRenderer = spy(new FlutterRenderer(mockFlutterJni));
            when(flutterEngine.getRenderer()).thenReturn(flutterRenderer);
            testView.attachToFlutterEngine(flutterEngine);

            WindowInsetsAnimation animation = mock(WindowInsetsAnimation.class);
            when(animation.getTypeMask()).thenReturn(WindowInsets.Type.ime());

            List<WindowInsetsAnimation> animationList = new ArrayList();
            animationList.add(animation);

            ArgumentCaptor<FlutterRenderer.ViewportMetrics> viewportMetricsCaptor =
                ArgumentCaptor.forClass(FlutterRenderer.ViewportMetrics.class);

            WindowInsets.Builder builder = new WindowInsets.Builder();

            // Set the initial insets and verify that they were set and the bottom view inset is
            // correct
            imeSyncCallback.getInsetsListener().onApplyWindowInsets(testView, builder.build());

            verify(flutterRenderer, atLeast(1)).setViewportMetrics(viewportMetricsCaptor.capture());
            assertEquals(0, viewportMetricsCaptor.getValue().viewInsetBottom);

            // Call onPrepare and set the lastWindowInsets - these should be stored for the end of
            // the
            // animation instead of being applied immediately
            imeSyncCallback.getAnimationCallback().onPrepare(animation);
            builder.setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 100));
            builder.setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 0));
            imeSyncCallback.getInsetsListener().onApplyWindowInsets(testView, builder.build());

            verify(flutterRenderer, atLeast(1)).setViewportMetrics(viewportMetricsCaptor.capture());
            assertEquals(0, viewportMetricsCaptor.getValue().viewInsetBottom);

            // Call onStart and apply new insets - these should be ignored completely
            imeSyncCallback.getAnimationCallback().onStart(animation, null);
            builder.setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 50));
            builder.setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 40));
            imeSyncCallback.getInsetsListener().onApplyWindowInsets(testView, builder.build());

            verify(flutterRenderer, atLeast(1)).setViewportMetrics(viewportMetricsCaptor.capture());
            assertEquals(0, viewportMetricsCaptor.getValue().viewInsetBottom);

            // Progress the animation and ensure that the navigation bar insets have not been
            // subtracted from the IME insets
            builder.setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 25));
            builder.setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 40));
            imeSyncCallback.getAnimationCallback().onProgress(builder.build(), animationList);

            verify(flutterRenderer, atLeast(1)).setViewportMetrics(viewportMetricsCaptor.capture());
            assertEquals(25, viewportMetricsCaptor.getValue().viewInsetBottom);

            builder.setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 50));
            builder.setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 40));
            imeSyncCallback.getAnimationCallback().onProgress(builder.build(), animationList);

            verify(flutterRenderer, atLeast(1)).setViewportMetrics(viewportMetricsCaptor.capture());
            assertEquals(50, viewportMetricsCaptor.getValue().viewInsetBottom);

            // End the animation and ensure that the bottom insets match the lastWindowInsets that
            // we set
            // during onPrepare
            imeSyncCallback.getAnimationCallback().onEnd(animation);

            verify(flutterRenderer, atLeast(1)).setViewportMetrics(viewportMetricsCaptor.capture());
            assertEquals(100, viewportMetricsCaptor.getValue().viewInsetBottom);
          });
    }
  }

  @Test
  @TargetApi(API_LEVELS.API_30)
  @Config(sdk = API_LEVELS.API_30)
  @SuppressWarnings("deprecation")
  // getWindowSystemUiVisibility, SYSTEM_UI_FLAG_LAYOUT_STABLE
  // flutter#133074 tracks migration work.
  public void lastWindowInsets_updatedOnSecondOnProgressCall() {
    try (ActivityScenario<Activity> scenario = ActivityScenario.launch(Activity.class)) {
      scenario.onActivity(
          activity -> {
            FlutterView testView = spy(new FlutterView(activity));
            when(testView.getWindowSystemUiVisibility())
                .thenReturn(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

            TextInputChannel textInputChannel = new TextInputChannel(mock(DartExecutor.class));
            ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
            TextInputPlugin textInputPlugin =
                new TextInputPlugin(
                    testView,
                    textInputChannel,
                    scribeChannel,
                    mock(PlatformViewsController.class),
                    mock(PlatformViewsController2.class));
            ImeSyncDeferringInsetsCallback imeSyncCallback = textInputPlugin.getImeSyncCallback();
            FlutterEngine flutterEngine =
                spy(new FlutterEngine(ctx, mockFlutterLoader, mockFlutterJni));
            FlutterRenderer flutterRenderer = spy(new FlutterRenderer(mockFlutterJni));
            when(flutterEngine.getRenderer()).thenReturn(flutterRenderer);
            testView.attachToFlutterEngine(flutterEngine);

            WindowInsetsAnimation imeAnimation = mock(WindowInsetsAnimation.class);
            when(imeAnimation.getTypeMask()).thenReturn(WindowInsets.Type.ime());
            WindowInsetsAnimation navigationBarAnimation = mock(WindowInsetsAnimation.class);
            when(navigationBarAnimation.getTypeMask())
                .thenReturn(WindowInsets.Type.navigationBars());

            List<WindowInsetsAnimation> animationList = new ArrayList();
            animationList.add(imeAnimation);
            animationList.add(navigationBarAnimation);

            ArgumentCaptor<FlutterRenderer.ViewportMetrics> viewportMetricsCaptor =
                ArgumentCaptor.forClass(FlutterRenderer.ViewportMetrics.class);

            WindowInsets.Builder builder = new WindowInsets.Builder();

            // Set the initial insets and verify that they were set and the bottom view padding is
            // correct
            builder.setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 1000));
            builder.setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 100));
            imeSyncCallback.getInsetsListener().onApplyWindowInsets(testView, builder.build());

            verify(flutterRenderer, atLeast(1)).setViewportMetrics(viewportMetricsCaptor.capture());
            assertEquals(100, viewportMetricsCaptor.getValue().viewPaddingBottom);

            // Call onPrepare and set the lastWindowInsets - these should be stored for the end of
            // the
            // animation instead of being applied immediately
            imeSyncCallback.getAnimationCallback().onPrepare(imeAnimation);
            builder.setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 0));
            builder.setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 100));
            imeSyncCallback.getInsetsListener().onApplyWindowInsets(testView, builder.build());

            verify(flutterRenderer, atLeast(1)).setViewportMetrics(viewportMetricsCaptor.capture());
            assertEquals(100, viewportMetricsCaptor.getValue().viewPaddingBottom);

            // Call onPrepare again and apply new insets - these should overrite lastWindowInsets
            imeSyncCallback.getAnimationCallback().onPrepare(navigationBarAnimation);
            builder.setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 0));
            builder.setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 0));
            imeSyncCallback.getInsetsListener().onApplyWindowInsets(testView, builder.build());

            verify(flutterRenderer, atLeast(1)).setViewportMetrics(viewportMetricsCaptor.capture());
            assertEquals(100, viewportMetricsCaptor.getValue().viewPaddingBottom);

            // Progress the animation and ensure that the navigation bar insets have not been
            // subtracted from the IME insets
            builder.setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 500));
            builder.setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 0));
            imeSyncCallback.getAnimationCallback().onProgress(builder.build(), animationList);

            verify(flutterRenderer, atLeast(1)).setViewportMetrics(viewportMetricsCaptor.capture());
            assertEquals(0, viewportMetricsCaptor.getValue().viewPaddingBottom);

            builder.setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 250));
            builder.setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 0));
            imeSyncCallback.getAnimationCallback().onProgress(builder.build(), animationList);

            verify(flutterRenderer, atLeast(1)).setViewportMetrics(viewportMetricsCaptor.capture());
            assertEquals(0, viewportMetricsCaptor.getValue().viewPaddingBottom);

            // End the animation and ensure that the bottom insets match the lastWindowInsets that
            // we set
            // during onPrepare
            imeSyncCallback.getAnimationCallback().onEnd(imeAnimation);

            verify(flutterRenderer, atLeast(1)).setViewportMetrics(viewportMetricsCaptor.capture());
            assertEquals(0, viewportMetricsCaptor.getValue().viewPaddingBottom);
          });
    }
  }

  @Test
  @TargetApi(API_LEVELS.API_24)
  @Config(sdk = API_LEVELS.API_24)
  public void inputConnection_hintLocalesIsSetInEditorInfo() {
    View testView = new View(ctx);
    DartExecutor dartExecutor = mock(DartExecutor.class);
    TextInputChannel textInputChannel = new TextInputChannel(dartExecutor);
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    final Locale[] hintLocales = {new Locale.Builder().setLanguage("en").build()};
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            false,
            false,
            true,
            true,
            false,
            TextInputChannel.TextCapitalization.NONE,
            new TextInputChannel.InputType(
                TextInputChannel.TextInputType.MULTILINE, false, false, false),
            null,
            null,
            null,
            null,
            null,
            hintLocales));

    EditorInfo editorInfo = new EditorInfo();
    InputConnection connection =
        textInputPlugin.createInputConnection(testView, mock(KeyboardManager.class), editorInfo);

    assertEquals(editorInfo.hintLocales, new LocaleList(hintLocales));
  }

  /**
   * Helper that constructs a minimal {@link TextInputChannel.Configuration} with the given number
   * variation flags and captures the resulting {@link EditorInfo} from {@link
   * TextInputPlugin#createInputConnection}.
   */
  private EditorInfo editorInfoForNumberConfig(
      boolean isSigned,
      boolean isDecimal,
      boolean isPassword,
      boolean obscureText,
      boolean enableIMEPersonalizedLearning,
      TextInputChannel.Configuration.Autofill autofill) {
    View testView = new View(ctx);
    DartExecutor dartExecutor = mock(DartExecutor.class);
    TextInputChannel textInputChannel = new TextInputChannel(dartExecutor);
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    textInputPlugin.setTextInputClient(
        0,
        new TextInputChannel.Configuration(
            obscureText,
            false,
            true,
            enableIMEPersonalizedLearning,
            false,
            TextInputChannel.TextCapitalization.NONE,
            new TextInputChannel.InputType(
                TextInputChannel.TextInputType.NUMBER, isSigned, isDecimal, isPassword),
            null,
            null,
            autofill,
            null,
            null,
            null));

    EditorInfo editorInfo = new EditorInfo();
    textInputPlugin.createInputConnection(testView, mock(KeyboardManager.class), editorInfo);
    return editorInfo;
  }

  @Test
  public void inputType_number() {
    EditorInfo editorInfo = editorInfoForNumberConfig(false, false, false, false, true, null);
    assertEquals(InputType.TYPE_CLASS_NUMBER, editorInfo.inputType);
  }

  @Test
  public void inputType_numberWithPassword() {
    EditorInfo editorInfo = editorInfoForNumberConfig(false, false, true, false, true, null);
    assertEquals(
        InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        editorInfo.inputType);
  }

  @Test
  public void inputType_numberWithSigned() {
    EditorInfo editorInfo = editorInfoForNumberConfig(true, false, false, false, true, null);
    assertEquals(
        InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED, editorInfo.inputType);
  }

  @Test
  public void inputType_numberWithDecimal() {
    EditorInfo editorInfo = editorInfoForNumberConfig(false, true, false, false, true, null);
    assertEquals(
        InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, editorInfo.inputType);
  }

  @Test
  public void inputType_numberWithSignedAndPassword() {
    EditorInfo editorInfo = editorInfoForNumberConfig(true, false, true, false, true, null);
    assertEquals(
        InputType.TYPE_CLASS_NUMBER
            | InputType.TYPE_NUMBER_FLAG_SIGNED
            | InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        editorInfo.inputType);
  }

  @Test
  public void inputType_numberWithDecimalAndPassword() {
    EditorInfo editorInfo = editorInfoForNumberConfig(false, true, true, false, true, null);
    assertEquals(
        InputType.TYPE_CLASS_NUMBER
            | InputType.TYPE_NUMBER_FLAG_DECIMAL
            | InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        editorInfo.inputType);
  }

  @Test
  public void inputType_numberObscureTextDoesNotSetPasswordVariation() {
    // obscureText is orthogonal to the numeric password variation; it must not implicitly set
    // TYPE_NUMBER_VARIATION_PASSWORD on a NUMBER field. This test is load-bearing because the
    // obscureText branch lives in the same inputTypeFromTextInputType() function as the NUMBER
    // branch — the two are the most plausible place for accidental coupling.
    EditorInfo editorInfo = editorInfoForNumberConfig(false, false, false, true, true, null);
    assertEquals(InputType.TYPE_CLASS_NUMBER, editorInfo.inputType);
    assertEquals(0, editorInfo.inputType & InputType.TYPE_NUMBER_VARIATION_PASSWORD);
  }

  @Test
  public void inputType_numberWithPasswordJsonRoundTrip()
      throws JSONException, NoSuchFieldException {
    // End-to-end coverage from JSON through Configuration.fromJson -> InputType.fromJson ->
    // setTextInputClient -> createInputConnection. Catches typos in the "password" JSON key,
    // gaps in Configuration.fromJson's recursive inputType handling, and any break in the
    // JSON -> field -> plugin chain that the matrix tests bypass by calling new InputType(...)
    // directly.
    final JSONObject inputType = new JSONObject();
    inputType.put("name", "TextInputType.number");
    inputType.put("password", true);

    final JSONObject arguments = new JSONObject();
    arguments.put("inputAction", "TextInputAction.done");
    arguments.put("textCapitalization", "TextCapitalization.none");
    arguments.put("inputType", inputType);

    final TextInputChannel.Configuration configuration =
        TextInputChannel.Configuration.fromJson(arguments);

    View testView = new View(ctx);
    DartExecutor dartExecutor = mock(DartExecutor.class);
    TextInputChannel textInputChannel = new TextInputChannel(dartExecutor);
    ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
    TextInputPlugin textInputPlugin =
        new TextInputPlugin(
            testView,
            textInputChannel,
            scribeChannel,
            mock(PlatformViewsController.class),
            mock(PlatformViewsController2.class));
    textInputPlugin.setTextInputClient(0, configuration);

    EditorInfo editorInfo = new EditorInfo();
    textInputPlugin.createInputConnection(testView, mock(KeyboardManager.class), editorInfo);

    assertEquals(
        InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        editorInfo.inputType);
  }

  private static TextInputChannel.Configuration createTextConfiguration(
      boolean obscureText, boolean enableDeltaModel) {
    return new TextInputChannel.Configuration(
        obscureText,
        true,
        true,
        true,
        enableDeltaModel,
        TextInputChannel.TextCapitalization.NONE,
        new TextInputChannel.InputType(TextInputChannel.TextInputType.TEXT, false, false, false),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  public void systemContextMenu_startsFloatingActionModeWithExpectedMenuIdsAndContentRect() {
    try (ActivityScenario<Activity> scenario = ActivityScenario.launch(Activity.class)) {
      scenario.onActivity(
          activity -> {
            View testView = spy(new View(activity));
            activity.setContentView(testView);
            final float originalDensity = testView.getResources().getDisplayMetrics().density;
            testView.getResources().getDisplayMetrics().density = 2.0f;
            try {

              DartExecutor dartExecutor =
                  spy(new DartExecutor(mock(FlutterJNI.class), mock(AssetManager.class)));
              dartExecutor.onAttachedToJNI();
              TextInputChannel textInputChannel = new TextInputChannel(dartExecutor);
              ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
              PlatformChannel platformChannel = new PlatformChannel(dartExecutor);
              TextInputPlugin textInputPlugin =
                  new TextInputPlugin(
                      testView,
                      textInputChannel,
                      scribeChannel,
                      mock(PlatformViewsController.class),
                      mock(PlatformViewsController2.class),
                      platformChannel);

              textInputPlugin.setTextInputClient(5, createTextConfiguration(false, false));
              textInputPlugin.setTextInputEditingState(
                  testView, new TextEditState("Hello world", 0, 5, -1, -1));

              ArgumentCaptor<ActionMode.Callback> callbackCaptor =
                  ArgumentCaptor.forClass(ActionMode.Callback.class);
              PlatformChannel.SystemContextMenuRequest request =
                  new PlatformChannel.SystemContextMenuRequest(
                      new PlatformChannel.SystemContextMenuTargetRect(10.0, 20.0, 0.0, 15.0),
                      Arrays.asList(
                          new PlatformChannel.SystemContextMenuItem("cut", null, null),
                          new PlatformChannel.SystemContextMenuItem("copy", null, null),
                          new PlatformChannel.SystemContextMenuItem("paste", null, null),
                          new PlatformChannel.SystemContextMenuItem("share", "Share", null),
                          new PlatformChannel.SystemContextMenuItem("selectAll", null, null),
                          new PlatformChannel.SystemContextMenuItem("lookUp", "Look Up", null),
                          new PlatformChannel.SystemContextMenuItem(
                              "custom", "Translate", "cb-1")));

              textInputPlugin.showSystemContextMenu(request);

              verify(testView)
                  .startActionMode(callbackCaptor.capture(), eq(ActionMode.TYPE_FLOATING));
              ActionMode mode = textInputPlugin.getSystemContextMenuActionMode();
              assertNotNull(mode);

              Menu menu = mode.getMenu();
              assertEquals(6, menu.size());
              assertEquals(android.R.id.cut, menu.getItem(0).getItemId());
              assertEquals(android.R.id.copy, menu.getItem(1).getItemId());
              assertEquals(android.R.id.paste, menu.getItem(2).getItemId());
              assertEquals(android.R.id.shareText, menu.getItem(3).getItemId());
              assertEquals(android.R.id.selectAll, menu.getItem(4).getItemId());
              assertEquals("Translate", menu.getItem(5).getTitle().toString());

              ActionMode.Callback2 callback2 = (ActionMode.Callback2) callbackCaptor.getValue();
              Rect outRect = new Rect();
              callback2.onGetContentRect(mode, testView, outRect);
              // Zero logical width must clamp to >= 1 physical pixel so outRect is non-empty.
              assertEquals(new Rect(20, 40, 21, 70), outRect);
              assertFalse(outRect.isEmpty());

              // Updating while visible reuses the same ActionMode in place.
              PlatformChannel.SystemContextMenuRequest updatedRequest =
                  new PlatformChannel.SystemContextMenuRequest(
                      new PlatformChannel.SystemContextMenuTargetRect(15.0, 25.0, 30.0, 15.0),
                      Arrays.asList(
                          new PlatformChannel.SystemContextMenuItem("paste", null, null)));
              textInputPlugin.showSystemContextMenu(updatedRequest);
              assertSame(mode, textInputPlugin.getSystemContextMenuActionMode());
              assertEquals(1, mode.getMenu().size());
              assertEquals(android.R.id.paste, mode.getMenu().getItem(0).getItemId());
            } finally {
              testView.getResources().getDisplayMetrics().density = originalDensity;
            }
          });
    }
  }

  @Test
  @SuppressWarnings("deprecation")
  public void systemContextMenu_pasteDismissesBeforeSendingDeltaEditingUpdate()
      throws JSONException {
    try (ActivityScenario<Activity> scenario = ActivityScenario.launch(Activity.class)) {
      scenario.onActivity(
          activity -> {
            View testView = spy(new View(activity));
            activity.setContentView(testView);
            ClipboardManager clipboard =
                (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("label", "Flutter"));

            DartExecutor dartExecutor =
                spy(new DartExecutor(mock(FlutterJNI.class), mock(AssetManager.class)));
            dartExecutor.onAttachedToJNI();
            TextInputChannel textInputChannel = new TextInputChannel(dartExecutor);
            ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
            PlatformChannel platformChannel = new PlatformChannel(dartExecutor);
            TextInputPlugin textInputPlugin =
                new TextInputPlugin(
                    testView,
                    textInputChannel,
                    scribeChannel,
                    mock(PlatformViewsController.class),
                    mock(PlatformViewsController2.class),
                    platformChannel);

            textInputPlugin.setTextInputClient(3, createTextConfiguration(false, true));
            textInputPlugin.setTextInputEditingState(
                testView, new TextEditState("Hi world", 0, 2, -1, -1));

            ArgumentCaptor<ActionMode.Callback> callbackCaptor =
                ArgumentCaptor.forClass(ActionMode.Callback.class);
            textInputPlugin.showSystemContextMenu(
                new PlatformChannel.SystemContextMenuRequest(
                    new PlatformChannel.SystemContextMenuTargetRect(0, 0, 10, 10),
                    Arrays.asList(new PlatformChannel.SystemContextMenuItem("paste", null, null))));
            verify(testView)
                .startActionMode(callbackCaptor.capture(), eq(ActionMode.TYPE_FLOATING));

            ActionMode mode = textInputPlugin.getSystemContextMenuActionMode();
            assertNotNull(mode);
            MenuItem pasteItem = mode.getMenu().findItem(android.R.id.paste);
            assertNotNull(pasteItem);

            org.mockito.Mockito.clearInvocations(dartExecutor);
            assertTrue(callbackCaptor.getValue().onActionItemClicked(mode, pasteItem));
            assertNull(textInputPlugin.getSystemContextMenuActionMode());
            assertEquals("Flutter world", textInputPlugin.getEditable().toString());
            assertEquals(7, Selection.getSelectionStart(textInputPlugin.getEditable()));
            assertEquals(7, Selection.getSelectionEnd(textInputPlugin.getEditable()));

            ArgumentCaptor<String> channelCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<ByteBuffer> bufferCaptor = ArgumentCaptor.forClass(ByteBuffer.class);
            verify(dartExecutor, times(2))
                .send(channelCaptor.capture(), bufferCaptor.capture(), isNull());

            // 1st message MUST be ContextMenu.onDismissSystemContextMenu on flutter/platform.
            assertEquals("flutter/platform", channelCaptor.getAllValues().get(0));
            ByteBuffer firstBuf = bufferCaptor.getAllValues().get(0);
            firstBuf.rewind();
            MethodCall firstCall = JSONMethodCodec.INSTANCE.decodeMethodCall(firstBuf);
            assertEquals("ContextMenu.onDismissSystemContextMenu", firstCall.method);

            // 2nd message MUST be TextInputClient.updateEditingStateWithDeltas on
            // flutter/textinput.
            assertEquals("flutter/textinput", channelCaptor.getAllValues().get(1));
            ByteBuffer secondBuf = bufferCaptor.getAllValues().get(1);
            secondBuf.rewind();
            MethodCall secondCall = JSONMethodCodec.INSTANCE.decodeMethodCall(secondBuf);
            assertEquals("TextInputClient.updateEditingStateWithDeltas", secondCall.method);
          });
    }
  }

  @Test
  public void systemContextMenu_cutCopySelectAllAndObscureTextBehavior() {
    try (ActivityScenario<Activity> scenario = ActivityScenario.launch(Activity.class)) {
      scenario.onActivity(
          activity -> {
            View testView = spy(new View(activity));
            activity.setContentView(testView);
            ClipboardManager clipboard =
                (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("initial", "UNCHANGED"));

            DartExecutor dartExecutor =
                spy(new DartExecutor(mock(FlutterJNI.class), mock(AssetManager.class)));
            dartExecutor.onAttachedToJNI();
            TextInputChannel textInputChannel = new TextInputChannel(dartExecutor);
            ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
            PlatformChannel platformChannel = new PlatformChannel(dartExecutor);
            TextInputPlugin textInputPlugin =
                new TextInputPlugin(
                    testView,
                    textInputChannel,
                    scribeChannel,
                    mock(PlatformViewsController.class),
                    mock(PlatformViewsController2.class),
                    platformChannel);

            // 1. Select All keeps ActionMode active and selects entire text.
            textInputPlugin.setTextInputClient(1, createTextConfiguration(false, false));
            textInputPlugin.setTextInputEditingState(
                testView, new TextEditState("abcdef", 2, 2, -1, -1));
            ArgumentCaptor<ActionMode.Callback> callbackCaptor =
                ArgumentCaptor.forClass(ActionMode.Callback.class);
            textInputPlugin.showSystemContextMenu(
                new PlatformChannel.SystemContextMenuRequest(
                    new PlatformChannel.SystemContextMenuTargetRect(0, 0, 10, 10),
                    Arrays.asList(
                        new PlatformChannel.SystemContextMenuItem("selectAll", null, null),
                        new PlatformChannel.SystemContextMenuItem("copy", null, null),
                        new PlatformChannel.SystemContextMenuItem("cut", null, null))));
            verify(testView)
                .startActionMode(callbackCaptor.capture(), eq(ActionMode.TYPE_FLOATING));
            ActionMode mode = textInputPlugin.getSystemContextMenuActionMode();
            assertNotNull(mode);

            assertTrue(
                callbackCaptor
                    .getValue()
                    .onActionItemClicked(mode, mode.getMenu().findItem(android.R.id.selectAll)));
            // ActionMode remains open after Select All so items can update in place.
            assertSame(mode, textInputPlugin.getSystemContextMenuActionMode());
            assertEquals(0, Selection.getSelectionStart(textInputPlugin.getEditable()));
            assertEquals(6, Selection.getSelectionEnd(textInputPlugin.getEditable()));

            // 2. Copy copies text, finishes ActionMode, and collapses selection to selMax.
            assertTrue(
                callbackCaptor
                    .getValue()
                    .onActionItemClicked(mode, mode.getMenu().findItem(android.R.id.copy)));
            assertNull(textInputPlugin.getSystemContextMenuActionMode());
            assertEquals("abcdef", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
            assertEquals(6, Selection.getSelectionStart(textInputPlugin.getEditable()));
            assertEquals(6, Selection.getSelectionEnd(textInputPlugin.getEditable()));

            // 3. Obscure text never writes password characters to clipboard on Cut.
            clipboard.setPrimaryClip(ClipData.newPlainText("initial", "SAFE"));
            textInputPlugin.setTextInputClient(2, createTextConfiguration(true, false));
            textInputPlugin.setTextInputEditingState(
                testView, new TextEditState("secret", 0, 6, -1, -1));
            org.mockito.Mockito.clearInvocations(testView);
            textInputPlugin.showSystemContextMenu(
                new PlatformChannel.SystemContextMenuRequest(
                    new PlatformChannel.SystemContextMenuTargetRect(0, 0, 10, 10),
                    Arrays.asList(new PlatformChannel.SystemContextMenuItem("cut", null, null))));
            verify(testView)
                .startActionMode(callbackCaptor.capture(), eq(ActionMode.TYPE_FLOATING));
            ActionMode obscureMode = textInputPlugin.getSystemContextMenuActionMode();
            assertTrue(
                callbackCaptor
                    .getValue()
                    .onActionItemClicked(
                        obscureMode, obscureMode.getMenu().findItem(android.R.id.cut)));
            assertEquals("SAFE", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
            // Cut on an obscured field is a no-op backstop and does not delete password text.
            assertEquals("secret", textInputPlugin.getEditable().toString());
          });
    }
  }

  @Test
  @SuppressWarnings("deprecation")
  public void systemContextMenu_customActionAndDismissLifecycleOrdering() throws JSONException {
    try (ActivityScenario<Activity> scenario = ActivityScenario.launch(Activity.class)) {
      scenario.onActivity(
          activity -> {
            View testView = spy(new View(activity));
            activity.setContentView(testView);

            DartExecutor dartExecutor =
                spy(new DartExecutor(mock(FlutterJNI.class), mock(AssetManager.class)));
            dartExecutor.onAttachedToJNI();
            TextInputChannel textInputChannel = new TextInputChannel(dartExecutor);
            ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
            PlatformChannel platformChannel = new PlatformChannel(dartExecutor);
            TextInputPlugin textInputPlugin =
                new TextInputPlugin(
                    testView,
                    textInputChannel,
                    scribeChannel,
                    mock(PlatformViewsController.class),
                    mock(PlatformViewsController2.class),
                    platformChannel);

            textInputPlugin.setTextInputClient(9, createTextConfiguration(false, false));
            textInputPlugin.setTextInputEditingState(
                testView, new TextEditState("Hello", 0, 5, -1, -1));

            ArgumentCaptor<ActionMode.Callback> callbackCaptor =
                ArgumentCaptor.forClass(ActionMode.Callback.class);
            textInputPlugin.showSystemContextMenu(
                new PlatformChannel.SystemContextMenuRequest(
                    new PlatformChannel.SystemContextMenuTargetRect(0, 0, 10, 10),
                    Arrays.asList(
                        new PlatformChannel.SystemContextMenuItem(
                            "custom", "Custom Action", "cb-99"))));
            verify(testView)
                .startActionMode(callbackCaptor.capture(), eq(ActionMode.TYPE_FLOATING));
            ActionMode mode = textInputPlugin.getSystemContextMenuActionMode();
            assertNotNull(mode);

            // Clicking custom item sends onPerformCustomAction FIRST, then
            // onDismissSystemContextMenu.
            org.mockito.Mockito.clearInvocations(dartExecutor);
            MenuItem customItem = mode.getMenu().getItem(0);
            assertTrue(callbackCaptor.getValue().onActionItemClicked(mode, customItem));

            ArgumentCaptor<ByteBuffer> bufferCaptor = ArgumentCaptor.forClass(ByteBuffer.class);
            verify(dartExecutor, times(2))
                .send(eq("flutter/platform"), bufferCaptor.capture(), isNull());

            ByteBuffer first = bufferCaptor.getAllValues().get(0);
            first.rewind();
            assertEquals(
                "ContextMenu.onPerformCustomAction",
                JSONMethodCodec.INSTANCE.decodeMethodCall(first).method);

            ByteBuffer second = bufferCaptor.getAllValues().get(1);
            second.rewind();
            assertEquals(
                "ContextMenu.onDismissSystemContextMenu",
                JSONMethodCodec.INSTANCE.decodeMethodCall(second).method);

            // Programmatic hideSystemContextMenu() finishes ActionMode WITHOUT echoing
            // onDismissSystemContextMenu.
            textInputPlugin.showSystemContextMenu(
                new PlatformChannel.SystemContextMenuRequest(
                    new PlatformChannel.SystemContextMenuTargetRect(0, 0, 10, 10),
                    Arrays.asList(new PlatformChannel.SystemContextMenuItem("copy", null, null))));
            assertNotNull(textInputPlugin.getSystemContextMenuActionMode());
            org.mockito.Mockito.clearInvocations(dartExecutor);
            textInputPlugin.hideSystemContextMenu();
            assertNull(textInputPlugin.getSystemContextMenuActionMode());
            verify(dartExecutor, never()).send(eq("flutter/platform"), any(), any());

            // Switching to a Platform View while ActionMode is active finishes it AND notifies
            // framework.
            textInputPlugin.showSystemContextMenu(
                new PlatformChannel.SystemContextMenuRequest(
                    new PlatformChannel.SystemContextMenuTargetRect(0, 0, 10, 10),
                    Arrays.asList(new PlatformChannel.SystemContextMenuItem("copy", null, null))));
            assertNotNull(textInputPlugin.getSystemContextMenuActionMode());
            org.mockito.Mockito.clearInvocations(dartExecutor);
            textInputPlugin.setPlatformViewTextInputClient(42, false);
            assertNull(textInputPlugin.getSystemContextMenuActionMode());
            ArgumentCaptor<ByteBuffer> pvDismissCaptor = ArgumentCaptor.forClass(ByteBuffer.class);
            verify(dartExecutor, times(1))
                .send(eq("flutter/platform"), pvDismissCaptor.capture(), isNull());
            ByteBuffer pvBuf = pvDismissCaptor.getValue();
            pvBuf.rewind();
            MethodCall pvCall = JSONMethodCodec.INSTANCE.decodeMethodCall(pvBuf);
            assertEquals("ContextMenu.onDismissSystemContextMenu", pvCall.method);
            assertEquals(9, ((JSONArray) pvCall.arguments).optInt(0));
          });
    }
  }

  @Test
  @SuppressWarnings("deprecation")
  public void
      systemContextMenu_engineDismissalsNotifyFrameworkWithOwningClientIdAndGuardHandlerOwnership()
          throws JSONException {
    try (ActivityScenario<Activity> scenario = ActivityScenario.launch(Activity.class)) {
      scenario.onActivity(
          activity -> {
            View testView = spy(new View(activity));
            activity.setContentView(testView);

            DartExecutor dartExecutor =
                spy(new DartExecutor(mock(FlutterJNI.class), mock(AssetManager.class)));
            dartExecutor.onAttachedToJNI();
            TextInputChannel textInputChannel = new TextInputChannel(dartExecutor);
            ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
            PlatformChannel platformChannel = new PlatformChannel(dartExecutor);
            ArgumentCaptor<BinaryMessenger.BinaryMessageHandler> platformHandlerCaptor =
                ArgumentCaptor.forClass(BinaryMessenger.BinaryMessageHandler.class);
            verify(dartExecutor)
                .setMessageHandler(eq("flutter/platform"), platformHandlerCaptor.capture());
            java.util.function.IntConsumer assertDismissedWithClientId =
                expectedClientId -> {
                  ArgumentCaptor<ByteBuffer> dismissCaptor =
                      ArgumentCaptor.forClass(ByteBuffer.class);
                  verify(dartExecutor, times(1))
                      .send(eq("flutter/platform"), dismissCaptor.capture(), isNull());
                  ByteBuffer buf = dismissCaptor.getValue();
                  buf.rewind();
                  MethodCall call = JSONMethodCodec.INSTANCE.decodeMethodCall(buf);
                  assertEquals("ContextMenu.onDismissSystemContextMenu", call.method);
                  assertEquals(expectedClientId, ((JSONArray) call.arguments).optInt(0));
                };
            TextInputPlugin pluginA =
                new TextInputPlugin(
                    testView,
                    textInputChannel,
                    scribeChannel,
                    mock(PlatformViewsController.class),
                    mock(PlatformViewsController2.class),
                    platformChannel);

            // 1. startActionMode returning null notifies Dart immediately with owning clientId.
            pluginA.setTextInputClient(10, createTextConfiguration(false, false));
            pluginA.setTextInputEditingState(testView, new TextEditState("Hello", 0, 5, -1, -1));
            org.mockito.Mockito.doReturn(null)
                .when(testView)
                .startActionMode(any(), eq(ActionMode.TYPE_FLOATING));
            org.mockito.Mockito.clearInvocations(dartExecutor);
            pluginA.showSystemContextMenu(
                new PlatformChannel.SystemContextMenuRequest(
                    new PlatformChannel.SystemContextMenuTargetRect(0, 0, 10, 10),
                    Arrays.asList(new PlatformChannel.SystemContextMenuItem("copy", null, null))));
            assertNull(pluginA.getSystemContextMenuActionMode());
            assertDismissedWithClientId.accept(10);

            // Restore real startActionMode.
            org.mockito.Mockito.doCallRealMethod()
                .when(testView)
                .startActionMode(any(), eq(ActionMode.TYPE_FLOATING));

            // 2. Unsupported-only items while an ActionMode is showing dismisses & notifies.
            pluginA.showSystemContextMenu(
                new PlatformChannel.SystemContextMenuRequest(
                    new PlatformChannel.SystemContextMenuTargetRect(0, 0, 10, 10),
                    Arrays.asList(new PlatformChannel.SystemContextMenuItem("copy", null, null))));
            assertNotNull(pluginA.getSystemContextMenuActionMode());
            org.mockito.Mockito.clearInvocations(dartExecutor);
            pluginA.showSystemContextMenu(
                new PlatformChannel.SystemContextMenuRequest(
                    new PlatformChannel.SystemContextMenuTargetRect(0, 0, 10, 10),
                    Arrays.asList(
                        new PlatformChannel.SystemContextMenuItem("lookUp", "Look Up", null))));
            assertNull(pluginA.getSystemContextMenuActionMode());
            assertDismissedWithClientId.accept(10);

            // 3. Switching client 10 -> 11 while showing notifies with OLD clientId (10).
            pluginA.showSystemContextMenu(
                new PlatformChannel.SystemContextMenuRequest(
                    new PlatformChannel.SystemContextMenuTargetRect(0, 0, 10, 10),
                    Arrays.asList(new PlatformChannel.SystemContextMenuItem("copy", null, null))));
            assertNotNull(pluginA.getSystemContextMenuActionMode());
            org.mockito.Mockito.clearInvocations(dartExecutor);
            pluginA.setTextInputClient(11, createTextConfiguration(false, false));
            assertNull(pluginA.getSystemContextMenuActionMode());
            assertDismissedWithClientId.accept(10);

            // 4. clearTextInputClient while showing notifies with clientId 11.
            pluginA.setTextInputEditingState(testView, new TextEditState("World", 0, 5, -1, -1));
            pluginA.showSystemContextMenu(
                new PlatformChannel.SystemContextMenuRequest(
                    new PlatformChannel.SystemContextMenuTargetRect(0, 0, 10, 10),
                    Arrays.asList(new PlatformChannel.SystemContextMenuItem("copy", null, null))));
            assertNotNull(pluginA.getSystemContextMenuActionMode());
            org.mockito.Mockito.clearInvocations(dartExecutor);
            pluginA.clearTextInputClient();
            assertNull(pluginA.getSystemContextMenuActionMode());
            assertDismissedWithClientId.accept(11);

            // 5. Attaching a newer pluginB before pluginA.destroy() preserves pluginB's handler,
            // and pluginB.destroy() notifies with pluginB's owning clientId.
            TextInputPlugin pluginB =
                new TextInputPlugin(
                    testView,
                    textInputChannel,
                    scribeChannel,
                    mock(PlatformViewsController.class),
                    mock(PlatformViewsController2.class),
                    platformChannel);
            pluginA.destroy();

            pluginB.setTextInputClient(12, createTextConfiguration(false, false));
            pluginB.setTextInputEditingState(testView, new TextEditState("Keep", 0, 4, -1, -1));
            JSONObject showArgs;
            try {
              JSONObject rectJson =
                  new JSONObject().put("x", 0).put("y", 0).put("width", 10).put("height", 10);
              JSONArray itemsJson = new JSONArray().put(new JSONObject().put("type", "copy"));
              showArgs = new JSONObject().put("targetRect", rectJson).put("items", itemsJson);
            } catch (JSONException e) {
              throw new RuntimeException(e);
            }
            ByteBuffer encodedShowCall =
                JSONMethodCodec.INSTANCE.encodeMethodCall(
                    new MethodCall("ContextMenu.showSystemContextMenu", showArgs));
            encodedShowCall.rewind();
            BinaryMessenger.BinaryReply binaryReply = mock(BinaryMessenger.BinaryReply.class);
            platformHandlerCaptor.getValue().onMessage(encodedShowCall, binaryReply);
            verify(binaryReply).reply(isNotNull());
            assertNotNull(pluginB.getSystemContextMenuActionMode());

            org.mockito.Mockito.clearInvocations(dartExecutor);
            pluginB.destroy();
            assertDismissedWithClientId.accept(12);
          });
    }
  }

  @Test
  public void systemContextMenu_composingRestartSpannedPlainTextAndClipEdgeCases()
      throws Exception {
    TestImm testImm = Shadow.extract(ctx.getSystemService(Context.INPUT_METHOD_SERVICE));
    try (ActivityScenario<Activity> scenario = ActivityScenario.launch(Activity.class)) {
      scenario.onActivity(
          activity -> {
            View testView = spy(new View(activity));
            activity.setContentView(testView);
            ClipboardManager clipboard =
                (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);

            DartExecutor dartExecutor =
                spy(new DartExecutor(mock(FlutterJNI.class), mock(AssetManager.class)));
            dartExecutor.onAttachedToJNI();
            TextInputChannel textInputChannel = new TextInputChannel(dartExecutor);
            ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
            PlatformChannel platformChannel = new PlatformChannel(dartExecutor);
            TextInputPlugin textInputPlugin =
                new TextInputPlugin(
                    testView,
                    textInputChannel,
                    scribeChannel,
                    mock(PlatformViewsController.class),
                    mock(PlatformViewsController2.class),
                    platformChannel);

            textInputPlugin.setTextInputClient(20, createTextConfiguration(false, false));
            // Active composing span [0, 3) on "hel".
            textInputPlugin.setTextInputEditingState(
                testView, new TextEditState("hel", 3, 3, 0, 3));
            int restartsBefore = testImm.getRestartCount(testView);

            // Clipboard contains a SpannableString marked with SPAN_COMPOSING and URLSpan.
            android.text.SpannableString styledClip = new android.text.SpannableString("lo!");
            styledClip.setSpan(
                new android.text.style.URLSpan("https://flutter.dev"),
                0,
                3,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    | android.text.Spanned.SPAN_COMPOSING);
            clipboard.setPrimaryClip(ClipData.newPlainText("styled", styledClip));

            ArgumentCaptor<ActionMode.Callback> callbackCaptor =
                ArgumentCaptor.forClass(ActionMode.Callback.class);
            textInputPlugin.showSystemContextMenu(
                new PlatformChannel.SystemContextMenuRequest(
                    new PlatformChannel.SystemContextMenuTargetRect(0, 0, 10, 10),
                    Arrays.asList(new PlatformChannel.SystemContextMenuItem("paste", null, null))));
            verify(testView)
                .startActionMode(callbackCaptor.capture(), eq(ActionMode.TYPE_FLOATING));
            ActionMode mode = textInputPlugin.getSystemContextMenuActionMode();
            assertTrue(
                callbackCaptor
                    .getValue()
                    .onActionItemClicked(mode, mode.getMenu().findItem(android.R.id.paste)));

            // Composing region was cleared, IME restarted, and pasted text has NO spans.
            assertEquals("hello!", textInputPlugin.getEditable().toString());
            assertEquals(
                -1, BaseInputConnection.getComposingSpanStart(textInputPlugin.getEditable()));
            assertEquals(
                0,
                textInputPlugin
                    .getEditable()
                    .getSpans(0, 6, android.text.style.URLSpan.class)
                    .length);
            assertEquals(restartsBefore + 1, testImm.getRestartCount(testView));

            // Empty-string clip does NOT delete selected text on paste.
            textInputPlugin.setTextInputEditingState(
                testView, new TextEditState("keepme", 0, 6, -1, -1));
            clipboard.setPrimaryClip(ClipData.newPlainText("empty", ""));
            org.mockito.Mockito.clearInvocations(testView);
            textInputPlugin.showSystemContextMenu(
                new PlatformChannel.SystemContextMenuRequest(
                    new PlatformChannel.SystemContextMenuTargetRect(0, 0, 10, 10),
                    Arrays.asList(new PlatformChannel.SystemContextMenuItem("paste", null, null))));
            verify(testView)
                .startActionMode(callbackCaptor.capture(), eq(ActionMode.TYPE_FLOATING));
            mode = textInputPlugin.getSystemContextMenuActionMode();
            assertTrue(
                callbackCaptor
                    .getValue()
                    .onActionItemClicked(mode, mode.getMenu().findItem(android.R.id.paste)));
            assertEquals("keepme", textInputPlugin.getEditable().toString());

            // Raw file:// URI clip is ignored on paste and omitted from legacy default menu.
            clipboard.setPrimaryClip(
                ClipData.newRawUri("file", android.net.Uri.parse("file:///sdcard/secret.txt")));
            org.mockito.Mockito.clearInvocations(testView);
            textInputPlugin.showSystemContextMenu(
                new PlatformChannel.SystemContextMenuRequest(
                    new PlatformChannel.SystemContextMenuTargetRect(0, 0, 10, 10), null));
            // Entire text [0, 6] is already selected and raw URI is not pasteable -> only Cut/Copy.
            verify(testView)
                .startActionMode(callbackCaptor.capture(), eq(ActionMode.TYPE_FLOATING));
            mode = textInputPlugin.getSystemContextMenuActionMode();
            assertNull(mode.getMenu().findItem(android.R.id.paste));
            mode.finish();

            // Invalid selection (-1, -1) is a no-op on paste.
            clipboard.setPrimaryClip(ClipData.newPlainText("valid", "NEW"));
            textInputPlugin.setTextInputEditingState(
                testView, new TextEditState("untouched", -1, -1, -1, -1));
            org.mockito.Mockito.clearInvocations(testView);
            textInputPlugin.showSystemContextMenu(
                new PlatformChannel.SystemContextMenuRequest(
                    new PlatformChannel.SystemContextMenuTargetRect(0, 0, 10, 10),
                    Arrays.asList(new PlatformChannel.SystemContextMenuItem("paste", null, null))));
            verify(testView)
                .startActionMode(callbackCaptor.capture(), eq(ActionMode.TYPE_FLOATING));
            mode = textInputPlugin.getSystemContextMenuActionMode();
            assertTrue(
                callbackCaptor
                    .getValue()
                    .onActionItemClicked(mode, mode.getMenu().findItem(android.R.id.paste)));
            assertEquals("untouched", textInputPlugin.getEditable().toString());
          });
    }
  }

  @Test
  @SuppressWarnings("deprecation")
  public void
      systemContextMenu_stableCustomIdsLegacySelectAllInvalidateAndShareSearchContextWrapper()
          throws JSONException {
    try (ActivityScenario<Activity> scenario = ActivityScenario.launch(Activity.class)) {
      scenario.onActivity(
          activity -> {
            Activity spyActivity = spy(activity);
            android.content.ContextWrapper wrappedContext =
                new android.content.ContextWrapper(spyActivity);
            View testView = spy(new View(wrappedContext));
            activity.setContentView(testView);

            DartExecutor dartExecutor =
                spy(new DartExecutor(mock(FlutterJNI.class), mock(AssetManager.class)));
            dartExecutor.onAttachedToJNI();
            TextInputChannel textInputChannel = new TextInputChannel(dartExecutor);
            ScribeChannel scribeChannel = new ScribeChannel(mock(DartExecutor.class));
            PlatformChannel platformChannel = new PlatformChannel(dartExecutor);
            TextInputPlugin textInputPlugin =
                new TextInputPlugin(
                    testView,
                    textInputChannel,
                    scribeChannel,
                    mock(PlatformViewsController.class),
                    mock(PlatformViewsController2.class),
                    platformChannel);

            textInputPlugin.setTextInputClient(30, createTextConfiguration(false, false));
            textInputPlugin.setTextInputEditingState(
                testView, new TextEditState("Hello Flutter", 0, 5, -1, -1));

            // 1. Custom item IDs remain pinned to callbackId across invalidate() reordering.
            ArgumentCaptor<ActionMode.Callback> callbackCaptor =
                ArgumentCaptor.forClass(ActionMode.Callback.class);
            textInputPlugin.showSystemContextMenu(
                new PlatformChannel.SystemContextMenuRequest(
                    new PlatformChannel.SystemContextMenuTargetRect(0, 0, 10, 10),
                    Arrays.asList(
                        new PlatformChannel.SystemContextMenuItem("custom", "Action A", "cb-A"),
                        new PlatformChannel.SystemContextMenuItem("custom", "Action B", "cb-B"))));
            verify(testView)
                .startActionMode(callbackCaptor.capture(), eq(ActionMode.TYPE_FLOATING));
            ActionMode mode = textInputPlugin.getSystemContextMenuActionMode();
            int idA = mode.getMenu().getItem(0).getItemId();
            int idB = mode.getMenu().getItem(1).getItemId();
            MenuItem staleItemA = mode.getMenu().getItem(0);

            // Reorder to [B, C], dropping A.
            textInputPlugin.showSystemContextMenu(
                new PlatformChannel.SystemContextMenuRequest(
                    new PlatformChannel.SystemContextMenuTargetRect(0, 0, 10, 10),
                    Arrays.asList(
                        new PlatformChannel.SystemContextMenuItem("custom", "Action B", "cb-B"),
                        new PlatformChannel.SystemContextMenuItem("custom", "Action C", "cb-C"))));
            assertEquals(idB, mode.getMenu().getItem(0).getItemId());
            int idC = mode.getMenu().getItem(1).getItemId();
            org.junit.Assert.assertNotEquals(idA, idC);

            // Clicking B dispatches cb-B (never cb-C).
            org.mockito.Mockito.clearInvocations(dartExecutor);
            assertTrue(
                callbackCaptor.getValue().onActionItemClicked(mode, mode.getMenu().getItem(0)));
            ArgumentCaptor<ByteBuffer> bufCaptor = ArgumentCaptor.forClass(ByteBuffer.class);
            verify(dartExecutor, org.mockito.Mockito.atLeastOnce())
                .send(eq("flutter/platform"), bufCaptor.capture(), isNull());
            ByteBuffer firstBuf = bufCaptor.getAllValues().get(0);
            firstBuf.rewind();
            MethodCall customCall = JSONMethodCodec.INSTANCE.decodeMethodCall(firstBuf);
            assertEquals("ContextMenu.onPerformCustomAction", customCall.method);
            assertEquals("cb-B", ((JSONArray) customCall.arguments).optString(1));
            assertNull(staleItemA.getSubMenu());

            // 2. Legacy request.items == null: selectAll refreshes menu via mode.invalidate().
            org.mockito.Mockito.clearInvocations(testView);
            textInputPlugin.setTextInputEditingState(
                testView, new TextEditState("Hello Flutter", 0, 0, -1, -1));
            textInputPlugin.showSystemContextMenu(
                new PlatformChannel.SystemContextMenuRequest(
                    new PlatformChannel.SystemContextMenuTargetRect(0, 0, 10, 10), null));
            verify(testView)
                .startActionMode(callbackCaptor.capture(), eq(ActionMode.TYPE_FLOATING));
            mode = textInputPlugin.getSystemContextMenuActionMode();
            assertNull(mode.getMenu().findItem(android.R.id.copy));
            assertNotNull(mode.getMenu().findItem(android.R.id.selectAll));
            assertTrue(
                callbackCaptor
                    .getValue()
                    .onActionItemClicked(mode, mode.getMenu().findItem(android.R.id.selectAll)));
            // After invalidate(), Copy/Cut are now present and Select All is gone!
            assertNotNull(mode.getMenu().findItem(android.R.id.copy));
            assertNull(mode.getMenu().findItem(android.R.id.selectAll));
            mode.finish();

            // 3. Share and SearchWeb unwrap ContextWrapper -> Activity without NEW_TASK and survive
            // ActivityNotFoundException.
            org.mockito.Mockito.doThrow(new android.content.ActivityNotFoundException("no handler"))
                .when(spyActivity)
                .startActivity(any(android.content.Intent.class));
            org.mockito.Mockito.clearInvocations(testView);
            textInputPlugin.showSystemContextMenu(
                new PlatformChannel.SystemContextMenuRequest(
                    new PlatformChannel.SystemContextMenuTargetRect(0, 0, 10, 10),
                    Arrays.asList(
                        new PlatformChannel.SystemContextMenuItem("share", "Share", null),
                        new PlatformChannel.SystemContextMenuItem("searchWeb", "Web", null))));
            verify(testView)
                .startActionMode(callbackCaptor.capture(), eq(ActionMode.TYPE_FLOATING));
            mode = textInputPlugin.getSystemContextMenuActionMode();
            MenuItem shareItem = mode.getMenu().findItem(android.R.id.shareText);
            assertTrue(callbackCaptor.getValue().onActionItemClicked(mode, shareItem));
            ArgumentCaptor<android.content.Intent> intentCaptor =
                ArgumentCaptor.forClass(android.content.Intent.class);
            verify(spyActivity).startActivity(intentCaptor.capture());
            assertEquals(
                0,
                intentCaptor.getValue().getFlags() & android.content.Intent.FLAG_ACTIVITY_NEW_TASK);

            // 4. Session ID echoing and dismissal when a sibling native EditText steals focus.
            android.widget.FrameLayout container = new android.widget.FrameLayout(activity);
            testView.setFocusable(true);
            testView.setFocusableInTouchMode(true);
            android.widget.EditText siblingEditText = new android.widget.EditText(activity);
            siblingEditText.setFocusable(true);
            siblingEditText.setFocusableInTouchMode(true);
            if (testView.getParent() instanceof android.view.ViewGroup) {
              ((android.view.ViewGroup) testView.getParent()).removeView(testView);
            }
            container.addView(testView);
            container.addView(siblingEditText);
            activity.setContentView(container);
            testView.requestFocus();

            org.mockito.Mockito.clearInvocations(testView, dartExecutor);
            textInputPlugin.showSystemContextMenu(
                new PlatformChannel.SystemContextMenuRequest(
                    new PlatformChannel.SystemContextMenuTargetRect(0, 0, 10, 10),
                    Arrays.asList(new PlatformChannel.SystemContextMenuItem("copy", null, null)),
                    4242));
            assertNotNull(textInputPlugin.getSystemContextMenuActionMode());

            // Focusing the native EditText immediately dismisses Flutter's floating ActionMode
            // and echoes sessionId 4242 to the framework.
            siblingEditText.requestFocus();
            assertNull(textInputPlugin.getSystemContextMenuActionMode());
            ArgumentCaptor<ByteBuffer> focusDismissCaptor =
                ArgumentCaptor.forClass(ByteBuffer.class);
            verify(dartExecutor)
                .send(eq("flutter/platform"), focusDismissCaptor.capture(), isNull());
            ByteBuffer focusDismissBuf = focusDismissCaptor.getValue();
            focusDismissBuf.rewind();
            MethodCall focusDismissCall =
                JSONMethodCodec.INSTANCE.decodeMethodCall(focusDismissBuf);
            assertEquals("ContextMenu.onDismissSystemContextMenu", focusDismissCall.method);
            JSONArray focusDismissArgs = (JSONArray) focusDismissCall.arguments;
            assertEquals(30, focusDismissArgs.optInt(0));
            assertEquals(4242, focusDismissArgs.optInt(1));
          });
    }
  }

  interface EventHandler {
    void sendAppPrivateCommand(View view, String action, Bundle data);
  }

  @Implements(InputMethodManager.class)
  public static class TestImm extends ShadowInputMethodManager {
    private InputMethodSubtype currentInputMethodSubtype;
    private SparseIntArray restartCounter = new SparseIntArray();
    private CursorAnchorInfo cursorAnchorInfo;
    private ArrayList<Integer> selectionUpdateValues;
    private boolean trackSelection = false;
    private EventHandler handler;

    public TestImm() {
      selectionUpdateValues = new ArrayList<Integer>();
    }

    @Implementation
    public InputMethodSubtype getCurrentInputMethodSubtype() {
      return currentInputMethodSubtype;
    }

    @Implementation
    public void restartInput(View view) {
      int count = restartCounter.get(view.hashCode(), /*defaultValue=*/ 0) + 1;
      restartCounter.put(view.hashCode(), count);
    }

    public void setCurrentInputMethodSubtype(InputMethodSubtype inputMethodSubtype) {
      this.currentInputMethodSubtype = inputMethodSubtype;
    }

    public int getRestartCount(View view) {
      return restartCounter.get(view.hashCode(), /*defaultValue=*/ 0);
    }

    public void setEventHandler(EventHandler eventHandler) {
      handler = eventHandler;
    }

    @Implementation
    public void sendAppPrivateCommand(View view, String action, Bundle data) {
      handler.sendAppPrivateCommand(view, action, data);
    }

    @Implementation
    public void updateCursorAnchorInfo(View view, CursorAnchorInfo cursorAnchorInfo) {
      this.cursorAnchorInfo = cursorAnchorInfo;
    }

    // We simply store the values to verify later.
    @Implementation
    public void updateSelection(
        View view, int selStart, int selEnd, int candidatesStart, int candidatesEnd) {
      if (trackSelection) {
        this.selectionUpdateValues.add(selStart);
        this.selectionUpdateValues.add(selEnd);
        this.selectionUpdateValues.add(candidatesStart);
        this.selectionUpdateValues.add(candidatesEnd);
      }
    }

    // only track values when enabled via this.
    public void setTrackSelection(boolean val) {
      trackSelection = val;
    }

    // Returns true if the last updateSelection call passed the following values.
    public ArrayList<Integer> getSelectionUpdateValues() {
      return selectionUpdateValues;
    }

    public CursorAnchorInfo getLastCursorAnchorInfo() {
      return cursorAnchorInfo;
    }
  }

  @Implements(AutofillManager.class)
  public static class TestAfm extends ShadowAutofillManager {
    public static int empty = -999;

    String finishState;
    int changeVirtualId = empty;
    String changeString;

    int enterId = empty;
    int exitId = empty;

    @Implementation
    public void cancel() {
      finishState = "cancel";
    }

    public void commit() {
      finishState = "commit";
    }

    public void notifyViewEntered(View view, int virtualId, Rect absBounds) {
      enterId = virtualId;
    }

    public void notifyViewExited(View view, int virtualId) {
      exitId = virtualId;
    }

    public void notifyValueChanged(View view, int virtualId, AutofillValue value) {
      if (Build.VERSION.SDK_INT < API_LEVELS.API_26) {
        return;
      }
      changeVirtualId = virtualId;
      changeString = value.getTextValue().toString();
    }

    public void resetStates() {
      finishState = null;
      changeVirtualId = empty;
      changeString = null;
      enterId = empty;
      exitId = empty;
    }
  }
}
