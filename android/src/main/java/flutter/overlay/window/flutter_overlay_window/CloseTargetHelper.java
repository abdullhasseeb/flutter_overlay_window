package flutter.overlay.window.flutter_overlay_window;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.RectF;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

public class CloseTargetHelper {
    private final WindowManager windowManager;
    private final Context context;
    private final Point screenSize;

    private View closeTargetView;
    private WindowManager.LayoutParams closeTargetParams;
    private ObjectAnimator pulseAnimator;
    private ValueAnimator attractAnimator; // Changed from ObjectAnimator to ValueAnimator
    private boolean isHighlighted = false;

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
            final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Path crossPath = new Path();

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                int width = getWidth();
                int height = getHeight();
                float centerX = width / 2f;
                float centerY = height / 2f;
                float radius = Math.min(width, height) / 2f - dpToPx(4);

                // Shadow
                shadowPaint.setStyle(Paint.Style.FILL);
                shadowPaint.setColor(Color.argb(50, 0, 0, 0));
                canvas.drawCircle(centerX + dpToPx(2), centerY + dpToPx(2), radius, shadowPaint);

                // Gradient-like effect with multiple circles
                for (int i = 0; i < 3; i++) {
                    circlePaint.setStyle(Paint.Style.FILL);
                    int alpha = isHighlighted ? 220 - (i * 30) : 180 - (i * 40);
                    circlePaint.setColor(Color.argb(alpha, 255, 59 + (i * 20), 48 + (i * 10)));
                    canvas.drawCircle(centerX, centerY, radius - (i * dpToPx(1)), circlePaint);
                }

                // Outer ring
                circlePaint.setStyle(Paint.Style.STROKE);
                circlePaint.setStrokeWidth(dpToPx(2));
                circlePaint.setColor(isHighlighted ? Color.argb(255, 255, 255, 255) : Color.argb(150, 255, 255, 255));
                canvas.drawCircle(centerX, centerY, radius - dpToPx(2), circlePaint);

                // Enhanced Cross (X)
                crossPaint.setStyle(Paint.Style.STROKE);
                crossPaint.setStrokeWidth(dpToPx(4));
                crossPaint.setStrokeCap(Paint.Cap.ROUND);
                crossPaint.setStrokeJoin(Paint.Join.ROUND);
                crossPaint.setColor(Color.WHITE);

                float crossSize = dpToPx(16);
                float crossLeft = centerX - crossSize;
                float crossTop = centerY - crossSize;
                float crossRight = centerX + crossSize;
                float crossBottom = centerY + crossSize;

                // Draw X with rounded ends
                canvas.drawLine(crossLeft, crossTop, crossRight, crossBottom, crossPaint);
                canvas.drawLine(crossRight, crossTop, crossLeft, crossBottom, crossPaint);

                // Add a subtle inner glow effect for the cross when highlighted
                if (isHighlighted) {
                    crossPaint.setStrokeWidth(dpToPx(6));
                    crossPaint.setColor(Color.argb(50, 255, 255, 255));
                    canvas.drawLine(crossLeft, crossTop, crossRight, crossBottom, crossPaint);
                    canvas.drawLine(crossRight, crossTop, crossLeft, crossBottom, crossPaint);
                }
            }
        };

        int size = dpToPx(80);
        closeTargetParams = new WindowManager.LayoutParams(
                size,
                size,
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

        // Initial entrance animation
        closeTargetView.setAlpha(0f);
        closeTargetView.setScaleX(0.3f);
        closeTargetView.setScaleY(0.3f);
        closeTargetView.animate()
                .alpha(0.9f)
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(300)
                .setInterpolator(new OvershootInterpolator())
                .start();

        startPulseAnimation();
    }

    public void hideCloseTarget() {
        if (closeTargetView != null) {
            // Exit animation
            closeTargetView.animate()
                    .alpha(0f)
                    .scaleX(0.3f)
                    .scaleY(0.3f)
                    .setDuration(200)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .withEndAction(() -> {
                        if (windowManager != null && closeTargetView != null) {
                            stopAllAnimations();
                            windowManager.removeView(closeTargetView);
                            closeTargetView = null;
                            closeTargetParams = null;
                        }
                    })
                    .start();
        }
    }

    public void highlightCloseTarget() {
        if (closeTargetView != null && !isHighlighted) {
            isHighlighted = true;
            stopAllAnimations();

            // Scale up and brighten
            closeTargetView.animate()
                    .scaleX(1.3f)
                    .scaleY(1.3f)
                    .alpha(1.0f)
                    .setDuration(200)
                    .setInterpolator(new OvershootInterpolator())
                    .start();

            // Magnetic attraction animation - move up slightly
            animateTargetPosition(-dpToPx(15), 150);

            // Invalidate to trigger redraw with new colors
            closeTargetView.invalidate();
        }
    }

    public void resetCloseTarget() {
        if (closeTargetView != null && isHighlighted) {
            isHighlighted = false;

            // Scale back down
            closeTargetView.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .alpha(0.9f)
                    .setDuration(300)
                    .setInterpolator(new OvershootInterpolator())
                    .start();

            // Return to original position
            animateTargetPosition(dpToPx(50), 300);

            // Restart pulse animation
            closeTargetView.postDelayed(this::startPulseAnimation, 300);

            // Invalidate to trigger redraw with original colors
            closeTargetView.invalidate();
        }
    }

    private void startPulseAnimation() {
        if (closeTargetView != null && !isHighlighted) {
            pulseAnimator = ObjectAnimator.ofFloat(closeTargetView, "alpha", 0.9f, 0.6f, 0.9f);
            pulseAnimator.setDuration(2000);
            pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
            pulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
            pulseAnimator.start();
        }
    }

    private void animateTargetPosition(int targetY, int duration) {
        if (closeTargetParams != null && windowManager != null && closeTargetView != null) {
            ValueAnimator animator = ValueAnimator.ofInt(closeTargetParams.y, targetY);
            animator.setDuration(duration);
            animator.setInterpolator(new OvershootInterpolator());
            animator.addUpdateListener(animation -> {
                if (closeTargetParams != null && windowManager != null && closeTargetView != null) {
                    closeTargetParams.y = (Integer) animation.getAnimatedValue();
                    try {
                        windowManager.updateViewLayout(closeTargetView, closeTargetParams);
                    } catch (Exception e) {
                        // Handle potential window manager exceptions
                    }
                }
            });
            animator.start();
            attractAnimator = animator; // Now correctly assigning ValueAnimator to ValueAnimator
        }
    }

    private void stopAllAnimations() {
        if (pulseAnimator != null && pulseAnimator.isRunning()) {
            pulseAnimator.cancel();
        }
        if (attractAnimator != null && attractAnimator.isRunning()) {
            attractAnimator.cancel();
        }
        if (closeTargetView != null) {
            closeTargetView.clearAnimation();
        }
    }

    public boolean isOverlapping(WindowManager.LayoutParams overlayParams) {
        if (overlayParams == null || closeTargetParams == null || closeTargetView == null)
            return false;

        // Overlay center (absolute)
        int overlayCenterX = overlayParams.x + overlayParams.width / 2;
        int overlayCenterY = overlayParams.y + overlayParams.height / 2;

        // Close target center: bottom-center of screen, offset by current y margin
        int targetCenterX = screenSize.x / 2;
        int targetCenterY = screenSize.y - (closeTargetParams.height / 2 + closeTargetParams.y);

        int dx = overlayCenterX - targetCenterX;
        int dy = overlayCenterY - targetCenterY;
        int radius = closeTargetParams.width / 2;

        // Increase detection radius when highlighted for better UX
        int detectionRadius = isHighlighted ? (int)(radius * 1.3f) : radius;

        return (dx * dx + dy * dy) < (detectionRadius * detectionRadius);
    }
}
