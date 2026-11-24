package com.kjota.procam

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.provider.MediaStore
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraFilter
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Mode(val label: String) {
    AUTO("AUTO"), PAISAJE("PAISAJE"), PRO("PRO"), MACRO("MACRO")
}

enum class WB(val label: String, val mode: Int) {
    AUTO("Auto", CameraMetadata.CONTROL_AWB_MODE_AUTO),
    SOL("Sol", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
    NUBLADO("Nublado", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    SOMBRA("Sombra", CameraMetadata.CONTROL_AWB_MODE_SHADE),
    INCAND("Incand.", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
    FLUOR("Fluor.", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT)
}

fun fmtShutter(ns: Long): String {
    val s = ns / 1e9
    return if (s >= 0.4) String.format(Locale.US, "%.1fs", s) else "1/${(1.0 / s).roundToInt()}"
}

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class ProCamera(private val ctx: Context) {

    val previewView = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }

    private val main = ContextCompat.getMainExecutor(ctx)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var provider: ProcessCameraProvider? = null
    private var extMgr: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var owner: LifecycleOwner? = null
    private var extActive = false
    private var dedicatedMacro = false
    private var pendingEv = 0f
    private var rotation = Surface.ROTATION_0

    // ---- Estado visible en la interfaz ----
    var mode by mutableStateOf(Mode.AUTO); private set
    var front by mutableStateOf(false); private set
    var hdr by mutableStateOf(false); private set
    var hdrCapable by mutableStateOf(false); private set
    var flash by mutableIntStateOf(ImageCapture.FLASH_MODE_OFF); private set
    var timer by mutableIntStateOf(0); private set
    var guides by mutableStateOf(true)

    var zoom by mutableFloatStateOf(1f); private set
    var minZoom by mutableFloatStateOf(1f); private set
    var maxZoom by mutableFloatStateOf(1f); private set

    var evIndex by mutableIntStateOf(0); private set
    var evMin by mutableIntStateOf(0); private set
    var evMax by mutableIntStateOf(0); private set
    var evStep by mutableFloatStateOf(1f / 3f); private set

    var autoExposure by mutableStateOf(true); private set
    var iso by mutableIntStateOf(100); private set
    var isoMin by mutableIntStateOf(50); private set
    var isoMax by mutableIntStateOf(3200); private set
    var shutterNs by mutableStateOf(8_000_000L); private set
    private var shutLo = 125_000.0
    private var shutHi = 1_000_000_000.0

    var wb by mutableStateOf(WB.AUTO); private set

    var autoFocus by mutableStateOf(true); private set
    var focusDiopter by mutableFloatStateOf(0f); private set
    var minFocusDiopter by mutableFloatStateOf(0f); private set

    var liveInfo by mutableStateOf(""); private set
    var macroInfo by mutableStateOf(""); private set
    var countdown by mutableIntStateOf(0); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var lastPhoto by mutableStateOf<Uri?>(null); private set
    var lastThumb by mutableStateOf<Bitmap?>(null); private set

    private val manualExposure get() = mode == Mode.PRO && !autoExposure
    private val manualFocus get() = (mode == Mode.PRO || mode == Mode.MACRO) && !autoFocus

    // ---- Valores en vivo desde el sensor ----
    private var frame = 0
    private var liveIsoV = 0
    private var liveTimeV = 0L
    private var liveFocusV = 0f
    private val liveCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val i = result.get(CaptureResult.SENSOR_SENSITIVITY)
            val t = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val f = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            if (i != null) liveIsoV = i
            if (t != null) liveTimeV = t
            if (f != null) liveFocusV = f
            if (++frame % 6 == 0 && i != null && t != null) {
                liveInfo = "ISO $i · ${fmtShutter(t)}"
            }
        }
    }

    private val orient = object : OrientationEventListener(ctx) {
        override fun onOrientationChanged(o: Int) {
            if (o == OrientationEventListener.ORIENTATION_UNKNOWN) return
            rotation = when {
                o in 45..134 -> Surface.ROTATION_270
                o in 135..224 -> Surface.ROTATION_180
                o in 225..314 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
            imageCapture?.targetRotation = rotation
        }
    }

    // ---- Ciclo de vida ----
    fun start(lifecycleOwner: LifecycleOwner) {
        owner = lifecycleOwner
        orient.enable()
        val f = ProcessCameraProvider.getInstance(ctx)
        f.addListener({
            val p = f.get()
            provider = p
            try {
                val ef = ExtensionsManager.getInstanceAsync(ctx, p)
                ef.addListener({
                    extMgr = try { ef.get() } catch (e: Exception) { null }
                    rebind()
                }, main)
            } catch (e: Exception) {
                rebind()
            }
        }, main)
    }

    fun release() {
        orient.disable()
        scope.cancel()
        provider?.unbindAll()
    }

    // ---- Construcción de la cámara ----
    private fun aspect43() = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .build()

    private fun bestMacroSelector(p: ProcessCameraProvider): CameraSelector? {
        var bestId: String? = null
        var bestD = 0f
        var defaultId: String? = null
        try {
            for (info in p.availableCameraInfos) {
                val c2 = Camera2CameraInfo.from(info)
                if (c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue
                if (defaultId == null) defaultId = c2.cameraId
                val d = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                if (d > bestD + 0.5f) { bestD = d; bestId = c2.cameraId }
            }
        } catch (e: Exception) { return null }
        val id = bestId ?: return null
        if (id == defaultId) return null
        return CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter(CameraFilter { infos ->
                infos.filter { Camera2CameraInfo.from(it).cameraId == id }
            })
            .build()
    }

    private fun rebind() {
        val p = provider ?: return
        val o = owner ?: return
        p.unbindAll()
        camera = null
        imageCapture = null

        val base = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val macroSel = if (mode == Mode.MACRO && !front) bestMacroSelector(p) else null
        dedicatedMacro = macroSel != null
        val m = extMgr
        hdrCapable = m != null && !front &&
            runCatching { m.isExtensionAvailable(base, ExtensionMode.HDR) }.getOrDefault(false)
        val wantHdr = hdr && hdrCapable && (mode == Mode.AUTO || mode == Mode.PAISAJE)

        fun attempt(sel: CameraSelector, highRes: Boolean, live: Boolean): Boolean {
            return try {
                val pb = Preview.Builder().setResolutionSelector(aspect43())
                if (live) Camera2Interop.Extender(pb).setSessionCaptureCallback(liveCallback)
                val preview = pb.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val rs = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .apply { if (highRes) setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY) }
                    .build()
                val cap = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(100)
                    .setFlashMode(flash)
                    .setResolutionSelector(rs)
                    .setTargetRotation(rotation)
                    .build()
                camera = p.bindToLifecycle(o, sel, preview, cap)
                imageCapture = cap
                true
            } catch (e: Exception) {
                p.unbindAll()
                false
            }
        }

        extActive = false
        var ok = false
        if (wantHdr && m != null) {
            ok = attempt(m.getExtensionEnabledCameraSelector(base, ExtensionMode.HDR), true, false)
            extActive = ok
        }
        if (!ok) {
            val sel = macroSel ?: base
            ok = attempt(sel, true, true) || attempt(sel, false, true) || attempt(base, false, false)
        }
        val cam = camera
        if (!ok || cam == null) {
            message = "No se pudo abrir la cámara"
            return
        }
        if (!extActive) liveInfo = ""

        cam.cameraInfo.zoomState.observe(o) { z ->
            minZoom = z.minZoomRatio
            maxZoom = z.maxZoomRatio
            zoom = z.zoomRatio
        }
        readCaps(cam)

        // Exposición inicial según el modo (Paisaje = un poco más oscuro)
        val es = cam.cameraInfo.exposureState
        if (es.isExposureCompensationSupported && evStep > 0f) {
            val idx = (pendingEv / evStep).roundToInt().coerceIn(evMin, evMax)
            evIndex = idx
            cam.cameraControl.setExposureCompensationIndex(idx)
        } else {
            evIndex = 0
        }

        // Macro sin lente dedicada: arrancar en la lente más ancha (enfoca más cerca)
        val mz = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1f
        if (mode == Mode.MACRO && !dedicatedMacro && mz < 1f) cam.cameraControl.setZoomRatio(mz)

        macroInfo = when {
            minFocusDiopter <= 0f -> "Esta lente tiene foco fijo: no enfoca de cerca."
            else -> {
                val cm = (100f / minFocusDiopter).roundToInt()
                (if (dedicatedMacro) "Lente cercana detectada. " else "") + "Enfoque mínimo de esta lente: ~$cm cm."
            }
        }
        applyOptions()
    }

    private fun readCaps(cam: Camera) {
        try {
            val ci = Camera2CameraInfo.from(cam.cameraInfo)
            ci.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
                isoMin = it.lower; isoMax = it.upper
            }
            ci.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
                shutLo = max(it.lower.toDouble(), 125_000.0)
                shutHi = min(it.upper.toDouble(), 1_000_000_000.0)
                if (shutHi <= shutLo) shutHi = shutLo * 2
            }
            minFocusDiopter = ci.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        } catch (e: Exception) {
            minFocusDiopter = 0f
        }
        iso = iso.coerceIn(isoMin, max(isoMin, isoMax))
        shutterNs = shutterNs.coerceIn(shutLo.toLong(), shutHi.toLong())
        focusDiopter = focusDiopter.coerceIn(0f, max(0f, minFocusDiopter))

        val es = cam.cameraInfo.exposureState
        if (es.isExposureCompensationSupported) {
            evMin = es.exposureCompensationRange.lower
            evMax = es.exposureCompensationRange.upper
            val st = es.exposureCompensationStep.toFloat()
            evStep = if (st > 0f) st else 1f / 3f
        } else {
            evMin = 0; evMax = 0
        }
    }

    // ---- Opciones avanzadas (Camera2) ----
    private fun applyOptions() {
        val cam = camera ?: return
        if (extActive) return
        try {
            val b = CaptureRequestOptions.Builder()
            if (manualExposure) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNs)
            }
            if (mode == Mode.PRO && wb != WB.AUTO) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, wb.mode)
            }
            if (manualFocus && minFocusDiopter > 0f) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopter)
            }
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
        } catch (e: Exception) {
            // Si el teléfono no soporta una opción, se ignora sin cerrar la app.
        }
    }

    // ---- Acciones de la interfaz ----
    fun setMode(m: Mode) {
        if (m == mode) return
        mode = m
        pendingEv = if (m == Mode.PAISAJE) -0.7f else 0f
        if (m == Mode.PAISAJE) hdr = true
        autoFocus = true
        rebind()
    }

    fun flip() {
        front = !front
        if (front && mode == Mode.MACRO) mode = Mode.AUTO
        rebind()
    }

    fun toggleHdr() {
        if (!hdrCapable) return
        hdr = !hdr
        rebind()
    }

    fun cycleFlash() {
        flash = when (flash) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flash
    }

    fun cycleTimer() {
        timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 }
    }

    fun setZoom(z: Float) {
        val c = z.coerceIn(minZoom, maxZoom)
        zoom = c
        camera?.cameraControl?.setZoomRatio(c)
    }

    fun setEv(index: Int) {
        val i = index.coerceIn(evMin, evMax)
        evIndex = i
        camera?.cameraControl?.setExposureCompensationIndex(i)
    }

    private fun enterManualExposure() {
        if (!autoExposure) return
        autoExposure = false
        if (liveIsoV > 0) iso = liveIsoV.coerceIn(isoMin, max(isoMin, isoMax))
        if (liveTimeV > 0) shutterNs = liveTimeV.coerceIn(shutLo.toLong(), shutHi.toLong())
    }

    fun setAutoExposure() {
        autoExposure = true
        applyOptions()
    }

    fun isoNorm(): Float =
        ((ln(iso.toDouble()) - ln(isoMin.toDouble())) / (ln(max(isoMax, isoMin + 1).toDouble()) - ln(isoMin.toDouble())))
            .toFloat().coerceIn(0f, 1f)

    fun setIsoNorm(t: Float) {
        enterManualExposure()
        val lo = ln(isoMin.toDouble())
        val hi = ln(max(isoMax, isoMin + 1).toDouble())
        iso = exp(lo + t * (hi - lo)).roundToInt().coerceIn(isoMin, max(isoMin, isoMax))
        applyOptions()
    }

    fun shutterNorm(): Float =
        ((ln(shutterNs.toDouble()) - ln(shutLo)) / (ln(shutHi) - ln(shutLo))).toFloat().coerceIn(0f, 1f)

    fun setShutterNorm(t: Float) {
        enterManualExposure()
        shutterNs = exp(ln(shutLo) + t * (ln(shutHi) - ln(shutLo))).toLong()
        applyOptions()
    }

    fun setWb(w: WB) {
        wb = w
        applyOptions()
    }

    fun setAutoFocus() {
        autoFocus = true
        applyOptions()
    }

    fun setFocus(d: Float) {
        if (autoFocus && liveFocusV > 0f) focusDiopter = liveFocusV
        autoFocus = false
        focusDiopter = d.coerceIn(0f, max(0f, minFocusDiopter))
        applyOptions()
    }

    fun tapFocus(x: Float, y: Float) {
        val cam = camera ?: return
        if (!autoFocus) setAutoFocus()
        try {
            val pt = previewView.meteringPointFactory.createPoint(x, y)
            val flags = if (manualExposure) FocusMeteringAction.FLAG_AF
            else FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            cam.cameraControl.startFocusAndMetering(
                FocusMeteringAction.Builder(pt, flags)
                    .setAutoCancelDuration(4, TimeUnit.SECONDS)
                    .build()
            )
        } catch (e: Exception) { }
    }

    // ---- Captura ----
    fun takePhoto() {
        if (busy || imageCapture == null) return
        busy = true
        scope.launch {
            var t = timer
            while (t > 0) {
                countdown = t
                delay(1000)
                t--
            }
            countdown = 0
            shoot()
        }
    }

    private fun shoot() {
        val cap = imageCapture ?: run { busy = false; return }
        val name = "PRO_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ProCam")
        }
        val opts = ImageCapture.OutputFileOptions.Builder(
            ctx.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv
        ).build()
        cap.takePicture(opts, main, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                busy = false
                val uri = r.savedUri
                lastPhoto = uri
                message = "Foto guardada en Galería › ProCam"
                if (uri != null) loadThumb(uri)
            }

            override fun onError(e: ImageCaptureException) {
                busy = false
                message = "Error al guardar: ${e.message}"
            }
        })
    }

    private fun loadThumb(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 200) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                if (bmp != null) lastThumb = bmp
            } catch (e: Exception) { }
        }
    }
}
