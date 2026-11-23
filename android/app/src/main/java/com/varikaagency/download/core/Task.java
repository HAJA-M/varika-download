package com.varikaagency.download.core;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Base commune d'un téléchargement (fichier classique ou vidéo HLS). */
public abstract class Task {
    // Clés de statut, identiques à celles qu'attend l'interface (ui/index.html)
    public static final String QUEUED = "queued", PROBING = "probing", DOWNLOADING = "downloading",
            PAUSED = "paused", COMPLETED = "completed", ERROR = "error", ASSEMBLING = "assembling";

    public final String id;
    public String url;
    public String sourceUrl;          // lien d'origine (avant redirections) : renouvelle un lien expiré
    public final File destDir;
    public int connections;
    public String forcedName;
    public final Map<String, String> headers = new LinkedHashMap<String, String>();
    public String filename;
    public File path;
    public long total = -1;
    public volatile String status = QUEUED;
    public volatile String error;
    public volatile String note;
    public volatile double speed;
    public volatile int active;
    public long added = System.currentTimeMillis() / 1000;
    public String publishedUri;       // Android : adresse du fichier publié dans « Téléchargements »

    protected volatile boolean stop;
    protected volatile String fatal;
    protected volatile boolean probed;
    private Thread runner;

    protected Task(String id, String url, File destDir, int connections) {
        this.id = id;
        this.url = url;
        this.sourceUrl = url;
        this.destDir = destDir;
        this.connections = Math.max(1, Math.min(32, connections));
    }

    public abstract String kind();

    public abstract long downloaded();

    public abstract double progress();

    public abstract List<double[]> uiSegments();

    public abstract boolean resumable();

    protected abstract void run();

    public abstract void cleanupFiles(boolean deleteFinal);

    public String variant() { return null; }

    public Double eta() {
        if (total <= 0 || speed < 1) return null;
        return (total - downloaded()) / speed;
    }

    public synchronized void start() {
        if (isRunning() || COMPLETED.equals(status)) return;
        stop = false;
        fatal = null;
        error = null;
        runner = new Thread(new Runnable() {
            @Override public void run() { Task.this.run(); }
        }, "varika-" + id);
        runner.setDaemon(true);
        runner.start();
    }

    public void pause() { stop = true; }

    public void await() {
        Thread t = runner;
        if (t == null) return;
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isRunning() {
        Thread t = runner;
        return t != null && t.isAlive();
    }

    /** Attente interruptible par une pause. */
    protected void sleepUnlessStopped(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (!stop && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(Math.min(100, ms));
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    protected Map<String, String> requestHeaders(Map<String, String> extra) {
        Map<String, String> h = new LinkedHashMap<String, String>(headers);
        // les cookies du site d'origine ne partent jamais vers un autre site (même après une redirection)
        if (!Net.host(url).equalsIgnoreCase(Net.host(sourceUrl))) {
            h.remove("Cookie");
            h.remove("Authorization");
        }
        if (extra != null) h.putAll(extra);
        return h;
    }

    // ------------------------------------------------------------------ sauvegarde
    public Map<String, Object> toRecord() {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("id", id);
        r.put("kind", kind());
        r.put("url", url);
        r.put("source_url", sourceUrl);
        r.put("dest_dir", destDir.getPath());
        r.put("connections", connections);
        r.put("filename", filename);
        r.put("path", path == null ? null : path.getPath());
        r.put("total", total);
        r.put("status", status);
        r.put("error", error);
        r.put("added", added);
        r.put("uri", publishedUri);
        Map<String, Object> h = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (!e.getKey().equalsIgnoreCase("Cookie")) h.put(e.getKey(), e.getValue());  // pas de cookies sur le disque
        }
        r.put("headers", h);
        return r;
    }

    protected void restoreCommon(Map<String, Object> r) {
        sourceUrl = Json.str(r, "source_url") != null ? Json.str(r, "source_url") : url;
        filename = Json.str(r, "filename");
        String p = Json.str(r, "path");
        path = p == null ? null : new File(p);
        total = Json.num(r, "total", -1);
        error = Json.str(r, "error");
        added = Json.num(r, "added", added);
        publishedUri = Json.str(r, "uri");
        for (Map.Entry<String, Object> e : Json.obj(r.get("headers")).entrySet()) {
            if (e.getValue() != null) headers.put(e.getKey(), e.getValue().toString());
        }
        String st = Json.str(r, "status");
        if (COMPLETED.equals(st) && (publishedUri != null || (path != null && path.exists()))) status = COMPLETED;
        else status = ERROR.equals(st) ? ERROR : PAUSED;
    }

    public static Task fromRecord(Map<String, Object> r) {
        String id = Json.str(r, "id");
        String url = Json.str(r, "url");
        File dest = new File(Json.str(r, "dest_dir"));
        int conns = (int) Json.num(r, "connections", 8);
        Task t = "hls".equals(Json.str(r, "kind")) ? new HlsTask(id, url, dest, conns, HlsTask.qualityOf(r)) : new FileTask(id, url, dest, conns);
        t.restore(r);
        return t;
    }

    protected abstract void restore(Map<String, Object> r);

    /** Ce que l'interface affiche. */
    public Map<String, Object> view() {
        Map<String, Object> v = new LinkedHashMap<String, Object>();
        boolean done = COMPLETED.equals(status);
        v.put("id", id);
        v.put("url", url);
        v.put("filename", filename);
        v.put("path", path == null ? null : path.getPath());
        v.put("total", total > 0 ? total : null);
        v.put("downloaded", done ? (total > 0 ? total : downloaded()) : downloaded());
        v.put("progress", done ? 1.0 : progress());
        v.put("speed", speed);
        v.put("eta", eta());
        v.put("status", status);
        v.put("error", error);
        v.put("active", active);
        v.put("connections", connections);
        v.put("resumable", resumable());
        List<Object> segs = new ArrayList<Object>();
        if (!done) for (double[] s : uiSegments()) segs.add(s);
        v.put("segments", segs);
        v.put("added", added);
        v.put("kind", kind());
        v.put("variant", variant());
        v.put("note", note);
        return v;
    }
}
