package com.example.meetingtranscription

import android.content.Context
import android.util.Log
import org.vosk.*
import java.io.*

/**
 * Vosk 离线语音识别引擎封装
 *
 * 模型会从外部存储（/sdcard/VoskModels/）复制到 App 内部目录再加载
 * 以避免 Android 11+ 的外部存储访问限制
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
            // 获取内部模型目录（App 私有目录，无需权限）
            val internalModelDir = getInternalModelDir()

            // 检查内部目录是否有模型
            if (!isModelValid(internalModelDir)) {
                // 从外部存储复制模型到内部
                val externalModelDir = findExternalModelDir()
                if (externalModelDir == null) {
                    initError = "未找到 Vosk 模型，请将 vosk-model-small-cn-0.22 放入手机存储的 VoskModels/ 目录"
                    Log.w(TAG, initError!!)
                    return false
                }
                Log.i(TAG, "正在从外部存储复制模型到内部目录...")
                copyModelToInternal(externalModelDir, internalModelDir)
            }

            if (!isModelValid(internalModelDir)) {
                initError = "模型文件不完整，请检查 vosk-model-small-cn-0.22 目录"
                Log.w(TAG, initError!!)
                return false
            }

            // 从内部目录加载模型
            model = Model(internalModelDir.absolutePath)
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat()).apply {
                setWords(true)
            }
            isInitialized = true
            Log.i(TAG, "Vosk 引擎初始化成功")
            return true
        } catch (e: Exception) {
            initError = "模型初始化失败: ${e.localizedMessage ?: "未知错误"}"
            Log.e(TAG, initError!!, e)
            return false
        }
    }

    /**
     * App 内部模型目录
     */
    private fun getInternalModelDir(): File {
        return File(context.filesDir, "vosk-model-cn")
    }

    /**
     * 检查模型目录是否有效（包含必要的文件）
     */
    private fun isModelValid(dir: File): Boolean {
        if (!dir.exists()) return false
        return File(dir, "am/final.mdl").exists()
    }

    /**
     * 在外部存储中搜索模型
     */
    private fun findExternalModelDir(): File? {
        val searchPaths = listOf(
            "/storage/emulated/0/VoskModels",
            "/sdcard/VoskModels",
            "/storage/emulated/0/Android/data/${context.packageName}/files/vosk-model",
            context.filesDir.absolutePath + "/vosk-model"
        )

        for (basePath in searchPaths) {
            val baseDir = File(basePath)
            if (!baseDir.exists()) continue

            // 子目录中查找
            val dirs = baseDir.listFiles { f -> f.isDirectory } ?: emptyArray()
            for (dir in dirs) {
                if (File(dir, "am/final.mdl").exists()) {
                    Log.i(TAG, "找到外部模型: ${dir.absolutePath}")
                    return dir
                }
            }

            // basePath 本身就是模型目录
            if (File(baseDir, "am/final.mdl").exists()) {
                Log.i(TAG, "找到外部模型: ${baseDir.absolutePath}")
                return baseDir
            }
        }
        return null
    }

    /**
     * 将模型从外部目录复制到内部目录
     */
    private fun copyModelToInternal(source: File, dest: File) {
        if (dest.exists()) {
            dest.deleteRecursively()
        }
        dest.mkdirs()

        source.copyRecursively(dest, overwrite = true)
        Log.i(TAG, "模型复制完成: ${dest.absolutePath}")
    }

    /**
     * 识别 PCM 音频数据
     */
    fun recognize(pcmData: ShortArray): String {
        val rec = recognizer ?: return initError ?: "引擎未就绪"

        return try {
            val byteData = ByteArray(pcmData.size * 2)
            for (i in pcmData.indices) {
                val v = pcmData[i].toInt()
                byteData[i * 2] = (v and 0xFF).toByte()
                byteData[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }

            rec.acceptWaveForm(byteData, byteData.size)
            val finalJson = rec.getFinalResult()
            extractText(finalJson)
        } catch (e: Exception) {
            Log.e(TAG, "识别失败", e)
            "[识别错误]"
        }
    }

    private fun extractText(jsonResult: String): String {
        return try {
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
