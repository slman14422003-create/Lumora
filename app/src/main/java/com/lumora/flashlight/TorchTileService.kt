package com.lumora.flashlight

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class TorchTileService : TileService() {
    private var manager: CameraManager? = null
    private var cameraId: String? = null
    private var torchOn = false
    private var available = true
    private var callback: CameraManager.TorchCallback? = null

    override fun onStartListening() {
        super.onStartListening()
        val cameras = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        manager = cameras
        val id = TorchSupport.findTorchCamera(cameras)
        cameraId = id
        updateTile()
        if (id == null) return
        val torchCallback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(changedId: String, enabled: Boolean) {
                if (changedId != id) return
                torchOn = enabled
                available = true
                updateTile()
            }

            override fun onTorchModeUnavailable(changedId: String) {
                if (changedId != id) return
                available = false
                updateTile()
            }
        }
        try {
            cameras.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
            callback = torchCallback
        } catch (_: Exception) { }
    }

    override fun onStopListening() {
        callback?.let { try { manager?.unregisterTorchCallback(it) } catch (_: Exception) { } }
        callback = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val cameras = manager ?: return
        val id = cameraId ?: return
        val target = !torchOn
        try {
            cameras.setTorchMode(id, target)
            if (target) {
                val minutes = getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
                    .getInt(Prefs.TIMER_MINUTES, 0)
                AutoOffTimer.start(this, minutes)
            } else {
                AutoOffTimer.cancel(this)
            }
        } catch (_: Exception) { }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        tile.state = when {
            cameraId == null || !available -> Tile.STATE_UNAVAILABLE
            torchOn -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }
}
