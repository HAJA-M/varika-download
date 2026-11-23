package com.varikaagency.download;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.webkit.WebSettings;

import com.varikaagency.download.core.HlsTask;
import com.varikaagency.download.core.Manager;
import com.varikaagency.download.core.Net;
import com.varikaagency.download.core.Task;

import java.io.File;

/** Application : un seul gestionnaire de téléchargements pour toute la durée de vie du processus. */
public class VarikaApp extends Application {
    public static final String CH_PROGRESS = "progress", CH_DONE = "done";
    private static Manager manager;
    /** true : les fichiers sont écrits directement dans Téléchargements/Varika Download (pas de copie à la fin). */
    static boolean directStorage;

    public static Manager manager() { return manager; }

    /**
     * Gros fichiers : écrire directement à l'emplacement final évite de recopier 5 Go à la fin
     * (et d'avoir besoin de deux fois la place). On vérifie que c'est possible sur ce téléphone ;
     * sinon, repli sur le dossier de l'application puis publication à la fin.
     */
    static File pickStorage(android.content.Context c) {
        File pub = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Varika Download");
        try {
            if (pub.mkdirs() || pub.isDirectory()) {
                File probe = new File(pub, ".varika-test-" + System.nanoTime());
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(probe, "rw");
                try {
                    raf.setLength(1 << 20);                // écriture à une position arbitraire, comme le moteur
                    raf.seek(1 << 19);
                    raf.write(42);
                } finally {
                    raf.close();
                }
                boolean ok = probe.length() == (1 << 20);
                if (!probe.delete()) probe.deleteOnExit();
                if (ok) {
                    directStorage = true;
                    return pub;
                }
            }
        } catch (Exception ignored) {
            // pas autorisé sur ce téléphone : repli
        }
        directStorage = false;
        File work = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        return work != null ? work : new File(c.getFilesDir(), "downloads");
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannels();
        try {
            Net.userAgent = WebSettings.getDefaultUserAgent(this);     // comme le navigateur du téléphone
        } catch (RuntimeException ignored) {
            // valeur par défaut
        }
        File work = pickStorage(this);
        HlsTask.remuxer = new MediaRemuxer();
        manager = new Manager(new File(getFilesDir(), "downloads.json"), work);
        manager.settings.put("dest", work.getPath());          // l'emplacement peut changer (permission accordée…)
        NetworkMonitor.start(this, manager);
        manager.setListener(new Manager.Listener() {
            @Override public void onCompleted(Task t) {
                Publisher.publish(VarikaApp.this, t);
                Notifications.completed(VarikaApp.this, t);
            }

            @Override public void onActivityChanged(boolean busy) {
                if (busy) {
                    Intent i = new Intent(VarikaApp.this, DownloadService.class);
                    try {
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                        else startService(i);
                    } catch (RuntimeException ignored) {
                        // Android 12+ refuse le démarrage depuis l'arrière-plan : le service démarrera
                        // à la prochaine ouverture de l'application, les téléchargements continuent.
                    }
                }
            }
        });
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel p = new NotificationChannel(CH_PROGRESS, getString(R.string.ch_progress), NotificationManager.IMPORTANCE_LOW);
        p.setShowBadge(false);
        nm.createNotificationChannel(p);
        nm.createNotificationChannel(new NotificationChannel(CH_DONE, getString(R.string.ch_done), NotificationManager.IMPORTANCE_DEFAULT));
    }
}
