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
}
