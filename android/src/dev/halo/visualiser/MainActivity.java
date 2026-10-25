package dev.halo.visualiser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.view.Gravity;
import android.widget.TextView;
import org.json.JSONObject;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import java.io.ByteArrayInputStream;

/** Landscape now-playing screen for the laptop's music, paired with the laptop like Halo. */
public final class MainActivity extends Activity {
    private static final String HOME = "http://127.0.0.1:8769/music/?app=android";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView web;
    private PhoneLink.Listener linkListener;
    private PhoneLink link;
    private TextView offline;
    private boolean resumed, failed;
    private final Runnable retry = () -> { if (resumed && failed) web.loadUrl(HOME); };

    private static boolean trusted(Uri uri) {
        return "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() == PhoneLink.PORT;
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void retryLater(long delay) {
        failed = true; offline.setVisibility(View.VISIBLE);
        handler.removeCallbacks(retry); handler.postDelayed(retry, delay);
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Draw into the curved (waterfall) edges and camera cut-out too.
        getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        web = new WebView(this); web.setBackgroundColor(Color.BLACK);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        offline = new TextView(this); offline.setBackgroundColor(Color.BLACK); offline.setTextColor(0x99FFFFFF);
        offline.setGravity(Gravity.CENTER); offline.setTextSize(14); offline.setVisibility(View.GONE);
        offline.setText("Waiting for your laptop\n\nSame Wi-Fi, laptop server running.\nFirst time: connect USB and run pair-phone.py --visualiser");
        root.addView(offline, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root); immersive();
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + " HaloVisualiser/1.0");
        web.addJavascriptInterface(new NativeBridge(), "HaloNative");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !trusted(request.getUrl());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (trusted(request.getUrl())) return null;
                String scheme = request.getUrl().getScheme();
                if ("data".equals(scheme) || "blob".equals(scheme) || "about".equals(scheme)) return null;
                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) retryLater(2000);
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 400) retryLater(2000);
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (!trusted(Uri.parse(url))) return;
                view.evaluateJavascript("Boolean(document.querySelector('#music'))", result -> {
                    if ("true".equals(result)) { failed = false; offline.setVisibility(View.GONE); handler.removeCallbacks(retry); }
                });
            }
        });
        try {
            linkListener = (online, transport) -> runOnUiThread(() -> {
                if (!online) { failed = true; return; }
                if (failed) web.loadUrl(HOME);
                else if (resumed) web.evaluateJavascript("window.dispatchEvent(new Event('halo-native-network'))", null);
            });
            link = PhoneLink.acquire(this, linkListener);
            CookieManager.getInstance().setCookie(HOME, "HaloLocal=" + link.localToken + "; Path=/; HttpOnly; SameSite=Strict");
        } catch (Exception ignored) {}
        web.loadUrl(HOME);
        pairingIntent(getIntent());
    }

    private void pairingIntent(Intent intent) {
        String encoded = intent.getStringExtra("halo_pair"); if (encoded == null || link == null) return;
        intent.removeExtra("halo_pair");
        try {
            final JSONObject config = new JSONObject(encoded);
            String pin = config.getString("fingerprint");
            if (!pin.matches("[a-f0-9]{64}")) return;
            new AlertDialog.Builder(this).setTitle("Pair Visualiser with your laptop?")
                .setMessage(config.getString("name") + "\n" + config.getString("host") + "\n\nLaptop fingerprint\n" + pin.substring(0, 16) + "…")
                .setNegativeButton("Cancel", (dialog, which) -> {})
                .setPositiveButton("Pair", (dialog, which) -> { try { link.pair(config); } catch (Exception ignored) {} }).show();
        } catch (Exception ignored) {}
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); pairingIntent(intent); }

    @Override public void onResume() {
        super.onResume(); resumed = true; immersive();
        if (web != null) { web.onResume(); web.evaluateJavascript("window.dispatchEvent(new Event('halo-native-resume'))", null); }
        if (failed) retryLater(400);
    }
    @Override public void onPause() {
        resumed = false; handler.removeCallbacks(retry);
        // Stops polling and the laptop's cava stream while the screen is away.
        if (web != null) { web.evaluateJavascript("window.dispatchEvent(new Event('halo-native-pause'))", null); web.onPause(); }
        super.onPause();
    }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) immersive(); }
    @Override public void onBackPressed() { moveTaskToBack(true); }
    @Override public void onDestroy() {
        if (linkListener != null) PhoneLink.release(linkListener);
        handler.removeCallbacksAndMessages(null);
        if (web != null) { web.removeJavascriptInterface("HaloNative"); web.destroy(); }
        super.onDestroy();
    }

    public final class NativeBridge {
        private long lastPulse;
        @JavascriptInterface public synchronized void haptic() {
            long now = SystemClock.uptimeMillis(); if (now - lastPulse < 80) return; lastPulse = now;
            if (Settings.System.getInt(getContentResolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return;
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                // A short crisp tick rather than Halo's 35 ms buzz.
                vibrator.vibrate(VibrationEffect.createOneShot(14, 180),
                    new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build());
            }
        }
    }
}
