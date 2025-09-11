package flutter.overlay.window.flutter_overlay_window;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationManagerCompat;

import java.util.Map;

import io.flutter.FlutterInjector;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.embedding.engine.FlutterEngineGroup;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BasicMessageChannel;
import io.flutter.plugin.common.JSONMessageCodec;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;

public class FlutterOverlayWindowPlugin implements
        FlutterPlugin, ActivityAware, BasicMessageChannel.MessageHandler, MethodCallHandler,
        PluginRegistry.ActivityResultListener {

    final int REQUEST_CODE_FOR_OVERLAY_PERMISSION = 1248;
    private MethodChannel channel;
    private Context context;
    private Activity mActivity;
    private BasicMessageChannel<Object> messenger;
    private Result pendingResult;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        this.context = flutterPluginBinding.getApplicationContext();
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), OverlayConstants.CHANNEL_TAG);
        channel.setMethodCallHandler(this);

        messenger = new BasicMessageChannel(flutterPluginBinding.getBinaryMessenger(), OverlayConstants.MESSENGER_TAG,
                JSONMessageCodec.INSTANCE);
        messenger.setMessageHandler(this);

        // This line is likely unnecessary and can be removed, but is harmless.
        WindowSetup.messenger = messenger;
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        pendingResult = result;
        if (call.method.equals("checkPermission")) {
            result.success(checkOverlayPermission());
        } else if (call.method.equals("requestPermission")) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                intent.setData(Uri.parse("package:" + mActivity.getPackageName()));
                mActivity.startActivityForResult(intent, REQUEST_CODE_FOR_OVERLAY_PERMISSION);
            } else {
                result.success(true);
            }
        } else if (call.method.equals("showOverlay")) {
            if (!checkOverlayPermission()) {
                result.error("PERMISSION", "overlay permission is not enabled", null);
                return;
            }
            Integer height = call.argument("height");
            Integer width = call.argument("width");
            String alignment = call.argument("alignment");
            String flag = call.argument("flag");
            String overlayTitle = call.argument("overlayTitle");
            String overlayContent = call.argument("overlayContent");
            String notificationVisibility = call.argument("notificationVisibility");
            boolean enableDrag = call.argument("enableDrag");
            // REMOVED: enableCloseOnDrag is no longer passed from showOverlay
            String positionGravity = call.argument("positionGravity");
            Map<String, Integer> startPosition = call.argument("startPosition");
            int startX = startPosition != null ? startPosition.getOrDefault("x", OverlayConstants.DEFAULT_XY) : OverlayConstants.DEFAULT_XY;
            int startY = startPosition != null ? startPosition.getOrDefault("y", OverlayConstants.DEFAULT_XY) : OverlayConstants.DEFAULT_XY;

            String entrypoint = call.argument("entrypoint");
            String engineId = call.argument("engineId");
            String initialRoute = call.argument("initialRoute");
            java.util.List<String> dartArgs = call.argument("dartArgs");

            final Intent intent = new Intent(context, OverlayService.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

            // Pass raw config to service (no longer including enableCloseOnDrag)
            intent.putExtra("widthDp", width != null ? width : -1);
            intent.putExtra("heightDp", height != null ? height : -1);
            intent.putExtra("alignment", alignment != null ? alignment : "center"); // service will convert to gravity
            intent.putExtra("flagStr", flag != null ? flag : "flagNotFocusable");
            intent.putExtra("enableDrag", enableDrag);
            // REMOVED: enableCloseOnDrag is not passed from showOverlay anymore
            intent.putExtra("positionGravity", positionGravity != null ? positionGravity : "none");
            intent.putExtra("overlayTitle", overlayTitle);
            intent.putExtra("overlayContent", overlayContent == null ? "" : overlayContent);
            intent.putExtra("notificationVisibility", notificationVisibility != null ? notificationVisibility : "visibilitySecret");

            // engine bootstrap
            intent.putExtra("startX", startX);
            intent.putExtra("startY", startY);
            intent.putExtra("entrypoint", entrypoint != null ? entrypoint : "overlayMain");
            intent.putExtra("engineId", engineId != null ? engineId : OverlayConstants.CACHED_TAG);
            if (initialRoute != null) intent.putExtra("initialRoute", initialRoute);
            if (dartArgs != null)
                intent.putStringArrayListExtra("dartArgs", new java.util.ArrayList<>(dartArgs));

            context.startService(intent);
            result.success(null);
        } else if (call.method.equals("isOverlayActive")) {
            String engineId = call.argument("engineId");
            if (engineId == null || engineId.isEmpty()) engineId = OverlayConstants.CACHED_TAG;
            result.success(OverlayService.hasOverlay(engineId));
            return;
        } else if (call.method.equals("moveOverlay")) {
            String engineId = call.argument("engineId");
            int x = call.argument("x");
            int y = call.argument("y");
            result.success(OverlayService.moveOverlay(engineId != null ? engineId : OverlayConstants.CACHED_TAG, x, y));
        } else if (call.method.equals("moveOverlayAbsolute")) {
            String engineId = call.argument("engineId");
            int x = call.argument("x");
            int y = call.argument("y");
            boolean ok = OverlayService.moveOverlayAbsolute(
                    engineId != null ? engineId : OverlayConstants.CACHED_TAG, x, y
            );
            result.success(ok);
            return;
        } else if (call.method.equals("getScreenSize")) {

            Map<String, Object> size = OverlayService.getScreenSize();
            result.success(size);

        } else if (call.method.equals("getOverlayPosition")) {
            String engineId = call.argument("engineId");
            result.success(OverlayService.getCurrentPosition(engineId != null ? engineId : OverlayConstants.CACHED_TAG));
        } else if (call.method.equals("closeOverlay")) {
            String engineId = call.argument("engineId"); // get engineId from Dart
            if (OverlayService.isRunning) {
                final Intent i = new Intent(context, OverlayService.class);
                i.putExtra("engineId", engineId != null ? engineId : OverlayConstants.CACHED_TAG);
                i.putExtra(OverlayService.INTENT_EXTRA_IS_CLOSE_WINDOW, true);
                context.startService(i); // tell service to close only this engineId
                result.success(true);
            } else {
                result.success(false);
            }
            return;
        } else if (call.method.equals("closeAllOverlays")) {
            if (OverlayService.isRunning) {
                final Intent i = new Intent(context, OverlayService.class);
                context.stopService(i); // kills the whole service, all overlays gone
                result.success(true);
            } else {
                result.success(false);
            }
            return;
        } else if (call.method.equals("showYouTubePip")) {
            String url = call.argument("url");
            Intent i = new Intent(context, PipActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            i.putExtra("url", url);
            context.startActivity(i);
            result.success(true);
        } else if (call.method.equals("resizeOverlay")) {
            String engineId = call.argument("engineId");
            int width = call.argument("width");
            int height = call.argument("height");
            boolean enableDrag = call.argument("enableDrag");
            // NEW: enableCloseOnDrag is now controlled from resizeOverlay
            Boolean enableCloseOnDragObj = call.argument("enableCloseOnDrag");
            boolean enableCloseOnDrag = enableCloseOnDragObj != null ? enableCloseOnDragObj : false;
            Integer duration = call.argument("duration");
            Boolean anchorLeft = call.argument("anchorLeft");
            Boolean anchorTop = call.argument("anchorTop");

            boolean ok = OverlayService.requestResize(
                    engineId != null ? engineId : OverlayConstants.CACHED_TAG,
                    width, height, enableDrag, enableCloseOnDrag, // Pass enableCloseOnDrag to resize
                    duration == null ? 0 : duration,
                    anchorLeft != null && anchorLeft,
                    anchorTop != null && anchorTop
            );
            result.success(ok);
            return;
        } else {
            result.notImplemented();
        }

    }

    @Override
    public void onMessage(@Nullable Object message, @NonNull BasicMessageChannel.Reply reply) {
        OverlayService.sendToAll(message);
        // Log the raw message for debugging
        Log.d("OverlayPlugin", "onMessage received from Dart: " + String.valueOf(message));
        reply.reply(true);  // send back an ack so Dart Future completes
    }


    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
        WindowSetup.messenger.setMessageHandler(null);
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        mActivity = binding.getActivity();
        binding.addActivityResultListener(this);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        this.mActivity = null;
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }

    @Override
    public void onDetachedFromActivity() {
        this.mActivity = null;
    }

    private boolean checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return true;
    }

    @Override
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_FOR_OVERLAY_PERMISSION) {
            pendingResult.success(checkOverlayPermission());
            return true;
        }
        return false;
    }
}