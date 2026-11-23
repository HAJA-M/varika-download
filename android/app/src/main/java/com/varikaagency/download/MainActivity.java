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
        // Android 10 : permission nécessaire pour écrire directement dans Téléchargements (gros fichiers sans copie)
        if (Build.VERSION.SDK_INT == 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 2);
        }
        handleIntent(getIntent());
        if (!Boolean.FALSE.equals(VarikaApp.manager().settings.get("auto_update"))) {
            Updater.check();                    // une vérification discrète à chaque ouverture
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == 2 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            VarikaApp.manager().settings.put("dest", VarikaApp.pickStorage(this).getPath());
        }
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

    static final long LARGE_FILE = 1024L * 1024 * 1024;      // au-delà de 1 Go sur données mobiles : on demande

    /**
     * Gros fichier et téléphone sur données mobiles : par prudence le téléchargement attend le Wi-Fi,
     * et on demande à l'utilisateur s'il préfère quand même utiliser ses données.
     */
    static void askIfMetered(final Activity a, Map<String, Object> res) {
        if (!Boolean.TRUE.equals(res.get("ok"))) return;
        final Manager m = VarikaApp.manager();
        final Task t = m.find(String.valueOf(res.get("id")));
        boolean metered = !Boolean.TRUE.equals(m.network().get("unmetered"));
        if (t == null || !metered || t.total < LARGE_FILE || t.wifiOnly != null || m.needsWifi(t)) return;
        m.setWifiOnly(t.id, true);                               // en attendant la réponse : on ne consomme rien
        new Handler(Looper.getMainLooper()).post(() -> {
            if (a.isFinishing()) return;
            new android.app.AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(R.string.metered_title)
                    .setMessage("« " + t.filename + " » fait " + Net.humanSize(t.total) + ". Vous êtes sur données mobiles : "
                            + "le télécharger maintenant peut consommer une grande partie de votre forfait.")
                    .setPositiveButton("Attendre le Wi-Fi", (d, w) -> m.setWifiOnly(t.id, true))
                    .setNegativeButton("Télécharger maintenant", (d, w) -> m.setWifiOnly(t.id, false))
                    .setCancelable(false)
                    .show();
        });
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
        // On vérifie le lien tout de suite (taille, nom) ; hors connexion, il est mis en file et attendra le réseau
        res = VarikaApp.manager().add(url, n, null, null, true, null, null);
        if (!Boolean.TRUE.equals(res.get("ok")) && res.get("error") != null && isOfflineError(String.valueOf(res.get("error")))) {
            res = VarikaApp.manager().add(url, n, null, null, false, null, null);
            if (Boolean.TRUE.equals(res.get("ok"))) {
                notifyUi("Ajouté, en attente du réseau", "Le téléchargement démarrera dès le retour d'Internet", "info");
                return res;
            }
        }
        if (Boolean.TRUE.equals(res.get("ok"))) {
            Object size = res.get("size");
            notifyUi("Ajouté à la liste", res.get("filename") + (size instanceof Number ? " · " + Net.humanSize(((Number) size).doubleValue()) : ""), "info");
            askIfMetered(this, res);
        } else {
            notifyUi("Lien refusé", String.valueOf(res.get("error")), "err");
        }
        return res;
    }

    private static boolean isOfflineError(String msg) {
        return msg.contains("pas de connexion") || msg.contains("ne répond pas") || msg.contains("Unable to resolve")
                || msg.contains("connect") || msg.contains("Network is unreachable");
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
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            extra.put("batteryRestricted", !pm.isIgnoringBatteryOptimizations(getPackageName()));
            extra.put("directStorage", VarikaApp.directStorage);
            extra.put("update", Updater.state());
            return ok(m().state(extra));
        }

        @JavascriptInterface public String add(String url, String connections) {
            // la vérification du lien peut prendre quelques secondes : elle ne doit pas bloquer l'interface
            new Thread(() -> addSmart(url, null, connections)).start();
            Map<String, Object> r = new LinkedHashMap<String, Object>();
            r.put("ok", true);
            r.put("pending", true);
            return ok(r);
        }

        @JavascriptInterface public String check_update() {
            Updater.check();
            return ok(Updater.state());
        }

        @JavascriptInterface public String install_update() {
            Updater.install(MainActivity.this);
            return ok(Updater.state());
        }

        @JavascriptInterface public String open_battery_settings() {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (ActivityNotFoundException e) {
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
            }
            return "null";
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
