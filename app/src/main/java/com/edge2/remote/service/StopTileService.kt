package com.edge2.remote.service

import android.service.quicksettings.TileService
import com.edge2.remote.RemoteEngine

/**
 * "STOP" Quick Settings tile: instantly stops every toy from the notification
 * shade, without opening the app (uses the process-scoped engine).
 */
class StopTileService : TileService() {
    override fun onClick() {
        super.onClick()
        RemoteEngine.get(applicationContext).stopAll()
    }
}
