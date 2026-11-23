package com.varikaagency.download;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import com.varikaagency.download.core.Task;

final class Notifications {
    private Notifications() {}

    static NotificationManager manager(Context c) {
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @SuppressWarnings("deprecation")
    static Notification.Builder builder(Context c, String channel) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, channel) : new Notification.Builder(c);
        return b.setColor(0xFF7ACC2E);
    }

    /** Notification « Téléchargement terminé » : un appui ouvre le fichier. */
    static void completed(Context c, Task t) {
        Intent open;
        if (t.publishedUri != null) {
            open = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(t.publishedUri), Publisher.mime(t.filename))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        } else {
            open = new Intent(c, MainActivity.class);
        }
        Notification n = builder(c, VarikaApp.CH_DONE)
                .setSmallIcon(R.drawable.ic_stat_done)
                .setContentTitle(c.getString(R.string.notif_done))
                .setContentText(t.filename + (t.note != null ? " — " + t.note : ""))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(c, t.id.hashCode(), Intent.createChooser(open, t.filename)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .build();
        manager(c).notify(t.id.hashCode(), n);
    }
}
