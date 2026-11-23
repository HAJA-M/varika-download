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

    public static Manager manager() { return manager; }

    @Override public void onCreate() {
        super.onCreate();
        createChannels();
        try {
            Net.userAgent = WebSettings.getDefaultUserAgent(this);     // comme le navigateur du téléphone
        } catch (RuntimeException ignored) {
            // valeur par défaut
        }
        // Dossier de travail privé (écriture à n'importe quelle position, nécessaire au multi-connexions).
        // Une fois terminé, le fichier est publié dans Téléchargements/Varika Download (voir Publisher).
        File work = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (work == null) work = new File(getFilesDir(), "downloads");
        HlsTask.remuxer = new MediaRemuxer();
        manager = new Manager(new File(getFilesDir(), "downloads.json"), work);
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
