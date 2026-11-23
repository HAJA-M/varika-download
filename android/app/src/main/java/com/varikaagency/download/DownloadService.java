package com.varikaagency.download;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.varikaagency.download.core.Manager;
import com.varikaagency.download.core.Net;
import com.varikaagency.download.core.Task;

/**
 * Service au premier plan : garde l'application en vie pendant les téléchargements
 * (sinon Android l'arrête quand l'écran s'éteint) et affiche la progression.
 */
public class DownloadService extends Service {
    static final int ID = 1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int idleTicks;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Manager m = VarikaApp.manager();
            if (m == null || !m.busy()) {
                if (++idleTicks >= 3) {             // rien depuis 3 s : on s'arrête
                    stopForegroundCompat();
                    stopSelf();
                    return;
                }
            } else {
                idleTicks = 0;
                Notifications.manager(DownloadService.this).notify(ID, build(m));
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = build(VarikaApp.manager());
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(ID, n);
        }
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_NOT_STICKY;
    }

    private Notification build(Manager m) {
        int active = 0;
        long done = 0, total = 0;
        double speed = 0;
        if (m != null) {
            for (Task t : m.snapshot()) {
                if (!t.isRunning()) continue;
                active++;
                speed += t.speed;
                if (t.total > 0) {
                    total += t.total;
                    done += Math.min(t.total, t.downloaded());
                }
            }
        }
        Notification.Builder b = Notifications.builder(this, VarikaApp.CH_PROGRESS)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(active <= 1 ? getString(R.string.notif_one) : getString(R.string.notif_many, active))
                .setContentText(Net.humanSize(speed) + "/s" + (total > 0 ? " · " + Net.humanSize(done) + " / " + Net.humanSize(total) : ""))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        if (total > 0) b.setProgress(1000, (int) (done * 1000 / total), false);
        else b.setProgress(0, 0, active > 0);
        Intent pause = new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_PAUSE_ALL);
        b.addAction(new Notification.Action.Builder(null, getString(R.string.pause_all),
                PendingIntent.getActivity(this, 1, pause, PendingIntent.FLAG_IMMUTABLE)).build());
        return b.build();
    }

    @SuppressWarnings("deprecation")
    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
