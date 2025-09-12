package flutter.overlay.window.flutter_overlay_window;

final public class OverlayConstants {

    static final String CACHED_TAG = "myCachedEngine";
    static final String CHANNEL_TAG = "x-slayer/overlay_channel";
    static final String OVERLAY_TAG = "x-slayer/overlay";
    
    // Original messenger tag for main app -> overlay communication
    static final String MESSENGER_TAG = "x-slayer/overlay_messenger";
    
    // NEW: Messenger tag for overlay -> main app communication
    static final String MAIN_APP_MESSENGER_TAG = "x-slayer/main_app_messenger";
    
    static final String CHANNEL_ID = "Overlay Channel";
    static final int NOTIFICATION_ID = 4579;
    static final int DEFAULT_XY = -6;
}
