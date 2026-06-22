package com.edge2.remote.service

/**
 * Pont minimal entre la notification du service premier-plan et le ViewModel :
 * le bouton « Couper » de la notif appelle [onStop] (défini par le ViewModel),
 * sans que le service ait besoin d'une référence au BLE.
 */
object AppActions {
    @Volatile
    var onStop: (() -> Unit)? = null
}
