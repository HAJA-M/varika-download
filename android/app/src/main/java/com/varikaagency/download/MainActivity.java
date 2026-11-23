package com.varikaagency.download;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.varikaagency.download.core.Json;
import com.varikaagency.download.core.Manager;
import com.varikaagency.download.core.Net;
import com.varikaagency.download.core.Task;

import java.net.HttpURLConnection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Écran principal : la même interface que la version Windows (ui/index.html), affichée dans
 * une WebView, avec un pont JavaScript -> Java (VarikaAndroid) vers le moteur.
 */
public class MainActivity extends Activity {
    static final String ACTION_PAUSE_ALL = "com.varikaagency.download.PAUSE_ALL";
    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);

    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Window w = getWindow();
        w.setStatusBarColor(Color.parseColor("#0b0c0b"));
        w.setNavigationBarColor(Color.parseColor("#0b0c0b"));
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0b0c0b"));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        web.addJavascriptInterface(new Bridge(), "VarikaAndroid");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !r.getUrl().toString().startsWith("file:///android_asset/");   // l'interface ne quitte jamais ses fichiers
            }
        });
        web.loadUrl("file:///android_asset/ui/index.html");
        setContentView(web);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        if (VarikaApp.manager().busy()) {            // relance le service si Android l'avait refusé en arrière-plan
            try {
                startForegroundService(new Intent(this, DownloadService.class));
            } catch (RuntimeException ignored) {
                // rien
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        web.evaluateJavascript("window.varikaBack ? window.varikaBack() : false", value -> {
            if (!"true".equals(value)) super.onBackPressed();
        });
    }

    /** Liens partagés depuis une autre application (navigateur, WhatsApp, Telegram…). */
    private void handleIntent(Intent i) {
        if (i == null) return;
        if (ACTION_PAUSE_ALL.equals(i.getAction())) {
            VarikaApp.manager().pauseAll();
            return;
        }
        String text = null;
        if (Intent.ACTION_SEND.equals(i.getAction())) text = i.getStringExtra(Intent.EXTRA_TEXT);
        else if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) text = i.getData().toString();
        if (text == null) return;
        Matcher m = URL_IN_TEXT.matcher(text);
        if (!m.find()) {
            notifyUi("Aucun lien trouvé", "Le texte partagé ne contient pas de lien http(s)", "err");
            return;
        }
        final String url = m.group();
        final String title = i.getStringExtra(Intent.EXTRA_SUBJECT);
        new Thread(() -> addSmart(url, title, null)).start();
    }

    /**
     * Ajoute un lien. Si c'est une page web (et pas un fichier), on l'ouvre dans le navigateur
     * vidéo pour y trouver la vidéo, au lieu de télécharger la page HTML.
     */
    Map<String, Object> addSmart(String url, String title, String connections) {
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        String host = Net.host(url).toLowerCase(Locale.ROOT);
        if (BrowserActivity.isBlocked(host)) {
            res.put("ok", false);
            res.put("error", getString(R.string.youtube_blocked));
            notifyUi("YouTube n'est pas pris en charge", getString(R.string.youtube_blocked), "err");
            return res;
        }
        if (isWebPage(url)) {
            main.post(() -> startActivity(new Intent(this, BrowserActivity.class).putExtra("url", url)));
            res.put("ok", false);
            res.put("openBrowser", true);
            return res;
        }
        Integer n = null;
        try {
            if (connections != null && !connections.isEmpty()) n = Integer.parseInt(connections);
        } catch (NumberFormatException ignored) {
            // valeur par défaut
        }
        res = VarikaApp.manager().add(url, n, null, null, false, null, null);
        if (Boolean.TRUE.equals(res.get("ok"))) notifyUi("Ajouté à la liste", Net.host(url), "info");
        else notifyUi("Lien refusé", String.valueOf(res.get("error")), "err");
        return res;
    }

    static boolean isWebPage(String url) {
        String path = url.split("[?#]")[0].toLowerCase(Locale.ROOT);
        if (path.matches(".*\\.(m3u8|mp4|mkv|webm|mov|avi|mp3|m4a|flac|wav|zip|rar|7z|apk|pdf|iso|exe|msi|gz|tar|docx?|xlsx?|pptx?|jpe?g|png|gif)$")) {
            return false;
        }
        try {
            Map<String, String> h = new LinkedHashMap<String, String>();
            h.put("Range", "bytes=0-0");
            HttpURLConnection c = Net.open(url, h);
            String type = String.valueOf(c.getContentType()).toLowerCase(Locale.ROOT);
            String cd = c.getHeaderField("Content-Disposition");
            c.disconnect();
            return type.startsWith("text/html") && (cd == null || !cd.toLowerCase(Locale.ROOT).contains("attachment"));
        } catch (Exception e) {
            return false;
        }
    }

    void notifyUi(String title, String text, String type) {
        Map<String, Object> msg = new LinkedHashMap<String, Object>();
        msg.put("title", title);
        msg.put("text", text);
        msg.put("type", type);
        final String js = "window.varikaShared && window.varikaShared(" + Json.write(msg) + ")";
        main.post(() -> {
            if (web != null) web.evaluateJavascript(js, null);
        });
    }

    /** Méthodes appelées par l'interface (mêmes noms que la classe Api de la version Windows). */
    final class Bridge {
        private Manager m() { return VarikaApp.manager(); }

        private String ok(Object o) { return Json.write(o); }

        @JavascriptInterface public String get_state() {
            Map<String, Object> extra = new LinkedHashMap<String, Object>();
            extra.put("platform", "android");
            return ok(m().state(extra));
        }

        @JavascriptInterface public String add(String url, String connections) {
            return ok(addSmart(url, null, connections));
        }

        @JavascriptInterface public String pause(String id) { m().pause(id); return "null"; }

        @JavascriptInterface public String resume(String id) { m().resume(id); return "null"; }

        @JavascriptInterface public String pause_all() { m().pauseAll(); return "null"; }

        @JavascriptInterface public String resume_all() { m().resumeAll(); return "null"; }

        @JavascriptInterface public String remove(String id, String deleteFiles) {
            boolean del = "true".equals(deleteFiles);
            Task t = m().remove(id, del);
            if (t != null && del) Publisher.unpublish(MainActivity.this, t);
            return "null";
        }

        @JavascriptInterface public String clear_completed() { return String.valueOf(m().clearCompleted()); }

        @JavascriptInterface public String open_file(String id) {
            Task t = m().find(id);
            if (t == null || t.publishedUri == null) return "false";
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(t.publishedUri), Publisher.mime(t.filename))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(Intent.createChooser(i, t.filename));
                return "true";
            } catch (ActivityNotFoundException e) {
                return "false";
            }
        }

        @JavascriptInterface public String open_folder(String id) {
            try {
                startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException e) {
                notifyUi("Dossier", "Ouvrez l'application Fichiers > Téléchargements > Varika Download", "info");
            }
            return "null";
        }

        @JavascriptInterface public String open_browser(String url) {
            main.post(() -> startActivity(new Intent(MainActivity.this, BrowserActivity.class).putExtra("url", url)));
            return "null";
        }

        @JavascriptInterface public String save_settings(String json) {
            return ok(m().saveSettings(Json.obj(Json.parse(json))));
        }

        @JavascriptInterface public String read_clipboard() {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData cd = cm == null ? null : cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return ok("");
            CharSequence t = cd.getItemAt(0).getText();
            String s = t == null ? "" : t.toString().trim();
            return ok(s.matches("(?i)https?://\\S+") && s.length() < 4000 ? s : "");
        }

        @JavascriptInterface public String free_space() {
            try {
                StatFs st = new StatFs(Environment.getExternalStorageDirectory().getPath());
                return String.valueOf(st.getAvailableBytes());
            } catch (RuntimeException e) {
                return "null";
            }
        }

        // Fonctions de la version Windows sans équivalent sur téléphone
        @JavascriptInterface public String choose_folder() { return "null"; }

        @JavascriptInterface public String open_extension_folder() { return "false"; }

        @JavascriptInterface public String open_browser_extensions(String b) { return "null"; }
    }
}
