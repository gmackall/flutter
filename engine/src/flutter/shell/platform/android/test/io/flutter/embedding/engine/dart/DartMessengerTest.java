// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.embedding.engine.dart;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertNotNull;
import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.robolectric.shadows.ShadowLooper.shadowMainLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.flutter.embedding.engine.FlutterJNI;
import io.flutter.embedding.engine.dart.DartMessenger.DartMessengerTaskQueue;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.BinaryMessenger.BinaryMessageHandler;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

@RunWith(AndroidJUnit4.class)
public class DartMessengerTest {
  SynchronousTaskQueue synchronousTaskQueue = new SynchronousTaskQueue();

  private static class ReportingUncaughtExceptionHandler
      implements Thread.UncaughtExceptionHandler {
    public Throwable latestException;

    @Override
    public void uncaughtException(Thread t, Throwable e) {
      latestException = e;
    }
  }

  private static class SynchronousTaskQueue implements DartMessengerTaskQueue {
    public void dispatch(Runnable runnable) {
      runnable.run();
    }
  }

  @Test
  public void itHandlesErrors() {
    // Setup test.
    final FlutterJNI fakeFlutterJni = mock(FlutterJNI.class);
    final Thread currentThread = Thread.currentThread();
    final Thread.UncaughtExceptionHandler savedHandler =
        currentThread.getUncaughtExceptionHandler();
    final ReportingUncaughtExceptionHandler reportingHandler =
        new ReportingUncaughtExceptionHandler();
    currentThread.setUncaughtExceptionHandler(reportingHandler);

    // Create object under test.
    final DartMessenger messenger =
        new DartMessenger(fakeFlutterJni, (options) -> synchronousTaskQueue);
    final BinaryMessageHandler throwingHandler = mock(BinaryMessageHandler.class);
    Mockito.doThrow(AssertionError.class)
        .when(throwingHandler)
        .onMessage(any(ByteBuffer.class), any(DartMessenger.Reply.class));
    BinaryMessenger.TaskQueue taskQueue = messenger.makeBackgroundTaskQueue();
    messenger.setMessageHandler("test", throwingHandler, taskQueue);
    messenger.handleMessageFromDart("test", ByteBuffer.allocate(0), 0, 0);
    assertNotNull(reportingHandler.latestException);
    assertTrue(reportingHandler.latestException instanceof AssertionError);
    currentThread.setUncaughtExceptionHandler(savedHandler);
  }

  @Test
  public void givesDirectByteBuffer() {
    // Setup test.
    final FlutterJNI fakeFlutterJni = mock(FlutterJNI.class);
    final DartMessenger messenger =
        new DartMessenger(fakeFlutterJni, (options) -> synchronousTaskQueue);
    final String channel = "foobar";
    final boolean[] wasDirect = {false};
    final BinaryMessenger.BinaryMessageHandler handler =
        (message, reply) -> wasDirect[0] = message.isDirect();
    BinaryMessenger.TaskQueue taskQueue = messenger.makeBackgroundTaskQueue();
    messenger.setMessageHandler(channel, handler, taskQueue);
    final ByteBuffer message = ByteBuffer.allocateDirect(4 * 2);
    message.rewind();
    message.putChar('a');
    message.putChar('b');
    message.putChar('c');
    message.putChar('d');
    messenger.handleMessageFromDart(channel, message, /*replyId=*/ 123, 0);
    assertTrue(wasDirect[0]);
  }

  @Test
  public void directByteBufferLimitZeroAfterUsage() {
    // Setup test.
    final FlutterJNI fakeFlutterJni = mock(FlutterJNI.class);
    final DartMessenger messenger =
        new DartMessenger(fakeFlutterJni, (options) -> synchronousTaskQueue);
    final String channel = "foobar";
    final ByteBuffer[] byteBuffers = {null};
    final int bufferSize = 4 * 2;
    final BinaryMessenger.BinaryMessageHandler handler =
        (message, reply) -> {
          byteBuffers[0] = message;
          assertEquals(bufferSize, byteBuffers[0].limit());
        };
    BinaryMessenger.TaskQueue taskQueue = messenger.makeBackgroundTaskQueue();
    messenger.setMessageHandler(channel, handler, taskQueue);
    final ByteBuffer message = ByteBuffer.allocateDirect(bufferSize);
    message.rewind();
    message.putChar('a');
    message.putChar('b');
    message.putChar('c');
    message.putChar('d');
    messenger.handleMessageFromDart(channel, message, /*replyId=*/ 123, 0);
    assertNotNull(byteBuffers[0]);
    assertTrue(byteBuffers[0].isDirect());
    assertEquals(0, byteBuffers[0].limit());
  }

  @Test
  public void directByteBufferLimitZeroAfterReply() {
    // Setup test.
    final FlutterJNI fakeFlutterJni = mock(FlutterJNI.class);
    final DartMessenger messenger = new DartMessenger(fakeFlutterJni);
    final ByteBuffer message = ByteBuffer.allocateDirect(4 * 2);
    final String channel = "foobar";
    message.rewind();
    message.putChar('a');
    message.putChar('b');
    message.putChar('c');
    message.putChar('d');
    final ByteBuffer[] byteBuffers = {null};
    BinaryMessenger.BinaryReply callback =
        (reply) -> {
          assertTrue(reply.isDirect());
          byteBuffers[0] = reply;
        };
    messenger.send(channel, null, callback);
    messenger.handlePlatformMessageResponse(1, message);
    assertEquals(0, byteBuffers[0].limit());
  }

  @Test
  public void replyIdIncrementsOnNullReply() {
    /// Setup test.
    final FlutterJNI fakeFlutterJni = mock(FlutterJNI.class);
    final DartMessenger messenger = new DartMessenger(fakeFlutterJni);
    final String channel = "foobar";
    messenger.send(channel, null, null);
    verify(fakeFlutterJni, times(1)).dispatchEmptyPlatformMessage(eq("foobar"), eq(1));
    messenger.send(channel, null, null);
    verify(fakeFlutterJni, times(1)).dispatchEmptyPlatformMessage(eq("foobar"), eq(2));
  }

  @Test
  public void cleansUpMessageData() throws InterruptedException {
    final FlutterJNI fakeFlutterJni = mock(FlutterJNI.class);
    final DartMessenger messenger =
        new DartMessenger(fakeFlutterJni, (options) -> synchronousTaskQueue);
    BinaryMessenger.TaskQueue taskQueue = messenger.makeBackgroundTaskQueue();
    String channel = "foobar";
    BinaryMessenger.BinaryMessageHandler handler =
        (ByteBuffer message, BinaryMessenger.BinaryReply reply) -> reply.reply(null);
    messenger.setMessageHandler(channel, handler, taskQueue);
    final ByteBuffer message = ByteBuffer.allocateDirect(4 * 2);
    final int replyId = 1;
    final long messageData = 1234;
    messenger.handleMessageFromDart(channel, message, replyId, messageData);
    verify(fakeFlutterJni).cleanupMessageData(eq(messageData));
  }

  @Test
  public void cleansUpMessageDataOnError() throws InterruptedException {
    final FlutterJNI fakeFlutterJni = mock(FlutterJNI.class);
    final DartMessenger messenger =
        new DartMessenger(fakeFlutterJni, (options) -> synchronousTaskQueue);
    BinaryMessenger.TaskQueue taskQueue = messenger.makeBackgroundTaskQueue();
    String channel = "foobar";
    BinaryMessenger.BinaryMessageHandler handler =
        (ByteBuffer message, BinaryMessenger.BinaryReply reply) -> {
          throw new RuntimeException("hello");
        };
    messenger.setMessageHandler(channel, handler, taskQueue);
    final ByteBuffer message = ByteBuffer.allocateDirect(4 * 2);
    final int replyId = 1;
    final long messageData = 1234;

    messenger.handleMessageFromDart(channel, message, replyId, messageData);
    verify(fakeFlutterJni).cleanupMessageData(eq(messageData));
  }

  @Test
  public void emptyResponseWhenHandlerIsNotSet() throws InterruptedException {
    final FlutterJNI fakeFlutterJni = mock(FlutterJNI.class);
    final DartMessenger messenger =
        new DartMessenger(fakeFlutterJni, (options) -> synchronousTaskQueue);
    final String channel = "foobar";
    final ByteBuffer message = ByteBuffer.allocateDirect(4 * 2);
    final int replyId = 1;
    final long messageData = 1234;

    messenger.handleMessageFromDart(channel, message, replyId, messageData);
    shadowMainLooper().idle();
    verify(fakeFlutterJni).invokePlatformMessageEmptyResponseCallback(replyId);
  }

  @Test
  public void buffersResponseWhenHandlerIsNotSet() throws InterruptedException {
    final FlutterJNI fakeFlutterJni = mock(FlutterJNI.class);
    final DartMessenger messenger =
        new DartMessenger(fakeFlutterJni, (options) -> synchronousTaskQueue);
    final BinaryMessenger.TaskQueue taskQueue = messenger.makeBackgroundTaskQueue();
    final String channel = "foobar";
    final ByteBuffer message = ByteBuffer.allocateDirect(4 * 2);
    final int replyId = 1;
    final long messageData = 1234;

    messenger.enableBufferingIncomingMessages();
    messenger.handleMessageFromDart(channel, message, replyId, messageData);

    shadowMainLooper().idle();
    verify(fakeFlutterJni, never()).invokePlatformMessageEmptyResponseCallback(eq(replyId));

    final BinaryMessenger.BinaryMessageHandler handler =
        (ByteBuffer msg, BinaryMessenger.BinaryReply reply) ->
            reply.reply(ByteBuffer.wrap("done".getBytes()));
    messenger.setMessageHandler(channel, handler, taskQueue);

    shadowMainLooper().idle();
    verify(fakeFlutterJni, never()).invokePlatformMessageEmptyResponseCallback(eq(replyId));

    final ArgumentCaptor<ByteBuffer> response = ArgumentCaptor.forClass(ByteBuffer.class);
    verify(fakeFlutterJni)
        .invokePlatformMessageResponseCallback(anyInt(), response.capture(), anyInt());
    assertArrayEquals("done".getBytes(), response.getValue().array());
  }

  @Test
  public void disableBufferingTriggersEmptyResponseForPendingMessages()
      throws InterruptedException {
    final FlutterJNI fakeFlutterJni = mock(FlutterJNI.class);
    final DartMessenger messenger =
        new DartMessenger(fakeFlutterJni, (options) -> synchronousTaskQueue);
    final String channel = "foobar";
    final ByteBuffer message = ByteBuffer.allocateDirect(4 * 2);
    final int replyId = 1;
    final long messageData = 1234;

    messenger.enableBufferingIncomingMessages();
    messenger.handleMessageFromDart(channel, message, replyId, messageData);
    shadowMainLooper().idle();
    verify(fakeFlutterJni, never()).invokePlatformMessageEmptyResponseCallback(replyId);

    messenger.disableBufferingIncomingMessages();
    shadowMainLooper().idle();
    verify(fakeFlutterJni).invokePlatformMessageEmptyResponseCallback(replyId);
  }

  @Test
  public void emptyResponseWhenHandlerIsUnregistered() throws InterruptedException {
    final FlutterJNI fakeFlutterJni = mock(FlutterJNI.class);
    final DartMessenger messenger =
        new DartMessenger(fakeFlutterJni, (options) -> synchronousTaskQueue);
    final BinaryMessenger.TaskQueue taskQueue = messenger.makeBackgroundTaskQueue();
    final String channel = "foobar";
    final ByteBuffer message = ByteBuffer.allocateDirect(4 * 2);
    final int replyId = 1;
    final long messageData = 1234;

    messenger.enableBufferingIncomingMessages();
    messenger.handleMessageFromDart(channel, message, replyId, messageData);

    shadowMainLooper().idle();
    verify(fakeFlutterJni, never()).invokePlatformMessageEmptyResponseCallback(eq(replyId));

    final BinaryMessenger.BinaryMessageHandler handler =
        (ByteBuffer msg, BinaryMessenger.BinaryReply reply) ->
            reply.reply(ByteBuffer.wrap("done".getBytes()));
    messenger.setMessageHandler(channel, handler, taskQueue);

    shadowMainLooper().idle();
    verify(fakeFlutterJni, never()).invokePlatformMessageEmptyResponseCallback(eq(replyId));

    final ArgumentCaptor<ByteBuffer> response = ArgumentCaptor.forClass(ByteBuffer.class);
    verify(fakeFlutterJni)
        .invokePlatformMessageResponseCallback(anyInt(), response.capture(), anyInt());
    assertArrayEquals("done".getBytes(), response.getValue().array());

    messenger.disableBufferingIncomingMessages();
    messenger.setMessageHandler(channel, null, null); // Unregister handler.

    messenger.handleMessageFromDart(channel, message, replyId, messageData);
    shadowMainLooper().idle();
    verify(fakeFlutterJni).invokePlatformMessageEmptyResponseCallback(replyId);
  }

  @Test
  public void testSerialTaskQueue() throws InterruptedException {
    final FlutterJNI fakeFlutterJni = mock(FlutterJNI.class);
    final DartMessenger messenger = new DartMessenger(fakeFlutterJni);
    final ExecutorService taskQueuePool = Executors.newFixedThreadPool(4);
    final DartMessengerTaskQueue taskQueue = new DartMessenger.SerialTaskQueue(taskQueuePool);
    final int count = 5000;
    final LinkedList<Integer> ints = new LinkedList<>();
    Random rand = new Random();
    for (int i = 0; i < count; ++i) {
      final int value = i;
      taskQueue.dispatch(
          () -> {
            try {
              Thread.sleep(rand.nextInt(10));
            } catch (InterruptedException ex) {
              System.out.println(ex.toString());
            }
            ints.add(value);
          });
      taskQueuePool.execute(
          () -> {
            // Add some extra noise to make sure we aren't always handling on the same thread.
            try {
              Thread.sleep(rand.nextInt(10));
            } catch (InterruptedException ex) {
              System.out.println(ex.toString());
            }
          });
    }
    CountDownLatch latch = new CountDownLatch(1);
    taskQueue.dispatch(latch::countDown);
    latch.await();
    assertEquals(count, ints.size());
    for (int i = 0; i < count - 1; ++i) {
      assertEquals((int) ints.get(i), ints.get(i + 1) - 1);
    }
  }

  @Test
  public void synchronousHandlerNotInvokedFromBackgroundThreadAndMessageQueued()
      throws InterruptedException {
    final FlutterJNI fakeFlutterJni = mock(FlutterJNI.class);
    final DartMessenger messenger = new DartMessenger(fakeFlutterJni);
    final String channel = "sync_channel";
    final boolean[] syncCalled = {false};
    final boolean[] asyncCalled = {false};

    messenger.setMessageHandler(channel, (msg, reply) -> asyncCalled[0] = true);
    messenger.setSynchronousMessageHandler(
        channel,
        (msg, reply) -> {
          syncCalled[0] = true;
          return true;
        });

    final ByteBuffer message = ByteBuffer.allocateDirect(4);
    final CountDownLatch backgroundLatch = new CountDownLatch(1);
    final Thread bgThread =
        new Thread(
            () -> {
              messenger.handleMessageFromDart(channel, message, 7, 456L);
              backgroundLatch.countDown();
            });
    bgThread.start();
    backgroundLatch.await();

    assertFalse(syncCalled[0]);
    assertFalse(asyncCalled[0]);

    shadowMainLooper().idle();
    assertFalse(syncCalled[0]);
    assertTrue(asyncCalled[0]);
    verify(fakeFlutterJni).cleanupMessageData(eq(456L));
  }

  @Test
  public void synchronousHandlerRestoresBufferPositionAndQueuesWhenReturningFalse() {
    final FlutterJNI fakeFlutterJni = mock(FlutterJNI.class);
    final DartMessenger messenger = new DartMessenger(fakeFlutterJni);
    final String channel = "sync_channel";
    final int[] observedPositionInAsyncHandler = {-1};

    messenger.setMessageHandler(
        channel, (msg, reply) -> observedPositionInAsyncHandler[0] = msg.position());
    messenger.setSynchronousMessageHandler(
        channel,
        (msg, reply) -> {
          // Consume 2 bytes and decline synchronous handling.
          msg.getShort();
          return false;
        });

    final ByteBuffer message = ByteBuffer.allocateDirect(8);
    message.putInt(0x12345678);
    message.putInt(0x9abcdef0);
    message.position(2);

    messenger.handleMessageFromDart(channel, message, 3, 789L);
    // Position must be restored immediately to 2 and async handler not yet invoked.
    assertEquals(2, message.position());
    assertEquals(-1, observedPositionInAsyncHandler[0]);

    shadowMainLooper().idle();
    assertEquals(2, observedPositionInAsyncHandler[0]);
    verify(fakeFlutterJni).cleanupMessageData(eq(789L));
  }

  @Test
  public void synchronousHandlerExceptionSendsEmptyReplyAndCleansUpOnce() {
    final FlutterJNI fakeFlutterJni = mock(FlutterJNI.class);
    final DartMessenger messenger = new DartMessenger(fakeFlutterJni);
    final String channel = "sync_channel";
    final boolean[] asyncCalled = {false};

    messenger.setMessageHandler(channel, (msg, reply) -> asyncCalled[0] = true);
    messenger.setSynchronousMessageHandler(
        channel,
        (msg, reply) -> {
          throw new RuntimeException("sync failure");
        });

    final ByteBuffer message = ByteBuffer.allocateDirect(4);
    messenger.handleMessageFromDart(channel, message, 99, 321L);

    shadowMainLooper().idle();
    assertFalse(asyncCalled[0]);
    verify(fakeFlutterJni, times(1)).invokePlatformMessageEmptyResponseCallback(eq(99));
    verify(fakeFlutterJni, times(1)).cleanupMessageData(eq(321L));
    assertEquals(0, message.limit());
  }

  @Test
  public void synchronousHandlerPreservesFifoWhenChannelBuffersOrHasPendingQueuedMessages() {
    final FlutterJNI fakeFlutterJni = mock(FlutterJNI.class);
    final DartMessenger messenger = new DartMessenger(fakeFlutterJni);
    final String channel = "sync_channel";
    final LinkedList<Integer> order = new LinkedList<>();

    messenger.enableBufferingIncomingMessages();
    final ByteBuffer msg1 = ByteBuffer.allocateDirect(4);
    msg1.putInt(0, 1);
    messenger.handleMessageFromDart(channel, msg1, 1, 1001L);

    messenger.setMessageHandler(channel, (msg, reply) -> order.add(msg.getInt(0)));
    messenger.setSynchronousMessageHandler(
        channel,
        (msg, reply) -> {
          order.add(msg.getInt(0) * 10);
          return true;
        });

    // Because msg1 was just dispatched from bufferedMessages to PlatformTaskQueue and has not run
    // yet, msg2 must also be queued rather than overtaking msg1 synchronously.
    final ByteBuffer msg2 = ByteBuffer.allocateDirect(4);
    msg2.putInt(0, 2);
    messenger.handleMessageFromDart(channel, msg2, 2, 1002L);
    assertTrue(order.isEmpty());

    shadowMainLooper().idle();
    assertEquals(2, order.size());
    assertEquals(Integer.valueOf(1), order.get(0));
    assertEquals(Integer.valueOf(2), order.get(1));

    // Once the queue has drained, msg3 runs synchronously (multiplied by 10).
    final ByteBuffer msg3 = ByteBuffer.allocateDirect(4);
    msg3.putInt(0, 3);
    messenger.handleMessageFromDart(channel, msg3, 0, 1003L);
    assertEquals(3, order.size());
    assertEquals(Integer.valueOf(30), order.get(2));
  }

  @Test
  public void setMessageHandlerReplacesHandlerInfoAndClearsSynchronousHandler() {
    final FlutterJNI fakeFlutterJni = mock(FlutterJNI.class);
    final DartMessenger messenger = new DartMessenger(fakeFlutterJni);
    final String channel = "sync_channel";
    final boolean[] syncCalled = {false};
    final boolean[] secondAsyncCalled = {false};

    messenger.setMessageHandler(channel, (msg, reply) -> {});
    messenger.setSynchronousMessageHandler(
        channel,
        (msg, reply) -> {
          syncCalled[0] = true;
          return true;
        });

    // Re-registering setMessageHandler replaces HandlerInfo and clears the synchronous handler.
    messenger.setMessageHandler(channel, (msg, reply) -> secondAsyncCalled[0] = true);

    final ByteBuffer message = ByteBuffer.allocateDirect(4);
    messenger.handleMessageFromDart(channel, message, 0, 555L);
    assertFalse(syncCalled[0]);
    assertFalse(secondAsyncCalled[0]);

    shadowMainLooper().idle();
    assertTrue(secondAsyncCalled[0]);
  }
}
