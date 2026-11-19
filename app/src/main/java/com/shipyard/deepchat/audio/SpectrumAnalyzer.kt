package com.shipyard.deepchat.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 录音时的实时频谱，参数对齐官方 chat-demo 的 Spectrum 组件：
 * 100–4000Hz 按对数分成 60 段，dB 范围 -80~-25，高频按 4dB/倍频程补偿，
 * 相邻三柱加权，起 0.8 / 落 0.35 平滑。
 */
class SpectrumAnalyzer(
    private val sampleRate: Int = 16000,
    private val fftSize: Int = 1024,
    private val bars: Int = 60
) {
    companion object {
        private const val FREQ_LOW = 100f
        private const val FREQ_HIGH = 4000f
        private const val DB_FLOOR = -80f
        private const val DB_CEIL = -25f
        private const val TILT_DB_PER_OCTAVE = 4f
    }

    private data class Band(val from: Int, val to: Int, val center: Float)

    private val window = FloatArray(fftSize) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / (fftSize - 1))).toFloat()
    }
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val levels = FloatArray(bars)
    private val bands: List<Band>

    init {
        val bw = sampleRate.toFloat() / fftSize
        val ratio = FREQ_HIGH / FREQ_LOW
        val limit = fftSize / 2 - 1
        bands = (0 until bars).map { i ->
            val low = FREQ_LOW * ratio.pow(i.toFloat() / bars)
            val high = FREQ_LOW * ratio.pow((i + 1).toFloat() / bars)
            val from = (low / bw).toInt().coerceIn(1, limit)
            val to = ceil(high / bw).toInt().coerceIn(from + 1, limit + 1)
            Band(from, to, sqrt(low * high) / bw)
        }
    }

    /** 喂一帧 PCM16，返回 60 根柱的高度（0..1） */
    fun push(pcm: ShortArray, len: Int): FloatArray {
        val n = min(len, fftSize)
        for (i in 0 until fftSize) {
            val v = if (i < n) pcm[i] / 32768f else 0f
            re[i] = v * window[i]
            im[i] = 0f
        }
        fft()
        val targets = FloatArray(bars)
        val norm = fftSize / 2f
        for (i in 0 until bars) {
            val b = bands[i]
            var peak = 0f
            for (k in b.from until b.to) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k]) / norm
                if (mag > peak) peak = mag
            }
            val db = 20f * log10(peak.coerceAtLeast(1e-9f)) + tilt(i)
            targets[i] = ((db - DB_FLOOR) / (DB_CEIL - DB_FLOOR)).coerceIn(0f, 1f)
        }
        val out = FloatArray(bars)
        for (i in 0 until bars) {
            val prev = targets[max(0, i - 1)]
            val next = targets[min(bars - 1, i + 1)]
            val t = (prev + targets[i] * 2f + next) / 4f
            levels[i] += (t - levels[i]) * (if (t > levels[i]) 0.8f else 0.35f)
            out[i] = levels[i]
        }
        return out
    }

    fun reset() {
        levels.fill(0f)
    }

    private fun tilt(i: Int): Float =
        TILT_DB_PER_OCTAVE * log2(FREQ_HIGH / FREQ_LOW) * (i.toFloat() / bars)

    /** 原地迭代 radix-2 FFT */
    private fun fft() {
        val n = fftSize
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            val half = len / 2
            while (i < n) {
                var curR = 1f
                var curI = 0f
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val uR = re[a]
                    val uI = im[a]
                    val vR = re[b] * curR - im[b] * curI
                    val vI = re[b] * curI + im[b] * curR
                    re[a] = uR + vR
                    im[a] = uI + vI
                    re[b] = uR - vR
                    im[b] = uI - vI
                    val nr = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
