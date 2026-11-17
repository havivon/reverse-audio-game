package com.achoranit.game;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Hosts the web game in a WebView. Assets are served from a fake https origin so the page
 * is a secure context (required for getUserMedia) and gets its own localStorage.
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/index.html";
    private static final int REQ_MIC = 7;

    private WebView web;
    private NativeAudio nativeAudio;
    private PermissionRequest pendingRequest;
    private boolean pendingNativeMic;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0D0B14);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        nativeAudio = new NativeAudio(this);
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.addJavascriptInterface(nativeAudio, "NativeAudio");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!HOST.equals(uri.getHost())) return null;
                String path = uri.getPath();
                if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
                try {
                    InputStream in = getAssets().open(path.substring(1));
                    return new WebResourceResponse(mimeFor(path), "UTF-8", in);
                } catch (Exception e) {
                    return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, null);
                }
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                if (HOST.equals(uri.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            request.grant(request.getResources());
                        } else {
                            if (pendingRequest != null) pendingRequest.deny();
                            pendingRequest = request;
                            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                        }
                    }
                });
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
    }

    /** Asks for the microphone on behalf of NativeAudio; the answer goes back as a 'permission' event. */
    void requestMicForNative() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            emitAudioEvent("permission", "true");
            return;
        }
        pendingNativeMic = true;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
    }

    /** Calls window.__nativeAudioEvent(type, value) on the UI thread. value must be a JS literal. */
    void emitAudioEvent(final String type, final String value) {
        if (web == null) return;
        web.post(new Runnable() {
            @Override
            public void run() {
                if (web != null) {
                    web.evaluateJavascript("window.__nativeAudioEvent&&window.__nativeAudioEvent('"
                            + type + "'," + value + ")", null);
                }
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_MIC) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (pendingRequest != null) {
            if (granted) pendingRequest.grant(pendingRequest.getResources());
            else pendingRequest.deny();
            pendingRequest = null;
        }
        if (pendingNativeMic) {
            pendingNativeMic = false;
            emitAudioEvent("permission", granted ? "true" : "false");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        // Let the page stop and keep a recording in progress before the WebView pauses.
        emitAudioEvent("pause", "0");
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        nativeAudio.shutdown();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    public void onBackPressed() {
        // The game moves between its own screens; ask the page first and leave only from the home screen.
        web.evaluateJavascript("(window.__appBack && window.__appBack()) ? '1' : '0'", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (value == null || !value.contains("1")) MainActivity.super.onBackPressed();
            }
        });
    }

    @Override
    protected void onDestroy() {
        nativeAudio.shutdown();
        web.destroy();
        web = null;
        super.onDestroy();
    }

    private static String mimeFor(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".json") || p.endsWith(".webmanifest")) return "application/json";
        if (p.endsWith(".wav")) return "audio/wav";
        return "application/octet-stream";
    }

    /** Exposed to JS as window.AndroidBridge. */
    private class Bridge {
        @JavascriptInterface
        public void shareWav(String base64, String name) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                File dir = new File(getCacheDir(), "share");
                if (!dir.exists()) dir.mkdirs();
                String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
                File f = new File(dir, safe);
                FileOutputStream out = new FileOutputStream(f);
                out.write(data);
                out.close();
                Uri uri = Uri.parse("content://" + ShareProvider.AUTHORITY + "/" + safe);
                final Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("audio/wav");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        startActivity(Intent.createChooser(send, "שיתוף"));
                    }
                });
            } catch (final Exception e) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, "השיתוף נכשל", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }
    }
}
