package com.selena.voice;

import android.net.ConnectivityManager;
import android.net.Network;

/**
 * v1.65: connectivity watcher for the offline-dictation workflow.
 * A NAMED top-level class, not an anonymous inner class — the one
 * anonymous inner class tried in this codebase (v1.59) made the
 * build-tools 34 d8 fail with an internal NPE (nestmate
 * desugaring), and this callback shape is exactly that hazard.
 */
public class OfflineNetworkCallback extends ConnectivityManager.NetworkCallback {
    private final VoiceService service;

    public OfflineNetworkCallback(VoiceService service) {
        this.service = service;
    }

    @Override
    public void onAvailable(Network network) {
        service.onNetworkAvailable();
    }

    @Override
    public void onLost(Network network) {
        service.onNetworkLost();
    }
}
