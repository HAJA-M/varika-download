package com.varikaagency.download.core;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Réseau et noms de fichiers : mêmes règles que la version Windows (engine.py). */
public final class Net {
    private Net() {}

    public static final int TIMEOUT_MS = 30000;
    public static String userAgent = "Mozilla/5.0 (Linux; Android) Varika Download";

    /** Ouvre une connexion en suivant les redirections ; les cookies ne passent jamais à un autre site. */
    public static HttpURLConnection open(String url, Map<String, String> headers) throws IOException {
        String current = url;
        Map<String, String> h = new LinkedHashMap<String, String>(headers);
        String firstHost = host(url);
        for (int hop = 0; hop < 10; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("User-Agent", userAgent);
            c.setRequestProperty("Accept-Encoding", "identity");
            for (Map.Entry<String, String> e : h.entrySet()) {
                boolean secret = e.getKey().equalsIgnoreCase("Cookie") || e.getKey().equalsIgnoreCase("Authorization");
                if (secret && !host(current).equalsIgnoreCase(firstHost)) continue;
                c.setRequestProperty(e.getKey(), e.getValue());
            }
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                current = new URL(new URL(current), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            return c;
        }
        throw new IOException("trop de redirections");
    }

    public static String host(String url) {
        try {
            String h = new URL(url).getHost();
            return h == null ? "" : h;
        } catch (IOException e) {
            return "";
        }
    }

    public static byte[] readAll(InputStream in, int limit) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > limit) throw new IOException("réponse anormalement grosse : téléchargement arrêté");
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ noms de fichiers
    public static String filenameFromHeaders(String contentDisposition, String url) {
        String cd = contentDisposition == null ? "" : contentDisposition;
        Matcher m = Pattern.compile("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", Pattern.CASE_INSENSITIVE).matcher(cd);
        if (m.find()) {
            try {
                String enc = m.group(1).isEmpty() ? "UTF-8" : m.group(1);
                return URLDecoder.decode(m.group(2).replace("\"", "").replace("+", "%2B"), enc);
            } catch (UnsupportedEncodingException | IllegalArgumentException ignored) {
                // on essaie la suite
            }
        }
        m = Pattern.compile("filename\\s*=\\s*\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE).matcher(cd);
        if (m.find()) {
            String name = m.group(1).trim();
            try {   // en-tête en UTF-8 brut lu comme du latin-1 : on le relit en UTF-8 strict
                byte[] raw = name.getBytes("ISO-8859-1");
                return java.nio.charset.Charset.forName("UTF-8").newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(raw)).toString();
            } catch (java.io.IOException ignored) {
                return name;      // ce n'était pas de l'UTF-8 : on garde le nom tel quel
            }
        }
        try {
            String path = new URL(url).getPath();
            String last = path.substring(path.lastIndexOf('/') + 1);
            String dec = URLDecoder.decode(last.replace("+", "%2B"), "UTF-8");
            return dec.isEmpty() ? "telechargement" : dec;
        } catch (IOException | IllegalArgumentException e) {
            return "telechargement";
        }
    }

    private static final java.util.Set<String> RESERVED = new java.util.HashSet<String>();
    static {
        String[] r = {"CON", "PRN", "AUX", "NUL"};
        for (String x : r) RESERVED.add(x);
        for (int i = 1; i < 10; i++) { RESERVED.add("COM" + i); RESERVED.add("LPT" + i); }
    }

    public static String safeName(String name) {
        String n = name == null ? "" : name.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1f]", "_").trim();
        while (n.endsWith(".") || n.endsWith(" ")) n = n.substring(0, n.length() - 1);
        while (n.startsWith(".") || n.startsWith(" ")) n = n.substring(1);
        if (n.isEmpty()) return "telechargement";
        int dot = n.lastIndexOf('.');
        String base = dot > 0 && n.length() - dot <= 16 ? n.substring(0, dot) : n;
        String ext = dot > 0 && n.length() - dot <= 16 ? n.substring(dot) : "";
        String first = base.split("\\.")[0].toUpperCase(Locale.ROOT);
        if (RESERVED.contains(first)) base = "_" + base;
        if (base.length() + ext.length() > 180) base = base.substring(0, 180 - ext.length()).trim();
        return base + ext;
    }

    // ------------------------------------------------------------------ réservation des noms
    private static final Map<String, Object> RESERVED_PATHS = new HashMap<String, Object>();

    private static String key(File f) {
        return f.getAbsolutePath();
    }

    /** Nom libre sur le disque ET parmi les téléchargements en cours (évite deux fichiers mélangés). */
    public static File claim(File wanted, Object owner) {
        String name = wanted.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        synchronized (RESERVED_PATHS) {
            for (int i = 0; ; i++) {
                File f = i == 0 ? wanted : new File(wanted.getParentFile(), base + " (" + i + ")" + ext);
                Object o = RESERVED_PATHS.get(key(f));
                boolean taken = o != null && o != owner;
                if (!taken && !f.exists() && !new File(f.getPath() + ".part").exists() && !new File(f.getPath() + ".parts").exists()) {
                    RESERVED_PATHS.put(key(f), owner);
                    return f;
                }
            }
        }
    }

    public static boolean claimExisting(File f, Object owner) {
        synchronized (RESERVED_PATHS) {
            Object o = RESERVED_PATHS.get(key(f));
            if (o != null && o != owner) return false;
            RESERVED_PATHS.put(key(f), owner);
            return true;
        }
    }

    public static void release(File f) {
        if (f == null) return;
        synchronized (RESERVED_PATHS) {
            RESERVED_PATHS.remove(key(f));
        }
    }

    public static String humanSize(double n) {
        String[] u = {"o", "Ko", "Mo", "Go", "To"};
        int i = 0;
        while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
        return i == 0 ? String.format(Locale.FRANCE, "%.0f %s", n, u[i]) : String.format(Locale.FRANCE, "%.1f %s", n, u[i]);
    }

    public static void checkFreeSpace(File dir, long needed) throws IOException {
        if (needed <= 0) return;
        long free = dir.getUsableSpace();
        if (free > 0 && free < needed + 16L * 1024 * 1024) {
            throw new IOException("Espace insuffisant : " + humanSize(needed) + " nécessaires, " + humanSize(free) + " libres");
        }
    }

    /** Erreur de réseau (coupure, délai, connexion refusée…) plutôt qu'une vraie erreur du lien. */
    public static boolean isNetworkError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.UnknownHostException || t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.ConnectException || t instanceof java.net.NoRouteToHostException
                    || t instanceof java.net.SocketException || t instanceof java.io.EOFException
                    || t instanceof javax.net.ssl.SSLException) return true;
            String m = String.valueOf(t.getMessage());
            if (m.contains("connexion fermée trop tôt") || m.contains("unexpected end of stream")
                    || m.contains("Connection reset") || m.contains("connexion interrompue")) return true;
        }
        return false;
    }

    /** Test rapide : le serveur répond-il ? */
    public static boolean reachable(String url) {
        try {
            URL u = new URL(url);
            int port = u.getPort() > 0 ? u.getPort() : ("https".equals(u.getProtocol()) ? 443 : 80);
            java.net.Socket s = new java.net.Socket();
            try {
                s.connect(new java.net.InetSocketAddress(u.getHost(), port), 4000);
                return true;
            } finally {
                s.close();
            }
        } catch (IOException e) {
            return false;
        }
    }

    public static String message(Throwable e) {
        String m = e.getMessage();
        if (e instanceof java.net.UnknownHostException) return "pas de connexion Internet (" + m + ")";
        if (e instanceof java.net.SocketTimeoutException) return "le serveur ne répond pas";
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
