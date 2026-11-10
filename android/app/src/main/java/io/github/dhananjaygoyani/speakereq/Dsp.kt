package io.github.dhananjaygoyani.speakereq

import kotlin.math.*

/** Port of speaker_eq.py: sweep -> response, SNR, distortion, DSP check -> diagnosis -> parametric EQ. */
object Dsp {
    const val FS = 48000
    private const val F1 = 20.0
    private const val F2 = 20000.0
    private const val T = 6.0
    private const val MIN_SNR = 15.0
    private const val MAX_THD = 0.03
    val GRID = DoubleArray(400) { 20.0 * 1000.0.pow(it / 399.0) } // np.geomspace(20, 20000, 400)

    data class Filter(val f: Double, val g: Double, val q: Double, val kind: String = "PK")
    class Result(val filters: List<Filter>, val dev: DoubleArray, val text: String)
    class MeasureError(msg: String) : Exception(msg)
    class Clipped : Exception("clipped")

    private class Band(val lo: Double, val hi: Double, val name: String, val more: String, val less: String)
    private val BANDS = listOf(
        Band(20.0, 60.0, "sub-bass", "rumbly", "no deep bass"),
        Band(60.0, 250.0, "bass", "boomy", "thin"),
        Band(250.0, 500.0, "low-mids", "muddy/boxy", "hollow"),
        Band(500.0, 2000.0, "mids", "honky/nasal", "recessed vocals"),
        Band(2000.0, 6000.0, "presence", "harsh/shouty", "dull/veiled"),
        Band(6000.0, 20000.0, "treble", "sibilant/bright", "dark/no air"),
    )

    fun ms(t: Double) = (t * FS / 1000).toInt()

    private fun hanning(m: Int) = DoubleArray(m) { 0.5 - 0.5 * cos(2 * PI * it / (m - 1)) }

    fun sweep(): DoubleArray {
        val n = (T * FS).toInt()
        val r = ln(F2 / F1)
        val s = DoubleArray(n) { sin(2 * PI * F1 * T / r * (exp(it.toDouble() / FS * r / T) - 1)) }
        val f = ms(50.0)
        val h = hanning(2 * f)
        for (i in 0 until f) { s[i] *= h[i]; s[n - f + i] *= h[f + i] }
        for (i in 0 until n) s[i] *= 0.5 // -6 dBFS
        return s
    }

    /** In-place iterative radix-2 complex FFT (inverse includes the 1/n). */
    fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2 * PI / len * (if (inverse) 1 else -1)
            val half = len / 2
            val wr = DoubleArray(half) { cos(ang * it) } // table: no twiddle drift on 2^20-point FFTs
            val wi = DoubleArray(half) { sin(ang * it) }
            var i = 0
            while (i < n) {
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val tr = re[b] * wr[k] - im[b] * wi[k]
                    val ti = re[b] * wi[k] + im[b] * wr[k]
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
    }

    private fun searchSorted(a: DoubleArray, v: Double): Int { // numpy searchsorted, side='left'
        var lo = 0
        var hi = a.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (a[m] < v) lo = m + 1 else hi = m }
        return lo
    }

    private fun interp(x: Double, xs: DoubleArray, ys: DoubleArray): Double { // np.interp (clamped)
        if (x <= xs[0]) return ys[0]
        if (x >= xs.last()) return ys.last()
        var i = 0
        while (x > xs[i + 1]) i++
        return ys[i] + (ys[i + 1] - ys[i]) * (x - xs[i]) / (xs[i + 1] - xs[i])
    }

    private val SM_X = doubleArrayOf(ln(100.0), ln(1000.0), ln(10000.0))
    private val SM_Y = doubleArrayOf(24.0, 6.0, 3.0)

    /** Frequency-dependent fractional-octave power average: 1/24 oct < 100 Hz, 1/6 at 1 kHz, 1/3 > 10 kHz. */
    fun smooth(f: DoubleArray, power: DoubleArray, grid: DoubleArray): DoubleArray {
        val c = DoubleArray(power.size + 1)
        for (i in power.indices) c[i + 1] = c[i] + power[i]
        return DoubleArray(grid.size) { g ->
            val n = interp(ln(grid[g]), SM_X, SM_Y)
            val lo = min(searchSorted(f, grid[g] * 2.0.pow(-1 / (2 * n))), f.size - 1)
            val hi = max(searchSorted(f, grid[g] * 2.0.pow(1 / (2 * n))), lo + 1)
            (c[hi] - c[lo]) / (hi - lo)
        }
    }

    private const val M = 1 shl 16
    private val FREQS = DoubleArray(M / 2 + 1) { it.toDouble() * FS / M }

    private fun spectrum(h: DoubleArray, start: Int, length: Int, grid: DoubleArray): DoubleArray {
        val re = DoubleArray(M)
        val im = DoubleArray(M)
        for (i in 0 until length) re[i] = h[Math.floorMod(start + i, h.size)]
        val t = length / 5
        val w = hanning(2 * t)
        for (i in 0 until t) re[length - t + i] *= w[t + i]
        fft(re, im, false)
        val pw = DoubleArray(M / 2 + 1) { re[it] * re[it] + im[it] * im[it] }
        return smooth(FREQS, pw, grid)
    }

    fun oct3(v: DoubleArray, n: Int = 13): DoubleArray { // moving average, edge-padded
        val h = n / 2
        return DoubleArray(v.size) { i ->
            var s = 0.0
            for (k in -h..h) s += v[(i + k).coerceIn(0, v.size - 1)]
            s / n
        }
    }

    /** Deconvolve recording y by sweep x: (power on GRID, THD ratio on GRID). */
    fun response(x: DoubleArray, y: DoubleArray): Pair<DoubleArray, DoubleArray> {
        var n = 1
        while (n < x.size + y.size) n = n shl 1
        val xr = DoubleArray(n); x.copyInto(xr)
        val xi = DoubleArray(n)
        val yr = DoubleArray(n); y.copyInto(yr)
        val yi = DoubleArray(n)
        fft(xr, xi, false)
        fft(yr, yi, false)
        var mx = 0.0
        for (i in 0 until n) mx = max(mx, xr[i] * xr[i] + xi[i] * xi[i])
        val eps = 1e-6 * mx
        for (i in 0 until n) {
            val d = xr[i] * xr[i] + xi[i] * xi[i] + eps
            val hr = (yr[i] * xr[i] + yi[i] * xi[i]) / d
            val hi = (yi[i] * xr[i] - yr[i] * xi[i]) / d
            yr[i] = hr; yi[i] = hi
        }
        fft(yr, yi, true)
        val h = yr
        var p = 0
        for (i in 0 until n) if (abs(h[i]) > abs(h[p])) p = i // latency (incl. Bluetooth) found here
        val power = spectrum(h, p - ms(5.0), ms(205.0), GRID)
        val short = spectrum(h, p - ms(5.0), ms(55.0), GRID)
        val harm = DoubleArray(GRID.size)
        for (k in 2..3) { // harmonic k sits T*ln(k)/ln(F2/F1) s before the main peak
            val d = (T * ln(k.toDouble()) / ln(F2 / F1) * FS).toInt()
            val hk = spectrum(h, p - d - ms(5.0), ms(55.0), DoubleArray(GRID.size) { GRID[it] * k })
            for (i in GRID.indices) if (GRID[i] * k < F2) harm[i] += hk[i]
        }
        val hs = oct3(harm)
        val ss = oct3(short)
        return power to DoubleArray(GRID.size) { sqrt(hs[it] / (ss[it] + 1e-30)) }
    }

    /** RBJ-cookbook biquad (b, a): PK peaking, LSC low shelf, HSC high shelf. */
    fun biquad(f0: Double, g: Double, q: Double, kind: String = "PK"): Pair<DoubleArray, DoubleArray> {
        val a = 10.0.pow(g / 40)
        val w = 2 * PI * f0 / FS
        val al = sin(w) / (2 * q)
        val c = cos(w)
        if (kind == "PK") return doubleArrayOf(1 + al * a, -2 * c, 1 - al * a) to doubleArrayOf(1 + al / a, -2 * c, 1 - al / a)
        val s = if (kind == "LSC") 1.0 else -1.0
        val r = 2 * sqrt(a) * al
        return doubleArrayOf(a * ((a + 1) - s * (a - 1) * c + r), s * 2 * a * ((a - 1) - s * (a + 1) * c),
            a * ((a + 1) - s * (a - 1) * c - r)) to
            doubleArrayOf((a + 1) + s * (a - 1) * c + r, -s * 2 * ((a - 1) + s * (a + 1) * c), (a + 1) + s * (a - 1) * c - r)
    }

    private val COS1 = DoubleArray(GRID.size) { cos(2 * PI * GRID[it] / FS) }
    private val SIN1 = DoubleArray(GRID.size) { sin(2 * PI * GRID[it] / FS) }
    private val COS2 = DoubleArray(GRID.size) { cos(4 * PI * GRID[it] / FS) }
    private val SIN2 = DoubleArray(GRID.size) { sin(4 * PI * GRID[it] / FS) }

    fun peqDb(filters: List<Filter>): DoubleArray {
        val out = DoubleArray(GRID.size)
        for (fl in filters) {
            val (b, a) = biquad(fl.f, fl.g, fl.q, fl.kind)
            for (i in GRID.indices) {
                val nr = b[0] + b[1] * COS1[i] + b[2] * COS2[i]
                val ni = -b[1] * SIN1[i] - b[2] * SIN2[i]
                val dr = a[0] + a[1] * COS1[i] + a[2] * COS2[i]
                val di = -a[1] * SIN1[i] - a[2] * SIN2[i]
                out[i] += 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
            }
        }
        return out
    }

    private fun usableRange(db: DoubleArray, ref: Double): Pair<Double, Double> {
        val s = DoubleArray(db.size) { i -> // np.convolve(db, ones(21)/21, 'same'): zero-padded
            var t = 0.0
            for (j in i - 10..i + 10) if (j in db.indices) t += db[j]
            t / 21
        }
        val i300 = searchSorted(GRID, 300.0)
        val below = (0 until i300).lastOrNull { s[it] < ref - 6 }
        val fLow = if (below != null) GRID[below + 1] else GRID[0]
        val i3k = searchSorted(GRID, 3000.0)
        val above = (i3k until GRID.size).firstOrNull { s[it] < ref - 10 }
        val fHigh = if (above != null) GRID[above - 1] else GRID.last()
        return fLow to min(fHigh, 16000.0)
    }

    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray { // Gaussian elimination, pivoting
        val n = b.size
        for (c in 0 until n) {
            var p = c
            for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[p][c])) p = r
            val tr = a[c]; a[c] = a[p]; a[p] = tr
            val tb = b[c]; b[c] = b[p]; b[p] = tb
            if (abs(a[c][c]) < 1e-300) continue
            for (r in c + 1 until n) {
                val f = a[r][c] / a[c][c]
                for (k in c until n) a[r][k] -= f * a[c][k]
                b[r] -= f * b[c]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (k in r + 1 until n) s -= a[r][k] * x[k]
            x[r] = if (abs(a[r][r]) < 1e-300) 0.0 else s / a[r][r]
        }
        return x
    }

    /** Bounded Levenberg-Marquardt (projected), standing in for scipy.optimize.least_squares. */
    private fun leastSquares(x0: DoubleArray, lo: DoubleArray, hi: DoubleArray, f: (DoubleArray) -> DoubleArray): DoubleArray {
        var x = x0.copyOf()
        var r = f(x)
        var cost = r.sumOf { it * it }
        var lam = 1e-3
        val n = x.size
        repeat(200) {
            val jac = Array(n) { k ->
                val h = 1e-6 * max(1.0, abs(x[k]))
                val xs = x.copyOf()
                val step = if (xs[k] + h > hi[k]) -h else h
                xs[k] += step
                val rk = f(xs)
                DoubleArray(r.size) { (rk[it] - r[it]) / step }
            }
            val a = Array(n) { i -> DoubleArray(n) { j -> var s = 0.0; for (m in r.indices) s += jac[i][m] * jac[j][m]; s } }
            val g = DoubleArray(n) { i -> var s = 0.0; for (m in r.indices) s += jac[i][m] * r[m]; s }
            while (true) {
                val mm = Array(n) { i -> DoubleArray(n) { j -> a[i][j] + if (i == j) lam * a[i][i] + 1e-12 else 0.0 } }
                val d = solve(mm, DoubleArray(n) { -g[it] })
                val xn = DoubleArray(n) { (x[it] + d[it]).coerceIn(lo[it], hi[it]) }
                val rn = f(xn)
                val cn = rn.sumOf { it * it }
                if (cn < cost) {
                    val done = cost - cn < 1e-10 * cost
                    x = xn; r = rn; cost = cn; lam = max(lam / 3, 1e-9)
                    if (done) return x
                    break
                }
                lam *= 4
                if (lam > 1e10) return x
            }
        }
        return x
    }

    private fun fit(desired: DoubleArray, inr: BooleanArray, cap: DoubleArray, maxBoost: Double, nMax: Int): List<Filter> {
        val p = mutableListOf<Filter>()
        repeat(nMax) {
            val eq = peqDb(p)
            val r = DoubleArray(GRID.size) { if (inr[it]) desired[it] - eq[it] else 0.0 }
            var i = 0
            for (k in r.indices) if (abs(r[k]) > abs(r[i])) i = k
            if (abs(r[i]) < 1) return@repeat
            val s = sign(r[i])
            var j = i
            var k = i
            while (j > 0 && s * r[j - 1] > abs(r[i]) / 2) j--
            while (k < r.size - 1 && s * r[k + 1] > abs(r[i]) / 2) k++
            val bw = max(log2(GRID[k] / GRID[j]), 1.0 / 6)
            p.add(Filter(GRID[i], r[i], sqrt(2.0.pow(bw)) / (2.0.pow(bw) - 1)))
        }
        if (p.isEmpty()) return emptyList()
        val fr = GRID.indices.filter { inr[it] }
        val lo = DoubleArray(3 * p.size) { doubleArrayOf(ln(GRID[fr.first()]), -12.0, 0.5)[it % 3] }
        val hi = DoubleArray(3 * p.size) { doubleArrayOf(ln(GRID[fr.last()]), maxBoost, 6.0)[it % 3] }
        val x0 = DoubleArray(3 * p.size) {
            val fl = p[it / 3]
            doubleArrayOf(ln(fl.f), fl.g, fl.q)[it % 3].coerceIn(lo[it] + 1e-6, hi[it] - 1e-6)
        }
        val unpack = { x: DoubleArray -> List(x.size / 3) { Filter(exp(x[3 * it]), x[3 * it + 1], x[3 * it + 2]) } }
        val w = DoubleArray(GRID.size) { if (inr[it]) 1.0 else 0.3 }
        val x = leastSquares(x0, lo, hi) { xv ->
            val eq = peqDb(unpack(xv))
            val n = GRID.size
            DoubleArray(2 * n + xv.size / 3) {
                when {
                    it < n -> w[it] * (eq[it] - desired[it])
                    it < 2 * n -> if (inr[it - n]) 3 * max(0.0, eq[it - n] - cap[it - n]) else 0.0
                    else -> 0.5 * xv[3 * (it - 2 * n) + 1] // gain penalty: no opposing filter pairs
                }
            }
        }
        return unpack(x).filter { abs(it.g) >= 0.3 }
    }

    /** scipy.signal.find_peaks(v, prominence=p) for smooth curves (strict local maxima). */
    private fun findPeaks(v: DoubleArray, prominence: Double): List<Int> = (1 until v.size - 1).filter { i ->
        if (!(v[i] > v[i - 1] && v[i] >= v[i + 1])) return@filter false
        var l = v[i]; var a = i - 1
        while (a >= 0 && v[a] <= v[i]) { l = min(l, v[a]); a-- }
        var r = v[i]; var b = i + 1
        while (b < v.size && v[b] <= v[i]) { r = min(r, v[b]); b++ }
        v[i] - max(l, r) >= prominence
    }

    private fun f0(v: Double) = "%.0f".format(v)

    private fun diagnose(dev: DoubleArray, inr: BooleanArray, fLow: Double, fHigh: Double, thd: DoubleArray,
                         ok: BooleanArray, dspDiff: DoubleArray?): List<String> {
        val out = mutableListOf("Usable range: ${f0(fLow)} Hz - ${f0(fHigh)} Hz")
        if (fLow > 80) out += "  Bass rolls off below ${f0(fLow)} Hz. That is the driver/box size; EQ will not boost below it."
        val masked = GRID.indices.filter { !ok[it] && GRID[it] < 300 }
        if (masked.isNotEmpty()) out += "  Room noise masks the sweep below ${f0(GRID[masked.last()])} Hz; not EQed there (quieter room or louder volume helps)."
        for (b in BANDS) {
            val m = GRID.indices.filter { inr[it] && GRID[it] >= b.lo && GRID[it] < b.hi }
            if (m.size < 5) continue
            val d = m.sumOf { dev[it] } / m.size
            if (abs(d) >= 2) out += "  ${b.name.padEnd(9)} ${"%+5.1f".format(d)} dB vs target -> ${if (d > 0) b.more else b.less}"
        }
        val idx = GRID.indices.filter { inr[it] }
        val d = DoubleArray(idx.size) { dev[idx[it]] }
        for ((sg, word) in listOf(1.0 to "peak", -1.0 to "dip")) {
            for (i in findPeaks(DoubleArray(d.size) { sg * d[it] }, 3.0)) {
                if (sg * d[i] < 3) continue
                val f = GRID[idx[i]]
                val b = BANDS.firstOrNull { f >= it.lo && f < it.hi } ?: BANDS.last()
                val note = if (sg > 0) b.more else "dips only partly fixable; often room/mic position"
                out += "  $word ${"%+5.1f".format(d[i])} dB at ${"%6.0f".format(f)} Hz (${b.name}: $note)"
            }
        }
        val bad = GRID.indices.filter { inr[it] && ok[it] && thd[it] > MAX_THD && GRID[it] < 10000 }
        if (bad.isNotEmpty()) {
            val i = bad.maxBy { thd[it] }
            out += "  Distortion: ${f0(thd[i] * 100)}% at ${f0(GRID[i])} Hz (over ${f0(MAX_THD * 100)}% in parts of " +
                "${f0(GRID[bad.first()])}-${f0(GRID[bad.last()])} Hz). No boost there; turn volume down if it sounds rough."
        }
        if (dspDiff != null && dspDiff.maxOf { abs(it) } >= 3) {
            val i = dspDiff.indices.maxBy { abs(dspDiff[it]) }
            out += "  Speaker DSP changes tone with level (${"%+.1f".format(dspDiff[i])} dB at ${f0(GRID[i])} Hz between " +
                "loud and quiet): dynamic bass/limiter. This EQ is right only at the volume you measured."
        }
        return out
    }

    private fun db10(v: Double) = 10 * log10(v)

    /**
     * Full measurement + analysis. playRec plays a signal through the speaker and returns the mic recording.
     * fixed = mic and speaker stay still (gentler, position-safe bass). Throws MeasureError / Clipped.
     */
    fun run(playRec: (DoubleArray) -> DoubleArray, positions: Int, fixed: Boolean, progress: (String) -> Unit = {},
            tiltDb: Double = -0.8, maxBoost: Double = 6.0, nMax: Int = 10): Result {
        val x = sweep()
        progress("Measuring room noise: stay quiet…")
        val y0 = playRec(DoubleArray(x.size))
        if (y0.maxOf { abs(it) } > 0.98) throw MeasureError("Microphone is saturated even in silence.")
        val noise = response(x, y0).first
        val powers = mutableListOf<DoubleArray>()
        val thds = mutableListOf<DoubleArray>()
        for (k in 0 until positions) {
            progress("Sweep ${k + 1} of $positions…")
            val y = playRec(x)
            val peak = y.maxOf { abs(it) }
            if (peak > 0.98) throw Clipped()
            if (peak < 0.01) throw MeasureError("Recording too quiet: raise the volume and check the speaker is connected.")
            val (pw, t) = response(x, y)
            powers += pw; thds += t
        }
        progress("Analysing…")
        val n = GRID.size
        val pAvg = DoubleArray(n) { i -> powers.sumOf { it[i] } / powers.size }
        val thd = DoubleArray(n) { i -> thds.sumOf { it[i] } / thds.size }
        val ok = BooleanArray(n) { db10(pAvg[it] / (noise[it] + 1e-30)) > MIN_SNR }
        val db = DoubleArray(n) { db10(pAvg[it] + 1e-30) }
        val tilt = DoubleArray(n) { tiltDb * log2(GRID[it] / 1000) }
        val mid = GRID.indices.filter { GRID[it] >= 300 && GRID[it] <= 3000 }
        val ref = mid.sumOf { db[it] - tilt[it] } / mid.size
        val (fLow, fHigh) = usableRange(db, ref)
        val inr = BooleanArray(n) { GRID[it] >= fLow && GRID[it] <= fHigh && ok[it] }
        val dev = DoubleArray(n) { db[it] - (ref + tilt[it]) }

        progress("Checking speaker DSP (quiet sweep)…") // a fixed speaker scales evenly, a DSP one changes shape
        val q = response(x, playRec(DoubleArray(x.size) { 0.25 * x[it] })).first
        val dq = DoubleArray(n) { db10(q[it] + 1e-30) }
        val qref = mid.sumOf { dq[it] - tilt[it] } / mid.size
        val raw = oct3(DoubleArray(n) { (db[it] - ref) - (dq[it] - qref) })
        val dspDiff = DoubleArray(n) { if (inr[it] && db10(q[it] / (noise[it] + 1e-30)) > MIN_SNR) raw[it] else 0.0 }

        var devS = oct3(dev) // decisions use 1/3-oct detail: narrower wiggles are room/position
        if (fixed) { // one spot: below ~500 Hz use 1/2-oct smoothing and 70% correction, blended over 300-600 Hz
            val broad = oct3(dev, 21)
            devS = DoubleArray(n) { val a = log2(GRID[it] / 300).coerceIn(0.0, 1.0); a * devS[it] + (1 - a) * 0.7 * broad[it] }
        }
        val lines = diagnose(devS, inr, fLow, fHigh, thd, ok, dspDiff).toMutableList()
        val cap = DoubleArray(n) { if (ok[it] && thd[it] > MAX_THD) 0.0 else maxBoost }
        val desired = DoubleArray(n) {
            val d = if (inr[it]) (-devS[it]).coerceIn(-12.0, max(cap[it], -12.0)) else 0.0
            if (GRID[it] > 5000) d.coerceIn(-3.0, 3.0) else d // phone mics are unreliable up top
        }
        lines += "  No mic calibration: corrections above 5 kHz limited to +/-3 dB."
        val filters = fit(desired, inr, cap, maxBoost, nMax).sortedBy { it.f }
        val eq = peqDb(filters)
        val ii = GRID.indices.filter { inr[it] }
        val before = sqrt(ii.sumOf { dev[it] * dev[it] } / ii.size)
        val after = sqrt(ii.sumOf { (dev[it] + eq[it]).pow(2) } / ii.size)
        lines += ""
        lines += "Deviation from target: ${"%.1f".format(before)} dB RMS before, ${"%.1f".format(after)} dB RMS after (predicted)"
        return Result(filters, dev, lines.joinToString("\n"))
    }

    /** Preamp that keeps average loudness (mean EQ gain 100 Hz-6 kHz); the limiter catches the peaks. */
    fun loudnessGain(filters: List<Filter>): Double {
        val eq = peqDb(filters)
        val idx = GRID.indices.filter { GRID[it] >= 100 && GRID[it] <= 6000 }
        return (-idx.sumOf { eq[it] } / idx.size).coerceIn(-12.0, 6.0)
    }

    /** Filters from an Equalizer APO / AutoEQ file (our Export, the desktop app, AutoEQ). Preamp lines are
     *  ignored: the app sets its own loudness-matched gain. */
    fun parseApo(text: String): List<Filter> =
        Regex("""Filter\s*\d*\s*:\s*ON\s+(PK|LSC|HSC)\s+Fc\s+([\d.]+)\s*Hz\s+Gain\s+(-?[\d.]+)\s*dB\s+Q\s+([\d.]+)""",
            RegexOption.IGNORE_CASE).findAll(text).map {
            val (kind, f, g, q) = it.destructured
            Filter(f.toDouble(), g.toDouble(), q.toDouble(), kind.uppercase())
        }.filter { it.f in 10.0..24000.0 && it.q > 0 && it.g in -30.0..30.0 }.toList() // untrusted file: sane values only

    /** Equalizer APO / AutoEQ text (same as speaker_eq.write_apo), for sharing with desktop or Wavelet. */
    fun apoText(filters: List<Filter>): String {
        val pre = -ceil(max(0.0, peqDb(filters).max()) * 10) / 10 + 0.0
        val sb = StringBuilder("Preamp: ${"%.1f".format(pre)} dB\n")
        filters.forEachIndexed { i, f ->
            sb.append("Filter ${i + 1}: ON ${f.kind} Fc ${"%.0f".format(f.f)} Hz Gain ${"%.1f".format(f.g)} dB Q ${"%.2f".format(f.q)}\n")
        }
        return sb.toString()
    }
}
