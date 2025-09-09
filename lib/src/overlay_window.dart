import 'dart:async';
import 'dart:developer';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_overlay_window/src/models/overlay_position.dart';
import 'package:flutter_overlay_window/src/overlay_config.dart';

class FlutterOverlayWindow {
  FlutterOverlayWindow._();

  static final StreamController _controller = StreamController();
  static const MethodChannel _channel = MethodChannel("x-slayer/overlay_channel");

  static MethodChannel _overlayChannel(String engineId) => MethodChannel('x-slayer/overlay/$engineId');
  static const BasicMessageChannel _overlayMessageChannel =
  BasicMessageChannel("x-slayer/overlay_messenger", JSONMessageCodec());

  // flutter_overlay_window.dart (Dart wrapper)
  static const String _baseMessenger = "x-slayer/overlay_messenger";

// Per-engine message channel (what OverlayService uses to rebroadcast)
  static BasicMessageChannel _engineMsg(String engineId) =>
      BasicMessageChannel(
        "x-slayer/overlay_messenger/$engineId",
        JSONMessageCodec(),
      );

  /// Open overLay content
  ///
  /// - Optional arguments:
  ///
  /// `height` the overlay height and default is [WindowSize.fullCover]
  ///
  /// `width` the overlay width and default is [WindowSize.matchParent]
  ///
  /// `alignment` the alignment postion on screen and default is [OverlayAlignment.center]
  ///
  /// `visibilitySecret` the detail displayed in notifications on the lock screen and default is [NotificationVisibility.visibilitySecret]
  ///
  /// `OverlayFlag` the overlay flag and default is [OverlayFlag.defaultFlag]
  ///
  /// `overlayTitle` the notification message and default is "overlay activated"
  ///
  /// `overlayContent` the notification message
  ///
  /// `enableDrag` to enable/disable dragging the overlay over the screen and default is "false"
  ///
  /// `positionGravity` the overlay postion after drag and default is [PositionGravity.none]
  ///
  /// `startPosition` the overlay start position and default is null
  ///
  /// `entrypoint` the Dart top-level function to run for this overlay engine (default: `overlayMain`)
  ///
  /// `engineId` a unique cache key for the engine to use/create (default: `main_engine`)
  ///
  /// `initialRoute` optional initial Flutter route for the overlay engine
  static Future<void> showOverlay({
    int height = WindowSize.fullCover,
    int width = WindowSize.matchParent,
    OverlayAlignment alignment = OverlayAlignment.center,
    NotificationVisibility visibility = NotificationVisibility.visibilitySecret,
    OverlayFlag flag = OverlayFlag.defaultFlag,
    String overlayTitle = "overlay activated",
    String? overlayContent,
    bool enableDrag = false,
    PositionGravity positionGravity = PositionGravity.none,
    OverlayPosition? startPosition,

    // NEW:
    String entrypoint = 'overlayMain',
    String engineId = 'tray_engine',
    String? initialRoute,
    List<String>? dartArgs,

  }) async {
    await _channel.invokeMethod(
      'showOverlay',
      {
        "height": height,
        "width": width,
        "alignment": alignment.name,
        "flag": flag.name,
        "overlayTitle": overlayTitle,
        "overlayContent": overlayContent,
        "enableDrag": enableDrag,
        "notificationVisibility": visibility.name,
        "positionGravity": positionGravity.name,
        "startPosition": startPosition?.toMap(),

        // NEW:
        "entrypoint": entrypoint,
        "engineId": engineId,
        "initialRoute": initialRoute,
        "dartArgs": dartArgs,
      },
    );
  }

  static Future<bool> showYouTubePip(String url) async {
    final ok = await _channel.invokeMethod<bool>('showYouTubePip', {"url": url});
    return ok ?? false;
  }

  /// Check if overlay permission is granted
  static Future<bool> isPermissionGranted() async {
    try {
      return await _channel.invokeMethod<bool>('checkPermission') ?? false;
    } on PlatformException catch (error) {
      log("$error");
      return Future.value(false);
    }
  }

  /// Request overlay permission
  /// it will open the overlay settings page and return `true` once the permission granted.
  static Future<bool?> requestPermission() async {
    try {
      return await _channel.invokeMethod<bool?>('requestPermission');
    } on PlatformException catch (error) {
      log("Error requestPermession: $error");
      rethrow;
    }
  }

  /// Closes overlay if open
  static Future<bool?> closeOverlay({String engineId = 'tray_engine'}) async {
    try {
      final bool? _res = await _channel.invokeMethod('closeOverlay', {
        "engineId": engineId,
      });
      return _res;
    } catch (e) {
      debugPrint('Error while close overlay: $e');
      return false;
    }
  }

  /// Closes overlay if open
  static Future<bool?> closeAllOverlays() async {
    try {
      final bool? _res = await _channel.invokeMethod('closeAllOverlays');
      return _res;
    } catch (e) {
      debugPrint('Error while close overlay: $e');
      return false;
    }
  }

  /// Broadcast data to and from overlay app
  static Future shareData(dynamic data) async {
    return await _overlayMessageChannel.send(data);
  }

  // Stream for a specific engineId
  static Stream<dynamic> overlayListener(String engineId) {
    final ctrl = StreamController.broadcast();
    _engineMsg(engineId).setMessageHandler((message) async {
      ctrl.add(message);
      return true; // ack
    });
    return ctrl.stream;
  }

  /// Update the overlay flag while the overlay in action
  static Future<bool?> updateFlag(OverlayFlag flag, {String engineId = 'tray_engine'}) async {
    final bool? _res = await _overlayChannel(engineId).invokeMethod<bool?>('updateFlag', {'flag': flag.name});
    return _res;
  }

  /// Update the overlay size in the screen
  // flutter_overlay_window.dart
  static Future<bool?> resizeOverlay(int width,
      int height,
      bool enableDrag, {
        int duration = 0,
        String engineId = 'tray_engine',

        // NEW: anchor flags so Java can know which edge to pin
        bool anchorLeft = false, // left grip: true  (pin RIGHT edge)
        bool anchorTop = false, // top grip:  true  (pin BOTTOM edge)
      }) async {
    final bool? res = await _channel.invokeMethod<bool?>(
      'resizeOverlay',
      {
        'engineId': engineId, // ✅ add this
        'width': width,
        'height': height,
        'enableDrag': enableDrag,
        'duration': duration,

        // pass through to Java
        'anchorLeft': anchorLeft,
        'anchorTop': anchorTop,
      },
    );
    return res;
  }

  /// Update the overlay position in the screen
  ///
  /// `position` the new position of the overlay
  ///
  /// `return` true if the position updated successfully
  static Future<bool?> moveOverlay(OverlayPosition position, {String engineId = 'tray_engine'}) async {
    final bool? _res = await _channel.invokeMethod<bool?>(
        'moveOverlay',
        {
          "engineId": engineId,
          ...position.toMap(),
        }

    );
    return _res;
  }

  /// Get the current overlay position
  ///
  /// `return` the current overlay position
  static Future<OverlayPosition> getOverlayPosition({String engineId = 'tray_engine'}) async {
    final Map<Object?, Object?>? _res = await _channel.invokeMethod(
      'getOverlayPosition',
      {
        "engineId": engineId,
      },
    );
    return OverlayPosition.fromMap(_res);
  }

  /// Check if the current overlay is active
  static Future<bool> isActive() async {
    final bool? _res = await _channel.invokeMethod<bool?>('isOverlayActive');
    return _res ?? false;
  }

  /// Dispose overlay stream
  static void disposeOverlayListener() {
    _controller.close();
  }
}
