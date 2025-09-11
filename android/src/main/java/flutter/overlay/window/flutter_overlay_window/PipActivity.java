package flutter.overlay.window.flutter_overlay_window;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An Activity responsible for displaying a web-based video in Picture-in-Picture (PiP) mode.
 * It is heavily optimized for performance and smoothness
 */
public class PipActivity extends Activity {
    private WebView webView;
    private static final String TAG = "PipActivity";

    // Reusable JavaScript snippet to find and play the primary video element on a page.
    private final String JS_PLAY_VIDEO = "javascript:var video = document.querySelector('video'); if(video) { video.play(); }";

    /**
     * Extracts the YouTube video ID from a variety of URL formats (e.g., watch, youtu.be, shorts).
     * This is a key optimization, as it allows us to build a lightweight "embed" URL.
     * @param youtubeUrl The full YouTube URL from the share intent.
     * @return The 11-character video ID, or null if it's not a valid YouTube URL.
     */
    private static String getYoutubeVideoId(String youtubeUrl) {
        if (youtubeUrl == null || youtubeUrl.trim().length() <= 0) {
            return null;
        }
        // This regex pattern covers most common YouTube URL formats.
        String pattern = "(?<=watch\\?v=|/videos/|embed\\/|youtu.be\\/|\\/v\\/|\\/e\\/|watch\\?v%3D|watch\\?feature=player_embedded&v=|%2Fvideos%2F|embed%2Fvideos%2F|youtu.be%2F|\\/v%2F)[^#\\&\\?\\n]*";
        Pattern compiledPattern = Pattern.compile(pattern);
        Matcher matcher = compiledPattern.matcher(youtubeUrl);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        // --- AGGRESSIVE PERFORMANCE OPTIMIZATIONS ---
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // Crucial for autoplay to work.
        s.setDomStorageEnabled(true);

        // Reduce WebView overhead by disabling features not needed for video playback.
        s.setAllowFileAccess(false);
        s.setGeolocationEnabled(false);
        s.setSupportZoom(false);

        // KEY FIX FOR SMOOTHNESS: Forces the WebView onto a hardware-accelerated layer.
        // This allows the GPU to handle resizing smoothly without waiting for the WebView to repaint.
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // --- End of Performance Optimizations ---

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "Page finished loading: " + url);

                // Start video playback as soon as the page is ready.
                view.evaluateJavascript(JS_PLAY_VIDEO, null);

                // Enter PiP mode after a short delay to allow the video to initialize.
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        enterPictureInPictureModeWithAspectRatio();
                    } else {
                        // PiP not supported on older Android versions.
                        finish();
                    }
                }, 500);
            }
        });

        // --- URL CONVERSION LOGIC FOR PERFORMANCE ---
        String originalUrl = getIntent().getStringExtra("url");
        if (originalUrl == null || originalUrl.isEmpty()) {
            finish(); // No URL provided, so nothing to do.
            return;
        }

        String finalUrl;
        String videoId = getYoutubeVideoId(originalUrl);

        if (videoId != null) {
            // It's a YouTube URL, so build the high-performance embed URL.
            // The embed page is extremely lightweight compared to the full YouTube site.
            finalUrl = "https://www.youtube.com/embed/" + videoId + "?autoplay=1";
            Log.d(TAG, "Converted to YouTube embed URL: " + finalUrl);
        } else {
            // Not a YouTube URL, or the format is unrecognized. Load the original URL.
            finalUrl = originalUrl;
            Log.d(TAG, "Not a recognized YouTube URL. Loading original: " + finalUrl);
        }

        webView.loadUrl(finalUrl);
        // --- END OF URL CONVERSION LOGIC ---
    }

    // Helper method to enter PiP mode with a standard 16:9 aspect ratio.
    private void enterPictureInPictureModeWithAspectRatio() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9))
                    .build();
            try {
                enterPictureInPictureMode(params);
            } catch (IllegalStateException e) {
                Log.e(TAG, "Failed to enter PiP mode", e);
                finish();
            }
        }
    }

    /**
     * FIX FOR VIDEO PAUSING ON RESIZE:
     * This method is called by the system every time the PiP window is entered OR resized.
     * We use this hook to forcefully re-issue the play command to the video.
     */
    @Override
    public void onPictureInPictureModeChanged(boolean isInPipMode, @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPipMode, newConfig);
        if (isInPipMode) {
            // A resize event occurred. Ensure the video continues playing.
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (webView != null) {
                    webView.evaluateJavascript(JS_PLAY_VIDEO, null);
                }
            }, 100); // A small delay to let the resize event settle.
        } else {
            // The user has closed the PiP window, so we should close this activity.
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        // IMPORTANT: Clean up the WebView properly to avoid memory leaks.
        if (webView != null) {
            // Detach from the view hierarchy before destroying.
            ((ViewGroup) webView.getParent()).removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}