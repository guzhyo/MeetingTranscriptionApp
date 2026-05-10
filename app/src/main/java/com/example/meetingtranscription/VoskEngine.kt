package com.example.meetingtranscription

import android.content.Context
import android.util.Log
import org.vosk.*
import java.io.*

/**
 * Vosk 离线语音识别引擎封装
 *
 * 核心设计：
 * - feedPcm() 在录音过程中实时识别，累积完整段落
 * - 停止时调用 finish() 获取最终剩余结果
 * - 不依赖 reset()（避免兼容性问题）
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

    // === 累积的完整段落文本 ===
    private var accumulatedText = ""
    private var sessionActive = false

    fun initialize(): Boolean {
        if (isInitialized) return true
        try {
            val modelDir = getModelDir()
            if (!isModelValid(modelDir)) {
                if (!extractModel(modelDir)) return fail("解压模型失败")
            }
            if (!isModelValid(modelDir)) return fail("模型文件不完整")
            model = Model(modelDir.absolutePath)
            // 全程只创建一次 Recognizer
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

    // ========== 会话管理（简化版） ==========

    /** 开始新录音。不调 reset（怕兼容问题），只清空累积文本 */
    fun startSession() {
        accumulatedText = ""
        sessionActive = true
        Log.i(TAG, "会话开始")
    }

    /**
     * 结束录音，获取完整结果
     * Vosk 的 getFinalResult() 返回识别器剩余的内部文本
     * 注意：必须在调 finish() 之前停止 feedPcm() 调用
     */
    fun finish(): String {
        sessionActive = false
        val rec = recognizer
        if (rec == null) return accumulatedText

        return try {
            val finalJson = rec.getFinalResult()
            val finalText = extractText(finalJson)
            Log.i(TAG, "finish: acc='${accumulatedText.take(40)}' final='${finalText.take(40)}'")

            // 合并
            return if (finalText.isNotEmpty()) {
                val fp = addPunctuation(finalText)
                if (accumulatedText.isNotEmpty()) "$accumulatedText $fp" else fp
            } else {
                accumulatedText
            }
        } catch (e: Exception) {
            Log.e(TAG, "finish error", e)
            accumulatedText
        }
    }

    fun isReady(): Boolean = isInitialized
    fun getInitError(): String? = initError

    // ========== 标点恢复 ==========

    private fun addPunctuation(text: String): String {
        if (text.isEmpty()) return text
        var r = text.trim()
        // 语气词/疑问词 → 问号
        r = r.replace(Regex("(吗|呢|吧|么|啊|呀|哈|哦|嘛|嗯|哈|哪|啥|怎|怎么|如何|为什么|什么|哪个|谁|几|多[久少好])(?=\\s*$|，)"), "$1？")
        // 感叹类 → 感叹号
        r = r.replace(Regex("(吧|啊|呀|哈|哦|嘛|啦|哇|哟|呢|了)(?=\\s*$|，)"), "$1！")
        // 连接词 → 逗号
        r = r.replace(Regex("(你好|您好|谢谢|请问|好的|对了|话说|那么|所以|但是|不过|然而|而且|因为|虽然|如果|然后|首先|其次|最后|总之|此外|另外|比如|例如|特别是|尤其是)"), "$1，")
        // 结尾加句号
        if (!r.endsWith("。") && !r.endsWith("？") && !r.endsWith("！") && !r.endsWith("，")) r += "。"
        return r
    }

    private fun finalizeSegment(text: String): String {
        if (text.isEmpty()) return text
        var r = text.trim()
        if (!r.endsWith("。") && !r.endsWith("？") && !r.endsWith("！")) r += "。"
        return r
    }

    // ========== 实时识别 ==========

    /**
     * 喂 PCM 数据给识别器
     *
     * Vosk 语义：
     * - acceptWaveform() 返回 true → getResult() 取完整段落
     * - 返回 false → getPartialResult() 取实时预览
     *
     * 返回的字符串 = 已累积段落 + 当前预览
     */
    fun feedPcm(pcmData: ShortArray): String {
        val rec = recognizer ?: return accumulatedText
        if (!sessionActive) return accumulatedText
        return try {
            val bytes = ByteArray(pcmData.size * 2)
            for (i in pcmData.indices) {
                val v = pcmData[i].toInt()
                bytes[i*2] = (v and 0xFF).toByte()
                bytes[i*2+1] = ((v shr 8) and 0xFF).toByte()
            }
            val hasResult = rec.acceptWaveForm(bytes, bytes.size)

            if (hasResult) {
                val resultJson = rec.getResult()
                val resultText = extractText(resultJson)
                if (resultText.isNotEmpty()) {
                    accumulatedText += finalizeSegment(resultText)
                }
            }

            val partial = extractText(rec.getPartialResult())
            val displayPartial = addPunctuation(partial)
            return if (accumulatedText.isNotEmpty()) {
                if (displayPartial.isNotEmpty()) "$accumulatedText $displayPartial" else accumulatedText
            } else {
                displayPartial
            }
        } catch (e: Exception) {
            Log.w(TAG, "feedPcm error", e)
            accumulatedText
        }
    }

    private fun extractText(json: String): String {
        return try {
            val k = "\"text\" : \""
            val s = json.indexOf(k)
            if (s >= 0) {
                val start = s + k.length
                val e = json.indexOf("\"", start)
                if (e > start) json.substring(start, e) else ""
            } else ""
        } catch (_: Exception) { "" }
    }

    fun release() {
        sessionActive = false
        try { recognizer?.close() } catch (_: Exception) {}
        try { model?.close() } catch (_: Exception) {}
        recognizer = null; model = null; isInitialized = false
    }
}
