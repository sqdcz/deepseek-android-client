package com.shipyard.deepchat.audio

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.abs

/** 本地 / 系统语音识别的统一出口 */
interface AsrEngine {
    var onPartial: ((String) -> Unit)?
    var onFinal: ((String) -> Unit)?
    var onError: ((String) -> Unit)?
    var onLevel: ((Float) -> Unit)?

    fun start(): Boolean
    /** 松手：结束录音并等最终结果 */
    fun stop()
    fun cancel()
    fun release()
}

// ---------------- 系统识别 ----------------

/**
 * 用手机自带的 SpeechRecognizer：零下载、免费。
 * 中文能不能离线取决于厂商是否装了语言包，没网时会报 ERROR_NETWORK。
 */
class SystemAsr(private val ctx: Context) : AsrEngine {

    override var onPartial: ((String) -> Unit)? = null
    override var onFinal: ((String) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null
    override var onLevel: ((Float) -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null
    private var lastText = ""
    private val main = Handler(Looper.getMainLooper())

    override fun start(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            onError?.invoke("这台手机没有可用的系统语音识别，换成本地模型试试")
            return false
        }
        release()
        lastText = ""
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                onLevel?.invoke(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }

            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                onError?.invoke(errText(error))
            }

            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                val text = t.ifBlank { lastText }
                if (text.isBlank()) onError?.invoke("没听清，再说一遍")
                else onFinal?.invoke(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (t.isNotBlank()) {
                    lastText = t
                    onPartial?.invoke(t)
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        return runCatching { r.startListening(intent) }.isSuccess
    }

    override fun stop() {
        main.post { runCatching { recognizer?.stopListening() } }
    }

    override fun cancel() {
        main.post {
            runCatching { recognizer?.cancel() }
            release()
        }
    }

    override fun release() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun errText(code: Int) = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "录音出错"
        SpeechRecognizer.ERROR_CLIENT -> "识别器客户端出错"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
        SpeechRecognizer.ERROR_NETWORK -> "系统识别要联网，没网就换成本地模型"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "识别超时"
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清，再说一遍"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙，稍等再试"
        SpeechRecognizer.ERROR_SERVER -> "识别服务出错"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到声音"
        else -> "识别失败（$code）"
    }
}

// ---------------- Vosk 本地模型 ----------------

/** Vosk 中文模型：42MB，离线，边说边出字 */
class VoskAsr(private val ctx: Context, private val modelDir: File) : AsrEngine {

    override var onPartial: ((String) -> Unit)? = null
    override var onFinal: ((String) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null
    override var onLevel: ((Float) -> Unit)? = null

    private var model: org.vosk.Model? = null
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    fun prepare(onReady: (String?) -> Unit) {
        if (model != null) {
            onReady(null)
            return
        }
        Thread {
            val err = runCatching {
                if (!AsrModel.ready(ctx)) error("模型还没下载")
                model = org.vosk.Model(modelDir.absolutePath)
                null
            }.getOrElse { it.message ?: "模型加载失败" }
            main.post { onReady(err) }
        }.start()
    }

    override fun start(): Boolean {
        val m = model
        if (m == null) {
            onError?.invoke("模型未就绪，先去设置里下载")
            return false
        }
        val min = AudioRecord.getMinBufferSize(
            AsrModel.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val r = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AsrModel.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, 8192)
            )
        }.getOrNull() ?: run {
            onError?.invoke("麦克风打不开")
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            onError?.invoke("麦克风初始化失败")
            return false
        }
        record = r
        r.startRecording()
        running = true

        thread = Thread {
            val recognizer = runCatching { org.vosk.Recognizer(m, AsrModel.SAMPLE_RATE.toFloat()) }
                .getOrNull()
            if (recognizer == null) {
                main.post { onError?.invoke("识别器创建失败") }
                return@Thread
            }
            val buf = ByteArray(4096)
            val acc = StringBuilder()
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                var i = 0
                while (i + 1 < n) {
                    val s = ((buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)).toShort()
                    sum += abs(s.toInt()).toDouble()
                    i += 2
                }
                val lvl = (sum / (n / 2).coerceAtLeast(1) / 8000.0).coerceIn(0.0, 1.0)
                main.post { onLevel?.invoke(lvl.toFloat()) }

                if (recognizer.acceptWaveForm(buf, n)) {
                    val t = textOf(recognizer.result)
                    if (t.isNotBlank()) {
                        if (acc.isNotEmpty()) acc.append("，")
                        acc.append(t)
                    }
                } else {
                    val p = textOf(recognizer.partialResult)
                    if (p.isNotBlank()) {
                        val whole = if (acc.isEmpty()) p else "$acc$p"
                        main.post { onPartial?.invoke(whole) }
                    }
                }
            }
            val tail = textOf(recognizer.finalResult)
            if (tail.isNotBlank()) {
                if (acc.isNotEmpty()) acc.append("，")
                acc.append(tail)
            }
            runCatching { recognizer.close() }
            val finalText = acc.toString().trim()
            main.post {
                if (finalText.isBlank()) onError?.invoke("没听清，再说一遍")
                else onFinal?.invoke(finalText)
            }
        }.also { it.start() }
        return true
    }

    override fun stop() {
        running = false
        runCatching { record?.stop() }
        val t = thread
        Thread {
            runCatching { t?.join(1200) }
            runCatching { record?.release() }
            record = null
            thread = null
        }.start()
    }

    override fun cancel() {
        running = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        thread = null
    }

    override fun release() {
        cancel()
        runCatching { model?.close() }
        model = null
    }

    private fun textOf(json: String?): String = runCatching {
        JSONObject(json ?: "").optString("text", "").ifBlank {
            JSONObject(json ?: "").optString("partial", "")
        }
    }.getOrDefault("")
}

// ---------------- 模型文件 ----------------

object AsrModel {
    const val SAMPLE_RATE = 16000
    const val DIR_NAME = "vosk-model-small-cn-0.22"
    const val ZIP_NAME = "vosk-model-small-cn-0.22.zip"
    const val DOWNLOAD_URL = "https://alphacephei.com/vosk/models/$ZIP_NAME"
    const val SIZE_MB = 42

    fun root(ctx: Context): File = File(ctx.filesDir, "vosk")

    fun dir(ctx: Context): File = File(root(ctx), DIR_NAME)

    /** 关键文件齐全就算就绪 */
    fun ready(ctx: Context): Boolean {
        val d = dir(ctx)
        return File(d, "conf").isDirectory || File(d, "am").isDirectory
    }

    /** 解压下载好的 zip，返回错误信息 */
    fun unzip(zip: File, ctx: Context): String? = runCatching {
        root(ctx).mkdirs()
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.substringAfter("$DIR_NAME/", "")
                if (name.isNotBlank() && !entry.isDirectory) {
                    val out = File(dir(ctx), name)
                    out.parentFile?.mkdirs()
                    out.outputStream().use { os -> zis.copyTo(os) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        null
    }.getOrElse { it.message ?: "解压失败" }

    /** 用户自己选的模型文件夹（里面直接是 am/、conf/）*/
    fun adoptFolder(src: File, ctx: Context): String? = runCatching {
        val target = dir(ctx)
        if (target.exists()) target.deleteRecursively()
        target.mkdirs()
        src.copyRecursively(target, overwrite = true)
        if (!ready(ctx)) "文件夹里没找到 am/ 或 conf/，确认选的是模型根目录" else null
    }.getOrElse { it.message ?: "复制失败" }
}
