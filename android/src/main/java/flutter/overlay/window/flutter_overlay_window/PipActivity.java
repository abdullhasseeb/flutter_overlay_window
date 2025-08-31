package flutter.overlay.window.flutter_overlay_window;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.content.Intent;

import androidx.annotation.Nullable;

public class PipActivity extends Activity {
    private WebView webView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // allow autoplay in PiP
        // For Android System WebView Chromium:
        try {
            // "Allows HTML5 video to use Android system PiP"
            webView.getSettings().setSupportMultipleWindows(true);
        } catch (Throwable ignored) {}

        webView.setWebChromeClient(new WebChromeClient());

        String url = getIntent().getStringExtra("url");
        if (url == null) url = "https://m.youtube.com";

        webView.loadUrl(url);

        // Enter PiP immediately (16:9 looks good for YouTube)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9))
                    .build();
            enterPictureInPictureMode(params);
        } else {
            // older devices: just finish
            finish();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPipMode, android.content.res.Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPipMode, newConfig);
        if (!isInPipMode) {
            // If user closes PiP, close this activity.
            finish();
        }
    }
}