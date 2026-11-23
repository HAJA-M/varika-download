package com.varikaagency.download.core;

import java.net.HttpURLConnection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Lit le début d'un MP4 pour savoir s'il contient l'image ou le son, et la hauteur de l'image. */
public final class Mp4 {
    private Mp4() {}

    public static final class Info {
        public String track;   // "video", "audio" ou null
        public int height;
        public long size = -1;
    }

    static int find(byte[] b, String name) {
        byte[] n = name.getBytes();
        outer:
        for (int i = 0; i + 4 <= b.length; i++) {
            for (int k = 0; k < 4; k++) if (b[i + k] != n[k]) continue outer;
            return i;
        }
        return -1;
    }

    public static Info parse(byte[] b) {
        Info info = new Info();
        int h = find(b, "hdlr");
        if (h > 0 && h + 16 <= b.length) {
            String type = new String(b, h + 12, 4);
            info.track = "soun".equals(type) ? "audio" : "vide".equals(type) ? "video" : null;
        }
        int t = find(b, "tkhd");
        if ("video".equals(info.track) && t > 0) {
            int off = t + (b[t + 4] == 1 ? 96 : 84);
            if (off + 2 <= b.length) {
                int height = ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
                if (height > 0 && height < 10000) info.height = height;
            }
        }
        return info;
    }

    /** Télécharge seulement les 256 premiers Ko et les analyse. */
    public static Info sniff(String url, Map<String, String> headers) {
        Info info = new Info();
        try {
            Map<String, String> h = new LinkedHashMap<String, String>(headers);
            h.put("Range", "bytes=0-262143");
            HttpURLConnection c = Net.open(url, h);
            try {
                // on ne lit que le début, même si le serveur ignore la plage demandée
                java.io.InputStream in = c.getInputStream();
                byte[] b = new byte[262144];
                int got = 0, n;
                while (got < b.length && (n = in.read(b, got, b.length - got)) > 0) got += n;
                b = java.util.Arrays.copyOf(b, got);
                info = parse(b);
                String cr = c.getHeaderField("Content-Range");
                if (cr != null && cr.matches(".*/\\d+\\s*$")) info.size = Long.parseLong(cr.replaceAll(".*/(\\d+)\\s*$", "$1"));
            } finally {
                c.disconnect();
            }
        } catch (Exception ignored) {
            // inconnu
        }
        return info;
    }

    /** Retire les paramètres de « morceau » ajoutés par les lecteurs adaptatifs (Facebook…). */
    public static String[] normalize(String url) {
        String u = url.replaceAll("([?&])(bytestart|byteend)=\\d*", "$1").replaceAll("&{2,}", "&").replace("?&", "?");
        if (u.endsWith("&") || u.endsWith("?")) u = u.substring(0, u.length() - 1);
        return new String[]{u, String.valueOf(!u.equals(url))};
    }
}
