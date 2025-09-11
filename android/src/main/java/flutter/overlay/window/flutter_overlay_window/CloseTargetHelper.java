package flutter.overlay.window.flutter_overlay_window;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.BounceInterpolator;
import android.view.animation.OvershootInterpolator;

import java.util.ArrayList;
import java.util.List;

public class CloseTargetHelper {
    private final WindowManager windowManager;
    private final Context context;
    private final Point screenSize;
    private View closeTargetView;
    private WindowManager.LayoutParams closeTargetParams;
    private AnimatorSet pulseAnimator;
    private AnimatorSet bounceAnimator;
    private boolean isHighlighted = false;

    // Colors for the cross and background
    private static final int CIRCLE_COLOR = Color.argb(200, 66, 133, 244); // Google blue
    private static final int CROSS_COLOR = Color.WHITE;
    private static final int PULSE_COLOR = Color.argb(100, 66, 133, 244); // Lighter blue for pulse

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

        closeTargetView = new View(context) {
            final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            float pulseRadius = 0;
            float pulseAlpha = 0;

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);

                int width = getWidth();
                int height = getHeight();
                int centerX = width / 2;
                int centerY = height / 2;
                int radius = Math.min(width, height) / 2;

                // Draw pulse circle if active
                if (pulseAlpha > 0) {
                    pulsePaint.setColor(PULSE_COLOR);
                    pulsePaint.setAlpha((int) (pulseAlpha * 255));
                    canvas.drawCircle(centerX, centerY, pulseRadius, pulsePaint);
                }

                // Draw main circle
                circlePaint.setColor(CIRCLE_COLOR);
                canvas.drawCircle(centerX, centerY, radius, circlePaint);

                // Draw cross
                crossPaint.setColor(CROSS_COLOR);
                crossPaint.setStrokeWidth(dpToPx(3));
                crossPaint.setStrokeCap(Paint.Cap.ROUND);

                float padding = dpToPx(18);
                canvas.drawLine(padding, padding, width - padding, height - padding, crossPaint);
                canvas.drawLine(width - padding, padding, padding, height - padding, crossPaint);
            }

            public void setPulseRadius(float radius) {
                this.pulseRadius = radius;
                invalidate();
            }

            public void setPulseAlpha(float alpha) {
                this.pulseAlpha = alpha;
                invalidate();
            }
        };

        int size = dpToPx(80);
        closeTargetParams = new WindowManager.LayoutParams(
                size, size,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
        );

        closeTargetParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        closeTargetParams.y = dpToPx(50);

        windowManager.addView(closeTargetView, closeTargetParams);

        // Create animations
        createPulseAnimation();
        createBounceAnimation();
    }

    private void createPulseAnimation() {
        ObjectAnimator pulseSizeAnimator = ObjectAnimator.ofFloat(
                closeTargetView, "pulseRadius", 0, dpToPx(50)
        );
        pulseSizeAnimator.setDuration(800);

        ObjectAnimator pulseAlphaAnimator = ObjectAnimator.ofFloat(
                closeTargetView, "pulseAlpha", 0.8f, 0f
        );
        pulseAlphaAnimator.setDuration(800);

        pulseAnimator = new AnimatorSet();
        pulseAnimator.playTogether(pulseSizeAnimator, pulseAlphaAnimator);
        pulseAnimator.setInterpolator(new OvershootInterpolator());
    }

    private void createBounceAnimation() {
        List<Animator> animators = new ArrayList<>();

        // Scale X animation
        ObjectAnimator scaleXAnimator = ObjectAnimator.ofFloat(
                closeTargetView, View.SCALE_X, 1.0f, 1.2f, 0.9f, 1.1f, 1.0f
        );
        scaleXAnimator.setDuration(600);
        animators.add(scaleXAnimator);

        // Scale Y animation
        ObjectAnimator scaleYAnimator = ObjectAnimator.ofFloat(
                closeTargetView, View.SCALE_Y, 1.0f, 1.2f, 0.9f, 1.1f, 1.0f
        );
        scaleYAnimator.setDuration(600);
        animators.add(scaleYAnimator);

        bounceAnimator = new AnimatorSet();
        bounceAnimator.playTogether(animators);
        bounceAnimator.setInterpolator(new BounceInterpolator());
    }

    public void hideCloseTarget() {
        if (windowManager != null && closeTargetView != null) {
            if (pulseAnimator != null && pulseAnimator.isRunning()) {
                pulseAnimator.cancel();
            }
            if (bounceAnimator != null && bounceAnimator.isRunning()) {
                bounceAnimator.cancel();
            }
            windowManager.removeView(closeTargetView);
            closeTargetView = null;
            closeTargetParams = null;
            isHighlighted = false;
        }
    }

    public void highlightCloseTarget() {
        if (closeTargetView != null && !isHighlighted) {
            isHighlighted = true;

            // Start pulse animation
            if (pulseAnimator != null) {
                if (pulseAnimator.isRunning()) {
                    pulseAnimator.cancel();
                }
                pulseAnimator.start();
            }

            // Start bounce animation
            if (bounceAnimator != null) {
                if (bounceAnimator.isRunning()) {
                    bounceAnimator.cancel();
                }
                bounceAnimator.start();
            }
        }
    }

    public void resetCloseTarget() {
        if (closeTargetView != null && isHighlighted) {
            isHighlighted = false;

            // Reset to normal state
            closeTargetView.setScaleX(1.0f);
            closeTargetView.setScaleY(1.0f);
            closeTargetView.setAlpha(1.0f);

            // Stop animations
            if (pulseAnimator != null && pulseAnimator.isRunning()) {
                pulseAnimator.cancel();
            }
            if (bounceAnimator != null && bounceAnimator.isRunning()) {
                bounceAnimator.cancel();
            }
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