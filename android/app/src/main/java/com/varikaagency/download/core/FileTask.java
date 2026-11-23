package com.varikaagency.download.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Téléchargement d'un fichier en plusieurs connexions (même algorithme que engine.py) :
 * sondage Range, segments, segmentation dynamique, reprise, vérifications d'intégrité.
 */
public class FileTask extends Task {
    static final int CHUNK = 64 * 1024;
    static final long MIN_SPLIT = 512 * 1024;
    static final int MAX_RETRIES = 8;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-\\d+/(\\d+|\\*)");

    static final class Segment {
        long start, end, pos;
        boolean busy;

        Segment(long start, long end, long pos) { this.start = start; this.end = end; this.pos = pos; }

        long remaining() { return Math.max(0, end - pos + 1); }

        boolean done() { return pos > end; }
    }

    private final List<Segment> segments = new ArrayList<Segment>();
    private boolean resumable;
    private String etag;
    private volatile long streamBytes;
    private int notRanged;
    /** Un seul canal d'écriture partagé par toutes les connexions (écritures positionnelles). */
    private volatile java.nio.channels.FileChannel channel;
    private volatile String waitingWhy;

    public FileTask(String id, String url, File destDir, int connections) {
        super(id, url, destDir, connections);
    }

    @Override public String kind() { return "file"; }

    @Override public boolean resumable() { return resumable; }

    File part() { return new File(path.getPath() + ".part"); }

    File stateFile() { return new File(path.getPath() + ".part.json"); }

    @Override public long downloaded() {
        if (!resumable) return streamBytes;
        synchronized (segments) {
            long n = 0;
            for (Segment s : segments) n += s.pos - s.start;
            return n;
        }
    }

    @Override public double progress() {
        if (COMPLETED.equals(status)) return 1;
        return total > 0 ? Math.min(1.0, downloaded() / (double) total) : 0;
    }

    @Override public List<double[]> uiSegments() {
        List<double[]> out = new ArrayList<double[]>();
        if (total <= 0) return out;
        synchronized (segments) {
            for (Segment s : segments) {
                if (out.size() >= 256) break;
                out.add(new double[]{s.start / (double) total, (s.end + 1) / (double) total, Math.min(s.pos, s.end + 1) / (double) total});
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ déroulement
    @Override protected void run() {
        try {
            if (path == null) {
                if (!probed) {
                    status = PROBING;
                    probe();
                }
                prepare();
            } else if (resumable && !probed) {
                status = PROBING;
                refresh();
            }
            status = DOWNLOADING;
            waitingWhy = null;
            boolean complete = resumable ? runSegmented() : runSingle();
            if (waitingWhy != null) throw new java.net.SocketException(waitingWhy);
            if (complete) {
                finish();
            } else {
                saveState();
                status = PAUSED;
                probed = false;
            }
        } catch (Exception e) {
            failed(e);                         // coupure réseau -> « en attente », reprise automatique
            probed = false;
            try {
                saveState();
            } catch (Exception ignored) {
                // rien
            }
        } finally {
            speed = 0;
            active = 0;
            if (PAUSED.equals(status) || ERROR.equals(status) || WAITING.equals(status)) Net.release(path);
        }
    }

    private HttpURLConnection ask(String u) throws IOException {
        Map<String, String> range = new LinkedHashMap<String, String>();
        range.put("Range", "bytes=0-0");
        HttpURLConnection c = Net.open(u, requestHeaders(range));
        if (c.getResponseCode() == 416) {            // fichier vide ou plage refusée
            c.disconnect();
            c = Net.open(u, requestHeaders(null));
        }
        if (c.getResponseCode() >= 400) {
            int code = c.getResponseCode();
            String msg = c.getResponseMessage();
            c.disconnect();
            throw new IOException("HTTP " + code + " " + (msg == null ? "" : msg));
        }
        return c;
    }

    private static String fingerprint(HttpURLConnection c) {
        String e = c.getHeaderField("ETag");
        return e != null ? e : c.getHeaderField("Last-Modified");
    }

    private static long totalFromContentRange(HttpURLConnection c) {
        String cr = c.getHeaderField("Content-Range");
        if (cr == null) return -1;
        Matcher m = Pattern.compile("/(\\d+)\\s*$").matcher(cr);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /** Sonde le serveur (lève une exception si le lien ne fonctionne pas). */
    public void probe() throws IOException {
        HttpURLConnection c = ask(url);
        try {
            url = c.getURL().toString();
            etag = fingerprint(c);
            if (c.getResponseCode() == 206) {
                total = totalFromContentRange(c);
                resumable = total > 0;
            } else {
                total = c.getContentLengthLong();
                resumable = false;
            }
            String name = forcedName != null ? forcedName : Net.filenameFromHeaders(c.getHeaderField("Content-Disposition"), url);
            // Une page web n'est pas un fichier : on refuse plutôt que d'enregistrer du HTML
            String ctype = String.valueOf(c.getContentType()).toLowerCase(java.util.Locale.ROOT);
            String cd = String.valueOf(c.getHeaderField("Content-Disposition")).toLowerCase(java.util.Locale.ROOT);
            if (ctype.startsWith("text/html") && !cd.contains("attachment") && forcedName == null) {
                throw new IOException("ce lien mène à une page web, pas à un fichier : ouvrez-la dans le navigateur vidéo");
            }
            filename = Net.safeName(name);
            probed = true;
        } finally {
            c.disconnect();
        }
    }

    /** Reprise : renouvelle le lien (les liens signés expirent) et vérifie que le fichier n'a pas changé. */
    private void refresh() throws IOException {
        long newTotal;
        String fp, finalUrl;
        try {
            HttpURLConnection c = ask(sourceUrl);
            try {
                finalUrl = c.getURL().toString();
                fp = fingerprint(c);
                newTotal = c.getResponseCode() == 206 ? totalFromContentRange(c) : -1;
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            probed = true;                     // lien d'origine mort : on tente l'ancien lien tel quel
            return;
        }
        if (newTotal != total || (fp != null && etag != null && !fp.equals(etag))) {
            cleanupFiles(false);
            path = null;
            synchronized (segments) { segments.clear(); }
            total = -1;
            resumable = false;
            url = sourceUrl;
            probe();
            prepare();
            note = "Le fichier a changé sur le serveur : téléchargement recommencé";
            return;
        }
        url = finalUrl;
        probed = true;
    }

    private void prepare() throws IOException {
        if (!destDir.mkdirs() && !destDir.isDirectory()) throw new IOException("dossier inaccessible : " + destDir);
        File wanted = new File(destDir, filename);
        if (resumable && loadState(wanted)) return;
        Net.checkFreeSpace(destDir, total);
        path = Net.claim(wanted, this);
        filename = path.getName();
        if (!resumable) return;
        RandomAccessFile f = new RandomAccessFile(part(), "rw");
        try {
            f.setLength(total);                    // fichier pré-alloué : chaque connexion écrit à sa place
        } finally {
            f.close();
        }
        int n = (int) Math.max(1, Math.min(connections, total / MIN_SPLIT));
        long size = total / n;
        synchronized (segments) {
            segments.clear();
            for (int i = 0; i < n; i++) segments.add(new Segment(i * size, i < n - 1 ? (i + 1) * size - 1 : total - 1, i * size));
        }
        saveState();
    }

    private boolean loadState(File p) {
        File sf = new File(p.getPath() + ".part.json");
        if (!sf.exists() || !new File(p.getPath() + ".part").exists()) return false;
        try {
            Map<String, Object> st = Json.obj(Json.parse(readText(sf)));
            String u = Json.str(st, "url");
            if (!(url.equals(u) || sourceUrl.equals(u)) || Json.num(st, "total", -2) != total) return false;
            if (!Net.claimExisting(p, this)) return false;     // utilisé par un autre téléchargement en cours
            List<Segment> list = new ArrayList<Segment>();
            for (Object o : Json.arr(st.get("segments"))) {
                Map<String, Object> d = Json.obj(o);
                list.add(new Segment(Json.num(d, "start", 0), Json.num(d, "end", 0), Json.num(d, "pos", 0)));
            }
            synchronized (segments) {
                segments.clear();
                segments.addAll(list);
            }
            path = p;
            return true;
        } catch (RuntimeException | IOException e) {
            return false;
        }
    }

    static String readText(File f) throws IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            return new String(Net.readAll(in, 64 * 1024 * 1024), "UTF-8");
        } finally {
            in.close();
        }
    }

    static void writeTextAtomic(File f, String text) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
        try {
            w.write(text);
        } finally {
            w.close();
        }
        if (!tmp.renameTo(f)) {
            if (!f.delete() || !tmp.renameTo(f)) throw new IOException("écriture impossible : " + f);
        }
    }

    private void saveState() throws IOException {
        if (!resumable || path == null) return;
        // Les octets doivent être réellement écrits sur le stockage AVANT d'enregistrer qu'ils sont reçus :
        // sinon une batterie vide laisserait des trous dans un fichier « terminé ».
        java.nio.channels.FileChannel ch = channel;
        if (ch != null && ch.isOpen()) {
            try {
                ch.force(false);
            } catch (IOException ignored) {
                // la sauvegarde suivante réessaiera
            }
        }
        Map<String, Object> st = new LinkedHashMap<String, Object>();
        st.put("url", url);
        st.put("total", total);
        List<Object> list = new ArrayList<Object>();
        synchronized (segments) {
            for (Segment s : segments) {
                Map<String, Object> d = new LinkedHashMap<String, Object>();
                d.put("start", s.start);
                d.put("end", s.end);
                d.put("pos", s.pos);
                list.add(d);
            }
        }
        st.put("segments", list);
        writeTextAtomic(stateFile(), Json.write(st));
    }

    // ------------------------------------------------------------------ plusieurs connexions
    private boolean runSegmented() throws Exception {
        RandomAccessFile raf = new RandomAccessFile(part(), "rw");
        channel = raf.getChannel();
        try {
            return runWorkers();
        } finally {
            try {
                channel.force(false);
            } catch (IOException ignored) {
                // rien
            }
            channel = null;
            raf.close();
        }
    }

    private boolean runWorkers() throws Exception {
        List<Thread> workers = new ArrayList<Thread>();
        for (int i = 0; i < connections; i++) {
            Thread t = new Thread(new Runnable() {
                @Override public void run() { worker(); }
            }, "varika-" + id + "-" + i);
            t.setDaemon(true);
            workers.add(t);
            t.start();
        }
        long lastT = System.currentTimeMillis(), lastB = downloaded(), lastSave = lastT;
        while (anyAlive(workers)) {
            Thread.sleep(500);
            long now = System.currentTimeMillis(), b = downloaded();
            double inst = (b - lastB) * 1000.0 / Math.max(1, now - lastT);
            speed = speed == 0 ? inst : 0.7 * speed + 0.3 * inst;
            lastT = now;
            lastB = b;
            if (now - lastSave > 3000) {
                saveState();
                lastSave = now;
            }
        }
        for (Thread t : workers) t.join();
        if (fatal != null) throw new IOException(fatal);
        synchronized (segments) {
            for (Segment s : segments) if (!s.done()) return false;
        }
        return true;
    }

    private static boolean anyAlive(List<Thread> ts) {
        for (Thread t : ts) if (t.isAlive()) return true;
        return false;
    }

    private void worker() {
        while (!stop) {
            Segment seg = acquire();
            if (seg == null) return;
            try {
                fetch(seg);
            } finally {
                synchronized (segments) { seg.busy = false; }
            }
        }
    }

    private Segment acquire() {
        synchronized (segments) {
            for (Segment s : segments) {
                if (!s.busy && !s.done()) {
                    s.busy = true;
                    return s;
                }
            }
            // segmentation dynamique : couper en deux le plus gros segment en cours
            Segment big = null;
            for (Segment s : segments) {
                if (s.busy && !s.done() && s.remaining() >= 2 * MIN_SPLIT && (big == null || s.remaining() > big.remaining())) big = s;
            }
            if (big == null) return null;
            long mid = big.pos + big.remaining() / 2;
            Segment n = new Segment(mid, big.end, mid);
            n.busy = true;
            big.end = mid - 1;
            segments.add(n);
            return n;
        }
    }

    private void fetch(Segment seg) {
        int retries = 0;
        byte[] buf = new byte[CHUNK];
        while (!stop && !seg.done()) {
            HttpURLConnection c = null;
            try {
                long from, to;
                synchronized (segments) { from = seg.pos; to = seg.end; }
                Map<String, String> range = new LinkedHashMap<String, String>();
                range.put("Range", "bytes=" + from + "-" + to);
                c = Net.open(url, requestHeaders(range));
                int code = c.getResponseCode();
                if (code >= 400) {
                    if (code == 408 || code == 429 || code >= 500) throw new IOException("HTTP " + code);
                    fail("HTTP " + code + " " + c.getResponseMessage());
                    return;
                }
                if (code != 206) {
                    if (++notRanged >= 3) {
                        fail("le serveur ne permet plus le téléchargement par morceaux");
                        return;
                    }
                    throw new IOException("le serveur a ignoré la plage demandée");
                }
                Matcher m = CONTENT_RANGE.matcher(String.valueOf(c.getHeaderField("Content-Range")));
                if (m.find()) {
                    if (!"*".equals(m.group(2)) && Long.parseLong(m.group(2)) != total) {
                        fail("le fichier a changé sur le serveur pendant le téléchargement : relancez-le");
                        return;
                    }
                    if (Long.parseLong(m.group(1)) != from) throw new IOException("le serveur a renvoyé une autre plage");
                }
                InputStream in = c.getInputStream();
                java.nio.channels.FileChannel ch = channel;
                synchronized (this) { active++; }
                try {
                    while (!stop) {
                        int want;
                        synchronized (segments) { want = (int) Math.min(CHUNK, seg.end - seg.pos + 1); }
                        if (want <= 0) break;            // segment fini (ou raccourci par un vol)
                        int n = in.read(buf, 0, want);
                        if (n < 0) throw new IOException("connexion fermée trop tôt");
                        long off;
                        synchronized (segments) {
                            n = (int) Math.min(n, Math.max(0, seg.end - seg.pos + 1));
                            off = seg.pos;
                            seg.pos += n;
                        }
                        try {
                            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buf, 0, n);
                            long p = off;
                            while (bb.hasRemaining()) p += ch.write(bb, p);
                        } catch (IOException e) {
                            synchronized (segments) { seg.pos = off; }   // annule la réservation
                            if (String.valueOf(e.getMessage()).contains("ENOSPC") || String.valueOf(e.getMessage()).toLowerCase().contains("no space")) {
                                fail("mémoire du téléphone pleine : libérez de la place puis reprenez");
                                return;
                            }
                            throw e;
                        }
                        retries = 0;
                    }
                } finally {
                    synchronized (this) { active--; }
                }
            } catch (IOException | RuntimeException e) {
                retries++;
                if (Net.isNetworkError(e) && retries > netRetries) {
                    waitingWhy = Net.message(e);           // coupure durable : on attendra le réseau
                    stop = true;
                    return;
                }
                if (retries > MAX_RETRIES) {
                    fail("abandon après " + MAX_RETRIES + " essais : " + Net.message(e));
                    return;
                }
                sleepUnlessStopped(Math.min(30000, 1000L << retries));
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }

    private void fail(String why) {
        fatal = why;
        stop = true;
    }

    // ------------------------------------------------------------------ une seule connexion
    private boolean runSingle() throws IOException {
        streamBytes = 0;
        HttpURLConnection c = Net.open(url, requestHeaders(null));
        FileOutputStream out = null;
        try {
            if (c.getResponseCode() >= 400) throw new IOException("HTTP " + c.getResponseCode() + " " + c.getResponseMessage());
            InputStream in = c.getInputStream();
            out = new FileOutputStream(part());
            active = 1;
            byte[] buf = new byte[CHUNK];
            long lastT = System.currentTimeMillis(), lastB = 0;
            int n;
            while (!stop && (n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                streamBytes += n;
                long now = System.currentTimeMillis();
                if (now - lastT >= 500) {
                    double inst = (streamBytes - lastB) * 1000.0 / (now - lastT);
                    speed = speed == 0 ? inst : 0.7 * speed + 0.3 * inst;
                    lastT = now;
                    lastB = streamBytes;
                }
            }
        } finally {
            if (out != null) out.close();
            c.disconnect();
        }
        if (stop) return false;
        if (total <= 0) total = streamBytes;
        if (streamBytes != total) {
            throw new IOException("connexion interrompue (" + Net.humanSize(streamBytes) + " reçus sur " + Net.humanSize(total) + ") : réessayez");
        }
        return true;
    }

    private void finish() throws IOException {
        File target = path;
        if (target.exists()) target = Net.claim(target, this);   // un fichier de ce nom est apparu entre-temps
        if (!part().renameTo(target)) throw new IOException("impossible de renommer le fichier terminé");
        File sf = stateFile();
        if (sf.exists() && !sf.delete()) sf.deleteOnExit();
        Net.release(path);
        Net.release(target);
        path = target;
        filename = target.getName();
        status = COMPLETED;
    }

    @Override public void cleanupFiles(boolean deleteFinal) {
        if (path == null) return;
        List<File> fs = new ArrayList<File>();
        Collections.addAll(fs, part(), stateFile());
        if (deleteFinal) fs.add(path);
        for (File f : fs) if (f.isFile() && !f.delete()) f.deleteOnExit();
        Net.release(path);
    }

    // ------------------------------------------------------------------ sauvegarde de la liste
    @Override public Map<String, Object> toRecord() {
        Map<String, Object> r = super.toRecord();
        r.put("resumable", resumable);
        r.put("etag", etag);
        return r;
    }

    @Override protected void restore(Map<String, Object> r) {
        restoreCommon(r);
        resumable = Json.bool(r, "resumable", false);
        etag = Json.str(r, "etag");
        if (COMPLETED.equals(status)) return;
        if (path != null && resumable && loadState(path)) return;      // reprise là où on s'était arrêté
        path = null;                                                    // sinon : on recommencera
        total = -1;
        resumable = false;
        url = sourceUrl;
    }
}
