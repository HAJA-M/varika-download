import com.varikaagency.download.core.DashTask;
import com.varikaagency.download.core.FileTask;
import com.varikaagency.download.core.Mp4;
import com.varikaagency.download.core.HlsTask;
import com.varikaagency.download.core.Json;
import com.varikaagency.download.core.Manager;
import com.varikaagency.download.core.Task;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tests du moteur Android sur ordinateur (lancés par tests/test_android_core.py). */
public class CoreTest {
    static String B, H;          // serveur de fichiers (bugs) et serveur HLS
    static File OUT;
    static int failed;

    static void ok(String name, String detail) { System.out.println("OK   " + name + " " + detail); }

    static void fail(String name, String why) { failed++; System.out.println("FAIL " + name + " " + why); }

    static String sha(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new java.io.FileInputStream(f);
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) md.update(b, 0, n);
        in.close();
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static String get(String u) throws Exception {
        InputStream in = new URL(u).openStream();
        String s = new String(com.varikaagency.download.core.Net.readAll(in, 1 << 20), "UTF-8");
        in.close();
        return s;
    }

    static FileTask file(String path, int conns) {
        FileTask t = new FileTask("t" + System.nanoTime(), B + path, OUT, conns);
        t.start();
        t.await();
        return t;
    }

    static java.io.BufferedReader IN = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));

    /** Demande au banc de test (Python) de couper ou rétablir le serveur, et attend sa réponse. */
    static void ask(String what) throws Exception {
        System.out.println("CTRL " + what);
        System.out.flush();
        String r = IN.readLine();
        if (!"OK".equals(r)) throw new IllegalStateException("banc de test : " + r);
    }

    static void waitFor(java.util.concurrent.Callable<Boolean> c, int seconds, String what) throws Exception {
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            if (c.call()) return;
            Thread.sleep(100);
        }
        throw new IllegalStateException("délai dépassé : " + what);
    }

    public static void main(String[] a) throws Exception {
        B = a[0];
        H = a[1];
        OUT = new File(a[2]);
        final String G = a[3];               // serveur qui génère du contenu et que l'on peut « couper »
        Task.netRetries = 1;
        Manager.netCheckEveryMs = 500;

        // 0. coupures d'Internet
        final FileTask n = new FileTask("net", G + "/41943040.bin", new File(OUT, "reseau"), 4);
        ask("slow");
        n.start();
        waitFor(() -> n.progress() > 0.3, 30, "30 %");
        ask("down");
        waitFor(() -> Task.WAITING.equals(n.status) && !n.isRunning(), 30, "attente réseau");
        long kept = n.downloaded();
        String why = n.error;
        ask("up");
        n.status = Task.QUEUED;
        n.start();
        n.await();
        if (Task.COMPLETED.equals(n.status)) {
            ok("coupure réseau", "attente (" + why + ") puis reprise depuis " + (kept * 100 / 41943040) + " %");
            System.out.println("FILE reseau " + n.path.getPath());
        } else fail("coupure réseau", n.status + " " + n.error);

        File st0 = new File(OUT, "etat-reseau.json");
        final Manager mg = new Manager(st0, new File(OUT, "reseau-auto"));
        ask("slow");
        final String nid = (String) mg.add(G + "/41943041.bin", 4, null, null, false, null, null).get("id");
        waitFor(() -> mg.find(nid).progress() > 0.3, 30, "30 %");
        ask("down");
        waitFor(() -> Task.WAITING.equals(mg.find(nid).status), 30, "attente réseau");
        Thread.sleep(1500);
        boolean stillWaiting = Task.WAITING.equals(mg.find(nid).status);
        ask("up");
        waitFor(() -> Task.COMPLETED.equals(mg.find(nid).status), 60, "reprise automatique");
        if (stillWaiting) {
            ok("reprise automatique", "repart seul au retour du réseau");
            System.out.println("FILE reseau-auto " + mg.find(nid).path.getPath());
        } else fail("reprise automatique", "n'attendait pas le réseau");

        // Wi-Fi uniquement : sur données mobiles, attend le Wi-Fi ; reprend dès qu'il revient
        mg.saveSettings(Json.obj(Json.parse("{\"wifi_only\":true}")));
        mg.setNetwork(true, false);
        final String wid = (String) mg.add(G + "/1048576.bin", 4, null, null, false, null, null).get("id");
        waitFor(() -> Task.WAITING.equals(mg.find(wid).status), 10, "attente Wi-Fi");
        String wwhy = mg.find(wid).error;
        mg.setNetwork(true, true);
        waitFor(() -> Task.COMPLETED.equals(mg.find(wid).status), 30, "reprise en Wi-Fi");
        // choix « télécharger quand même sur données mobiles » pour un fichier précis
        mg.setNetwork(true, false);
        final String fid = (String) mg.add(G + "/1048577.bin", 4, null, null, false, null, null).get("id");
        mg.setWifiOnly(fid, false);
        waitFor(() -> Task.COMPLETED.equals(mg.find(fid).status), 30, "données mobiles autorisées");
        ok("Wi-Fi uniquement", "« " + wwhy + " » puis reprise en Wi-Fi ; exception par fichier respectée");
        mg.setNetwork(true, true);
        mg.saveSettings(Json.obj(Json.parse("{\"wifi_only\":false}")));
        mg.shutdown();
        String expected = get(B + "/control?sha=1").trim();

        // 1. plusieurs connexions
        FileTask t = file("/same", 8);
        if (Task.COMPLETED.equals(t.status) && sha(t.path).equals(expected)) ok("multi-connexions", t.filename);
        else fail("multi-connexions", t.status + " " + t.error);

        // 2. noms de fichiers
        t = file("/cd-utf8", 2);
        if ("Conférence été.pdf".equals(t.filename)) ok("nom UTF-8", t.filename); else fail("nom UTF-8", t.filename);
        t = file("/reserved", 2);
        if (!t.filename.toUpperCase().startsWith("CON.")) ok("nom réservé", t.filename); else fail("nom réservé", t.filename);

        // 3. deux téléchargements du même nom en même temps
        File same = new File(OUT, "meme");
        FileTask x = new FileTask("x", B + "/same", same, 4), y = new FileTask("y", B + "/same", same, 4);
        x.start(); y.start(); x.await(); y.await();
        if (Task.COMPLETED.equals(x.status) && Task.COMPLETED.equals(y.status) && !x.path.equals(y.path)
                && sha(x.path).equals(expected) && sha(y.path).equals(expected)) ok("même nom simultané", x.filename + " / " + y.filename);
        else fail("même nom simultané", x.status + " " + x.error + " | " + y.status + " " + y.error);

        // 4. pause, « redémarrage » (sauvegarde -> relecture), reprise
        get(B + "/control?slow=0.02");
        FileTask p = new FileTask("p", B + "/source", new File(OUT, "reprise"), 2);
        p.start();
        while (p.downloaded() == 0 && p.isRunning()) Thread.sleep(20);
        p.pause();
        p.await();
        get(B + "/control?slow=0&token=2");           // l'ancien lien signé expire
        Task p2 = Task.fromRecord(Json.obj(Json.parse(Json.write(p.toRecord()))));
        p2.start();
        p2.await();
        if (Task.PAUSED.equals("paused") && Task.COMPLETED.equals(p2.status) && sha(p2.path).equals(expected)) ok("reprise lien expiré", p2.filename);
        else fail("reprise lien expiré", p2.status + " " + p2.error);

        // 5. fichier modifié sur le serveur pendant la pause
        get(B + "/control?slow=0.02&changed=0");
        FileTask c = new FileTask("c", B + "/changing", new File(OUT, "change"), 2);
        c.start();
        while (c.downloaded() == 0 && c.isRunning()) Thread.sleep(20);
        c.pause();
        c.await();
        get(B + "/control?slow=0&changed=1");
        Task c2 = Task.fromRecord(Json.obj(Json.parse(Json.write(c.toRecord()))));
        c2.start();
        c2.await();
        String sha2 = get(B + "/control?sha2=1").trim();
        get(B + "/control?changed=0");
        if (Task.COMPLETED.equals(c2.status) && sha(c2.path).equals(sha2)) ok("fichier modifié", "recommencé : " + c2.note);
        else fail("fichier modifié", c2.status + " " + c2.error);

        // 6. connexion coupée sans reprise -> erreur
        t = file("/short", 2);
        // 1.4 : une coupure n'est plus une erreur définitive : attente du réseau, puis reprise automatique
        if ((Task.ERROR.equals(t.status) || Task.WAITING.equals(t.status)) && t.error != null) ok("connexion coupée", t.status + " : " + t.error);
        else fail("connexion coupée", t.status);

        // 7. cookies jamais envoyés à un autre site
        get(B + "/control?reset_cookies=1");
        FileTask k = new FileTask("k", B + "/redirect-other", new File(OUT, "cookie"), 2);
        k.headers.put("Cookie", "session=SECRET");
        k.start();
        k.await();
        String seen = get(B + "/control?cookies=1");
        if (Task.COMPLETED.equals(k.status) && !seen.contains("SECRET")) ok("cookies protégés", seen.trim());
        else fail("cookies protégés", k.status + " " + k.error + " " + seen);

        // 8. HLS
        String[][] hls = {{"/master.m3u8", "maitre"}, {"/enc/index.m3u8", "chiffre"}, {"/fmp4/master.m3u8", "fmp4"}};
        for (String[] h : hls) {
            HlsTask v = new HlsTask("h" + h[1], H + h[0], new File(OUT, "hls"), 6, null);
            v.forcedName = h[1] + ".mp4";
            v.start();
            v.await();
            if (Task.COMPLETED.equals(v.status)) {
                ok("hls " + h[1], v.filename + " " + (v.variant() == null ? "" : v.variant()) + (v.note == null ? "" : " — " + v.note));
                System.out.println("FILE " + h[1] + " " + v.path.getPath());
            } else fail("hls " + h[1], v.status + " " + v.error);
        }
        HlsTask q = new HlsTask("q", H + "/master.m3u8", new File(OUT, "hls"), 4, 480);
        q.start();
        q.await();
        if ("640x360".equals(q.variant())) ok("hls qualité", "<= 480p -> " + q.variant()); else fail("hls qualité", String.valueOf(q.variant()) + " " + q.error);
        HlsTask d = new HlsTask("d", H + "/drm.m3u8", new File(OUT, "hls"), 2, null);
        d.start();
        d.await();
        if (Task.ERROR.equals(d.status) && d.error.contains("DRM")) ok("hls DRM refusé", d.error); else fail("hls DRM", d.status + " " + d.error);
        HlsTask l = new HlsTask("l", B + "/live.m3u8", new File(OUT, "hls"), 2, null);
        l.start();
        l.await();
        if (Task.ERROR.equals(l.status) && l.error.contains("direct")) ok("hls direct refusé", l.error); else fail("hls direct", l.status + " " + l.error);

        // 8b. vidéo « image + son » séparés (Facebook…) : analyse des pistes puis téléchargement
        Mp4.Info vi = Mp4.sniff(H + "/dash/video.mp4", new LinkedHashMap<String, String>());
        Mp4.Info ai = Mp4.sniff(H + "/dash/audio.mp4", new LinkedHashMap<String, String>());
        String[] norm = Mp4.normalize("https://video.fbcdn.net/v/x.mp4?oh=SIG&bytestart=0&byteend=299999&oe=68");
        if ("video".equals(vi.track) && vi.height == 480 && "audio".equals(ai.track)
                && "https://video.fbcdn.net/v/x.mp4?oh=SIG&oe=68".equals(norm[0]) && "true".equals(norm[1])) {
            ok("analyse des pistes", "image " + vi.height + "p, son ; adresse nettoyée");
        } else fail("analyse des pistes", vi.track + " " + vi.height + " / " + ai.track + " / " + norm[0]);
        DashTask ds = new DashTask("ds", H + "/dash/video.mp4", H + "/dash/audio.mp4", new File(OUT, "dash"), 4);
        ds.forcedName = "Facebook.mp4";
        ds.probe();
        ds.start();
        ds.await();
        File son = new File(ds.path == null ? "" : ds.path.getPath().replaceAll("\\.mp4$", ".son.m4a"));
        if (Task.COMPLETED.equals(ds.status) && son.isFile()) {
            ok("image + son", ds.filename + " + " + son.getName() + " (sans MediaMuxer sur ordinateur)");
            System.out.println("FILE dash-image " + ds.path.getPath());
            System.out.println("FILE dash-son " + son.getPath());
        } else fail("image + son", ds.status + " " + ds.error);

        // 9. Manager : file d'attente, état pour l'interface, sauvegarde et relecture
        File state = new File(OUT, "etat.json");
        Manager m = new Manager(state, new File(OUT, "manager"));
        Map<String, String> hd = new LinkedHashMap<String, String>();
        hd.put("Referer", "https://exemple.mg/page");
        Map<String, Object> r = m.add(B + "/same", 4, null, hd, true, null, null);
        Map<String, Object> bad = m.add("ftp://x", null, null, null, false, null, null);
        String id = (String) r.get("id");
        for (int i = 0; i < 80 && !Task.COMPLETED.equals(m.find(id).status); i++) Thread.sleep(250);
        Map<String, Object> st = Json.obj(Json.parse(Json.write(m.state(null))));
        List<Object> items = Json.arr(st.get("items"));
        m.shutdown();
        Manager m2 = new Manager(state, new File(OUT, "manager"));
        if (Boolean.TRUE.equals(r.get("ok")) && Boolean.FALSE.equals(bad.get("ok")) && items.size() == 1
                && "completed".equals(Json.str(Json.obj(items.get(0)), "status")) && m2.find(id) != null
                && Task.COMPLETED.equals(m2.find(id).status)) ok("manager", "file d'attente + état JSON + relecture");
        else fail("manager", Json.write(st));

        System.out.println(failed == 0 ? "TOUS LES TESTS PASSENT" : failed + " ÉCHEC(S)");
        System.exit(failed == 0 ? 0 : 1);
    }
}
