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
        const JSONMessageCodec(),
      );


  static Future<void> registerAsMainApp() async {
    await _channel.invokeMethod('registerMainApp');
  }


  /// Channel for sending messages FROM main app TO overlays
  static const BasicMessageChannel<dynamic> _messengerChannel =
  BasicMessageChannel('x-slayer/overlay_messenger', JSONMessageCodec());

  /// Channel for receiving messages FROM overlays TO main app
  static const BasicMessageChannel<dynamic> _mainAppMessengerChannel =
  BasicMessageChannel('x-slayer/main_app_messenger', JSONMessageCodec());

  /// Stream controllers for message handling
  static StreamController<dynamic>? _overlayMessageStreamController;
  static StreamController<dynamic>? _mainAppMessageStreamController;


  /// Send a message FROM an overlay (the overlay isolate) TO the main app.
  /// Pass the same `engineId` you used to launch this overlay.
  static Future<bool> sendToMainApp(dynamic message, {required String engineId}) async {
    try {
      // Calls the platform method handler inside OverlayService for THIS overlay engine
      await _overlayChannel(engineId).invokeMethod('sendToMainApp', {'message': message});
      return true;
    } catch (e) {
      debugPrint('sendToMainApp error: $e');
      return false;
    }
  }


  /// Initialize message listeners
  static void initializeMessageChannels() {
    // Initialize stream controllers if not already done
    _overlayMessageStreamController ??= StreamController<dynamic>.broadcast();
    _mainAppMessageStreamController ??= StreamController<dynamic>.broadcast();

    // Listen for messages FROM overlays TO main app
    _mainAppMessengerChannel.setMessageHandler((message) async {
      print('Main app received message from overlay: $message');
      _mainAppMessageStreamController?.add(message);
      return true; // Send acknowledgment
    });

    // Listen for messages FROM main app TO overlays (if needed in main app)
    _messengerChannel.setMessageHandler((message) async {
      print('Main app messenger received: $message');
      _overlayMessageStreamController?.add(message);
      return true; // Send acknowledgment
    });
  }

  /// Stream to listen for messages FROM overlays TO main app
  static Stream<dynamic> get overlayMessagesStream {
    initializeMessageChannels();
    return _mainAppMessageStreamController!.stream;
  }

  /// Stream to listen for messages FROM main app TO overlays (useful for debugging)
  static Stream<dynamic> get mainAppMessagesStream {
    initializeMessageChannels();
    return _overlayMessageStreamController!.stream;
  }

  /// Send message FROM main app TO all overlays
  static Future<bool> sendMessageToOverlays(dynamic message) async {
    try {
      await _channel.invokeMethod('sendMessageToOverlays', {
        'message': message,
      });
      return true;
    } catch (e) {
      print('Error sending message to overlays: $e');
      return false;
    }
  }

  /// Send message FROM main app TO specific overlay
  static Future<bool> sendMessageToSpecificOverlay(dynamic message, String engineId) async {
    try {
      await _channel.invokeMethod('sendMessageToOverlays', {
        'message': message,
        'engineId': engineId,
      });
      return true;
    } catch (e) {
      print('Error sending message to specific overlay: $e');
      return false;
    }
  }



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
        'enableCloseOnDrag': true
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
        bool closeButtonOnDrag = false,

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
        'enableCloseOnDrag': closeButtonOnDrag
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

  static Future<bool?> moveOverlayAbsolute(OverlayPosition position, {String engineId = 'tray_engine'}) async {
    final bool? _res = await _channel.invokeMethod<bool?>(
      'moveOverlayAbsolute',
      {
        "engineId": engineId,
        ...position.toMap(),
      },
    );
    return _res;
  }

  static Future<Size> getScreenSize() async {
    final Map<Object?, Object?>? res =
    await _channel.invokeMethod('getScreenSize');
    final w = (res?['width'] as num?)?.toDouble() ?? 0;
    final h = (res?['height'] as num?)?.toDouble() ?? 0;
    return Size(w, h);
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
  static Future<bool> isActive({String? engineId}) async {
    // per-engine
    final ok = await _channel.invokeMethod<bool>('isOverlayActive', {
      'engineId': engineId,
    });
    return ok ?? false;
  }

  /// Dispose overlay stream
  static void disposeOverlayListener() {
    _controller.close();
  }

  /// Make the Overlay Can be removed by dragging bottom
  static Future<bool> setCloseOnDrag({
    required String engineId,
    required bool enabled,
  }) async {
    final ok = await _overlayChannel(engineId).invokeMethod<bool>('setCloseOnDrag', {
      'enabled': enabled,
    });
    return ok ?? false;
  }

}
