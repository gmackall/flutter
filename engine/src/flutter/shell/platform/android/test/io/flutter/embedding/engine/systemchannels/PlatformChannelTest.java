// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.embedding.engine.systemchannels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.refEq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.AssetManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.flutter.embedding.engine.FlutterJNI;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.plugin.common.JSONMethodCodec;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import java.nio.ByteBuffer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

@RunWith(AndroidJUnit4.class)
public class PlatformChannelTest {
  @Test
  public void platformChannel_hasStringsMessage() {
    MethodChannel rawChannel = mock(MethodChannel.class);
    FlutterJNI mockFlutterJNI = mock(FlutterJNI.class);
    DartExecutor dartExecutor = new DartExecutor(mockFlutterJNI, mock(AssetManager.class));
    PlatformChannel fakePlatformChannel = new PlatformChannel(dartExecutor);
    PlatformChannel.PlatformMessageHandler mockMessageHandler =
        mock(PlatformChannel.PlatformMessageHandler.class);
    fakePlatformChannel.setPlatformMessageHandler(mockMessageHandler);
    Boolean returnValue = true;
    when(mockMessageHandler.clipboardHasStrings()).thenReturn(returnValue);
    MethodCall methodCall = new MethodCall("Clipboard.hasStrings", null);
    MethodChannel.Result mockResult = mock(MethodChannel.Result.class);
    fakePlatformChannel.parsingMethodCallHandler.onMethodCall(methodCall, mockResult);

    JSONObject expected = new JSONObject();
    try {
      expected.put("value", returnValue);
    } catch (JSONException e) {
    }
    verify(mockResult).success(refEq(expected));
  }

  @Test
  public void platformChannel_shareInvokeMessage() {
    MethodChannel rawChannel = mock(MethodChannel.class);
    FlutterJNI mockFlutterJNI = mock(FlutterJNI.class);
    DartExecutor dartExecutor = new DartExecutor(mockFlutterJNI, mock(AssetManager.class));
    PlatformChannel fakePlatformChannel = new PlatformChannel(dartExecutor);
    PlatformChannel.PlatformMessageHandler mockMessageHandler =
        mock(PlatformChannel.PlatformMessageHandler.class);
    fakePlatformChannel.setPlatformMessageHandler(mockMessageHandler);

    ArgumentCaptor<String> valueCapture = ArgumentCaptor.forClass(String.class);
    doNothing().when(mockMessageHandler).share(valueCapture.capture());

    final String expectedContent = "Flutter";
    MethodCall methodCall = new MethodCall("Share.invoke", expectedContent);
    MethodChannel.Result mockResult = mock(MethodChannel.Result.class);
    fakePlatformChannel.parsingMethodCallHandler.onMethodCall(methodCall, mockResult);

    assertEquals(expectedContent, valueCapture.getValue());
    verify(mockResult).success(null);
  }

  @Test
  public void platformChannel_replaysFrameworkHandlesBackForNewHandler() {
    FlutterJNI mockFlutterJNI = mock(FlutterJNI.class);
    DartExecutor dartExecutor = new DartExecutor(mockFlutterJNI, mock(AssetManager.class));
    PlatformChannel fakePlatformChannel = new PlatformChannel(dartExecutor);
    PlatformChannel.PlatformMessageHandler firstMessageHandler =
        mock(PlatformChannel.PlatformMessageHandler.class);
    fakePlatformChannel.setPlatformMessageHandler(firstMessageHandler);

    MethodCall methodCall = new MethodCall("SystemNavigator.setFrameworkHandlesBack", true);
    MethodChannel.Result mockResult = mock(MethodChannel.Result.class);
    fakePlatformChannel.parsingMethodCallHandler.onMethodCall(methodCall, mockResult);
    fakePlatformChannel.setPlatformMessageHandler(null);

    PlatformChannel.PlatformMessageHandler replacementMessageHandler =
        mock(PlatformChannel.PlatformMessageHandler.class);
    fakePlatformChannel.setPlatformMessageHandler(replacementMessageHandler);

    verify(mockResult).success(null);
    verify(replacementMessageHandler).setFrameworkHandlesBack(true);
  }

  @Test
  public void platformChannel_remembersFrameworkHandlesBackWithoutHandler() {
    FlutterJNI mockFlutterJNI = mock(FlutterJNI.class);
    DartExecutor dartExecutor = new DartExecutor(mockFlutterJNI, mock(AssetManager.class));
    PlatformChannel fakePlatformChannel = new PlatformChannel(dartExecutor);

    MethodCall methodCall = new MethodCall("SystemNavigator.setFrameworkHandlesBack", true);
    MethodChannel.Result mockResult = mock(MethodChannel.Result.class);
    fakePlatformChannel.parsingMethodCallHandler.onMethodCall(methodCall, mockResult);

    PlatformChannel.PlatformMessageHandler messageHandler =
        mock(PlatformChannel.PlatformMessageHandler.class);
    fakePlatformChannel.setPlatformMessageHandler(messageHandler);

    verify(mockResult).success(null);
    verify(messageHandler).setFrameworkHandlesBack(true);
  }

  @Test
  public void platformChannel_showSystemContextMenuReturnsHandlerBooleanWithoutMessageHandler()
      throws JSONException {
    FlutterJNI mockFlutterJNI = mock(FlutterJNI.class);
    DartExecutor dartExecutor = spy(new DartExecutor(mockFlutterJNI, mock(AssetManager.class)));
    PlatformChannel platformChannel = new PlatformChannel(dartExecutor);
    PlatformChannel.SystemContextMenuHandler mockContextMenuHandler =
        mock(PlatformChannel.SystemContextMenuHandler.class);
    platformChannel.setSystemContextMenuHandler(mockContextMenuHandler);
    when(mockContextMenuHandler.showSystemContextMenu(any())).thenReturn(true, false);

    JSONObject rectJson = new JSONObject();
    rectJson.put("x", 12.5);
    rectJson.put("y", 34.0);
    rectJson.put("width", 56.0);
    rectJson.put("height", 20.0);
    JSONArray itemsJson = new JSONArray();
    itemsJson.put(new JSONObject().put("type", "paste"));
    itemsJson.put(new JSONObject().put("type", "custom").put("title", "Translate").put("id", "42"));
    JSONObject args = new JSONObject();
    args.put("targetRect", rectJson);
    args.put("items", itemsJson);

    MethodChannel.Result shownResult = mock(MethodChannel.Result.class);
    platformChannel.parsingMethodCallHandler.onMethodCall(
        new MethodCall("ContextMenu.showSystemContextMenu", args), shownResult);

    ArgumentCaptor<PlatformChannel.SystemContextMenuRequest> requestCaptor =
        ArgumentCaptor.forClass(PlatformChannel.SystemContextMenuRequest.class);
    verify(mockContextMenuHandler).showSystemContextMenu(requestCaptor.capture());
    verify(shownResult).success(true);

    PlatformChannel.SystemContextMenuRequest request = requestCaptor.getValue();
    assertEquals(12.5, request.targetRect.x, 0.001);
    assertEquals(34.0, request.targetRect.y, 0.001);
    assertEquals(56.0, request.targetRect.width, 0.001);
    assertEquals(20.0, request.targetRect.height, 0.001);
    assertNotNull(request.items);
    assertEquals(2, request.items.size());
    assertEquals("paste", request.items.get(0).type);
    assertNull(request.items.get(0).title);
    assertEquals("custom", request.items.get(1).type);
    assertEquals("Translate", request.items.get(1).title);
    assertEquals("42", request.items.get(1).id);

    MethodChannel.Result notShownResult = mock(MethodChannel.Result.class);
    platformChannel.parsingMethodCallHandler.onMethodCall(
        new MethodCall("ContextMenu.showSystemContextMenu", args), notShownResult);
    verify(notShownResult).success(false);
  }

  @Test
  public void platformChannel_hideSystemContextMenuRepliesNull() {
    FlutterJNI mockFlutterJNI = mock(FlutterJNI.class);
    DartExecutor dartExecutor = new DartExecutor(mockFlutterJNI, mock(AssetManager.class));
    PlatformChannel platformChannel = new PlatformChannel(dartExecutor);
    PlatformChannel.SystemContextMenuHandler mockContextMenuHandler =
        mock(PlatformChannel.SystemContextMenuHandler.class);
    platformChannel.setSystemContextMenuHandler(mockContextMenuHandler);

    MethodChannel.Result hideResult = mock(MethodChannel.Result.class);
    platformChannel.parsingMethodCallHandler.onMethodCall(
        new MethodCall("ContextMenu.hideSystemContextMenu", null), hideResult);
    verify(mockContextMenuHandler).hideSystemContextMenu();
    verify(hideResult).success(null);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void platformChannel_onDismissSystemContextMenuEncodesClientId() throws JSONException {
    FlutterJNI mockFlutterJNI = mock(FlutterJNI.class);
    DartExecutor dartExecutor = spy(new DartExecutor(mockFlutterJNI, mock(AssetManager.class)));
    dartExecutor.onAttachedToJNI();
    PlatformChannel platformChannel = new PlatformChannel(dartExecutor);

    ArgumentCaptor<ByteBuffer> bufferCaptor = ArgumentCaptor.forClass(ByteBuffer.class);
    platformChannel.onDismissSystemContextMenu(7);

    verify(dartExecutor).send(eq("flutter/platform"), bufferCaptor.capture(), isNull());
    ByteBuffer buf = bufferCaptor.getValue();
    buf.rewind();
    MethodCall call = JSONMethodCodec.INSTANCE.decodeMethodCall(buf);
    assertEquals("ContextMenu.onDismissSystemContextMenu", call.method);
    JSONArray args = (JSONArray) call.arguments;
    assertEquals(1, args.length());
    assertEquals(7, args.getInt(0));
  }

  @Test
  @SuppressWarnings("deprecation")
  public void platformChannel_onPerformCustomActionEncodesClientIdAndActionId()
      throws JSONException {
    FlutterJNI mockFlutterJNI = mock(FlutterJNI.class);
    DartExecutor dartExecutor = spy(new DartExecutor(mockFlutterJNI, mock(AssetManager.class)));
    dartExecutor.onAttachedToJNI();
    PlatformChannel platformChannel = new PlatformChannel(dartExecutor);

    ArgumentCaptor<ByteBuffer> bufferCaptor = ArgumentCaptor.forClass(ByteBuffer.class);
    platformChannel.onPerformCustomAction(7, "cb-123");

    verify(dartExecutor).send(eq("flutter/platform"), bufferCaptor.capture(), isNull());
    ByteBuffer buf = bufferCaptor.getValue();
    buf.rewind();
    MethodCall call = JSONMethodCodec.INSTANCE.decodeMethodCall(buf);
    assertEquals("ContextMenu.onPerformCustomAction", call.method);
    JSONArray args = (JSONArray) call.arguments;
    assertEquals(2, args.length());
    assertEquals(7, args.getInt(0));
    assertEquals("cb-123", args.getString(1));
  }

  @Test
  @SuppressWarnings("deprecation")
  public void platformChannel_onPerformSystemActionEncodesClientIdActionAndOptionalText()
      throws JSONException {
    FlutterJNI mockFlutterJNI = mock(FlutterJNI.class);
    DartExecutor dartExecutor = spy(new DartExecutor(mockFlutterJNI, mock(AssetManager.class)));
    dartExecutor.onAttachedToJNI();
    PlatformChannel platformChannel = new PlatformChannel(dartExecutor);

    ArgumentCaptor<ByteBuffer> bufferCaptor = ArgumentCaptor.forClass(ByteBuffer.class);
    platformChannel.onPerformSystemAction(7, "paste", "Copied text");
    platformChannel.onPerformSystemAction(7, "copy", null);

    verify(dartExecutor, times(2)).send(eq("flutter/platform"), bufferCaptor.capture(), isNull());

    ByteBuffer pasteBuf = bufferCaptor.getAllValues().get(0);
    pasteBuf.rewind();
    MethodCall pasteCall = JSONMethodCodec.INSTANCE.decodeMethodCall(pasteBuf);
    assertEquals("ContextMenu.onPerformSystemAction", pasteCall.method);
    JSONArray pasteArgs = (JSONArray) pasteCall.arguments;
    assertEquals(3, pasteArgs.length());
    assertEquals(7, pasteArgs.getInt(0));
    assertEquals("paste", pasteArgs.getString(1));
    assertEquals("Copied text", pasteArgs.getString(2));

    ByteBuffer copyBuf = bufferCaptor.getAllValues().get(1);
    copyBuf.rewind();
    MethodCall copyCall = JSONMethodCodec.INSTANCE.decodeMethodCall(copyBuf);
    assertEquals("ContextMenu.onPerformSystemAction", copyCall.method);
    JSONArray copyArgs = (JSONArray) copyCall.arguments;
    assertEquals(3, copyArgs.length());
    assertEquals(7, copyArgs.getInt(0));
    assertEquals("copy", copyArgs.getString(1));
    assertTrue(copyArgs.isNull(2));
  }

  @Test
  @SuppressWarnings("deprecation")
  public void platformChannel_showSystemContextMenuWithoutHandlerReturnsFalseWithoutDismiss()
      throws JSONException {
    FlutterJNI mockFlutterJNI = mock(FlutterJNI.class);
    DartExecutor dartExecutor = spy(new DartExecutor(mockFlutterJNI, mock(AssetManager.class)));
    dartExecutor.onAttachedToJNI();
    PlatformChannel platformChannel = new PlatformChannel(dartExecutor);

    JSONObject validDetachedArgs = new JSONObject();
    JSONObject rect = new JSONObject();
    rect.put("x", 0.0);
    rect.put("y", 0.0);
    rect.put("width", 10.0);
    rect.put("height", 10.0);
    validDetachedArgs.put("targetRect", rect);

    MethodChannel.Result detachedShowResult = mock(MethodChannel.Result.class);
    platformChannel.parsingMethodCallHandler.onMethodCall(
        new MethodCall("ContextMenu.showSystemContextMenu", validDetachedArgs), detachedShowResult);
    verify(detachedShowResult).success(false);
    verify(dartExecutor, never()).send(eq("flutter/platform"), any(), any());

    MethodChannel.Result detachedHideResult = mock(MethodChannel.Result.class);
    platformChannel.parsingMethodCallHandler.onMethodCall(
        new MethodCall("ContextMenu.hideSystemContextMenu", null), detachedHideResult);
    verify(detachedHideResult).success(null);
  }

  @Test
  public void platformChannel_systemContextMenuMalformedArgumentsAndHandlerOwnership()
      throws JSONException {
    FlutterJNI mockFlutterJNI = mock(FlutterJNI.class);
    DartExecutor dartExecutor = new DartExecutor(mockFlutterJNI, mock(AssetManager.class));
    PlatformChannel platformChannel = new PlatformChannel(dartExecutor);

    PlatformChannel.SystemContextMenuHandler handlerA =
        mock(PlatformChannel.SystemContextMenuHandler.class);
    PlatformChannel.SystemContextMenuHandler handlerB =
        mock(PlatformChannel.SystemContextMenuHandler.class);
    when(handlerB.showSystemContextMenu(any())).thenReturn(true);
    platformChannel.setSystemContextMenuHandler(handlerB);
    platformChannel.clearSystemContextMenuHandler(handlerA); // Does not clear handlerB.

    JSONObject validArgs =
        new JSONObject()
            .put(
                "targetRect",
                new JSONObject()
                    .put("x", 0.0)
                    .put("y", 0.0)
                    .put("width", 10.0)
                    .put("height", 10.0));
    MethodChannel.Result activeShowResult = mock(MethodChannel.Result.class);
    platformChannel.parsingMethodCallHandler.onMethodCall(
        new MethodCall("ContextMenu.showSystemContextMenu", validArgs), activeShowResult);
    verify(activeShowResult).success(true);

    MethodChannel.Result nullArgsResult = mock(MethodChannel.Result.class);
    platformChannel.parsingMethodCallHandler.onMethodCall(
        new MethodCall("ContextMenu.showSystemContextMenu", null), nullArgsResult);
    verify(nullArgsResult)
        .error(
            eq("error"),
            eq("Expected JSONObject arguments for ContextMenu.showSystemContextMenu"),
            isNull());

    MethodChannel.Result missingRectResult = mock(MethodChannel.Result.class);
    platformChannel.parsingMethodCallHandler.onMethodCall(
        new MethodCall("ContextMenu.showSystemContextMenu", new JSONObject()), missingRectResult);
    verify(missingRectResult).error(eq("error"), anyString(), isNull());

    platformChannel.clearSystemContextMenuHandler(handlerB); // Clears handlerB.
    MethodChannel.Result clearedShowResult = mock(MethodChannel.Result.class);
    platformChannel.parsingMethodCallHandler.onMethodCall(
        new MethodCall("ContextMenu.showSystemContextMenu", validArgs), clearedShowResult);
    verify(clearedShowResult).success(false);
  }
}
