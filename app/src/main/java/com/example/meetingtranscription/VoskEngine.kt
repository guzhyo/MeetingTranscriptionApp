package com.example.meetingtranscription

import android.content.Context
import android.util.Log
import org.vosk.*
import java.io.*

/**
 * Vosk 离线语音识别引擎封装
 *
 * 模型文件：vosk-model-small-cn-0.22
 * 位置：/storage/emulated/0/VoskModels/vosk-model-small-cn-0.22/
 */
class VoskEngine(private val context: Context) {

    companion object {
        private const val TAG = "VoskEngine"
        const val SAMPLE_RATE = 16000
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var isInitialized = false
    private var initError: String? = null

    fun initialize(): Boolean {
        if (isInitialized) return true

        try {
            val modelDir = findModelDir() ?: run {
                initError = "未找到 Vosk 模型，请将 vosk-model-small-cn-0.22 放入手机存储的 VoskModels/ 目录"
                Log.w(TAG, initError)
                return false
            }

            model = Model(modelDir.absolutePath)
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat()).apply {
                setWords(true)
            }
            isInitialized = true
            Log.i(TAG, "Vosk 引擎初始化成功: $modelDir")
            return true
        } catch (e: Exception) {
            initError = "模型初始化失败: ${e.localizedMessage ?: "未知错误"}"
            Log.e(TAG, initError!!, e)
            return false
        }
    }

    private fun findModelDir(): File? {
        val searchPaths = listOf(
            "/storage/emulated/0/VoskModels",
            "/sdcard/VoskModels",
            "/storage/emulated/0/Android/data/${context.packageName}/files/vosk-model",
            context.filesDir.absolutePath + "/vosk-model"
        )

        for (basePath in searchPaths) {
            val baseDir = File(basePath)
            if (!baseDir.exists()) continue

            // 直接查找包含 am/final.mdl 的子目录
            val dirs = baseDir.listFiles { f -> f.isDirectory } ?: emptyArray()
            for (dir in dirs) {
                if (File(dir, "am/final.mdl").exists()) {
                    return dir
                }
            }

            // 也可能是 basePath 本身就是模型目录
            if (File(baseDir, "am/final.mdl").exists()) {
                return baseDir
            }
        }

        return null
    }

    /**
     * 识别 PCM 音频数据
     */
    fun recognize(pcmData: ShortArray): String {
        val rec = recognizer ?: return initError ?: "引擎未就绪"

        return try {
            // 将 ShortArray 转为 ByteArray
            val byteData = ByteArray(pcmData.size * 2)
            for (i in pcmData.indices) {
                val v = pcmData[i].toInt()
                byteData[i * 2] = (v and 0xFF).toByte()
                byteData[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }

            rec.acceptWaveForm(byteData, byteData.size)

            // 获取完整结果
            val finalJson = rec.getFinalResult()
            val text = extractText(finalJson)
            text
        } catch (e: Exception) {
            Log.e(TAG, "识别失败", e)
            "[识别错误]"
        }
    }

    /**
     * 从 Vosk JSON 结果中提取 text 字段
     */
    private fun extractText(jsonResult: String): String {
        return try {
            // JSON 格式: {"text": "你好世界"}
            val key = "\"text\" : \""
            val start = jsonResult.indexOf(key)
            if (start >= 0) {
                val startVal = start + key.length
                val end = jsonResult.indexOf("\"", startVal)
                if (end > startVal) jsonResult.substring(startVal, end) else ""
            } else ""
        } catch (e: Exception) { "" }
    }

    fun isReady(): Boolean = isInitialized
    fun getInitError(): String? = initError

    fun release() {
        try { recognizer?.close() } catch (_: Exception) {}
        try { model?.close() } catch (_: Exception) {}
        recognizer = null
        model = null
        isInitialized = false
    }
}
