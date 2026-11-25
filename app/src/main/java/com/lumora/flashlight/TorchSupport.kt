package com.lumora.flashlight

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

object TorchSupport {
    fun findTorchCamera(manager: CameraManager): String? = try {
        manager.cameraIdList.firstOrNull { id ->
            try {
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } catch (_: Exception) { false }
        }
    } catch (_: Exception) { null }

    fun turnOff(context: Context) {
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            findTorchCamera(manager)?.let { manager.setTorchMode(it, false) }
        } catch (_: Exception) { }
    }
}
