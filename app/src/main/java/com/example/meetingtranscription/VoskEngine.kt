package com.example.meetingtranscription

import android.content.Context
import android.util.Log
import org.vosk.*
import java.io.*

/**
 * Vosk 离线语音识别引擎封装
 *
 * 从 /sdcard/VoskModels/ 读取模型，逐个文件复制到 App 内部目录后加载
 * 这样避免 Android 11+ 对外部存储的直接访问限制
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
            val internalDir = getInternalModelDir()

            // 检查内部是否已有有效模型
            if (!isModelValid(internalDir)) {
                // 从外部存储复制
                val externalDir = findExternalModelDir()
                if (externalDir == null) {
                    initError = "未找到 Vosk 模型，请将 vosk-model-small-cn-0.22 放入手机 VoskModels/ 目录"
                    Log.w(TAG, initError!!)
                    return false
                }
                Log.i(TAG, "正在复制模型到内部目录...")
                copyDir(externalDir, internalDir)
            }

            // 再次检查
            if (!isModelValid(internalDir)) {
                initError = "模型文件不完整（缺少 am/final.mdl）"
                Log.w(TAG, initError!!)
                return false
            }

            model = Model(internalDir.absolutePath)
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

    private fun getInternalModelDir(): File {
        return File(context.filesDir, "vosk-model-cn")
    }

    private fun isModelValid(dir: File): Boolean {
        return dir.exists() && File(dir, "am/final.mdl").exists()
    }

    private fun findExternalModelDir(): File? {
        val paths = listOf(
            "/storage/emulated/0/VoskModels",
            "/sdcard/VoskModels"
        )
        for (basePath in paths) {
            val base = try { File(basePath) } catch (e: Exception) { null } ?: continue
            if (!base.exists()) continue
            val dirs = base.listFiles { f -> f.isDirectory } ?: emptyArray()
            for (dir in dirs) {
                if (File(dir, "am/final.mdl").exists()) return dir
            }
            if (File(base, "am/final.mdl").exists()) return base
        }
        return null
    }

    /**
     * 逐个文件递归复制（兼容 Android 文件系统）
     */
    private fun copyDir(src: File, dst: File) {
        if (dst.exists()) dst.deleteRecursively()
        dst.mkdirs()

        src.walkTopDown().forEach { srcFile ->
            val relPath = srcFile.relativeTo(src)
            val dstFile = File(dst, relPath.path)

            if (srcFile.isDirectory) {
                dstFile.mkdirs()
            } else if (srcFile.isFile) {
                try {
                    srcFile.inputStream().use { input ->
                        dstFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "复制文件失败: ${srcFile.name}", e)
                }
            }
        }
        Log.i(TAG, "模型复制完成: ${dst.absolutePath}")
    }

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
            extractText(rec.getFinalResult())
        } catch (e: Exception) {
            Log.e(TAG, "识别失败", e)
            "[识别错误]"
        }
    }

    private fun extractText(json: String): String {
        return try {
            val key = "\"text\" : \""
            val s = json.indexOf(key)
            if (s >= 0) {
                val start = s + key.length
                val end = json.indexOf("\"", start)
                if (end > start) json.substring(start, end) else ""
            } else ""
        } catch (e: Exception) { "" }
    }

    fun isReady(): Boolean = isInitialized
    fun getInitError(): String? = initError

    fun release() {
        try { recognizer?.close() } catch (_: Exception) {}
        try { model?.close() } catch (_: Exception) {}
        recognizer = null; model = null; isInitialized = false
    }
}
