package com.example.meetingtranscription

import android.content.Context
import android.util.Log
import org.vosk.*
import java.io.*

/**
 * Vosk 离线语音识别引擎封装
 * 支持实时部分结果和完整结果
 */
class VoskEngine(private val context: Context) {

    companion object {
        private const val TAG = "VoskEngine"
        const val SAMPLE_RATE = 16000
        private const val ASSETS_ZIP = "models/vosk-model-small-cn-0.22.zip"
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var isInitialized = false
    private var initError: String? = null

    fun initialize(): Boolean {
        if (isInitialized) return true
        try {
            val modelDir = getModelDir()
            if (!isModelValid(modelDir)) {
                if (!extractModel(modelDir)) return fail("解压模型失败")
            }
            if (!isModelValid(modelDir)) return fail("模型文件不完整")
            model = Model(modelDir.absolutePath)
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat()).apply { setWords(true) }
            isInitialized = true
            Log.i(TAG, "Vosk 引擎初始化成功")
            return true
        } catch (e: Exception) {
            initError = "模型初始化失败: ${e.localizedMessage ?: "未知错误"}"
            Log.e(TAG, initError!!, e); return false
        }
    }

    private fun fail(m: String): Boolean { initError = m; Log.w(TAG, m); return false }

    private fun getModelDir() = File(context.filesDir, "vosk-model-cn")
    private fun isModelValid(d: File) = File(d, "am/final.mdl").exists()

    private fun extractModel(dst: File): Boolean {
        try {
            if (dst.exists()) { dst.listFiles()?.forEach { if (it.isDirectory) it.deleteRecursively() else it.delete() }; dst.delete() }
            dst.mkdirs()
            val bytes = context.assets.open(ASSETS_ZIP).use { it.readBytes() }
            val zis = java.util.zip.ZipInputStream(ByteArrayInputStream(bytes))
            var entry = zis.nextEntry
            val buf = ByteArray(8192)
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.substringAfter('/')
                    if (name.isNotEmpty()) {
                        val f = File(dst, name); f.parentFile?.mkdirs()
                        FileOutputStream(f).use { var n: Int; while (zis.read(buf).also { n = it } >= 0) it.write(buf, 0, n) }
                    }
                }
                zis.closeEntry(); entry = zis.nextEntry
            }
            zis.close()
            Log.i(TAG, "模型解压完成"); return true
        } catch (e: Exception) { Log.e(TAG, "解压失败", e); return false }
    }

    /** 喂 PCM 数据给识别器并返回部分结果 */
    fun feedPcm(pcmData: ShortArray): String {
        val rec = recognizer ?: return ""
        return try {
            val bytes = ByteArray(pcmData.size * 2)
            for (i in pcmData.indices) { val v = pcmData[i].toInt(); bytes[i*2] = (v and 0xFF).toByte(); bytes[i*2+1] = ((v shr 8) and 0xFF).toByte() }
            rec.acceptWaveForm(bytes, bytes.size)
            extractText(rec.getPartialResult())
        } catch (e: Exception) { Log.w(TAG, "feedPcm error", e); "" }
    }

    /** 最终识别 */
    fun recognize(pcmData: ShortArray): String {
        val rec = recognizer ?: return initError ?: "引擎未就绪"
        return try {
            val bytes = ByteArray(pcmData.size * 2)
            for (i in pcmData.indices) { val v = pcmData[i].toInt(); bytes[i*2] = (v and 0xFF).toByte(); bytes[i*2+1] = ((v shr 8) and 0xFF).toByte() }
            rec.acceptWaveForm(bytes, bytes.size)
            extractText(rec.getFinalResult())
        } catch (e: Exception) { Log.e(TAG, "识别失败", e); "[识别错误]" }
    }

    private fun extractText(json: String): String {
        return try { val k = "\"text\" : \""; val s = json.indexOf(k); if (s >= 0) { val start = s + k.length; val e = json.indexOf("\"", start); if (e > start) json.substring(start, e) else "" } else "" } catch (_: Exception) { "" }
    }

    fun isReady(): Boolean = isInitialized
    fun getInitError(): String? = initError
    fun release() { try { recognizer?.close() } catch (_: Exception) {}; try { model?.close() } catch (_: Exception) {}; recognizer = null; model = null; isInitialized = false }
}
