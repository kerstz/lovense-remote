package com.edge2.remote.service

import android.service.quicksettings.TileService
import com.edge2.remote.RemoteEngine

/**
 * Tuile Réglages rapides « STOP » : coupe instantanément le toy depuis le volet
 * de notifications, sans ouvrir l'app (utilise le moteur au scope processus).
 */
class StopTileService : TileService() {
    override fun onClick() {
        super.onClick()
        RemoteEngine.get(applicationContext).stopAll()
    }
}
