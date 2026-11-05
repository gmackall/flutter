// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

import '../system_context_menu_utils.dart';
import '../widgets/clipboard_utils.dart';
import 'text_input_utils.dart';

void main() {
  final TestWidgetsFlutterBinding binding = TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() async {
    final mockClipboard = MockClipboard();
    TestWidgetsFlutterBinding.ensureInitialized().defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      mockClipboard.handleMethodCall,
    );
    // Fill the clipboard so that the Paste option is available in the text
    // selection menu.
    await Clipboard.setData(const ClipboardData(text: 'Clipboard data'));
  });

  tearDown(() {
    TestWidgetsFlutterBinding.ensureInitialized().defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      null,
    );
  });

  test('showing and hiding one controller', () {
    // Create an active connection, which is required to show the system menu.
    final client = FakeTextInputClient(const TextEditingValue(text: 'test1'));
    final TextInputConnection connection = TextInput.attach(client, client.configuration);
    addTearDown(() {
      connection.close();
    });

    final targetRects = <Map<String, double>>[];
    var hideCount = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (MethodCall methodCall) async {
        switch (methodCall.method) {
          case 'ContextMenu.showSystemContextMenu':
            final arguments = methodCall.arguments as Map<String, dynamic>;
            final untypedTargetRect = arguments['targetRect'] as Map<String, dynamic>;
            final Map<String, double> lastTargetRect = untypedTargetRect.map((
              String key,
              dynamic value,
            ) {
              return MapEntry<String, double>(key, value as double);
            });
            targetRects.add(lastTargetRect);
          case 'ContextMenu.hideSystemContextMenu':
            hideCount += 1;
        }
        return;
      },
    );
    addTearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      );
    });

    final controller = SystemContextMenuController();
    addTearDown(() {
      controller.dispose();
    });

    expect(targetRects, isEmpty);
    expect(hideCount, 0);
    expect(controller.isVisible, isFalse);

    // Showing calls the platform.
    const rect1 = Rect.fromLTWH(0.0, 0.0, 100.0, 100.0);
    final items = <SystemContextMenuItemData>[const SystemContextMenuItemDataCopy()];
    controller.showWithItems(rect1, items);
    expect(targetRects, hasLength(1));
    expect(targetRects.last['x'], rect1.left);
    expect(targetRects.last['y'], rect1.top);
    expect(targetRects.last['width'], rect1.width);
    expect(targetRects.last['height'], rect1.height);

    // Showing the same thing again does nothing.
    controller.showWithItems(rect1, items);
    expect(controller.isVisible, isTrue);
    expect(targetRects, hasLength(1));

    // Showing a new rect calls the platform.
    const rect2 = Rect.fromLTWH(1.0, 1.0, 200.0, 200.0);
    controller.showWithItems(rect2, items);
    expect(targetRects, hasLength(2));
    expect(targetRects.last['x'], rect2.left);
    expect(targetRects.last['y'], rect2.top);
    expect(targetRects.last['width'], rect2.width);
    expect(targetRects.last['height'], rect2.height);

    // Hiding calls the platform.
    controller.hide();
    expect(controller.isVisible, isFalse);
    expect(hideCount, 1);

    // Hiding again does nothing.
    controller.hide();
    expect(controller.isVisible, isFalse);
    expect(hideCount, 1);

    // Showing the last shown rect calls the platform.
    controller.showWithItems(rect2, items);
    expect(controller.isVisible, isTrue);
    expect(targetRects, hasLength(3));
    expect(targetRects.last['x'], rect2.left);
    expect(targetRects.last['y'], rect2.top);
    expect(targetRects.last['width'], rect2.width);
    expect(targetRects.last['height'], rect2.height);

    controller.hide();
    expect(controller.isVisible, isFalse);
    expect(hideCount, 2);
  });

  test('the system can hide the menu with handleSystemHide', () async {
    // Create an active connection, which is required to show the system menu.
    final client = FakeTextInputClient(const TextEditingValue(text: 'test1'));
    final TextInputConnection connection = TextInput.attach(client, client.configuration);
    addTearDown(() {
      connection.close();
    });

    final targetRects = <Map<String, double>>[];
    var hideCount = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (MethodCall methodCall) async {
        switch (methodCall.method) {
          case 'ContextMenu.showSystemContextMenu':
            final arguments = methodCall.arguments as Map<String, dynamic>;
            final untypedTargetRect = arguments['targetRect'] as Map<String, dynamic>;
            final Map<String, double> lastTargetRect = untypedTargetRect.map((
              String key,
              dynamic value,
            ) {
              return MapEntry<String, double>(key, value as double);
            });
            targetRects.add(lastTargetRect);
          case 'ContextMenu.hideSystemContextMenu':
            hideCount += 1;
        }
        return;
      },
    );
    addTearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      );
    });

    var systemHideCount = 0;
    final controller = SystemContextMenuController(
      onSystemHide: () {
        systemHideCount += 1;
      },
    );
    addTearDown(() {
      controller.dispose();
    });

    expect(controller.isVisible, isFalse);
    expect(targetRects, isEmpty);
    expect(hideCount, 0);
    expect(systemHideCount, 0);

    // Showing calls the platform.
    const rect1 = Rect.fromLTWH(0.0, 0.0, 100.0, 100.0);
    final items = <SystemContextMenuItemData>[const SystemContextMenuItemDataCopy()];
    await controller.showWithItems(rect1, items);
    expect(controller.isVisible, isTrue);
    expect(targetRects, hasLength(1));
    expect(targetRects.last['x'], rect1.left);
    expect(targetRects.last['y'], rect1.top);
    expect(targetRects.last['width'], rect1.width);
    expect(targetRects.last['height'], rect1.height);

    // If the system hides the menu, onSystemHide is called.
    final ByteData? messageBytes = const JSONMessageCodec().encodeMessage(<String, dynamic>{
      'method': 'ContextMenu.onDismissSystemContextMenu',
    });
    await binding.defaultBinaryMessenger.handlePlatformMessage(
      'flutter/platform',
      messageBytes,
      (ByteData? data) {},
    );
    expect(controller.isVisible, isFalse);
    expect(hideCount, 0);
    expect(systemHideCount, 1);

    // Hiding does not call the platform, since the menu was already hidden.
    await controller.hide();
    expect(controller.isVisible, isFalse);
    expect(hideCount, 0);
  });

  test('showing a second controller while one is visible is an error', () {
    // Create an active connection, which is required to show the system menu.
    final client = FakeTextInputClient(const TextEditingValue(text: 'test1'));
    final TextInputConnection connection = TextInput.attach(client, client.configuration);
    addTearDown(() {
      connection.close();
    });

    final controller1 = SystemContextMenuController();
    addTearDown(() {
      controller1.dispose();
    });
    expect(controller1.isVisible, isFalse);
    const rect1 = Rect.fromLTWH(0.0, 0.0, 100.0, 100.0);
    final items = <SystemContextMenuItemData>[const SystemContextMenuItemDataCopy()];
    expect(() {
      controller1.showWithItems(rect1, items);
    }, isNot(throwsAssertionError));
    expect(controller1.isVisible, isTrue);

    final controller2 = SystemContextMenuController();
    addTearDown(() {
      controller2.dispose();
    });
    expect(controller1.isVisible, isTrue);
    expect(controller2.isVisible, isFalse);
    const rect2 = Rect.fromLTWH(1.0, 1.0, 200.0, 200.0);
    expect(() {
      controller2.showWithItems(rect2, items);
    }, throwsAssertionError);
    expect(controller1.isVisible, isTrue);
    expect(controller2.isVisible, isFalse);

    controller1.hide();
    expect(controller1.isVisible, isFalse);
    expect(controller2.isVisible, isFalse);
  });

  test('showing and hiding two controllers', () {
    // Create an active connection, which is required to show the system menu.
    final client = FakeTextInputClient(const TextEditingValue(text: 'test1'));
    final TextInputConnection connection = TextInput.attach(client, client.configuration);
    addTearDown(() {
      connection.close();
    });

    final targetRects = <Map<String, double>>[];
    var hideCount = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (MethodCall methodCall) async {
        switch (methodCall.method) {
          case 'ContextMenu.showSystemContextMenu':
            final arguments = methodCall.arguments as Map<String, dynamic>;
            final untypedTargetRect = arguments['targetRect'] as Map<String, dynamic>;
            final Map<String, double> lastTargetRect = untypedTargetRect.map((
              String key,
              dynamic value,
            ) {
              return MapEntry<String, double>(key, value as double);
            });
            targetRects.add(lastTargetRect);
          case 'ContextMenu.hideSystemContextMenu':
            hideCount += 1;
        }
        return;
      },
    );
    addTearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      );
    });

    final controller1 = SystemContextMenuController();
    addTearDown(() {
      controller1.dispose();
    });

    expect(controller1.isVisible, isFalse);
    expect(targetRects, isEmpty);
    expect(hideCount, 0);

    // Showing calls the platform.
    const rect1 = Rect.fromLTWH(0.0, 0.0, 100.0, 100.0);
    final items = <SystemContextMenuItemData>[const SystemContextMenuItemDataCopy()];
    controller1.showWithItems(rect1, items);
    expect(controller1.isVisible, isTrue);
    expect(targetRects, hasLength(1));
    expect(targetRects.last['x'], rect1.left);

    // Hiding calls the platform.
    controller1.hide();
    expect(controller1.isVisible, isFalse);
    expect(hideCount, 1);

    // Showing a new controller calls the platform.
    final controller2 = SystemContextMenuController();
    addTearDown(() {
      controller2.dispose();
    });
    expect(controller2.isVisible, isFalse);
    const rect2 = Rect.fromLTWH(1.0, 1.0, 200.0, 200.0);
    controller2.showWithItems(rect2, items);
    expect(controller1.isVisible, isFalse);
    expect(controller2.isVisible, isTrue);
    expect(targetRects, hasLength(2));
    expect(targetRects.last['x'], rect2.left);
    expect(targetRects.last['y'], rect2.top);
    expect(targetRects.last['width'], rect2.width);
    expect(targetRects.last['height'], rect2.height);

    // Hiding the old controller does nothing.
    controller1.hide();
    expect(controller1.isVisible, isFalse);
    expect(controller2.isVisible, isTrue);
    expect(hideCount, 1);

    // Hiding the new controller calls the platform.
    controller2.hide();
    expect(controller1.isVisible, isFalse);
    expect(controller2.isVisible, isFalse);
    expect(hideCount, 2);
  });

  test('showing a controller with custom items', () {
    // Create an active connection, which is required to show the system menu.
    final client = FakeTextInputClient(const TextEditingValue(text: 'test1'));
    final TextInputConnection connection = TextInput.attach(client, client.configuration);
    addTearDown(() {
      connection.close();
    });

    final itemsReceived = <List<SystemContextMenuItemData>>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (MethodCall methodCall) async {
        switch (methodCall.method) {
          case 'ContextMenu.showSystemContextMenu':
            final arguments = methodCall.arguments as Map<String, dynamic>;
            final untypedItems = arguments['items'] as List<dynamic>;
            final List<SystemContextMenuItemData> lastItems = untypedItems.map((dynamic value) {
              final itemJson = value as Map<String, dynamic>;
              return systemContextMenuItemDataFromJson(itemJson);
            }).toList();
            itemsReceived.add(lastItems);
        }
        return;
      },
    );
    addTearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      );
    });

    final controller = SystemContextMenuController();
    addTearDown(() {
      controller.dispose();
    });

    expect(controller.isVisible, isFalse);

    // Showing calls the platform.
    const rect = Rect.fromLTWH(0.0, 0.0, 100.0, 100.0);
    final items1 = <SystemContextMenuItemData>[
      const SystemContextMenuItemDataCut(),
      const SystemContextMenuItemDataCopy(),
      const SystemContextMenuItemDataPaste(),
      const SystemContextMenuItemDataSelectAll(),
      const SystemContextMenuItemDataSearchWeb(title: 'Special Search'),
      // TODO(justinmc): Support the "custom" item type.
      // https://github.com/flutter/flutter/issues/103163
    ];

    controller.showWithItems(rect, items1);
    expect(controller.isVisible, isTrue);
    expect(itemsReceived, hasLength(1));
    expect(itemsReceived.last, hasLength(items1.length));
    expect(itemsReceived.last, equals(items1));

    // Showing the same thing again does nothing.
    controller.showWithItems(rect, items1);
    expect(controller.isVisible, isTrue);
    expect(itemsReceived, hasLength(1));

    // Showing new items calls the platform.
    final items2 = <SystemContextMenuItemData>[const SystemContextMenuItemDataCut()];
    controller.showWithItems(rect, items2);
    expect(controller.isVisible, isTrue);
    expect(itemsReceived, hasLength(2));
    expect(itemsReceived.last, hasLength(items2.length));
    expect(itemsReceived.last, equals(items2));

    controller.hide();
    expect(controller.isVisible, isFalse);
  });

  test('showing a controller with empty items', () {
    // Create an active connection, which is required to show the system menu.
    final client = FakeTextInputClient(const TextEditingValue(text: 'test1'));
    final TextInputConnection connection = TextInput.attach(client, client.configuration);
    addTearDown(() {
      connection.close();
    });

    final itemsReceived = <List<SystemContextMenuItemData>>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (MethodCall methodCall) async {
        switch (methodCall.method) {
          case 'ContextMenu.showSystemContextMenu':
            final arguments = methodCall.arguments as Map<String, dynamic>;
            final untypedItems = arguments['items'] as List<dynamic>;
            final List<SystemContextMenuItemData> lastItems = untypedItems.map((dynamic value) {
              final itemJson = value as Map<String, dynamic>;
              return systemContextMenuItemDataFromJson(itemJson);
            }).toList();
            itemsReceived.add(lastItems);
        }
        return;
      },
    );
    addTearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      );
    });

    final controller = SystemContextMenuController();
    addTearDown(() {
      controller.dispose();
    });

    expect(controller.isVisible, isFalse);

    const rect = Rect.fromLTWH(0.0, 0.0, 100.0, 100.0);
    final items = <SystemContextMenuItemData>[];

    expect(() {
      controller.showWithItems(rect, items);
    }, throwsAssertionError);
    expect(controller.isVisible, isFalse);
    expect(itemsReceived, hasLength(0));
  });

  testWidgets('showing a controller for an EditableText', (WidgetTester tester) async {
    final textEditingController = TextEditingController(text: 'test');
    final focusNode = FocusNode();
    final key = GlobalKey<EditableTextState>();
    late final WidgetsLocalizations localizations;
    addTearDown(() {
      textEditingController.dispose();
      focusNode.dispose();
    });

    await tester.pumpWidget(
      TestWidgetsApp(
        home: Align(
          alignment: Alignment.topLeft,
          child: SizedBox(
            width: 400,
            child: Builder(
              builder: (BuildContext context) {
                localizations = WidgetsLocalizations.of(context);
                return EditableText(
                  key: key,
                  maxLines: 10,
                  controller: textEditingController,
                  showSelectionHandles: true,
                  autofocus: true,
                  focusNode: focusNode,
                  style: const TextStyle(),
                  cursorColor: const Color(0xFF0000FF),
                  backgroundCursorColor: const Color(0xFF808080),
                  keyboardType: TextInputType.text,
                  textAlign: TextAlign.right,
                  selectionControls: emptyTextSelectionControls,
                );
              },
            ),
          ),
        ),
      ),
    );

    final EditableTextState editableTextState = tester.state<EditableTextState>(
      find.byType(EditableText),
    );
    final List<SystemContextMenuItem> defaultItems = SystemContextMenu.getDefaultItems(
      editableTextState,
    );
    expect(defaultItems, hasLength(4));
    expect(defaultItems[0], const SystemContextMenuItemCut());
    expect(defaultItems[1], const SystemContextMenuItemCopy());
    expect(defaultItems[2], const SystemContextMenuItemPaste());
    expect(defaultItems[3], const SystemContextMenuItemSelectAll());

    final (startGlyphHeight: double startGlyphHeight, endGlyphHeight: double endGlyphHeight) =
        editableTextState.getGlyphHeights();
    final Rect anchor = TextSelectionToolbarAnchors.getSelectionRect(
      editableTextState.renderEditable,
      startGlyphHeight,
      endGlyphHeight,
      editableTextState.renderEditable.getEndpointsForSelection(
        editableTextState.textEditingValue.selection,
      ),
    );
    final List<SystemContextMenuItemData> defaultItemDatas = defaultItems
        .map((SystemContextMenuItem item) => item.getData(localizations))
        .toList();

    expect(defaultItemDatas, isNotEmpty);

    final systemContextMenuController = SystemContextMenuController();
    addTearDown(() {
      systemContextMenuController.dispose();
    });

    expect(systemContextMenuController.isVisible, isFalse);
    await systemContextMenuController.showWithItems(anchor, defaultItemDatas);
    expect(systemContextMenuController.isVisible, isTrue);
  });

  test('custom action callbacks are properly managed', () {
    final client = FakeTextInputClient(const TextEditingValue(text: 'test'));
    final TextInputConnection connection = TextInput.attach(client, client.configuration);
    addTearDown(() {
      connection.close();
    });

    var action1Called = false;
    var action2Called = false;

    final controller = SystemContextMenuController();
    addTearDown(() {
      controller.dispose();
    });

    final items = <SystemContextMenuItemData>[
      SystemContextMenuItemDataCustom(
        title: 'Action 1',
        onPressed: () {
          action1Called = true;
        },
      ),
      SystemContextMenuItemDataCustom(
        title: 'Action 2',
        onPressed: () {
          action2Called = true;
        },
      ),
    ];

    const rect = Rect.fromLTWH(0.0, 0.0, 100.0, 100.0);
    controller.showWithItems(rect, items);

    expect(controller.isVisible, isTrue);

    // Get the actual callback IDs from the items.
    final String callbackId1 = (items[0] as SystemContextMenuItemDataCustom).callbackId;
    final String callbackId2 = (items[1] as SystemContextMenuItemDataCustom).callbackId;

    controller.handleCustomContextMenuAction(callbackId1);
    expect(action1Called, isTrue);
    expect(action2Called, isFalse);

    controller.handleCustomContextMenuAction(callbackId2);
    expect(action1Called, isTrue);
    expect(action2Called, isTrue);

    action1Called = false;
    controller.hide();
    expect(controller.isVisible, isFalse);

    expect(() => controller.handleCustomContextMenuAction(callbackId1), returnsNormally);
    expect(action1Called, isFalse);
  });

  test('multiple controllers handle callbacks independently', () {
    final client1 = FakeTextInputClient(const TextEditingValue(text: 'test1'));
    final TextInputConnection connection1 = TextInput.attach(client1, client1.configuration);
    addTearDown(() {
      connection1.close();
    });

    var controller1ActionCalled = false;
    var controller2ActionCalled = false;

    final controller1 = SystemContextMenuController();
    final controller2 = SystemContextMenuController();
    addTearDown(() {
      controller1.dispose();
      controller2.dispose();
    });

    final items1 = <SystemContextMenuItemData>[
      SystemContextMenuItemDataCustom(
        title: 'Controller 1 Action',
        onPressed: () {
          controller1ActionCalled = true;
        },
      ),
    ];

    final items2 = <SystemContextMenuItemData>[
      SystemContextMenuItemDataCustom(
        title: 'Controller 2 Action',
        onPressed: () {
          controller2ActionCalled = true;
        },
      ),
    ];

    const rect1 = Rect.fromLTWH(0.0, 0.0, 100.0, 100.0);
    const rect2 = Rect.fromLTWH(100.0, 100.0, 100.0, 100.0);

    controller1.showWithItems(rect1, items1);
    expect(controller1.isVisible, isTrue);

    controller1.hide();
    expect(controller1.isVisible, isFalse);

    controller2.showWithItems(rect2, items2);
    expect(controller2.isVisible, isTrue);

    // Get the actual callback ID from the items.
    final String callbackId2 = (items2[0] as SystemContextMenuItemDataCustom).callbackId;

    controller2.handleCustomContextMenuAction(callbackId2);
    expect(controller2ActionCalled, isTrue);
    expect(controller1ActionCalled, isFalse);

    // Get the actual callback ID from controller1's items.
    final String callbackId1 = (items1[0] as SystemContextMenuItemDataCustom).callbackId;

    expect(() => controller1.handleCustomContextMenuAction(callbackId1), returnsNormally);
    expect(controller1ActionCalled, isFalse);
  });

  test('platform dismissal clears callbacks', () {
    final client = FakeTextInputClient(const TextEditingValue(text: 'test'));
    final TextInputConnection connection = TextInput.attach(client, client.configuration);
    addTearDown(() {
      connection.close();
    });

    var actionCalled = false;

    final controller = SystemContextMenuController();
    addTearDown(() {
      controller.dispose();
    });

    final items = <SystemContextMenuItemData>[
      SystemContextMenuItemDataCustom(
        title: 'Test Action',
        onPressed: () {
          actionCalled = true;
        },
      ),
    ];

    const rect = Rect.fromLTWH(0.0, 0.0, 100.0, 100.0);
    controller.showWithItems(rect, items);
    expect(controller.isVisible, isTrue);

    controller.handleSystemHide();
    expect(controller.isVisible, isFalse);

    // Get the actual callback ID from the item.
    final String callbackId = (items[0] as SystemContextMenuItemDataCustom).callbackId;

    expect(() => controller.handleCustomContextMenuAction(callbackId), returnsNormally);
    expect(actionCalled, isFalse);
  });

  test('calling handleCustomContextMenuAction with no systemContextMenuClient', () {
    // Don't create a controller or set any client.
    ServicesBinding.systemContextMenuClient = null;

    expect(() async {
      final ByteData message = const JSONMethodCodec().encodeMethodCall(
        const MethodCall('ContextMenu.onPerformCustomAction', <dynamic>[0, 'test-id']),
      );
      await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.handlePlatformMessage(
        'flutter/platform',
        message,
        (_) {},
      );
    }, returnsNormally);
  });

  test('handleCustomContextMenuAction with non-existent callbackId is safely ignored', () {
    final client = FakeTextInputClient(const TextEditingValue(text: 'test'));
    final TextInputConnection connection = TextInput.attach(client, client.configuration);
    addTearDown(() {
      connection.close();
    });

    final controller = SystemContextMenuController();
    addTearDown(() {
      controller.dispose();
    });

    final items = <SystemContextMenuItemData>[
      SystemContextMenuItemDataCustom(title: 'Test Action', onPressed: () {}),
    ];

    const rect = Rect.fromLTWH(0.0, 0.0, 100.0, 100.0);
    controller.showWithItems(rect, items);
    expect(controller.isVisible, isTrue);

    expect(() => controller.handleCustomContextMenuAction('non-existent-id'), returnsNormally);
  });

  test('handleCustomContextMenuAction after hide clears callbacks', () {
    final client = FakeTextInputClient(const TextEditingValue(text: 'test'));
    final TextInputConnection connection = TextInput.attach(client, client.configuration);
    addTearDown(() {
      connection.close();
    });

    var actionCalled = false;

    final controller = SystemContextMenuController();
    addTearDown(() {
      controller.dispose();
    });

    final items = <SystemContextMenuItemData>[
      SystemContextMenuItemDataCustom(
        title: 'Test Action',
        onPressed: () {
          actionCalled = true;
        },
      ),
    ];

    const rect = Rect.fromLTWH(0.0, 0.0, 100.0, 100.0);
    controller.showWithItems(rect, items);
    expect(controller.isVisible, isTrue);

    final String callbackId = (items[0] as SystemContextMenuItemDataCustom).callbackId;

    // Test that it works before hiding.
    controller.handleCustomContextMenuAction(callbackId);
    expect(actionCalled, isTrue);
    actionCalled = false;

    controller.hide();
    expect(controller.isVisible, isFalse);

    expect(() => controller.handleCustomContextMenuAction(callbackId), returnsNormally);
    expect(actionCalled, isFalse);
  });

  test('showing new menu invalidates old menu callbacks without asserting', () {
    final client = FakeTextInputClient(const TextEditingValue(text: 'test'));
    final TextInputConnection connection = TextInput.attach(client, client.configuration);
    addTearDown(() {
      connection.close();
    });

    var oldActionCalled = false;
    var newActionCalled = false;

    final controller = SystemContextMenuController();
    addTearDown(() {
      controller.dispose();
    });

    // First menu with old action.
    final oldItems = <SystemContextMenuItemData>[
      SystemContextMenuItemDataCustom(
        title: 'Old Action',
        onPressed: () {
          oldActionCalled = true;
        },
      ),
    ];

    const rect1 = Rect.fromLTWH(0.0, 0.0, 100.0, 100.0);
    controller.showWithItems(rect1, oldItems);
    expect(controller.isVisible, isTrue);

    final String oldCallbackId = (oldItems[0] as SystemContextMenuItemDataCustom).callbackId;

    // Show new menu with new action.
    final newItems = <SystemContextMenuItemData>[
      SystemContextMenuItemDataCustom(
        title: 'New Action',
        onPressed: () {
          newActionCalled = true;
        },
      ),
    ];

    const rect2 = Rect.fromLTWH(100.0, 100.0, 100.0, 100.0);
    controller.showWithItems(rect2, newItems);
    expect(controller.isVisible, isTrue);

    final String newCallbackId = (newItems[0] as SystemContextMenuItemDataCustom).callbackId;

    // Superseded callback should be safely ignored without asserting.
    expect(() => controller.handleCustomContextMenuAction(oldCallbackId), returnsNormally);
    expect(oldActionCalled, isFalse);

    // New callback should work.
    controller.handleCustomContextMenuAction(newCallbackId);
    expect(newActionCalled, isTrue);
    expect(oldActionCalled, isFalse);
  });

  test('ignores stale onDismiss and onPerformCustomAction for mismatched clientId', () async {
    final attachedClientIds = <int>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.textInput,
      (MethodCall call) async {
        if (call.method == 'TextInput.setClient') {
          attachedClientIds.add((call.arguments as List<dynamic>)[0] as int);
        }
        return null;
      },
    );
    addTearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.textInput,
        null,
      );
    });

    final client1 = FakeTextInputClient(const TextEditingValue(text: 'field1'));
    final TextInputConnection connection1 = TextInput.attach(client1, client1.configuration);
    final int clientId1 = attachedClientIds.last;

    var hide1Count = 0;
    final controller1 = SystemContextMenuController(onSystemHide: () => hide1Count += 1);
    const rect1 = Rect.fromLTWH(0.0, 0.0, 50.0, 20.0);
    await controller1.showWithItems(rect1, const <SystemContextMenuItemData>[
      SystemContextMenuItemDataCopy(),
    ]);
    expect(controller1.isVisible, isTrue);

    // Construct controller2 before controller1 is dismissed; late dismiss for clientId1
    // must dismiss controller1 without tripping assert(isVisible) on controller2.
    var hide2Count = 0;
    var action2Count = 0;
    final controller2 = SystemContextMenuController(onSystemHide: () => hide2Count += 1);
    addTearDown(() {
      controller1.dispose();
      controller2.dispose();
    });

    final ByteData dismiss1Message = const JSONMethodCodec().encodeMethodCall(
      MethodCall('ContextMenu.onDismissSystemContextMenu', <dynamic>[clientId1]),
    );
    await binding.defaultBinaryMessenger.handlePlatformMessage(
      'flutter/platform',
      dismiss1Message,
      (_) {},
    );
    expect(controller1.isVisible, isFalse);
    expect(hide1Count, 1);
    expect(hide2Count, 0);

    // Attach client2 and show controller2.
    connection1.close();
    final client2 = FakeTextInputClient(const TextEditingValue(text: 'field2'));
    final TextInputConnection connection2 = TextInput.attach(client2, client2.configuration);
    addTearDown(connection2.close);
    final int clientId2 = attachedClientIds.last;
    expect(clientId2, isNot(clientId1));

    final customItem2 = SystemContextMenuItemDataCustom(
      title: 'Action 2',
      onPressed: () => action2Count += 1,
    );
    await controller2.showWithItems(
      const Rect.fromLTWH(10.0, 10.0, 60.0, 20.0),
      <SystemContextMenuItemData>[customItem2],
    );
    expect(controller2.isVisible, isTrue);

    // Stale dismiss and custom action carrying clientId1 must NOT affect controller2.
    await binding.defaultBinaryMessenger.handlePlatformMessage(
      'flutter/platform',
      dismiss1Message,
      (_) {},
    );
    final ByteData staleActionMessage = const JSONMethodCodec().encodeMethodCall(
      MethodCall('ContextMenu.onPerformCustomAction', <dynamic>[clientId1, customItem2.callbackId]),
    );
    await binding.defaultBinaryMessenger.handlePlatformMessage(
      'flutter/platform',
      staleActionMessage,
      (_) {},
    );
    expect(controller2.isVisible, isTrue);
    expect(hide2Count, 0);
    expect(action2Count, 0);

    // Matching clientId2 executes action and dismisses controller2.
    final ByteData validActionMessage = const JSONMethodCodec().encodeMethodCall(
      MethodCall('ContextMenu.onPerformCustomAction', <dynamic>[clientId2, customItem2.callbackId]),
    );
    await binding.defaultBinaryMessenger.handlePlatformMessage(
      'flutter/platform',
      validActionMessage,
      (_) {},
    );
    expect(action2Count, 1);

    final ByteData dismiss2Message = const JSONMethodCodec().encodeMethodCall(
      MethodCall('ContextMenu.onDismissSystemContextMenu', <dynamic>[clientId2]),
    );
    await binding.defaultBinaryMessenger.handlePlatformMessage(
      'flutter/platform',
      dismiss2Message,
      (_) {},
    );
    expect(controller2.isVisible, isFalse);
    expect(hide2Count, 1);
  });

  test('showWithItems sends targetRect and items only and clears controller state without onSystemHide when platform returns false', () async {
    final client = FakeTextInputClient(const TextEditingValue(text: 'hello'));
    final TextInputConnection connection = TextInput.attach(client, client.configuration);
    addTearDown(connection.close);

    final sentPayloads = <Map<String, dynamic>>[];
    bool? nextShowResult;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (MethodCall methodCall) async {
        if (methodCall.method == 'ContextMenu.showSystemContextMenu') {
          sentPayloads.add(methodCall.arguments as Map<String, dynamic>);
          return nextShowResult;
        }
        return null;
      },
    );
    addTearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      );
    });

    var systemHideCount = 0;
    final controller = SystemContextMenuController(onSystemHide: () => systemHideCount += 1);
    addTearDown(controller.dispose);

    // Null response (e.g. iOS or legacy mock) resolves to true.
    nextShowResult = null;
    final bool nullShown = await controller.showWithItems(
      const Rect.fromLTWH(0.0, 0.0, 50.0, 20.0),
      const <SystemContextMenuItemData>[SystemContextMenuItemDataCopy()],
    );
    expect(nullShown, isTrue);
    expect(controller.isVisible, isTrue);
    expect(sentPayloads.single.keys, unorderedEquals(<String>['targetRect', 'items']));
    await controller.hide();

    // True response resolves to true.
    nextShowResult = true;
    final bool trueShown = await controller.showWithItems(
      const Rect.fromLTWH(0.0, 0.0, 50.0, 20.0),
      const <SystemContextMenuItemData>[SystemContextMenuItemDataCopy()],
    );
    expect(trueShown, isTrue);
    expect(controller.isVisible, isTrue);
    await controller.hide();

    // False response clears visibility, registered client, and callbacks WITHOUT invoking onSystemHide.
    var customActionCalled = false;
    final rejectedItem = SystemContextMenuItemDataCustom(
      id: 'rejected-id',
      title: 'Rejected Action',
      onPressed: () => customActionCalled = true,
    );
    nextShowResult = false;
    final bool falseShown = await controller.showWithItems(
      const Rect.fromLTWH(0.0, 0.0, 50.0, 20.0),
      <SystemContextMenuItemData>[rejectedItem],
    );
    expect(falseShown, isFalse);
    expect(controller.isVisible, isFalse);
    expect(ServicesBinding.instance.debugSystemContextMenuClient, isNull);
    expect(systemHideCount, 0);
    controller.handleCustomContextMenuAction('rejected-id');
    expect(customActionCalled, isFalse);

    // Another controller can immediately show without tripping assert(_lastShown == null).
    nextShowResult = true;
    final controller2 = SystemContextMenuController();
    addTearDown(controller2.dispose);
    expect(
      await controller2.showWithItems(
        const Rect.fromLTWH(10.0, 10.0, 50.0, 20.0),
        const <SystemContextMenuItemData>[SystemContextMenuItemDataCopy()],
      ),
      isTrue,
    );
    expect(controller2.isVisible, isTrue);

    // Calling showWithItems on another controller with the exact same rect and
    // items deduplicates without throwing an AssertionError.
    final controller3 = SystemContextMenuController();
    addTearDown(controller3.dispose);
    final int payloadCountBeforeDedup = sentPayloads.length;
    expect(
      await controller3.showWithItems(
        const Rect.fromLTWH(10.0, 10.0, 50.0, 20.0),
        const <SystemContextMenuItemData>[SystemContextMenuItemDataCopy()],
      ),
      isTrue,
    );
    expect(sentPayloads, hasLength(payloadCountBeforeDedup));
  });

  test('SystemContextMenuItemDataCustom with stable id refreshes callback in place without re-sending showSystemContextMenu', () async {
    final client = FakeTextInputClient(const TextEditingValue(text: 'hello'));
    final TextInputConnection connection = TextInput.attach(client, client.configuration);
    addTearDown(connection.close);

    var showCallCount = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (MethodCall methodCall) async {
        if (methodCall.method == 'ContextMenu.showSystemContextMenu') {
          showCallCount += 1;
          return true;
        }
        return null;
      },
    );
    addTearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      );
    });

    final controller = SystemContextMenuController();
    addTearDown(controller.dispose);

    var invokedVersion = 0;
    const rect = Rect.fromLTWH(0.0, 0.0, 80.0, 20.0);

    await controller.showWithItems(rect, <SystemContextMenuItemData>[
      SystemContextMenuItemDataCustom(
        id: 'translate_action',
        title: 'Translate',
        onPressed: () => invokedVersion = 1,
      ),
    ]);
    expect(showCallCount, 1);

    // Rebuild with a new closure for the same stable (id, title) and same rect:
    // callback map updates in place without re-sending ContextMenu.showSystemContextMenu.
    await controller.showWithItems(rect, <SystemContextMenuItemData>[
      SystemContextMenuItemDataCustom(
        id: 'translate_action',
        title: 'Translate',
        onPressed: () => invokedVersion = 2,
      ),
    ]);
    expect(showCallCount, 1);

    controller.handleCustomContextMenuAction('translate_action');
    expect(invokedVersion, 2);
  });

  test(
    'ContextMenu.onPerformSystemAction routes to onSystemAction and respects clientId scoping',
    () async {
      final attachedClientIds = <int>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.textInput,
        (MethodCall call) async {
          if (call.method == 'TextInput.setClient') {
            attachedClientIds.add((call.arguments as List<dynamic>)[0] as int);
          }
          return null;
        },
      );
      addTearDown(() {
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
          SystemChannels.textInput,
          null,
        );
      });

      final client = FakeTextInputClient(
        const TextEditingValue(
          text: 'Hello world',
          selection: TextSelection(baseOffset: 0, extentOffset: 5),
        ),
      );
      final TextInputConnection connection = TextInput.attach(client, client.configuration);
      addTearDown(connection.close);
      final int clientId = attachedClientIds.single;

      final recordedActions = <(String, String?)>[];
      final controller = SystemContextMenuController(
        onSystemAction: (String action, String? text) {
          recordedActions.add((action, text));
        },
      );
      addTearDown(controller.dispose);
      await controller.showWithItems(
        const Rect.fromLTWH(0.0, 0.0, 60.0, 20.0),
        const <SystemContextMenuItemData>[
          SystemContextMenuItemDataCut(),
          SystemContextMenuItemDataCopy(),
          SystemContextMenuItemDataPaste(),
          SystemContextMenuItemDataSelectAll(),
        ],
      );

      // Mismatched positive clientId is ignored.
      final ByteData mismatchedMsg = const JSONMethodCodec().encodeMethodCall(
        MethodCall('ContextMenu.onPerformSystemAction', <dynamic>[
          clientId + 99,
          'selectAll',
          null,
        ]),
      );
      await binding.defaultBinaryMessenger.handlePlatformMessage(
        'flutter/platform',
        mismatchedMsg,
        (_) {},
      );
      expect(recordedActions, isEmpty);

      // Matching clientId with 2-element list ('copy') invokes onSystemAction with null text.
      final ByteData copyMsg = const JSONMethodCodec().encodeMethodCall(
        MethodCall('ContextMenu.onPerformSystemAction', <dynamic>[clientId, 'copy']),
      );
      await binding.defaultBinaryMessenger.handlePlatformMessage(
        'flutter/platform',
        copyMsg,
        (_) {},
      );
      expect(recordedActions, <(String, String?)>[('copy', null)]);

      // Paste with platform-provided text forwards both action and text.
      final ByteData pasteWithTextMsg = const JSONMethodCodec().encodeMethodCall(
        MethodCall('ContextMenu.onPerformSystemAction', <dynamic>[clientId, 'paste', 'Hi']),
      );
      await binding.defaultBinaryMessenger.handlePlatformMessage(
        'flutter/platform',
        pasteWithTextMsg,
        (_) {},
      );
      expect(recordedActions, <(String, String?)>[('copy', null), ('paste', 'Hi')]);

      // Unscoped clientId (0 or negative) also routes to the active controller when connection matches.
      for (final (int rawId, String action) in <(int, String)>[(0, 'selectAll'), (-1, 'cut')]) {
        final ByteData msg = const JSONMethodCodec().encodeMethodCall(
          MethodCall('ContextMenu.onPerformSystemAction', <dynamic>[rawId, action, null]),
        );
        await binding.defaultBinaryMessenger.handlePlatformMessage('flutter/platform', msg, (_) {});
      }
      expect(recordedActions, <(String, String?)>[
        ('copy', null),
        ('paste', 'Hi'),
        ('selectAll', null),
        ('cut', null),
      ]);

      // Direct controller without onSystemAction logs a debug diagnostic without throwing.
      await controller.hide();
      final noHandlerController = SystemContextMenuController();
      addTearDown(noHandlerController.dispose);
      await noHandlerController.showWithItems(
        const Rect.fromLTWH(0.0, 0.0, 60.0, 20.0),
        const <SystemContextMenuItemData>[SystemContextMenuItemDataCopy()],
      );
      final printedMessages = <String?>[];
      final DebugPrintCallback originalDebugPrint = debugPrint;
      debugPrint = (String? message, {int? wrapWidth}) {
        printedMessages.add(message);
      };
      try {
        await binding.defaultBinaryMessenger.handlePlatformMessage(
          'flutter/platform',
          copyMsg,
          (_) {},
        );
      } finally {
        debugPrint = originalDebugPrint;
      }
      expect(printedMessages.single, contains('onSystemAction is null'));
    },
  );

  test(
    'non-controller custom SystemContextMenuClient receives callbacks after controller usage',
    () async {
      final client = FakeTextInputClient(const TextEditingValue(text: 'hello'));
      final TextInputConnection connection = TextInput.attach(client, client.configuration);
      addTearDown(connection.close);

      final controller = SystemContextMenuController();
      addTearDown(controller.dispose);
      await controller.showWithItems(
        const Rect.fromLTWH(0.0, 0.0, 50.0, 20.0),
        const <SystemContextMenuItemData>[SystemContextMenuItemDataCopy()],
      );
      await controller.hide();

      final customClient = _FakeCustomSystemContextMenuClient();
      ServicesBinding.systemContextMenuClient = customClient;
      addTearDown(() {
        ServicesBinding.systemContextMenuClient = null;
      });

      final ByteData actionMsg = const JSONMethodCodec().encodeMethodCall(
        const MethodCall('ContextMenu.onPerformCustomAction', <dynamic>[0, 'custom-cb']),
      );
      await binding.defaultBinaryMessenger.handlePlatformMessage(
        'flutter/platform',
        actionMsg,
        (_) {},
      );
      expect(customClient.receivedCallbacks, <String>['custom-cb']);

      final ByteData systemActionMsg = const JSONMethodCodec().encodeMethodCall(
        const MethodCall('ContextMenu.onPerformSystemAction', <dynamic>[0, 'paste', 'clip']),
      );
      await binding.defaultBinaryMessenger.handlePlatformMessage(
        'flutter/platform',
        systemActionMsg,
        (_) {},
      );
      expect(customClient.receivedSystemActions, <(String, String?)>[('paste', 'clip')]);

      final ByteData dismissMsg = const JSONMethodCodec().encodeMethodCall(
        const MethodCall('ContextMenu.onDismissSystemContextMenu'),
      );
      await binding.defaultBinaryMessenger.handlePlatformMessage(
        'flutter/platform',
        dismissMsg,
        (_) {},
      );
      expect(customClient.hideCount, 1);
    },
  );

  test('deprecated IOSSystemContextMenuItemData typedefs alias SystemContextMenuItemData', () {
    void noop() {}
    // ignore: deprecated_member_use
    const IOSSystemContextMenuItemData cut = IOSSystemContextMenuItemDataCut();
    // ignore: deprecated_member_use
    const IOSSystemContextMenuItemData copy = IOSSystemContextMenuItemDataCopy();
    // ignore: deprecated_member_use
    const IOSSystemContextMenuItemData paste = IOSSystemContextMenuItemDataPaste();
    // ignore: deprecated_member_use
    const IOSSystemContextMenuItemData selectAll = IOSSystemContextMenuItemDataSelectAll();
    // ignore: deprecated_member_use
    const IOSSystemContextMenuItemData lookUp = IOSSystemContextMenuItemDataLookUp(title: 'Look');
    // ignore: deprecated_member_use
    const IOSSystemContextMenuItemData searchWeb = IOSSystemContextMenuItemDataSearchWeb(
      title: 'Search',
    );
    // ignore: deprecated_member_use
    const IOSSystemContextMenuItemData share = IOSSystemContextMenuItemDataShare(title: 'Share');
    // ignore: deprecated_member_use
    const IOSSystemContextMenuItemData liveText = IOSSystemContextMenuItemDataLiveText();
    // ignore: deprecated_member_use
    final IOSSystemContextMenuItemData custom = IOSSystemContextMenuItemDataCustom(
      id: 'id1',
      title: 'Custom',
      onPressed: noop,
    );

    expect(cut, const SystemContextMenuItemDataCut());
    expect(copy, const SystemContextMenuItemDataCopy());
    expect(paste, const SystemContextMenuItemDataPaste());
    expect(selectAll, const SystemContextMenuItemDataSelectAll());
    expect(lookUp, const SystemContextMenuItemDataLookUp(title: 'Look'));
    expect(searchWeb, const SystemContextMenuItemDataSearchWeb(title: 'Search'));
    expect(share, const SystemContextMenuItemDataShare(title: 'Share'));
    expect(liveText, isA<SystemContextMenuItemDataLiveText>());
    expect(custom, SystemContextMenuItemDataCustom(id: 'id1', title: 'Custom', onPressed: noop));
  });
}

class _FakeCustomSystemContextMenuClient with SystemContextMenuClient {
  final List<String> receivedCallbacks = <String>[];
  final List<(String, String?)> receivedSystemActions = <(String, String?)>[];
  int hideCount = 0;

  @override
  void handleCustomContextMenuAction(String callbackId) {
    receivedCallbacks.add(callbackId);
  }

  @override
  void handleSystemContextMenuAction(String action, {String? text}) {
    receivedSystemActions.add((action, text));
  }

  @override
  void handleSystemHide() {
    hideCount += 1;
  }
}
