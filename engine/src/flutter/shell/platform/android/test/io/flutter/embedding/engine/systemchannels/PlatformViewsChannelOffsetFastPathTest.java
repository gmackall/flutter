// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.embedding.engine.systemchannels;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.robolectric.shadows.ShadowLooper.shadowMainLooper;

import android.content.res.AssetManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.flutter.embedding.engine.FlutterJNI;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.StandardMethodCodec;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

@RunWith(AndroidJUnit4.class)
public class PlatformViewsChannelOffsetFastPathTest {
  @After
  public void tearDown() {
    PlatformViewsChannel.enableSynchronousOffset = true;
  }

  private static ByteBuffer encodeOffset(int id, double top, double left) {
    final Map<String, Object> args = new LinkedHashMap<>();
    args.put("id", id);
    args.put("top", top);
    args.put("left", left);
    final ByteBuffer encoded =
        StandardMethodCodec.INSTANCE.encodeMethodCall(new MethodCall("offset", args));
    encoded.flip();
    return encoded;
  }

  @Test
  public void offsetRunsSynchronouslyBeforeLooperIdleAndSkipsReplyForOneWay() {
    final FlutterJNI mockJni = mock(FlutterJNI.class);
    final DartExecutor executor = new DartExecutor(mockJni, mock(AssetManager.class));
    executor.onAttachedToJNI();
    final ArgumentCaptor<io.flutter.embedding.engine.dart.PlatformMessageHandler> handlerCaptor =
        ArgumentCaptor.forClass(io.flutter.embedding.engine.dart.PlatformMessageHandler.class);
    verify(mockJni).setPlatformMessageHandler(handlerCaptor.capture());

    final PlatformViewsChannel channel = new PlatformViewsChannel(executor);
    final PlatformViewsChannel.PlatformViewsHandler mockViewsHandler =
        mock(PlatformViewsChannel.PlatformViewsHandler.class);
    channel.setPlatformViewsHandler(mockViewsHandler);

    final ByteBuffer msg = encodeOffset(7, -142.5, 36.25);
    assertEquals(56, msg.remaining());

    // Invoke without idling main looper: handler.offset must run synchronously inline.
    handlerCaptor.getValue().handleMessageFromDart("flutter/platform_views", msg, 0, 999L);

    verify(mockViewsHandler).offset(eq(7), eq(-142.5), eq(36.25));
    verify(mockJni, never()).invokePlatformMessageEmptyResponseCallback(anyInt());
    verify(mockJni, never())
        .invokePlatformMessageResponseCallback(anyInt(), any(ByteBuffer.class), anyInt());
    verify(mockJni).cleanupMessageData(eq(999L));
    assertEquals(0, msg.limit());
  }

  @Test
  public void offsetSendsStandardNullSuccessEnvelopeWhenReplyRequested() {
    final FlutterJNI mockJni = mock(FlutterJNI.class);
    final DartExecutor executor = new DartExecutor(mockJni, mock(AssetManager.class));
    executor.onAttachedToJNI();
    final ArgumentCaptor<io.flutter.embedding.engine.dart.PlatformMessageHandler> handlerCaptor =
        ArgumentCaptor.forClass(io.flutter.embedding.engine.dart.PlatformMessageHandler.class);
    verify(mockJni).setPlatformMessageHandler(handlerCaptor.capture());

    final PlatformViewsChannel channel = new PlatformViewsChannel(executor);
    final PlatformViewsChannel.PlatformViewsHandler mockViewsHandler =
        mock(PlatformViewsChannel.PlatformViewsHandler.class);
    channel.setPlatformViewsHandler(mockViewsHandler);

    final ByteBuffer msg = encodeOffset(3, 10.0, 20.0);
    handlerCaptor.getValue().handleMessageFromDart("flutter/platform_views", msg, 42, 123L);

    verify(mockViewsHandler).offset(eq(3), eq(10.0), eq(20.0));
    final ArgumentCaptor<ByteBuffer> replyCaptor = ArgumentCaptor.forClass(ByteBuffer.class);
    final ArgumentCaptor<Integer> posCaptor = ArgumentCaptor.forClass(Integer.class);
    verify(mockJni)
        .invokePlatformMessageResponseCallback(
            eq(42), replyCaptor.capture(), posCaptor.capture());
    final ByteBuffer reply = replyCaptor.getValue().duplicate();
    reply.position(0);
    reply.limit(posCaptor.getValue());
    assertEquals(null, StandardMethodCodec.INSTANCE.decodeEnvelope(reply));
  }

  @Test
  public void offsetEncodesErrorEnvelopeOnIllegalStateException() {
    final FlutterJNI mockJni = mock(FlutterJNI.class);
    final DartExecutor executor = new DartExecutor(mockJni, mock(AssetManager.class));
    executor.onAttachedToJNI();
    final ArgumentCaptor<io.flutter.embedding.engine.dart.PlatformMessageHandler> handlerCaptor =
        ArgumentCaptor.forClass(io.flutter.embedding.engine.dart.PlatformMessageHandler.class);
    verify(mockJni).setPlatformMessageHandler(handlerCaptor.capture());

    final PlatformViewsChannel channel = new PlatformViewsChannel(executor);
    final PlatformViewsChannel.PlatformViewsHandler mockViewsHandler =
        mock(PlatformViewsChannel.PlatformViewsHandler.class);
    doThrow(new IllegalStateException("bad state"))
        .when(mockViewsHandler)
        .offset(anyInt(), anyDouble(), anyDouble());
    channel.setPlatformViewsHandler(mockViewsHandler);

    final ByteBuffer msg = encodeOffset(1, 0.0, 0.0);
    handlerCaptor.getValue().handleMessageFromDart("flutter/platform_views", msg, 11, 55L);

    verify(mockJni).invokePlatformMessageResponseCallback(eq(11), any(ByteBuffer.class), anyInt());
    verify(mockJni).cleanupMessageData(eq(55L));
  }

  @Test
  public void nonOffsetMessagesFallBackToQueuedDispatch() {
    final FlutterJNI mockJni = mock(FlutterJNI.class);
    final DartExecutor executor = new DartExecutor(mockJni, mock(AssetManager.class));
    executor.onAttachedToJNI();
    final ArgumentCaptor<io.flutter.embedding.engine.dart.PlatformMessageHandler> handlerCaptor =
        ArgumentCaptor.forClass(io.flutter.embedding.engine.dart.PlatformMessageHandler.class);
    verify(mockJni).setPlatformMessageHandler(handlerCaptor.capture());

    final PlatformViewsChannel channel = new PlatformViewsChannel(executor);
    final PlatformViewsChannel.PlatformViewsHandler mockViewsHandler =
        mock(PlatformViewsChannel.PlatformViewsHandler.class);
    channel.setPlatformViewsHandler(mockViewsHandler);

    final ByteBuffer clearFocusMsg =
        StandardMethodCodec.INSTANCE.encodeMethodCall(new MethodCall("clearFocus", 9));
    clearFocusMsg.flip();

    handlerCaptor
        .getValue()
        .handleMessageFromDart("flutter/platform_views", clearFocusMsg, 7, 321L);
    // Must NOT have executed before looper idle.
    verify(mockViewsHandler, never()).clearFocus(anyInt());

    shadowMainLooper().idle();
    verify(mockViewsHandler).clearFocus(eq(9));
    verify(mockJni).cleanupMessageData(eq(321L));
  }

  @Test
  public void disableSynchronousOffsetFlagFallsBackToQueuedDispatch() {
    PlatformViewsChannel.enableSynchronousOffset = false;

    final FlutterJNI mockJni = mock(FlutterJNI.class);
    final DartExecutor executor = new DartExecutor(mockJni, mock(AssetManager.class));
    executor.onAttachedToJNI();
    final ArgumentCaptor<io.flutter.embedding.engine.dart.PlatformMessageHandler> handlerCaptor =
        ArgumentCaptor.forClass(io.flutter.embedding.engine.dart.PlatformMessageHandler.class);
    verify(mockJni).setPlatformMessageHandler(handlerCaptor.capture());

    final PlatformViewsChannel channel = new PlatformViewsChannel(executor);
    final PlatformViewsChannel.PlatformViewsHandler mockViewsHandler =
        mock(PlatformViewsChannel.PlatformViewsHandler.class);
    channel.setPlatformViewsHandler(mockViewsHandler);

    final ByteBuffer msg = encodeOffset(5, 12.0, 24.0);
    handlerCaptor.getValue().handleMessageFromDart("flutter/platform_views", msg, 0, 777L);

    verify(mockViewsHandler, never()).offset(anyInt(), anyDouble(), anyDouble());

    shadowMainLooper().idle();
    verify(mockViewsHandler).offset(eq(5), eq(12.0), eq(24.0));
    verify(mockJni).cleanupMessageData(eq(777L));
  }

  @Test
  public void nonOffsetMessageWith56BytesRestoresPositionAndFallsBackToQueuedDispatch() {
    final FlutterJNI mockJni = mock(FlutterJNI.class);
    final DartExecutor executor = new DartExecutor(mockJni, mock(AssetManager.class));
    executor.onAttachedToJNI();
    final ArgumentCaptor<io.flutter.embedding.engine.dart.PlatformMessageHandler> handlerCaptor =
        ArgumentCaptor.forClass(io.flutter.embedding.engine.dart.PlatformMessageHandler.class);
    verify(mockJni).setPlatformMessageHandler(handlerCaptor.capture());

    final PlatformViewsChannel channel = new PlatformViewsChannel(executor);
    final PlatformViewsChannel.PlatformViewsHandler mockViewsHandler =
        mock(PlatformViewsChannel.PlatformViewsHandler.class);
    channel.setPlatformViewsHandler(mockViewsHandler);

    // 6-char method name ("custom") + same 3 map keys -> exactly 56 bytes, not "offset".
    final Map<String, Object> args = new LinkedHashMap<>();
    args.put("id", 7);
    args.put("top", 10.0);
    args.put("left", 20.0);
    final ByteBuffer custom56ByteMsg =
        StandardMethodCodec.INSTANCE.encodeMethodCall(new MethodCall("custom", args));
    custom56ByteMsg.flip();
    assertEquals(56, custom56ByteMsg.remaining());

    handlerCaptor
        .getValue()
        .handleMessageFromDart("flutter/platform_views", custom56ByteMsg, 15, 888L);
    // Position must be rewound to 0 when synchronous handler declines the message.
    assertEquals(0, custom56ByteMsg.position());
    verify(mockViewsHandler, never()).offset(anyInt(), anyDouble(), anyDouble());

    shadowMainLooper().idle();
    // MethodChannel responds with notImplemented (empty response callback) once queued task runs.
    verify(mockJni).invokePlatformMessageEmptyResponseCallback(eq(15));
    verify(mockJni).cleanupMessageData(eq(888L));
  }

  @Test
  public void offsetDoesNotOvertakePendingQueuedMessageOnSameChannel() {
    final FlutterJNI mockJni = mock(FlutterJNI.class);
    final DartExecutor executor = new DartExecutor(mockJni, mock(AssetManager.class));
    executor.onAttachedToJNI();
    final ArgumentCaptor<io.flutter.embedding.engine.dart.PlatformMessageHandler> handlerCaptor =
        ArgumentCaptor.forClass(io.flutter.embedding.engine.dart.PlatformMessageHandler.class);
    verify(mockJni).setPlatformMessageHandler(handlerCaptor.capture());

    final PlatformViewsChannel channel = new PlatformViewsChannel(executor);
    final PlatformViewsChannel.PlatformViewsHandler mockViewsHandler =
        mock(PlatformViewsChannel.PlatformViewsHandler.class);
    channel.setPlatformViewsHandler(mockViewsHandler);

    final ByteBuffer clearFocusMsg =
        StandardMethodCodec.INSTANCE.encodeMethodCall(new MethodCall("clearFocus", 4));
    clearFocusMsg.flip();
    final ByteBuffer offsetMsg = encodeOffset(4, 15.0, 25.0);

    // 1. Send a non-synchronous message that gets queued on PlatformTaskQueue.
    handlerCaptor
        .getValue()
        .handleMessageFromDart("flutter/platform_views", clearFocusMsg, 1, 101L);
    // 2. Send an offset message while clearFocus is still pending in the queue:
    //    it must NOT overtake clearFocus.
    handlerCaptor.getValue().handleMessageFromDart("flutter/platform_views", offsetMsg, 0, 102L);
    verify(mockViewsHandler, never()).clearFocus(anyInt());
    verify(mockViewsHandler, never()).offset(anyInt(), anyDouble(), anyDouble());

    // 3. Drain the queue: clearFocus must execute before offset.
    shadowMainLooper().idle();
    final InOrder inOrder = inOrder(mockViewsHandler);
    inOrder.verify(mockViewsHandler).clearFocus(eq(4));
    inOrder.verify(mockViewsHandler).offset(eq(4), eq(15.0), eq(25.0));

    // 4. Once the queue is empty again, subsequent offset messages run synchronously.
    final ByteBuffer secondOffsetMsg = encodeOffset(4, 30.0, 45.0);
    handlerCaptor
        .getValue()
        .handleMessageFromDart("flutter/platform_views", secondOffsetMsg, 0, 103L);
    verify(mockViewsHandler).offset(eq(4), eq(30.0), eq(45.0));
  }
}
