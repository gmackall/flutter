// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.embedding.engine.systemchannels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.refEq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
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
  public void platformChannel_systemContextMenuMessagesDoNotRequirePlatformMessageHandler()
      throws JSONException {
    FlutterJNI mockFlutterJNI = mock(FlutterJNI.class);
    DartExecutor dartExecutor = spy(new DartExecutor(mockFlutterJNI, mock(AssetManager.class)));
    PlatformChannel platformChannel = new PlatformChannel(dartExecutor);
    PlatformChannel.SystemContextMenuHandler mockContextMenuHandler =
        mock(PlatformChannel.SystemContextMenuHandler.class);
    platformChannel.setSystemContextMenuHandler(mockContextMenuHandler);

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
    args.put("sessionId", 99);

    MethodChannel.Result showResult = mock(MethodChannel.Result.class);
    platformChannel.parsingMethodCallHandler.onMethodCall(
        new MethodCall("ContextMenu.showSystemContextMenu", args), showResult);

    ArgumentCaptor<PlatformChannel.SystemContextMenuRequest> requestCaptor =
        ArgumentCaptor.forClass(PlatformChannel.SystemContextMenuRequest.class);
    verify(mockContextMenuHandler).showSystemContextMenu(requestCaptor.capture());
    verify(showResult).success(null);

    PlatformChannel.SystemContextMenuRequest request = requestCaptor.getValue();
    assertEquals(12.5, request.targetRect.x, 0.001);
    assertEquals(34.0, request.targetRect.y, 0.001);
    assertEquals(56.0, request.targetRect.width, 0.001);
    assertEquals(20.0, request.targetRect.height, 0.001);
    assertEquals(Integer.valueOf(99), request.sessionId);
    assertNotNull(request.items);
    assertEquals(2, request.items.size());
    assertEquals("paste", request.items.get(0).type);
    assertNull(request.items.get(0).title);
    assertEquals("custom", request.items.get(1).type);
    assertEquals("Translate", request.items.get(1).title);
    assertEquals("42", request.items.get(1).id);

    MethodChannel.Result hideResult = mock(MethodChannel.Result.class);
    platformChannel.parsingMethodCallHandler.onMethodCall(
        new MethodCall("ContextMenu.hideSystemContextMenu", null), hideResult);
    verify(mockContextMenuHandler).hideSystemContextMenu();
    verify(hideResult).success(null);
  }

  @Test
  @SuppressWarnings("deprecation")
  public void platformChannel_systemContextMenuOutgoingCallbacksEncodeExpectedArguments()
      throws JSONException {
    FlutterJNI mockFlutterJNI = mock(FlutterJNI.class);
    DartExecutor dartExecutor = spy(new DartExecutor(mockFlutterJNI, mock(AssetManager.class)));
    dartExecutor.onAttachedToJNI();
    PlatformChannel platformChannel = new PlatformChannel(dartExecutor);

    ArgumentCaptor<ByteBuffer> bufferCaptor = ArgumentCaptor.forClass(ByteBuffer.class);
    platformChannel.onPerformCustomAction(7, "cb-123");
    platformChannel.onDismissSystemContextMenu(7);
    platformChannel.onPerformCustomAction(7, "cb-456", 55);
    platformChannel.onDismissSystemContextMenu(7, 55);

    verify(dartExecutor, org.mockito.Mockito.times(4))
        .send(eq("flutter/platform"), bufferCaptor.capture(), isNull());

    ByteBuffer first = bufferCaptor.getAllValues().get(0);
    first.rewind();
    MethodCall customCall = JSONMethodCodec.INSTANCE.decodeMethodCall(first);
    assertEquals("ContextMenu.onPerformCustomAction", customCall.method);
    JSONArray customArgs = (JSONArray) customCall.arguments;
    assertEquals(2, customArgs.length());
    assertEquals(7, customArgs.getInt(0));
    assertEquals("cb-123", customArgs.getString(1));

    ByteBuffer second = bufferCaptor.getAllValues().get(1);
    second.rewind();
    MethodCall dismissCall = JSONMethodCodec.INSTANCE.decodeMethodCall(second);
    assertEquals("ContextMenu.onDismissSystemContextMenu", dismissCall.method);
    JSONArray dismissArgs = (JSONArray) dismissCall.arguments;
    assertEquals(1, dismissArgs.length());
    assertEquals(7, dismissArgs.getInt(0));

    ByteBuffer third = bufferCaptor.getAllValues().get(2);
    third.rewind();
    MethodCall sessionCustomCall = JSONMethodCodec.INSTANCE.decodeMethodCall(third);
    assertEquals("ContextMenu.onPerformCustomAction", sessionCustomCall.method);
    JSONArray sessionCustomArgs = (JSONArray) sessionCustomCall.arguments;
    assertEquals(3, sessionCustomArgs.length());
    assertEquals(7, sessionCustomArgs.getInt(0));
    assertEquals("cb-456", sessionCustomArgs.getString(1));
    assertEquals(55, sessionCustomArgs.getInt(2));

    ByteBuffer fourth = bufferCaptor.getAllValues().get(3);
    fourth.rewind();
    MethodCall sessionDismissCall = JSONMethodCodec.INSTANCE.decodeMethodCall(fourth);
    assertEquals("ContextMenu.onDismissSystemContextMenu", sessionDismissCall.method);
    JSONArray sessionDismissArgs = (JSONArray) sessionDismissCall.arguments;
    assertEquals(2, sessionDismissArgs.length());
    assertEquals(7, sessionDismissArgs.getInt(0));
    assertEquals(55, sessionDismissArgs.getInt(1));
  }

  @Test
  @SuppressWarnings("deprecation")
  public void platformChannel_systemContextMenuWithoutHandlerAndMalformedJson()
      throws JSONException {
    FlutterJNI mockFlutterJNI = mock(FlutterJNI.class);
    DartExecutor dartExecutor = spy(new DartExecutor(mockFlutterJNI, mock(AssetManager.class)));
    dartExecutor.onAttachedToJNI();
    PlatformChannel platformChannel = new PlatformChannel(dartExecutor);

    // Without a registered handler (detached view), valid show and hide complete with success(null)
    // rather than throwing MissingPluginException, and show notifies dismissal with sessionId.
    JSONObject validDetachedArgs = new JSONObject();
    JSONObject rect = new JSONObject();
    rect.put("x", 0.0);
    rect.put("y", 0.0);
    rect.put("width", 10.0);
    rect.put("height", 10.0);
    validDetachedArgs.put("targetRect", rect);
    validDetachedArgs.put("sessionId", 77);

    MethodChannel.Result detachedShowResult = mock(MethodChannel.Result.class);
    platformChannel.parsingMethodCallHandler.onMethodCall(
        new MethodCall("ContextMenu.showSystemContextMenu", validDetachedArgs), detachedShowResult);
    verify(detachedShowResult).success(null);

    ArgumentCaptor<ByteBuffer> detachedDismissCaptor = ArgumentCaptor.forClass(ByteBuffer.class);
    verify(dartExecutor).send(eq("flutter/platform"), detachedDismissCaptor.capture(), isNull());
    ByteBuffer dismissBuf = detachedDismissCaptor.getValue();
    dismissBuf.rewind();
    MethodCall dismissCall = JSONMethodCodec.INSTANCE.decodeMethodCall(dismissBuf);
    assertEquals("ContextMenu.onDismissSystemContextMenu", dismissCall.method);
    JSONArray dismissArgs = (JSONArray) dismissCall.arguments;
    assertEquals(-1, dismissArgs.getInt(0));
    assertEquals(77, dismissArgs.getInt(1));

    MethodChannel.Result detachedHideResult = mock(MethodChannel.Result.class);
    platformChannel.parsingMethodCallHandler.onMethodCall(
        new MethodCall("ContextMenu.hideSystemContextMenu", null), detachedHideResult);
    verify(detachedHideResult).success(null);

    // Ownership check on clearSystemContextMenuHandler.
    PlatformChannel.SystemContextMenuHandler handlerA =
        mock(PlatformChannel.SystemContextMenuHandler.class);
    PlatformChannel.SystemContextMenuHandler handlerB =
        mock(PlatformChannel.SystemContextMenuHandler.class);
    platformChannel.setSystemContextMenuHandler(handlerB);
    platformChannel.clearSystemContextMenuHandler(handlerA); // Does not clear handlerB.

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
    verify(missingRectResult).error(eq("error"), org.mockito.Mockito.anyString(), isNull());

    platformChannel.clearSystemContextMenuHandler(handlerB); // Clears handlerB.
    MethodChannel.Result clearedHideResult = mock(MethodChannel.Result.class);
    platformChannel.parsingMethodCallHandler.onMethodCall(
        new MethodCall("ContextMenu.hideSystemContextMenu", null), clearedHideResult);
    verify(clearedHideResult).success(null);
  }
}
