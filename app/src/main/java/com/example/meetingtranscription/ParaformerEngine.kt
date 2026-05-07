package com.example.meetingtranscription

import android.content.Context
import android.util.Log
import ai.onnxruntime.*
import java.io.*
import kotlin.math.*

/**
 * Paraformer-small ONNX 离线语音识别引擎
 * 
 * 模型来源：ModelScope - FunASR
 * https://www.modelscope.cn/models/damo/speech_paraformer_asr_nat-zh-cn-16k-common-vocab8404-pytorch
 * 
 * 需要将 paraformer-small.onnx 放入手机存储的 ParaformerModels/ 目录
 */
class ParaformerEngine(private val context: Context) {

    companion object {
        private const val TAG = "ParaformerEngine"
        const val SAMPLE_RATE = 16000
        private const val MODEL_NAME = "paraformer-small.onnx"
    }

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isInitialized = false
    private var initError: String? = null

    /**
     * 初始化 ONNX 引擎
     */
    fun initialize(): Boolean {
        if (isInitialized) return true

        try {
            val modelFile = findModelFile()
            if (modelFile == null) {
                initError = "模型文件未找到，请将 $MODEL_NAME 放入手机存储的 ParaformerModels/ 目录"
                Log.w(TAG, initError)
                return false
            }

            ortEnvironment = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            ortSession = ortEnvironment?.createSession(modelFile.absolutePath, sessionOptions)
            isInitialized = true
            Log.i(TAG, "Paraformer 引擎初始化成功")
            return true

        } catch (e: Exception) {
            initError = "模型初始化失败: ${e.localizedMessage ?: "未知错误"}"
            Log.e(TAG, initError, e)
            return false
        }
    }

    /**
     * 查找模型文件
     */
    private fun findModelFile(): File? {
        // 1. 外部存储 ParaformerModels 目录
        val externalPaths = listOf(
            "/storage/emulated/0/ParaformerModels",
            "/sdcard/ParaformerModels",
            "/storage/emulated/0/Android/data/${context.packageName}/files/ParaformerModels"
        )
        for (path in externalPaths) {
            val file = File(path, MODEL_NAME)
            if (file.exists()) {
                Log.i(TAG, "找到外部模型: ${file.absolutePath}")
                return file
            }
            // 也检查子目录
            val dir = File(path)
            if (dir.isDirectory) {
                val found = dir.listFiles { f -> f.name.endsWith(".onnx") }?.firstOrNull()
                if (found != null) return found
            }
        }

        // 2. 内部存储
        val internalFile = File(context.filesDir, MODEL_NAME)
        if (internalFile.exists()) return internalFile

        // 3. Assets（如果有打包模型）
        return copyFromAssets()
    }

    /**
     * 从 assets 复制模型（如果有的话）
     */
    private fun copyFromAssets(): File? {
        return try {
            val destFile = File(context.filesDir, MODEL_NAME)
            context.assets.open(MODEL_NAME).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            destFile
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 对 PCM 音频数据进行语音识别
     * @param pcmData 16kHz 16-bit PCM 数据
     * @return 识别文本
     */
    fun recognize(pcmData: ShortArray): String {
        if (!isInitialized || ortSession == null) {
            return initError ?: "引擎未就绪"
        }

        return try {
            val session = ortSession!!
            val env = ortEnvironment!!

            // 1. 音频预处理 - 提取 FBank 特征
            val features = extractFBank(pcmData)
            val numFrames = features.size / 80 // 80-dim fbank

            if (numFrames == 0) return ""

            // 2. 创建输入张量 [1, 80, numFrames]
            val shape = longArrayOf(1, 80, numFrames.toLong())
            val inputTensor = OnnxTensor.createTensor(
                env, 
                java.nio.FloatBuffer.wrap(features), 
                shape
            )

            // 3. 运行推理
            val inputName = session.inputNames.iterator().next()
            val results = session.run(mapOf(inputName to inputTensor))

            // 4. 解码输出
            val output = results.get(0)
            val outputData = output.value as? Array<*> ?: return ""

            // 简化的 token 解码
            val tokens = outputData.mapNotNull { it as? FloatArray }
            if (tokens.isEmpty()) return ""

            val result = decodeTokens(tokens[0])
            results.close()
            result

        } catch (e: Exception) {
            Log.e(TAG, "推理失败", e)
            "[推理错误: ${e.localizedMessage ?: "未知错误"}]"
        }
    }

    /**
     * 提取 80-dim FBank 特征
     * 简化实现：计算梅尔频谱
     */
    private fun extractFBank(pcmData: ShortArray): FloatArray {
        val nFft = 512
        val hopLength = 160
        val nMels = 80

        // 归一化
        val normalized = FloatArray(pcmData.size) { i ->
            pcmData[i].toFloat() / 32768f
        }

        // 分帧
        val numFrames = (normalized.size - nFft) / hopLength + 1
        if (numFrames <= 0) return FloatArray(0)

        val fbank = FloatArray(nMels * numFrames)

        for (frameIdx in 0 until numFrames) {
            val start = frameIdx * hopLength

            // 加汉宁窗并做 FFT
            val real = FloatArray(nFft)
            val imag = FloatArray(nFft)

            for (i in 0 until nFft) {
                val window = 0.5f * (1f - cos(2 * PI * i / (nFft - 1))).toFloat()
                real[i] = if (start + i < normalized.size) normalized[start + i] * window else 0f
            }

            // 简单 DFT
            val mag = FloatArray(nFft / 2)
            for (k in 0 until nFft / 2) {
                var sumRe = 0f
                var sumIm = 0f
                for (n in 0 until nFft) {
                    val angle = -2 * PI * k * n / nFft
                    sumRe += real[n] * cos(angle).toFloat()
                    sumIm += real[n] * sin(angle).toFloat()
                }
                mag[k] = sqrt(sumRe * sumRe + sumIm * sumIm)
            }

            // 梅尔滤波 (简化)
            val melLow = 0f
            val melHigh = 2595f * log10(1f + SAMPLE_RATE / 2f / 700f)
            val melStep = melHigh / nMels

            for (m in 0 until nMels) {
                val melCenter = m * melStep
                var energy = 0f

                for (k in 0 until nFft / 2) {
                    val melFreq = 2595f * log10(1f + k * SAMPLE_RATE / nFft / 700f)
                    val diff = abs(melFreq - melCenter)
                    val weight = if (diff < melStep) 1f - diff / melStep else 0f
                    energy += (mag[k] * mag[k]) * weight
                }

                energy = maxOf(energy, 1e-10f)
                fbank[frameIdx * nMels + m] = ln(energy)
            }
        }

        return fbank
    }

    /**
     * 简化的 token 解码
     * 实际 Paraformer 使用词汇表来映射 token ID 到汉字
     * 这里用一个简单的映射
     */
    private fun decodeTokens(output: FloatArray): String {
        // 获取最大概率的 token ID
        val maxIdx = output.indices.maxByOrNull { output[it] } ?: return ""
        
        // Paraformer 中文词汇表 8404 个 token
        // 简单的映射：用 token ID 取模映射到常用汉字
        return if (maxIdx > 0 && maxIdx < 8404) {
            mapTokenToChar(maxIdx)
        } else {
            ""
        }
    }

    /**
     * Token ID 到汉字的简易映射
     * 注意：这是简化映射，实际需要完整的词汇表文件
     * 完整识别需要 vocab.txt 文件
     */
    private fun mapTokenToChar(tokenId: Int): String {
        // 尝试加载自定义词汇表
        val vocabFile = try {
            File(context.filesDir, "vocab.txt")
        } catch (e: Exception) { null }

        if (vocabFile != null && vocabFile.exists()) {
            try {
                val lines = vocabFile.readLines()
                if (tokenId < lines.size) {
                    val line = lines[tokenId].trim()
                    if (line.isNotEmpty()) return line
                }
            } catch (e: Exception) {
                Log.w(TAG, "读取词汇表失败", e)
            }
        }

        // 备选：从内置词汇表映射
        return try {
            val stream = context.assets.open("vocab.txt")
            val reader = BufferedReader(InputStreamReader(stream))
            var line: String?
            var idx = 0
            while (reader.readLine().also { line = it } != null) {
                if (idx == tokenId) {
                    val text = line!!.trim()
                    if (text.isNotEmpty()) return text
                    break
                }
                idx++
            }
            reader.close()
            ""
        } catch (e: Exception) {
            // 没有词汇表时返回 token ID
            "[token_$tokenId]"
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        try {
            ortSession?.close()
        } catch (_: Exception) {}
        try {
            ortEnvironment?.close()
        } catch (_: Exception) {}
        ortSession = null
        ortEnvironment = null
        isInitialized = false
    }

    fun isReady(): Boolean = isInitialized
    fun getInitError(): String? = initError
}
