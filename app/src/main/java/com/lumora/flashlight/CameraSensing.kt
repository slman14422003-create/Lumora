package com.lumora.flashlight

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.pow

fun hasCameraPermission(context: Context) =
    context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private class TinyCamera(
    private val context: Context,
    private val facing: Int,
    private val onFrame: (Image?, TotalCaptureResult, CameraCharacteristics) -> Unit,
    private val onClosed: () -> Unit
) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val handler = Handler(Looper.getMainLooper())
    private var device: CameraDevice? = null
    private var reader: ImageReader? = null
    private var opening = false
    private var closed = false

    @SuppressLint("MissingPermission")
    fun open() {
        if (!hasCameraPermission(context)) return finish()
        try {
            val id = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
            } ?: return finish()
            val info = manager.getCameraCharacteristics(id)
            val size = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.minByOrNull { it.width * it.height } ?: return finish()
            val images = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
            reader = images
            var latest: TotalCaptureResult? = null
            images.setOnImageAvailableListener({ source ->
                val image = try { source.acquireLatestImage() } catch (_: Exception) { null }
                try {
                    val result = latest
                    if (!closed && result != null) onFrame(image, result, info)
                } finally {
                    image?.close()
                }
            }, handler)
            opening = true
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    opening = false
                    device = camera
                    if (closed) return camera.close()
                    start(camera, images) { latest = it }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    opening = false
                    camera.close()
                    close()
                }
                override fun onError(camera: CameraDevice, error: Int) = onDisconnected(camera)
                override fun onClosed(camera: CameraDevice) = this@TinyCamera.onClosed()
            }, handler)
        } catch (_: Exception) {
            opening = false
            close()
        }
    }

    @Suppress("DEPRECATION")
    private fun start(camera: CameraDevice, images: ImageReader, onResult: (TotalCaptureResult) -> Unit) {
        try {
            camera.createCaptureSession(listOf(images.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (closed) return
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(images.surface)
                            set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                        }.build()
                        session.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                result: TotalCaptureResult
                            ) = onResult(result)
                        }, handler)
                    } catch (_: Exception) {
                        close()
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) = close()
            }, handler)
        } catch (_: Exception) {
            close()
        }
    }

    private fun finish() {
        closed = true
        onClosed()
    }

    fun close() {
        if (closed) return
        closed = true
        val camera = device
        device = null
        try {
            camera?.close()
        } catch (_: Exception) { }
        reader?.close()
        reader = null
        if (camera == null && !opening) onClosed()
    }
}

class FrontLightSampler(
    private val context: Context,
    private val onReading: (lux: Float, redGain: Float, blueGain: Float) -> Unit
) {
    private var camera: TinyCamera? = null
    private var lastRead = 0L

    val running get() = camera != null

    fun start() {
        if (camera != null) return
        camera = TinyCamera(context, CameraCharacteristics.LENS_FACING_FRONT, ::read) {}.also { it.open() }
    }

    fun stop() {
        camera?.close()
        camera = null
    }

    private fun read(image: Image?, result: TotalCaptureResult, info: CameraCharacteristics) {
        val now = SystemClock.elapsedRealtime()
        if (image == null || now - lastRead < 700) return
        val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
        val aperture = result.get(CaptureResult.LENS_APERTURE)
            ?: info.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull() ?: 2f
        if (exposure <= 0 || iso <= 0) return
        lastRead = now
        val plane = image.planes[0]
        val buffer = plane.buffer
        var sum = 0L
        var count = 0
        for (row in 0 until 64) {
            val y = row * image.height / 64
            for (column in 0 until 64) {
                val index = y * plane.rowStride + (column * image.width / 64) * plane.pixelStride
                if (index < buffer.limit()) {
                    sum += buffer.get(index).toInt() and 0xFF
                    count++
                }
            }
        }
        if (count == 0) return
        val brightness = (sum.toDouble() / count / 118.0).coerceIn(.02, 2.2)
        val scene = 2.5 * (aperture * aperture / (exposure / 1e9)) * (100.0 / iso)
        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        onReading((scene * brightness.pow(2.2)).toFloat(), gains?.red ?: 0f, gains?.blue ?: 0f)
    }
}

class BackPreCheck(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())

    fun run(done: (distanceMeters: Float?) -> Unit) {
        var camera: TinyCamera? = null
        var distance: Float? = null
        var frames = 0
        var delivered = false
        val deliver = Runnable {
            if (!delivered) {
                delivered = true
                done(distance)
            }
        }
        val stop = Runnable { camera?.close() }
        camera = TinyCamera(context, CameraCharacteristics.LENS_FACING_BACK, { _, result, info ->
            frames++
            val calibrated = info.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION) !=
                CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED
            val state = result.get(CaptureResult.CONTROL_AF_STATE)
            val focused = state == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
            val diopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            if (calibrated && focused && diopters != null && diopters > 0f) distance = 1f / diopters
            if (distance != null || frames >= 12) handler.post(stop)
        }) {
            handler.removeCallbacks(stop)
            handler.postDelayed(deliver, 200)
        }
        handler.postDelayed(stop, 900)
        handler.postDelayed(deliver, 2000)
        camera?.open()
    }
}
