package com.edge2.remote.service

import android.service.quicksettings.TileService
import com.edge2.remote.RemoteEngine

/**
 * "STOP" Quick Settings tile: instantly stops every toy from the notification
 * shade, without opening the app (uses the process-scoped engine). Also stops
 * sharing, so a remote controller cannot restart the toys.
 */
class StopTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val engine = RemoteEngine.get(applicationContext)
        engine.stopSharing() // cut remote access so a controller can't restart the toy
        engine.stopAll()
    }
}
