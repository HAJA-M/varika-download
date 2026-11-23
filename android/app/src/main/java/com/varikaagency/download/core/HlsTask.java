package com.varikaagency.download.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Vidéos en streaming HLS (.m3u8) : même logique que hls.py, sans ffmpeg. */
public class HlsTask extends Task {
    static final int MAX_SEGMENT = 256 * 1024 * 1024;
    private static final Pattern ATTR = Pattern.compile("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)");

    /** Optionnel (Android) : réassemble les .ts en .mp4 avec MediaMuxer. */
    public interface Remuxer {
        /** @return le fichier .mp4 créé, ou null si impossible */
        File remux(File video, File audio, File wantedMp4);
    }

    public static volatile Remuxer remuxer;

    static final class Seg {
        String url, keyUri, keyIv, range;
        long seq;
    }

    static final class Track {
        final List<Seg> segs = new ArrayList<Seg>();
        String initUrl, initRange;
    }

    public final Integer quality;
    private String variant;
    private final Map<String, Track> tracks = new LinkedHashMap<String, Track>();
    private final Map<String, Long> done = new ConcurrentHashMap<String, Long>();
    private final Map<String, byte[]> keys = new HashMap<String, byte[]>();
    private volatile long bytes;

    public HlsTask(String id, String url, File destDir, int connections, Integer quality) {
        super(id, url, destDir, connections);
        this.quality = quality;
    }

    @Override public String kind() { return "hls"; }

    @Override public boolean resumable() { return true; }

    @Override public String variant() { return variant; }

    File parts() { return new File(path.getPath() + ".parts"); }

    private int count() {
        int n = 0;
        for (Track t : tracks.values()) n += t.segs.size();
        return n;
    }

    @Override public long downloaded() { return bytes; }

    @Override public double progress() {
        if (COMPLETED.equals(status)) return 1;
        int n = count();
        return n == 0 ? 0 : done.size() / (double) n;
    }

    @Override public Double eta() {
        int n = count(), d = done.size();
        if (n == 0 || d == 0 || speed < 1) return null;
        return (bytes / (double) d) * (n - d) / speed;
    }

    @Override public List<double[]> uiSegments() {
        List<double[]> out = new ArrayList<double[]>();
        Track v = tracks.get("v");
        if (v == null || v.segs.isEmpty()) return out;
        int n = v.segs.size(), blocks = Math.min(48, n);
        for (int b = 0; b < blocks; b++) {
            int s = b * n / blocks, e = (b + 1) * n / blocks, d = 0;
            for (int i = s; i < e; i++) if (done.containsKey("v" + i)) d++;
            out.add(new double[]{s / (double) n, e / (double) n, s / (double) n + (d / (double) Math.max(1, e - s)) * (e - s) / n});
        }
        return out;
    }

    // ------------------------------------------------------------------ playlists
    static Map<String, String> attrs(String s) {
        Map<String, String> m = new HashMap<String, String>();
        Matcher x = ATTR.matcher(s);
        while (x.find()) m.put(x.group(1), x.group(2).replace("\"", ""));
        return m;
    }

    private String fetchText(String u, String[] finalUrl) throws IOException {
        HttpURLConnection c = Net.open(u, requestHeadersFor(u, null));
        try {
            if (c.getResponseCode() >= 400) throw new IOException("HTTP " + c.getResponseCode() + " " + c.getResponseMessage());
            finalUrl[0] = c.getURL().toString();
            return new String(Net.readAll(c.getInputStream(), 8 * 1024 * 1024), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** Les cookies ne partent que vers le site de la playlist. */
    private Map<String, String> requestHeadersFor(String u, Map<String, String> extra) {
        Map<String, String> h = requestHeaders(extra);
        if (!Net.host(u).equalsIgnoreCase(Net.host(url))) {
            h.remove("Cookie");
            h.remove("Authorization");
        }
        return h;
    }

    static String resolve(String base, String rel) throws IOException {
        return new URL(new URL(base), rel.trim()).toString();
    }

    public void probe() throws IOException {
        String[] fin = new String[1];
        String text = fetchText(url, fin);
        List<String> lines = lines(text);
        String audioUrl = null;
        if (text.contains("#EXT-X-STREAM-INF")) {
            List<String[]> variants = new ArrayList<String[]>();    // {url, height, bandwidth, audioGroup, resolution}
            Map<String, String> audio = new HashMap<String, String>();
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.startsWith("#EXT-X-STREAM-INF")) {
                    Map<String, String> a = attrs(l.substring(l.indexOf(':') + 1));
                    String uri = null;
                    for (int j = i + 1; j < lines.size(); j++) if (!lines.get(j).startsWith("#")) { uri = lines.get(j); break; }
                    if (uri == null) continue;
                    String res = a.containsKey("RESOLUTION") ? a.get("RESOLUTION") : "";
                    int h = res.contains("x") ? Integer.parseInt(res.split("x")[1].trim()) : 0;
                    variants.add(new String[]{resolve(fin[0], uri), String.valueOf(h), a.containsKey("BANDWIDTH") ? a.get("BANDWIDTH") : "0", a.get("AUDIO"), res});
                } else if (l.startsWith("#EXT-X-MEDIA") && l.contains("TYPE=AUDIO")) {
                    Map<String, String> a = attrs(l.substring(l.indexOf(':') + 1));
                    if (a.containsKey("URI") && (!audio.containsKey(a.get("GROUP-ID")) || "YES".equals(a.get("DEFAULT")))) {
                        audio.put(a.get("GROUP-ID"), resolve(fin[0], a.get("URI")));
                    }
                }
            }
            if (variants.isEmpty()) throw new IOException("aucune qualité trouvée dans la playlist");
            Collections.sort(variants, new Comparator<String[]>() {
                @Override public int compare(String[] x, String[] y) {
                    int c = Integer.compare(Integer.parseInt(x[1]), Integer.parseInt(y[1]));
                    return c != 0 ? c : Long.compare(Long.parseLong(x[2]), Long.parseLong(y[2]));
                }
            });
            String[] chosen = variants.get(variants.size() - 1);
            if (quality != null) {
                chosen = variants.get(0);
                for (String[] v : variants) if (Integer.parseInt(v[1]) > 0 && Integer.parseInt(v[1]) <= quality) chosen = v;
            }
            variant = chosen[4].isEmpty() ? (Long.parseLong(chosen[2]) / 1000) + " kb/s" : chosen[4];
            audioUrl = chosen[3] != null ? audio.get(chosen[3]) : null;
            text = fetchText(chosen[0], fin);
            if (text.contains("#EXT-X-STREAM-INF")) throw new IOException("playlist imbriquée non prise en charge");
        }
        tracks.clear();
        tracks.put("v", parseMedia(text, fin[0]));
        if (audioUrl != null) tracks.put("a", parseMedia(fetchText(audioUrl, fin), fin[0]));
        if (filename == null) {
            String base;
            if (forcedName != null) {
                base = forcedName.replaceAll("\\.[A-Za-z0-9]{2,4}$", "");
            } else {
                String p = new URL(url).getPath();
                base = p.substring(p.lastIndexOf('/') + 1).replaceAll("\\.m3u8?$", "");
                if (base.isEmpty() || base.matches("(?i)index|master|playlist|manifest|video|chunklist")) {
                    base = "video-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(new java.util.Date());
                }
            }
            boolean fmp4 = tracks.get("v").initUrl != null;
            filename = Net.safeName(base + (fmp4 || remuxer != null ? ".mp4" : ".ts"));
        }
        probed = true;
    }

    static List<String> lines(String text) {
        List<String> out = new ArrayList<String>();
        for (String l : text.replace("﻿", "").split("\\r?\\n")) if (!l.trim().isEmpty()) out.add(l.trim());
        if (out.isEmpty() || !out.get(0).startsWith("#EXTM3U")) throw new IllegalArgumentException("ce n'est pas une playlist HLS");
        return out;
    }

    Track parseMedia(String text, String base) throws IOException {
        List<String> ls = lines(text);
        Track t = new Track();
        long seq = 0, nextOffset = 0;
        String keyUri = null, keyIv = null, pendingRange = null;
        boolean ended = false;
        for (String l : ls) {
            if (l.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                seq = Long.parseLong(l.substring(22).trim());
            } else if (l.startsWith("#EXT-X-ENDLIST") || l.startsWith("#EXT-X-PLAYLIST-TYPE:VOD")) {
                ended = true;
            } else if (l.startsWith("#EXT-X-KEY:")) {
                Map<String, String> a = attrs(l.substring(11));
                String method = a.containsKey("METHOD") ? a.get("METHOD") : "NONE";
                String fmt = a.containsKey("KEYFORMAT") ? a.get("KEYFORMAT") : "identity";
                if ("NONE".equals(method)) {
                    keyUri = null;
                } else if ("AES-128".equals(method) && "identity".equals(fmt)) {
                    keyUri = resolve(base, a.get("URI"));
                    keyIv = a.get("IV");
                } else {
                    throw new IOException("vidéo protégée par DRM : téléchargement impossible");
                }
            } else if (l.startsWith("#EXT-X-MAP:")) {
                Map<String, String> a = attrs(l.substring(11));
                t.initUrl = resolve(base, a.get("URI"));
                if (a.containsKey("BYTERANGE")) t.initRange = range(a.get("BYTERANGE"), 0)[0];
            } else if (l.startsWith("#EXT-X-BYTERANGE:")) {
                pendingRange = l.substring(17);
            } else if (!l.startsWith("#")) {
                Seg s = new Seg();
                s.url = resolve(base, l);
                s.keyUri = keyUri;
                s.keyIv = keyIv;
                s.seq = seq + t.segs.size();
                if (pendingRange != null) {
                    String[] r = range(pendingRange, nextOffset);
                    s.range = r[0];
                    nextOffset = Long.parseLong(r[1]);
                    pendingRange = null;
                }
                t.segs.add(s);
            }
        }
        if (t.segs.isEmpty()) throw new IOException("playlist vide");
        if (!ended) throw new IOException("diffusion en direct : non prise en charge (seules les vidéos complètes se téléchargent)");
        return t;
    }

    static String[] range(String spec, long next) {
        String[] p = spec.split("@");
        long len = Long.parseLong(p[0].trim());
        long start = p.length > 1 ? Long.parseLong(p[1].trim()) : next;
        long end = start + len - 1;
        return new String[]{"bytes=" + start + "-" + end, String.valueOf(end + 1)};
    }

    // ------------------------------------------------------------------ déroulement
    @Override protected void run() {
        try {
            if (!probed) {
                status = PROBING;
                probe();
            }
            if (path == null) {
                if (!destDir.isDirectory() && !destDir.mkdirs()) throw new IOException("dossier inaccessible : " + destDir);
                path = Net.claim(new File(destDir, filename), this);
                filename = path.getName();
            }
            if (!parts().isDirectory() && !parts().mkdirs()) throw new IOException("dossier temporaire impossible");
            scanDone();
            if (total > 0 && !done.isEmpty()) Net.checkFreeSpace(destDir, (total - bytes) * 2);
            status = DOWNLOADING;
            runWorkers();
            if (fatal != null) throw new IOException(fatal);
            if (done.size() == count()) {
                assemble();
                status = COMPLETED;
            } else {
                status = PAUSED;
            }
        } catch (Exception e) {
            error = Net.message(e);
            status = ERROR;
        } finally {
            speed = 0;
            active = 0;
            probed = false;                  // au prochain départ, on relit la playlist (liens expirés)
            if (COMPLETED.equals(status) || ERROR.equals(status)) Net.release(path);
        }
    }

    private File segFile(String track, int i) {
        return new File(parts(), track + String.format(java.util.Locale.ROOT, "%06d", i) + ".seg");
    }

    private void scanDone() {
        done.clear();
        long b = 0;
        for (Map.Entry<String, Track> e : tracks.entrySet()) {
            for (int i = 0; i < e.getValue().segs.size(); i++) {
                File f = segFile(e.getKey(), i);
                if (f.isFile()) {
                    done.put(e.getKey() + i, f.length());
                    b += f.length();
                }
            }
        }
        bytes = b;
        estimate();
    }

    private void estimate() {
        int n = count(), d = done.size();
        if (d > 0) total = (long) (bytes / (double) d * n);
    }

    private void runWorkers() throws InterruptedException {
        final List<String[]> todo = new ArrayList<String[]>();
        for (Map.Entry<String, Track> e : tracks.entrySet()) {
            for (int i = 0; i < e.getValue().segs.size(); i++) if (!done.containsKey(e.getKey() + i)) todo.add(new String[]{e.getKey(), String.valueOf(i)});
        }
        final AtomicInteger next = new AtomicInteger();
        List<Thread> ws = new ArrayList<Thread>();
        for (int w = 0; w < connections; w++) {
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    while (!stop) {
                        int k = next.getAndIncrement();
                        if (k >= todo.size()) return;
                        fetchSegment(todo.get(k)[0], Integer.parseInt(todo.get(k)[1]));
                    }
                }
            });
            t.setDaemon(true);
            ws.add(t);
            t.start();
        }
        long lastT = System.currentTimeMillis(), lastB = bytes;
        boolean alive = true;
        while (alive) {
            Thread.sleep(500);
            alive = false;
            for (Thread t : ws) alive |= t.isAlive();
            long now = System.currentTimeMillis(), b = bytes;
            double inst = (b - lastB) * 1000.0 / Math.max(1, now - lastT);
            speed = speed == 0 ? inst : 0.7 * speed + 0.3 * inst;
            lastT = now;
            lastB = b;
        }
    }

    private byte[] key(String uri) throws IOException {
        synchronized (keys) {
            if (keys.containsKey(uri)) return keys.get(uri);
        }
        HttpURLConnection c = Net.open(uri, requestHeadersFor(uri, null));
        try {
            if (c.getResponseCode() >= 400) throw new IOException("clé de chiffrement : HTTP " + c.getResponseCode());
            byte[] k = Net.readAll(c.getInputStream(), 64);
            if (k.length != 16) throw new IOException("vidéo protégée par DRM : téléchargement impossible");
            synchronized (keys) { keys.put(uri, k); }
            return k;
        } finally {
            c.disconnect();
        }
    }

    private byte[] decrypt(byte[] data, Seg s) throws Exception {
        byte[] iv = new byte[16];
        if (s.keyIv != null) {
            String hex = s.keyIv.replaceFirst("^0[xX]", "");
            while (hex.length() < 32) hex = "0" + hex;
            for (int i = 0; i < 16; i++) iv[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        } else {
            for (int i = 0; i < 8; i++) iv[15 - i] = (byte) (s.seq >>> (8 * i));
        }
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(s.keyUri), "AES"), new IvParameterSpec(iv));
        byte[] out = c.doFinal(data.length % 16 == 0 ? data : java.util.Arrays.copyOf(data, data.length - data.length % 16));
        int pad = out.length > 0 ? out[out.length - 1] & 0xff : 0;
        if (pad >= 1 && pad <= 16 && pad <= out.length) {
            boolean ok = true;
            for (int i = out.length - pad; i < out.length; i++) ok &= (out[i] & 0xff) == pad;
            if (ok) return java.util.Arrays.copyOf(out, out.length - pad);
        }
        return out;
    }

    private void fetchSegment(String track, int i) {
        Seg s = tracks.get(track).segs.get(i);
        int retries = 0;
        while (!stop) {
            HttpURLConnection c = null;
            try {
                Map<String, String> extra = new LinkedHashMap<String, String>();
                if (s.range != null) extra.put("Range", s.range);
                synchronized (this) { active++; }
                byte[] data;
                try {
                    c = Net.open(s.url, requestHeadersFor(s.url, extra));
                    int code = c.getResponseCode();
                    if (code >= 400 && code != 408 && code != 429 && code < 500) {
                        fatal = "segment " + (i + 1) + " : HTTP " + code + " (lien expiré ? relancez depuis la page)";
                        stop = true;
                        return;
                    }
                    if (code >= 400) throw new IOException("HTTP " + code);
                    data = Net.readAll(c.getInputStream(), MAX_SEGMENT);
                } finally {
                    synchronized (this) { active--; }
                }
                if (s.keyUri != null) data = decrypt(data, s);
                File tmp = new File(segFile(track, i).getPath() + ".tmp");
                OutputStream o = new FileOutputStream(tmp);
                try {
                    o.write(data);
                } finally {
                    o.close();
                }
                if (!tmp.renameTo(segFile(track, i))) throw new IOException("écriture impossible");
                done.put(track + i, (long) data.length);
                synchronized (this) { bytes += data.length; }
                estimate();
                return;
            } catch (Exception e) {
                if (String.valueOf(e.getMessage()).contains("DRM")) {
                    fatal = e.getMessage();
                    stop = true;
                    return;
                }
                if (++retries > FileTask.MAX_RETRIES) {
                    fatal = "segment " + (i + 1) + " : abandon après " + FileTask.MAX_RETRIES + " essais (" + Net.message(e) + ")";
                    stop = true;
                    return;
                }
                sleepUnlessStopped(Math.min(30000, 1000L << retries));
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }

    // ------------------------------------------------------------------ assemblage
    private void concat(String track, File out) throws IOException {
        Track t = tracks.get(track);
        OutputStream o = new FileOutputStream(out);
        byte[] buf = new byte[1024 * 1024];
        try {
            if (t.initUrl != null) {
                Map<String, String> extra = new LinkedHashMap<String, String>();
                if (t.initRange != null) extra.put("Range", t.initRange);
                HttpURLConnection c = Net.open(t.initUrl, requestHeadersFor(t.initUrl, extra));
                try {
                    o.write(Net.readAll(c.getInputStream(), MAX_SEGMENT));
                } finally {
                    c.disconnect();
                }
            }
            for (int i = 0; i < t.segs.size(); i++) {
                InputStream in = new FileInputStream(segFile(track, i));
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                } finally {
                    in.close();
                }
            }
        } finally {
            o.close();
        }
    }

    private void assemble() throws IOException {
        status = ASSEMBLING;
        boolean vfmp4 = tracks.get("v").initUrl != null;
        File video = new File(parts(), "video" + (vfmp4 ? ".mp4" : ".ts"));
        concat("v", video);
        File audio = null;
        if (tracks.containsKey("a")) {
            audio = new File(parts(), "audio" + (tracks.get("a").initUrl != null ? ".mp4" : ".ts"));
            concat("a", audio);
        }
        if (path.exists()) {                 // un fichier de ce nom est apparu entre-temps : on ne l'écrase pas
            path = Net.claim(path, this);
            filename = path.getName();
        }
        Remuxer r = remuxer;
        if (r != null && (!vfmp4 || audio != null)) {
            File mp4 = r.remux(video, audio, new File(path.getPath().replaceAll("\\.[^./\\\\]+$", "") + ".mp4"));
            if (mp4 != null && mp4.isFile()) {
                path = mp4;
                filename = mp4.getName();
                deleteTree(parts());
                return;
            }
            note = "conversion en .mp4 impossible : fichier .ts conservé (lisible avec VLC)";
        }
        String base = path.getPath().replaceAll("\\.[^./\\\\]+$", "");
        File fin = new File(base + (vfmp4 ? ".mp4" : ".ts"));
        if (!fin.equals(path)) fin = Net.claim(fin, this);
        if (!video.renameTo(fin)) throw new IOException("impossible de créer le fichier final");
        if (audio != null) {
            File af = Net.claim(new File(base + ".audio" + (audio.getName().endsWith(".mp4") ? ".m4a" : ".ts")), this);
            if (audio.renameTo(af)) note = "piste audio séparée enregistrée à côté de la vidéo";
            Net.release(af);
        }
        Net.release(path);
        path = fin;
        filename = fin.getName();
        deleteTree(parts());
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        if (f.exists() && !f.delete()) f.deleteOnExit();
    }

    @Override public void cleanupFiles(boolean deleteFinal) {
        if (path == null) return;
        deleteTree(parts());
        if (deleteFinal && path.isFile() && !path.delete()) path.deleteOnExit();
        Net.release(path);
    }

    @Override public Map<String, Object> toRecord() {
        Map<String, Object> r = super.toRecord();
        r.put("quality", quality);
        r.put("variant", variant);
        return r;
    }

    @Override protected void restore(Map<String, Object> r) {
        restoreCommon(r);
        variant = Json.str(r, "variant");
    }

    static Integer qualityOf(Map<String, Object> r) {
        Object q = r.get("quality");
        return q instanceof Number ? ((Number) q).intValue() : null;
    }
}
