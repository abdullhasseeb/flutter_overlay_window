package flutter.overlay.window.flutter_overlay_window;
import android.animation.ValueAnimator;
import android.animation.AnimatorSet;
import android.view.animation.AccelerateDecelerateInterpolator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.app.PendingIntent;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

import io.flutter.embedding.android.FlutterView;
import io.flutter.FlutterInjector;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.FlutterEngineCache;
import io.flutter.embedding.engine.dart.DartExecutor;
import io.flutter.plugin.common.BasicMessageChannel;
import io.flutter.plugin.common.JSONMessageCodec;
import io.flutter.plugin.common.MethodChannel;

import io.flutter.embedding.engine.plugins.util.GeneratedPluginRegister;
import io.flutter.embedding.android.FlutterSurfaceView;

// Inside OverlayService.java (top-level, e.g. above the Service class or as a static inner class)
final class EngineConfig {
    int widthDp;
    int heightDp;
    int gravity;                  // WindowManager gravity
    int flag;                     // Window flag (focusable/not, etc.)
    boolean enableDrag;
    boolean enableCloseOnDrag;    // Close on drag functionality
    String positionGravity;       // "none" | "auto" | "left" | "right"
    int notificationVisibility;   // NotificationCompat visibility (or WindowSetup's int)
    String overlayTitle;
    String overlayContent;

    EngineConfig() {
        // Set default values
        this.enableCloseOnDrag = false; // Default to false
    }
}

public class OverlayService extends Service implements View.OnTouchListener {
    public static volatile boolean platformViewsReady = false; // ADD THIS

    // In OverlayService
    private final ConcurrentHashMap<String, EngineConfig> configs = new ConcurrentHashMap<>();
    private final int DEFAULT_NAV_BAR_HEIGHT_DP = 48;
    private final int DEFAULT_STATUS_BAR_HEIGHT_DP = 25;

    private Integer mStatusBarHeight = -1;
    private Integer mNavigationBarHeight = -1;
    private Resources mResources;

    // Engine config (coming from Intent extras)
    private String engineId = OverlayConstants.CACHED_TAG; // default cache key
    private String entrypoint = "overlayMain";            // default dart entrypoint
    private String initialRoute = null;                   // optional
    private ArrayList<String> dartArgs = null;            // optional

    public static final String INTENT_EXTRA_IS_CLOSE_WINDOW = "IsCloseWindow";

    private static OverlayService instance;
    public static boolean isRunning = false;
    // Per-engine state
    private final ConcurrentHashMap<String, FlutterEngine> engines = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FlutterView> views = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, MethodChannel> channels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BasicMessageChannel<Object>> messengers = new ConcurrentHashMap<>();
    private WindowManager windowManager = null; // shared system service
    private int clickableFlag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

    // Close target functionality
    private CloseTargetHelper closeTargetHelper;
    private boolean closeTargetShown = false;

    private Handler mAnimationHandler = new Handler();
    private float lastX, lastY;
    private int lastYPosition;
    private boolean dragging;
    private static final float MAXIMUM_OPACITY_ALLOWED_FOR_S_AND_HIGHER = 0.8f;
    private Point szWindow = new Point();
    private Timer mTrayAnimationTimer;
    private TrayAnimationTimerTask mTrayTimerTask;

    public static Map<String, Object> getScreenSize() {
        if (instance == null || instance.windowManager == null || instance.mResources == null) {
            return null;
        }
        Map<String, Object> size = new HashMap<>();
        try {
            Display display = instance.windowManager.getDefaultDisplay();
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);

            float density = instance.mResources.getDisplayMetrics().density;
            // return logical dp to match your moveOverlay/resize units
            size.put("width", metrics.widthPixels / density);
            size.put("height", metrics.heightPixels / density);
        } catch (Throwable t) {
            size.put("width", 0);
            size.put("height", 0);
        }
        return size;
    }

    private int mapFlagFromString(String flagStr) {
        // Mirror your existing WindowSetup.setFlag mapping
        if (flagStr == null) return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        // Minimal mapper (extend to your full set):
        switch (flagStr) {
            case "focusPointer":
                // Focusable so TextFields can request IME,
                // but NOT_TOUCH_MODAL so taps outside go to underlying apps.
                return WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            case "defaultFlag":
            case "flagNotFocusable":
            default:
                return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
    }

    public static void sendToAll(Object message) {
        if (instance == null) return;
        // Loop through all the registered messengers and send the message.
        for (Map.Entry<String, BasicMessageChannel<Object>> e : instance.messengers.entrySet()) {
            Log.d("OverlayService", "send to " + e.getKey() + ": " + message);
            e.getValue().send(message);
        }
    }

    private int mapGravityFromAlignment(String alignment) {
        // Map your OverlayAlignment.name to Android gravity
        // Example mapping—adjust as needed:
        if (alignment == null) return Gravity.CENTER;
        switch (alignment) {
            case "topLeft":
                return Gravity.TOP | Gravity.LEFT;
            case "topRight":
                return Gravity.TOP | Gravity.RIGHT;
            case "bottomLeft":
                return Gravity.BOTTOM | Gravity.LEFT;
            case "bottomRight":
                return Gravity.BOTTOM | Gravity.RIGHT;
            case "centerLeft":
                return Gravity.CENTER | Gravity.LEFT;
            case "centerRight":
                return Gravity.CENTER | Gravity.RIGHT;
            case "topCenter":
                return Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            case "bottomCenter":
                return Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            case "center":
            default:
                return Gravity.CENTER;
        }
    }

    private int mapNotificationVisibility(String vis) {
        if (vis == null) return NotificationCompat.VISIBILITY_SECRET;
        switch (vis) {
            case "visibilityPublic":
                return NotificationCompat.VISIBILITY_PUBLIC;
            case "visibilityPrivate":
                return NotificationCompat.VISIBILITY_PRIVATE;
            case "visibilitySecret":
            default:
                return NotificationCompat.VISIBILITY_SECRET;
        }
    }


    public static boolean hasOverlay(String engineId) {
        return instance != null && instance.views.containsKey(engineId);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onDestroy() {
        platformViewsReady = false;
        Log.d("OverLay", "Destroying the overlay window service");

        // Hide close target if shown
        if (closeTargetHelper != null) {
            closeTargetHelper.hideCloseTarget();
            closeTargetHelper = null;
        }
        closeTargetShown = false;

        try {
            for (Map.Entry<String, FlutterEngine> e : engines.entrySet()) {
                try {
                    e.getValue().getPlatformViewsController().detach();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        if (windowManager != null) {
            for (FlutterView v : views.values()) {
                try {
                    windowManager.removeView(v);
                } catch (Throwable ignored) {
                }
                try {
                    v.detachFromFlutterEngine();
                } catch (Throwable ignored) {
                }
            }
            windowManager = null;
        }
        engines.clear();
        views.clear();
        channels.clear();
        messengers.clear();
        isRunning = false;
        NotificationManager notificationManager = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(OverlayConstants.NOTIFICATION_ID);
        instance = null;
    }

    public static boolean requestResize(String engineId,
                                        int width,
                                        int height,
                                        boolean enableDrag,
                                        boolean enableCloseOnDrag, // Parameter for close functionality
                                        int durationMs,
                                        boolean anchorLeft,
                                        boolean anchorTop) {

        if (instance == null) return false;
        FlutterView v = instance.views.get(engineId);
        if (v == null) return false;
        // delegate to the real method; pass null for MethodChannel.Result
        instance.resizeOverlayFor(engineId, v, width, height,
                enableDrag, enableCloseOnDrag,
                durationMs, anchorLeft, anchorTop, null);

        return true;
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mResources = getApplicationContext().getResources();

        // Read optional engine configuration from the intent
        String extraEngineId = intent.getStringExtra("engineId");
        String extraEntrypoint = intent.getStringExtra("entrypoint");
        String extraInitialRoute = intent.getStringExtra("initialRoute");
        ArrayList<String> extraDartArgs = intent.getStringArrayListExtra("dartArgs");
        if (extraEngineId != null && !extraEngineId.isEmpty()) engineId = extraEngineId;
        if (extraEntrypoint != null && !extraEntrypoint.isEmpty()) entrypoint = extraEntrypoint;
        if (extraInitialRoute != null && !extraInitialRoute.isEmpty())
            initialRoute = extraInitialRoute;
        if (extraDartArgs != null && !extraDartArgs.isEmpty()) dartArgs = extraDartArgs;

        int startX = intent.getIntExtra("startX", OverlayConstants.DEFAULT_XY);
        int startY = intent.getIntExtra("startY", OverlayConstants.DEFAULT_XY);
        boolean isCloseWindow = intent.getBooleanExtra(INTENT_EXTRA_IS_CLOSE_WINDOW, false);
        if (isCloseWindow) {
            if (windowManager != null && views.containsKey(engineId)) {
                FlutterView v = views.get(engineId);
                try {
                    windowManager.removeView(v);
                } catch (Throwable ignored) {
                }
                try {
                    v.detachFromFlutterEngine();
                } catch (Throwable ignored) {
                }
                views.remove(engineId);
                // Remove engine/channel/messenger as well if desired
                engines.remove(engineId);
                channels.remove(engineId);
                messengers.remove(engineId);
                configs.remove(engineId); // ← remove config too
            }
            // If no views left, consider stopping foreground
            isRunning = !views.isEmpty();
            return START_STICKY;
        }

        // ----- Build or update per-engine config from Intent -----
        EngineConfig cfg = configs.get(engineId);
        if (cfg == null) {
            cfg = new EngineConfig();
            configs.put(engineId, cfg);
        }
        int widthDp = intent.getIntExtra("widthDp", -1);
        int heightDp = intent.getIntExtra("heightDp", -1);
        String alignment = intent.getStringExtra("alignment");
        String flagStr = intent.getStringExtra("flagStr");
        boolean enableDrag = intent.getBooleanExtra("enableDrag", false);
        String positionGravity = intent.getStringExtra("positionGravity");
        String overlayTitle = intent.getStringExtra("overlayTitle");
        String overlayContent = intent.getStringExtra("overlayContent");
        String notificationVisibility = intent.getStringExtra("notificationVisibility");

        cfg.widthDp = widthDp;
        cfg.heightDp = heightDp;
        cfg.gravity = mapGravityFromAlignment(alignment);
        cfg.flag = mapFlagFromString(flagStr);
        cfg.enableDrag = enableDrag;
        // Note: enableCloseOnDrag is NOT set from showOverlay intent anymore
        // It will only be set when resizeOverlay is called
        cfg.positionGravity = (positionGravity != null) ? positionGravity : "none";
        cfg.overlayTitle = overlayTitle;
        cfg.overlayContent = overlayContent != null ? overlayContent : "";
        cfg.notificationVisibility = mapNotificationVisibility(notificationVisibility);

        isRunning = true;
        Log.d("onStartCommand", "Service started");

        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                windowManager.getDefaultDisplay().getSize(szWindow);
            } else {
                DisplayMetrics displaymetrics = new DisplayMetrics();
                windowManager.getDefaultDisplay().getMetrics(displaymetrics);
                szWindow.set(displaymetrics.widthPixels, displaymetrics.heightPixels);
            }

            // Initialize close target helper
            closeTargetHelper = new CloseTargetHelper(getApplicationContext(), windowManager, szWindow);
        }

        // Acquire or create the FlutterEngine using provided engineId/entrypoint/route
        FlutterEngine engine = engines.get(engineId);
        if (engine == null) {
            engine = FlutterEngineCache.getInstance().get(engineId);
        }
        if (engine == null) {
            engine = new FlutterEngine(getApplicationContext());
            engine.getPlatformViewsController().attach(
                    getApplicationContext(),
                    engine.getRenderer(),
                    engine.getDartExecutor()
            );
            platformViewsReady = true;
            GeneratedPluginRegister.registerGeneratedPlugins(engine);
            if (initialRoute != null) {
                engine.getNavigationChannel().setInitialRoute(initialRoute);
            }
            DartExecutor.DartEntrypoint dEntry = new DartExecutor.DartEntrypoint(
                    FlutterInjector.instance().flutterLoader().findAppBundlePath(),
                    entrypoint
            );
            if (dartArgs != null && !dartArgs.isEmpty()) {
                engine.getDartExecutor().executeDartEntrypoint(dEntry, dartArgs);
            } else {
                engine.getDartExecutor().executeDartEntrypoint(dEntry);
            }
            FlutterEngineCache.getInstance().put(engineId, engine);
            engines.put(engineId, engine);
        } else {
            engines.put(engineId, engine);
        }
        engine.getLifecycleChannel().appIsResumed();

        if (views.containsKey(engineId)) {
            // Already showing this engineId; just return START_STICKY (no duplicate view)
            return START_STICKY;
        }
        FlutterSurfaceView surface = new FlutterSurfaceView(getApplicationContext(), true);
        surface.setZOrderOnTop(false);
        surface.setZOrderMediaOverlay(true);
        FlutterView flutterView = new FlutterView(getApplicationContext(), surface);
        flutterView.attachToFlutterEngine(engine);
        flutterView.setFitsSystemWindows(true);
        flutterView.setFocusable(true);
        flutterView.setFocusableInTouchMode(true);
        flutterView.setBackgroundColor(Color.TRANSPARENT);
        flutterView.setOnTouchListener(this);
        views.put(engineId, flutterView);

        MethodChannel flutterChannel = new MethodChannel(engine.getDartExecutor(), OverlayConstants.OVERLAY_TAG + "/" + engineId);
        BasicMessageChannel<Object> overlayMessageChannel = new BasicMessageChannel<>(engine.getDartExecutor(), OverlayConstants.MESSENGER_TAG + "/" + engineId, JSONMessageCodec.INSTANCE);
        channels.put(engineId, flutterChannel);
        messengers.put(engineId, overlayMessageChannel);

        flutterChannel.setMethodCallHandler((call, result) -> {
            String method = call.method;

            if ("updateFlag".equals(method)) {
                Object raw = call.argument("flag");
                final String flag = (raw == null) ? "flagNotFocusable" : raw.toString();
                updateOverlayFlagFor(engineId, flutterView, result, flag);

            } else if ("updateOverlayPosition".equals(method)) {
                int x = call.<Integer>argument("x");
                int y = call.<Integer>argument("y");
                moveOverlayFor(engineId, flutterView, x, y, result);

            } else if ("resizeOverlay".equals(method)) {
                int width = call.argument("width");
                int height = call.argument("height");
                final boolean newEnableDrag = call.argument("enableDrag");
                Boolean enableCloseOnDragObj = call.argument("enableCloseOnDrag"); // Get from resizeOverlay
                final boolean newEnableCloseOnDrag = enableCloseOnDragObj != null ? enableCloseOnDragObj : false;
                Integer duration = call.argument("duration");
                Boolean anchorLeft = call.argument("anchorLeft");
                Boolean anchorTop = call.argument("anchorTop");
                resizeOverlayFor(engineId, flutterView, width, height,
                        newEnableDrag, newEnableCloseOnDrag,
                        duration == null ? 500 : duration,
                        anchorLeft != null && anchorLeft,
                        anchorTop != null && anchorTop,
                        result);

            } else if ("isPlatformViewsReady".equals(method)) {
                result.success(platformViewsReady);

            } else {
                result.notImplemented();
            }
        });

        overlayMessageChannel.setMessageHandler((message, reply) -> {
            WindowSetup.messenger.send(message); // fan-out if you need
        });

        int dx = startX == OverlayConstants.DEFAULT_XY ? 0 : startX;
        int dy = startY == OverlayConstants.DEFAULT_XY ? -statusBarHeightPx() : startY;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                (cfg.widthDp == -1999 || cfg.widthDp == -1) ? WindowManager.LayoutParams.MATCH_PARENT : dpToPx(cfg.widthDp),
                (cfg.heightDp == -1999 || cfg.heightDp == -1) ? WindowManager.LayoutParams.MATCH_PARENT : dpToPx(cfg.heightDp),
                0,
                -statusBarHeightPx(),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                cfg.flag
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = cfg.gravity;
        windowManager.addView(flutterView, params);
        moveOverlayFor(engineId, flutterView, dx, dy, null);
        return START_STICKY;
    }


    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    private int screenHeight() {
        Display display = windowManager.getDefaultDisplay();
        DisplayMetrics dm = new DisplayMetrics();
        display.getRealMetrics(dm);
        return inPortrait() ?
                dm.heightPixels + statusBarHeightPx() + navigationBarHeightPx()
                :
                dm.heightPixels + statusBarHeightPx();
    }

    private int statusBarHeightPx() {
        if (mStatusBarHeight == -1) {
            int statusBarHeightId = mResources.getIdentifier("status_bar_height", "dimen", "android");

            if (statusBarHeightId > 0) {
                mStatusBarHeight = mResources.getDimensionPixelSize(statusBarHeightId);
            } else {
                mStatusBarHeight = dpToPx(DEFAULT_STATUS_BAR_HEIGHT_DP);
            }
        }

        return mStatusBarHeight;
    }

    int navigationBarHeightPx() {
        if (mNavigationBarHeight == -1) {
            int navBarHeightId = mResources.getIdentifier("navigation_bar_height", "dimen", "android");

            if (navBarHeightId > 0) {
                mNavigationBarHeight = mResources.getDimensionPixelSize(navBarHeightId);
            } else {
                mNavigationBarHeight = dpToPx(DEFAULT_NAV_BAR_HEIGHT_DP);
            }
        }

        return mNavigationBarHeight;
    }


    private void updateOverlayFlagFor(String engineId, FlutterView view, MethodChannel.Result result, String flag) {
        EngineConfig cfg = configs.get(engineId);
        if (windowManager != null && view != null && cfg != null) {
            cfg.flag = mapFlagFromString(flag);
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) view.getLayoutParams();
            params.flags = cfg.flag
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            windowManager.updateViewLayout(view, params);
            result.success(true);
        } else {
            result.success(false);
        }
    }

    /// Keep the window inside screen, not outside
    private void clampToScreen(WindowManager.LayoutParams p) {
        // screen size you already cache
        final int maxX = Math.max(0, szWindow.x - p.width);
        final int maxY = Math.max(0, screenHeight() - p.height);
        if (p.x < 0) p.x = 0;
        else if (p.x > maxX) p.x = maxX;
        if (p.y < 0) p.y = 0;
        else if (p.y > maxY) p.y = maxY;
    }

    // This method now updates the enableCloseOnDrag setting when resizing
    private void resizeOverlayFor(
            String engineId,
            FlutterView view,
            int width,
            int height,
            boolean enableDrag,
            boolean enableCloseOnDrag, // This now controls close functionality
            int durationMs,
            boolean anchorLeft,
            boolean anchorTop,
            MethodChannel.Result result
    ) {
        EngineConfig cfg = configs.get(engineId);
        if (windowManager == null || view == null || cfg == null) {
            if (result != null) result.success(false);
            return;
        }

        cfg.enableDrag = enableDrag;
        cfg.enableCloseOnDrag = enableCloseOnDrag; // Update the config with the new setting

        final WindowManager.LayoutParams params = (WindowManager.LayoutParams) view.getLayoutParams();

        // Resolve targets (dp -> px)
        final int targetW = (width == -1999 || width == -1) ? WindowManager.LayoutParams.MATCH_PARENT : dpToPx(width);
        final int targetH = (height == -1999 || height == -1) ? WindowManager.LayoutParams.MATCH_PARENT : dpToPx(height);

        // Capture starting geometry
        final int startW = params.width;
        final int startH = params.height;

        // Normalize to absolute TOP|LEFT like drag()
        int startX = params.x;
        int startY = params.y;
        final int screenW = szWindow.x;
        final int screenH = szWindow.y;
        final int horiz = params.gravity & Gravity.HORIZONTAL_GRAVITY_MASK;
        final int vert = params.gravity & Gravity.VERTICAL_GRAVITY_MASK;

        if (horiz == Gravity.RIGHT) {
            startX = screenW - startW - startX;
        } else if (horiz == Gravity.CENTER_HORIZONTAL) {
            startX = (screenW - startW) / 2 + startX;
        }
        if (vert == Gravity.BOTTOM) {
            startY = screenH - startH - startY;
        } else if (vert == Gravity.CENTER_VERTICAL) {
            startY = (screenH - startH) / 2 + startY;
        }

        params.gravity = Gravity.TOP | Gravity.LEFT;
        try {
            params.preferredRefreshRate = 90f;
            windowManager.updateViewLayout(view, params);
        } catch (Throwable ignored) {
        }

        final int baseX = startX;
        final int baseY = startY;
        final int baseRight = baseX + startW;   // ← absolute right edge at the start
        final int baseBottom = baseY + startH;  // ← absolute bottom edge at the start

        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(Math.max(0, durationMs));
        animator.addUpdateListener(a -> {
            if (windowManager == null || view == null) return;
            final float t = (float) a.getAnimatedValue();

            // Interpolate size (respect MATCH_PARENT)
            final int w = (startW == WindowManager.LayoutParams.MATCH_PARENT || targetW == WindowManager.LayoutParams.MATCH_PARENT)
                    ? targetW
                    : Math.round(startW + (targetW - startW) * t);
            final int h = (startH == WindowManager.LayoutParams.MATCH_PARENT || targetH == WindowManager.LayoutParams.MATCH_PARENT)
                    ? targetH
                    : Math.round(startH + (targetH - startH) * t);

            params.width = w;
            params.height = h;

            // Horizontal anchor:
            // - Right grip (anchorLeft == false): keep LEFT edge fixed → x = baseX
            // - Left grip  (anchorLeft == true) : keep RIGHT edge fixed → x = baseRight - w
            if (startW != WindowManager.LayoutParams.MATCH_PARENT && targetW != WindowManager.LayoutParams.MATCH_PARENT) {
                params.x = anchorLeft ? (baseRight - w) : baseX;
            } else {
                params.x = baseX;
            }

            // Vertical anchor (kept identical to your logic; anchorTop pins bottom edge)
            if (startH != WindowManager.LayoutParams.MATCH_PARENT && targetH != WindowManager.LayoutParams.MATCH_PARENT) {
                params.y = anchorTop ? (baseBottom - h) : baseY;
            } else {
                params.y = baseY;
            }

            clampToScreen(params);         // you already have this
            try {
                windowManager.updateViewLayout(view, params);
            } catch (Throwable ignored) {
            }
        });

        view.post(animator::start);
        if (result != null) result.success(true);
    }

    private void moveOverlayFor(String engineId, FlutterView view, int x, int y, MethodChannel.Result result) {
        if (windowManager != null && view != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) view.getLayoutParams();
            params.x = (x == -1999 || x == -1) ? -1 : dpToPx(x);
            params.y = dpToPx(y);
            windowManager.updateViewLayout(view, params);
            if (result != null) result.success(true);
        } else {
            if (result != null) result.success(false);
        }
    }

    // Add a helper in OverlayService.java
    private void cancelSnapTimerIfAny() {
        try {
            if (mTrayTimerTask != null) mTrayTimerTask.cancel();
            if (mTrayAnimationTimer != null) mTrayAnimationTimer.cancel();
        } catch (Throwable ignored) {
        }
    }

    public static boolean moveOverlayAbsolute(String engineId, int x, int y) {
        if (instance != null && instance.views.containsKey(engineId)) {
            FlutterView v = instance.views.get(engineId);
            if (instance.windowManager != null) {
                // stop any snap animation
                instance.cancelSnapTimerIfAny();
                EngineConfig cfg = instance.configs.get(engineId);
                if (cfg != null) cfg.positionGravity = "none";

                WindowManager.LayoutParams params = (WindowManager.LayoutParams) v.getLayoutParams();
                // normalize to absolute top-left
                params.gravity = Gravity.TOP | Gravity.LEFT;
                params.x = (x == -1999 || x == -1) ? -1 : instance.dpToPx(x);
                params.y = instance.dpToPx(y);
                instance.clampToScreen(params);
                try {
                    instance.windowManager.updateViewLayout(v, params);
                } catch (Throwable ignored) {
                }
                return true;
            }
        }
        return false;
    }

    public static Map<String, Double> getCurrentPosition(String engineId) {
        if (instance != null && instance.views.containsKey(engineId)) {
            FlutterView v = instance.views.get(engineId);
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) v.getLayoutParams();
            Map<String, Double> position = new HashMap<>();
            position.put("x", instance.pxToDp(params.x));
            position.put("y", instance.pxToDp(params.y));
            return position;
        }
        return null;
    }

    public static boolean moveOverlay(String engineId, int x, int y) {
        if (instance != null && instance.views.containsKey(engineId)) {
            FlutterView v = instance.views.get(engineId);
            if (instance.windowManager != null) {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) v.getLayoutParams();
                params.x = (x == -1999 || x == -1) ? -1 : instance.dpToPx(x);
                params.y = instance.dpToPx(y);
                instance.windowManager.updateViewLayout(v, params);
                return true;
            }
        }
        return false;
    }

    // Helper method to close specific overlay
    private void closeOverlayByEngineId(String engineId) {
        if (windowManager != null && views.containsKey(engineId)) {
            FlutterView v = views.get(engineId);
            try {
                windowManager.removeView(v);
            } catch (Throwable ignored) {
            }
            try {
                v.detachFromFlutterEngine();
            } catch (Throwable ignored) {
            }

            // Clean up all associated resources
            views.remove(engineId);
            engines.remove(engineId);
            channels.remove(engineId);
            messengers.remove(engineId);
            configs.remove(engineId);

            // Hide close target
            if (closeTargetHelper != null) {
                closeTargetHelper.hideCloseTarget();
                closeTargetShown = false;
            }

            Log.d("OverlayService", "Overlay closed: " + engineId);
        }

        // If no overlays left, stop the service
        if (views.isEmpty()) {
            isRunning = false;
            stopSelf();
        }
    }


    @Override
    public void onCreate() {
        // Ensure Flutter loader
        FlutterInjector injector = FlutterInjector.instance();
        if (!injector.flutterLoader().initialized()) {
            injector.flutterLoader().startInitialization(getApplicationContext());
            injector.flutterLoader().ensureInitializationComplete(getApplicationContext(), null);
        }

        createNotificationChannel();
        Intent notificationIntent = new Intent(this, FlutterOverlayWindowPlugin.class);
        int pendingFlags;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            pendingFlags = PendingIntent.FLAG_IMMUTABLE;
        } else {
            pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, pendingFlags);
        final int notifyIcon = getDrawableResourceId("mipmap", "launcher");
        Notification notification = new NotificationCompat.Builder(this, OverlayConstants.CHANNEL_ID)
                .setContentTitle(WindowSetup.overlayTitle)
                .setContentText(WindowSetup.overlayContent)
                .setSmallIcon(notifyIcon == 0 ? R.drawable.notification_icon : notifyIcon)
                .setContentIntent(pendingIntent)
                .setVisibility(WindowSetup.notificationVisibility)
                .build();
        startForeground(OverlayConstants.NOTIFICATION_ID, notification);
        instance = this;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    OverlayConstants.CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            assert manager != null;
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private int getDrawableResourceId(String resType, String name) {
        return getApplicationContext().getResources().getIdentifier(String.format("ic_%s", name), resType, getApplicationContext().getPackageName());
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                Float.parseFloat(dp + ""), mResources.getDisplayMetrics());
    }

    private double pxToDp(int px) {
        return (double) px / mResources.getDisplayMetrics().density;
    }

    private boolean inPortrait() {
        return mResources.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        FlutterView touched = null;
        for (FlutterView v : views.values()) {
            if (v == view) {
                touched = v;
                break;
            }
        }
        if (touched == null) return false;

        // Identify engineId for this view
        String currentId = null;
        for (Map.Entry<String, FlutterView> e : views.entrySet()) {
            if (e.getValue() == touched) {
                currentId = e.getKey();
                break;
            }
        }
        if (currentId == null) return false;

        EngineConfig cfg = configs.get(currentId);
        if (windowManager != null && cfg != null && cfg.enableDrag) {
            final WindowManager.LayoutParams p =
                    (WindowManager.LayoutParams) touched.getLayoutParams();

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN: {
                    final int screenW = szWindow.x;
                    final int screenH = szWindow.y;
                    final int vw = touched.getWidth();
                    final int vh = touched.getHeight();

                    final int horiz = p.gravity & Gravity.HORIZONTAL_GRAVITY_MASK;
                    final int vert = p.gravity & Gravity.VERTICAL_GRAVITY_MASK;

                    switch (horiz) {
                        case Gravity.RIGHT:
                            // distance from right -> absolute left
                            p.x = screenW - vw - p.x;
                            break;
                        case Gravity.CENTER_HORIZONTAL:
                            // offset from center -> absolute left
                            p.x = (screenW - vw) / 2 + p.x;
                            break;
                        // LEFT: p.x is already absolute
                    }

                    switch (vert) {
                        case Gravity.BOTTOM:
                            // distance from bottom -> absolute top
                            p.y = screenH - vh - p.y;
                            break;
                        case Gravity.CENTER_VERTICAL:
                            // offset from center -> absolute top
                            p.y = (screenH - vh) / 2 + p.y;
                            break;
                        // TOP: p.y is already absolute
                    }

                    p.gravity = Gravity.TOP | Gravity.LEFT;
                    windowManager.updateViewLayout(touched, p);

                    lastX = event.getRawX();
                    lastY = event.getRawY();
                    dragging = false;

                    // Show close target when starting to drag (only if enabled for this overlay)
                    if (cfg.enableCloseOnDrag && closeTargetHelper != null && !closeTargetShown) {
                        closeTargetHelper.showCloseTarget();
                        closeTargetShown = true;
                    }
                    break;
                }

                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - lastX;
                    float dy = event.getRawY() - lastY;

                    if (!dragging && dx * dx + dy * dy < 25) return false;
                    dragging = true;

                    lastX = event.getRawX();
                    lastY = event.getRawY();

                    p.x += Math.round(dx);
                    p.y += Math.round(dy);
                    windowManager.updateViewLayout(touched, p);

                    // Check if overlay is over close target (only if enabled)
                    if (cfg.enableCloseOnDrag && closeTargetHelper != null && closeTargetShown) {
                        if (closeTargetHelper.isOverlapping(p)) {
                            closeTargetHelper.highlightCloseTarget();
                        } else {
                            closeTargetHelper.resetCloseTarget();
                        }
                    }
                    break;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    // Check if overlay should be closed (only if enabled for this overlay)
                    if (cfg.enableCloseOnDrag && closeTargetHelper != null && closeTargetShown && closeTargetHelper.isOverlapping(p)) {
                        // Close this overlay
                        closeOverlayByEngineId(currentId);
                        return true;
                    }

                    // Hide close target after drag ends (only if it was shown)
                    if (cfg.enableCloseOnDrag && closeTargetHelper != null && closeTargetShown) {
                        closeTargetHelper.hideCloseTarget();
                        closeTargetShown = false;
                    }

                    lastYPosition = p.y;
                    if (!"none".equals(cfg.positionGravity)) {
                        windowManager.updateViewLayout(touched, p);
                        mTrayTimerTask = new TrayAnimationTimerTask(touched, cfg);
                        mTrayAnimationTimer = new Timer();
                        mTrayAnimationTimer.schedule(mTrayTimerTask, 0, 25);
                    }
                    return false;
                }
            }
            return false;
        }
        return false;
    }

    private class TrayAnimationTimerTask extends TimerTask {
        int mDestX;
        int mDestY;
        WindowManager.LayoutParams params;
        FlutterView trayView;
        EngineConfig cfg;

        public TrayAnimationTimerTask(FlutterView trayView, EngineConfig cfg) {
            super();
            this.trayView = trayView;
            this.cfg = cfg;
            this.params = (WindowManager.LayoutParams) trayView.getLayoutParams();
            mDestY = lastYPosition;
            switch (cfg.positionGravity) {
                case "auto":
                    mDestX = (params.x + (trayView.getWidth() / 2)) <= szWindow.x / 2 ? 0 : szWindow.x - trayView.getWidth();
                    return;
                case "left":
                    mDestX = 0;
                    return;
                case "right":
                    mDestX = szWindow.x - trayView.getWidth();
                    return;
                default:
                    mDestX = params.x;
                    mDestY = params.y;
                    break;
            }
        }

        @Override
        public void run() {
            mAnimationHandler.post(() -> {
                params.x = (2 * (params.x - mDestX)) / 3 + mDestX;
                params.y = (2 * (params.y - mDestY)) / 3 + mDestY;
                if (windowManager != null) {
                    windowManager.updateViewLayout(trayView, params);
                }
                if (Math.abs(params.x - mDestX) < 2 && Math.abs(params.y - mDestY) < 2) {
                    TrayAnimationTimerTask.this.cancel();
                    mTrayAnimationTimer.cancel();
                }
            });
        }
    }
}