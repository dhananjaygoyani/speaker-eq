package io.github.dhananjaygoyani.speakereq

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.DynamicsProcessing
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.sqrt

/** Play a signal through the current output (the Bluetooth speaker) while recording the phone mic. */
object AudioIO {
    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO
    fun playRecord(ctx: Context, sig: DoubleArray): DoubleArray {
        val fs = Dsp.FS
        val pad = Dsp.ms(300.0)
        val out = FloatArray(pad + sig.size + pad)
        for (i in sig.indices) out[pad + i] = sig[i].toFloat()

        val am = ctx.getSystemService(AudioManager::class.java)
        val source = if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
            MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION // least processing
        val minRec = AudioRecord.getMinBufferSize(fs, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = AudioRecord(source, fs, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, max(minRec, fs))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw Dsp.MeasureError("Microphone is not available.") }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(fs)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(AudioTrack.getMinBufferSize(fs, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT), fs))
            .build()

        val buf = FloatArray(out.size + 3 * fs)
        var count = 0
        val stop = AtomicBoolean(false)
        try {
            rec.startRecording()
            val reader = thread {
                while (!stop.get() && count < buf.size) {
                    val n = rec.read(buf, count, minOf(4096, buf.size - count), AudioRecord.READ_BLOCKING)
                    if (n <= 0) break
                    count += n
                }
            }
            Thread.sleep(500)
            track.play()
            track.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
            val deadline = System.currentTimeMillis() + 3000
            while (track.playbackHeadPosition < out.size && System.currentTimeMillis() < deadline) Thread.sleep(20)
            Thread.sleep(1000) // Bluetooth adds 100-300 ms latency; keep recording past it
            stop.set(true)
            reader.join(2000) // count is read only after the reader has finished
        } finally {
            track.release()
            rec.stop()
            rec.release()
        }
        val skip = Dsp.ms(300.0) // drop the mic start-up click; playback starts 500 ms in
        return DoubleArray(max(0, count - skip)) { buf[skip + it].toDouble() }
    }

    fun bluetoothConnected(ctx: Context) = ctx.getSystemService(AudioManager::class.java)
        .getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
}

/** Tone sliders: (label, frequency label, filter kind, frequency, Q), same as the desktop app. */
class Tone(val name: String, val hz: String, val kind: String, val f: Double, val q: Double)
val TONES = listOf(
    Tone("Bass", "100 Hz", "LSC", 100.0, 0.71),
    Tone("Warmth", "300 Hz", "PK", 300.0, 1.0),
    Tone("Vocals", "1 kHz", "PK", 1000.0, 1.0),
    Tone("Clarity", "3 kHz", "PK", 3000.0, 1.0),
    Tone("Treble", "8 kHz", "HSC", 8000.0, 0.71),
)

data class Saved(
    val auto: List<Dsp.Filter> = emptyList(),
    val dev: List<Double>? = null,
    val amount: Float = 100f,
    val enabled: Boolean = true,
    val autoOn: Boolean = true,
    val moving: Boolean = false,
    val sliders: Map<String, Float> = TONES.associate { it.name to 0f },
    val diag: String = "",
) {
    fun filters(): List<Dsp.Filter> =
        (if (autoOn) auto.map { it.copy(g = it.g * amount / 100) } else emptyList()) +
            TONES.map { Dsp.Filter(it.f, (sliders[it.name] ?: 0f).toDouble(), it.q, it.kind) }
}

object Store {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("speaker_eq", Context.MODE_PRIVATE)

    fun load(ctx: Context): Saved = try {
        val j = JSONObject(prefs(ctx).getString("state", null) ?: return Saved())
        val auto = j.getJSONArray("auto").let { a ->
            List(a.length()) { a.getJSONArray(it).let { f -> Dsp.Filter(f.getDouble(0), f.getDouble(1), f.getDouble(2)) } }
        }
        val dev = j.optJSONArray("dev")?.let { a -> List(a.length()) { a.getDouble(it) } }
        val sl = j.getJSONObject("sliders")
        Saved(auto, dev, j.getDouble("amount").toFloat(), j.getBoolean("enabled"), j.getBoolean("autoOn"),
            j.getBoolean("moving"), TONES.associate { it.name to sl.optDouble(it.name, 0.0).toFloat() }, j.getString("diag"))
    } catch (e: org.json.JSONException) {
        Saved() // unreadable state: start from defaults
    }

    fun save(ctx: Context, s: Saved) {
        val j = JSONObject()
            .put("auto", JSONArray(s.auto.map { JSONArray(listOf(it.f, it.g, it.q)) }))
            .put("dev", s.dev?.let { JSONArray(it) })
            .put("amount", s.amount.toDouble()).put("enabled", s.enabled).put("autoOn", s.autoOn)
            .put("moving", s.moving).put("sliders", JSONObject(s.sliders.mapValues { it.value.toDouble() }))
            .put("diag", s.diag)
        prefs(ctx).edit().putString("state", j.toString()).apply()
    }
}

/**
 * System-wide EQ through DynamicsProcessing: 32 pre-EQ bands (1/3 octave) following our parametric curve,
 * loudness-matched input gain and a -1 dBFS limiter. Session 0 = global output mix (blocked on some phones);
 * players that broadcast an audio session also get their own instance.
 */
object EqEngine {
    private const val BANDS = 32
    private val centers = DoubleArray(BANDS) { 20.0 * Math.pow(1000.0, it / (BANDS - 1.0)) }
    private val effects = mutableMapOf<Int, DynamicsProcessing>()
    private var curve = DoubleArray(Dsp.GRID.size)
    private var preamp = 0.0
    private var enabled = false
    private var bypass = false
    var globalOk = false
        private set

    @Synchronized fun apply(s: Saved) {
        val f = s.filters()
        curve = Dsp.peqDb(f)
        preamp = Dsp.loudnessGain(f)
        enabled = s.enabled
        effects.values.forEach(::configure)
    }

    @Synchronized fun setBypass(on: Boolean) { bypass = on; effects.values.forEach(::configure) }

    @Synchronized fun attach(session: Int) {
        if (session in effects) return
        try {
            val cfg = DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                true, BANDS, false, 0, false, 0, true).build()
            val dp = DynamicsProcessing(0, session, cfg)
            effects[session] = dp
            configure(dp)
            if (session == 0) globalOk = true
        } catch (e: RuntimeException) { // UnsupportedOperationException / IllegalStateException on locked-down phones
            if (session == 0) globalOk = false
        }
    }

    @Synchronized fun detach(session: Int) { effects.remove(session)?.release() }

    @Synchronized fun releaseAll() { effects.values.forEach { it.release() }; effects.clear(); globalOk = false }

    /** Band i spans (cutoff[i-1], cutoff[i]]; its gain is our curve at the band centre. */
    fun bandGains(c: DoubleArray): List<Pair<Float, Float>> = List(BANDS) { i ->
        val cutoff = if (i < BANDS - 1) sqrt(centers[i] * centers[i + 1]) else 20000.0
        val g = Dsp.GRID.indexOfFirst { it >= centers[i] }.let { if (it < 0) c.last() else c[it] }
        cutoff.toFloat() to g.toFloat()
    }

    private fun configure(dp: DynamicsProcessing) {
        val eq = DynamicsProcessing.Eq(true, true, BANDS)
        bandGains(curve).forEachIndexed { i, (cut, g) -> eq.setBand(i, DynamicsProcessing.EqBand(true, cut, g)) }
        dp.setPreEqAllChannelsTo(eq)
        dp.setInputGainAllChannelsTo(preamp.toFloat())
        dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f))
        dp.enabled = enabled && !bypass
    }
}
