package com.example.meetingtranscription

import android.content.Context
import android.util.Log
import org.vosk.*
import java.io.*

/**
 * Vosk 离线语音识别引擎封装
 *
 * 从 /sdcard/VoskModels/ 读取模型目录，递归复制到 App 内部目录后加载
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
            if (!internalDir.exists() || !isModelValid(internalDir)) {
                // 查找外部模型
                val modelDir = findModelDir() ?: return fail("未找到 Vosk 模型，请将 vosk-model-small-cn-0.22 放入手机 VoskModels/ 目录")

                // 删除旧的内部模型，重新复制
                if (internalDir.exists()) deleteDir(internalDir)
                internalDir.mkdirs()

                if (!copyDir(modelDir, internalDir)) {
                    return fail("复制模型失败")
                }
            }

            if (!isModelValid(internalDir)) {
                return fail("模型文件不完整（缺少 am/final.mdl）")
            }

            model = Model(internalDir.absolutePath)
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat()).apply { setWords(true) }
            isInitialized = true
            Log.i(TAG, "Vosk 引擎初始化成功")
            return true
        } catch (e: Exception) {
            initError = "模型初始化失败: ${e.localizedMessage ?: "未知错误"}"
            Log.e(TAG, initError!!, e)
            return false
        }
    }

    private fun fail(msg: String): Boolean {
        initError = msg
        Log.w(TAG, msg)
        return false
    }

    private fun getInternalModelDir(): File = File(context.filesDir, "vosk-model-cn")

    private fun isModelValid(dir: File): Boolean {
        val mdl = File(dir, "am/final.mdl")
        return mdl.exists() && mdl.length() > 0
    }

    /** 查找外部存储中的 Vosk 模型目录 */
    private fun findModelDir(): File? {
        for (basePath in listOf("/storage/emulated/0/VoskModels", "/sdcard/VoskModels")) {
            val base = File(basePath)
            if (!base.exists()) continue

            // 子目录（如 vosk-model-small-cn-0.22）
            val dirs = base.listFiles() ?: continue
            for (d in dirs) {
                if (d.isDirectory && File(d, "am/final.mdl").exists()) {
                    Log.i(TAG, "找到模型: ${d.absolutePath}")
                    return d
                }
            }

            // 也可能 base 本身是模型目录
            if (File(base, "am/final.mdl").exists()) {
                Log.i(TAG, "找到模型: ${base.absolutePath}")
                return base
            }
        }
        return null
    }

    /** 递归复制目录（兼容 Android API 24） */
    private fun copyDir(src: File, dst: File): Boolean {
        try {
            val queue = ArrayDeque<Pair<File, File>>()
            queue.add(Pair(src, dst))

            while (queue.isNotEmpty()) {
                val (srcDir, dstDir) = queue.removeFirst()
                dstDir.mkdirs()

                val entries = srcDir.listFiles() ?: continue
                for (entry in entries) {
                    val destFile = File(dstDir, entry.name)
                    if (entry.isDirectory) {
                        queue.add(Pair(entry, destFile))
                    } else {
                        copyFile(entry, destFile)
                    }
                }
            }
            Log.i(TAG, "模型复制完成: ${dst.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "复制模型失败", e)
            return false
        }
    }

    private fun deleteDir(dir: File) {
        val all = dir.listFiles() ?: return
        for (f in all) {
            if (f.isDirectory) deleteDir(f)
            else f.delete()
        }
        dir.delete()
    }

    private fun copyFile(src: File, dst: File) {
        try {
            FileInputStream(src).use { `in` ->
                FileOutputStream(dst).use { out ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (`in`.read(buf).also { n = it } >= 0) {
                        out.write(buf, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "复制文件失败: ${src.name}", e)
        }
    }

    /** 识别 PCM ShortArray */
    fun recognize(pcmData: ShortArray): String {
        val rec = recognizer ?: return initError ?: "引擎未就绪"
        return try {
            val bytes = ByteArray(pcmData.size * 2)
            for (i in pcmData.indices) {
                val v = pcmData[i].toInt()
                bytes[i * 2] = (v and 0xFF).toByte()
                bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            rec.acceptWaveForm(bytes, bytes.size)
            extractText(rec.getFinalResult())
        } catch (e: Exception) {
            Log.e(TAG, "识别失败", e); "[识别错误]"
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
        } catch (_: Exception) { "" }
    }

    fun isReady(): Boolean = isInitialized
    fun getInitError(): String? = initError

    fun release() {
        try { recognizer?.close() } catch (_: Exception) {}
        try { model?.close() } catch (_: Exception) {}
        recognizer = null; model = null; isInitialized = false
    }
}
