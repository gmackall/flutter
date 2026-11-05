// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

import '../system_context_menu_utils.dart';
import 'editable_text_tester.dart';

Widget _buildSystemContextMenuTestApp({
  required Widget child,
  bool supportsShowingSystemContextMenu = false,
}) {
  return TestWidgetsApp(
    home: child,
    builder: (BuildContext context, Widget? appChild) {
      final MediaQueryData mediaQueryData =
          MediaQuery.maybeOf(context) ?? MediaQueryData.fromView(View.of(context));
      return MediaQuery(
        data: mediaQueryData.copyWith(
          supportsShowingSystemContextMenu: supportsShowingSystemContextMenu,
        ),
        child: appChild ?? const SizedBox.shrink(),
      );
    },
  );
}

void main() {
  final TestWidgetsFlutterBinding binding = TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets(
    'asserts when built on an unsupported device',
    (WidgetTester tester) async {
      final controller = TextEditingController(text: 'one two three');
      addTearDown(controller.dispose);
      await tester.pumpWidget(
        // By default, MediaQueryData.supportsShowingSystemContextMenu is false.
        _buildSystemContextMenuTestApp(
          child: Center(
            child: TestTextField(
              controller: controller,
              contextMenuBuilder: (BuildContext context, EditableTextState editableTextState) {
                return SystemContextMenu.editableText(editableTextState: editableTextState);
              },
            ),
          ),
        ),
      );

      await tester.tap(find.byType(TestTextField));
      final EditableTextState state = tester.state<EditableTextState>(find.byType(EditableText));
      expect(state.showToolbar(), true);
      await tester.pump();

      expect(tester.takeException(), isAssertionError);
    },
    skip: kIsWeb, // [intended]
    variant: TargetPlatformVariant.all(),
  );

  testWidgets(
    'asserts when built on web',
    (WidgetTester tester) async {
      // Disable the browser context menu so that contextMenuBuilder will be used.
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.contextMenu,
        (MethodCall call) {
          // Just complete successfully, so that BrowserContextMenu thinks that
          // the engine successfully received its call.
          return Future<void>.value();
        },
      );
      await BrowserContextMenu.disableContextMenu();
      addTearDown(() async {
        await BrowserContextMenu.enableContextMenu();
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
          SystemChannels.contextMenu,
          null,
        );
      });

      final controller = TextEditingController(text: 'one two three');
      addTearDown(controller.dispose);
      await tester.pumpWidget(
        // By default, MediaQueryData.supportsShowingSystemContextMenu is false.
        _buildSystemContextMenuTestApp(
          child: Center(
            child: TestTextField(
              controller: controller,
              contextMenuBuilder: (BuildContext context, EditableTextState editableTextState) {
                return SystemContextMenu.editableText(editableTextState: editableTextState);
              },
            ),
          ),
        ),
      );

      await tester.tap(find.byType(TestTextField));
      final EditableTextState state = tester.state<EditableTextState>(find.byType(EditableText));
      expect(state.showToolbar(), true);
      await tester.pump();

      expect(tester.takeException(), isAssertionError);
    },
    skip: !kIsWeb, // [intended]
    variant: TargetPlatformVariant.only(TargetPlatform.iOS),
  );

  testWidgets(
    'can be shown and hidden like a normal context menu',
    (WidgetTester tester) async {
      final controller = TextEditingController(text: 'one two three');
      addTearDown(controller.dispose);
      await tester.pumpWidget(
        _buildSystemContextMenuTestApp(
          supportsShowingSystemContextMenu: true,
          child: Center(
            child: TestTextField(
              controller: controller,
              contextMenuBuilder: (BuildContext context, EditableTextState editableTextState) {
                return SystemContextMenu.editableText(editableTextState: editableTextState);
              },
            ),
          ),
        ),
      );

      expect(find.byType(SystemContextMenu), findsNothing);

      await tester.tap(find.byType(TestTextField));
      final EditableTextState state = tester.state<EditableTextState>(find.byType(EditableText));
      expect(state.showToolbar(), true);
      await tester.pump();
      expect(find.byType(SystemContextMenu), findsOneWidget);

      state.hideToolbar();
      await tester.pump();
      expect(find.byType(SystemContextMenu), findsNothing);
    },
    skip: kIsWeb, // [intended]
    variant: TargetPlatformVariant.only(TargetPlatform.iOS),
  );

  testWidgets(
    'can customize the menu items',
    (WidgetTester tester) async {
      final itemsReceived = <List<IOSSystemContextMenuItemData>>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (MethodCall methodCall) async {
          switch (methodCall.method) {
            case 'ContextMenu.showSystemContextMenu':
              final arguments = methodCall.arguments as Map<String, dynamic>;
              final untypedItems = arguments['items'] as List<dynamic>;
              final List<IOSSystemContextMenuItemData> lastItems = untypedItems.map((
                dynamic value,
              ) {
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

      const items1 = <IOSSystemContextMenuItem>[
        IOSSystemContextMenuItemCopy(),
        IOSSystemContextMenuItemShare(title: 'My Share Title'),
        IOSSystemContextMenuItemLiveText(),
      ];
      final controller = TextEditingController(text: 'one two three');
      addTearDown(controller.dispose);
      await tester.pumpWidget(
        _buildSystemContextMenuTestApp(
          supportsShowingSystemContextMenu: true,
          child: Center(
            child: TestTextField(
              controller: controller,
              contextMenuBuilder: (BuildContext context, EditableTextState editableTextState) {
                return SystemContextMenu.editableText(
                  editableTextState: editableTextState,
                  items: items1,
                );
              },
            ),
          ),
        ),
      );

      expect(find.byType(SystemContextMenu), findsNothing);
      expect(itemsReceived, hasLength(0));

      await tester.tap(find.byType(TestTextField));
      final EditableTextState state = tester.state<EditableTextState>(find.byType(EditableText));
      expect(state.showToolbar(), true);
      await tester.pump();
      expect(find.byType(SystemContextMenu), findsOneWidget);

      expect(itemsReceived, hasLength(1));
      expect(itemsReceived.last, hasLength(items1.length));
      expect(itemsReceived.last[0], equals(const IOSSystemContextMenuItemDataCopy()));
      expect(
        itemsReceived.last[1],
        equals(const IOSSystemContextMenuItemDataShare(title: 'My Share Title')),
      );
      expect(itemsReceived.last[2], equals(const IOSSystemContextMenuItemDataLiveText()));

      state.hideToolbar();
      await tester.pump();
      expect(find.byType(SystemContextMenu), findsNothing);
    },
    skip: kIsWeb, // [intended]
    variant: TargetPlatformVariant.only(TargetPlatform.iOS),
  );

  testWidgets(
    "passing empty items builds the widget but doesn't show the system context menu",
    (WidgetTester tester) async {
      final itemsReceived = <List<IOSSystemContextMenuItemData>>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (MethodCall methodCall) async {
          switch (methodCall.method) {
            case 'ContextMenu.showSystemContextMenu':
              final arguments = methodCall.arguments as Map<String, dynamic>;
              final untypedItems = arguments['items'] as List<dynamic>;
              final List<IOSSystemContextMenuItemData> lastItems = untypedItems.map((
                dynamic value,
              ) {
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

      const items1 = <IOSSystemContextMenuItem>[];
      final controller = TextEditingController(text: 'one two three');
      addTearDown(controller.dispose);
      await tester.pumpWidget(
        _buildSystemContextMenuTestApp(
          supportsShowingSystemContextMenu: true,
          child: Center(
            child: TestTextField(
              controller: controller,
              contextMenuBuilder: (BuildContext context, EditableTextState editableTextState) {
                return SystemContextMenu.editableText(
                  editableTextState: editableTextState,
                  items: items1,
                );
              },
            ),
          ),
        ),
      );

      expect(tester.takeException(), isNull);

      expect(find.byType(SystemContextMenu), findsNothing);
      expect(itemsReceived, hasLength(0));

      await tester.tap(find.byType(TestTextField));
      final EditableTextState state = tester.state<EditableTextState>(find.byType(EditableText));
      expect(state.showToolbar(), true);
      expect(tester.takeException(), isNull);

      await tester.pump();
      expect(tester.takeException(), isNull);
      expect(find.byType(SystemContextMenu), findsOneWidget);
      expect(itemsReceived, hasLength(0));
    },
    skip: kIsWeb, // [intended]
    variant: TargetPlatformVariant.only(TargetPlatform.iOS),
  );

  testWidgets(
    'items receive a default title',
    (WidgetTester tester) async {
      final itemsReceived = <List<IOSSystemContextMenuItemData>>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (MethodCall methodCall) async {
          switch (methodCall.method) {
            case 'ContextMenu.showSystemContextMenu':
              final arguments = methodCall.arguments as Map<String, dynamic>;
              final untypedItems = arguments['items'] as List<dynamic>;
              final List<IOSSystemContextMenuItemData> lastItems = untypedItems.map((
                dynamic value,
              ) {
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

      const items1 = <IOSSystemContextMenuItem>[
        // Copy gets no title, it's set by the platform.
        IOSSystemContextMenuItemCopy(),
        // Share could take a title, but if not, it gets a localized default.
        IOSSystemContextMenuItemShare(),
      ];
      final controller = TextEditingController(text: 'one two three');
      addTearDown(controller.dispose);
      await tester.pumpWidget(
        _buildSystemContextMenuTestApp(
          supportsShowingSystemContextMenu: true,
          child: Center(
            child: TestTextField(
              controller: controller,
              contextMenuBuilder: (BuildContext context, EditableTextState editableTextState) {
                return SystemContextMenu.editableText(
                  editableTextState: editableTextState,
                  items: items1,
                );
              },
            ),
          ),
        ),
      );

      expect(find.byType(SystemContextMenu), findsNothing);
      expect(itemsReceived, hasLength(0));

      await tester.tap(find.byType(TestTextField));
      final EditableTextState state = tester.state<EditableTextState>(find.byType(EditableText));
      expect(state.showToolbar(), true);
      await tester.pump();
      expect(find.byType(SystemContextMenu), findsOneWidget);

      expect(itemsReceived, hasLength(1));
      expect(itemsReceived.last, hasLength(items1.length));
      expect(itemsReceived.last[0], equals(const IOSSystemContextMenuItemDataCopy()));
      const WidgetsLocalizations localizations = DefaultWidgetsLocalizations();
      expect(
        itemsReceived.last[1],
        equals(IOSSystemContextMenuItemDataShare(title: localizations.shareButtonLabel)),
      );

      state.hideToolbar();
      await tester.pump();
      expect(find.byType(SystemContextMenu), findsNothing);
    },
    skip: kIsWeb, // [intended]
    variant: TargetPlatformVariant.only(TargetPlatform.iOS),
  );

  testWidgets(
    'can be updated.',
    (WidgetTester tester) async {
      final targetRects = <Map<String, double>>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (MethodCall methodCall) async {
          if (methodCall.method == 'ContextMenu.showSystemContextMenu') {
            final arguments = methodCall.arguments as Map<String, dynamic>;
            final untypedTargetRect = arguments['targetRect'] as Map<String, dynamic>;
            final Map<String, double> lastTargetRect = untypedTargetRect.map((
              String key,
              dynamic value,
            ) {
              return MapEntry<String, double>(key, value as double);
            });
            targetRects.add(lastTargetRect);
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

      final controller = TextEditingController(text: 'one two three');
      addTearDown(controller.dispose);
      await tester.pumpWidget(
        _buildSystemContextMenuTestApp(
          supportsShowingSystemContextMenu: true,
          child: Center(
            child: TestTextField(
              controller: controller,
              contextMenuBuilder: (BuildContext context, EditableTextState editableTextState) {
                return SystemContextMenu.editableText(editableTextState: editableTextState);
              },
            ),
          ),
        ),
      );

      expect(targetRects, isEmpty);

      await tester.tap(find.byType(TestTextField));
      final EditableTextState state = tester.state<EditableTextState>(find.byType(EditableText));
      expect(state.showToolbar(), true);
      await tester.pump();

      expect(targetRects, hasLength(1));
      expect(targetRects.last, containsPair('width', 0.0));

      controller.selection = const TextSelection(baseOffset: 4, extentOffset: 7);
      await tester.pumpAndSettle();

      expect(targetRects, hasLength(2));
      expect(targetRects.last['width'], greaterThan(0.0));
    },
    skip: kIsWeb, // [intended]
    variant: TargetPlatformVariant.only(TargetPlatform.iOS),
  );

  testWidgets(
    'can be rebuilt',
    (WidgetTester tester) async {
      final controller = TextEditingController(text: 'one two three');
      addTearDown(controller.dispose);
      late StateSetter setState;
      await tester.pumpWidget(
        _buildSystemContextMenuTestApp(
          supportsShowingSystemContextMenu: true,
          child: Center(
            child: StatefulBuilder(
              builder: (BuildContext context, StateSetter localSetState) {
                setState = localSetState;
                return TestTextField(
                  controller: controller,
                  contextMenuBuilder: (BuildContext context, EditableTextState editableTextState) {
                    return SystemContextMenu.editableText(editableTextState: editableTextState);
                  },
                );
              },
            ),
          ),
        ),
      );

      await tester.tap(find.byType(TestTextField));
      final EditableTextState state = tester.state<EditableTextState>(find.byType(EditableText));
      expect(state.showToolbar(), true);
      await tester.pump();

      setState(() {});
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
    },
    skip: kIsWeb, // [intended]
    variant: TargetPlatformVariant.only(TargetPlatform.iOS),
  );

  testWidgets(
    'can handle multiple instances',
    (WidgetTester tester) async {
      final controller1 = TextEditingController(text: 'one two three');
      addTearDown(controller1.dispose);
      final controller2 = TextEditingController(text: 'four five six');
      addTearDown(controller2.dispose);
      final GlobalKey field1Key = GlobalKey();
      final GlobalKey field2Key = GlobalKey();
      final GlobalKey menu1Key = GlobalKey();
      final GlobalKey menu2Key = GlobalKey();
      await tester.pumpWidget(
        _buildSystemContextMenuTestApp(
          supportsShowingSystemContextMenu: true,
          child: Center(
            child: Column(
              children: <Widget>[
                TestTextField(
                  key: field1Key,
                  controller: controller1,
                  contextMenuBuilder: (BuildContext context, EditableTextState editableTextState) {
                    return SystemContextMenu.editableText(
                      key: menu1Key,
                      editableTextState: editableTextState,
                    );
                  },
                ),
                TestTextField(
                  key: field2Key,
                  controller: controller2,
                  contextMenuBuilder: (BuildContext context, EditableTextState editableTextState) {
                    return SystemContextMenu.editableText(
                      key: menu2Key,
                      editableTextState: editableTextState,
                    );
                  },
                ),
              ],
            ),
          ),
        ),
      );

      expect(find.byType(SystemContextMenu), findsNothing);

      await tester.tap(find.byKey(field1Key));
      final EditableTextState state1 = tester.state<EditableTextState>(
        find.descendant(of: find.byKey(field1Key), matching: find.byType(EditableText)),
      );
      expect(state1.showToolbar(), true);
      await tester.pump();
      expect(find.byKey(menu1Key), findsOneWidget);
      expect(find.byKey(menu2Key), findsNothing);

      // In a real app, this message is sent by iOS when the user taps anywhere
      // outside of the system context menu.
      final ByteData? messageBytes = const JSONMessageCodec().encodeMessage(<String, dynamic>{
        'method': 'ContextMenu.onDismissSystemContextMenu',
      });
      await binding.defaultBinaryMessenger.handlePlatformMessage(
        'flutter/platform',
        messageBytes,
        (ByteData? data) {},
      );
      await tester.pump();
      expect(find.byType(SystemContextMenu), findsNothing);

      await tester.tap(find.byKey(field2Key));
      final EditableTextState state2 = tester.state<EditableTextState>(
        find.descendant(of: find.byKey(field2Key), matching: find.byType(EditableText)),
      );
      expect(state2.showToolbar(), true);
      await tester.pump();
      expect(find.byKey(menu1Key), findsNothing);
      expect(find.byKey(menu2Key), findsOneWidget);
    },
    skip: kIsWeb, // [intended]
    variant: TargetPlatformVariant.only(TargetPlatform.iOS),
  );

  test(
    'can get the IOSSystemContextMenuItemData representation of an IOSSystemContextMenuItemCopy',
    () {
      const item = IOSSystemContextMenuItemCopy();
      const WidgetsLocalizations localizations = DefaultWidgetsLocalizations();
      expect(item.getData(localizations), const IOSSystemContextMenuItemDataCopy());
    },
  );

  test(
    'can get the IOSSystemContextMenuItemData representation of an IOSSystemContextMenuItemCut',
    () {
      const item = IOSSystemContextMenuItemCut();
      const WidgetsLocalizations localizations = DefaultWidgetsLocalizations();
      expect(item.getData(localizations), const IOSSystemContextMenuItemDataCut());
    },
  );

  test(
    'can get the IOSSystemContextMenuItemData representation of an IOSSystemContextMenuItemPaste',
    () {
      const item = IOSSystemContextMenuItemPaste();
      const WidgetsLocalizations localizations = DefaultWidgetsLocalizations();
      expect(item.getData(localizations), const IOSSystemContextMenuItemDataPaste());
    },
  );

  test('can get the IOSSystemContextMenuItemData representation of an IOSSystemContextMenuItemSelectAll', () {
    const item = IOSSystemContextMenuItemSelectAll();
    const WidgetsLocalizations localizations = DefaultWidgetsLocalizations();
    expect(item.getData(localizations), const IOSSystemContextMenuItemDataSelectAll());
  });

  test(
    'can get the IOSSystemContextMenuItemData representation of an IOSSystemContextMenuItemLookUp',
    () {
      const item = IOSSystemContextMenuItemLookUp();
      const WidgetsLocalizations localizations = DefaultWidgetsLocalizations();
      expect(
        item.getData(localizations),
        IOSSystemContextMenuItemDataLookUp(title: localizations.lookUpButtonLabel),
      );
    },
  );

  test('can get the IOSSystemContextMenuItemData representation of an IOSSystemContextMenuItemSearchWeb', () {
    const item = IOSSystemContextMenuItemSearchWeb();
    const WidgetsLocalizations localizations = DefaultWidgetsLocalizations();
    expect(
      item.getData(localizations),
      IOSSystemContextMenuItemDataSearchWeb(title: localizations.searchWebButtonLabel),
    );
  });

  test(
    'can get the IOSSystemContextMenuItemData representation of an IOSSystemContextMenuItemShare',
    () {
      const item = IOSSystemContextMenuItemShare();
      const WidgetsLocalizations localizations = DefaultWidgetsLocalizations();
      expect(
        item.getData(localizations),
        IOSSystemContextMenuItemDataShare(title: localizations.shareButtonLabel),
      );
    },
  );

  test('can get the IOSSystemContextMenuItemData representation of an IOSSystemContextMenuItemLiveText', () {
    const item = IOSSystemContextMenuItemLiveText();
    const WidgetsLocalizations localizations = DefaultWidgetsLocalizations();
    final IOSSystemContextMenuItemData data = item.getData(localizations);
    expect(data, isA<IOSSystemContextMenuItemDataLiveText>());
  });

  test('systemContextMenuItemDataFromJson handles Live Text', () {
    final json = <String, dynamic>{'type': 'captureTextFromCamera'};
    final IOSSystemContextMenuItemData item = systemContextMenuItemDataFromJson(json);
    expect(item, isA<IOSSystemContextMenuItemDataLiveText>());
  });

  // Regression test for https://github.com/flutter/flutter/issues/169696.
  test('IOSSystemContextMenuItemLookUp debugFillProperties', () {
    const title = 'my title';
    const item = IOSSystemContextMenuItemLookUp(title: title);
    final List<DiagnosticsNode> diagnosticsNodes = item.toDiagnosticsNode().getProperties();
    expect(diagnosticsNodes, hasLength(1));
    expect(diagnosticsNodes.first.name, 'title');
    expect(diagnosticsNodes.first.value, title);
  });

  // Regression test for https://github.com/flutter/flutter/issues/169696.
  test('IOSSystemContextMenuItemSearchWeb debugFillProperties', () {
    const title = 'my title';
    const item = IOSSystemContextMenuItemSearchWeb(title: title);
    final List<DiagnosticsNode> diagnosticsNodes = item.toDiagnosticsNode().getProperties();
    expect(diagnosticsNodes, hasLength(1));
    expect(diagnosticsNodes.first.name, 'title');
    expect(diagnosticsNodes.first.value, title);
  });

  // Regression test for https://github.com/flutter/flutter/issues/169696.
  test('IOSSystemContextMenuItemShare debugFillProperties', () {
    const title = 'my title';
    const item = IOSSystemContextMenuItemShare(title: title);
    final List<DiagnosticsNode> diagnosticsNodes = item.toDiagnosticsNode().getProperties();
    expect(diagnosticsNodes, hasLength(1));
    expect(diagnosticsNodes.first.name, 'title');
    expect(diagnosticsNodes.first.value, title);
  });

  testWidgets(
    'when supportsShowingSystemContextMenu is false, isSupported is false',
    (WidgetTester tester) async {
      final controller = TextEditingController(text: 'one two three');
      addTearDown(controller.dispose);
      late BuildContext buildContext;
      await tester.pumpWidget(
        _buildSystemContextMenuTestApp(
          child: Builder(
            builder: (BuildContext context) {
              buildContext = context;
              return TestTextField(
                controller: controller,
                contextMenuBuilder: (BuildContext context, EditableTextState editableTextState) {
                  return SystemContextMenu.editableText(editableTextState: editableTextState);
                },
              );
            },
          ),
        ),
      );

      expect(SystemContextMenu.isSupported(buildContext), isFalse);
    },
    skip: kIsWeb, // [intended] SystemContextMenu is not supported on web.
    variant: TargetPlatformVariant.only(TargetPlatform.iOS),
  );

  testWidgets(
    'when supportsShowingSystemContextMenu is true and the platform is iOS or Android, isSupported is true',
    (WidgetTester tester) async {
      final controller = TextEditingController(text: 'one two three');
      addTearDown(controller.dispose);
      late BuildContext buildContext;
      await tester.pumpWidget(
        _buildSystemContextMenuTestApp(
          supportsShowingSystemContextMenu: true,
          child: Builder(
            builder: (BuildContext context) {
              buildContext = context;
              return TestTextField(
                controller: controller,
                contextMenuBuilder: (BuildContext context, EditableTextState editableTextState) {
                  return SystemContextMenu.editableText(editableTextState: editableTextState);
                },
              );
            },
          ),
        ),
      );

      expect(SystemContextMenu.isSupported(buildContext), switch (defaultTargetPlatform) {
        TargetPlatform.iOS || TargetPlatform.android => isTrue,
        _ => isFalse,
      });
    },
    skip: kIsWeb, // [intended] SystemContextMenu is not supported on web.
    variant: TargetPlatformVariant.all(),
  );

  for (final readOnly in <bool>[true, false]) {
    testWidgets(
      'read only fields do not support the system context menu',
      (WidgetTester tester) async {
        await tester.pumpWidget(
          _buildSystemContextMenuTestApp(
            supportsShowingSystemContextMenu: true,
            child: TestTextField(readOnly: readOnly),
          ),
        );

        final EditableTextState editableTextState = tester.state(find.byType(EditableText));
        expect(SystemContextMenu.isSupportedByField(editableTextState), switch (readOnly) {
          true => isFalse,
          false => isTrue,
        });
      },
      skip: kIsWeb, // [intended] SystemContextMenu is not supported on web.
      variant: const TargetPlatformVariant(<TargetPlatform>{
        TargetPlatform.iOS,
        TargetPlatform.android,
      }),
    );
  }

  // Regression test for https://github.com/flutter/flutter/issues/170521.
  testWidgets(
    'when supportsShowingSystemContextMenu is false, SystemContextMenu throws',
    (WidgetTester tester) async {
      final controller = TextEditingController(text: 'one two three');
      addTearDown(controller.dispose);
      await tester.pumpWidget(
        _buildSystemContextMenuTestApp(
          child: Center(
            child: TestTextField(
              controller: controller,
              contextMenuBuilder: (BuildContext context, EditableTextState editableTextState) {
                return SystemContextMenu.editableText(editableTextState: editableTextState);
              },
            ),
          ),
        ),
      );

      expect(find.byType(SystemContextMenu), findsNothing);

      await tester.tap(find.byType(TestTextField));
      final EditableTextState state = tester.state<EditableTextState>(find.byType(EditableText));
      expect(state.showToolbar(), true);
      await tester.pump();

      expect(tester.takeException(), isAssertionError);
    },
    skip: kIsWeb, // [intended] SystemContextMenu is not supported on web.
  );

  testWidgets(
    'can use custom menu items',
    (WidgetTester tester) async {
      var customAction1Called = false;
      var customAction2Called = false;
      final itemsReceived = <List<IOSSystemContextMenuItemData>>[];

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (MethodCall methodCall) async {
          switch (methodCall.method) {
            case 'ContextMenu.showSystemContextMenu':
              final arguments = methodCall.arguments as Map<String, dynamic>;
              final untypedItems = arguments['items'] as List<dynamic>;
              final List<IOSSystemContextMenuItemData> lastItems = untypedItems.map((
                dynamic value,
              ) {
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

      final items = <IOSSystemContextMenuItem>[
        const IOSSystemContextMenuItemCopy(),
        IOSSystemContextMenuItemCustom(
          title: 'Custom Action 1',
          onPressed: () {
            customAction1Called = true;
          },
        ),
        IOSSystemContextMenuItemCustom(
          title: 'Custom Action 2',
          onPressed: () {
            customAction2Called = true;
          },
        ),
      ];

      final controller = TextEditingController(text: 'test text');
      addTearDown(controller.dispose);

      await tester.pumpWidget(
        _buildSystemContextMenuTestApp(
          supportsShowingSystemContextMenu: true,
          child: Center(
            child: TestTextField(
              controller: controller,
              contextMenuBuilder: (BuildContext context, EditableTextState editableTextState) {
                return SystemContextMenu.editableText(
                  editableTextState: editableTextState,
                  items: items,
                );
              },
            ),
          ),
        ),
      );

      await tester.tap(find.byType(TestTextField));
      final EditableTextState state = tester.state<EditableTextState>(find.byType(EditableText));
      expect(state.showToolbar(), true);
      await tester.pump();

      expect(find.byType(SystemContextMenu), findsOneWidget);
      expect(itemsReceived, hasLength(1));
      expect(itemsReceived.last, hasLength(3));

      expect(itemsReceived.last[0], equals(const IOSSystemContextMenuItemDataCopy()));
      expect(itemsReceived.last[1], isA<IOSSystemContextMenuItemDataCustom>());
      expect(
        (itemsReceived.last[1] as IOSSystemContextMenuItemDataCustom).title,
        'Custom Action 1',
      );
      expect(itemsReceived.last[2], isA<IOSSystemContextMenuItemDataCustom>());
      expect(
        (itemsReceived.last[2] as IOSSystemContextMenuItemDataCustom).title,
        'Custom Action 2',
      );

      final customItem1 = items[1] as IOSSystemContextMenuItemCustom;
      final customItem2 = items[2] as IOSSystemContextMenuItemCustom;

      ByteData? message = const JSONMethodCodec().encodeMethodCall(
        MethodCall('ContextMenu.onPerformCustomAction', <dynamic>[
          0,
          customItem1.hashCode.toString(),
        ]),
      );
      await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.handlePlatformMessage(
        'flutter/platform',
        message,
        (_) {},
      );
      expect(customAction1Called, isTrue);
      expect(customAction2Called, isFalse);

      message = const JSONMethodCodec().encodeMethodCall(
        MethodCall('ContextMenu.onPerformCustomAction', <dynamic>[
          0,
          customItem2.hashCode.toString(),
        ]),
      );
      await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.handlePlatformMessage(
        'flutter/platform',
        message,
        (_) {},
      );
      expect(customAction2Called, isTrue);

      state.hideToolbar();
      await tester.pump();
      expect(find.byType(SystemContextMenu), findsNothing);
    },
    skip: kIsWeb, // [intended]
    variant: TargetPlatformVariant.only(TargetPlatform.iOS),
  );

  testWidgets(
    'system context menu auto-closes after custom action',
    (WidgetTester tester) async {
      var customActionCalled = false;
      final controller = TextEditingController(text: 'test text');
      addTearDown(controller.dispose);

      await tester.pumpWidget(
        _buildSystemContextMenuTestApp(
          supportsShowingSystemContextMenu: true,
          child: Center(
            child: TestTextField(
              controller: controller,
              contextMenuBuilder: (BuildContext context, EditableTextState editableTextState) {
                return SystemContextMenu.editableText(
                  editableTextState: editableTextState,
                  items: <IOSSystemContextMenuItem>[
                    IOSSystemContextMenuItemCustom(
                      title: 'Test Action',
                      onPressed: () {
                        customActionCalled = true;
                      },
                    ),
                  ],
                );
              },
            ),
          ),
        ),
      );

      await tester.tap(find.byType(TestTextField));
      final EditableTextState state = tester.state<EditableTextState>(find.byType(EditableText));
      expect(state.showToolbar(), true);
      await tester.pump();

      expect(find.byType(SystemContextMenu), findsOneWidget);

      final SystemContextMenu menu = tester.widget<SystemContextMenu>(
        find.byType(SystemContextMenu),
      );
      final item = menu.items[0] as IOSSystemContextMenuItemCustom;

      final ByteData message = const JSONMethodCodec().encodeMethodCall(
        MethodCall('ContextMenu.onPerformCustomAction', <dynamic>[0, item.hashCode.toString()]),
      );
      await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.handlePlatformMessage(
        'flutter/platform',
        message,
        (_) {},
      );

      expect(customActionCalled, isTrue);

      // iOS system menus auto-close after custom actions on real devices.
      // Simulate this by sending the platform dismiss message.
      final ByteData? messageBytes = const JSONMessageCodec().encodeMessage(<String, dynamic>{
        'method': 'ContextMenu.onDismissSystemContextMenu',
      });
      await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.handlePlatformMessage(
        'flutter/platform',
        messageBytes,
        (_) {},
      );
      await tester.pump();

      expect(find.byType(SystemContextMenu), findsNothing);
    },
    skip: kIsWeb, // [intended]
    variant: TargetPlatformVariant.only(TargetPlatform.iOS),
  );

  testWidgets(
    'can trigger custom menu action through platform channel message',
    (WidgetTester tester) async {
      final controller = TextEditingController(text: 'one two three');
      addTearDown(controller.dispose);

      var customActionCalled = false;
      final items = <IOSSystemContextMenuItem>[
        const IOSSystemContextMenuItemCut(),
        IOSSystemContextMenuItemCustom(
          title: 'Test Action',
          onPressed: () {
            customActionCalled = true;
          },
        ),
      ];

      await tester.pumpWidget(
        _buildSystemContextMenuTestApp(
          supportsShowingSystemContextMenu: true,
          child: Center(
            child: TestTextField(
              controller: controller,
              contextMenuBuilder: (BuildContext context, EditableTextState editableTextState) {
                return SystemContextMenu.editableText(
                  editableTextState: editableTextState,
                  items: items,
                );
              },
            ),
          ),
        ),
      );

      await tester.tap(find.byType(TestTextField));
      await tester.pumpAndSettle();

      const selection = TextSelection(baseOffset: 0, extentOffset: 3);
      controller.selection = selection;
      await tester.longPress(find.byType(TestTextField));
      await tester.pumpAndSettle();

      expect(find.byType(SystemContextMenu), findsOneWidget);

      final customItem = items[1] as IOSSystemContextMenuItemCustom;
      final callbackId = customItem.hashCode.toString();

      final ByteData message = const JSONMethodCodec().encodeMethodCall(
        MethodCall('ContextMenu.onPerformCustomAction', <dynamic>[0, callbackId]),
      );
      await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.handlePlatformMessage(
        'flutter/platform',
        message,
        (_) {},
      );

      expect(customActionCalled, isTrue);

      // Verify menu closes after custom action.
      // Simulate platform dismiss message for consistency.
      final ByteData? dismissMessage = const JSONMessageCodec().encodeMessage(<String, dynamic>{
        'method': 'ContextMenu.onDismissSystemContextMenu',
      });
      await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.handlePlatformMessage(
        'flutter/platform',
        dismissMessage,
        (_) {},
      );
      await tester.pump();
      expect(find.byType(SystemContextMenu), findsNothing);
    },
    skip: kIsWeb, // [intended]
    variant: TargetPlatformVariant.only(TargetPlatform.iOS),
  );

  testWidgets(
    'two TextFields can have different custom SystemContextMenu items',
    (WidgetTester tester) async {
      var field1ActionCalled = false;
      var field2ActionCalled = false;

      final controller1 = TextEditingController(text: 'Field 1 text');
      final controller2 = TextEditingController(text: 'Field 2 text');
      addTearDown(() {
        controller1.dispose();
        controller2.dispose();
      });

      await tester.pumpWidget(
        _buildSystemContextMenuTestApp(
          supportsShowingSystemContextMenu: true,
          child: Column(
            children: <Widget>[
              TestTextField(
                controller: controller1,
                contextMenuBuilder: (BuildContext context, EditableTextState editableTextState) {
                  return SystemContextMenu.editableText(
                    editableTextState: editableTextState,
                    items: <IOSSystemContextMenuItem>[
                      IOSSystemContextMenuItemCustom(
                        title: 'Field 1 Action',
                        onPressed: () {
                          field1ActionCalled = true;
                        },
                      ),
                    ],
                  );
                },
              ),
              TestTextField(
                controller: controller2,
                contextMenuBuilder: (BuildContext context, EditableTextState editableTextState) {
                  return SystemContextMenu.editableText(
                    editableTextState: editableTextState,
                    items: <IOSSystemContextMenuItem>[
                      IOSSystemContextMenuItemCustom(
                        title: 'Field 2 Action',
                        onPressed: () {
                          field2ActionCalled = true;
                        },
                      ),
                    ],
                  );
                },
              ),
            ],
          ),
        ),
      );

      await tester.longPress(find.byType(TestTextField).first);
      await tester.pump();
      expect(find.byType(SystemContextMenu), findsOneWidget);

      final SystemContextMenu menu1 = tester.widget<SystemContextMenu>(
        find.byType(SystemContextMenu),
      );
      final item1 = menu1.items[0] as IOSSystemContextMenuItemCustom;

      ByteData message = const JSONMethodCodec().encodeMethodCall(
        MethodCall('ContextMenu.onPerformCustomAction', <dynamic>[0, item1.hashCode.toString()]),
      );
      await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.handlePlatformMessage(
        'flutter/platform',
        message,
        (_) {},
      );

      expect(field1ActionCalled, isTrue);
      expect(field2ActionCalled, isFalse);

      final ByteData? messageBytes1 = const JSONMessageCodec().encodeMessage(<String, dynamic>{
        'method': 'ContextMenu.onDismissSystemContextMenu',
      });
      await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.handlePlatformMessage(
        'flutter/platform',
        messageBytes1,
        (_) {},
      );
      await tester.pump();

      field1ActionCalled = false;

      await tester.longPress(find.byType(TestTextField).last);
      await tester.pump();
      expect(find.byType(SystemContextMenu), findsOneWidget);

      final SystemContextMenu menu2 = tester.widget<SystemContextMenu>(
        find.byType(SystemContextMenu),
      );
      final item2 = menu2.items[0] as IOSSystemContextMenuItemCustom;

      message = const JSONMethodCodec().encodeMethodCall(
        MethodCall('ContextMenu.onPerformCustomAction', <dynamic>[0, item2.hashCode.toString()]),
      );
      await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.handlePlatformMessage(
        'flutter/platform',
        message,
        (_) {},
      );

      expect(field1ActionCalled, isFalse);
      expect(field2ActionCalled, isTrue);

      final ByteData? messageBytes2 = const JSONMessageCodec().encodeMessage(<String, dynamic>{
        'method': 'ContextMenu.onDismissSystemContextMenu',
      });
      await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.handlePlatformMessage(
        'flutter/platform',
        messageBytes2,
        (_) {},
      );
      await tester.pump();

      expect(find.byType(SystemContextMenu), findsNothing);
    },
    skip: kIsWeb, // [intended]
    variant: TargetPlatformVariant.only(TargetPlatform.iOS),
  );

  testWidgets(
    'Default iOS SystemContextMenu includes Share for non-empty selection',
    (WidgetTester tester) async {
      final itemsReceived = <List<IOSSystemContextMenuItemData>>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (MethodCall methodCall) async {
          if (methodCall.method == 'ContextMenu.showSystemContextMenu') {
            final arguments = methodCall.arguments as Map<String, dynamic>;
            final untypedItems = arguments['items'] as List<dynamic>;
            final lastItems = <IOSSystemContextMenuItemData>[
              for (final dynamic value in untypedItems)
                systemContextMenuItemDataFromJson(value as Map<String, dynamic>),
            ];
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

      final controller = TextEditingController(text: 'Hello world');
      addTearDown(controller.dispose);
      await tester.pumpWidget(
        _buildSystemContextMenuTestApp(
          supportsShowingSystemContextMenu: true,
          child: Center(
            child: TestTextField(
              controller: controller,
              contextMenuBuilder: (BuildContext context, EditableTextState editableTextState) {
                return SystemContextMenu.editableText(editableTextState: editableTextState);
              },
            ),
          ),
        ),
      );

      // Focus the field first (this establishes the TextInputConnection).
      await tester.tap(find.byType(TestTextField));
      await tester.pump();
      final EditableTextState state = tester.state<EditableTextState>(find.byType(EditableText));

      // Set a non-empty selection to enable sharing.
      controller.selection = const TextSelection(baseOffset: 0, extentOffset: 5); // "Hello"
      await tester.pump();

      // Nit: ensure no platform message sent before showing toolbar.
      expect(itemsReceived, isEmpty);

      // Show the context menu.
      expect(state.showToolbar(), true);
      await tester.pump();

      // Assert that the platform message included a Share item.
      expect(itemsReceived, isNotEmpty);
      expect(itemsReceived.last, contains(isA<IOSSystemContextMenuItemDataShare>()));
    },
    skip: kIsWeb, // [intended]
    variant: const TargetPlatformVariant(<TargetPlatform>{
      TargetPlatform.iOS,
      TargetPlatform.android,
    }),
  );

  testWidgets(
    'Android default items order Share before Select All and include ProcessText actions with stable callbacks',
    (WidgetTester tester) async {
      final itemsReceived = <List<IOSSystemContextMenuItemData>>[];
      String? lastProcessedId;
      String? lastProcessedText;

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.processText,
        (MethodCall methodCall) async {
          switch (methodCall.method) {
            case 'ProcessText.queryTextActions':
              return <String, String>{'translate_id': 'Translate', 'define_id': 'Define'};
            case 'ProcessText.processTextAction':
              final args = methodCall.arguments as List<dynamic>;
              lastProcessedId = args[0] as String;
              lastProcessedText = args[1] as String;
              return 'HOLA';
            default:
              return null;
          }
        },
      );
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (MethodCall methodCall) async {
          if (methodCall.method == 'Clipboard.hasStrings') {
            return <String, bool>{'value': true};
          }
          if (methodCall.method == 'ContextMenu.showSystemContextMenu') {
            final arguments = methodCall.arguments as Map<String, dynamic>;
            final untypedItems = arguments['items'] as List<dynamic>;
            itemsReceived.add(
              untypedItems.map((dynamic value) {
                final itemJson = value as Map<String, dynamic>;
                return systemContextMenuItemDataFromJson(itemJson);
              }).toList(),
            );
          }
          return null;
        },
      );
      addTearDown(() {
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
          SystemChannels.processText,
          null,
        );
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
          SystemChannels.platform,
          null,
        );
      });

      final controller = TextEditingController(text: 'Hello world');
      addTearDown(controller.dispose);
      await tester.pumpWidget(
        _buildSystemContextMenuTestApp(
          supportsShowingSystemContextMenu: true,
          child: Center(
            child: TestTextField(
              controller: controller,
              contextMenuBuilder: (BuildContext context, EditableTextState editableTextState) {
                return SystemContextMenu.editableText(editableTextState: editableTextState);
              },
            ),
          ),
        ),
      );
      // Let _initProcessTextActions complete.
      await tester.pump();

      await tester.tap(find.byType(TestTextField));
      await tester.pump();
      final EditableTextState state = tester.state<EditableTextState>(find.byType(EditableText));

      controller.selection = const TextSelection(baseOffset: 0, extentOffset: 5);
      await tester.pump();

      expect(state.showToolbar(), isTrue);
      await tester.pump();

      expect(itemsReceived, hasLength(1));
      expect(itemsReceived.last, <Matcher>[
        isA<IOSSystemContextMenuItemDataCut>(),
        isA<IOSSystemContextMenuItemDataCopy>(),
        isA<IOSSystemContextMenuItemDataPaste>(),
        isA<IOSSystemContextMenuItemDataShare>(),
        isA<IOSSystemContextMenuItemDataSelectAll>(),
        isA<IOSSystemContextMenuItemDataCustom>().having(
          (IOSSystemContextMenuItemDataCustom item) => item.title,
          'title',
          'Translate',
        ),
        isA<IOSSystemContextMenuItemDataCustom>().having(
          (IOSSystemContextMenuItemDataCustom item) => item.title,
          'title',
          'Define',
        ),
      ]);

      // Rebuilding without changing selection or items should deduplicate and NOT re-send
      // showSystemContextMenu because ProcessText callbacks are cached per action ID.
      (tester.element(find.byType(SystemContextMenu)) as StatefulElement).markNeedsBuild();
      await tester.pump();
      expect(itemsReceived, hasLength(1));

      // Trigger the ProcessText custom action via SystemContextMenu.getDefaultItems callback ID.
      final List<IOSSystemContextMenuItem> defaultItems = SystemContextMenu.getDefaultItems(state);
      final translateItem = defaultItems[5] as IOSSystemContextMenuItemCustom;
      final translateData =
          translateItem.getData(const DefaultWidgetsLocalizations())
              as IOSSystemContextMenuItemDataCustom;
      final ByteData performActionBytes = const JSONMethodCodec().encodeMethodCall(
        MethodCall('ContextMenu.onPerformCustomAction', <dynamic>[
          0,
          translateData.callbackId,
        ]),
      );
      await binding.defaultBinaryMessenger.handlePlatformMessage(
        'flutter/platform',
        performActionBytes,
        (ByteData? _) {},
      );
      await tester.pump();

      expect(lastProcessedId, 'translate_id');
      expect(lastProcessedText, 'Hello');
      expect(controller.text, 'HOLA world');
    },
    skip: kIsWeb, // [intended]
    variant: TargetPlatformVariant.only(TargetPlatform.android),
  );

  testWidgets(
    'late ContextMenu.onDismissSystemContextMenu after toolbar is hidden does not throw',
    (WidgetTester tester) async {
      final controller = TextEditingController(text: 'Hello world');
      addTearDown(controller.dispose);
      await tester.pumpWidget(
        _buildSystemContextMenuTestApp(
          supportsShowingSystemContextMenu: true,
          child: Center(
            child: TestTextField(
              controller: controller,
              contextMenuBuilder: (BuildContext context, EditableTextState editableTextState) {
                return SystemContextMenu.editableText(editableTextState: editableTextState);
              },
            ),
          ),
        ),
      );

      await tester.tap(find.byType(TestTextField));
      await tester.pump();
      final EditableTextState state = tester.state<EditableTextState>(find.byType(EditableText));
      controller.selection = const TextSelection(baseOffset: 0, extentOffset: 5);
      await tester.pump();

      expect(state.showToolbar(), isTrue);
      await tester.pump();

      // Hide toolbar from Dart first (unregistering the SystemContextMenuClient),
      // then simulate an in-flight platform dismissal message arriving afterwards.
      state.hideToolbar();
      await tester.pump();

      final ByteData dismissBytes = const JSONMethodCodec().encodeMethodCall(
        const MethodCall('ContextMenu.onDismissSystemContextMenu', <dynamic>[0]),
      );
      await binding.defaultBinaryMessenger.handlePlatformMessage(
        'flutter/platform',
        dismissBytes,
        (ByteData? _) {},
      );
      await tester.pump();
      expect(tester.takeException(), isNull);
    },
    skip: kIsWeb, // [intended]
    variant: const TargetPlatformVariant(<TargetPlatform>{
      TargetPlatform.iOS,
      TargetPlatform.android,
    }),
  );

  testWidgets(
    'Android getDefaultItems handles empty ProcessText actions and obscureText fields',
    (WidgetTester tester) async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (MethodCall methodCall) async {
          if (methodCall.method == 'Clipboard.hasStrings') {
            return <String, bool>{'value': true};
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

      final controller = TextEditingController(text: 'secret text');
      addTearDown(controller.dispose);

      // 1. Non-obscured field with no ProcessText actions.
      await tester.pumpWidget(
        _buildSystemContextMenuTestApp(
          supportsShowingSystemContextMenu: true,
          child: Center(
            child: TestTextField(
              controller: controller,
              contextMenuBuilder: (BuildContext context, EditableTextState editableTextState) {
                return SystemContextMenu.editableText(editableTextState: editableTextState);
              },
            ),
          ),
        ),
      );
      await tester.pump();
      await tester.tap(find.byType(TestTextField));
      await tester.pump();
      controller.selection = const TextSelection(baseOffset: 0, extentOffset: 6);
      await tester.pump();

      EditableTextState state = tester.state<EditableTextState>(find.byType(EditableText));
      expect(SystemContextMenu.getDefaultItems(state), <Matcher>[
        isA<IOSSystemContextMenuItemCut>(),
        isA<IOSSystemContextMenuItemCopy>(),
        isA<IOSSystemContextMenuItemPaste>(),
        isA<IOSSystemContextMenuItemShare>(),
        isA<IOSSystemContextMenuItemSelectAll>(),
      ]);

      // 2. Obscured field omits Cut, Copy, Share, and ProcessText actions.
      final focusNode = FocusNode();
      addTearDown(focusNode.dispose);
      await tester.pumpWidget(
        _buildSystemContextMenuTestApp(
          supportsShowingSystemContextMenu: true,
          child: Center(
            child: EditableText(
              controller: controller,
              focusNode: focusNode,
              obscureText: true,
              style: const TextStyle(),
              cursorColor: const Color(0xFF000000),
              backgroundCursorColor: const Color(0xFF888888),
              contextMenuBuilder: (BuildContext context, EditableTextState editableTextState) {
                return SystemContextMenu.editableText(editableTextState: editableTextState);
              },
            ),
          ),
        ),
      );
      focusNode.requestFocus();
      await tester.pump();
      controller.selection = const TextSelection(baseOffset: 0, extentOffset: 6);
      await tester.pump();

      state = tester.state<EditableTextState>(find.byType(EditableText));
      expect(SystemContextMenu.getDefaultItems(state), <Matcher>[
        isA<IOSSystemContextMenuItemPaste>(),
        isA<IOSSystemContextMenuItemSelectAll>(),
      ]);
    },
    skip: kIsWeb, // [intended]
    variant: TargetPlatformVariant.only(TargetPlatform.android),
  );

  testWidgets(
    'ProcessText action completing after EditableText unmount is safely ignored',
    (WidgetTester tester) async {
      final processCompleter = Completer<String?>();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.processText,
        (MethodCall methodCall) async {
          switch (methodCall.method) {
            case 'ProcessText.queryTextActions':
              return <String, String>{'async_id': 'AsyncTransform'};
            case 'ProcessText.processTextAction':
              return processCompleter.future;
          }
          return null;
        },
      );
      addTearDown(() {
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
          SystemChannels.processText,
          null,
        );
      });

      final controller = TextEditingController(text: 'Hello world');
      addTearDown(controller.dispose);
      await tester.pumpWidget(
        _buildSystemContextMenuTestApp(
          supportsShowingSystemContextMenu: true,
          child: Center(
            child: TestTextField(
              controller: controller,
              contextMenuBuilder: (BuildContext context, EditableTextState editableTextState) {
                return SystemContextMenu.editableText(editableTextState: editableTextState);
              },
            ),
          ),
        ),
      );
      await tester.pump();
      await tester.tap(find.byType(TestTextField));
      await tester.pump();
      controller.selection = const TextSelection(baseOffset: 0, extentOffset: 5);
      await tester.pump();

      final EditableTextState state = tester.state<EditableTextState>(find.byType(EditableText));
      expect(state.showToolbar(), isTrue);
      await tester.pump();

      final List<IOSSystemContextMenuItem> defaultItems = SystemContextMenu.getDefaultItems(state);
      final IOSSystemContextMenuItemCustom asyncCustomItem =
          defaultItems.whereType<IOSSystemContextMenuItemCustom>().single;
      final String callbackId =
          (asyncCustomItem.getData(const DefaultWidgetsLocalizations())
                  as IOSSystemContextMenuItemDataCustom)
              .callbackId;

      final ByteData performActionBytes = const JSONMethodCodec().encodeMethodCall(
        MethodCall('ContextMenu.onPerformCustomAction', <dynamic>[0, callbackId]),
      );
      await binding.defaultBinaryMessenger.handlePlatformMessage(
        'flutter/platform',
        performActionBytes,
        (ByteData? _) {},
      );
      await tester.pump();

      // Unmount EditableText while ProcessText activity result is still pending.
      await tester.pumpWidget(const SizedBox.shrink());
      processCompleter.complete('REPLACED');
      await tester.pump();

      expect(tester.takeException(), isNull);
      expect(controller.text, 'Hello world');
    },
    skip: kIsWeb, // [intended]
    variant: TargetPlatformVariant.only(TargetPlatform.android),
  );
}
