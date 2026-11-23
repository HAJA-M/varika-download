package com.varikaagency.download;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import com.varikaagency.download.core.Task;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Publie un fichier terminé dans Téléchargements/Varika Download (MediaStore, Android 10+),
 * pour qu'il apparaisse dans l'application Fichiers, la galerie, etc.
 */
final class Publisher {
    private Publisher() {}

    static final String FOLDER = Environment.DIRECTORY_DOWNLOADS + "/Varika Download";

    static String mime(String name) {
        String ext = name == null ? "" : name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if ("ts".equals(ext)) return "video/mp2t";
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m != null ? m : "application/octet-stream";
    }

    static void publish(Context c, final Task t) {
        final File f = t.path;
        if (f == null || !f.isFile()) return;
        if (VarikaApp.directStorage && f.getPath().contains("/Download/")) {
            // Déjà au bon endroit : on demande seulement à Android de l'indexer (galerie, Fichiers…)
            android.media.MediaScannerConnection.scanFile(c, new String[]{f.getPath()}, new String[]{mime(f.getName())},
                    (path, uri) -> {
                        if (uri != null) t.publishedUri = uri.toString();
                        if (VarikaApp.manager() != null) VarikaApp.manager().markDirty();
                    });
            return;
        }
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, f.getName());
        v.put(MediaStore.Downloads.MIME_TYPE, mime(f.getName()));
        v.put(MediaStore.Downloads.RELATIVE_PATH, FOLDER);
        v.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = null;
        try {
            uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IllegalStateException("MediaStore indisponible");
            InputStream in = new FileInputStream(f);
            OutputStream out = cr.openOutputStream(uri);
            try {
                byte[] buf = new byte[1 << 20];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                in.close();
                if (out != null) out.close();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            t.publishedUri = uri.toString();
            if (!f.delete()) f.deleteOnExit();            // la copie publique fait foi
            // Piste audio séparée éventuelle (HLS sans conversion)
            File audio = new File(f.getParentFile(), f.getName().replaceAll("\\.[^.]+$", "") + ".audio.m4a");
            if (!audio.isFile()) audio = new File(f.getParentFile(), f.getName().replaceAll("\\.[^.]+$", "") + ".audio.ts");
            if (audio.isFile()) publishExtra(c, audio);
        } catch (Exception e) {
            if (uri != null) cr.delete(uri, null, null);
            t.note = "Fichier gardé dans le dossier de l'application (" + e.getMessage() + ")";
        }
    }

    private static void publishExtra(Context c, File f) throws Exception {
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, f.getName());
        v.put(MediaStore.Downloads.MIME_TYPE, mime(f.getName()));
        v.put(MediaStore.Downloads.RELATIVE_PATH, FOLDER);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) return;
        InputStream in = new FileInputStream(f);
        OutputStream out = cr.openOutputStream(uri);
        try {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
            if (out != null) out.close();
        }
        if (!f.delete()) f.deleteOnExit();
    }

    /** Supprime un fichier publié (quand l'utilisateur retire un téléchargement avec suppression). */
    static void unpublish(Context c, Task t) {
        if (t.path != null && t.path.isFile() && !t.path.delete()) t.path.deleteOnExit();
        if (t.publishedUri == null) return;
        try {
            c.getContentResolver().delete(Uri.parse(t.publishedUri), null, null);
        } catch (RuntimeException ignored) {
            // déjà supprimé par l'utilisateur
        }
    }
}
