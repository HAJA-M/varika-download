package com.varikaagency.download;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import com.varikaagency.download.core.Manager;

/**
 * Suit l'état du réseau : connecté ou non, Wi-Fi (illimité) ou données mobiles (facturées).
 * Dès que le réseau revient, les téléchargements en attente repartent tout seuls.
 */
final class NetworkMonitor extends ConnectivityManager.NetworkCallback {
    private final ConnectivityManager cm;
    private final Manager manager;

    private NetworkMonitor(Context c, Manager m) {
        cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        manager = m;
    }

    static void start(Context c, Manager m) {
        NetworkMonitor n = new NetworkMonitor(c, m);
        n.publish(n.cm.getNetworkCapabilities(n.cm.getActiveNetwork()));
        n.cm.registerDefaultNetworkCallback(n);
    }

    private void publish(NetworkCapabilities caps) {
        boolean available = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        // « non facturé » = Wi-Fi ou Ethernet ; « temporairement non facturé » = forfait illimité signalé par l'opérateur
        boolean unmetered = caps != null && (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                || (android.os.Build.VERSION.SDK_INT >= 30
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)));
        manager.setNetwork(available, unmetered);
    }

    @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) { publish(caps); }

    @Override public void onLost(Network network) { manager.setNetwork(false, false); }
}
