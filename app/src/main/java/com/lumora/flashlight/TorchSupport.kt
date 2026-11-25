package com.lumora.flashlight

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import kotlin.math.roundToInt

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

    fun deviceLevel(strength: Int, maxLevel: Int): Int =
        ((((strength.coerceIn(1, 10) - 1) / 9f) * (maxLevel - 1)).roundToInt() + 1).coerceIn(1, maxLevel)

    fun hasFrontCamera(manager: CameraManager): Boolean = try {
        manager.cameraIdList.any {
            try {
                manager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } catch (_: Exception) { false }
        }
    } catch (_: Exception) { false }

    fun hasLightSensor(context: Context): Boolean = try {
        (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
            .getDefaultSensor(Sensor.TYPE_LIGHT) != null
    } catch (_: Exception) { false }
}

class TorchOutput(private val manager: CameraManager) {
    val id: String? = TorchSupport.findTorchCamera(manager)
    private val maxLevel: Int = readMaxLevel()
    private var lastLevel = -1

    private fun readMaxLevel(): Int {
        val camera = id ?: return 1
        if (Build.VERSION.SDK_INT < 33) return 1
        return try {
            manager.getCameraCharacteristics(camera)
                .get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
        } catch (_: Exception) { 1 }
    }

    fun forget() {
        lastLevel = -1
    }

    fun set(on: Boolean, strength: Int) {
        val camera = id ?: return
        if (!on) {
            manager.setTorchMode(camera, false)
            lastLevel = -1
        } else if (maxLevel > 1 && Build.VERSION.SDK_INT >= 33) {
            val level = TorchSupport.deviceLevel(strength, maxLevel)
            if (level != lastLevel) {
                manager.turnOnTorchWithStrengthLevel(camera, level)
                lastLevel = level
            }
        } else {
            manager.setTorchMode(camera, true)
        }
    }
}
