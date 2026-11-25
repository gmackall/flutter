// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.view;

import static io.flutter.Build.API_LEVELS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.UiModeManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.SpannedString;
import android.text.style.LocaleSpan;
import android.text.style.TtsSpan;
import android.text.style.URLSpan;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.flutter.embedding.engine.FlutterJNI;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.mutatorsstack.FlutterMutatorView;
import io.flutter.embedding.engine.systemchannels.AccessibilityChannel;
import io.flutter.plugin.common.BasicMessageChannel;
import io.flutter.plugin.platform.PlatformViewWrapper;
import io.flutter.plugin.platform.PlatformViewsAccessibilityDelegate;
import io.flutter.view.AccessibilityBridge.Action;
import io.flutter.view.AccessibilityBridge.Flag;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.invocation.InvocationOnMock;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(AndroidJUnit4.class)
public class AccessibilityBridgeTest {

  private static final int ACCESSIBILITY_FEATURE_NAVIGATION = 1 << 0;
  private static final int ACCESSIBILITY_FEATURE_INVERT_COLORS = 1 << 1;
  private static final int ACCESSIBILITY_FEATURE_DISABLE_ANIMATIONS = 1 << 2;
  private static final int ACCESSIBILITY_FEATURE_BOLD_TEXT = 1 << 3;
  private static final int ACCESSIBILITY_FEATURE_HIGH_CONTRAST = 1 << 5;
  private static final int ACCESSIBILITY_FEATURE_NO_ANNOUNCE = 1 << 7;

  @Test
  public void itDescribesNonTextFieldsWithAContentDescription() {
    AccessibilityBridge accessibilityBridge = setUpBridge();

    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    testSemanticsNode.label = "Hello, World";
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);

    assertEquals("Hello, World", nodeInfo.getContentDescription().toString());
    assertNull(nodeInfo.getText());
  }

  @Config(sdk = API_LEVELS.API_28)
  @TargetApi(API_LEVELS.API_28)
  @Test
  public void itDescribesTextFieldsWithTextAndHint() {
    AccessibilityBridge accessibilityBridge = setUpBridge();

    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    testSemanticsNode.value = "Hello, World";
    testSemanticsNode.label = "some label";
    testSemanticsNode.hint = "some hint";
    testSemanticsNode.addFlag(AccessibilityBridge.Flag.IS_TEXT_FIELD);
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);

    assertNull(nodeInfo.getContentDescription());
    assertEquals("Hello, World", nodeInfo.getText().toString());
    assertEquals("some label, some hint", nodeInfo.getHintText().toString());
  }

  @Test
  public void itTakesGlobalCoordinatesOfFlutterViewIntoAccount() {
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    final int position = 88;
    // The getBoundsInScreen() in createAccessibilityNodeInfo() needs
    // View.getLocationOnScreen()
    doAnswer(
            invocation -> {
              int[] outLocation = (int[]) invocation.getArguments()[0];
              outLocation[0] = position;
              outLocation[1] = position;
              return null;
            })
        .when(mockRootView)
        .getLocationOnScreen(any(int[].class));

    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(mockRootView, mockManager, mockViewEmbedder);

    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();

    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);

    Rect outBoundsInScreen = new Rect();
    nodeInfo.getBoundsInScreen(outBoundsInScreen);
    assertEquals(position, outBoundsInScreen.left);
    assertEquals(position, outBoundsInScreen.top);
  }

  @Test
  public void itSetsNoAnnounceAccessibleFlagByDefault() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    when(mockManager.isTouchExplorationEnabled()).thenReturn(false);
    setUpBridge(
        /* rootAccessibilityView= */ mockRootView,
        /* accessibilityChannel= */ mockChannel,
        /* accessibilityManager= */ mockManager,
        /* contentResolver= */ null,
        /* accessibilityViewEmbedder= */ mockViewEmbedder,
        /* platformViewsAccessibilityDelegate= */ null);
    verify(mockChannel, atLeastOnce()).setAccessibilityFeatures(ACCESSIBILITY_FEATURE_NO_ANNOUNCE);
  }

  @Test
  public void itSetsDelegateOnChannel() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);

    AccessibilityBridge bridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);
    verify(mockChannel).setAccessibilityMessageHandler(bridge.accessibilityMessageHandler);
  }

  @Test
  public void setSemanticsTreeEnabledFalseClearsSemanticsTree() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);

    AccessibilityBridge bridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);

    // Add a node to the semantics tree.
    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(bridge);
    assertFalse(bridge.flutterSemanticsTree.isEmpty());

    // Disable semantics and check that the tree is cleared.
    bridge.accessibilityMessageHandler.resetSemantics();
    assertTrue(bridge.flutterSemanticsTree.isEmpty());
  }

  @Test
  public void itSetsAccessibleNavigation() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    when(mockManager.isTouchExplorationEnabled()).thenReturn(false);
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);
    ArgumentCaptor<AccessibilityManager.TouchExplorationStateChangeListener> listenerCaptor =
        ArgumentCaptor.forClass(AccessibilityManager.TouchExplorationStateChangeListener.class);
    verify(mockManager).addTouchExplorationStateChangeListener(listenerCaptor.capture());

    assertFalse(accessibilityBridge.getAccessibleNavigation());
    verify(mockChannel, atLeastOnce()).setAccessibilityFeatures(ACCESSIBILITY_FEATURE_NO_ANNOUNCE);
    reset(mockChannel);

    // Simulate assistive technology accessing accessibility tree.
    accessibilityBridge.createAccessibilityNodeInfo(0);
    verify(mockChannel, atLeastOnce())
        .setAccessibilityFeatures(
            ACCESSIBILITY_FEATURE_NAVIGATION | ACCESSIBILITY_FEATURE_NO_ANNOUNCE);

    // Simulate turning off TalkBack.
    reset(mockChannel);
    listenerCaptor.getValue().onTouchExplorationStateChanged(false);
    verify(mockChannel, atLeastOnce()).setAccessibilityFeatures(ACCESSIBILITY_FEATURE_NO_ANNOUNCE);
    assertFalse(accessibilityBridge.getAccessibleNavigation());
  }

  @Test
  public void itDoesNotContainADescriptionIfScopesRoute() {
    AccessibilityBridge accessibilityBridge = setUpBridge();

    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    testSemanticsNode.label = "Hello, World";
    testSemanticsNode.addFlag(AccessibilityBridge.Flag.SCOPES_ROUTE);
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();

    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);

    assertNull(nodeInfo.getContentDescription());
    assertNull(nodeInfo.getText());
  }

  @Test
  public void itCreatesURLSpanForlinkURL() {
    AccessibilityBridge accessibilityBridge = setUpBridge();

    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    testSemanticsNode.label = "Hello";
    testSemanticsNode.linkUrl = "https://flutter.dev";
    testSemanticsNode.addFlag(AccessibilityBridge.Flag.IS_LINK);
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();

    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    SpannableString actual = (SpannableString) nodeInfo.getContentDescription();
    assertEquals("Hello", actual.toString());
    Object[] objectSpans = actual.getSpans(0, actual.length(), Object.class);
    assertEquals(1, objectSpans.length);
    URLSpan span = (URLSpan) objectSpans[0];
    assertEquals("https://flutter.dev", span.getURL());
  }

  @Test
  public void itUnfocusesPlatformViewWhenPlatformViewGoesAway() {
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(mockRootView, mockManager, mockViewEmbedder);

    // Sent a11y tree with platform view.
    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode platformView = new TestSemanticsNode();
    platformView.id = 1;
    platformView.platformViewId = 42;
    root.children.add(platformView);
    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    // Set a11y focus to platform view.
    View mockView = mock(View.class);
    AccessibilityEvent focusEvent = mock(AccessibilityEvent.class);
    when(mockViewEmbedder.requestSendAccessibilityEvent(mockView, mockView, focusEvent))
        .thenReturn(true);
    when(mockViewEmbedder.getRecordFlutterId(mockView, focusEvent)).thenReturn(42);
    when(focusEvent.getEventType()).thenReturn(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
    accessibilityBridge.externalViewRequestSendAccessibilityEvent(mockView, mockView, focusEvent);

    // Replace the platform view.
    TestSemanticsNode node = new TestSemanticsNode();
    node.id = 2;
    root.children.clear();
    root.children.add(node);
    testSemanticsUpdate = root.toUpdate();
    when(mockManager.isEnabled()).thenReturn(true);
    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    // Check that unfocus event was sent.
    ArgumentCaptor<AccessibilityEvent> eventCaptor =
        ArgumentCaptor.forClass(AccessibilityEvent.class);
    verify(mockParent, times(2))
        .requestSendAccessibilityEvent(eq(mockRootView), eventCaptor.capture());
    AccessibilityEvent event = eventCaptor.getAllValues().get(0);
    assertEquals(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED, event.getEventType());
  }

  @Test
  public void itAnnouncesRouteNameWhenAddingNewRoute() {
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(mockRootView, mockManager, mockViewEmbedder);
    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode node1 = new TestSemanticsNode();
    node1.id = 1;
    node1.addFlag(AccessibilityBridge.Flag.SCOPES_ROUTE);
    node1.addFlag(AccessibilityBridge.Flag.NAMES_ROUTE);
    node1.label = "node1";
    root.children.add(node1);
    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    verify(mockRootView, times(1)).setAccessibilityPaneTitle(eq("node1"));

    TestSemanticsNode new_root = new TestSemanticsNode();
    new_root.id = 0;
    TestSemanticsNode new_node1 = new TestSemanticsNode();
    new_node1.id = 1;
    new_node1.addFlag(AccessibilityBridge.Flag.SCOPES_ROUTE);
    new_node1.addFlag(AccessibilityBridge.Flag.NAMES_ROUTE);
    new_node1.label = "new_node1";
    new_root.children.add(new_node1);
    TestSemanticsNode new_node2 = new TestSemanticsNode();
    new_node2.id = 2;
    new_node2.addFlag(AccessibilityBridge.Flag.SCOPES_ROUTE);
    new_node2.addFlag(AccessibilityBridge.Flag.NAMES_ROUTE);
    new_node2.label = "new_node2";
    new_node1.children.add(new_node2);
    testSemanticsUpdate = new_root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    verify(mockRootView, times(1)).setAccessibilityPaneTitle(eq("new_node2"));
  }

  @Test
  public void itSetsTraversalAfter() {
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(mockRootView, mockManager, mockViewEmbedder);
    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode node1 = new TestSemanticsNode();
    node1.id = 1;
    node1.label = "node1";
    root.children.add(node1);
    TestSemanticsNode node2 = new TestSemanticsNode();
    node2.id = 2;
    node2.label = "node2";
    root.children.add(node2);
    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    AccessibilityBridge spyAccessibilityBridge = spy(accessibilityBridge);
    AccessibilityNodeInfo mockNodeInfo2 = mock(AccessibilityNodeInfo.class);

    when(spyAccessibilityBridge.obtainAccessibilityNodeInfo(mockRootView, 2))
        .thenReturn(mockNodeInfo2);
    spyAccessibilityBridge.createAccessibilityNodeInfo(2);
    verify(mockNodeInfo2, times(1)).setTraversalAfter(eq(mockRootView), eq(1));
  }

  @Config(sdk = API_LEVELS.API_24)
  @TargetApi(API_LEVELS.API_24)
  @Test
  public void itSetsRootViewNotImportantForAccessibility() {
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(mockRootView, mockManager, mockViewEmbedder);
    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    AccessibilityBridge spyAccessibilityBridge = spy(accessibilityBridge);
    AccessibilityNodeInfo mockNodeInfo = mock(AccessibilityNodeInfo.class);

    when(spyAccessibilityBridge.obtainAccessibilityNodeInfo(mockRootView)).thenReturn(mockNodeInfo);
    spyAccessibilityBridge.createAccessibilityNodeInfo(View.NO_ID);
    verify(mockNodeInfo, times(1)).setImportantForAccessibility(eq(false));
  }

  @Config(sdk = API_LEVELS.API_24)
  @TargetApi(API_LEVELS.API_24)
  @Test
  public void itSetsNodeImportantForAccessibilityIfItHasContent() {
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(mockRootView, mockManager, mockViewEmbedder);
    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    root.label = "some label";
    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    AccessibilityBridge spyAccessibilityBridge = spy(accessibilityBridge);
    AccessibilityNodeInfo mockNodeInfo = mock(AccessibilityNodeInfo.class);

    when(spyAccessibilityBridge.obtainAccessibilityNodeInfo(mockRootView, 0))
        .thenReturn(mockNodeInfo);
    spyAccessibilityBridge.createAccessibilityNodeInfo(0);
    verify(mockNodeInfo, times(1)).setImportantForAccessibility(eq(true));
  }

  @Config(sdk = API_LEVELS.API_24)
  @TargetApi(API_LEVELS.API_24)
  @Test
  public void itSetsNodeImportantForAccessibilityIfItHasActions() {
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(mockRootView, mockManager, mockViewEmbedder);
    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    root.addAction(Action.TAP);
    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    AccessibilityBridge spyAccessibilityBridge = spy(accessibilityBridge);
    AccessibilityNodeInfo mockNodeInfo = mock(AccessibilityNodeInfo.class);

    when(spyAccessibilityBridge.obtainAccessibilityNodeInfo(mockRootView, 0))
        .thenReturn(mockNodeInfo);
    spyAccessibilityBridge.createAccessibilityNodeInfo(0);
    verify(mockNodeInfo, times(1)).setImportantForAccessibility(eq(true));
  }

  @Config(sdk = API_LEVELS.API_24)
  @TargetApi(API_LEVELS.API_24)
  @Test
  public void itSetsNodeUnImportantForAccessibilityIfItIsEmpty() {
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(mockRootView, mockManager, mockViewEmbedder);
    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode node = new TestSemanticsNode();
    node.id = 1;
    root.children.add(node);
    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    AccessibilityBridge spyAccessibilityBridge = spy(accessibilityBridge);
    AccessibilityNodeInfo mockNodeInfo = mock(AccessibilityNodeInfo.class);

    when(spyAccessibilityBridge.obtainAccessibilityNodeInfo(mockRootView, 0))
        .thenReturn(mockNodeInfo);
    spyAccessibilityBridge.createAccessibilityNodeInfo(0);
    verify(mockNodeInfo, times(1)).setImportantForAccessibility(eq(false));

    AccessibilityNodeInfo mockNodeInfo1 = mock(AccessibilityNodeInfo.class);

    when(spyAccessibilityBridge.obtainAccessibilityNodeInfo(mockRootView, 1))
        .thenReturn(mockNodeInfo1);
    spyAccessibilityBridge.createAccessibilityNodeInfo(1);
    verify(mockNodeInfo1, times(1)).setImportantForAccessibility(eq(false));
  }

  @Test
  public void itIgnoresUnfocusableNodeDuringHitTest() {
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(mockRootView, mockManager, mockViewEmbedder);
    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);
    when(mockManager.isTouchExplorationEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    root.left = 0;
    root.top = 0;
    root.bottom = 20;
    root.right = 20;
    TestSemanticsNode ignored = new TestSemanticsNode();
    ignored.id = 1;
    ignored.addFlag(AccessibilityBridge.Flag.SCOPES_ROUTE);
    ignored.left = 0;
    ignored.top = 0;
    ignored.bottom = 20;
    ignored.right = 20;
    root.children.add(ignored);
    TestSemanticsNode child = new TestSemanticsNode();
    child.id = 2;
    child.label = "label";
    child.left = 0;
    child.top = 0;
    child.bottom = 20;
    child.right = 20;
    root.children.add(child);
    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    verify(mockRootView, times(1)).setAccessibilityPaneTitle(eq(" "));

    // Synthesize an accessibility hit test event.
    MotionEvent mockEvent = mock(MotionEvent.class);
    when(mockEvent.getX()).thenReturn(10.0f);
    when(mockEvent.getY()).thenReturn(10.0f);
    when(mockEvent.getAction()).thenReturn(MotionEvent.ACTION_HOVER_ENTER);
    boolean hit = accessibilityBridge.onAccessibilityHoverEvent(mockEvent);

    assertTrue(hit);

    ArgumentCaptor<AccessibilityEvent> eventCaptor =
        ArgumentCaptor.forClass(AccessibilityEvent.class);
    verify(mockParent, times(2))
        .requestSendAccessibilityEvent(eq(mockRootView), eventCaptor.capture());
    AccessibilityEvent event = eventCaptor.getAllValues().get(1);
    assertEquals(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER, event.getEventType());
    assertEquals(2, accessibilityBridge.getHoveredObjectId());
  }

  @Test
  public void itFindsPlatformViewsDuringHoverByDefault() {
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(mockRootView, mockManager, mockViewEmbedder);
    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);
    when(mockManager.isTouchExplorationEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    root.left = 0;
    root.top = 0;
    root.bottom = 20;
    root.right = 20;
    TestSemanticsNode platformView = new TestSemanticsNode();
    platformView.id = 1;
    platformView.platformViewId = 1;
    platformView.left = 0;
    platformView.top = 0;
    platformView.bottom = 20;
    platformView.right = 20;
    root.addChild(platformView);
    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    // Synthesize an accessibility hit test event.
    MotionEvent mockEvent = mock(MotionEvent.class);
    when(mockEvent.getX()).thenReturn(10.0f);
    when(mockEvent.getY()).thenReturn(10.0f);
    when(mockEvent.getAction()).thenReturn(MotionEvent.ACTION_HOVER_ENTER);

    final boolean handled = accessibilityBridge.onAccessibilityHoverEvent(mockEvent);

    assertTrue(handled);
  }

  @Test
  public void itIgnoresPlatformViewsDuringHoverIfRequested() {
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(mockRootView, mockManager, mockViewEmbedder);
    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);
    when(mockManager.isTouchExplorationEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    root.left = 0;
    root.top = 0;
    root.bottom = 20;
    root.right = 20;
    TestSemanticsNode platformView = new TestSemanticsNode();
    platformView.id = 1;
    platformView.platformViewId = 1;
    platformView.left = 0;
    platformView.top = 0;
    platformView.bottom = 20;
    platformView.right = 20;
    root.addChild(platformView);
    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    // Synthesize an accessibility hit test event.
    MotionEvent mockEvent = mock(MotionEvent.class);
    when(mockEvent.getX()).thenReturn(10.0f);
    when(mockEvent.getY()).thenReturn(10.0f);
    when(mockEvent.getAction()).thenReturn(MotionEvent.ACTION_HOVER_ENTER);

    final boolean handled = accessibilityBridge.onAccessibilityHoverEvent(mockEvent, true);

    assertFalse(handled);
  }

  @Test
  public void itAnnouncesRouteNameWhenRemoveARoute() {
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(mockRootView, mockManager, mockViewEmbedder);
    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode node1 = new TestSemanticsNode();
    node1.id = 1;
    node1.addFlag(AccessibilityBridge.Flag.SCOPES_ROUTE);
    node1.addFlag(AccessibilityBridge.Flag.NAMES_ROUTE);
    node1.label = "node1";
    root.children.add(node1);
    TestSemanticsNode node2 = new TestSemanticsNode();
    node2.id = 2;
    node2.addFlag(AccessibilityBridge.Flag.SCOPES_ROUTE);
    node2.addFlag(AccessibilityBridge.Flag.NAMES_ROUTE);
    node2.label = "node2";
    node1.children.add(node2);
    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    verify(mockRootView, times(1)).setAccessibilityPaneTitle(eq("node2"));

    TestSemanticsNode new_root = new TestSemanticsNode();
    new_root.id = 0;
    TestSemanticsNode new_node1 = new TestSemanticsNode();
    new_node1.id = 1;
    new_node1.label = "new_node1";
    new_root.children.add(new_node1);
    TestSemanticsNode new_node2 = new TestSemanticsNode();
    new_node2.id = 2;
    new_node2.addFlag(AccessibilityBridge.Flag.SCOPES_ROUTE);
    new_node2.addFlag(AccessibilityBridge.Flag.NAMES_ROUTE);
    new_node2.label = "new_node2";
    new_node1.children.add(new_node2);
    testSemanticsUpdate = new_root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    verify(mockRootView, times(1)).setAccessibilityPaneTitle(eq("new_node2"));
  }

  @Test
  @Config(minSdk = API_LEVELS.FLUTTER_MIN)
  public void itCanPerformSetText() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);

    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode node1 = new TestSemanticsNode();
    node1.id = 1;
    node1.addFlag(AccessibilityBridge.Flag.IS_TEXT_FIELD);
    root.children.add(node1);
    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    Bundle bundle = new Bundle();
    String expectedText = "some string";
    bundle.putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, expectedText);
    accessibilityBridge.performAction(1, AccessibilityNodeInfo.ACTION_SET_TEXT, bundle);
    verify(mockChannel)
        .dispatchSemanticsAction(1, AccessibilityBridge.Action.SET_TEXT, expectedText);
  }

  @Test
  @Config(minSdk = API_LEVELS.FLUTTER_MIN)
  public void itCanPredictSetText() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);

    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode node1 = new TestSemanticsNode();
    node1.id = 1;
    node1.addFlag(AccessibilityBridge.Flag.IS_TEXT_FIELD);
    root.children.add(node1);
    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    Bundle bundle = new Bundle();
    String expectedText = "some string";
    bundle.putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, expectedText);
    accessibilityBridge.performAction(1, AccessibilityNodeInfo.ACTION_SET_TEXT, bundle);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(1);
    assertEquals(expectedText, nodeInfo.getText().toString());
  }

  @Test
  @Config(minSdk = API_LEVELS.FLUTTER_MIN)
  public void itBuildsAttributedString() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);

    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    root.label = "label";
    TestStringAttribute attribute = new TestStringAttributeSpellOut();
    attribute.start = 1;
    attribute.end = 2;
    attribute.type = TestStringAttributeType.SPELLOUT;
    root.labelAttributes =
        new ArrayList<TestStringAttribute>() {
          {
            add(attribute);
          }
        };
    root.value = "value";
    TestStringAttributeLocale localeAttribute = new TestStringAttributeLocale();
    localeAttribute.start = 1;
    localeAttribute.end = 2;
    localeAttribute.type = TestStringAttributeType.LOCALE;
    localeAttribute.locale = "es-MX";
    root.valueAttributes =
        new ArrayList<TestStringAttribute>() {
          {
            add(localeAttribute);
          }
        };

    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    SpannedString actual = (SpannedString) nodeInfo.getContentDescription();
    assertEquals("value, label", actual.toString());
    Object[] objectSpans = actual.getSpans(0, actual.length(), Object.class);
    assertEquals(2, objectSpans.length);
    LocaleSpan localeSpan = (LocaleSpan) objectSpans[0];
    assertEquals("es-MX", localeSpan.getLocale().toLanguageTag());
    assertEquals(1, actual.getSpanStart(localeSpan));
    assertEquals(2, actual.getSpanEnd(localeSpan));
    TtsSpan spellOutSpan = (TtsSpan) objectSpans[1];
    assertEquals(TtsSpan.TYPE_VERBATIM, spellOutSpan.getType());
    assertEquals(8, actual.getSpanStart(spellOutSpan));
    assertEquals(9, actual.getSpanEnd(spellOutSpan));
  }

  @Test
  @Config(minSdk = API_LEVELS.FLUTTER_MIN)
  public void itBuildsAttributedStringWithLocale() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);

    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    root.label = "label";
    root.locale = "es-MX";

    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    SpannableString actual = (SpannableString) nodeInfo.getContentDescription();
    assertEquals("label", actual.toString());
    Object[] objectSpans = actual.getSpans(0, actual.length(), Object.class);
    assertEquals(1, objectSpans.length);
    LocaleSpan localeSpan = (LocaleSpan) objectSpans[0];
    assertEquals("es-MX", localeSpan.getLocale().toLanguageTag());
    assertEquals(0, actual.getSpanStart(localeSpan));
    assertEquals(actual.getSpanEnd(localeSpan), actual.length());
  }

  @Test
  @Config(minSdk = API_LEVELS.FLUTTER_MIN)
  public void itSetsDefaultLocale() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);

    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    root.label = "label";

    accessibilityBridge.setLocale("es-MX");
    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    SpannableString actual = (SpannableString) nodeInfo.getContentDescription();
    assertEquals("label", actual.toString());
    Object[] objectSpans = actual.getSpans(0, actual.length(), Object.class);
    assertEquals(1, objectSpans.length);
    LocaleSpan localeSpan = (LocaleSpan) objectSpans[0];
    assertEquals("es-MX", localeSpan.getLocale().toLanguageTag());
    assertEquals(0, actual.getSpanStart(localeSpan));
    assertEquals(actual.getSpanEnd(localeSpan), actual.length());
  }

  @Test
  @Config(minSdk = API_LEVELS.FLUTTER_MIN)
  public void itPrioritizesSectionLocale() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);

    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    root.label = "label";
    // Sets both section locale and main locale.
    root.locale = "fr-FR";
    accessibilityBridge.setLocale("es-MX");

    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    SpannableString actual = (SpannableString) nodeInfo.getContentDescription();
    assertEquals("label", actual.toString());
    Object[] objectSpans = actual.getSpans(0, actual.length(), Object.class);
    assertEquals(1, objectSpans.length);
    LocaleSpan localeSpan = (LocaleSpan) objectSpans[0];
    // Prioritizes section locale over main locale.
    assertEquals("fr-FR", localeSpan.getLocale().toLanguageTag());
    assertEquals(0, actual.getSpanStart(localeSpan));
    assertEquals(actual.getSpanEnd(localeSpan), actual.length());
  }

  @Test
  @Config(minSdk = API_LEVELS.FLUTTER_MIN)
  public void itSetsTextCorrectly() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);

    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    root.value = "value";
    TestStringAttribute attribute = new TestStringAttributeSpellOut();
    attribute.start = 1;
    attribute.end = 2;
    attribute.type = TestStringAttributeType.SPELLOUT;
    root.valueAttributes = new ArrayList<>();
    root.valueAttributes.add(attribute);

    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    SpannableString actual = (SpannableString) nodeInfo.getContentDescription();
    assertEquals("value", actual.toString());
    Object[] objectSpans = actual.getSpans(0, actual.length(), Object.class);
    assertEquals(1, objectSpans.length);
    TtsSpan spellOutSpan = (TtsSpan) objectSpans[0];
    assertEquals(TtsSpan.TYPE_VERBATIM, spellOutSpan.getType());
    assertEquals(1, actual.getSpanStart(spellOutSpan));
    assertEquals(2, actual.getSpanEnd(spellOutSpan));

    // Perform a set text action.
    Bundle bundle = new Bundle();
    String expectedText = "a";
    bundle.putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, expectedText);
    accessibilityBridge.performAction(0, AccessibilityNodeInfo.ACTION_SET_TEXT, bundle);

    // The action should remove the string attributes.
    nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    actual = (SpannableString) nodeInfo.getContentDescription();
    assertEquals(expectedText, actual.toString());
    objectSpans = actual.getSpans(0, actual.length(), Object.class);
    assertEquals(0, objectSpans.length);
  }

  @Config(sdk = API_LEVELS.API_28)
  @TargetApi(API_LEVELS.API_28)
  @Test
  public void itSetsTooltipCorrectly() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);

    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);
    // Create a node with tooltip.
    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    root.tooltip = "tooltip";

    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    // Test the generated AccessibilityNodeInfo for the node we created
    // and verify it has correct tooltip text.
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    CharSequence actualTooltipText = nodeInfo.getTooltipText();
    CharSequence actualContentDescription = nodeInfo.getContentDescription();
    assertEquals(actualTooltipText.toString(), root.tooltip);
    assertEquals(actualContentDescription.toString(), root.tooltip);
  }

  @Config(minSdk = API_LEVELS.API_25)
  @Test
  public void itSetsTooltipCorrectlyWithContentDescription() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);

    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);
    // Create a node with tooltip.
    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    root.tooltip = "tooltip";
    root.label = "desc";

    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    // Test the generated AccessibilityNodeInfo for the node we created
    // and verify it has correct tooltip text.
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    CharSequence actualContentDescription = nodeInfo.getContentDescription();

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      CharSequence actualTooltipText = nodeInfo.getTooltipText();
      assertEquals(actualTooltipText.toString(), root.tooltip);
      assertEquals(actualContentDescription.toString(), root.label);
    } else {
      assertEquals(actualContentDescription.toString(), root.label + "\n" + root.tooltip);
    }
  }

  @TargetApi(API_LEVELS.API_28)
  @Test
  public void itSetsIdentifierCorrectly() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);

    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);
    // Create a node with identifier.
    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    root.identifier = "identifier";

    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    // Test the generated AccessibilityNodeInfo for the node we created and
    // verify it has correct identifier (i.e. resource-id per Android
    // terminology).
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    CharSequence actual = nodeInfo.getViewIdResourceName();
    assertEquals(actual.toString(), root.identifier);
  }

  @Test
  @Config(minSdk = API_LEVELS.FLUTTER_MIN)
  public void itCanCreateAccessibilityNodeInfoWithSetText() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);

    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode node1 = new TestSemanticsNode();
    node1.id = 1;
    node1.addFlag(AccessibilityBridge.Flag.IS_TEXT_FIELD);
    node1.addAction(AccessibilityBridge.Action.SET_TEXT);
    root.children.add(node1);
    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(1);
    List<AccessibilityNodeInfo.AccessibilityAction> actions = nodeInfo.getActionList();
    assertTrue(actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT));
  }

  @Test
  public void itCanPredictSetSelection() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);

    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode node1 = new TestSemanticsNode();
    node1.id = 1;
    node1.value = "some text";
    node1.textSelectionBase = -1;
    node1.textSelectionExtent = -1;
    node1.addFlag(AccessibilityBridge.Flag.IS_TEXT_FIELD);
    root.children.add(node1);
    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    Bundle bundle = new Bundle();
    int expectedStart = 1;
    int expectedEnd = 3;
    bundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, expectedStart);
    bundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, expectedEnd);
    accessibilityBridge.performAction(1, AccessibilityNodeInfo.ACTION_SET_SELECTION, bundle);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(1);
    assertEquals(expectedStart, nodeInfo.getTextSelectionStart());
    assertEquals(expectedEnd, nodeInfo.getTextSelectionEnd());
  }

  @Test
  public void itPerformsClearAccessibilityFocusCorrectly() {
    BasicMessageChannel mockChannel = mock(BasicMessageChannel.class);
    AccessibilityChannel accessibilityChannel =
        new AccessibilityChannel(mockChannel, mock(FlutterJNI.class));
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ accessibilityChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);

    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    root.label = "root";
    TestSemanticsNode node1 = new TestSemanticsNode();
    node1.id = 1;
    node1.value = "some text";
    root.children.add(node1);

    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    accessibilityBridge.performAction(0, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertTrue(nodeInfo.isAccessibilityFocused());

    HashMap<String, Object> message = new HashMap<>();
    message.put("type", "didGainFocus");
    message.put("nodeId", 0);
    verify(mockChannel).send(message);
    // Clear focus on non-focused node shouldn't do anything
    accessibilityBridge.performAction(
        1, AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null);
    nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertTrue(nodeInfo.isAccessibilityFocused());

    // Now, clear the focus for real.
    accessibilityBridge.performAction(
        0, AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null);
    nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertFalse(nodeInfo.isAccessibilityFocused());
  }

  @Test
  public void itSetsFocusabilityBasedOnFlagsCorrectly() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);

    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    root.addFlag(Flag.HAS_IMPLICIT_SCROLLING);
    TestSemanticsNode node1 = new TestSemanticsNode();
    node1.id = 1;
    node1.addFlag(Flag.IS_READ_ONLY);
    root.children.add(node1);
    TestSemanticsNode node2 = new TestSemanticsNode();
    node2.id = 2;
    node2.addFlag(Flag.HAS_CHECKED_STATE);
    root.children.add(node2);
    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    // Only node 2 is focusable because it has a flag that is not in
    // AccessibilityBridge.TRIVIAL_FLAGS.
    AccessibilityNodeInfo rootInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertFalse(rootInfo.isFocusable());
    AccessibilityNodeInfo node1Info = accessibilityBridge.createAccessibilityNodeInfo(1);
    assertFalse(node1Info.isFocusable());
    AccessibilityNodeInfo node2Info = accessibilityBridge.createAccessibilityNodeInfo(2);
    assertTrue(node2Info.isFocusable());
  }

  @Config(sdk = API_LEVELS.API_31)
  @TargetApi(API_LEVELS.API_31)
  @Test
  public void itSetsBoldTextFlagCorrectly() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    Resources resource = mock(Resources.class);
    Configuration config = new Configuration();
    config.fontWeightAdjustment = 300;

    when(mockRootView.getContext()).thenReturn(context);
    when(mockRootView.getResources()).thenReturn(resource);
    when(resource.getConfiguration()).thenReturn(config);

    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);

    verify(mockChannel, atLeastOnce())
        .setAccessibilityFeatures(
            ACCESSIBILITY_FEATURE_BOLD_TEXT | ACCESSIBILITY_FEATURE_NO_ANNOUNCE);
    reset(mockChannel);

    // Now verify that clearing the BOLD_TEXT flag doesn't touch any of the other
    // flags.
    // Ensure the DISABLE_ANIMATION flag will be set
    Settings.Global.putFloat(null, "transition_animation_scale", 0.0f);
    // Ensure the BOLD_TEXT flag will be cleared
    config.fontWeightAdjustment = 0;

    accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);

    // setAccessibilityFeatures() will be called multiple times from
    // AccessibilityBridge's
    // constructor, verify that the latest argument is correct
    ArgumentCaptor<Integer> captor = ArgumentCaptor.forClass(Integer.class);
    verify(mockChannel, atLeastOnce()).setAccessibilityFeatures(captor.capture());
    assertEquals(
        ACCESSIBILITY_FEATURE_DISABLE_ANIMATIONS | ACCESSIBILITY_FEATURE_NO_ANNOUNCE,
        captor.getValue().intValue());

    // Set back to default
    Settings.Global.putFloat(null, "transition_animation_scale", 1.0f);
  }

  @Test
  public void itSetsInvertColorsFlagBasedOnSecureSetting() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    ContentResolver realContentResolver = RuntimeEnvironment.getApplication().getContentResolver();
    ContentResolver spyContentResolver = spy(realContentResolver);

    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ null,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ null,
            /* contentResolver= */ spyContentResolver,
            /* accessibilityViewEmbedder= */ null,
            /* platformViewsAccessibilityDelegate= */ null);

    ArgumentCaptor<ContentObserver> observerCaptor = ArgumentCaptor.forClass(ContentObserver.class);
    verify(spyContentResolver)
        .registerContentObserver(
            eq(Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED)),
            eq(false),
            observerCaptor.capture());
    ContentObserver observer = observerCaptor.getValue();

    reset(mockChannel);

    Settings.Secure.putInt(
        spyContentResolver, Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, 1);
    observer.onChange(false);

    ArgumentCaptor<Integer> featuresCaptor = ArgumentCaptor.forClass(Integer.class);
    verify(mockChannel).setAccessibilityFeatures(featuresCaptor.capture());
    int expectedFlags = ACCESSIBILITY_FEATURE_INVERT_COLORS | ACCESSIBILITY_FEATURE_NO_ANNOUNCE;
    assertEquals(expectedFlags, featuresCaptor.getValue().intValue());

    reset(mockChannel);

    Settings.Secure.putInt(
        spyContentResolver, Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, 0);
    observer.onChange(false);

    verify(mockChannel).setAccessibilityFeatures(featuresCaptor.capture());
    assertEquals(ACCESSIBILITY_FEATURE_NO_ANNOUNCE, featuresCaptor.getValue().intValue());
  }

  @Config(sdk = API_LEVELS.API_34)
  @TargetApi(API_LEVELS.API_34)
  @Test
  public void itSetsHighContrastFlagBasedOnUiModeManager() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    UiModeManager mockUiModeManager = mock(UiModeManager.class);

    when(mockRootView.getContext()).thenReturn(context);
    when(context.getSystemService(Context.UI_MODE_SERVICE)).thenReturn(mockUiModeManager);
    when(context.getMainExecutor())
        .thenReturn(RuntimeEnvironment.getApplication().getMainExecutor());

    when(mockUiModeManager.getContrast()).thenReturn(1.0f);

    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ null,
            /* platformViewsAccessibilityDelegate= */ null);

    ArgumentCaptor<Integer> featuresCaptor = ArgumentCaptor.forClass(Integer.class);
    verify(mockChannel, atLeastOnce()).setAccessibilityFeatures(featuresCaptor.capture());

    int expectedFlags = ACCESSIBILITY_FEATURE_HIGH_CONTRAST | ACCESSIBILITY_FEATURE_NO_ANNOUNCE;
    assertEquals(expectedFlags, featuresCaptor.getValue().intValue());
  }

  @Config(sdk = API_LEVELS.API_34)
  @TargetApi(API_LEVELS.API_34)
  @Test
  public void itUpdatesHighContrastFlagOnChangeListener() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    UiModeManager mockUiModeManager = mock(UiModeManager.class);

    when(mockRootView.getContext()).thenReturn(context);
    when(context.getSystemService(Context.UI_MODE_SERVICE)).thenReturn(mockUiModeManager);
    when(context.getMainExecutor())
        .thenReturn(RuntimeEnvironment.getApplication().getMainExecutor());

    when(mockUiModeManager.getContrast()).thenReturn(0.0f);

    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ null,
            /* platformViewsAccessibilityDelegate= */ null);

    ArgumentCaptor<UiModeManager.ContrastChangeListener> listenerCaptor =
        ArgumentCaptor.forClass(UiModeManager.ContrastChangeListener.class);
    verify(mockUiModeManager).addContrastChangeListener(any(), listenerCaptor.capture());
    UiModeManager.ContrastChangeListener listener = listenerCaptor.getValue();

    reset(mockChannel);

    when(mockUiModeManager.getContrast()).thenReturn(0.5f);
    listener.onContrastChanged(0.5f);

    ArgumentCaptor<Integer> featuresCaptor = ArgumentCaptor.forClass(Integer.class);
    verify(mockChannel).setAccessibilityFeatures(featuresCaptor.capture());
    int expectedFlags = ACCESSIBILITY_FEATURE_HIGH_CONTRAST | ACCESSIBILITY_FEATURE_NO_ANNOUNCE;
    assertEquals(expectedFlags, featuresCaptor.getValue().intValue());

    reset(mockChannel);

    when(mockUiModeManager.getContrast()).thenReturn(0.0f);
    listener.onContrastChanged(0.0f);

    verify(mockChannel).setAccessibilityFeatures(featuresCaptor.capture());
    assertEquals(ACCESSIBILITY_FEATURE_NO_ANNOUNCE, featuresCaptor.getValue().intValue());
  }

  @Config(sdk = API_LEVELS.API_34)
  @TargetApi(API_LEVELS.API_34)
  @Test
  public void itUnregistersHighContrastObserverOnRelease() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    UiModeManager mockUiModeManager = mock(UiModeManager.class);

    when(mockRootView.getContext()).thenReturn(context);
    when(context.getSystemService(Context.UI_MODE_SERVICE)).thenReturn(mockUiModeManager);
    when(context.getMainExecutor())
        .thenReturn(RuntimeEnvironment.getApplication().getMainExecutor());

    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ null,
            /* platformViewsAccessibilityDelegate= */ null);

    ArgumentCaptor<UiModeManager.ContrastChangeListener> listenerCaptor =
        ArgumentCaptor.forClass(UiModeManager.ContrastChangeListener.class);
    verify(mockUiModeManager).addContrastChangeListener(any(), listenerCaptor.capture());
    UiModeManager.ContrastChangeListener listener = listenerCaptor.getValue();

    accessibilityBridge.release();

    verify(mockUiModeManager).removeContrastChangeListener(listener);
  }

  @Config(sdk = API_LEVELS.API_33)
  @TargetApi(API_LEVELS.API_33)
  @Test
  public void itDoesNotSetHighContrastFlagOnOldApi() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);

    when(mockRootView.getContext()).thenReturn(context);
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ null,
            /* platformViewsAccessibilityDelegate= */ null);

    ArgumentCaptor<Integer> featuresCaptor = ArgumentCaptor.forClass(Integer.class);
    verify(mockChannel, atLeastOnce()).setAccessibilityFeatures(featuresCaptor.capture());
    assertEquals(ACCESSIBILITY_FEATURE_NO_ANNOUNCE, featuresCaptor.getValue().intValue());
    verify(context, never()).getSystemService(Context.UI_MODE_SERVICE);
  }

  @Test
  public void itSetsFocusedNodeBeforeSendingEvent() {
    BasicMessageChannel mockChannel = mock(BasicMessageChannel.class);
    AccessibilityChannel accessibilityChannel =
        new AccessibilityChannel(mockChannel, mock(FlutterJNI.class));

    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ accessibilityChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);

    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    root.label = "root";

    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    class Verifier {
      public Verifier(AccessibilityBridge accessibilityBridge) {
        this.accessibilityBridge = accessibilityBridge;
      }

      public final AccessibilityBridge accessibilityBridge;
      public boolean verified = false;

      public boolean verify(InvocationOnMock invocation) {
        AccessibilityEvent event = (AccessibilityEvent) invocation.getArguments()[1];
        assertEquals(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, event.getEventType());
        // The accessibility focus must be set before sending out
        // the TYPE_VIEW_ACCESSIBILITY_FOCUSED event.
        AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
        assertTrue(nodeInfo.isAccessibilityFocused());
        verified = true;
        return true;
      }
    }
    Verifier verifier = new Verifier(accessibilityBridge);
    when(mockParent.requestSendAccessibilityEvent(eq(mockRootView), any(AccessibilityEvent.class)))
        .thenAnswer(verifier::verify);
    accessibilityBridge.performAction(0, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
    assertTrue(verifier.verified);

    HashMap<String, Object> message = new HashMap<>();
    message.put("type", "didGainFocus");
    message.put("nodeId", 0);
    verify(mockChannel).send(message);
  }

  @Test
  public void itClearsFocusedNodeBeforeSendingEvent() {
    BasicMessageChannel mockChannel = mock(BasicMessageChannel.class);
    AccessibilityChannel accessibilityChannel =
        new AccessibilityChannel(mockChannel, mock(FlutterJNI.class));
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ accessibilityChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);

    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    root.label = "root";

    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    // Set the focus on root.
    accessibilityBridge.performAction(0, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertTrue(nodeInfo.isAccessibilityFocused());
    HashMap<String, Object> message = new HashMap<>();
    message.put("type", "didGainFocus");
    message.put("nodeId", 0);
    verify(mockChannel).send(message);

    class Verifier {
      public Verifier(AccessibilityBridge accessibilityBridge) {
        this.accessibilityBridge = accessibilityBridge;
      }

      public final AccessibilityBridge accessibilityBridge;
      public boolean verified = false;

      public boolean verify(InvocationOnMock invocation) {
        AccessibilityEvent event = (AccessibilityEvent) invocation.getArguments()[1];
        assertEquals(
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED, event.getEventType());
        // The accessibility focus must be cleared before sending out
        // the TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED event.
        AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
        assertFalse(nodeInfo.isAccessibilityFocused());
        verified = true;
        return true;
      }
    }
    Verifier verifier = new Verifier(accessibilityBridge);
    when(mockParent.requestSendAccessibilityEvent(eq(mockRootView), any(AccessibilityEvent.class)))
        .thenAnswer(verifier::verify);
    accessibilityBridge.performAction(
        0, AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null);
    assertTrue(verifier.verified);
  }

  @Test
  public void itCanPredictCursorMovementsWithGranularityWord() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);

    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode node1 = new TestSemanticsNode();
    node1.id = 1;
    node1.value = "some text";
    node1.textSelectionBase = 0;
    node1.textSelectionExtent = 0;
    node1.addFlag(AccessibilityBridge.Flag.IS_TEXT_FIELD);
    root.children.add(node1);
    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    Bundle bundle = new Bundle();
    bundle.putInt(
        AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD);
    bundle.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false);
    accessibilityBridge.performAction(
        1, AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, bundle);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(1);
    // The selection should be at the end of 'text'
    assertEquals(9, nodeInfo.getTextSelectionStart());
    assertEquals(9, nodeInfo.getTextSelectionEnd());

    bundle = new Bundle();
    bundle.putInt(
        AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD);
    bundle.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false);
    accessibilityBridge.performAction(
        1, AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, bundle);
    nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(1);
    // The selection should be go to beginning of 'text'.
    assertEquals(5, nodeInfo.getTextSelectionStart());
    assertEquals(5, nodeInfo.getTextSelectionEnd());
  }

  @Test
  public void itAlsoFireSelectionEventWhenPredictCursorMovements() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);

    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode node1 = new TestSemanticsNode();
    node1.id = 1;
    node1.value = "some text";
    node1.textSelectionBase = 0;
    node1.textSelectionExtent = 0;
    node1.addFlag(AccessibilityBridge.Flag.IS_TEXT_FIELD);
    root.children.add(node1);
    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    Bundle bundle = new Bundle();
    bundle.putInt(
        AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER);
    bundle.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false);
    accessibilityBridge.performAction(
        1, AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, bundle);
    ArgumentCaptor<AccessibilityEvent> eventCaptor =
        ArgumentCaptor.forClass(AccessibilityEvent.class);
    verify(mockParent, times(2))
        .requestSendAccessibilityEvent(eq(mockRootView), eventCaptor.capture());
    AccessibilityEvent event = eventCaptor.getAllValues().get(1);
    assertEquals(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED, event.getEventType());
    assertEquals(event.getText().toString(), "[" + node1.value + "]");
    assertEquals(1, event.getFromIndex());
    assertEquals(1, event.getToIndex());
    assertEquals(event.getItemCount(), node1.value.length());
  }

  @Test
  public void itDoesNotFireSelectionEventWhenPredictCursorMovementsDoesNotChangeSelection() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);

    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode node1 = new TestSemanticsNode();
    node1.id = 1;
    node1.value = "some text";
    node1.textSelectionBase = 0;
    node1.textSelectionExtent = 0;
    node1.addFlag(AccessibilityBridge.Flag.IS_TEXT_FIELD);
    root.children.add(node1);
    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    Bundle bundle = new Bundle();
    bundle.putInt(
        AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER);
    bundle.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false);
    accessibilityBridge.performAction(
        1, AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, bundle);
    ArgumentCaptor<AccessibilityEvent> eventCaptor =
        ArgumentCaptor.forClass(AccessibilityEvent.class);
    verify(mockParent, times(1))
        .requestSendAccessibilityEvent(eq(mockRootView), eventCaptor.capture());
    assertEquals(1, eventCaptor.getAllValues().size());
    AccessibilityEvent event = eventCaptor.getAllValues().get(0);
    assertNotEquals(AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED, event.getEventType());
  }

  @Ignore(
      "Fails on JDK 19+ due to https://github.com/flutter/flutter/issues/175623. Not an actual bug on device; test-specific only.")
  @Test
  public void itCanPredictCursorMovementsWithGranularityWordUnicode() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);

    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode node1 = new TestSemanticsNode();
    node1.id = 1;
    node1.value = "你 好 嗎";
    node1.textSelectionBase = 0;
    node1.textSelectionExtent = 0;
    node1.addFlag(AccessibilityBridge.Flag.IS_TEXT_FIELD);
    root.children.add(node1);
    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    Bundle bundle = new Bundle();
    bundle.putInt(
        AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD);
    bundle.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false);
    accessibilityBridge.performAction(
        1, AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, bundle);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(1);
    // The selection should be at the end of '好'
    assertEquals(3, nodeInfo.getTextSelectionStart());
    assertEquals(3, nodeInfo.getTextSelectionEnd());

    bundle = new Bundle();
    bundle.putInt(
        AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD);
    bundle.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false);
    accessibilityBridge.performAction(
        1, AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, bundle);
    nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(1);
    // The selection should be go to beginning of '好'.
    assertEquals(2, nodeInfo.getTextSelectionStart());
    assertEquals(2, nodeInfo.getTextSelectionEnd());
  }

  @Test
  public void itCanPredictCursorMovementsWithGranularityLine() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);

    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode node1 = new TestSemanticsNode();
    node1.id = 1;
    node1.value = "How are you\nI am fine\nThank you";
    // Selection is at the second line.
    node1.textSelectionBase = 14;
    node1.textSelectionExtent = 14;
    node1.addFlag(AccessibilityBridge.Flag.IS_TEXT_FIELD);
    root.children.add(node1);
    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    Bundle bundle = new Bundle();
    bundle.putInt(
        AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE);
    bundle.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false);
    accessibilityBridge.performAction(
        1, AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, bundle);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(1);
    // The selection should be at the beginning of the third line.
    assertEquals(21, nodeInfo.getTextSelectionStart());
    assertEquals(21, nodeInfo.getTextSelectionEnd());

    bundle = new Bundle();
    bundle.putInt(
        AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE);
    bundle.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false);
    accessibilityBridge.performAction(
        1, AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, bundle);
    nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(1);
    // The selection should be at the beginning of the second line.
    assertEquals(11, nodeInfo.getTextSelectionStart());
    assertEquals(11, nodeInfo.getTextSelectionEnd());
  }

  @Test
  public void itCanPredictCursorMovementsWithGranularityCharacter() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ mockRootView,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ mockManager,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ mockViewEmbedder,
            /* platformViewsAccessibilityDelegate= */ null);

    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode node1 = new TestSemanticsNode();
    node1.id = 1;
    node1.value = "some text";
    node1.textSelectionBase = 0;
    node1.textSelectionExtent = 0;
    node1.addFlag(AccessibilityBridge.Flag.IS_TEXT_FIELD);
    root.children.add(node1);
    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    Bundle bundle = new Bundle();
    bundle.putInt(
        AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER);
    bundle.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false);
    accessibilityBridge.performAction(
        1, AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, bundle);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(1);
    assertEquals(1, nodeInfo.getTextSelectionStart());
    assertEquals(1, nodeInfo.getTextSelectionEnd());

    bundle = new Bundle();
    bundle.putInt(
        AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
        AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER);
    bundle.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, false);
    accessibilityBridge.performAction(
        1, AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, bundle);
    nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(1);
    assertEquals(0, nodeInfo.getTextSelectionStart());
    assertEquals(0, nodeInfo.getTextSelectionEnd());
  }

  @Test
  public void itAnnouncesWhiteSpaceWhenNoNamesRoute() {
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(mockRootView, mockManager, mockViewEmbedder);
    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    // Sent a11y tree with scopeRoute without namesRoute.
    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode scopeRoute = new TestSemanticsNode();
    scopeRoute.id = 1;
    scopeRoute.addFlag(AccessibilityBridge.Flag.SCOPES_ROUTE);
    root.children.add(scopeRoute);
    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    verify(mockRootView, times(1)).setAccessibilityPaneTitle(eq(" "));
  }

  @Test
  public void itHoverOverOutOfBoundsDoesNotCrash() {
    // SemanticsNode.hitTest() returns null when out of bounds.
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    AccessibilityBridge accessibilityBridge =
        setUpBridge(mockRootView, mockManager, mockViewEmbedder);

    // Sent a11y tree with platform view.
    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode platformView = new TestSemanticsNode();
    platformView.id = 1;
    platformView.platformViewId = 42;
    root.children.add(platformView);
    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    // Pass an out of bounds MotionEvent.
    accessibilityBridge.onAccessibilityHoverEvent(MotionEvent.obtain(1, 1, 1, -10, -10, 0));
  }

  @Test
  public void itDoesNotCrashWhenHitTestingChildWithUninitializedTransform() {
    // Regression test for https://github.com/flutter/flutter/issues/184810.
    // When an OverlayPortal grafts a semantics node as a traversal child of one parent
    // and a hit-test child of another, the engine may create the node via
    // getOrCreateSemanticsNode before its own updateWith() has been called, leaving
    // hitTestTransform null. A subsequent hover event must not crash.
    AccessibilityViewEmbedder mockViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    when(mockManager.isTouchExplorationEnabled()).thenReturn(true);
    AccessibilityBridge accessibilityBridge =
        setUpBridge(mockRootView, mockManager, mockViewEmbedder);

    // Build a root node that references a phantom child (id=1) whose data is not
    // in the update buffer. This simulates the scenario where a parent's updateWith()
    // creates the child via getOrCreateSemanticsNode but the child never receives its
    // own updateWith(), leaving hitTestTransform as null.
    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    root.left = 0;
    root.top = 0;
    root.bottom = 20;
    root.right = 20;
    root.phantomChildIds.add(1);
    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    // Fire a hover event within the root's bounds. Before the fix, this would crash
    // with a NullPointerException in ensureInverseTransform because the phantom child's
    // hitTestTransform was never initialized.
    accessibilityBridge.onAccessibilityHoverEvent(MotionEvent.obtain(1, 1, 1, 10, 10, 0));
  }

  @Test
  public void itProducesPlatformViewNodeForHybridComposition() {
    PlatformViewsAccessibilityDelegate accessibilityDelegate =
        mock(PlatformViewsAccessibilityDelegate.class);

    Context context = ApplicationProvider.getApplicationContext();
    View rootAccessibilityView = new View(context);
    AccessibilityViewEmbedder accessibilityViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            rootAccessibilityView,
            /* accessibilityChannel= */ null,
            /* accessibilityManager= */ null,
            /* contentResolver= */ null,
            accessibilityViewEmbedder,
            accessibilityDelegate);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;

    TestSemanticsNode platformView = new TestSemanticsNode();
    platformView.id = 1;
    platformView.platformViewId = 1;
    root.addChild(platformView);

    View embeddedView = mock(View.class);
    View wrapperView = mock(View.class);
    // Stub getPlatformViewById to confirm the embedded view itself is never mutated.
    when(accessibilityDelegate.getPlatformViewById(1)).thenReturn(embeddedView);
    when(accessibilityDelegate.getPlatformViewWrapperById(1)).thenReturn(wrapperView);
    when(accessibilityDelegate.usesVirtualDisplay(1)).thenReturn(false);

    TestSemanticsUpdate testSemanticsRootUpdate = root.toUpdate();
    testSemanticsRootUpdate.sendUpdateToBridge(accessibilityBridge);

    AccessibilityNodeInfo result = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertNotNull(result);
    assertEquals(1, result.getChildCount());
    verify(wrapperView).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    verify(wrapperView).setAccessibilityDelegate(any(View.AccessibilityDelegate.class));
    verify(embeddedView, never()).setImportantForAccessibility(anyInt());
    assertEquals("android.view.View", result.getClassName());
  }

  @Test
  public void itMakesPlatformViewImportantForAccessibility() {
    PlatformViewsAccessibilityDelegate accessibilityDelegate =
        mock(PlatformViewsAccessibilityDelegate.class);

    Context context = ApplicationProvider.getApplicationContext();
    View rootAccessibilityView = new View(context);
    AccessibilityViewEmbedder accessibilityViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            rootAccessibilityView,
            /* accessibilityChannel= */ null,
            /* accessibilityManager= */ null,
            /* contentResolver= */ null,
            accessibilityViewEmbedder,
            accessibilityDelegate);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;

    TestSemanticsNode platformView = new TestSemanticsNode();
    platformView.id = 1;
    platformView.platformViewId = 1;
    root.addChild(platformView);

    View embeddedView = mock(View.class);
    View wrapperView = mock(View.class);
    when(accessibilityDelegate.getPlatformViewById(1)).thenReturn(embeddedView);
    when(accessibilityDelegate.getPlatformViewWrapperById(1)).thenReturn(wrapperView);
    when(accessibilityDelegate.usesVirtualDisplay(1)).thenReturn(false);

    TestSemanticsUpdate testSemanticsRootUpdate = root.toUpdate();
    testSemanticsRootUpdate.sendUpdateToBridge(accessibilityBridge);

    verify(wrapperView).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    verify(wrapperView).setAccessibilityDelegate(any(View.AccessibilityDelegate.class));
    verify(embeddedView, never()).setImportantForAccessibility(anyInt());
  }

  @Test
  public void itMakesPlatformViewNoImportantForAccessibility() {
    PlatformViewsAccessibilityDelegate accessibilityDelegate =
        mock(PlatformViewsAccessibilityDelegate.class);

    Context context = ApplicationProvider.getApplicationContext();
    View rootAccessibilityView = new View(context);
    AccessibilityViewEmbedder accessibilityViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            rootAccessibilityView,
            /* accessibilityChannel= */ null,
            /* accessibilityManager= */ null,
            /* contentResolver= */ null,
            accessibilityViewEmbedder,
            accessibilityDelegate);

    TestSemanticsNode rootWithPlatformView = new TestSemanticsNode();
    rootWithPlatformView.id = 0;

    TestSemanticsNode platformView = new TestSemanticsNode();
    platformView.id = 1;
    platformView.platformViewId = 1;
    rootWithPlatformView.addChild(platformView);

    View embeddedView = mock(View.class);
    View wrapperView = mock(View.class);
    when(accessibilityDelegate.getPlatformViewById(1)).thenReturn(embeddedView);
    when(accessibilityDelegate.getPlatformViewWrapperById(1)).thenReturn(wrapperView);
    when(accessibilityDelegate.usesVirtualDisplay(1)).thenReturn(false);

    TestSemanticsUpdate testSemanticsRootWithPlatformViewUpdate = rootWithPlatformView.toUpdate();
    testSemanticsRootWithPlatformViewUpdate.sendUpdateToBridge(accessibilityBridge);

    TestSemanticsNode rootWithoutPlatformView = new TestSemanticsNode();
    rootWithoutPlatformView.id = 0;
    TestSemanticsUpdate testSemanticsRootWithoutPlatformViewUpdate =
        rootWithoutPlatformView.toUpdate();
    testSemanticsRootWithoutPlatformViewUpdate.sendUpdateToBridge(accessibilityBridge);

    verify(wrapperView)
        .setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    verify(wrapperView).setAccessibilityDelegate(null);
    verify(embeddedView, never()).setImportantForAccessibility(anyInt());
  }

  @Test
  public void itPreservesEmbeddedPlainContainerAndExposesChildrenThroughWrapper() {
    PlatformViewsAccessibilityDelegate accessibilityDelegate =
        mock(PlatformViewsAccessibilityDelegate.class);
    Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    View rootAccessibilityView = new View(activity);
    AccessibilityViewEmbedder accessibilityViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityBridge accessibilityBridge =
        spy(
            setUpBridge(
                rootAccessibilityView,
                /* accessibilityChannel= */ null,
                /* accessibilityManager= */ null,
                /* contentResolver= */ null,
                accessibilityViewEmbedder,
                accessibilityDelegate));

    // Build a real FlutterMutatorView wrapping FrameLayout (AUTO) > LinearLayout (AUTO) > TextView.
    FlutterMutatorView wrapper = new FlutterMutatorView(activity);
    wrapper.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    FrameLayout embeddedContainer = new FrameLayout(activity);
    LinearLayout innerLayout = new LinearLayout(activity);
    TextView textView = new TextView(activity);
    textView.setText("Native text");
    innerLayout.addView(textView);
    embeddedContainer.addView(innerLayout);
    wrapper.addView(embeddedContainer);
    activity.setContentView(wrapper);

    when(accessibilityDelegate.getPlatformViewById(1)).thenReturn(embeddedContainer);
    when(accessibilityDelegate.getPlatformViewWrapperById(1)).thenReturn(wrapper);
    when(accessibilityDelegate.usesVirtualDisplay(1)).thenReturn(false);

    assertFalse(wrapper.isImportantForAccessibility());
    assertFalse(textView.isImportantForAccessibility());

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode platformView = new TestSemanticsNode();
    platformView.id = 1;
    platformView.platformViewId = 1;
    root.addChild(platformView);
    root.toUpdate().sendUpdateToBridge(accessibilityBridge);

    AccessibilityNodeInfo mockRootInfo = mock(AccessibilityNodeInfo.class);
    when(accessibilityBridge.obtainAccessibilityNodeInfo(rootAccessibilityView, 0))
        .thenReturn(mockRootInfo);
    AccessibilityNodeInfo result = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertSame(mockRootInfo, result);
    verify(mockRootInfo).addChild(wrapper);
    verify(mockRootInfo, never()).addChild(embeddedContainer);

    // Wrapper is important so Android resolves the virtual-to-View child link, while embedded
    // views retain their original importance modes untouched.
    assertTrue(wrapper.isImportantForAccessibility());
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, embeddedContainer.getImportantForAccessibility());
    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, textView.getImportantForAccessibility());
    assertTrue(textView.isImportantForAccessibility());

    // Real wrapper node info builds cleanly through PlatformViewWrapperAccessibilityDelegate and
    // standard Android ViewGroup traversal flattens the AUTO containers to reach TextView.
    AccessibilityNodeInfo realWrapperInfo = wrapper.createAccessibilityNodeInfo();
    assertNotNull(realWrapperInfo);
    assertTrue(realWrapperInfo.isImportantForAccessibility());
    ArrayList<View> accessibleChildren = new ArrayList<>();
    wrapper.addChildrenForAccessibility(accessibleChildren);
    assertEquals(1, accessibleChildren.size());
    assertEquals(textView, accessibleChildren.get(0));

    // Wrapper's AccessibilityDelegate links its parent back to the virtual Flutter SemanticsNode.
    AccessibilityNodeInfo mockWrapperInfo = mock(AccessibilityNodeInfo.class);
    assertNotNull(wrapper.getAccessibilityDelegate());
    wrapper.getAccessibilityDelegate().onInitializeAccessibilityNodeInfo(wrapper, mockWrapperInfo);
    verify(mockWrapperInfo).setParent(rootAccessibilityView, 0);

    // Removing the semantics node hides the wrapper again and clears the delegate.
    TestSemanticsNode rootWithoutPlatformView = new TestSemanticsNode();
    rootWithoutPlatformView.id = 0;
    rootWithoutPlatformView.toUpdate().sendUpdateToBridge(accessibilityBridge);

    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        wrapper.getImportantForAccessibility());
    assertFalse(wrapper.isImportantForAccessibility());
    assertFalse(textView.isImportantForAccessibility());
    assertNull(wrapper.getAccessibilityDelegate());
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, embeddedContainer.getImportantForAccessibility());
  }

  @Test
  public void itPreservesStandaloneTextViewAndRespectsPluginOptOut() {
    PlatformViewsAccessibilityDelegate accessibilityDelegate =
        mock(PlatformViewsAccessibilityDelegate.class);
    Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    View rootAccessibilityView = new View(activity);
    AccessibilityViewEmbedder accessibilityViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            rootAccessibilityView,
            /* accessibilityChannel= */ null,
            /* accessibilityManager= */ null,
            /* contentResolver= */ null,
            accessibilityViewEmbedder,
            accessibilityDelegate);

    LinearLayout host = new LinearLayout(activity);

    // 1. Standalone TextView (constructor sets YES) — must remain YES and be reachable.
    PlatformViewWrapper textWrapper = new PlatformViewWrapper(activity);
    textWrapper.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    TextView standaloneText = new TextView(activity);
    standaloneText.setText("Standalone");
    textWrapper.addView(standaloneText);
    host.addView(textWrapper);

    // 2. Decorative TextView explicitly marked NO by the plugin — must stay NO and be skipped.
    PlatformViewWrapper optOutNoWrapper = new PlatformViewWrapper(activity);
    optOutNoWrapper.setImportantForAccessibility(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    TextView decorativeText = new TextView(activity);
    decorativeText.setText("Decorative");
    decorativeText.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    optOutNoWrapper.addView(decorativeText);
    host.addView(optOutNoWrapper);

    // 3. Container explicitly marked NO_HIDE_DESCENDANTS by the plugin — children stay hidden.
    PlatformViewWrapper optOutHideWrapper = new PlatformViewWrapper(activity);
    optOutHideWrapper.setImportantForAccessibility(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    FrameLayout hiddenContainer = new FrameLayout(activity);
    hiddenContainer.setImportantForAccessibility(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    TextView hiddenChild = new TextView(activity);
    hiddenChild.setText("Hidden");
    hiddenContainer.addView(hiddenChild);
    optOutHideWrapper.addView(hiddenContainer);
    host.addView(optOutHideWrapper);

    activity.setContentView(host);

    when(accessibilityDelegate.getPlatformViewWrapperById(1)).thenReturn(textWrapper);
    when(accessibilityDelegate.getPlatformViewWrapperById(2)).thenReturn(optOutNoWrapper);
    when(accessibilityDelegate.getPlatformViewWrapperById(3)).thenReturn(optOutHideWrapper);
    when(accessibilityDelegate.usesVirtualDisplay(anyInt())).thenReturn(false);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    for (int i = 1; i <= 3; i++) {
      TestSemanticsNode pvNode = new TestSemanticsNode();
      pvNode.id = i;
      pvNode.platformViewId = i;
      root.addChild(pvNode);
    }
    root.toUpdate().sendUpdateToBridge(accessibilityBridge);

    // All three wrappers are enabled with delegates; plugin opt-outs are honored natively on the
    // unmodified embedded views.
    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, textWrapper.getImportantForAccessibility());
    assertNotNull(textWrapper.getAccessibilityDelegate());
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_YES, optOutNoWrapper.getImportantForAccessibility());
    assertNotNull(optOutNoWrapper.getAccessibilityDelegate());
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_YES, optOutHideWrapper.getImportantForAccessibility());
    assertNotNull(optOutHideWrapper.getAccessibilityDelegate());

    ArrayList<View> children1 = new ArrayList<>();
    textWrapper.addChildrenForAccessibility(children1);
    assertEquals(1, children1.size());
    assertEquals(standaloneText, children1.get(0));
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_YES, standaloneText.getImportantForAccessibility());

    ArrayList<View> children2 = new ArrayList<>();
    optOutNoWrapper.addChildrenForAccessibility(children2);
    assertTrue(children2.isEmpty());
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO, decorativeText.getImportantForAccessibility());

    ArrayList<View> children3 = new ArrayList<>();
    optOutHideWrapper.addChildrenForAccessibility(children3);
    assertTrue(children3.isEmpty());
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        hiddenContainer.getImportantForAccessibility());
    assertFalse(hiddenChild.isImportantForAccessibility());
  }

  @Test
  public void itEnablesWrapperWhenPlatformViewAttachesAfterUpdateSemantics() {
    PlatformViewsAccessibilityDelegate accessibilityDelegate =
        mock(PlatformViewsAccessibilityDelegate.class);
    AccessibilityManager accessibilityManager = mock(AccessibilityManager.class);
    when(accessibilityManager.isEnabled()).thenReturn(true);

    View rootAccessibilityView = mock(View.class);
    ViewParent mockParent = mock(ViewParent.class);
    Context context = ApplicationProvider.getApplicationContext();
    when(rootAccessibilityView.getContext()).thenReturn(context);
    when(rootAccessibilityView.getParent()).thenReturn(mockParent);

    AccessibilityViewEmbedder accessibilityViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            rootAccessibilityView,
            /* accessibilityChannel= */ null,
            accessibilityManager,
            /* contentResolver= */ null,
            accessibilityViewEmbedder,
            accessibilityDelegate);

    when(accessibilityDelegate.usesVirtualDisplay(1)).thenReturn(false);
    // Simulate HC/HCPP lazy attachment: wrapper does not exist yet during updateSemantics.
    when(accessibilityDelegate.getPlatformViewWrapperById(1)).thenReturn(null);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode platformView = new TestSemanticsNode();
    platformView.id = 1;
    platformView.platformViewId = 1;
    root.addChild(platformView);
    root.toUpdate().sendUpdateToBridge(accessibilityBridge);

    reset(mockParent);

    // Wrapper is now created and attached by
    // PlatformViewsController(2)#initializePlatformViewIfNeeded.
    FlutterMutatorView wrapper = new FlutterMutatorView(context);
    wrapper.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    when(accessibilityDelegate.getPlatformViewWrapperById(1)).thenReturn(wrapper);

    accessibilityBridge.onPlatformViewAttached(1);

    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, wrapper.getImportantForAccessibility());
    assertNotNull(wrapper.getAccessibilityDelegate());
    ArgumentCaptor<AccessibilityEvent> eventCaptor =
        ArgumentCaptor.forClass(AccessibilityEvent.class);
    verify(mockParent)
        .requestSendAccessibilityEvent(eq(rootAccessibilityView), eventCaptor.capture());
    assertEquals(
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, eventCaptor.getValue().getEventType());
    assertEquals(
        AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE,
        eventCaptor.getValue().getContentChangeTypes());
  }

  @Test
  public void itDetachesWrapperOnHiddenResetAndRelease() {
    PlatformViewsAccessibilityDelegate accessibilityDelegate =
        mock(PlatformViewsAccessibilityDelegate.class);
    Context context = ApplicationProvider.getApplicationContext();
    View rootAccessibilityView = new View(context);
    AccessibilityViewEmbedder accessibilityViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            rootAccessibilityView,
            /* accessibilityChannel= */ null,
            /* accessibilityManager= */ null,
            /* contentResolver= */ null,
            accessibilityViewEmbedder,
            accessibilityDelegate);

    FlutterMutatorView wrapper = new FlutterMutatorView(context);
    wrapper.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    when(accessibilityDelegate.getPlatformViewWrapperById(1)).thenReturn(wrapper);
    when(accessibilityDelegate.usesVirtualDisplay(1)).thenReturn(false);

    // Attach normally.
    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode platformView = new TestSemanticsNode();
    platformView.id = 1;
    platformView.platformViewId = 1;
    root.addChild(platformView);
    root.toUpdate().sendUpdateToBridge(accessibilityBridge);
    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, wrapper.getImportantForAccessibility());
    assertNotNull(wrapper.getAccessibilityDelegate());

    // Mark hidden -> wrapper must be disabled and delegate cleared.
    platformView.addFlag(Flag.IS_HIDDEN);
    root.toUpdate().sendUpdateToBridge(accessibilityBridge);
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        wrapper.getImportantForAccessibility());
    assertNull(wrapper.getAccessibilityDelegate());

    // Unhide -> re-enabled.
    platformView.flags = 0;
    root.toUpdate().sendUpdateToBridge(accessibilityBridge);
    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, wrapper.getImportantForAccessibility());
    assertNotNull(wrapper.getAccessibilityDelegate());

    // reset() -> detaches wrapper.
    accessibilityBridge.reset();
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        wrapper.getImportantForAccessibility());
    assertNull(wrapper.getAccessibilityDelegate());

    // Re-attach, then release() -> detaches wrapper.
    root.toUpdate().sendUpdateToBridge(accessibilityBridge);
    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, wrapper.getImportantForAccessibility());
    accessibilityBridge.release();
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        wrapper.getImportantForAccessibility());
    assertNull(wrapper.getAccessibilityDelegate());
  }

  @Test
  public void itSetsTraversalAfterForPlatformViewWrapperAndFollowingSibling() {
    PlatformViewsAccessibilityDelegate accessibilityDelegate =
        mock(PlatformViewsAccessibilityDelegate.class);
    Context context = ApplicationProvider.getApplicationContext();
    View rootAccessibilityView = new View(context);
    AccessibilityViewEmbedder accessibilityViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityBridge accessibilityBridge =
        spy(
            setUpBridge(
                rootAccessibilityView,
                /* accessibilityChannel= */ null,
                /* accessibilityManager= */ null,
                /* contentResolver= */ null,
                accessibilityViewEmbedder,
                accessibilityDelegate));

    FlutterMutatorView wrapper = new FlutterMutatorView(context);
    when(accessibilityDelegate.getPlatformViewWrapperById(1)).thenReturn(wrapper);
    when(accessibilityDelegate.usesVirtualDisplay(1)).thenReturn(false);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode beforeNode = new TestSemanticsNode();
    beforeNode.id = 1;
    beforeNode.label = "Above";
    TestSemanticsNode platformViewNode = new TestSemanticsNode();
    platformViewNode.id = 2;
    platformViewNode.platformViewId = 1;
    TestSemanticsNode afterNode = new TestSemanticsNode();
    afterNode.id = 3;
    afterNode.label = "Below";
    root.addChild(beforeNode);
    root.addChild(platformViewNode);
    root.addChild(afterNode);
    root.toUpdate().sendUpdateToBridge(accessibilityBridge);

    // Platform view wrapper traverses after virtual node 1 ("Above").
    AccessibilityNodeInfo mockWrapperInfo = mock(AccessibilityNodeInfo.class);
    assertNotNull(wrapper.getAccessibilityDelegate());
    wrapper.getAccessibilityDelegate().onInitializeAccessibilityNodeInfo(wrapper, mockWrapperInfo);
    verify(mockWrapperInfo).setParent(rootAccessibilityView, 0);
    verify(mockWrapperInfo).setTraversalAfter(rootAccessibilityView, 1);

    // Following Flutter sibling (id 3, "Below") traverses after the real wrapper view.
    AccessibilityNodeInfo mockAfterInfo = mock(AccessibilityNodeInfo.class);
    when(accessibilityBridge.obtainAccessibilityNodeInfo(rootAccessibilityView, 3))
        .thenReturn(mockAfterInfo);
    accessibilityBridge.createAccessibilityNodeInfo(3);
    verify(mockAfterInfo).setTraversalAfter(wrapper);
  }

  @Test
  public void itDoesNotDetachPlatformViewIdZeroWhenAddingNewSemanticsNodes() {
    PlatformViewsAccessibilityDelegate accessibilityDelegate =
        mock(PlatformViewsAccessibilityDelegate.class);
    Context context = ApplicationProvider.getApplicationContext();
    View rootAccessibilityView = new View(context);
    AccessibilityViewEmbedder accessibilityViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            rootAccessibilityView,
            /* accessibilityChannel= */ null,
            /* accessibilityManager= */ null,
            /* contentResolver= */ null,
            accessibilityViewEmbedder,
            accessibilityDelegate);

    View wrapper0 = mock(View.class);
    when(accessibilityDelegate.getPlatformViewWrapperById(0)).thenReturn(wrapper0);
    when(accessibilityDelegate.usesVirtualDisplay(0)).thenReturn(false);

    // First update: platform view with id 0 (the first ID allocated by PlatformViewsRegistry).
    TestSemanticsNode root1 = new TestSemanticsNode();
    root1.id = 0;
    TestSemanticsNode pvNode = new TestSemanticsNode();
    pvNode.id = 1;
    pvNode.platformViewId = 0;
    root1.addChild(pvNode);
    root1.toUpdate().sendUpdateToBridge(accessibilityBridge);

    verify(wrapper0, times(1)).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    verify(wrapper0, times(1)).setAccessibilityDelegate(any(View.AccessibilityDelegate.class));

    // Second update: add an unrelated new Flutter SemanticsNode (id 2, platformViewId = -1).
    // Regression test: an uninitialized SemanticsNode.platformViewId (default 0) would falsely
    // detach platform view 0 and wipe live accessibility focus.
    TestSemanticsNode root2 = new TestSemanticsNode();
    root2.id = 0;
    TestSemanticsNode pvNode2 = new TestSemanticsNode();
    pvNode2.id = 1;
    pvNode2.platformViewId = 0;
    TestSemanticsNode unrelatedNode = new TestSemanticsNode();
    unrelatedNode.id = 2;
    unrelatedNode.label = "New widget";
    root2.addChild(pvNode2);
    root2.addChild(unrelatedNode);
    root2.toUpdate().sendUpdateToBridge(accessibilityBridge);

    verify(wrapper0, never())
        .setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    verify(wrapper0, never()).setAccessibilityDelegate(null);
    // Delegate is preserved without reallocation.
    verify(wrapper0, times(1)).setAccessibilityDelegate(any(View.AccessibilityDelegate.class));
  }

  @Test
  public void itKeepsWrapperEnabledWhenSemanticsNodeIdChangesForSamePlatformView() {
    PlatformViewsAccessibilityDelegate accessibilityDelegate =
        mock(PlatformViewsAccessibilityDelegate.class);
    Context context = ApplicationProvider.getApplicationContext();
    View rootAccessibilityView = new View(context);
    AccessibilityViewEmbedder accessibilityViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            rootAccessibilityView,
            /* accessibilityChannel= */ null,
            /* accessibilityManager= */ null,
            /* contentResolver= */ null,
            accessibilityViewEmbedder,
            accessibilityDelegate);

    FlutterMutatorView wrapper = spy(new FlutterMutatorView(context));
    wrapper.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    when(accessibilityDelegate.getPlatformViewWrapperById(7)).thenReturn(wrapper);
    when(accessibilityDelegate.usesVirtualDisplay(7)).thenReturn(false);

    // Update 1: root(0) -> node(1, pv=7).
    TestSemanticsNode root1 = new TestSemanticsNode();
    root1.id = 0;
    TestSemanticsNode oldPvNode = new TestSemanticsNode();
    oldPvNode.id = 1;
    oldPvNode.platformViewId = 7;
    root1.addChild(oldPvNode);
    root1.toUpdate().sendUpdateToBridge(accessibilityBridge);

    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, wrapper.getImportantForAccessibility());
    View.AccessibilityDelegate initialDelegate = wrapper.getAccessibilityDelegate();
    assertNotNull(initialDelegate);

    // Update 2: root(0) -> container(3) -> node(2, pv=7); node(1) is removed in the same batch.
    reset(wrapper);
    TestSemanticsNode root2 = new TestSemanticsNode();
    root2.id = 0;
    TestSemanticsNode newParent = new TestSemanticsNode();
    newParent.id = 3;
    TestSemanticsNode newPvNode = new TestSemanticsNode();
    newPvNode.id = 2;
    newPvNode.platformViewId = 7;
    newParent.addChild(newPvNode);
    root2.addChild(newParent);
    root2.toUpdate().sendUpdateToBridge(accessibilityBridge);

    // Wrapper must remain YES with zero transient NO_HIDE_DESCENDANTS transitions and updated
    // parent linkage.
    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, wrapper.getImportantForAccessibility());
    verify(wrapper, never())
        .setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    assertSame(initialDelegate, wrapper.getAccessibilityDelegate());
    AccessibilityNodeInfo mockInfo = mock(AccessibilityNodeInfo.class);
    wrapper.getAccessibilityDelegate().onInitializeAccessibilityNodeInfo(wrapper, mockInfo);
    verify(mockInfo).setParent(rootAccessibilityView, 3);
  }

  @Test
  public void itKeepsWrapperEnabledWhenPlatformViewIdMovesBetweenExistingNodesInOneUpdate() {
    PlatformViewsAccessibilityDelegate accessibilityDelegate =
        mock(PlatformViewsAccessibilityDelegate.class);
    Context context = ApplicationProvider.getApplicationContext();
    View rootAccessibilityView = new View(context);
    AccessibilityViewEmbedder accessibilityViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            rootAccessibilityView,
            /* accessibilityChannel= */ null,
            /* accessibilityManager= */ null,
            /* contentResolver= */ null,
            accessibilityViewEmbedder,
            accessibilityDelegate);

    FlutterMutatorView wrapper = spy(new FlutterMutatorView(context));
    wrapper.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    when(accessibilityDelegate.getPlatformViewWrapperById(7)).thenReturn(wrapper);
    when(accessibilityDelegate.usesVirtualDisplay(7)).thenReturn(false);

    // Update 1: node 1 holds pv=7, node 2 is plain.
    TestSemanticsNode root1 = new TestSemanticsNode();
    root1.id = 0;
    TestSemanticsNode node1 = new TestSemanticsNode();
    node1.id = 1;
    node1.platformViewId = 7;
    TestSemanticsNode node2 = new TestSemanticsNode();
    node2.id = 2;
    root1.addChild(node1);
    root1.addChild(node2);
    root1.toUpdate().sendUpdateToBridge(accessibilityBridge);

    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, wrapper.getImportantForAccessibility());
    reset(wrapper);

    // Update 2: node 1 drops pv=7 and node 2 adopts pv=7 in the same batch.
    TestSemanticsNode root2 = new TestSemanticsNode();
    root2.id = 0;
    TestSemanticsNode node1Updated = new TestSemanticsNode();
    node1Updated.id = 1;
    node1Updated.platformViewId = -1;
    TestSemanticsNode node2Updated = new TestSemanticsNode();
    node2Updated.id = 2;
    node2Updated.platformViewId = 7;
    root2.addChild(node1Updated);
    root2.addChild(node2Updated);
    root2.toUpdate().sendUpdateToBridge(accessibilityBridge);

    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, wrapper.getImportantForAccessibility());
    verify(wrapper, never())
        .setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    verify(wrapper, never()).setAccessibilityDelegate(null);
  }

  @Test
  public void itDetachesWrapperWhenPlatformViewIdChangesOnSameNode() {
    PlatformViewsAccessibilityDelegate accessibilityDelegate =
        mock(PlatformViewsAccessibilityDelegate.class);
    Context context = ApplicationProvider.getApplicationContext();
    View rootAccessibilityView = new View(context);
    AccessibilityViewEmbedder accessibilityViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            rootAccessibilityView,
            /* accessibilityChannel= */ null,
            /* accessibilityManager= */ null,
            /* contentResolver= */ null,
            accessibilityViewEmbedder,
            accessibilityDelegate);

    FlutterMutatorView wrapper1 = new FlutterMutatorView(context);
    FlutterMutatorView wrapper2 = new FlutterMutatorView(context);
    when(accessibilityDelegate.getPlatformViewWrapperById(1)).thenReturn(wrapper1);
    when(accessibilityDelegate.getPlatformViewWrapperById(2)).thenReturn(wrapper2);
    when(accessibilityDelegate.usesVirtualDisplay(anyInt())).thenReturn(false);

    TestSemanticsNode root1 = new TestSemanticsNode();
    root1.id = 0;
    TestSemanticsNode node = new TestSemanticsNode();
    node.id = 1;
    node.platformViewId = 1;
    root1.addChild(node);
    root1.toUpdate().sendUpdateToBridge(accessibilityBridge);

    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, wrapper1.getImportantForAccessibility());
    assertNotNull(wrapper1.getAccessibilityDelegate());

    // Same SemanticsNode (id=1) now references platformViewId=2.
    TestSemanticsNode root2 = new TestSemanticsNode();
    root2.id = 0;
    TestSemanticsNode nodeUpdated = new TestSemanticsNode();
    nodeUpdated.id = 1;
    nodeUpdated.platformViewId = 2;
    root2.addChild(nodeUpdated);
    root2.toUpdate().sendUpdateToBridge(accessibilityBridge);

    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        wrapper1.getImportantForAccessibility());
    assertNull(wrapper1.getAccessibilityDelegate());
    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, wrapper2.getImportantForAccessibility());
    assertNotNull(wrapper2.getAccessibilityDelegate());
  }

  @Test
  public void itReplacesWrapperAndCleansUpPreviousInstanceForSamePlatformViewId() {
    PlatformViewsAccessibilityDelegate accessibilityDelegate =
        mock(PlatformViewsAccessibilityDelegate.class);
    Context context = ApplicationProvider.getApplicationContext();
    View rootAccessibilityView = new View(context);
    AccessibilityViewEmbedder accessibilityViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            rootAccessibilityView,
            /* accessibilityChannel= */ null,
            /* accessibilityManager= */ null,
            /* contentResolver= */ null,
            accessibilityViewEmbedder,
            accessibilityDelegate);

    FlutterMutatorView oldWrapper = new FlutterMutatorView(context);
    FlutterMutatorView newWrapper = new FlutterMutatorView(context);
    when(accessibilityDelegate.getPlatformViewWrapperById(1)).thenReturn(oldWrapper);
    when(accessibilityDelegate.usesVirtualDisplay(1)).thenReturn(false);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode pvNode = new TestSemanticsNode();
    pvNode.id = 1;
    pvNode.platformViewId = 1;
    root.addChild(pvNode);
    root.toUpdate().sendUpdateToBridge(accessibilityBridge);

    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, oldWrapper.getImportantForAccessibility());
    assertNotNull(oldWrapper.getAccessibilityDelegate());

    // Controller now returns a new wrapper instance for the same platformViewId.
    when(accessibilityDelegate.getPlatformViewWrapperById(1)).thenReturn(newWrapper);
    root.toUpdate().sendUpdateToBridge(accessibilityBridge);

    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        oldWrapper.getImportantForAccessibility());
    assertNull(oldWrapper.getAccessibilityDelegate());
    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, newWrapper.getImportantForAccessibility());
    assertNotNull(newWrapper.getAccessibilityDelegate());
  }

  @Test
  public void itPreservesWrapperVisibilityUnderScrollContainer() {
    Context context = ApplicationProvider.getApplicationContext();
    FrameLayout hostContainer = new FrameLayout(context);
    View rootAccessibilityView = new View(context);
    hostContainer.addView(rootAccessibilityView);

    PlatformViewsAccessibilityDelegate accessibilityDelegate =
        mock(PlatformViewsAccessibilityDelegate.class);
    AccessibilityManager accessibilityManager = mock(AccessibilityManager.class);
    when(accessibilityManager.isEnabled()).thenReturn(true);
    when(accessibilityManager.isTouchExplorationEnabled()).thenReturn(true);
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            rootAccessibilityView,
            /* accessibilityChannel= */ null,
            accessibilityManager,
            /* contentResolver= */ null,
            mock(AccessibilityViewEmbedder.class),
            accessibilityDelegate);

    FlutterMutatorView scrollChildWrapper = new FlutterMutatorView(context);
    when(accessibilityDelegate.getPlatformViewWrapperById(1)).thenReturn(scrollChildWrapper);
    when(accessibilityDelegate.usesVirtualDisplay(1)).thenReturn(false);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode scrollContainer = new TestSemanticsNode();
    scrollContainer.id = 1;
    scrollContainer.addAction(Action.SCROLL_DOWN);
    scrollContainer.scrollChildren = 2;
    TestSemanticsNode scrollPv = new TestSemanticsNode();
    scrollPv.id = 2;
    scrollPv.platformViewId = 1;
    scrollContainer.addChild(scrollPv);
    root.addChild(scrollContainer);
    root.toUpdate().sendUpdateToBridge(accessibilityBridge);

    AccessibilityNodeInfo wrapperInfo = mock(AccessibilityNodeInfo.class);
    scrollChildWrapper
        .getAccessibilityDelegate()
        .onInitializeAccessibilityNodeInfo(scrollChildWrapper, wrapperInfo);
    verify(wrapperInfo).setParent(rootAccessibilityView, 1);
    verify(wrapperInfo, never()).setVisibleToUser(false);
  }

  @Test
  public void itUpdatesTraversalAfterDynamicallyAcrossOutOfOrderLazyPlatformViewAttachments() {
    PlatformViewsAccessibilityDelegate accessibilityDelegate =
        mock(PlatformViewsAccessibilityDelegate.class);
    Context context = ApplicationProvider.getApplicationContext();
    View rootAccessibilityView = new View(context);
    AccessibilityViewEmbedder accessibilityViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            rootAccessibilityView,
            /* accessibilityChannel= */ null,
            /* accessibilityManager= */ null,
            /* contentResolver= */ null,
            accessibilityViewEmbedder,
            accessibilityDelegate);

    when(accessibilityDelegate.usesVirtualDisplay(anyInt())).thenReturn(false);
    // Neither wrapper exists yet when updateSemantics arrives.
    when(accessibilityDelegate.getPlatformViewWrapperById(0)).thenReturn(null);
    when(accessibilityDelegate.getPlatformViewWrapperById(1)).thenReturn(null);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode pv1 = new TestSemanticsNode();
    pv1.id = 1;
    pv1.platformViewId = 0;
    TestSemanticsNode pv2 = new TestSemanticsNode();
    pv2.id = 2;
    pv2.platformViewId = 1;
    root.addChild(pv1);
    root.addChild(pv2);
    root.toUpdate().sendUpdateToBridge(accessibilityBridge);

    // Second platform view attaches BEFORE the first.
    FlutterMutatorView wrapper2 = new FlutterMutatorView(context);
    when(accessibilityDelegate.getPlatformViewWrapperById(1)).thenReturn(wrapper2);
    accessibilityBridge.onPlatformViewAttached(1);

    AccessibilityNodeInfo infoBeforePv1Attaches = mock(AccessibilityNodeInfo.class);
    wrapper2
        .getAccessibilityDelegate()
        .onInitializeAccessibilityNodeInfo(wrapper2, infoBeforePv1Attaches);
    verify(infoBeforePv1Attaches).setTraversalAfter(rootAccessibilityView, 1);

    // First platform view now attaches lazily; without another updateSemantics, wrapper2's
    // delegate must dynamically resolve traversalAfter to wrapper1.
    FlutterMutatorView wrapper1 = new FlutterMutatorView(context);
    when(accessibilityDelegate.getPlatformViewWrapperById(0)).thenReturn(wrapper1);
    accessibilityBridge.onPlatformViewAttached(0);

    AccessibilityNodeInfo infoAfterPv1Attaches = mock(AccessibilityNodeInfo.class);
    wrapper2
        .getAccessibilityDelegate()
        .onInitializeAccessibilityNodeInfo(wrapper2, infoAfterPv1Attaches);
    verify(infoAfterPv1Attaches).setTraversalAfter(wrapper1);

    // If pv1 becomes hidden, wrapper2 must not point traversalAfter at the hidden wrapper1.
    pv1.addFlag(Flag.IS_HIDDEN);
    root.toUpdate().sendUpdateToBridge(accessibilityBridge);

    AccessibilityNodeInfo infoAfterPv1Hidden = mock(AccessibilityNodeInfo.class);
    wrapper2
        .getAccessibilityDelegate()
        .onInitializeAccessibilityNodeInfo(wrapper2, infoAfterPv1Hidden);
    verify(infoAfterPv1Hidden, never()).setTraversalAfter(wrapper1);
    verify(infoAfterPv1Hidden).setTraversalAfter(rootAccessibilityView, 1);
  }

  @Test
  public void itDetachesWrapperUnderHiddenAncestorAndKeepsCreateAccessibilityNodeInfoReadOnly() {
    PlatformViewsAccessibilityDelegate accessibilityDelegate =
        mock(PlatformViewsAccessibilityDelegate.class);
    Context context = ApplicationProvider.getApplicationContext();
    View rootAccessibilityView = new View(context);
    AccessibilityViewEmbedder accessibilityViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityBridge accessibilityBridge =
        spy(
            setUpBridge(
                rootAccessibilityView,
                /* accessibilityChannel= */ null,
                /* accessibilityManager= */ null,
                /* contentResolver= */ null,
                accessibilityViewEmbedder,
                accessibilityDelegate));

    FlutterMutatorView wrapper = new FlutterMutatorView(context);
    wrapper.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    when(accessibilityDelegate.getPlatformViewWrapperById(1)).thenReturn(wrapper);
    when(accessibilityDelegate.usesVirtualDisplay(1)).thenReturn(false);

    // Attach root(0) -> ancestor(1) -> pv(2, pvId=1).
    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode ancestor = new TestSemanticsNode();
    ancestor.id = 1;
    TestSemanticsNode pvNode = new TestSemanticsNode();
    pvNode.id = 2;
    pvNode.platformViewId = 1;
    ancestor.addChild(pvNode);
    root.addChild(ancestor);
    root.toUpdate().sendUpdateToBridge(accessibilityBridge);

    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, wrapper.getImportantForAccessibility());
    assertNotNull(wrapper.getAccessibilityDelegate());

    // Hiding the ancestor detaches the descendant wrapper.
    ancestor.addFlag(Flag.IS_HIDDEN);
    root.toUpdate().sendUpdateToBridge(accessibilityBridge);
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        wrapper.getImportantForAccessibility());
    assertNull(wrapper.getAccessibilityDelegate());

    // Querying createAccessibilityNodeInfo on the hidden ancestor must remain read-only and must
    // not re-enable the wrapper or attach it as a child.
    AccessibilityNodeInfo mockAncestorInfo = mock(AccessibilityNodeInfo.class);
    when(accessibilityBridge.obtainAccessibilityNodeInfo(rootAccessibilityView, 1))
        .thenReturn(mockAncestorInfo);
    accessibilityBridge.createAccessibilityNodeInfo(1);
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        wrapper.getImportantForAccessibility());
    assertNull(wrapper.getAccessibilityDelegate());
    verify(mockAncestorInfo, never()).addChild(wrapper);

    // Unhide, then release(): createAccessibilityNodeInfo after release() must not re-attach.
    ancestor.flags = 0;
    root.toUpdate().sendUpdateToBridge(accessibilityBridge);
    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, wrapper.getImportantForAccessibility());

    accessibilityBridge.release();
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        wrapper.getImportantForAccessibility());
    assertNull(wrapper.getAccessibilityDelegate());

    AccessibilityNodeInfo mockPostReleaseInfo = mock(AccessibilityNodeInfo.class);
    when(accessibilityBridge.obtainAccessibilityNodeInfo(rootAccessibilityView, 1))
        .thenReturn(mockPostReleaseInfo);
    accessibilityBridge.createAccessibilityNodeInfo(1);
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        wrapper.getImportantForAccessibility());
    assertNull(wrapper.getAccessibilityDelegate());
    verify(mockPostReleaseInfo, never()).addChild(wrapper);
  }

  @Test
  public void itIgnoresOnPlatformViewAttachedWhenHiddenAbsentVirtualDisplayOrReleased() {
    PlatformViewsAccessibilityDelegate accessibilityDelegate =
        mock(PlatformViewsAccessibilityDelegate.class);
    Context context = ApplicationProvider.getApplicationContext();
    View rootAccessibilityView = new View(context);
    AccessibilityViewEmbedder accessibilityViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            rootAccessibilityView,
            /* accessibilityChannel= */ null,
            /* accessibilityManager= */ null,
            /* contentResolver= */ null,
            accessibilityViewEmbedder,
            accessibilityDelegate);

    FlutterMutatorView hiddenNodeWrapper = new FlutterMutatorView(context);
    hiddenNodeWrapper.setImportantForAccessibility(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    FlutterMutatorView hiddenAncestorWrapper = new FlutterMutatorView(context);
    hiddenAncestorWrapper.setImportantForAccessibility(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    FlutterMutatorView absentNodeWrapper = new FlutterMutatorView(context);
    absentNodeWrapper.setImportantForAccessibility(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    FlutterMutatorView vdWrapper = new FlutterMutatorView(context);
    vdWrapper.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    FlutterMutatorView visibleWrapper = new FlutterMutatorView(context);
    visibleWrapper.setImportantForAccessibility(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

    when(accessibilityDelegate.usesVirtualDisplay(anyInt())).thenReturn(false);
    when(accessibilityDelegate.usesVirtualDisplay(4)).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;

    TestSemanticsNode hiddenPv = new TestSemanticsNode();
    hiddenPv.id = 1;
    hiddenPv.platformViewId = 1;
    hiddenPv.addFlag(Flag.IS_HIDDEN);
    root.addChild(hiddenPv);

    TestSemanticsNode hiddenAncestor = new TestSemanticsNode();
    hiddenAncestor.id = 2;
    hiddenAncestor.addFlag(Flag.IS_HIDDEN);
    TestSemanticsNode childOfHiddenAncestor = new TestSemanticsNode();
    childOfHiddenAncestor.id = 3;
    childOfHiddenAncestor.platformViewId = 2;
    hiddenAncestor.addChild(childOfHiddenAncestor);
    root.addChild(hiddenAncestor);

    TestSemanticsNode vdPv = new TestSemanticsNode();
    vdPv.id = 4;
    vdPv.platformViewId = 4;
    root.addChild(vdPv);

    TestSemanticsNode visiblePv = new TestSemanticsNode();
    visiblePv.id = 5;
    visiblePv.platformViewId = 5;
    root.addChild(visiblePv);

    // Semantics arrive while wrappers are still null.
    root.toUpdate().sendUpdateToBridge(accessibilityBridge);

    when(accessibilityDelegate.getPlatformViewWrapperById(1)).thenReturn(hiddenNodeWrapper);
    when(accessibilityDelegate.getPlatformViewWrapperById(2)).thenReturn(hiddenAncestorWrapper);
    when(accessibilityDelegate.getPlatformViewWrapperById(3)).thenReturn(absentNodeWrapper);
    when(accessibilityDelegate.getPlatformViewWrapperById(4)).thenReturn(vdWrapper);
    when(accessibilityDelegate.getPlatformViewWrapperById(5)).thenReturn(visibleWrapper);

    // 1. Node itself hidden.
    accessibilityBridge.onPlatformViewAttached(1);
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        hiddenNodeWrapper.getImportantForAccessibility());
    assertNull(hiddenNodeWrapper.getAccessibilityDelegate());

    // 2. Ancestor hidden.
    accessibilityBridge.onPlatformViewAttached(2);
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        hiddenAncestorWrapper.getImportantForAccessibility());
    assertNull(hiddenAncestorWrapper.getAccessibilityDelegate());

    // 3. Absent from semantics tree (e.g. ExcludeSemantics).
    accessibilityBridge.onPlatformViewAttached(3);
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        absentNodeWrapper.getImportantForAccessibility());
    assertNull(absentNodeWrapper.getAccessibilityDelegate());

    // 4. Virtual display platform view.
    accessibilityBridge.onPlatformViewAttached(4);
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        vdWrapper.getImportantForAccessibility());
    assertNull(vdWrapper.getAccessibilityDelegate());

    // 5. Bridge released before attach arrives.
    accessibilityBridge.release();
    accessibilityBridge.onPlatformViewAttached(5);
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        visibleWrapper.getImportantForAccessibility());
    assertNull(visibleWrapper.getAccessibilityDelegate());
  }

  @Test
  public void itDetachesWrapperOnPlatformViewDetached() {
    PlatformViewsAccessibilityDelegate accessibilityDelegate =
        mock(PlatformViewsAccessibilityDelegate.class);
    Context context = ApplicationProvider.getApplicationContext();
    View rootAccessibilityView = new View(context);
    AccessibilityViewEmbedder accessibilityViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityBridge accessibilityBridge =
        spy(
            setUpBridge(
                rootAccessibilityView,
                /* accessibilityChannel= */ null,
                /* accessibilityManager= */ null,
                /* contentResolver= */ null,
                accessibilityViewEmbedder,
                accessibilityDelegate));

    FlutterMutatorView wrapper = new FlutterMutatorView(context);
    when(accessibilityDelegate.getPlatformViewWrapperById(1)).thenReturn(wrapper);
    when(accessibilityDelegate.usesVirtualDisplay(1)).thenReturn(false);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode pvNode = new TestSemanticsNode();
    pvNode.id = 1;
    pvNode.platformViewId = 1;
    root.addChild(pvNode);
    root.toUpdate().sendUpdateToBridge(accessibilityBridge);

    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, wrapper.getImportantForAccessibility());
    assertNotNull(wrapper.getAccessibilityDelegate());

    accessibilityBridge.onPlatformViewDetached(1);

    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        wrapper.getImportantForAccessibility());
    assertNull(wrapper.getAccessibilityDelegate());

    // Detached wrapper is no longer linked as a child from createAccessibilityNodeInfo.
    AccessibilityNodeInfo mockRootInfo = mock(AccessibilityNodeInfo.class);
    when(accessibilityBridge.obtainAccessibilityNodeInfo(rootAccessibilityView, 0))
        .thenReturn(mockRootInfo);
    accessibilityBridge.createAccessibilityNodeInfo(0);
    verify(mockRootInfo, never()).addChild(wrapper);
  }

  @Test
  public void itDoesNotClearNewerBridgeDelegateWhenOlderBridgeReleases() {
    PlatformViewsAccessibilityDelegate accessibilityDelegate =
        mock(PlatformViewsAccessibilityDelegate.class);
    Context context = ApplicationProvider.getApplicationContext();
    View rootView1 = new View(context);
    View rootView2 = new View(context);

    AccessibilityBridge bridge1 =
        setUpBridge(
            rootView1,
            /* accessibilityChannel= */ null,
            /* accessibilityManager= */ null,
            /* contentResolver= */ null,
            mock(AccessibilityViewEmbedder.class),
            accessibilityDelegate);
    AccessibilityBridge bridge2 =
        setUpBridge(
            rootView2,
            /* accessibilityChannel= */ null,
            /* accessibilityManager= */ null,
            /* contentResolver= */ null,
            mock(AccessibilityViewEmbedder.class),
            accessibilityDelegate);

    FlutterMutatorView sharedWrapper = new FlutterMutatorView(context);
    sharedWrapper.setImportantForAccessibility(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    when(accessibilityDelegate.getPlatformViewWrapperById(1)).thenReturn(sharedWrapper);
    when(accessibilityDelegate.usesVirtualDisplay(1)).thenReturn(false);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode pvNode = new TestSemanticsNode();
    pvNode.id = 1;
    pvNode.platformViewId = 1;
    root.addChild(pvNode);

    // Bridge 1 attaches first, then Bridge 2 takes over before Bridge 1 releases.
    root.toUpdate().sendUpdateToBridge(bridge1);
    root.toUpdate().sendUpdateToBridge(bridge2);
    View.AccessibilityDelegate bridge2Delegate = sharedWrapper.getAccessibilityDelegate();
    assertNotNull(bridge2Delegate);

    bridge1.release();

    // Bridge 2's delegate and importance must remain untouched.
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_YES, sharedWrapper.getImportantForAccessibility());
    assertSame(bridge2Delegate, sharedWrapper.getAccessibilityDelegate());

    // Once Bridge 2 releases, cleanup proceeds normally.
    bridge2.release();
    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        sharedWrapper.getImportantForAccessibility());
    assertNull(sharedWrapper.getAccessibilityDelegate());
  }

  @Test
  public void itPreservesPluginDelegateWhenDefaultWrapperFallbackReturnsEmbeddedView() {
    Context context = ApplicationProvider.getApplicationContext();
    View rootAccessibilityView = new View(context);
    FrameLayout embeddedView = new FrameLayout(context);
    View.AccessibilityDelegate pluginDelegate = new View.AccessibilityDelegate();
    embeddedView.setAccessibilityDelegate(pluginDelegate);

    // Custom implementer that relies on the interface default getPlatformViewWrapperById()
    // returning getPlatformViewById().
    PlatformViewsAccessibilityDelegate customDelegate =
        new PlatformViewsAccessibilityDelegate() {
          @Override
          public View getPlatformViewById(int viewId) {
            return viewId == 1 ? embeddedView : null;
          }

          @Override
          public boolean usesVirtualDisplay(int id) {
            return false;
          }

          @Override
          public void attachAccessibilityBridge(AccessibilityBridge accessibilityBridge) {}

          @Override
          public void detachAccessibilityBridge() {}
        };

    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            rootAccessibilityView,
            /* accessibilityChannel= */ null,
            /* accessibilityManager= */ null,
            /* contentResolver= */ null,
            mock(AccessibilityViewEmbedder.class),
            customDelegate);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode pvNode = new TestSemanticsNode();
    pvNode.id = 1;
    pvNode.platformViewId = 1;
    root.addChild(pvNode);
    root.toUpdate().sendUpdateToBridge(accessibilityBridge);

    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, embeddedView.getImportantForAccessibility());
    assertSame(pluginDelegate, embeddedView.getAccessibilityDelegate());

    // Removing the node disables importance without clobbering the plugin's delegate.
    TestSemanticsNode emptyRoot = new TestSemanticsNode();
    emptyRoot.id = 0;
    emptyRoot.toUpdate().sendUpdateToBridge(accessibilityBridge);

    assertEquals(
        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
        embeddedView.getImportantForAccessibility());
    assertSame(pluginDelegate, embeddedView.getAccessibilityDelegate());
  }

  @Test
  public void itDoesNotMutateVirtualDisplayEmbeddedViewOnAttachOrRemoval() {
    PlatformViewsAccessibilityDelegate accessibilityDelegate =
        mock(PlatformViewsAccessibilityDelegate.class);
    Context context = ApplicationProvider.getApplicationContext();
    View rootAccessibilityView = new View(context);
    AccessibilityViewEmbedder accessibilityViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            rootAccessibilityView,
            /* accessibilityChannel= */ null,
            /* accessibilityManager= */ null,
            /* contentResolver= */ null,
            accessibilityViewEmbedder,
            accessibilityDelegate);

    View vdEmbeddedView = mock(View.class);
    when(accessibilityDelegate.getPlatformViewById(1)).thenReturn(vdEmbeddedView);
    when(accessibilityDelegate.getPlatformViewWrapperById(1)).thenReturn(null);
    when(accessibilityDelegate.usesVirtualDisplay(1)).thenReturn(true);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;
    TestSemanticsNode vdNode = new TestSemanticsNode();
    vdNode.id = 1;
    vdNode.platformViewId = 1;
    root.addChild(vdNode);
    root.toUpdate().sendUpdateToBridge(accessibilityBridge);

    TestSemanticsNode emptyRoot = new TestSemanticsNode();
    emptyRoot.id = 0;
    emptyRoot.toUpdate().sendUpdateToBridge(accessibilityBridge);

    accessibilityBridge.reset();
    accessibilityBridge.release();

    verify(vdEmbeddedView, never()).setImportantForAccessibility(anyInt());
    verify(vdEmbeddedView, never()).setAccessibilityDelegate(any());
  }

  @Test
  public void itProducesPlatformViewNodeForVirtualDisplay() {
    PlatformViewsAccessibilityDelegate accessibilityDelegate =
        mock(PlatformViewsAccessibilityDelegate.class);
    AccessibilityViewEmbedder accessibilityViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ null,
            /* accessibilityChannel= */ null,
            /* accessibilityManager= */ null,
            /* contentResolver= */ null,
            accessibilityViewEmbedder,
            accessibilityDelegate);

    TestSemanticsNode platformView = new TestSemanticsNode();
    platformView.platformViewId = 1;

    TestSemanticsUpdate testSemanticsUpdate = platformView.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    View embeddedView = mock(View.class);
    when(accessibilityDelegate.getPlatformViewById(1)).thenReturn(embeddedView);
    when(accessibilityDelegate.usesVirtualDisplay(1)).thenReturn(true);

    accessibilityBridge.createAccessibilityNodeInfo(0);
    verify(accessibilityViewEmbedder).getRootNode(eq(embeddedView), eq(0), any(Rect.class));
  }

  @Test
  public void itDoesNotCrashWhenEmbeddedViewIsNull() {
    PlatformViewsAccessibilityDelegate accessibilityDelegate =
        mock(PlatformViewsAccessibilityDelegate.class);
    AccessibilityViewEmbedder accessibilityViewEmbedder = mock(AccessibilityViewEmbedder.class);
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ null,
            /* accessibilityChannel= */ null,
            /* accessibilityManager= */ null,
            /* contentResolver= */ null,
            accessibilityViewEmbedder,
            accessibilityDelegate);

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;

    TestSemanticsNode platformView = new TestSemanticsNode();
    platformView.id = 1;
    platformView.platformViewId = 1;
    root.addChild(platformView);

    when(accessibilityDelegate.usesVirtualDisplay(1)).thenReturn(false);
    when(accessibilityDelegate.getPlatformViewById(1)).thenReturn(null);
    when(accessibilityDelegate.getPlatformViewWrapperById(1)).thenReturn(null);

    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    // This should not crash.
    AccessibilityNodeInfo result = accessibilityBridge.createAccessibilityNodeInfo(0);

    // Verify that we fell back to adding the child as a virtual node (standard semantics node)
    // instead of trying to add the null embedded view.
    boolean hasChild = false;
    for (int i = 0; i < result.getChildCount(); i++) {
      hasChild = true;
    }
    assertTrue("Should have added the virtual child node", hasChild);
  }

  @Test
  public void testItSetsDisableAnimationsFlagBasedOnTransitionAnimationScale() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    ContentResolver mockContentResolver = mock(ContentResolver.class);

    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ null,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ null,
            /* contentResolver= */ mockContentResolver,
            /* accessibilityViewEmbedder= */ null,
            /* platformViewsAccessibilityDelegate= */ null);

    // Capture the observer registered for
    // Settings.Global.TRANSITION_ANIMATION_SCALE
    ArgumentCaptor<ContentObserver> observerCaptor = ArgumentCaptor.forClass(ContentObserver.class);
    verify(mockContentResolver)
        .registerContentObserver(
            eq(Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE)),
            eq(false),
            observerCaptor.capture());
    ContentObserver observer = observerCaptor.getValue();

    // Initial state
    verify(mockChannel, atLeastOnce()).setAccessibilityFeatures(ACCESSIBILITY_FEATURE_NO_ANNOUNCE);
    reset(mockChannel);

    // Animations are disabled
    Settings.Global.putFloat(mockContentResolver, "transition_animation_scale", 0.0f);
    observer.onChange(false);
    verify(mockChannel, atLeastOnce())
        .setAccessibilityFeatures(
            ACCESSIBILITY_FEATURE_DISABLE_ANIMATIONS | ACCESSIBILITY_FEATURE_NO_ANNOUNCE);
    reset(mockChannel);

    // Animations are enabled
    Settings.Global.putFloat(mockContentResolver, "transition_animation_scale", 1.0f);
    observer.onChange(false);
    verify(mockChannel, atLeastOnce()).setAccessibilityFeatures(ACCESSIBILITY_FEATURE_NO_ANNOUNCE);
  }

  @Test
  public void releaseDropsChannelMessageHandler() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    ContentResolver mockContentResolver = mock(ContentResolver.class);
    when(mockManager.isEnabled()).thenReturn(true);
    AccessibilityBridge accessibilityBridge =
        setUpBridge(null, mockChannel, mockManager, mockContentResolver, null, null);
    verify(mockChannel)
        .setAccessibilityMessageHandler(
            any(AccessibilityChannel.AccessibilityMessageHandler.class));
    ArgumentCaptor<AccessibilityManager.AccessibilityStateChangeListener> stateListenerCaptor =
        ArgumentCaptor.forClass(AccessibilityManager.AccessibilityStateChangeListener.class);
    ArgumentCaptor<AccessibilityManager.TouchExplorationStateChangeListener> touchListenerCaptor =
        ArgumentCaptor.forClass(AccessibilityManager.TouchExplorationStateChangeListener.class);
    verify(mockManager).addAccessibilityStateChangeListener(stateListenerCaptor.capture());
    verify(mockManager).addTouchExplorationStateChangeListener(touchListenerCaptor.capture());
    accessibilityBridge.release();
    verify(mockChannel).setAccessibilityMessageHandler(null);
    reset(mockChannel);
    stateListenerCaptor.getValue().onAccessibilityStateChanged(true);
    verify(mockChannel, never()).onAndroidAccessibilityEnabled();
    touchListenerCaptor.getValue().onTouchExplorationStateChanged(true);
    verify(mockChannel, never()).setAccessibilityFeatures(anyInt());
  }

  @Test
  public void sendFocusAccessibilityEvent() {
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    AccessibilityChannel accessibilityChannel =
        new AccessibilityChannel(mock(DartExecutor.class), mock(FlutterJNI.class));

    ContentResolver mockContentResolver = mock(ContentResolver.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    when(mockManager.isEnabled()).thenReturn(true);

    AccessibilityBridge accessibilityBridge =
        setUpBridge(mockRootView, accessibilityChannel, mockManager, null, null, null);

    HashMap<String, Object> arguments = new HashMap<>();
    arguments.put("type", "focus");
    arguments.put("nodeId", 123);
    BasicMessageChannel.Reply reply = mock(BasicMessageChannel.Reply.class);
    accessibilityChannel.parsingMessageHandler.onMessage(arguments, reply);

    // Check that focus event was sent.
    ArgumentCaptor<AccessibilityEvent> eventCaptor =
        ArgumentCaptor.forClass(AccessibilityEvent.class);
    verify(mockParent).requestSendAccessibilityEvent(eq(mockRootView), eventCaptor.capture());
    AccessibilityEvent event = eventCaptor.getAllValues().get(0);
    assertEquals(AccessibilityEvent.TYPE_VIEW_FOCUSED, event.getEventType());
    assertNull(event.getSource());
  }

  @Test
  public void SetSourceAndPackageNameForAccessibilityEvent() {
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    ContentResolver mockContentResolver = mock(ContentResolver.class);
    View mockRootView = mock(View.class);
    Context context = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(context);
    when(context.getPackageName()).thenReturn("test");
    when(mockManager.isEnabled()).thenReturn(true);
    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    AccessibilityEvent mockEvent = mock(AccessibilityEvent.class);

    AccessibilityBridge accessibilityBridge =
        setUpBridge(mockRootView, null, mockManager, null, null, null);

    AccessibilityBridge spyAccessibilityBridge = spy(accessibilityBridge);

    when(spyAccessibilityBridge.obtainAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED))
        .thenReturn(mockEvent);

    spyAccessibilityBridge.sendAccessibilityEvent(123, AccessibilityEvent.TYPE_VIEW_FOCUSED);

    verify(mockEvent).setPackageName("test");
    verify(mockEvent).setSource(eq(mockRootView), eq(123));
  }

  @Test
  public void itAddsButtonClassToLinkWithoutURL() {
    AccessibilityBridge accessibilityBridge = setUpBridge();

    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    testSemanticsNode.addFlag(AccessibilityBridge.Flag.IS_LINK);
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);

    assertEquals("android.widget.Button", nodeInfo.getClassName());
  }

  @Test
  public void itAddsClickActionToSliderNodeInfo() {
    AccessibilityBridge accessibilityBridge = setUpBridge();

    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    testSemanticsNode.addFlag(AccessibilityBridge.Flag.IS_SLIDER);
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);

    assertTrue(nodeInfo.isClickable());
    List<AccessibilityNodeInfo.AccessibilityAction> actions = nodeInfo.getActionList();
    assertTrue(actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
  }

  // Setup method for testing CollectionInfo
  // The logic has branching based on SDK version. The version tests are below
  public void itAddsCollectionInfo() {
    AccessibilityBridge accessibilityBridge = setUpBridge();

    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    testSemanticsNode.addFlag(AccessibilityBridge.Flag.HAS_IMPLICIT_SCROLLING);
    // test with 1 scrollChild
    testSemanticsNode.scrollChildren = 1;
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertNull(nodeInfo.getCollectionInfo());
    // test with 2 scrollChildren
    testSemanticsNode.scrollChildren = 2;
    testSemanticsUpdate = testSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    AccessibilityNodeInfo.CollectionInfo collectionInfo = nodeInfo.getCollectionInfo();
    assertNotNull(collectionInfo);

    assertEquals(testSemanticsNode.scrollChildren, collectionInfo.getRowCount());
    assertEquals(1, collectionInfo.getColumnCount()); // 1 column for a list
    assertFalse(collectionInfo.isHierarchical());
  }

  @Config(sdk = API_LEVELS.API_32)
  @TargetApi(API_LEVELS.API_32)
  @Test
  public void itAddsCollectionInfoAPI32() {
    // Testing CollectionInfo creation for API 32
    itAddsCollectionInfo();
  }

  @Config(sdk = API_LEVELS.API_33)
  @TargetApi(API_LEVELS.API_33)
  @Test
  public void itAddsCollectionInfoAPI33() {
    // Testing CollectionInfo creation for API 33
    itAddsCollectionInfo();
  }

  // Setup method for testing CollectionItemInfo
  // The logic has branching based on SDK version. The version tests are below
  public void itAddsCollectionItemInfo() {
    AccessibilityBridge accessibilityBridge = setUpBridge();

    TestSemanticsNode parentTestSemanticsNode = new TestSemanticsNode();
    parentTestSemanticsNode.addFlag(AccessibilityBridge.Flag.HAS_IMPLICIT_SCROLLING);
    parentTestSemanticsNode.scrollChildren = 2;
    parentTestSemanticsNode.id = 0;
    // add children to parentTestSemanticsNode
    TestSemanticsNode childNode1 = new TestSemanticsNode();
    childNode1.id = 1;
    childNode1.label = "Test 1";
    TestSemanticsNode childNode2 = new TestSemanticsNode();
    childNode2.id = 2;
    childNode2.label = "Test 2";
    parentTestSemanticsNode.addChild(childNode1);
    parentTestSemanticsNode.addChild(childNode2);
    TestSemanticsUpdate testSemanticsUpdate = parentTestSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(1);
    AccessibilityNodeInfo.CollectionItemInfo itemInfo = nodeInfo.getCollectionItemInfo();
    assertNotNull(itemInfo);

    assertEquals(0, itemInfo.getRowIndex()); // first item in the list
    assertEquals(1, itemInfo.getRowSpan());
    assertEquals(0, itemInfo.getColumnIndex()); // only a single column
    assertEquals(1, itemInfo.getColumnSpan());
    // Note: CollectionItemInfo.isHeading() was deprecated in API 28, and since this test node
    // doesn't have IS_HEADER flag,
    // we expect it to not be a heading. The heading state is set during CollectionItemInfo
    // construction.
    // Documentation says to check AccessibilityNodeInfo.isHeading() instead.
    assertFalse(nodeInfo.isHeading());
  }

  @Test
  public void itAddsScrollViewToClassName() {
    AccessibilityBridge accessibilityBridge = setUpBridge();
    // Vertical scroll view
    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    testSemanticsNode.addFlag(AccessibilityBridge.Flag.HAS_IMPLICIT_SCROLLING);
    testSemanticsNode.addAction(Action.SCROLL_UP);
    testSemanticsNode.addAction(Action.SCROLL_DOWN);
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals("android.widget.ScrollView", nodeInfo.getClassName().toString());
    // Horizontal scroll view
    testSemanticsNode = new TestSemanticsNode();
    testSemanticsNode.addFlag(AccessibilityBridge.Flag.HAS_IMPLICIT_SCROLLING);
    testSemanticsNode.addAction(Action.SCROLL_LEFT);
    testSemanticsNode.addAction(Action.SCROLL_RIGHT);
    testSemanticsUpdate = testSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals("android.widget.HorizontalScrollView", nodeInfo.getClassName().toString());
  }

  @Test
  public void itAddsProgressBarToClassName() {
    AccessibilityBridge accessibilityBridge = setUpBridge();
    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    testSemanticsNode.role = AccessibilityBridge.Role.PROGRESS_BAR.value;
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals("android.widget.ProgressBar", nodeInfo.getClassName().toString());
  }

  @Test
  public void itAddsComboBoxToClassName() {
    AccessibilityBridge accessibilityBridge = setUpBridge();
    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    testSemanticsNode.role = AccessibilityBridge.Role.COMBO_BOX.value;
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals("android.widget.Spinner", nodeInfo.getClassName().toString());
    assertTrue(nodeInfo.canOpenPopup());
  }

  @Test
  public void itAddsMenuToClassName() {
    AccessibilityBridge accessibilityBridge = setUpBridge();
    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    testSemanticsNode.role = AccessibilityBridge.Role.MENU.value;
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals("android.widget.Spinner", nodeInfo.getClassName().toString());
    assertTrue(nodeInfo.canOpenPopup());
  }

  @Test
  public void itAddsMenuItemToClassName() {
    AccessibilityBridge accessibilityBridge = setUpBridge();
    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    testSemanticsNode.role = AccessibilityBridge.Role.MENU_ITEM.value;
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals("android.view.MenuItem", nodeInfo.getClassName().toString());
  }

  @Test
  public void itAddsMenuItemCheckboxToClassName() {
    AccessibilityBridge accessibilityBridge = setUpBridge();
    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    testSemanticsNode.role = AccessibilityBridge.Role.MENU_ITEM_CHECKBOX.value;
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals("android.view.MenuItem", nodeInfo.getClassName().toString());
  }

  @Test
  public void itAddsMenuItemRadioToClassName() {
    AccessibilityBridge accessibilityBridge = setUpBridge();
    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    testSemanticsNode.role = AccessibilityBridge.Role.MENU_ITEM_RADIO.value;
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals("android.view.MenuItem", nodeInfo.getClassName().toString());
  }

  @Test
  public void itAddsListViewToClassName() {
    AccessibilityBridge accessibilityBridge = setUpBridge();
    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    testSemanticsNode.role = AccessibilityBridge.Role.LIST.value;
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals("android.widget.ListView", nodeInfo.getClassName().toString());
  }

  @Test
  public void itAddsRadioGroupToClassName() {
    AccessibilityBridge accessibilityBridge = setUpBridge();
    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    testSemanticsNode.role = AccessibilityBridge.Role.RADIO_GROUP.value;
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals("android.widget.RadioGroup", nodeInfo.getClassName().toString());
  }

  @Test
  public void itAddsRangeInfoToProgressBar() {
    AccessibilityBridge accessibilityBridge = setUpBridge();
    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    testSemanticsNode.role = AccessibilityBridge.Role.PROGRESS_BAR.value;
    testSemanticsNode.value = "50";
    testSemanticsNode.minValue = "0";
    testSemanticsNode.maxValue = "100";
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals("android.widget.ProgressBar", nodeInfo.getClassName().toString());
    assertNotNull(nodeInfo.getRangeInfo());
    assertEquals(0.0f, nodeInfo.getRangeInfo().getMin(), 1e-4f);
    assertEquals(100.0f, nodeInfo.getRangeInfo().getMax(), 1e-4f);
    assertEquals(50.0f, nodeInfo.getRangeInfo().getCurrent(), 1e-4f);
  }

  @Test
  public void itAddsRangeInfoToSlider_withPercentage() {
    AccessibilityBridge accessibilityBridge = setUpBridge();
    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    testSemanticsNode.role = AccessibilityBridge.Role.SLIDER.value;
    testSemanticsNode.value = "50%";
    testSemanticsNode.minValue = "0.0";
    testSemanticsNode.maxValue = "5.0";
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals("android.widget.SeekBar", nodeInfo.getClassName().toString());
    assertNotNull(nodeInfo.getRangeInfo());
    assertEquals(0.0f, nodeInfo.getRangeInfo().getMin(), 1e-4f);
    assertEquals(5.0f, nodeInfo.getRangeInfo().getMax(), 1e-4f);
    assertEquals(2.5f, nodeInfo.getRangeInfo().getCurrent(), 1e-4f);
  }

  @Test
  @Config(sdk = 24)
  public void itAddsActionSetProgressForSlider() {
    AccessibilityBridge accessibilityBridge = setUpBridge();
    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    testSemanticsNode.role = AccessibilityBridge.Role.SLIDER.value;
    testSemanticsNode.value = "50%";
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals("android.widget.SeekBar", nodeInfo.getClassName().toString());
    assertTrue(
        nodeInfo
            .getActionList()
            .contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS));
  }

  @Test
  @Config(sdk = 24)
  public void itOmitsActionSetProgressForProgressBar() {
    AccessibilityBridge accessibilityBridge = setUpBridge();
    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    testSemanticsNode.role = AccessibilityBridge.Role.PROGRESS_BAR.value;
    testSemanticsNode.value = "50";
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals("android.widget.ProgressBar", nodeInfo.getClassName().toString());
    assertFalse(
        nodeInfo
            .getActionList()
            .contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS));
  }

  @Test
  public void itAddsRangeInfoToProgressBar_missingMinAndMaxValue() {
    AccessibilityBridge accessibilityBridge = setUpBridge();
    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    testSemanticsNode.role = AccessibilityBridge.Role.PROGRESS_BAR.value;
    testSemanticsNode.value = "50";
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals("android.widget.ProgressBar", nodeInfo.getClassName().toString());
    assertNotNull(nodeInfo.getRangeInfo());
    assertEquals(Float.NEGATIVE_INFINITY, nodeInfo.getRangeInfo().getMin(), 1e-4f);
    assertEquals(Float.POSITIVE_INFINITY, nodeInfo.getRangeInfo().getMax(), 1e-4f);
    assertEquals(50.0f, nodeInfo.getRangeInfo().getCurrent(), 1e-4f);
  }

  @Test
  public void itAddsRangeInfoToProgressBar_unparseableMinValue() {
    AccessibilityBridge accessibilityBridge = setUpBridge();
    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    testSemanticsNode.role = AccessibilityBridge.Role.PROGRESS_BAR.value;
    testSemanticsNode.value = "50";
    testSemanticsNode.minValue = "a";
    testSemanticsNode.maxValue = "100";
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals("android.widget.ProgressBar", nodeInfo.getClassName().toString());
    assertNotNull(nodeInfo.getRangeInfo());
    assertEquals(Float.NEGATIVE_INFINITY, nodeInfo.getRangeInfo().getMin(), 1e-4f);
    assertEquals(100.0f, nodeInfo.getRangeInfo().getMax(), 1e-4f);
    assertEquals(50.0f, nodeInfo.getRangeInfo().getCurrent(), 1e-4f);
  }

  @Test
  public void itAddsRangeInfoToProgressBar_unparseableMaxValue() {
    AccessibilityBridge accessibilityBridge = setUpBridge();
    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    testSemanticsNode.role = AccessibilityBridge.Role.PROGRESS_BAR.value;
    testSemanticsNode.value = "50";
    testSemanticsNode.minValue = "0";
    testSemanticsNode.maxValue = "a";
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals("android.widget.ProgressBar", nodeInfo.getClassName().toString());
    assertNotNull(nodeInfo.getRangeInfo());
    assertEquals(0.0f, nodeInfo.getRangeInfo().getMin(), 1e-4f);
    assertEquals(Float.POSITIVE_INFINITY, nodeInfo.getRangeInfo().getMax(), 1e-4f);
    assertEquals(50.0f, nodeInfo.getRangeInfo().getCurrent(), 1e-4f);
  }

  @Config(sdk = API_LEVELS.API_34)
  @TargetApi(API_LEVELS.API_34)
  @Test
  public void itAddsRangeInfoToProgressBar_unparseableValue() {
    AccessibilityBridge accessibilityBridge = setUpBridge();
    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    testSemanticsNode.role = AccessibilityBridge.Role.PROGRESS_BAR.value;
    testSemanticsNode.value = "a";
    testSemanticsNode.minValue = "0";
    testSemanticsNode.maxValue = "100";
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals("android.widget.ProgressBar", nodeInfo.getClassName().toString());
    assertNotNull(nodeInfo.getRangeInfo());
    assertEquals(
        AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, nodeInfo.getRangeInfo().getType());
    assertEquals(0.0f, nodeInfo.getRangeInfo().getMin(), 1e-4f);
    assertEquals(0.0f, nodeInfo.getRangeInfo().getMax(), 1e-4f);
    assertEquals(0.0f, nodeInfo.getRangeInfo().getCurrent(), 1e-4f);
  }

  @Config(sdk = API_LEVELS.API_36)
  @TargetApi(API_LEVELS.API_36)
  @Test
  public void itAddsRangeInfoToProgressBar_unparseableValueAPI36() {
    AccessibilityBridge accessibilityBridge = setUpBridge();
    TestSemanticsNode testSemanticsNode = new TestSemanticsNode();
    testSemanticsNode.role = AccessibilityBridge.Role.PROGRESS_BAR.value;
    testSemanticsNode.value = "a";
    testSemanticsNode.minValue = "0";
    testSemanticsNode.maxValue = "100";
    TestSemanticsUpdate testSemanticsUpdate = testSemanticsNode.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals("android.widget.ProgressBar", nodeInfo.getClassName().toString());
    assertNotNull(nodeInfo.getRangeInfo());
    assertEquals(
        AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INDETERMINATE,
        nodeInfo.getRangeInfo().getType());
    assertEquals(0.0f, nodeInfo.getRangeInfo().getMin(), 1e-4f);
    assertEquals(0.0f, nodeInfo.getRangeInfo().getMax(), 1e-4f);
    assertEquals(0.0f, nodeInfo.getRangeInfo().getCurrent(), 1e-4f);
  }

  @Config(sdk = API_LEVELS.API_32)
  @TargetApi(API_LEVELS.API_32)
  @Test
  public void itAddsCollectionItemInfoAPI32() {
    // Testing CollectionItemInfo creation for API 32
    itAddsCollectionItemInfo();
  }

  @Config(sdk = API_LEVELS.API_33)
  @TargetApi(API_LEVELS.API_33)
  @Test
  public void itAddsCollectionItemInfoAPI33() {
    // Testing CollectionItemInfo creation for API 33
    itAddsCollectionItemInfo();
  }

  @Config(sdk = API_LEVELS.API_36)
  @TargetApi(API_LEVELS.API_36)
  @Test
  public void itSetsCheckedStateBasedOnFlagsCorrectly() {
    AccessibilityBridge accessibilityBridge = setUpBridge();

    TestSemanticsNode node = new TestSemanticsNode();
    node.addFlag(AccessibilityBridge.Flag.HAS_CHECKED_STATE);
    TestSemanticsUpdate testSemanticsUpdate = node.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals(AccessibilityNodeInfo.CHECKED_STATE_FALSE, nodeInfo.getChecked());

    node = new TestSemanticsNode();
    node.addFlag(AccessibilityBridge.Flag.HAS_CHECKED_STATE);
    node.addFlag(AccessibilityBridge.Flag.IS_CHECKED);
    testSemanticsUpdate = node.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals(AccessibilityNodeInfo.CHECKED_STATE_TRUE, nodeInfo.getChecked());

    node = new TestSemanticsNode();
    node.addFlag(AccessibilityBridge.Flag.HAS_CHECKED_STATE);
    node.addFlag(AccessibilityBridge.Flag.IS_CHECK_STATE_MIXED);
    testSemanticsUpdate = node.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals(AccessibilityNodeInfo.CHECKED_STATE_PARTIAL, nodeInfo.getChecked());

    node = new TestSemanticsNode();
    node.addFlag(AccessibilityBridge.Flag.HAS_TOGGLED_STATE);
    testSemanticsUpdate = node.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals(AccessibilityNodeInfo.CHECKED_STATE_FALSE, nodeInfo.getChecked());

    node = new TestSemanticsNode();
    node.addFlag(AccessibilityBridge.Flag.HAS_TOGGLED_STATE);
    node.addFlag(AccessibilityBridge.Flag.IS_TOGGLED);
    testSemanticsUpdate = node.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals(AccessibilityNodeInfo.CHECKED_STATE_TRUE, nodeInfo.getChecked());
  }

  @Config(sdk = API_LEVELS.API_36)
  @TargetApi(API_LEVELS.API_36)
  @Test
  public void itSetsExpandedStateBasedOnFlagsCorrectly() {
    AccessibilityBridge accessibilityBridge = setUpBridge();

    TestSemanticsNode node = new TestSemanticsNode();
    TestSemanticsUpdate testSemanticsUpdate = node.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals(AccessibilityNodeInfo.EXPANDED_STATE_UNDEFINED, nodeInfo.getExpandedState());

    node = new TestSemanticsNode();
    node.addFlag(AccessibilityBridge.Flag.HAS_EXPANDED_STATE);
    testSemanticsUpdate = node.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals(AccessibilityNodeInfo.EXPANDED_STATE_COLLAPSED, nodeInfo.getExpandedState());

    node = new TestSemanticsNode();
    node.addFlag(AccessibilityBridge.Flag.HAS_EXPANDED_STATE);
    node.addFlag(AccessibilityBridge.Flag.IS_EXPANDED);
    testSemanticsUpdate = node.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertEquals(AccessibilityNodeInfo.EXPANDED_STATE_FULL, nodeInfo.getExpandedState());
  }

  @Config(sdk = API_LEVELS.API_36)
  @TargetApi(API_LEVELS.API_36)
  @Test
  public void itAddsExpandActionBasedOnFlagsCorrectly() {
    AccessibilityBridge accessibilityBridge = setUpBridge();

    TestSemanticsNode node = new TestSemanticsNode();
    TestSemanticsUpdate testSemanticsUpdate = node.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    List<AccessibilityNodeInfo.AccessibilityAction> actions = nodeInfo.getActionList();
    assertFalse(actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND));

    node = new TestSemanticsNode();
    node.addFlag(AccessibilityBridge.Flag.HAS_EXPANDED_STATE);
    testSemanticsUpdate = node.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    actions = nodeInfo.getActionList();
    assertFalse(actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND));

    node = new TestSemanticsNode();
    node.addFlag(AccessibilityBridge.Flag.HAS_EXPANDED_STATE);
    node.addAction(AccessibilityBridge.Action.EXPAND);
    testSemanticsUpdate = node.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    actions = nodeInfo.getActionList();
    assertTrue(actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND));
  }

  @Config(sdk = API_LEVELS.API_36)
  @TargetApi(API_LEVELS.API_36)
  @Test
  public void itCanPerformExpand() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ null,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ null,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ null,
            /* platformViewsAccessibilityDelegate= */ null);

    TestSemanticsNode node = new TestSemanticsNode();
    node.addFlag(AccessibilityBridge.Flag.HAS_EXPANDED_STATE);
    node.addAction(AccessibilityBridge.Action.EXPAND);
    TestSemanticsUpdate testSemanticsUpdate = node.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    accessibilityBridge.performAction(0, AccessibilityNodeInfo.ACTION_EXPAND, null);
    verify(mockChannel).dispatchSemanticsAction(0, AccessibilityBridge.Action.EXPAND);
  }

  @Config(sdk = API_LEVELS.API_36)
  @TargetApi(API_LEVELS.API_36)
  @Test
  public void itAddsCollapseActionBasedOnFlagsCorrectly() {
    AccessibilityBridge accessibilityBridge = setUpBridge();

    TestSemanticsNode node = new TestSemanticsNode();
    TestSemanticsUpdate testSemanticsUpdate = node.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    AccessibilityNodeInfo nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    List<AccessibilityNodeInfo.AccessibilityAction> actions = nodeInfo.getActionList();
    assertFalse(actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE));

    node = new TestSemanticsNode();
    node.addFlag(AccessibilityBridge.Flag.HAS_EXPANDED_STATE);
    node.addFlag(AccessibilityBridge.Flag.IS_EXPANDED);
    testSemanticsUpdate = node.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    actions = nodeInfo.getActionList();
    assertFalse(actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE));

    node = new TestSemanticsNode();
    node.addFlag(AccessibilityBridge.Flag.HAS_EXPANDED_STATE);
    node.addFlag(AccessibilityBridge.Flag.IS_EXPANDED);
    node.addAction(AccessibilityBridge.Action.COLLAPSE);
    testSemanticsUpdate = node.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    nodeInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    actions = nodeInfo.getActionList();
    assertTrue(actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE));
  }

  @Config(sdk = API_LEVELS.API_36)
  @TargetApi(API_LEVELS.API_36)
  @Test
  public void itCanPerformCollapse() {
    AccessibilityChannel mockChannel = mock(AccessibilityChannel.class);
    AccessibilityBridge accessibilityBridge =
        setUpBridge(
            /* rootAccessibilityView= */ null,
            /* accessibilityChannel= */ mockChannel,
            /* accessibilityManager= */ null,
            /* contentResolver= */ null,
            /* accessibilityViewEmbedder= */ null,
            /* platformViewsAccessibilityDelegate= */ null);

    TestSemanticsNode node = new TestSemanticsNode();
    node.addFlag(AccessibilityBridge.Flag.HAS_EXPANDED_STATE);
    node.addFlag(AccessibilityBridge.Flag.IS_EXPANDED);
    node.addAction(AccessibilityBridge.Action.COLLAPSE);
    TestSemanticsUpdate testSemanticsUpdate = node.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    accessibilityBridge.performAction(0, AccessibilityNodeInfo.ACTION_COLLAPSE, null);
    verify(mockChannel).dispatchSemanticsAction(0, AccessibilityBridge.Action.COLLAPSE);
  }

  @Config(sdk = API_LEVELS.API_36)
  @TargetApi(API_LEVELS.API_36)
  @Test
  public void itSendsContentChangeCheckedOnAPI36ForCheckbox() {
    View mockRootView = mock(View.class);
    Context mockContext = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(mockContext);
    when(mockContext.getPackageName()).thenReturn("test");
    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    when(mockManager.isEnabled()).thenReturn(true);

    AccessibilityBridge accessibilityBridge = setUpBridge(mockRootView, mockManager, null);

    TestSemanticsNode node = new TestSemanticsNode();
    node.id = 0;
    node.addFlag(AccessibilityBridge.Flag.HAS_CHECKED_STATE);
    node.toUpdate().sendUpdateToBridge(accessibilityBridge);

    // Change checked state: false -> true
    node.addFlag(AccessibilityBridge.Flag.IS_CHECKED);
    node.toUpdate().sendUpdateToBridge(accessibilityBridge);

    // Change checked state: true -> false
    node.flags &= ~AccessibilityBridge.Flag.IS_CHECKED.value;
    node.toUpdate().sendUpdateToBridge(accessibilityBridge);

    ArgumentCaptor<AccessibilityEvent> eventCaptor =
        ArgumentCaptor.forClass(AccessibilityEvent.class);
    verify(mockParent, atLeastOnce())
        .requestSendAccessibilityEvent(eq(mockRootView), eventCaptor.capture());

    int foundCount = 0;
    for (AccessibilityEvent event : eventCaptor.getAllValues()) {
      if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
          && event.getContentChangeTypes() == AccessibilityEvent.CONTENT_CHANGE_TYPE_CHECKED) {
        foundCount++;
      }
    }
    assertEquals(
        "CONTENT_CHANGE_TYPE_CHECKED event should be sent twice for checkbox transitions",
        2,
        foundCount);
  }

  @Config(sdk = API_LEVELS.API_36)
  @TargetApi(API_LEVELS.API_36)
  @Test
  public void itSendsContentChangeCheckedOnAPI36ForToggle() {
    View mockRootView = mock(View.class);
    Context mockContext = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(mockContext);
    when(mockContext.getPackageName()).thenReturn("test");
    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    when(mockManager.isEnabled()).thenReturn(true);

    AccessibilityBridge accessibilityBridge = setUpBridge(mockRootView, mockManager, null);

    TestSemanticsNode node = new TestSemanticsNode();
    node.id = 0;
    node.addFlag(AccessibilityBridge.Flag.HAS_TOGGLED_STATE);
    node.toUpdate().sendUpdateToBridge(accessibilityBridge);

    // Change toggle state: false -> true
    node.addFlag(AccessibilityBridge.Flag.IS_TOGGLED);
    node.toUpdate().sendUpdateToBridge(accessibilityBridge);

    // Change toggle state: true -> false
    node.flags &= ~AccessibilityBridge.Flag.IS_TOGGLED.value;
    node.toUpdate().sendUpdateToBridge(accessibilityBridge);

    ArgumentCaptor<AccessibilityEvent> eventCaptor =
        ArgumentCaptor.forClass(AccessibilityEvent.class);
    verify(mockParent, atLeastOnce())
        .requestSendAccessibilityEvent(eq(mockRootView), eventCaptor.capture());

    int foundCount = 0;
    for (AccessibilityEvent event : eventCaptor.getAllValues()) {
      if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
          && event.getContentChangeTypes() == AccessibilityEvent.CONTENT_CHANGE_TYPE_CHECKED) {
        foundCount++;
      }
    }
    assertEquals(
        "CONTENT_CHANGE_TYPE_CHECKED event should be sent twice for toggle transitions",
        2,
        foundCount);
  }

  @Config(sdk = API_LEVELS.API_36)
  @TargetApi(API_LEVELS.API_36)
  @Test
  public void itSendsContentChangeCheckedOnAPI36ForMixedState() {
    View mockRootView = mock(View.class);
    Context mockContext = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(mockContext);
    when(mockContext.getPackageName()).thenReturn("test");
    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    when(mockManager.isEnabled()).thenReturn(true);

    AccessibilityBridge accessibilityBridge = setUpBridge(mockRootView, mockManager, null);

    TestSemanticsNode node = new TestSemanticsNode();
    node.id = 0;
    node.addFlag(AccessibilityBridge.Flag.HAS_CHECKED_STATE);
    node.toUpdate().sendUpdateToBridge(accessibilityBridge);

    // Change to mixed state: false -> mixed
    node.addFlag(AccessibilityBridge.Flag.IS_CHECK_STATE_MIXED);
    node.toUpdate().sendUpdateToBridge(accessibilityBridge);

    // Change back from mixed state: mixed -> false
    node.flags &= ~AccessibilityBridge.Flag.IS_CHECK_STATE_MIXED.value;
    node.toUpdate().sendUpdateToBridge(accessibilityBridge);

    ArgumentCaptor<AccessibilityEvent> eventCaptor =
        ArgumentCaptor.forClass(AccessibilityEvent.class);
    verify(mockParent, atLeastOnce())
        .requestSendAccessibilityEvent(eq(mockRootView), eventCaptor.capture());

    int foundCount = 0;
    for (AccessibilityEvent event : eventCaptor.getAllValues()) {
      if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
          && event.getContentChangeTypes() == AccessibilityEvent.CONTENT_CHANGE_TYPE_CHECKED) {
        foundCount++;
      }
    }
    assertEquals(
        "CONTENT_CHANGE_TYPE_CHECKED event should be sent twice for mixed state transitions",
        2,
        foundCount);
  }

  @Config(sdk = API_LEVELS.API_36)
  @TargetApi(API_LEVELS.API_36)
  @Test
  public void itSendsContentChangeExpandedOnAPI36() {
    View mockRootView = mock(View.class);
    Context mockContext = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(mockContext);
    when(mockContext.getPackageName()).thenReturn("test");
    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    when(mockManager.isEnabled()).thenReturn(true);

    AccessibilityBridge accessibilityBridge = setUpBridge(mockRootView, mockManager, null);

    // Initially expanded state: false
    TestSemanticsNode node = new TestSemanticsNode();
    node.id = 0;
    node.addFlag(AccessibilityBridge.Flag.HAS_EXPANDED_STATE);
    node.toUpdate().sendUpdateToBridge(accessibilityBridge);

    // Change expanded state: false -> true
    node.addFlag(AccessibilityBridge.Flag.IS_EXPANDED);
    node.toUpdate().sendUpdateToBridge(accessibilityBridge);

    // Change expanded state: true -> false
    node.flags &= ~AccessibilityBridge.Flag.IS_EXPANDED.value;
    node.toUpdate().sendUpdateToBridge(accessibilityBridge);

    ArgumentCaptor<AccessibilityEvent> eventCaptor =
        ArgumentCaptor.forClass(AccessibilityEvent.class);
    verify(mockParent, atLeastOnce())
        .requestSendAccessibilityEvent(eq(mockRootView), eventCaptor.capture());

    int foundCount = 0;
    for (AccessibilityEvent event : eventCaptor.getAllValues()) {
      if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
          && event.getContentChangeTypes() == AccessibilityEvent.CONTENT_CHANGE_TYPE_EXPANDED) {
        foundCount++;
      }
    }
    assertEquals(
        "CONTENT_CHANGE_TYPE_EXPANDED event should be sent twice for expansion state changes",
        3,
        foundCount);
  }

  @Config(sdk = API_LEVELS.API_35)
  @TargetApi(API_LEVELS.API_35)
  @Test
  public void itDoesNotSendContentChangeExpandedOnAPI35() {
    View mockRootView = mock(View.class);
    Context mockContext = mock(Context.class);
    when(mockRootView.getContext()).thenReturn(mockContext);
    when(mockContext.getPackageName()).thenReturn("test");
    ViewParent mockParent = mock(ViewParent.class);
    when(mockRootView.getParent()).thenReturn(mockParent);
    AccessibilityManager mockManager = mock(AccessibilityManager.class);
    when(mockManager.isEnabled()).thenReturn(true);

    AccessibilityBridge accessibilityBridge = setUpBridge(mockRootView, mockManager, null);

    TestSemanticsNode node = new TestSemanticsNode();
    node.id = 0;
    node.addFlag(AccessibilityBridge.Flag.HAS_EXPANDED_STATE);
    node.toUpdate().sendUpdateToBridge(accessibilityBridge);

    // Change expanded state: false -> true
    node.addFlag(AccessibilityBridge.Flag.IS_EXPANDED);
    node.toUpdate().sendUpdateToBridge(accessibilityBridge);

    ArgumentCaptor<AccessibilityEvent> eventCaptor =
        ArgumentCaptor.forClass(AccessibilityEvent.class);
    verify(mockParent, atLeastOnce())
        .requestSendAccessibilityEvent(eq(mockRootView), eventCaptor.capture());

    int foundCount = 0;
    for (AccessibilityEvent event : eventCaptor.getAllValues()) {
      if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
          && event.getContentChangeTypes() == AccessibilityEvent.CONTENT_CHANGE_TYPE_EXPANDED) {
        foundCount++;
      }
    }
    assertEquals("CONTENT_CHANGE_TYPE_EXPANDED event should not be sent on API 35", 0, foundCount);
  }

  @Config(sdk = API_LEVELS.API_28)
  @TargetApi(API_LEVELS.API_28)
  @Test
  public void itSetsHeadingWhenHeadingLevelIsPositive() {
    AccessibilityBridge accessibilityBridge = setUpBridge();

    TestSemanticsNode headingNode = new TestSemanticsNode();
    headingNode.headingLevel = 2;
    headingNode.label = "Level 2 heading";
    TestSemanticsUpdate headingUpdate = headingNode.toUpdate();
    headingUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo headingInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertTrue(headingInfo.isHeading());
  }

  @Config(sdk = API_LEVELS.API_28)
  @TargetApi(API_LEVELS.API_28)
  @Test
  public void itDoesNotSetHeadingWhenHeadingLevelIsZero() {
    AccessibilityBridge accessibilityBridge = setUpBridge();

    TestSemanticsNode nonHeadingNode = new TestSemanticsNode();
    nonHeadingNode.headingLevel = 0;
    nonHeadingNode.label = "Not a heading";
    TestSemanticsUpdate nonHeadingUpdate = nonHeadingNode.toUpdate();
    nonHeadingUpdate.sendUpdateToBridge(accessibilityBridge);
    AccessibilityNodeInfo nonHeadingInfo = accessibilityBridge.createAccessibilityNodeInfo(0);
    assertFalse(nonHeadingInfo.isHeading());
  }

  AccessibilityBridge setUpBridge() {
    return setUpBridge(null, null, null, null, null, null);
  }

  AccessibilityBridge setUpBridge(
      View rootAccessibilityView,
      AccessibilityManager accessibilityManager,
      AccessibilityViewEmbedder accessibilityViewEmbedder) {
    return setUpBridge(
        rootAccessibilityView, null, accessibilityManager, null, accessibilityViewEmbedder, null);
  }

  AccessibilityBridge setUpBridge(
      View rootAccessibilityView,
      AccessibilityChannel accessibilityChannel,
      AccessibilityManager accessibilityManager,
      ContentResolver contentResolver,
      AccessibilityViewEmbedder accessibilityViewEmbedder,
      PlatformViewsAccessibilityDelegate platformViewsAccessibilityDelegate) {
    if (rootAccessibilityView == null) {
      rootAccessibilityView = mock(View.class);
      Context context = mock(Context.class);
      when(rootAccessibilityView.getContext()).thenReturn(context);
      when(context.getPackageName()).thenReturn("test");
    }
    if (accessibilityChannel == null) {
      accessibilityChannel = mock(AccessibilityChannel.class);
    }
    if (accessibilityManager == null) {
      accessibilityManager = mock(AccessibilityManager.class);
    }
    if (contentResolver == null) {
      contentResolver = mock(ContentResolver.class);
    }
    if (accessibilityViewEmbedder == null) {
      accessibilityViewEmbedder = mock(AccessibilityViewEmbedder.class);
    }
    if (platformViewsAccessibilityDelegate == null) {
      platformViewsAccessibilityDelegate = mock(PlatformViewsAccessibilityDelegate.class);
    }
    return new AccessibilityBridge(
        rootAccessibilityView,
        accessibilityChannel,
        accessibilityManager,
        contentResolver,
        accessibilityViewEmbedder,
        platformViewsAccessibilityDelegate);
  }

  @Config(sdk = API_LEVELS.API_36)
  @TargetApi(API_LEVELS.API_36)
  @SuppressWarnings("deprecation")
  @Test
  public void itLogsDeprecationWarningForAnnounceOnAPI36() {
    AccessibilityBridge accessibilityBridge = setUpBridge();
    try (MockedStatic<io.flutter.Log> mockedLog = mockStatic(io.flutter.Log.class)) {
      accessibilityBridge.accessibilityMessageHandler.announce("Hello");

      mockedLog.verify(
          () ->
              io.flutter.Log.w(
                  eq("AccessibilityBridge"),
                  contains(
                      "Using AnnounceSemanticsEvent for accessibility is deprecated on Android")));
    }
  }

  @Config(sdk = API_LEVELS.API_35)
  @TargetApi(API_LEVELS.API_35)
  @SuppressWarnings("deprecation")
  @Test
  public void itDoesNotLogDeprecationWarningForAnnounceOnAPI35() {
    AccessibilityBridge accessibilityBridge = setUpBridge();
    try (MockedStatic<io.flutter.Log> mockedLog = mockStatic(io.flutter.Log.class)) {
      accessibilityBridge.accessibilityMessageHandler.announce("Hello");

      mockedLog.verify(
          () -> io.flutter.Log.w(eq("AccessibilityBridge"), any(String.class)), never());
    }
  }

  @Test
  public void itSetsVisibleToUserFalseForHiddenNodes() {
    AccessibilityBridge accessibilityBridge = setUpBridge();

    TestSemanticsNode root = new TestSemanticsNode();
    root.id = 0;

    TestSemanticsNode visibleNode = new TestSemanticsNode();
    visibleNode.id = 1;
    visibleNode.label = "visible";
    root.children.add(visibleNode);

    TestSemanticsNode hiddenNode = new TestSemanticsNode();
    hiddenNode.id = 2;
    hiddenNode.label = "hidden";
    hiddenNode.addFlag(AccessibilityBridge.Flag.IS_HIDDEN);
    root.children.add(hiddenNode);

    TestSemanticsUpdate testSemanticsUpdate = root.toUpdate();
    testSemanticsUpdate.sendUpdateToBridge(accessibilityBridge);

    AccessibilityNodeInfo visibleNodeInfo = accessibilityBridge.createAccessibilityNodeInfo(1);
    assertTrue(visibleNodeInfo.isVisibleToUser());

    AccessibilityNodeInfo hiddenNodeInfo = accessibilityBridge.createAccessibilityNodeInfo(2);
    assertFalse(hiddenNodeInfo.isVisibleToUser());
  }

  /// The encoding for semantics is described in platform_view_android.cc
  class TestSemanticsUpdate {
    TestSemanticsUpdate(ByteBuffer buffer, String[] strings, ByteBuffer[] stringAttributeArgs) {
      this.buffer = buffer;
      this.strings = strings;
      this.stringAttributeArgs = stringAttributeArgs;
    }

    void sendUpdateToBridge(AccessibilityBridge bridge) {
      bridge.updateSemantics(buffer, strings, stringAttributeArgs);
    }

    final ByteBuffer buffer;
    final String[] strings;
    final ByteBuffer[] stringAttributeArgs;
  }

  enum TestStringAttributeType {
    SPELLOUT(0),
    LOCALE(1);

    private final int value;

    TestStringAttributeType(int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }
  }

  class TestStringAttribute {
    int start;
    int end;
    TestStringAttributeType type;
  }

  class TestStringAttributeSpellOut extends TestStringAttribute {}

  class TestStringAttributeLocale extends TestStringAttribute {
    String locale;
  }

  class TestSemanticsNode {
    TestSemanticsNode() {}

    void addFlag(AccessibilityBridge.Flag flag) {
      flags |= flag.value;
    }

    void addAction(AccessibilityBridge.Action action) {
      actions |= action.value;
    }

    // These fields are declared in the order they should be
    // encoded.
    int id = 0;
    long flags = 0;
    int actions = 0;
    int maxValueLength = 0;
    int currentValueLength = 0;
    int textSelectionBase = 0;
    int textSelectionExtent = 0;
    int platformViewId = -1;
    int scrollChildren = 0;
    int scrollIndex = 0;
    int traversalParent = -1;
    float scrollPosition = 0.0f;
    float scrollExtentMax = 0.0f;
    float scrollExtentMin = 0.0f;
    String identifier = null;
    String label = null;
    List<TestStringAttribute> labelAttributes;
    String value = null;
    List<TestStringAttribute> valueAttributes;
    String increasedValue = null;
    List<TestStringAttribute> increasedValueAttributes;
    String decreasedValue = null;
    List<TestStringAttribute> decreasedValueAttributes;
    String hint = null;
    List<TestStringAttribute> hintAttributes;
    String tooltip = null;
    String linkUrl = null;
    String locale = null;
    String minValue = null;
    String maxValue = null;
    int role = 0;
    int headingLevel = 0;
    int textDirection = 0;
    float left = 0.0f;
    float top = 0.0f;
    float right = 0.0f;
    float bottom = 0.0f;
    float[] transform =
        new float[] {
          1.0f, 0.0f, 0.0f, 0.0f,
          0.0f, 1.0f, 0.0f, 0.0f,
          0.0f, 0.0f, 1.0f, 0.0f,
          0.0f, 0.0f, 0.0f, 1.0f
        };
    float[] hitTestTransform =
        new float[] {
          1.0f, 0.0f, 0.0f, 0.0f,
          0.0f, 1.0f, 0.0f, 0.0f,
          0.0f, 0.0f, 1.0f, 0.0f,
          0.0f, 0.0f, 0.0f, 1.0f
        };

    final List<TestSemanticsNode> children = new ArrayList<TestSemanticsNode>();
    final List<Integer> phantomChildIds = new ArrayList<Integer>();

    public void addChild(TestSemanticsNode child) {
      children.add(child);
    }
    // custom actions not supported.

    TestSemanticsUpdate toUpdate() {
      ArrayList<String> strings = new ArrayList<String>();
      ByteBuffer bytes = ByteBuffer.allocate(4096);
      ArrayList<ByteBuffer> stringAttributeArgs = new ArrayList<ByteBuffer>();
      addToBuffer(bytes, strings, stringAttributeArgs);
      bytes.flip();
      return new TestSemanticsUpdate(
          bytes,
          strings.toArray(new String[strings.size()]),
          stringAttributeArgs.toArray(new ByteBuffer[stringAttributeArgs.size()]));
    }

    protected void addToBuffer(
        ByteBuffer bytes, ArrayList<String> strings, ArrayList<ByteBuffer> stringAttributeArgs) {
      bytes.putInt(id);
      bytes.putLong(flags);
      bytes.putInt(actions);
      bytes.putInt(maxValueLength);
      bytes.putInt(currentValueLength);
      bytes.putInt(textSelectionBase);
      bytes.putInt(textSelectionExtent);
      bytes.putInt(platformViewId);
      bytes.putInt(scrollChildren);
      bytes.putInt(scrollIndex);
      bytes.putInt(traversalParent);
      bytes.putFloat(scrollPosition);
      bytes.putFloat(scrollExtentMax);
      bytes.putFloat(scrollExtentMin);
      bytes.putInt(role);
      if (identifier == null) {
        bytes.putInt(-1);
      } else {
        strings.add(identifier);
        bytes.putInt(strings.size() - 1);
      }
      updateString(label, labelAttributes, bytes, strings, stringAttributeArgs);
      updateString(value, valueAttributes, bytes, strings, stringAttributeArgs);
      updateString(increasedValue, increasedValueAttributes, bytes, strings, stringAttributeArgs);
      updateString(decreasedValue, decreasedValueAttributes, bytes, strings, stringAttributeArgs);
      updateString(hint, hintAttributes, bytes, strings, stringAttributeArgs);
      if (tooltip == null) {
        bytes.putInt(-1);
      } else {
        strings.add(tooltip);
        bytes.putInt(strings.size() - 1);
      }
      if (linkUrl == null) {
        bytes.putInt(-1);
      } else {
        strings.add(linkUrl);
        bytes.putInt(strings.size() - 1);
      }
      if (locale == null) {
        bytes.putInt(-1);
      } else {
        strings.add(locale);
        bytes.putInt(strings.size() - 1);
      }
      if (minValue == null) {
        bytes.putInt(-1);
      } else {
        strings.add(minValue);
        bytes.putInt(strings.size() - 1);
      }
      if (maxValue == null) {
        bytes.putInt(-1);
      } else {
        strings.add(maxValue);
        bytes.putInt(strings.size() - 1);
      }
      bytes.putInt(headingLevel);
      bytes.putInt(textDirection);
      bytes.putFloat(left);
      bytes.putFloat(top);
      bytes.putFloat(right);
      bytes.putFloat(bottom);
      // transform.
      for (int i = 0; i < 16; i++) {
        bytes.putFloat(transform[i]);
      }
      // hitTestTransform.
      for (int i = 0; i < 16; i++) {
        bytes.putFloat(hitTestTransform[i]);
      }
      // children in traversal order.
      bytes.putInt(children.size() + phantomChildIds.size());
      for (TestSemanticsNode node : children) {
        bytes.putInt(node.id);
      }
      for (int phantomId : phantomChildIds) {
        bytes.putInt(phantomId);
      }
      // children in hit test order.
      bytes.putInt(children.size() + phantomChildIds.size());
      for (TestSemanticsNode node : children) {
        bytes.putInt(node.id);
      }
      for (int phantomId : phantomChildIds) {
        bytes.putInt(phantomId);
      }
      // custom actions
      bytes.putInt(0);
      // child nodes
      for (TestSemanticsNode node : children) {
        node.addToBuffer(bytes, strings, stringAttributeArgs);
      }
    }
  }

  static void updateString(
      String value,
      List<TestStringAttribute> attributes,
      ByteBuffer bytes,
      ArrayList<String> strings,
      ArrayList<ByteBuffer> stringAttributeArgs) {
    if (value == null) {
      bytes.putInt(-1);
    } else {
      strings.add(value);
      bytes.putInt(strings.size() - 1);
    }
    // attributes
    if (attributes == null || attributes.isEmpty()) {
      bytes.putInt(-1);
      return;
    }
    bytes.putInt(attributes.size());
    for (TestStringAttribute attribute : attributes) {
      bytes.putInt(attribute.start);
      bytes.putInt(attribute.end);
      bytes.putInt(attribute.type.getValue());
      switch (attribute.type) {
        case SPELLOUT:
          bytes.putInt(-1);
          break;
        case LOCALE:
          bytes.putInt(stringAttributeArgs.size());
          TestStringAttributeLocale localeAttribute = (TestStringAttributeLocale) attribute;
          stringAttributeArgs.add(StandardCharsets.UTF_8.encode(localeAttribute.locale));
          break;
      }
    }
  }
}
