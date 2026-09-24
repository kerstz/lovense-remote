package com.edge2.remote.service

/**
 * Minimal bridge between the foreground-service notification and the engine:
 * the notification's "Disconnect" button calls [onStop] (set by the engine),
 * without the service needing a reference to the BLE layer.
 */
object AppActions {
    @Volatile
    var onStop: (() -> Unit)? = null
}
