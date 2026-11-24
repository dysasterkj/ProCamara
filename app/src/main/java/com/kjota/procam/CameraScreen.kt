package com.kjota.procam

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.ComponentActivity
import androidx.camera.core.ImageCapture
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

private val Amber = Color(0xFFFFC107)
private val Panel = Color(0xFF2A2A2A)

@Composable
fun CameraScreen(onCam: (ProCamera) -> Unit) {
    val ctx = LocalContext.current
    val cam = remember { ProCamera(ctx) }
    DisposableEffect(cam) {
        onCam(cam)
        cam.start(ctx as ComponentActivity)
        onDispose { cam.release() }
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()
    ) {
        TopBar(cam)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val w = minOf(maxWidth, maxHeight * 3f / 4f)
            Viewfinder(cam, Modifier.size(w, w * 4f / 3f))
        }
        ControlsPanel(cam)
        ModeSelector(cam)
        ShutterRow(cam)
    }
}

// ---------- Piezas pequeñas ----------

@Composable
fun Pill(text: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = if (active) Amber else Panel
    val fg = if (active) Color.Black else if (enabled) Color.White else Color(0xFF777777)
    Text(
        text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(CircleShape).background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = Amber, activeTrackColor = Amber, inactiveTrackColor = Color(0xFF555555)
)

@Composable
fun LabeledSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>,
    steps: Int = 0, onChange: (Float) -> Unit
) {
    if (range.endInclusive <= range.start) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(96.dp))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange,
            valueRange = range, steps = steps, colors = sliderColors(), modifier = Modifier.weight(1f)
        )
    }
}

private fun fmtZoom(z: Float): String =
    if (abs(z - z.roundToInt()) < 0.05f) "${z.roundToInt()}x" else String.format(Locale.US, "%.1fx", z)

private fun fmtEv(v: Float): String = String.format(Locale.US, "%+.1f", v)

private fun fmtFocus(d: Float): String = if (d < 0.05f) "∞" else "${(100f / d).roundToInt()} cm"

// ---------- Barra superior ----------

@Composable
fun TopBar(cam: ProCamera) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Pill(
            when (cam.flash) {
                ImageCapture.FLASH_MODE_AUTO -> "⚡ Auto"
                ImageCapture.FLASH_MODE_ON -> "⚡ Sí"
                else -> "⚡ No"
            }, active = cam.flash != ImageCapture.FLASH_MODE_OFF
        ) { cam.cycleFlash() }
        Pill(
            "HDR", active = cam.hdr && cam.hdrCapable,
            enabled = cam.hdrCapable && (cam.mode == Mode.AUTO || cam.mode == Mode.PAISAJE)
        ) { cam.toggleHdr() }
        Pill("⏱ ${if (cam.timer == 0) "No" else "${cam.timer}s"}", active = cam.timer > 0) { cam.cycleTimer() }
        Pill("▦ Guías", active = cam.guides) { cam.guides = !cam.guides }
    }
}

// ---------- Visor ----------

@Composable
fun rememberRoll(enabled: Boolean): FloatState {
    val ctx = LocalContext.current
    val roll = remember { mutableFloatStateOf(0f) }
    DisposableEffect(enabled) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val a = Math.toDegrees(atan2(e.values[0].toDouble(), e.values[1].toDouble())).toFloat()
                roll.floatValue = roll.floatValue * 0.8f + a * 0.2f
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        if (enabled && sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    return roll
}

@Composable
fun Viewfinder(cam: ProCamera, modifier: Modifier) {
    var ring by remember { mutableStateOf<Offset?>(null) }
    var ringKey by remember { mutableIntStateOf(0) }
    val roll = rememberRoll(cam.guides)

    LaunchedEffect(ringKey) {
        kotlinx.coroutines.delay(1300)
        ring = null
    }
    LaunchedEffect(cam.message) {
        if (cam.message != null) {
            kotlinx.coroutines.delay(2500)
            cam.message = null
        }
    }

    Box(modifier.clip(RoundedCornerShape(10.dp))) {
        AndroidView(factory = { cam.previewView }, modifier = Modifier.fillMaxSize())

        // Gestos: toque = enfocar, pellizco = zoom
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { off ->
                        cam.tapFocus(off.x, off.y)
                        ring = off
                        ringKey++
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, z, _ ->
                        if (z != 1f) cam.zoomTo(cam.zoom * z)
                    }
                }
        )

        Canvas(Modifier.fillMaxSize()) {
            if (cam.guides) {
                val g = Color(0x55FFFFFF)
                for (i in 1..2) {
                    drawLine(g, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), 1.5f)
                    drawLine(g, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), 1.5f)
                }
                val r = roll.floatValue
                if (abs(r) < 20f) {
                    val c = if (abs(r) < 1.2f) Amber else Color(0x99FFFFFF)
                    rotate(r, pivot = center) {
                        drawLine(c, Offset(size.width * 0.3f, center.y), Offset(size.width * 0.7f, center.y), 3f)
                    }
                }
            }
            ring?.let { drawCircle(Amber, radius = 42.dp.toPx(), center = it, style = Stroke(3f)) }
        }

        // Datos en vivo
        if (cam.liveInfo.isNotEmpty()) {
            Text(
                cam.liveInfo, color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    .background(Color(0x66000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Text(
            fmtZoom(cam.zoom), color = Color.White, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                .background(Color(0x66000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
        )

        // Botones de zoom: gran angular / 1x / 2x / 4x
        val opts = buildList {
            if (cam.minZoom < 0.95f) add(cam.minZoom)
            add(1f)
            if (cam.maxZoom >= 2f) add(2f)
            if (cam.maxZoom >= 4f) add(4f)
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
                .background(Color(0x66000000), CircleShape).padding(4.dp)
        ) {
            opts.forEach { z ->
                val sel = abs(cam.zoom - z) < 0.06f
                Text(
                    fmtZoom(z), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = if (sel) Color.Black else Color.White,
                    modifier = Modifier.clip(CircleShape)
                        .background(if (sel) Amber else Color.Transparent)
                        .clickable { cam.zoomTo(z) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        if (cam.countdown > 0) {
            Text(
                "${cam.countdown}", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        cam.message?.let {
            Text(
                it, color = Color.White, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

// ---------- Panel de controles según el modo ----------

@Composable
fun ControlsPanel(cam: ProCamera) {
    Column(Modifier.fillMaxWidth().height(116.dp).padding(horizontal = 14.dp, vertical = 4.dp)) {
        when (cam.mode) {
            Mode.AUTO, Mode.PAISAJE -> {
                EvSlider(cam)
                Text(
                    if (cam.mode == Mode.PAISAJE)
                        "Paisaje: exposición un poco más baja + HDR para conservar el cielo y las luces del fondo."
                    else "Toca la pantalla para enfocar. Pellizca para hacer zoom.",
                    color = Color(0xFFAAAAAA), fontSize = 12.sp
                )
            }
            Mode.PRO -> ProPanel(cam)
            Mode.MACRO -> MacroPanel(cam)
        }
    }
}

@Composable
fun EvSlider(cam: ProCamera) {
    if (cam.evMax > cam.evMin) {
        LabeledSlider(
            "Luz ${fmtEv(cam.evIndex * cam.evStep)}", cam.evIndex.toFloat(),
            cam.evMin.toFloat()..cam.evMax.toFloat(), steps = cam.evMax - cam.evMin - 1
        ) { cam.setEv(it.roundToInt()) }
    } else {
        Text("Este teléfono no permite ajustar la exposición.", color = Color(0xFFAAAAAA), fontSize = 12.sp)
    }
}

@Composable
fun ProPanel(cam: ProCamera) {
    var sel by remember { mutableStateOf("EV") }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("EV", "ISO", "OBT", "BAL", "ENF").forEach { Pill(it, active = sel == it) { sel = it } }
    }
    Box(Modifier.padding(top = 4.dp)) {
        when (sel) {
            "EV" -> if (cam.autoExposure) EvSlider(cam)
            else Text("Exposición manual activa (ISO y obturador). Toca AUTO en ISO u OBT para volver.", color = Color(0xFFAAAAAA), fontSize = 12.sp)
            "ISO" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Pill("AUTO", active = cam.autoExposure) { cam.setAutoExposure() }
                Box(Modifier.weight(1f)) {
                    LabeledSlider("ISO ${cam.iso}", cam.isoNorm(), 0f..1f) { cam.setIsoNorm(it) }
                }
            }
            "OBT" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Pill("AUTO", active = cam.autoExposure) { cam.setAutoExposure() }
                Box(Modifier.weight(1f)) {
                    LabeledSlider(fmtShutter(cam.shutterNs), cam.shutterNorm(), 0f..1f) { cam.setShutterNorm(it) }
                }
            }
            "BAL" -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WB.values().forEach { w -> Pill(w.label, active = cam.wb == w) { cam.changeWb(w) } }
            }
            else -> FocusRow(cam)
        }
    }
}

@Composable
fun FocusRow(cam: ProCamera) {
    if (cam.minFocusDiopter <= 0f) {
        Text("Esta lente tiene foco fijo.", color = Color(0xFFAAAAAA), fontSize = 12.sp)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("AF", active = cam.autoFocus) { cam.setAutoFocus() }
            Box(Modifier.weight(1f)) {
                LabeledSlider(
                    if (cam.autoFocus) "Auto" else fmtFocus(cam.focusDiopter),
                    cam.focusDiopter, 0f..cam.minFocusDiopter
                ) { cam.setFocus(it) }
            }
        }
    }
}

@Composable
fun MacroPanel(cam: ProCamera) {
    Text(cam.macroInfo, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    FocusRow(cam)
    Text(
        "Si no enfoca: aléjate 15–25 cm, usa zoom 2x–4x y luego recorta la foto. Con mucha luz sale más nítido.",
        color = Color(0xFFAAAAAA), fontSize = 11.sp
    )
}

// ---------- Modos y disparador ----------

@Composable
fun ModeSelector(cam: ProCamera) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Mode.values().forEach { m ->
            Text(
                m.label, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                color = if (cam.mode == m) Amber else Color(0xFFBBBBBB),
                modifier = Modifier.clickable { cam.changeMode(m) }.padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
fun ShutterRow(cam: ProCamera) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(54.dp).clip(RoundedCornerShape(10.dp)).background(Panel).clickable {
                cam.lastPhoto?.let { uri ->
                    try {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        )
                    } catch (e: Exception) { }
                }
            }
        ) {
            cam.lastThumb?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }

        Box(
            Modifier.size(78.dp).border(4.dp, Color.White, CircleShape).padding(7.dp)
                .clip(CircleShape).background(if (cam.busy) Color(0xFF888888) else Color.White)
                .clickable(enabled = !cam.busy) { cam.takePhoto() }
        )

        Text(
            "🔄", fontSize = 24.sp,
            modifier = Modifier.size(54.dp).clip(CircleShape).background(Panel)
                .clickable { cam.flip() }.padding(top = 11.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
