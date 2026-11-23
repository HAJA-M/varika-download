package com.varikaagency.download;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.varikaagency.download.core.Net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Navigateur vidéo : équivalent mobile de l'extension. Il observe les requêtes de la page
 * et repère les vidéos (MP4, WebM, streaming HLS…) ; un bouton les envoie à Varika Download.
 */
public class BrowserActivity extends Activity {
    private static final Pattern MEDIA = Pattern.compile(".*\\.(m3u8|mp4|webm|mkv|mov|m4v|flv|mp3|m4a|ogg|opus|wav|flac)$");
    private static final Pattern SEGMENT = Pattern.compile(".*\\.(ts|m4s|aac|vtt|key)$");
    private static final Pattern BLOCKED = Pattern.compile("(^|.*\\.)(youtube\\.com|youtu\\.be|youtube-nocookie\\.com|googlevideo\\.com)$");

    static boolean isBlocked(String host) {
        return host != null && BLOCKED.matcher(host.toLowerCase(Locale.ROOT)).matches();
    }

    private WebView web;
    private EditText address;
    private Button found;
    private ProgressBar progress;
    private final Map<String, String> media = new LinkedHashMap<String, String>();   // url -> type
    private final Handler main = new Handler(Looper.getMainLooper());
    private String pageUrl = "";

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(Color.parseColor("#0b0c0b"));
        int pad = dp(8);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0b0c0b"));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(pad, pad, pad, pad);
        ImageButton close = new ImageButton(this);
        close.setImageResource(R.drawable.ic_close);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setContentDescription(getString(R.string.close));
        close.setOnClickListener(v -> finish());
        address = new EditText(this);
        address.setSingleLine(true);
        address.setHint(R.string.browser_hint);
        address.setTextColor(Color.parseColor("#eef1ea"));
        address.setHintTextColor(Color.parseColor("#646d61"));
        address.setTextSize(15);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setPadding(dp(14), dp(10), dp(14), dp(10));
        address.setBackground(round("#141714", "#252a25", 12));
        address.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_GO || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                go(address.getText().toString());
                return true;
            }
            return false;
        });
        bar.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        bar.addView(address, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        FrameLayout frame = new FrameLayout(this);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setAllowFileAccess(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new PageSpy(), "VarikaPage");
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView v, int p) {
                progress.setProgress(p);
                progress.setVisibility(p >= 100 ? View.INVISIBLE : View.VISIBLE);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String scheme = r.getUrl().getScheme();
                return scheme == null || !(scheme.equals("http") || scheme.equals("https"));   // pas d'applis externes
            }

            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
                pageUrl = url;
                address.setText(url);
                synchronized (media) { media.clear(); }
                refreshButton();
            }

            @Override public void onPageFinished(WebView v, String url) {
                // repère aussi les <video src="…"> classiques (pas seulement le trafic réseau)
                v.evaluateJavascript("(function(){var f=function(){document.querySelectorAll('video,audio,source').forEach(function(e){"
                        + "var s=e.currentSrc||e.src;if(s&&/^https?:/.test(s))VarikaPage.found(s);});};f();setInterval(f,2000);})()", null);
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                consider(r.getUrl().toString(), r.getRequestHeaders() != null ? r.getRequestHeaders().get("Accept") : null);
                return null;                 // on observe seulement, on ne modifie rien
            }
        });
        frame.addView(web);

        found = new Button(this);
        found.setAllCaps(false);
        found.setTextColor(Color.parseColor("#0A0B0A"));
        found.setTextSize(15);
        found.setBackground(round("#BFFE6F", null, 24));
        found.setPadding(dp(20), 0, dp(20), 0);
        found.setVisibility(View.GONE);
        found.setOnClickListener(v -> choose());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48), Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, dp(16), dp(20));
        frame.addView(found, lp);
        root.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        String start = getIntent().getStringExtra("url");
        if (start != null && !start.isEmpty()) go(start);
        else address.requestFocus();
    }

    private void go(String text) {
        String t = text.trim();
        if (t.isEmpty()) return;
        if (!t.matches("(?i)https?://.*")) {
            t = t.contains(".") && !t.contains(" ") ? "https://" + t
                    : "https://duckduckgo.com/?q=" + android.net.Uri.encode(t);
        }
        if (isBlocked(Net.host(t))) {
            Toast.makeText(this, R.string.youtube_blocked, Toast.LENGTH_LONG).show();
        }
        web.loadUrl(t);
    }

    private void consider(String url, String accept) {
        if (!url.startsWith("http")) return;
        if (isBlocked(Net.host(url)) || isBlocked(Net.host(pageUrl))) return;
        String path = url.split("[?#]")[0].toLowerCase(Locale.ROOT);
        if (SEGMENT.matcher(path).matches()) return;
        String type;
        if (path.endsWith(".m3u8")) type = "hls";
        else if (MEDIA.matcher(path).matches()) type = path.matches(".*\\.(mp3|m4a|ogg|opus|wav|flac)$") ? "audio" : "video";
        else return;
        synchronized (media) {
            if (media.containsKey(url)) return;
            // une playlist fille d'une playlist maître déjà vue n'est pas proposée en double
            if ("hls".equals(type)) for (Map.Entry<String, String> e : media.entrySet()) if ("hls".equals(e.getValue())) return;
            media.put(url, type);
        }
        refreshButton();
    }

    private void refreshButton() {
        main.post(() -> {
            int n;
            synchronized (media) { n = media.size(); }
            found.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
            found.setText(n == 1 ? getString(R.string.download_video) : getString(R.string.download_n, n));
        });
    }

    private void choose() {
        final List<String> urls = new ArrayList<String>();
        List<String> labels = new ArrayList<String>();
        synchronized (media) {
            for (Map.Entry<String, String> e : media.entrySet()) {
                urls.add(e.getKey());
                String name = e.getKey().split("[?#]")[0];
                name = name.substring(name.lastIndexOf('/') + 1);
                labels.add(("hls".equals(e.getValue()) ? "Streaming (meilleure qualité)" : "audio".equals(e.getValue()) ? "Audio" : "Vidéo")
                        + " · " + TextUtils.ellipsize(name, new android.text.TextPaint(), 600, TextUtils.TruncateAt.MIDDLE));
            }
        }
        if (urls.size() == 1) {
            send(urls.get(0));
            return;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.choose_media)
                .setItems(labels.toArray(new String[0]), (d, which) -> send(urls.get(which)))
                .show();
    }

    private void send(final String url) {
        final String title = web.getTitle();
        final String referer = pageUrl;
        final String ua = web.getSettings().getUserAgentString();
        final String cookies = CookieManager.getInstance().getCookie(url);
        final String type;
        synchronized (media) { type = media.get(url); }
        Toast.makeText(this, R.string.sending, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Map<String, String> h = new LinkedHashMap<String, String>();
            h.put("Referer", referer);
            h.put("User-Agent", ua);
            if (cookies != null) h.put("Cookie", cookies);
            // vidéo en streaming : nommée d'après le titre de la page ; fichier direct : nom donné par le serveur
            String name = "hls".equals(type) && title != null && !title.trim().isEmpty() ? Net.safeName(title.trim()) + ".mp4" : null;
            Map<String, Object> r = VarikaApp.manager().add(url, null, name, h, true, "hls".equals(type) ? "hls" : null, null);
            main.post(() -> {
                if (Boolean.TRUE.equals(r.get("ok"))) {
                    Toast.makeText(this, getString(R.string.added, String.valueOf(r.get("filename"))), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, getString(R.string.failed, String.valueOf(r.get("error"))), Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable round(String fill, String stroke, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(fill));
        g.setCornerRadius(dp(radius));
        if (stroke != null) g.setStroke(dp(1), Color.parseColor(stroke));
        return g;
    }

    /** Reçoit les sources des balises <video> de la page. */
    final class PageSpy {
        @JavascriptInterface public void found(String url) { consider(url, null); }
    }
}
