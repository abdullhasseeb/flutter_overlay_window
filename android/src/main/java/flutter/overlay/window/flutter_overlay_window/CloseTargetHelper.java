package flutter.overlay.window.flutter_overlay_window;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;

public class CloseTargetHelper {
    private final WindowManager windowManager;
    private final Context context;
    private final Point screenSize;

    private View closeTargetView;
    private WindowManager.LayoutParams closeTargetParams;

    public CloseTargetHelper(Context context, WindowManager windowManager, Point screenSize) {
        this.context = context;
        this.windowManager = windowManager;
        this.screenSize = screenSize;
    }

    private int dpToPx(int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    public void showCloseTarget() {
        if (windowManager == null || closeTargetView != null) return;

        // Draw a circle with an X inside (simple)
        closeTargetView = new View(context) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c) {
                super.onDraw(c);
                int w = getWidth(), h = getHeight();
                int r = Math.min(w, h) / 2;
                // circle
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.argb(180, 200, 0, 0));
                c.drawCircle(w / 2f, h / 2f, r, p);
                // white X
                p.setColor(Color.WHITE);
                p.setStrokeWidth(dpToPx(3));
                float pad = dpToPx(18);
                c.drawLine(pad, pad, w - pad, h - pad, p);
                c.drawLine(w - pad, pad, pad, h - pad, p);
            }
        };

        int size = dpToPx(80);
        closeTargetParams = new WindowManager.LayoutParams(
                size, size,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
        );
        closeTargetParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        closeTargetParams.y = dpToPx(50);

        windowManager.addView(closeTargetView, closeTargetParams);
    }

    public void hideCloseTarget() {
        if (windowManager != null && closeTargetView != null) {
            windowManager.removeView(closeTargetView);
            closeTargetView = null;
            closeTargetParams = null;
        }
    }

    public void highlightCloseTarget() {
        if (closeTargetView != null) {
            closeTargetView.setScaleX(1.1f);
            closeTargetView.setScaleY(1.1f);
            closeTargetView.setAlpha(1.0f);
        }
    }

    public void resetCloseTarget() {
        if (closeTargetView != null) {
            closeTargetView.setScaleX(1.0f);
            closeTargetView.setScaleY(1.0f);
            closeTargetView.setAlpha(0.9f);
        }
    }

    public boolean isOverlapping(WindowManager.LayoutParams overlayParams) {
        if (overlayParams == null || closeTargetParams == null || closeTargetView == null) return false;

        // Overlay center (absolute)
        int overlayCenterX = overlayParams.x + overlayParams.width / 2;
        int overlayCenterY = overlayParams.y + overlayParams.height / 2;

        // Close target center: bottom-center of screen, offset by y margin
        int targetCenterX = screenSize.x / 2;
        int targetCenterY = screenSize.y - (closeTargetParams.height / 2 + dpToPx(50));

        int dx = overlayCenterX - targetCenterX;
        int dy = overlayCenterY - targetCenterY;
        int radius = closeTargetParams.width / 2;

        return (dx * dx + dy * dy) < (radius * radius);
    }
}