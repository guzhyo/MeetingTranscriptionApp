package com.example.meetingtranscription

import android.content.Context
import android.util.Log
import org.vosk.*
import java.io.*
import java.util.zip.ZipInputStream

/**
 * Vosk 离线语音识别引擎封装
 *
 * 模型从 assets 解压到 App 内部目录后加载
 */
class VoskEngine(private val context: Context) {

    companion object {
        private const val TAG = "VoskEngine"
        const val SAMPLE_RATE = 16000
        private const val ASSETS_MODEL_ZIP = "models/vosk-model-small-cn-0.22.zip"
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var isInitialized = false
    private var initError: String? = null

    fun initialize(): Boolean {
        if (isInitialized) return true

        try {
            val modelDir = getModelDir()

            // 检查模型是否已解压
            if (!isModelValid(modelDir)) {
                // 从 assets 解压
                if (!extractModelFromAssets(modelDir)) {
                    return fail("从 assets 解压模型失败")
                }
            }

            if (!isModelValid(modelDir)) {
                return fail("模型文件不完整（缺少 am/final.mdl）")
            }

            model = Model(modelDir.absolutePath)
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
        initError = msg; Log.w(TAG, msg); return false
    }

    private fun getModelDir(): File = File(context.filesDir, "vosk-model-cn")

    private fun isModelValid(dir: File): Boolean {
        return File(dir, "am/final.mdl").exists()
    }

    /**
     * 从 assets 解压模型 zip 到内部存储
     */
    private fun extractModelFromAssets(destDir: File): Boolean {
        try {
            // 删除旧的解压目录
            if (destDir.exists()) {
                deleteDir(destDir)
            }
            destDir.mkdirs()

            // 读取 assets 中的 zip 文件
            val zipBytes = readAssetBytes(ASSETS_MODEL_ZIP)
            if (zipBytes == null) {
                Log.w(TAG, "assets 中未找到模型 zip: $ASSETS_MODEL_ZIP")
                return false
            }

            val zipStream = ZipInputStream(ByteArrayInputStream(zipBytes))
            var entry = zipStream.nextEntry
            val buffer = ByteArray(8192)

            while (entry != null) {
                if (!entry.isDirectory) {
                    // 去除顶层目录（zip 中可能包含 vosk-model-small-cn-0.22/ 前缀）
                    val name = stripTopDir(entry.name)
                    if (name.isNotEmpty()) {
                        val outFile = File(destDir, name)
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            var n: Int
                            while (zipStream.read(buffer).also { n = it } >= 0) {
                                fos.write(buffer, 0, n)
                            }
                        }
                    }
                }
                zipStream.closeEntry()
                entry = zipStream.nextEntry
            }
            zipStream.close()

            Log.i(TAG, "模型解压完成: ${destDir.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "解压模型失败", e)
            return false
        }
    }

    /**
     * 读取 assets 文件的全部字节
     */
    private fun readAssetBytes(path: String): ByteArray? {
        return try {
            context.assets.open(path).use { input ->
                val baos = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    baos.write(buf, 0, n)
                }
                baos.toByteArray()
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取 assets 失败: $path", e)
            null
        }
    }

    /**
     * 去除 zip 条目路径的顶层目录
     * 例如 "vosk-model-small-cn-0.22/am/final.mdl" -> "am/final.mdl"
     */
    private fun stripTopDir(path: String): String {
        val idx = path.indexOf('/')
        return if (idx >= 0) path.substring(idx + 1) else path
    }

    private fun deleteDir(dir: File) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.isDirectory) deleteDir(f) else f.delete()
        }
        dir.delete()
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
