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
    private android.os.PowerManager.WakeLock wake;
    private android.net.wifi.WifiManager.WifiLock wifi;

    /**
     * Pendant un téléchargement, écran éteint : on garde le processeur et le Wi-Fi éveillés.
     * Sans ça, Android met le Wi-Fi en veille et un fichier de 5 Go n'avance plus.
     * Les verrous sont rendus dès qu'il n'y a plus de transfert (attente du réseau comprise).
     */
    @SuppressWarnings("deprecation")
    private void holdLocks(boolean transferring) {
        if (transferring) {
            if (wake == null) {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                wake = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "varika:telechargement");
                wake.setReferenceCounted(false);
            }
            if (!wake.isHeld()) wake.acquire(60 * 60 * 1000L);        // renouvelé tant que ça télécharge
            if (wifi == null) {
                android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                wifi = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "varika:wifi");
                wifi.setReferenceCounted(false);
            }
            if (!wifi.isHeld()) wifi.acquire();
        } else {
            if (wake != null && wake.isHeld()) wake.release();
            if (wifi != null && wifi.isHeld()) wifi.release();
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Manager m = VarikaApp.manager();
            if (m == null || !m.busy()) {
                if (++idleTicks >= 3) {             // rien depuis 3 s : on s'arrête
                    holdLocks(false);
                    stopForegroundCompat();
                    stopSelf();
                    return;
                }
            } else {
                idleTicks = 0;
                boolean transferring = false;
                for (Task t : m.snapshot()) transferring |= t.isRunning();
                holdLocks(transferring);
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
        int active = 0, waiting = 0;
        long done = 0, total = 0;
        double speed = 0;
        if (m != null) {
            for (Task t : m.snapshot()) {
                if (Task.WAITING.equals(t.status)) waiting++;
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
                .setContentTitle(active == 0 && waiting > 0 ? getString(R.string.notif_waiting)
                        : active <= 1 ? getString(R.string.notif_one) : getString(R.string.notif_many, active))
                .setContentText(active == 0 && waiting > 0 ? getString(R.string.notif_waiting_text, waiting)
                        : Net.humanSize(speed) + "/s" + (total > 0 ? " · " + Net.humanSize(done) + " / " + Net.humanSize(total) : ""))
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

    /**
     * Android 15 limite la durée d'un service de transfert. Quand la limite arrive, on met les
     * téléchargements en attente proprement (tout est sauvegardé) : ils reprendront à la
     * prochaine ouverture de l'application.
     */
    @Override public void onTimeout(int startId, int fgsType) {
        Manager m = VarikaApp.manager();
        if (m != null) for (Task t : m.snapshot()) if (t.isRunning()) t.suspend(getString(R.string.limit_reached));
        holdLocks(false);
        stopSelf();
    }

    @Override public void onDestroy() {
        holdLocks(false);
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
