package com.varikaagency.download.core;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Vidéo dont l'image et le son sont dans deux fichiers séparés (Facebook, Instagram…) :
 * on télécharge les deux puis on les réunit en un .mp4 (MediaMuxer sur Android).
 * Même logique que dash.py sur Windows.
 */
public class DashTask extends Task {
    public final String audioUrl;
    private FileTask video, audio;

    public DashTask(String id, String url, String audioUrl, File destDir, int connections) {
        super(id, url, destDir, connections);
        this.audioUrl = audioUrl;
    }

    @Override public String kind() { return "dash"; }

    @Override public boolean resumable() { return true; }

    @Override public String variant() { return "image + son"; }

    File parts() { return new File(path != null ? path.getPath() + ".parts" : new File(destDir, ".varika-parts").getPath()); }

    private List<FileTask> tracks() {
        List<FileTask> l = new ArrayList<FileTask>();
        if (video != null) l.add(video);
        if (audio != null) l.add(audio);
        return l;
    }

    private void makeTracks() {
        int n = Math.max(2, connections / 2);
        video = new FileTask(id + "v", url, parts(), Math.max(2, connections - n));
        audio = new FileTask(id + "a", audioUrl, parts(), n);
        video.forcedName = "video.mp4";
        audio.forcedName = "audio.m4a";
        video.headers.putAll(headers);
        audio.headers.putAll(headers);
    }

    public void probe() throws IOException {
        makeTracks();
        video.probe();
        audio.probe();
        String base = forcedName != null ? forcedName.replaceAll("\\.[A-Za-z0-9]{2,4}$", "")
                : "video-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(new java.util.Date());
        filename = Net.safeName(base + ".mp4");
        probed = true;
    }

    @Override public long downloaded() {
        long n = 0;
        for (FileTask t : tracks()) n += COMPLETED.equals(t.status) ? Math.max(0, t.total) : t.downloaded();
        return n;
    }

    private long totalBytes() {
        long n = 0;
        for (FileTask t : tracks()) {
            if (t.total <= 0) return -1;
            n += t.total;
        }
        return tracks().isEmpty() ? -1 : n;
    }

    @Override public double progress() {
        if (COMPLETED.equals(status)) return 1;
        long tot = totalBytes();
        return tot > 0 ? Math.min(1.0, downloaded() / (double) tot) : 0;
    }

    @Override public List<double[]> uiSegments() {
        List<double[]> out = new ArrayList<double[]>();
        long tot = totalBytes();
        if (tot <= 0) return out;
        double start = 0;
        for (FileTask t : tracks()) {
            double share = t.total / (double) tot;
            double done = COMPLETED.equals(t.status) ? 1 : t.progress();
            out.add(new double[]{start, start + share, start + share * done});
            start += share;
        }
        return out;
    }

    @Override protected void run() {
        try {
            if (video == null) {
                status = PROBING;
                probe();
            }
            if (path == null) {
                if (!destDir.mkdirs() && !destDir.isDirectory()) throw new IOException("dossier inaccessible : " + destDir);
                path = Net.claim(new File(destDir, filename), this);
                filename = path.getName();
                makeTracksKeepingProbe();
            }
            if (!parts().mkdirs() && !parts().isDirectory()) throw new IOException("dossier temporaire impossible");
            status = DOWNLOADING;
            for (FileTask t : tracks()) {
                if (!COMPLETED.equals(t.status)) {
                    if (PAUSED.equals(t.status) || ERROR.equals(t.status) || WAITING.equals(t.status)) t.status = QUEUED;
                    t.start();
                }
            }
            while (true) {                                   // relaie la vitesse et la pause
                boolean running = false;
                double sp = 0;
                int act = 0;
                for (FileTask t : tracks()) {
                    running |= t.isRunning();
                    sp += t.speed;
                    act += t.active;
                    if (stop) {
                        if (userPaused) t.pause();
                        else t.suspend(waitReason);
                    }
                }
                speed = sp;
                active = act;
                total = totalBytes();
                if (!running) break;
                Thread.sleep(300);
            }
            for (FileTask t : tracks()) {
                if (WAITING.equals(t.status) && !userPaused) throw new java.net.SocketException(t.error);
                if (ERROR.equals(t.status)) throw new IOException((t == video ? "l'image : " : "le son : ") + t.error);
            }
            if (COMPLETED.equals(video.status) && COMPLETED.equals(audio.status)) {
                merge();
                status = COMPLETED;
            } else {
                status = PAUSED;
            }
        } catch (Exception e) {
            failed(e);
        } finally {
            speed = 0;
            active = 0;
            if (COMPLETED.equals(status) || ERROR.equals(status)) Net.release(path);
        }
    }

    /** Une fois le nom final choisi, les pistes vont dans le dossier de travail de ce téléchargement. */
    private void makeTracksKeepingProbe() throws IOException {
        FileTask pv = video, pa = audio;
        makeTracks();
        video.forcedName = "video.mp4";
        audio.forcedName = "audio.m4a";
        // le sondage a déjà validé les liens ; on garde les adresses finales (après redirections)
        video.url = pv.url;
        audio.url = pa.url;
    }

    private void merge() throws IOException {
        status = ASSEMBLING;
        if (path.exists()) {
            path = Net.claim(path, this);
            filename = path.getName();
        }
        HlsTask.Remuxer r = HlsTask.remuxer;
        if (r != null) {
            File out = r.remux(video.path, audio.path, path);
            if (out != null && out.isFile()) {
                path = out;
                filename = out.getName();
                HlsTask.deleteTree(parts());
                return;
            }
        }
        // sans conversion possible : la vidéo (muette) et le son, côte à côte
        if (!video.path.renameTo(path)) throw new IOException("impossible de créer le fichier final");
        File son = Net.claim(new File(path.getPath().replaceAll("\\.[^./\\\\]+$", "") + ".son.m4a"), this);
        if (audio.path.renameTo(son)) note = "image et son enregistrés séparément (le son est à côté de la vidéo)";
        Net.release(son);
        HlsTask.deleteTree(parts());
    }

    @Override public void pause() {
        super.pause();
        for (FileTask t : tracks()) t.pause();
    }

    @Override public void cleanupFiles(boolean deleteFinal) {
        if (path == null) return;
        HlsTask.deleteTree(parts());
        if (deleteFinal && path.isFile() && !path.delete()) path.deleteOnExit();
        Net.release(path);
    }

    @Override public Map<String, Object> toRecord() {
        Map<String, Object> r = super.toRecord();
        r.put("audio_url", audioUrl);
        r.put("video", video == null ? null : video.toRecord());
        r.put("audio", audio == null ? null : audio.toRecord());
        return r;
    }

    @Override protected void restore(Map<String, Object> r) {
        restoreCommon(r);
        if (COMPLETED.equals(status)) return;
        if (r.get("video") instanceof Map && r.get("audio") instanceof Map) {
            video = (FileTask) Task.fromRecord(Json.obj(r.get("video")));
            audio = (FileTask) Task.fromRecord(Json.obj(r.get("audio")));
            probed = true;
        }
    }
}
