package com.varikaagency.download;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import com.varikaagency.download.core.FileTask;
import com.varikaagency.download.core.Json;
import com.varikaagency.download.core.Manager;
import com.varikaagency.download.core.Net;
import com.varikaagency.download.core.Task;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mises à jour automatiques (Android) : même principe que updater.py sur Windows.
 * Dernière « Release » GitHub -> APK téléchargé -> empreinte SHA-256 vérifiée -> installation
 * par l'installeur système (l'utilisateur confirme ; Android exige la même signature).
 */
final class Updater {
    static final String REPO = "HAJA-M/varika-download-releases";
    static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";

    private static final Map<String, Object> STATE = new LinkedHashMap<String, Object>();
    private static volatile Map<String, Object> release;
    private static volatile FileTask download;

    static {
        STATE.put("status", "idle");
        STATE.put("available", false);
        STATE.put("current", Manager.VERSION);
    }

    static synchronized Map<String, Object> state() {
        Map<String, Object> s = new LinkedHashMap<String, Object>(STATE);
        FileTask d = download;
        if (d != null) s.put("progress", d.progress());
        return s;
    }

    static int[] version(String v) {
        Matcher m = Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?").matcher(v == null ? "" : v);
        int[] out = new int[3];
        if (m.find()) for (int i = 0; i < 3; i++) out[i] = m.group(i + 1) == null ? 0 : Integer.parseInt(m.group(i + 1));
        return out;
    }

    static boolean newer(String remote, String local) {
        int[] r = version(remote), l = version(local);
        for (int i = 0; i < 3; i++) if (r[i] != l[i]) return r[i] > l[i];
        return false;
    }

    private static String get(String url, int limit) throws Exception {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Accept", "application/vnd.github+json");
        HttpURLConnection c = Net.open(url, h);
        try {
            if (c.getResponseCode() >= 400) throw new java.io.IOException("HTTP " + c.getResponseCode());
            return new String(Net.readAll(c.getInputStream(), limit), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** Vérification (en arrière-plan). */
    static void check() {
        new Thread(() -> {
            try {
                Map<String, Object> rel = Json.obj(Json.parse(get(API, 2_000_000)));
                release = rel;
                String v = String.valueOf(rel.get("tag_name")).replaceFirst("^v", "");
                synchronized (Updater.class) {
                    STATE.put("version", v);
                    STATE.put("notes", String.valueOf(rel.get("body")));
                    STATE.put("available", newer(v, Manager.VERSION));
                    STATE.put("error", null);
                }
            } catch (Exception e) {
                synchronized (Updater.class) { STATE.put("error", "vérification impossible : " + Net.message(e)); }
            }
        }).start();
    }

    private static String asset(String suffix) {
        if (release == null) return null;
        for (Object o : Json.arr(release.get("assets"))) {
            Map<String, Object> a = Json.obj(o);
            String n = String.valueOf(a.get("name"));
            if (n.matches(suffix)) return n + "\n" + a.get("browser_download_url");
        }
        return null;
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) md.update(b, 0, n);
        } finally {
            in.close();
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Télécharge, vérifie et ouvre l'installeur système. */
    static void install(final Context c) {
        synchronized (Updater.class) {
            Object st = STATE.get("status");
            if ("downloading".equals(st) || "installing".equals(st)) return;
            STATE.put("status", "downloading");
            STATE.put("error", null);
        }
        new Thread(() -> {
            try {
                if (Build.VERSION.SDK_INT >= 26 && !c.getPackageManager().canRequestPackageInstalls()) {
                    // Android demande d'autoriser Varika Download à installer des applications (une seule fois)
                    c.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName()))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    throw new IllegalStateException("autorisez « Installer des applications inconnues », puis appuyez de nouveau sur Mettre à jour");
                }
                String apk = asset("VarikaDownload-.*\\.apk");
                String sums = asset("SHA256SUMS.*");
                if (apk == null) throw new IllegalStateException("cette version ne contient pas d'APK");
                if (sums == null) throw new IllegalStateException("empreintes SHA-256 absentes : mise à jour refusée par sécurité");
                String name = apk.split("\n")[0], url = apk.split("\n")[1];
                String expected = null;
                for (String line : get(sums.split("\n")[1], 200_000).split("\n")) {
                    String[] p = line.trim().split("\\s+\\*?", 2);
                    if (p.length == 2 && p[1].trim().equals(name)) expected = p[0].toLowerCase();
                }
                if (expected == null) throw new IllegalStateException("empreinte de l'APK introuvable : mise à jour refusée");
                File dir = new File(c.getExternalFilesDir(null), "updates");
                FileTask d = new FileTask("maj", url, dir, 4);
                d.forcedName = name;
                download = d;
                d.start();
                d.await();
                download = null;
                if (!Task.COMPLETED.equals(d.status)) throw new IllegalStateException("téléchargement : " + d.error);
                if (!expected.equals(sha256(d.path))) {
                    if (!d.path.delete()) d.path.deleteOnExit();
                    throw new SecurityException("le fichier ne correspond pas à l'empreinte publiée : mise à jour refusée");
                }
                synchronized (Updater.class) { STATE.put("status", "installing"); }
                installApk(c, d.path);
            } catch (Exception e) {
                synchronized (Updater.class) {
                    STATE.put("status", "error");
                    STATE.put("error", Net.message(e));
                }
            }
        }).start();
    }

    /** Installe via PackageInstaller (pas besoin de partager le fichier avec une autre application). */
    private static void installApk(Context c, File apk) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int id = pi.createSession(params);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            OutputStream out = s.openWrite("varika.apk", 0, apk.length());
            InputStream in = new FileInputStream(apk);
            try {
                byte[] b = new byte[1 << 16];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                s.fsync(out);
            } finally {
                in.close();
                out.close();
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent p = PendingIntent.getBroadcast(c, id, new Intent(c, Result.class), flags);
            s.commit(p.getIntentSender());
        } finally {
            s.close();
        }
    }

    /** Réponse de l'installeur : affiche la confirmation demandée par Android, ou l'erreur. */
    public static final class Result extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            synchronized (Updater.class) {
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    STATE.put("status", "idle");
                } else {
                    STATE.put("status", "error");
                    String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    STATE.put("error", status == PackageInstaller.STATUS_FAILURE_CONFLICT
                            ? "signature différente : désinstallez l'ancienne version puis installez la nouvelle"
                            : "installation annulée ou impossible" + (msg != null ? " (" + msg + ")" : ""));
                }
            }
        }
    }
}
