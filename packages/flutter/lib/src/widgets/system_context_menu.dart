// Copyright 2014 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

/// @docImport 'package:flutter/material.dart';
library;

import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';

import 'basic.dart';
import 'context_menu_button_item.dart';
import 'editable_text.dart';
import 'framework.dart';
import 'localizations.dart';
import 'media_query.dart';
import 'text_selection_toolbar_anchors.dart';

/// Displays the system context menu on top of the Flutter view.
///
/// Currently supported on iOS 16.0 and above (via `UIEditMenuInteraction`) and
/// on Android 17 (API level 37) and above (when `ActionMode.TYPE_FLOATING` is
/// rendered out-of-process by SystemUI's `SelectionToolbarRenderService`), and
/// displays nothing on other platforms unless a [fallbackBuilder] is provided.
///
/// The context menu is the menu that appears, for example, when doing text
/// selection. Flutter typically draws this menu itself, but this class deals
/// with the platform-rendered context menu instead.
///
/// There can only be one system context menu visible at a time. Building this
/// widget when the system context menu is already visible will hide the old one
/// and display this one. A system context menu that is hidden is informed via
/// [onSystemHide].
///
/// Pass [items] to specify the buttons that will appear in the menu. Any items
/// without a title will be given a default title from [WidgetsLocalizations].
///
/// By default, [items] will be set to the result of [getDefaultItems]. This
/// method considers the state of the [EditableTextState] so that, for example,
/// it will only include [SystemContextMenuItemCopy] if there is currently a
/// selection to copy.
///
/// To check if the current device supports showing the system context menu,
/// call [isSupported].
///
/// {@tool dartpad}
/// This example shows how to create a [TextField] that uses the system context
/// menu where supported and falls back to a Flutter-rendered toolbar otherwise.
///
/// ** See code in examples/api/lib/widgets/system_context_menu/system_context_menu.0.dart **
/// {@end-tool}
///
/// See also:
///
///  * [SystemContextMenuController], which directly controls the hiding and
///    showing of the system context menu.
class SystemContextMenu extends StatefulWidget {
  /// Creates an instance of [SystemContextMenu] that points to the given
  /// [anchor].
  const SystemContextMenu._({
    super.key,
    required this.anchor,
    required this.items,
    this.onSystemHide,
    this.fallbackBuilder,
    SystemContextMenuActionCallback? onSystemAction,
    // ignore: prefer_initializing_formals, _onSystemAction is intentionally internal to SystemContextMenu.editableText.
  }) : _onSystemAction = onSystemAction;

  /// Creates an instance of [SystemContextMenu] for the field indicated by the
  /// given [EditableTextState].
  ///
  /// If the platform declines to show the system context menu at runtime (for
  /// example, when the host window cannot start an `ActionMode`),
  /// [fallbackBuilder] is built instead for the lifetime of this menu state if
  /// provided; otherwise [EditableTextState.hideToolbar] is called to reset
  /// toolbar visibility.
  factory SystemContextMenu.editableText({
    Key? key,
    required EditableTextState editableTextState,
    List<SystemContextMenuItem>? items,
    WidgetBuilder? fallbackBuilder,
  }) {
    final (startGlyphHeight: double startGlyphHeight, endGlyphHeight: double endGlyphHeight) =
        editableTextState.getGlyphHeights();

    return SystemContextMenu._(
      key: key,
      anchor: TextSelectionToolbarAnchors.getSelectionRect(
        editableTextState.renderEditable,
        startGlyphHeight,
        endGlyphHeight,
        editableTextState.renderEditable.getEndpointsForSelection(
          editableTextState.textEditingValue.selection,
        ),
      ),
      items: items ?? getDefaultItems(editableTextState),
      onSystemHide: () => editableTextState.hideToolbar(false),
      fallbackBuilder: fallbackBuilder,
      onSystemAction: (String action, String? text) {
        editableTextState.handleSystemContextMenuAction(action, text: text);
      },
    );
  }

  /// The [Rect] that the context menu should point to.
  final Rect anchor;

  /// A list of the items to be displayed in the system context menu.
  ///
  /// When passed, items will be shown regardless of the state of text input.
  /// For example, [SystemContextMenuItemCopy] will produce a copy button even
  /// when there is no selection to copy. Use [EditableTextState] and/or the
  /// result of [getDefaultItems] to add and remove items based on the state of
  /// the input.
  ///
  /// Defaults to the result of [getDefaultItems].
  ///
  /// To add custom menu items, pass [SystemContextMenuItemCustom] instances in
  /// the [items] list. Each custom item requires a title and an onPressed
  /// callback.
  ///
  /// See also:
  ///
  ///  * [SystemContextMenuItemCustom], which creates custom menu items.
  final List<SystemContextMenuItem> items;

  /// Called when the system hides this context menu.
  ///
  /// For example, tapping outside of the context menu typically causes the
  /// system to hide the menu.
  ///
  /// This is not called when showing a new system context menu causes another
  /// to be hidden.
  final VoidCallback? onSystemHide;

  /// An optional builder for a Flutter-rendered fallback context menu when the
  /// platform declines to show the system context menu at runtime.
  ///
  /// Once activated, the fallback remains displayed until this
  /// [SystemContextMenu] state is disposed.
  final WidgetBuilder? fallbackBuilder;

  // Intentionally private to SystemContextMenu.editableText (not part of the
  // public widget API yet).
  final SystemContextMenuActionCallback? _onSystemAction;

  /// Whether the current view supports showing the system context menu.
  ///
  /// Driven by [MediaQuery.maybeSupportsShowingSystemContextMenu], which is
  /// currently reported as true on iOS 16.0+ and Android 17+ (API level 37+).
  ///
  /// See also:
  ///
  ///  * [isSupportedByField], which uses this method and determines whether an
  ///    individual [EditableTextState] supports the system context menu.
  static bool isSupported(BuildContext context) {
    return MediaQuery.maybeSupportsShowingSystemContextMenu(context) ?? false;
  }

  /// Whether the given field supports showing the system context menu.
  ///
  /// Currently [SystemContextMenu] is only supported with an active
  /// [TextInputConnection]. In cases where this isn't possible, such as in a
  /// read-only field, fall back to using a Flutter-rendered context menu like
  /// [AdaptiveTextSelectionToolbar].
  ///
  /// See also:
  ///
  ///  * [isSupported], which is used by this method and determines whether the
  ///    platform in general supports showing the system context menu.
  static bool isSupportedByField(EditableTextState editableTextState) {
    return !editableTextState.widget.readOnly && isSupported(editableTextState.context);
  }

  /// The default [items] for the given [EditableTextState].
  ///
  /// For example, [SystemContextMenuItemCopy] will only be included when the
  /// field represented by the [EditableTextState] has a selection.
  /// [ContextMenuButtonKind.custom] items with a non-empty label and non-null
  /// callback reported by [EditableTextState.contextMenuButtonItems] (such as
  /// Android `ACTION_PROCESS_TEXT` actions from [ProcessTextService]) are
  /// mapped to [SystemContextMenuItemCustom] on both iOS and Android.
  ///
  /// See also:
  ///
  ///  * [EditableTextState.contextMenuButtonItems], which provides the default
  ///    [ContextMenuButtonItem]s for the Flutter-rendered context menu.
  static List<SystemContextMenuItem> getDefaultItems(EditableTextState editableTextState) {
    final items = <SystemContextMenuItem>[];

    // Use the generic Flutter-rendered context menu model as the single source of truth.
    for (final ContextMenuButtonItem button in editableTextState.contextMenuButtonItems) {
      switch (button.kind) {
        case ContextMenuButtonKind.copy:
          items.add(const SystemContextMenuItemCopy());
        case ContextMenuButtonKind.cut:
          items.add(const SystemContextMenuItemCut());
        case ContextMenuButtonKind.paste:
          items.add(const SystemContextMenuItemPaste());
        case ContextMenuButtonKind.selectAll:
          items.add(const SystemContextMenuItemSelectAll());
        case ContextMenuButtonKind.lookUp:
          items.add(const SystemContextMenuItemLookUp());
        case ContextMenuButtonKind.searchWeb:
          items.add(const SystemContextMenuItemSearchWeb());
        case ContextMenuButtonKind.share:
          items.add(const SystemContextMenuItemShare());
        case ContextMenuButtonKind.liveTextInput:
          items.add(const SystemContextMenuItemLiveText());
        case ContextMenuButtonKind.delete:
        // No native iOS or Android system menu button for Delete — intentionally ignored.
        case ContextMenuButtonKind.translate:
        // Not mapped to a system menu item yet.
        case ContextMenuButtonKind.custom:
          if (button.label != null && button.label!.isNotEmpty && button.onPressed != null) {
            items.add(
              SystemContextMenuItemCustom(
                id: button.id,
                title: button.label!,
                onPressed: button.onPressed!,
              ),
            );
          }
        case _:
        // Kinds added in the future are ignored until they're mapped here.
      }
    }

    return items;
  }

  @override
  State<SystemContextMenu> createState() => _SystemContextMenuState();
}

class _SystemContextMenuState extends State<SystemContextMenu> {
  late final SystemContextMenuController _systemContextMenuController;
  bool _platformRejectedShow = false;
  int _showRequestId = 0;

  @override
  void initState() {
    super.initState();
    _systemContextMenuController = SystemContextMenuController(
      onSystemHide: () => widget.onSystemHide?.call(),
      onSystemAction: (String action, String? text) => widget._onSystemAction?.call(action, text),
    );
  }

  @override
  void dispose() {
    _systemContextMenuController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    assert(SystemContextMenu.isSupported(context));

    if (_platformRejectedShow) {
      return widget.fallbackBuilder?.call(context) ?? const SizedBox.shrink();
    }

    if (widget.items.isNotEmpty) {
      final WidgetsLocalizations localizations = WidgetsLocalizations.of(context);
      final List<SystemContextMenuItemData> itemDatas = widget.items
          .map((SystemContextMenuItem item) => item.getData(localizations))
          .toList();
      final int requestId = ++_showRequestId;
      _systemContextMenuController.showWithItems(widget.anchor, itemDatas).then((bool shown) {
        if (!mounted || requestId != _showRequestId || shown) {
          return;
        }
        if (widget.fallbackBuilder != null) {
          setState(() {
            _platformRejectedShow = true;
          });
        } else {
          widget.onSystemHide?.call();
        }
      });
    }

    return const SizedBox.shrink();
  }
}

/// Describes a context menu button that will be rendered in the system context
/// menu and not by Flutter itself.
///
/// See also:
///
///  * [SystemContextMenu], a widget that can be used to display the system
///    context menu.
///  * [SystemContextMenuItemData], which performs a similar role but at the
///    method channel level and mirrors the requirements of the method channel
///    API.
///  * [ContextMenuButtonItem], which performs a similar role for Flutter-drawn
///    context menus.
@immutable
sealed class SystemContextMenuItem {
  const SystemContextMenuItem();

  /// The text to display to the user.
  ///
  /// Not exposed for some built-in menu items whose title is always set by the
  /// platform.
  String? get title => null;

  /// Returns the representation of this class used by method channels.
  SystemContextMenuItemData getData(WidgetsLocalizations localizations);

  @override
  int get hashCode => title.hashCode;

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) {
      return true;
    }
    if (other.runtimeType != runtimeType) {
      return false;
    }
    return other is SystemContextMenuItem && other.title == title;
  }
}

/// Creates an instance of [SystemContextMenuItem] for the system's built-in
/// copy button.
///
/// Supported on iOS 16.0 and above, and on Android 17 (API level 37) and above.
///
/// Should only appear when there is a selection that can be copied.
///
/// See also:
///
///  * [SystemContextMenu], a widget that can be used to display the system
///    context menu.
///  * [SystemContextMenuItemDataCopy], which specifies the data to be sent to
///    the platform for this same button.
final class SystemContextMenuItemCopy extends SystemContextMenuItem {
  /// Creates an instance of [SystemContextMenuItemCopy].
  const SystemContextMenuItemCopy();

  @override
  SystemContextMenuItemDataCopy getData(WidgetsLocalizations localizations) {
    return const SystemContextMenuItemDataCopy();
  }
}

/// Creates an instance of [SystemContextMenuItem] for the system's built-in
/// cut button.
///
/// Supported on iOS 16.0 and above, and on Android 17 (API level 37) and above.
///
/// Should only appear when there is a selection that can be cut.
///
/// See also:
///
///  * [SystemContextMenu], a widget that can be used to display the system
///    context menu.
///  * [SystemContextMenuItemDataCut], which specifies the data to be sent to
///    the platform for this same button.
final class SystemContextMenuItemCut extends SystemContextMenuItem {
  /// Creates an instance of [SystemContextMenuItemCut].
  const SystemContextMenuItemCut();

  @override
  SystemContextMenuItemDataCut getData(WidgetsLocalizations localizations) {
    return const SystemContextMenuItemDataCut();
  }
}

/// Creates an instance of [SystemContextMenuItem] for the system's built-in
/// paste button.
///
/// Supported on iOS 16.0 and above, and on Android 17 (API level 37) and above.
///
/// Should only appear when the field can receive pasted content.
///
/// See also:
///
///  * [SystemContextMenu], a widget that can be used to display the system
///    context menu.
///  * [SystemContextMenuItemDataPaste], which specifies the data to be sent to
///    the platform for this same button.
final class SystemContextMenuItemPaste extends SystemContextMenuItem {
  /// Creates an instance of [SystemContextMenuItemPaste].
  const SystemContextMenuItemPaste();

  @override
  SystemContextMenuItemDataPaste getData(WidgetsLocalizations localizations) {
    return const SystemContextMenuItemDataPaste();
  }
}

/// Creates an instance of [SystemContextMenuItem] for the system's built-in
/// select all button.
///
/// Supported on iOS 16.0 and above, and on Android 17 (API level 37) and above.
///
/// Should only appear when the field can have its selection changed.
///
/// See also:
///
///  * [SystemContextMenu], a widget that can be used to display the system
///    context menu.
///  * [SystemContextMenuItemDataSelectAll], which specifies the data to be
///    sent to the platform for this same button.
final class SystemContextMenuItemSelectAll extends SystemContextMenuItem {
  /// Creates an instance of [SystemContextMenuItemSelectAll].
  const SystemContextMenuItemSelectAll();

  @override
  SystemContextMenuItemDataSelectAll getData(WidgetsLocalizations localizations) {
    return const SystemContextMenuItemDataSelectAll();
  }
}

/// Creates an instance of [SystemContextMenuItem] for the system's built-in
/// look up button.
///
/// Supported on iOS 16.0 and above; ignored on Android.
///
/// Should only appear when content is selected.
///
/// The [title] is optional, but it must be specified before being sent to the
/// platform. Typically it should be set to
/// [WidgetsLocalizations.lookUpButtonLabel].
///
/// See also:
///
///  * [SystemContextMenu], a widget that can be used to display the system
///    context menu.
///  * [SystemContextMenuItemDataLookUp], which specifies the data to be sent
///    to the platform for this same button.
final class SystemContextMenuItemLookUp extends SystemContextMenuItem with Diagnosticable {
  /// Creates an instance of [SystemContextMenuItemLookUp].
  const SystemContextMenuItemLookUp({this.title});

  @override
  final String? title;

  @override
  SystemContextMenuItemDataLookUp getData(WidgetsLocalizations localizations) {
    return SystemContextMenuItemDataLookUp(title: title ?? localizations.lookUpButtonLabel);
  }

  @override
  void debugFillProperties(DiagnosticPropertiesBuilder properties) {
    super.debugFillProperties(properties);
    properties.add(DiagnosticsProperty<String>('title', title));
  }
}

/// Creates an instance of [SystemContextMenuItem] for the system's built-in
/// search web button.
///
/// Supported on iOS 16.0 and above; ignored on Android.
///
/// Should only appear when content is selected.
///
/// The [title] is optional, but it must be specified before being sent to the
/// platform. Typically it should be set to
/// [WidgetsLocalizations.searchWebButtonLabel].
///
/// See also:
///
///  * [SystemContextMenu], a widget that can be used to display the system
///    context menu.
///  * [SystemContextMenuItemDataSearchWeb], which specifies the data to be
///    sent to the platform for this same button.
final class SystemContextMenuItemSearchWeb extends SystemContextMenuItem with Diagnosticable {
  /// Creates an instance of [SystemContextMenuItemSearchWeb].
  const SystemContextMenuItemSearchWeb({this.title});

  @override
  final String? title;

  @override
  SystemContextMenuItemDataSearchWeb getData(WidgetsLocalizations localizations) {
    return SystemContextMenuItemDataSearchWeb(title: title ?? localizations.searchWebButtonLabel);
  }

  @override
  void debugFillProperties(DiagnosticPropertiesBuilder properties) {
    super.debugFillProperties(properties);
    properties.add(DiagnosticsProperty<String>('title', title));
  }
}

/// Creates an instance of [SystemContextMenuItem] for the system's built-in
/// share button.
///
/// Supported on iOS 16.0 and above, and on Android 17 (API level 37) and above.
///
/// Opens the system share dialog.
///
/// Should only appear when shareable content is selected.
///
/// The [title] is optional, but it must be specified before being sent to the
/// platform. Typically it should be set to
/// [WidgetsLocalizations.shareButtonLabel].
///
/// See also:
///
///  * [SystemContextMenu], a widget that can be used to display the system
///    context menu.
///  * [SystemContextMenuItemDataShare], which specifies the data to be sent
///    to the platform for this same button.
final class SystemContextMenuItemShare extends SystemContextMenuItem with Diagnosticable {
  /// Creates an instance of [SystemContextMenuItemShare].
  const SystemContextMenuItemShare({this.title});

  @override
  final String? title;

  @override
  SystemContextMenuItemDataShare getData(WidgetsLocalizations localizations) {
    return SystemContextMenuItemDataShare(title: title ?? localizations.shareButtonLabel);
  }

  @override
  void debugFillProperties(DiagnosticPropertiesBuilder properties) {
    super.debugFillProperties(properties);
    properties.add(StringProperty('title', title));
  }
}

/// Creates an instance of [SystemContextMenuItem] for the system's built-in
/// Live Text button.
///
/// Supported on iOS 16.0 and above on devices with camera support; ignored on
/// Android.
///
/// The title and action are both handled by the platform.
///
/// See also:
///
///  * [SystemContextMenu], a widget that can be used to display the system
///    context menu.
///  * [SystemContextMenuItemDataLiveText], which specifies the data to be sent
///    to the platform for this same button.
final class SystemContextMenuItemLiveText extends SystemContextMenuItem {
  /// Creates an instance of [SystemContextMenuItemLiveText].
  const SystemContextMenuItemLiveText();

  @override
  SystemContextMenuItemData getData(WidgetsLocalizations localizations) {
    return const SystemContextMenuItemDataLiveText();
  }
}

/// Creates an instance of [SystemContextMenuItem] for custom action buttons
/// defined by the developer.
///
/// Supported on iOS 16.0 and above, and on Android 17 (API level 37) and above.
///
/// The [title] and [onPressed] callback must be provided. Providing an optional
/// stable [id] allows [SystemContextMenu] to update [onPressed] across rebuilds
/// without re-showing the platform menu.
///
/// {@tool dartpad}
/// This example shows how to add custom menu items to the system context menu.
///
/// ** See code in examples/api/lib/widgets/system_context_menu/system_context_menu.1.dart **
/// {@end-tool}
///
/// See also:
///
///  * [SystemContextMenu], a widget that can be used to display the system
///    context menu.
///  * [SystemContextMenuItemDataCustom], which specifies the data to be sent
///    to the platform for this button.
@immutable
class SystemContextMenuItemCustom extends SystemContextMenuItem with Diagnosticable {
  /// Creates an instance of [SystemContextMenuItemCustom].
  const SystemContextMenuItemCustom({this.id, required this.title, required this.onPressed});

  /// An optional stable identifier for this custom action.
  final String? id;

  @override
  final String title;

  /// The callback that is called when the button is pressed.
  final VoidCallback onPressed;

  @override
  SystemContextMenuItemData getData(WidgetsLocalizations localizations) {
    return SystemContextMenuItemDataCustom(id: id, title: title, onPressed: onPressed);
  }

  @override
  int get hashCode => id != null ? Object.hash(id, title) : Object.hash(title, onPressed);

  @override
  bool operator ==(Object other) {
    if (identical(this, other)) {
      return true;
    }
    if (other.runtimeType != runtimeType) {
      return false;
    }
    if (other is! SystemContextMenuItemCustom) {
      return false;
    }
    if (id != null || other.id != null) {
      return id == other.id && title == other.title;
    }
    return title == other.title && onPressed == other.onPressed;
  }

  @override
  void debugFillProperties(DiagnosticPropertiesBuilder properties) {
    super.debugFillProperties(properties);
    properties.add(StringProperty('id', id, defaultValue: null));
    properties.add(StringProperty('title', title));
    properties.add(ObjectFlagProperty<VoidCallback>.has('onPressed', onPressed));
  }
}

/// Deprecated alias for [SystemContextMenuItem].
@Deprecated(
  'Use SystemContextMenuItem instead. '
  'This feature was deprecated after v3.47.0-0.0.pre.',
)
typedef IOSSystemContextMenuItem = SystemContextMenuItem;

/// Deprecated alias for [SystemContextMenuItemCopy].
@Deprecated(
  'Use SystemContextMenuItemCopy instead. '
  'This feature was deprecated after v3.47.0-0.0.pre.',
)
typedef IOSSystemContextMenuItemCopy = SystemContextMenuItemCopy;

/// Deprecated alias for [SystemContextMenuItemCut].
@Deprecated(
  'Use SystemContextMenuItemCut instead. '
  'This feature was deprecated after v3.47.0-0.0.pre.',
)
typedef IOSSystemContextMenuItemCut = SystemContextMenuItemCut;

/// Deprecated alias for [SystemContextMenuItemPaste].
@Deprecated(
  'Use SystemContextMenuItemPaste instead. '
  'This feature was deprecated after v3.47.0-0.0.pre.',
)
typedef IOSSystemContextMenuItemPaste = SystemContextMenuItemPaste;

/// Deprecated alias for [SystemContextMenuItemSelectAll].
@Deprecated(
  'Use SystemContextMenuItemSelectAll instead. '
  'This feature was deprecated after v3.47.0-0.0.pre.',
)
typedef IOSSystemContextMenuItemSelectAll = SystemContextMenuItemSelectAll;

/// Deprecated alias for [SystemContextMenuItemLookUp].
@Deprecated(
  'Use SystemContextMenuItemLookUp instead. '
  'This feature was deprecated after v3.47.0-0.0.pre.',
)
typedef IOSSystemContextMenuItemLookUp = SystemContextMenuItemLookUp;

/// Deprecated alias for [SystemContextMenuItemSearchWeb].
@Deprecated(
  'Use SystemContextMenuItemSearchWeb instead. '
  'This feature was deprecated after v3.47.0-0.0.pre.',
)
typedef IOSSystemContextMenuItemSearchWeb = SystemContextMenuItemSearchWeb;

/// Deprecated alias for [SystemContextMenuItemShare].
@Deprecated(
  'Use SystemContextMenuItemShare instead. '
  'This feature was deprecated after v3.47.0-0.0.pre.',
)
typedef IOSSystemContextMenuItemShare = SystemContextMenuItemShare;

/// Deprecated alias for [SystemContextMenuItemLiveText].
@Deprecated(
  'Use SystemContextMenuItemLiveText instead. '
  'This feature was deprecated after v3.47.0-0.0.pre.',
)
typedef IOSSystemContextMenuItemLiveText = SystemContextMenuItemLiveText;

/// Deprecated alias for [SystemContextMenuItemCustom].
@Deprecated(
  'Use SystemContextMenuItemCustom instead. '
  'This feature was deprecated after v3.47.0-0.0.pre.',
)
typedef IOSSystemContextMenuItemCustom = SystemContextMenuItemCustom;
