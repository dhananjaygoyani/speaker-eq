package io.github.dhananjaygoyani.speakereq

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.concurrent.thread
import kotlin.math.ln
import kotlin.math.roundToInt

private val BG = Color(0xFFF2F2F7)
private val CARD = Color.White
private val TEXT = Color(0xFF1C1C1E)
private val SUB = Color(0xFF8E8E93)
private val BLUE = Color(0xFF007AFF)
private val GREEN = Color(0xFF34C759)
private val ORANGE = Color(0xFFFF9500)
private val TRACK = Color(0xFFE5E5EA)
private val OFF = Color(0xFFE9E9EB)
private val DISABLED = Color(0xFFC7C7CC)

class MainActivity : ComponentActivity() {
    private var state by mutableStateOf(Saved())
    private var status by mutableStateOf("Ready")
    private var measuring by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = Store.load(this)
        startForegroundService(Intent(this, EqService::class.java))
        setContent { Screen() }
    }

    override fun onResume() {
        super.onResume()
        // the service attaches the global effect asynchronously after start; report once it had the chance
        window.decorView.postDelayed({ if (status == "Ready") status = eqStatus(state) }, 800) // keep import/measure messages
    }

    private fun eqStatus(s: Saved) = when {
        !s.enabled -> "Equalizer off"
        EqEngine.globalOk -> "EQ on for all apps · loudness matched · limiter on"
        else -> "This phone blocks system-wide EQ: works in players with an equalizer setting"
    }

    private fun update(s: Saved) {
        state = s
        Store.save(this, s)
        EqEngine.apply(s)
        status = eqStatus(s)
    }

    private fun measure() {
        measuring = true
        EqEngine.setBypass(true) // measure the bare speaker, not our own EQ
        val moving = state.moving
        thread {
            var result: Dsp.Result? = null
            var error: String? = null
            var level = 1.0
            try {
                for (attempt in 0..3) {
                    try {
                        result = Dsp.run({ s -> AudioIO.playRecord(this, DoubleArray(s.size) { s[it] * level }) },
                            positions = if (moving) 5 else 3, fixed = !moving,
                            progress = { msg -> runOnUiThread { status = msg } })
                        break
                    } catch (c: Dsp.Clipped) { // phone mic gain can't be changed: play quieter instead
                        if (attempt == 3) error = "Recording clipped: turn the volume down or move the phone further away."
                        level *= 0.5
                        runOnUiThread { status = "Recording clipped: playing quieter and measuring again…" }
                    }
                }
            } catch (e: Dsp.MeasureError) {
                error = e.message
            } catch (e: Exception) {
                error = "Measurement failed: $e"
            }
            runOnUiThread {
                measuring = false
                EqEngine.setBypass(false)
                val r = result
                if (r != null) {
                    update(state.copy(auto = r.filters, dev = r.dev.toList(), diag = r.text, autoOn = true))
                    status = "Auto-tune done, EQ applied"
                } else {
                    state = state.copy(diag = error ?: "Measurement failed")
                    status = "Measurement failed, see Diagnosis"
                }
            }
        }
    }

    /** Fire TV has no file picker: take the newest EQ text file in Downloads (e.g. saved by the Downloader app). */
    private fun importFromDownloads() {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt", true) && f.length() < 100_000 }
            ?.sortedByDescending { it.lastModified() }?.firstOrNull { Dsp.parseApo(it.readText()).isNotEmpty() }
        if (file == null) {
            state = state.copy(diag = "No EQ file found in Downloads. Export the tuning from Speaker EQ on your phone or " +
                "computer, put the file in this device's Download folder (for example with the Downloader app), " +
                "then press Import again.")
            status = "Nothing to import"
            return
        }
        importText(file.readText(), file.name)
    }

    private fun importText(text: String, name: String) {
        val f = Dsp.parseApo(text)
        if (f.isEmpty()) { status = "No EQ filters found in $name"; return }
        update(state.copy(auto = f, dev = null, autoOn = true, amount = 100f,
            diag = "Imported ${f.size} filters from $name (measured on another device).\nThe Tone sliders still work on top."))
        status = "Imported ${f.size} filters · ${eqStatus(state)}"
    }

    @Composable
    private fun Screen() {
        var confirm by remember { mutableStateOf(false) }
        var noBluetooth by remember { mutableStateOf(false) }
        val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) confirm = true else status = "Microphone permission is needed to measure"
        }
        val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { it.write(Dsp.apoText(state.filters()).toByteArray()) }
                status = "Exported. Import it in Wavelet or Equalizer APO"
            }
        }
        val importDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) contentResolver.openInputStream(uri)?.use { importText(it.readBytes().decodeToString(), "the file") }
        }
        val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) importFromDownloads() else status = "Storage permission is needed to import from Downloads"
        }
        val s = state

        Column(
            Modifier.fillMaxSize().background(BG).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(top = 24.dp, bottom = 32.dp)
        ) {
            Text("Speaker EQ", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = TEXT)
            Text(status, fontSize = 13.sp, color = SUB, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))
            Pill(if (measuring) "Measuring…" else "Measure & Auto-Tune", enabled = !measuring) {
                when {
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    !AudioIO.bluetoothConnected(this@MainActivity) -> noBluetooth = true
                    else -> confirm = true
                }
            }
            Row(Modifier.padding(top = 10.dp, bottom = 4.dp)) {
                Link("Reset") { update(s.copy(amount = 100f, sliders = TONES.associate { it.name to 0f })) }
                Link("Import…") {
                    try {
                        importDoc.launch(arrayOf("text/*"))
                    } catch (e: ActivityNotFoundException) { // Fire TV / Android TV: no document picker
                        if (Build.VERSION.SDK_INT <= 32 &&
                            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                        ) storagePermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE) else importFromDownloads()
                    }
                }
                Link("Export…") { export.launch("SpeakerEQ_APO.txt") }
            }

            Card {
                SwitchRow("Auto-Tune", "Corrects what the measurement found", s.autoOn) { update(s.copy(autoOn = it)) }
                SliderRow("Strength", "", s.amount, 0f..100f, 5f, 100f, false, s.autoOn, { "${it.roundToInt()}%" }) {
                    update(s.copy(amount = it))
                }
                Sep()
                SwitchRow("Equalizer", "All sound processing on or off", s.enabled) { update(s.copy(enabled = it)) }
                Sep()
                SwitchRow("Moving While Measuring", "Off if phone and speaker stay still", s.moving) {
                    update(s.copy(moving = it))
                }
            }

            Section("TONE")
            Card {
                TONES.forEachIndexed { i, t ->
                    if (i > 0) Sep()
                    val v = s.sliders[t.name] ?: 0f
                    SliderRow(t.name, t.hz, v, -12f..12f, 0.5f, 0f, true, true,
                        { if (it == 0f) "0 dB" else "%+.1f dB".format(it) }) {
                        update(s.copy(sliders = s.sliders + (t.name to it)))
                    }
                }
            }

            Section("FREQUENCY RESPONSE")
            Card { Graph(s) }

            Section("DIAGNOSIS")
            Card {
                Text(
                    s.diag.ifEmpty {
                        "Connect your Bluetooth speaker, hold the phone where you listen (0.5–1 m away), set your normal " +
                            "volume and tap \"Measure & Auto-Tune\". Set \"Moving While Measuring\" to match what you'll do.\n" +
                            "Then shape the sound with the Tone sliders (double-tap a slider to reset it)."
                    },
                    fontSize = 12.sp, color = TEXT, fontFamily = if (s.diag.isEmpty()) FontFamily.Default else FontFamily.Monospace,
                    lineHeight = 17.sp
                )
            }
        }

        if (noBluetooth) AlertDialog(
            onDismissRequest = { noBluetooth = false },
            title = { Text("No Bluetooth speaker") },
            text = { Text("Connect your speaker first, or measure the phone's current output anyway.") },
            confirmButton = { TextButton({ noBluetooth = false; confirm = true }) { Text("Measure anyway") } },
            dismissButton = { TextButton({ noBluetooth = false }) { Text("Cancel") } },
        )
        if (confirm) AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Auto-Tune") },
            text = {
                Text("The speaker will play test sweeps for about a minute. Quiet room, normal listening volume. " +
                    if (s.moving) "Keep moving the phone slowly around where you sit." else "Keep the phone and speaker completely still.")
            },
            confirmButton = { TextButton({ confirm = false; measure() }) { Text("Start") } },
            dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
        .background(CARD).padding(16.dp), content = content
)

@Composable
private fun Section(text: String) =
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SUB, modifier = Modifier.padding(start = 6.dp, top = 18.dp, bottom = 6.dp))

@Composable
private fun Sep() = Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(TRACK))

@Composable
private fun Link(text: String, onClick: () -> Unit) =
    Text(text, color = BLUE, fontSize = 15.sp, modifier = Modifier.clickable(onClick = onClick).padding(end = 18.dp, top = 4.dp, bottom = 4.dp))

@Composable
private fun Pill(text: String, enabled: Boolean, onClick: () -> Unit) = Box(
    Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(25.dp)).background(if (enabled) BLUE else DISABLED)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    contentAlignment = Alignment.Center
) { Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TEXT)
            Text(subtitle, fontSize = 12.sp, color = SUB)
        }
        AppleSwitch(checked, onChange)
    }
}

@Composable
private fun AppleSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val pos by animateFloatAsState(if (checked) 1f else 0f, label = "switch")
    val track by animateColorAsState(if (checked) GREEN else OFF, label = "track")
    Box(
        Modifier.size(51.dp, 31.dp).clip(RoundedCornerShape(16.dp)).background(track)
            .clickable(role = Role.Switch) { onChange(!checked) }
            .semantics { stateDescription = if (checked) "On" else "Off" }
            .padding(2.dp)
    ) {
        Box(Modifier.offset(x = 20.dp * pos).size(27.dp).shadow(2.dp, CircleShape).background(Color.White, CircleShape))
    }
}

@Composable
private fun SliderRow(
    title: String, hint: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, default: Float,
    bipolar: Boolean, enabled: Boolean, fmt: (Float) -> String, onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, fontSize = 15.sp, color = TEXT)
            Text(hint, fontSize = 12.sp, color = SUB, modifier = Modifier.padding(start = 6.dp).weight(1f))
            Text(fmt(value), fontSize = 14.sp, color = SUB)
        }
        AppleSlider(value, range, step, default, bipolar, enabled, onChange)
    }
}

/** Thin track, blue fill (from 0 for +/- sliders), round white knob. Drag, tap; double-tap resets. */
@Composable
private fun AppleSlider(
    value: Float, range: ClosedFloatingPointRange<Float>, step: Float, default: Float, bipolar: Boolean,
    enabled: Boolean, onChange: (Float) -> Unit,
) {
    val change by rememberUpdatedState(onChange)
    val current by rememberUpdatedState(value)
    var focused by remember { mutableStateOf(false) }
    val r = 13.dp
    fun snap(v: Float) = ((v / step).roundToInt() * step).coerceIn(range.start, range.endInclusive)
    Canvas(
        Modifier.fillMaxWidth().height(36.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, range)
                if (enabled) setProgress { change(snap(it)); true }
            }
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { // TV remote: left/right adjusts, up/down moves on to the next control
                if (it.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (it.key) {
                    Key.DirectionLeft -> { change(snap(current - step)); true }
                    Key.DirectionRight -> { change(snap(current + step)); true }
                    else -> false
                }
            }
            .focusable(enabled)
            .pointerInput(enabled, range) {
                if (!enabled) return@pointerInput
                val rp = r.toPx()
                fun at(x: Float) = snap(range.start + (x - rp) / (size.width - 2 * rp) * (range.endInclusive - range.start))
                detectTapGestures(onDoubleTap = { change(default) }, onTap = { change(at(it.x)) })
            }
            .pointerInput(enabled, range) {
                if (!enabled) return@pointerInput
                val rp = r.toPx()
                fun at(x: Float) = snap(range.start + (x - rp) / (size.width - 2 * rp) * (range.endInclusive - range.start))
                detectHorizontalDragGestures { ch, _ -> change(at(ch.position.x)) }
            }
    ) {
        val rp = r.toPx()
        val cy = size.height / 2
        fun x(v: Float) = rp + (v - range.start) / (range.endInclusive - range.start) * (size.width - 2 * rp)
        drawLine(TRACK, Offset(rp, cy), Offset(size.width - rp, cy), 4.dp.toPx(), StrokeCap.Round)
        drawLine(if (enabled) BLUE else DISABLED, Offset(x(if (bipolar) 0f else range.start), cy), Offset(x(value), cy), 4.dp.toPx(), StrokeCap.Round)
        if (bipolar) drawLine(DISABLED, Offset(x(0f), cy - 5.dp.toPx()), Offset(x(0f), cy + 5.dp.toPx()), 1.dp.toPx())
        if (focused) drawCircle(BLUE.copy(alpha = 0.25f), rp + 6.dp.toPx(), Offset(x(value), cy)) // remote focus halo
        drawCircle(Color(0x22000000), rp, Offset(x(value), cy + 1.5.dp.toPx()))
        drawCircle(Color.White, rp, Offset(x(value), cy))
        drawCircle(if (focused) BLUE else Color(0xFFD8D8DC), rp, Offset(x(value), cy), style = Stroke((if (focused) 2 else 1).dp.toPx()))
    }
}

@Composable
private fun Graph(s: Saved) {
    val eq = if (s.enabled) Dsp.peqDb(s.filters()) else DoubleArray(Dsp.GRID.size)
    val dev = s.dev?.let { Dsp.oct3(it.toDoubleArray()) }
    Column {
        Row(Modifier.padding(bottom = 6.dp)) {
            Legend("Speaker", DISABLED); Legend("With EQ", BLUE); Legend("EQ", ORANGE)
        }
        Canvas(Modifier.fillMaxWidth().height(220.dp)) {
            val left = 34.dp.toPx()
            val bottom = size.height - 18.dp.toPx()
            fun x(f: Double) = left + (ln(f / 20) / ln(1000.0)).toFloat() * (size.width - left)
            fun y(db: Double) = ((15 - db) / 35).toFloat() * bottom
            val label = Paint().apply { color = SUB.toArgb(); textSize = 10.sp.toPx(); isAntiAlias = true }
            for (db in listOf(10, 0, -10, -20)) {
                drawLine(Color(0xFFEFEFF4), Offset(left, y(db.toDouble())), Offset(size.width, y(db.toDouble())), 1f)
                drawContext.canvas.nativeCanvas.drawText("$db", 0f, y(db.toDouble()) + 4.dp.toPx(), label)
            }
            for ((f, t) in listOf(20.0 to "20", 100.0 to "100", 1000.0 to "1k", 10000.0 to "10k", 20000.0 to "20k")) {
                drawLine(Color(0xFFEFEFF4), Offset(x(f), 0f), Offset(x(f), bottom), 1f)
                val w = label.measureText(t)
                drawContext.canvas.nativeCanvas.drawText(t, (x(f) - w / 2).coerceIn(left, size.width - w), size.height - 2.dp.toPx(), label)
            }
            drawLine(SUB, Offset(left, y(0.0)), Offset(size.width, y(0.0)), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            fun curve(v: DoubleArray, c: Color, w: Float) {
                val p = Path()
                Dsp.GRID.forEachIndexed { i, f ->
                    val yy = y(v[i]).coerceIn(0f, bottom)
                    if (i == 0) p.moveTo(x(f), yy) else p.lineTo(x(f), yy)
                }
                drawPath(p, c, style = Stroke(w))
            }
            if (dev != null) {
                curve(dev, DISABLED, 1.8.dp.toPx())
                curve(DoubleArray(dev.size) { dev[it] + eq[it] }, BLUE, 2.6.dp.toPx())
            }
            curve(eq, ORANGE, 1.8.dp.toPx())
        }
    }
}

@Composable
private fun Legend(text: String, c: Color) = Row(Modifier.padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(14.dp, 3.dp).background(c))
    Text(text, fontSize = 11.sp, color = TEXT, modifier = Modifier.padding(start = 5.dp))
}
