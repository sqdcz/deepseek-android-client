package com.shipyard.deepchat.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/**
 * 录音到内存，松手写成一个 16kHz / 16bit / 单声道 wav。
 * 这个格式正好是 /v1/tools/asr 接受的。
 */
class Recorder(private val ctx: Context) {

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_SECONDS = 120
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var buf = ByteArrayOutputStream()

    @Volatile
    private var running = false

    @Volatile
    var level: Float = 0f
        private set

    /** 最近一帧的频谱高度（60 根，0..1），供浮层画柱 */
    @Volatile
    var latestSpectrum: FloatArray? = null
        private set

    val isRunning: Boolean get() = running

    fun start(): Boolean {
        if (running) return true
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING,
                maxOf(min, SAMPLE_RATE / 4)
            )
        } catch (e: Exception) {
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        record = r
        buf = ByteArrayOutputStream()
        running = true
        level = 0f
        try {
            r.startRecording()
        } catch (e: Exception) {
            running = false
            r.release()
            record = null
            return false
        }
        thread = Thread {
            val tmp = ByteArray(3200)
            val maxBytes = SAMPLE_RATE * 2 * MAX_SECONDS
            val analyzer = SpectrumAnalyzer(SAMPLE_RATE)
            val shorts = ShortArray(1600)
            while (running) {
                val n = r.read(tmp, 0, tmp.size)
                if (n > 0) {
                    buf.write(tmp, 0, n)
                    var peak = 0
                    var i = 0
                    var k = 0
                    while (i + 1 < n) {
                        val lo = tmp[i].toInt() and 0xFF
                        val hi = tmp[i + 1].toInt()
                        val s = (hi shl 8) or lo
                        val a = abs(s)
                        if (a > peak) peak = a
                        if (k < shorts.size) shorts[k++] = s.toShort()
                        i += 2
                    }
                    level = (peak / 32768f).coerceIn(0f, 1f)
                    // 真频谱：100–4000Hz 对数分频，60 根
                    latestSpectrum = analyzer.push(shorts, k)
                    if (buf.size() >= maxBytes) running = false
                }
            }
        }.apply { isDaemon = true; start() }
        return true
    }

    fun stop(): File? {
        if (!running && record == null) return null
        running = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { thread?.join(400) }
        thread = null
        val pcm = buf.toByteArray()
        buf = ByteArrayOutputStream()
        if (pcm.size < SAMPLE_RATE / 2) return null   // 少于 0.5 秒当误触
        val f = File(ctx.cacheDir, "rec_${System.currentTimeMillis()}.wav")
        runCatching {
            f.outputStream().use {
                it.write(wavHeader(pcm.size))
                it.write(pcm)
            }
        }.onFailure { return null }
        return f
    }

    fun cancel() {
        running = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { thread?.join(300) }
        thread = null
        buf = ByteArrayOutputStream()
        level = 0f
    }

    private fun wavHeader(dataLen: Int): ByteArray {
        val total = dataLen + 36
        val h = ByteArray(44)
        fun putStr(off: Int, s: String) {
            for (i in s.indices) h[off + i] = s[i].code.toByte()
        }
        fun putInt(off: Int, v: Int) {
            h[off] = (v and 0xFF).toByte()
            h[off + 1] = ((v shr 8) and 0xFF).toByte()
            h[off + 2] = ((v shr 16) and 0xFF).toByte()
            h[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun putShort(off: Int, v: Int) {
            h[off] = (v and 0xFF).toByte()
            h[off + 1] = ((v shr 8) and 0xFF).toByte()
        }
        putStr(0, "RIFF")
        putInt(4, total)
        putStr(8, "WAVE")
        putStr(12, "fmt ")
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, 1)
        putInt(24, SAMPLE_RATE)
        putInt(28, SAMPLE_RATE * 2)
        putShort(32, 2)
        putShort(34, 16)
        putStr(36, "data")
        putInt(40, dataLen)
        return h
    }
}

/** 粗略 token 估算：中文按 1 字 1 token，英文按 4 字符 1 token */
object Tokens {
    fun estimate(s: String): Int {
        if (s.isBlank()) return 0
        var cjk = 0
        var other = 0
        for (ch in s) {
            if (ch.code > 0x2E80) cjk++ else other++
        }
        return cjk + other / 4 + 1
    }
}
