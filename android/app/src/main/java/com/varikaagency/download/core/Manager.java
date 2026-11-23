package com.varikaagency.download.core;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Liste des téléchargements, file d'attente et sauvegarde (équivalent de la classe Api de app.py).
 * Ne dépend pas d'Android : testé sur ordinateur avec tests/test_android_core.py.
 */
public class Manager {
    public interface Listener {
        /** Appelé (hors du fil de l'interface) quand un téléchargement se termine. */
        void onCompleted(Task t);

        /** Appelé quand l'activité change (pour démarrer / arrêter le service Android). */
        void onActivityChanged(boolean busy);
    }

    public static final String VERSION = "1.3.0";

    private final File stateFile;
    private final List<Task> order = new ArrayList<Task>();
    public final Map<String, Object> settings = new LinkedHashMap<String, Object>();
    private volatile boolean dirty, closing;
    private volatile Listener listener;
    private final Object saveLock = new Object();
    private boolean wasBusy;

    public Manager(File stateFile, File defaultDest) {
        this.stateFile = stateFile;
        settings.put("dest", defaultDest.getPath());
        settings.put("connections", 8L);
        settings.put("max_parallel", 3L);
        load();
        Thread loop = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "varika-file-attente");
        loop.setDaemon(true);
        loop.start();
    }

    public void setListener(Listener l) { listener = l; }

    // ------------------------------------------------------------------ persistance
    private void load() {
        if (!stateFile.isFile()) return;
        try {
            Map<String, Object> data = Json.obj(Json.parse(FileTask.readText(stateFile)));
            Map<String, Object> s = Json.obj(data.get("settings"));
            for (String k : new String[]{"connections", "max_parallel"}) if (s.get(k) instanceof Number) settings.put(k, s.get(k));
            for (Object o : Json.arr(data.get("items"))) {
                try {                                   // une entrée abîmée est ignorée, pas fatale
                    Map<String, Object> r = Json.obj(o);
                    if (Json.str(r, "id") == null || Json.str(r, "url") == null) continue;
                    Task t = Task.fromRecord(r);
                    if (find(t.id) == null) order.add(t);
                } catch (RuntimeException ignored) {
                    // suivant
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // fichier illisible : on repart d'une liste vide
        }
    }

    public void save() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        List<Object> items = new ArrayList<Object>();
        synchronized (order) {
            for (Task t : order) items.add(t.toRecord());
        }
        data.put("settings", settings);
        data.put("items", items);
        synchronized (saveLock) {
            try {
                FileTask.writeTextAtomic(stateFile, Json.write(data));
            } catch (IOException ignored) {
                // nouvel essai à la prochaine sauvegarde
            }
        }
    }

    private void loop() {
        long lastSave = 0;
        while (true) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
            boolean busy = false;
            List<Task> completed = new ArrayList<Task>();
            try {
                synchronized (order) {
                    int running = 0;
                    for (Task t : order) if (t.isRunning()) running++;
                    int max = ((Number) settings.get("max_parallel")).intValue();
                    for (Task t : order) {
                        if (closing || running >= max) break;
                        if (Task.QUEUED.equals(t.status) && !t.isRunning()) {
                            try {
                                t.start();
                                running++;
                            } catch (RuntimeException e) {
                                t.status = Task.ERROR;
                                t.error = "démarrage impossible : " + e.getMessage();
                            }
                        }
                    }
                    busy = running > 0;
                    for (Task t : order) {
                        if (Task.COMPLETED.equals(t.status) && t.publishedUri == null && !t.isRunning() && !published.contains(t.id)) {
                            published.add(t.id);
                            completed.add(t);
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // la file d'attente ne doit jamais s'arrêter
            }
            Listener l = listener;
            if (l != null) {
                for (Task t : completed) {
                    try {
                        l.onCompleted(t);
                    } catch (RuntimeException ignored) {
                        // rien
                    }
                    dirty = true;
                }
                if (busy != wasBusy) {
                    wasBusy = busy;
                    try {
                        l.onActivityChanged(busy);
                    } catch (RuntimeException ignored) {
                        // rien
                    }
                }
            }
            long now = System.currentTimeMillis();
            if (dirty || (busy && now - lastSave > 3000)) {
                dirty = false;
                lastSave = now;
                save();
            }
        }
    }

    private final java.util.Set<String> published = new java.util.HashSet<String>();

    public void shutdown() {
        closing = true;
        List<Task> running = new ArrayList<Task>();
        synchronized (order) {
            for (Task t : order) if (t.isRunning()) running.add(t);
        }
        for (Task t : running) t.pause();
        for (Task t : running) t.await();
        save();
    }

    // ------------------------------------------------------------------ actions
    public Task find(String id) {
        synchronized (order) {
            for (Task t : order) if (t.id.equals(id)) return t;
        }
        return null;
    }

    public boolean busy() {
        synchronized (order) {
            for (Task t : order) if (t.isRunning() || Task.QUEUED.equals(t.status)) return true;
        }
        return false;
    }

    private static boolean isHls(String url) {
        try {
            String p = new URL(url).getPath().toLowerCase(java.util.Locale.ROOT);
            return p.endsWith(".m3u8") || p.endsWith(".m3u");
        } catch (IOException e) {
            return false;
        }
    }

    /** Ajoute un téléchargement. {@code probe} : vérifie le lien tout de suite (liens venant d'un navigateur). */
    public Map<String, Object> add(String url, Integer connections, String filename, Map<String, String> headers,
                                   boolean probe, String kind, Integer quality) {
        Map<String, Object> res = new LinkedHashMap<String, Object>();
        url = url == null ? "" : url.trim();
        if (!url.toLowerCase(java.util.Locale.ROOT).startsWith("http://") && !url.toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
            res.put("ok", false);
            res.put("error", "Le lien doit commencer par http:// ou https://");
            return res;
        }
        int n = connections != null && connections > 0 ? connections : ((Number) settings.get("connections")).intValue();
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        File dest = new File(settings.get("dest").toString());
        Task t = "hls".equals(kind) || isHls(url) ? new HlsTask(id, url, dest, n, quality) : new FileTask(id, url, dest, n);
        t.forcedName = filename;
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty() && e.getValue().indexOf('\n') < 0 && e.getValue().indexOf('\r') < 0) {
                t.headers.put(e.getKey(), e.getValue().length() > 16000 ? e.getValue().substring(0, 16000) : e.getValue());
            }
        }
        if (probe) {
            try {
                if (t instanceof HlsTask) ((HlsTask) t).probe();
                else ((FileTask) t).probe();
            } catch (Exception e) {
                res.put("ok", false);
                res.put("error", Net.message(e));
                return res;
            }
        }
        synchronized (order) {
            order.add(0, t);
        }
        dirty = true;
        res.put("ok", true);
        res.put("id", id);
        res.put("filename", t.filename);
        return res;
    }

    public void pause(String id) {
        Task t = find(id);
        if (t == null) return;
        if (t.isRunning()) t.pause();
        else if (Task.QUEUED.equals(t.status)) t.status = Task.PAUSED;
        dirty = true;
    }

    public void resume(String id) {
        Task t = find(id);
        if (t != null && !t.isRunning() && (Task.PAUSED.equals(t.status) || Task.ERROR.equals(t.status))) {
            t.status = Task.QUEUED;
            dirty = true;
        }
    }

    public void pauseAll() {
        for (Task t : snapshot()) pause(t.id);
    }

    public void resumeAll() {
        for (Task t : snapshot()) resume(t.id);
    }

    public List<Task> snapshot() {
        synchronized (order) {
            return new ArrayList<Task>(order);
        }
    }

    /** Retire de la liste ; supprime les fichiers seulement si demandé. */
    public Task remove(String id, final boolean deleteFiles) {
        final Task t = find(id);
        if (t == null) return null;
        synchronized (order) {
            order.remove(t);
        }
        dirty = true;
        Thread th = new Thread(new Runnable() {
            @Override public void run() {
                t.pause();
                t.await();
                if (deleteFiles) t.cleanupFiles(true);
            }
        });
        th.setDaemon(true);
        th.start();
        return t;
    }

    public int clearCompleted() {
        int n = 0;
        synchronized (order) {
            for (Task t : new ArrayList<Task>(order)) {
                if (Task.COMPLETED.equals(t.status)) {
                    order.remove(t);
                    n++;
                }
            }
        }
        dirty = true;
        return n;
    }

    public Map<String, Object> saveSettings(Map<String, Object> s) {
        if (s.get("connections") instanceof Number) {
            settings.put("connections", (long) Math.max(1, Math.min(32, ((Number) s.get("connections")).intValue())));
        }
        if (s.get("max_parallel") instanceof Number) {
            settings.put("max_parallel", (long) Math.max(1, Math.min(10, ((Number) s.get("max_parallel")).intValue())));
        }
        dirty = true;
        return settings;
    }

    public Map<String, Object> state(Map<String, Object> extra) {
        Map<String, Object> st = new LinkedHashMap<String, Object>();
        List<Object> items = new ArrayList<Object>();
        double totalSpeed = 0;
        for (Task t : snapshot()) {
            totalSpeed += t.speed;
            items.add(t.view());
        }
        st.put("items", items);
        st.put("totalSpeed", totalSpeed);
        st.put("settings", settings);
        st.put("version", VERSION);
        if (extra != null) st.putAll(extra);
        return st;
    }

    public void markDirty() { dirty = true; }
}
