// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

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
    final items = <IOSSystemContextMenuItemData>[const IOSSystemContextMenuItemDataCopy()];
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
    final items = <IOSSystemContextMenuItemData>[const IOSSystemContextMenuItemDataCopy()];
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
    final items = <IOSSystemContextMenuItemData>[const IOSSystemContextMenuItemDataCopy()];
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
    final items = <IOSSystemContextMenuItemData>[const IOSSystemContextMenuItemDataCopy()];
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

    final itemsReceived = <List<IOSSystemContextMenuItemData>>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (MethodCall methodCall) async {
        switch (methodCall.method) {
          case 'ContextMenu.showSystemContextMenu':
            final arguments = methodCall.arguments as Map<String, dynamic>;
            final untypedItems = arguments['items'] as List<dynamic>;
            final List<IOSSystemContextMenuItemData> lastItems = untypedItems.map((dynamic value) {
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
    final items1 = <IOSSystemContextMenuItemData>[
      const IOSSystemContextMenuItemDataCut(),
      const IOSSystemContextMenuItemDataCopy(),
      const IOSSystemContextMenuItemDataPaste(),
      const IOSSystemContextMenuItemDataSelectAll(),
      const IOSSystemContextMenuItemDataSearchWeb(title: 'Special Search'),
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
    final items2 = <IOSSystemContextMenuItemData>[const IOSSystemContextMenuItemDataCut()];
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

    final itemsReceived = <List<IOSSystemContextMenuItemData>>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (MethodCall methodCall) async {
        switch (methodCall.method) {
          case 'ContextMenu.showSystemContextMenu':
            final arguments = methodCall.arguments as Map<String, dynamic>;
            final untypedItems = arguments['items'] as List<dynamic>;
            final List<IOSSystemContextMenuItemData> lastItems = untypedItems.map((dynamic value) {
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
    final items = <IOSSystemContextMenuItemData>[];

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
    final List<IOSSystemContextMenuItem> defaultItems = SystemContextMenu.getDefaultItems(
      editableTextState,
    );
    expect(defaultItems, hasLength(4));
    expect(defaultItems[0], const IOSSystemContextMenuItemCut());
    expect(defaultItems[1], const IOSSystemContextMenuItemCopy());
    expect(defaultItems[2], const IOSSystemContextMenuItemPaste());
    expect(defaultItems[3], const IOSSystemContextMenuItemSelectAll());

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
    final List<IOSSystemContextMenuItemData> defaultItemDatas = defaultItems
        .map((IOSSystemContextMenuItem item) => item.getData(localizations))
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

    final items = <IOSSystemContextMenuItemData>[
      IOSSystemContextMenuItemDataCustom(
        title: 'Action 1',
        onPressed: () {
          action1Called = true;
        },
      ),
      IOSSystemContextMenuItemDataCustom(
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
    final String callbackId1 = (items[0] as IOSSystemContextMenuItemDataCustom).callbackId;
    final String callbackId2 = (items[1] as IOSSystemContextMenuItemDataCustom).callbackId;

    controller.handleCustomContextMenuAction(callbackId1);
    expect(action1Called, isTrue);
    expect(action2Called, isFalse);

    controller.handleCustomContextMenuAction(callbackId2);
    expect(action1Called, isTrue);
    expect(action2Called, isTrue);

    controller.hide();
    expect(controller.isVisible, isFalse);

    expect(() => controller.handleCustomContextMenuAction(callbackId1), throwsAssertionError);
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

    final items1 = <IOSSystemContextMenuItemData>[
      IOSSystemContextMenuItemDataCustom(
        title: 'Controller 1 Action',
        onPressed: () {
          controller1ActionCalled = true;
        },
      ),
    ];

    final items2 = <IOSSystemContextMenuItemData>[
      IOSSystemContextMenuItemDataCustom(
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
    final String callbackId2 = (items2[0] as IOSSystemContextMenuItemDataCustom).callbackId;

    controller2.handleCustomContextMenuAction(callbackId2);
    expect(controller2ActionCalled, isTrue);
    expect(controller1ActionCalled, isFalse);

    // Get the actual callback ID from controller1's items.
    final String callbackId1 = (items1[0] as IOSSystemContextMenuItemDataCustom).callbackId;

    expect(() => controller1.handleCustomContextMenuAction(callbackId1), throwsAssertionError);
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

    final items = <IOSSystemContextMenuItemData>[
      IOSSystemContextMenuItemDataCustom(
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
    final String callbackId = (items[0] as IOSSystemContextMenuItemDataCustom).callbackId;

    expect(() => controller.handleCustomContextMenuAction(callbackId), throwsAssertionError);
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

  test('handleCustomContextMenuAction with non-existent callbackId', () {
    final client = FakeTextInputClient(const TextEditingValue(text: 'test'));
    final TextInputConnection connection = TextInput.attach(client, client.configuration);
    addTearDown(() {
      connection.close();
    });

    final controller = SystemContextMenuController();
    addTearDown(() {
      controller.dispose();
    });

    final items = <IOSSystemContextMenuItemData>[
      IOSSystemContextMenuItemDataCustom(title: 'Test Action', onPressed: () {}),
    ];

    const rect = Rect.fromLTWH(0.0, 0.0, 100.0, 100.0);
    controller.showWithItems(rect, items);
    expect(controller.isVisible, isTrue);

    expect(() => controller.handleCustomContextMenuAction('non-existent-id'), throwsAssertionError);
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

    final items = <IOSSystemContextMenuItemData>[
      IOSSystemContextMenuItemDataCustom(
        title: 'Test Action',
        onPressed: () {
          actionCalled = true;
        },
      ),
    ];

    const rect = Rect.fromLTWH(0.0, 0.0, 100.0, 100.0);
    controller.showWithItems(rect, items);
    expect(controller.isVisible, isTrue);

    final String callbackId = (items[0] as IOSSystemContextMenuItemDataCustom).callbackId;

    // Test that it works before hiding.
    controller.handleCustomContextMenuAction(callbackId);
    expect(actionCalled, isTrue);
    actionCalled = false;

    controller.hide();
    expect(controller.isVisible, isFalse);

    expect(() => controller.handleCustomContextMenuAction(callbackId), throwsAssertionError);
    expect(actionCalled, isFalse);
  });

  test('showing new menu invalidates old menu callbacks', () {
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
    final oldItems = <IOSSystemContextMenuItemData>[
      IOSSystemContextMenuItemDataCustom(
        title: 'Old Action',
        onPressed: () {
          oldActionCalled = true;
        },
      ),
    ];

    const rect1 = Rect.fromLTWH(0.0, 0.0, 100.0, 100.0);
    controller.showWithItems(rect1, oldItems);
    expect(controller.isVisible, isTrue);

    final String oldCallbackId = (oldItems[0] as IOSSystemContextMenuItemDataCustom).callbackId;

    // Show new menu with new action.
    final newItems = <IOSSystemContextMenuItemData>[
      IOSSystemContextMenuItemDataCustom(
        title: 'New Action',
        onPressed: () {
          newActionCalled = true;
        },
      ),
    ];

    const rect2 = Rect.fromLTWH(100.0, 100.0, 100.0, 100.0);
    controller.showWithItems(rect2, newItems);
    expect(controller.isVisible, isTrue);

    final String newCallbackId = (newItems[0] as IOSSystemContextMenuItemDataCustom).callbackId;

    // Old callback should not work.
    expect(() => controller.handleCustomContextMenuAction(oldCallbackId), throwsAssertionError);
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
    await controller1.showWithItems(rect1, const <IOSSystemContextMenuItemData>[
      IOSSystemContextMenuItemDataCopy(),
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

    final customItem2 = IOSSystemContextMenuItemDataCustom(
      title: 'Action 2',
      onPressed: () => action2Count += 1,
    );
    await controller2.showWithItems(
      const Rect.fromLTWH(10.0, 10.0, 60.0, 20.0),
      <IOSSystemContextMenuItemData>[customItem2],
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

  test(
    'synchronous re-show inside custom action callback ignores trailing dismiss for prior sessionId',
    () async {
      late int attachedClientId;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.textInput,
        (MethodCall call) async {
          if (call.method == 'TextInput.setClient') {
            attachedClientId = (call.arguments as List<dynamic>)[0] as int;
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

      final client = FakeTextInputClient(const TextEditingValue(text: 'hello'));
      final TextInputConnection connection = TextInput.attach(client, client.configuration);
      addTearDown(connection.close);

      final sentSessionIds = <int>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (MethodCall methodCall) async {
          if (methodCall.method == 'ContextMenu.showSystemContextMenu') {
            final args = methodCall.arguments as Map<String, dynamic>;
            sentSessionIds.add(args['sessionId'] as int);
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

      var hideCount = 0;
      late SystemContextMenuController controller;
      controller = SystemContextMenuController(onSystemHide: () => hideCount += 1);
      addTearDown(controller.dispose);

      var secondActionCount = 0;
      final secondItem = IOSSystemContextMenuItemDataCustom(
        title: 'Second Menu Action',
        onPressed: () => secondActionCount += 1,
      );
      final firstItem = IOSSystemContextMenuItemDataCustom(
        title: 'Re-show Menu',
        onPressed: () {
          // Synchronously show a new menu before Android's trailing onDestroyActionMode arrives.
          controller.showWithItems(
            const Rect.fromLTWH(20.0, 20.0, 80.0, 20.0),
            <IOSSystemContextMenuItemData>[secondItem],
          );
        },
      );

      await controller.showWithItems(
        const Rect.fromLTWH(0.0, 0.0, 80.0, 20.0),
        <IOSSystemContextMenuItemData>[firstItem],
      );
      expect(sentSessionIds, hasLength(1));
      final int firstSessionId = sentSessionIds.first;

      // Platform dispatches onPerformCustomAction for session 1, triggering synchronous re-show
      // (session 2), followed by onDismissSystemContextMenu for session 1.
      final ByteData customActionMsg = const JSONMethodCodec().encodeMethodCall(
        MethodCall('ContextMenu.onPerformCustomAction', <dynamic>[
          attachedClientId,
          firstItem.callbackId,
          firstSessionId,
        ]),
      );
      await binding.defaultBinaryMessenger.handlePlatformMessage(
        'flutter/platform',
        customActionMsg,
        (_) {},
      );
      expect(sentSessionIds, hasLength(2));
      final int secondSessionId = sentSessionIds.last;
      expect(secondSessionId, greaterThan(firstSessionId));
      expect(controller.isVisible, isTrue);

      // Trailing dismiss for firstSessionId must NOT hide the newly shown secondSessionId!
      final ByteData trailingDismissMsg = const JSONMethodCodec().encodeMethodCall(
        MethodCall('ContextMenu.onDismissSystemContextMenu', <dynamic>[
          attachedClientId,
          firstSessionId,
        ]),
      );
      await binding.defaultBinaryMessenger.handlePlatformMessage(
        'flutter/platform',
        trailingDismissMsg,
        (_) {},
      );
      expect(controller.isVisible, isTrue);
      expect(hideCount, 0);

      // Second menu's custom action and dismiss with secondSessionId work normally.
      final ByteData secondActionMsg = const JSONMethodCodec().encodeMethodCall(
        MethodCall('ContextMenu.onPerformCustomAction', <dynamic>[
          attachedClientId,
          secondItem.callbackId,
          secondSessionId,
        ]),
      );
      await binding.defaultBinaryMessenger.handlePlatformMessage(
        'flutter/platform',
        secondActionMsg,
        (_) {},
      );
      expect(secondActionCount, 1);

      final ByteData secondDismissMsg = const JSONMethodCodec().encodeMethodCall(
        MethodCall('ContextMenu.onDismissSystemContextMenu', <dynamic>[
          attachedClientId,
          secondSessionId,
        ]),
      );
      await binding.defaultBinaryMessenger.handlePlatformMessage(
        'flutter/platform',
        secondDismissMsg,
        (_) {},
      );
      expect(controller.isVisible, isFalse);
      expect(hideCount, 1);
    },
  );

  test(
    'in-flight custom action tap during in-place showWithItems update still executes if callbackId remains registered',
    () async {
      late int attachedClientId;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.textInput,
        (MethodCall call) async {
          if (call.method == 'TextInput.setClient') {
            attachedClientId = (call.arguments as List<dynamic>)[0] as int;
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

      final client = FakeTextInputClient(const TextEditingValue(text: 'hello'));
      final TextInputConnection connection = TextInput.attach(client, client.configuration);
      addTearDown(connection.close);

      final sentSessionIds = <int>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (MethodCall methodCall) async {
          if (methodCall.method == 'ContextMenu.showSystemContextMenu') {
            final args = methodCall.arguments as Map<String, dynamic>;
            sentSessionIds.add(args['sessionId'] as int);
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

      var actionCount = 0;
      final stableItem = IOSSystemContextMenuItemDataCustom(
        title: 'Translate',
        onPressed: () => actionCount += 1,
      );

      await controller.showWithItems(
        const Rect.fromLTWH(0.0, 0.0, 80.0, 20.0),
        <IOSSystemContextMenuItemData>[stableItem],
      );
      final int firstSession = sentSessionIds.first;

      // Dart updates targetRect (advancing sessionId) while user taps stableItem on firstSession.
      await controller.showWithItems(
        const Rect.fromLTWH(5.0, 5.0, 80.0, 20.0),
        <IOSSystemContextMenuItemData>[stableItem],
      );
      expect(sentSessionIds, hasLength(2));

      final ByteData inFlightAction = const JSONMethodCodec().encodeMethodCall(
        MethodCall('ContextMenu.onPerformCustomAction', <dynamic>[
          attachedClientId,
          stableItem.callbackId,
          firstSession,
        ]),
      );
      await binding.defaultBinaryMessenger.handlePlatformMessage(
        'flutter/platform',
        inFlightAction,
        (_) {},
      );
      expect(actionCount, 1);
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
        const <IOSSystemContextMenuItemData>[IOSSystemContextMenuItemDataCopy()],
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
}

class _FakeCustomSystemContextMenuClient with SystemContextMenuClient {
  final List<String> receivedCallbacks = <String>[];
  int hideCount = 0;

  @override
  void handleCustomContextMenuAction(String callbackId) {
    receivedCallbacks.add(callbackId);
  }

  @override
  void handleSystemHide() {
    hideCount += 1;
  }
}
