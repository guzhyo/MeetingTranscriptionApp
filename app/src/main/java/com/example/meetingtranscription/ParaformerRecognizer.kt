package com.example.meetingtranscription

import ai.onnxruntime.*
import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.*

/**
 * Paraformer-small 离线语音识别模型封装
 * 使用 ONNX Runtime 在移动端进行本地推理
 */
class ParaformerRecognizer(context: Context) {

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var isInitialized = false

    // 音频预处理参数
    companion object {
        const val SAMPLE_RATE = 16000
        const val N_MELS = 80
        const val N_FFT = 400
        const val HOP_LENGTH = 160
        const val CHUNK_LENGTH = 30 // 30秒分块
    }

    init {
        try {
            initModel(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 初始化 ONNX 模型
     * 模型文件应放在 assets 目录下
     */
    private fun initModel(context: Context) {
        ortEnvironment = OrtEnvironment.getEnvironment()
        
        // 从 assets 复制模型到本地存储
        val modelFile = copyModelFromAssets(context, "paraformer-small.onnx")
        
        if (modelFile != null && modelFile.exists()) {
            val sessionOptions = OrtSession.SessionOptions().apply {
                // 移动端优化选项
                setIntraOpNumThreads(2) // 使用2个线程
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            
            ortSession = ortEnvironment?.createSession(modelFile.absolutePath, sessionOptions)
            isInitialized = true
        }
    }

    /**
     * 从 assets 复制模型文件
     */
    private fun copyModelFromAssets(context: Context, modelName: String): File? {
        val modelFile = File(context.filesDir, modelName)
        
        if (modelFile.exists()) return modelFile
        
        return try {
            context.assets.open(modelName).use { inputStream ->
                FileOutputStream(modelFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            modelFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 执行语音识别
     * @param audioData 16kHz 采样率的 PCM 音频数据
     * @return 识别文本
     */
    fun recognize(audioData: ShortArray): String {
        if (!isInitialized || ortSession == null) {
            return ""
        }

        return try {
            // 1. 音频预处理 - 转换为梅尔频谱
            val melSpectrogram = audioToMelSpectrogram(audioData)
            
            // 2. 准备输入张量
            val inputTensor = createInputTensor(melSpectrogram)
            
            // 3. 运行推理
            val results = ortSession?.run(mapOf("input" to inputTensor))
            
            // 4. 解码输出
            val outputTensor = results?.get(0)
            decodeOutput(outputTensor)
            
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    /**
     * 音频转梅尔频谱 (简化版)
     */
    private fun audioToMelSpectrogram(audioData: ShortArray): Array<FloatArray> {
        // 1. 归一化到 [-1, 1]
        val normalized = audioData.map { it / 32768.0f }.toFloatArray()
        
        // 2. 预加重
        val preemphasized = FloatArray(normalized.size)
        preemphasized[0] = normalized[0]
        for (i in 1 until normalized.size) {
            preemphasized[i] = normalized[i] - 0.97f * normalized[i - 1]
        }
        
        // 3. 分帧和加窗
        val numFrames = (preemphasized.size - N_FFT) / HOP_LENGTH + 1
        val frames = Array(numFrames) { frameIdx ->
            val frame = FloatArray(N_FFT)
            for (i in 0 until N_FFT) {
                val sampleIdx = frameIdx * HOP_LENGTH + i
                val window = 0.54f - 0.46f * cos(2 * PI * i / (N_FFT - 1)).toFloat()
                frame[i] = if (sampleIdx < preemphasized.size) preemphasized[sampleIdx] * window else 0f
            }
            frame
        }
        
        // 4. FFT 和梅尔滤波 (简化实现)
        val melSpec = Array(N_MELS) { FloatArray(numFrames) { 0f } }
        
        // 简化的梅尔频谱计算
        for (frameIdx in 0 until numFrames) {
            val frame = frames[frameIdx]
            val fft = performFFT(frame)
            
            // 简化的梅尔滤波
            for (melBin in 0 until N_MELS) {
                var energy = 0f
                for (freqBin in 0 until N_FFT / 2) {
                    val melWeight = getMelWeight(freqBin, melBin)
                    energy += fft[freqBin] * melWeight
                }
                melSpec[melBin][frameIdx] = ln(energy + 1e-10f)
            }
        }
        
        return melSpec
    }

    /**
     * 简化 FFT 实现
     */
    private fun performFFT(frame: FloatArray): FloatArray {
        val magnitude = FloatArray(frame.size / 2)
        // 简化的 FFT - 实际应使用 FFT 库
        for (k in magnitude.indices) {
            var real = 0f
            var imag = 0f
            for (n in frame.indices) {
                val angle = -2 * PI * k * n / frame.size
                real += frame[n] * cos(angle).toFloat()
                imag += frame[n] * sin(angle).toFloat()
            }
            magnitude[k] = sqrt(real * real + imag * imag)
        }
        return magnitude
    }

    /**
     * 获取梅尔滤波器权重
     */
    private fun getMelWeight(freqBin: Int, melBin: Int): Float {
        // 简化的梅尔滤波器
        val melFreq = 2595 * log10(1 + freqBin * SAMPLE_RATE / N_FFT / 700.0)
        val melCenter = melBin * 2595 * log10(1 + SAMPLE_RATE / 2 / 700.0) / N_MELS
        val diff = abs(melFreq - melCenter)
        return max(0f, 1 - diff.toFloat() / 100)
    }

    /**
     * 创建 ONNX 输入张量
     */
    private fun createInputTensor(melSpectrogram: Array<FloatArray>): OnnxTensor {
        val numFrames = melSpectrogram[0].size
        val flattened = FloatArray(N_MELS * numFrames)
        
        for (i in 0 until N_MELS) {
            for (j in 0 until numFrames) {
                flattened[i * numFrames + j] = melSpectrogram[i][j]
            }
        }
        
        val shape = longArrayOf(1, N_MELS.toLong(), numFrames.toLong())
        return OnnxTensor.createTensor(ortEnvironment, FloatBuffer.wrap(flattened), shape)
    }

    /**
     * 解码模型输出
     */
    private fun decodeOutput(outputTensor: OnnxValue?): String {
        // 简化的解码 - 实际应根据模型输出格式解析
        return outputTensor?.value?.toString() ?: ""
    }

    /**
     * 释放资源
     */
    fun release() {
        ortSession?.close()
        ortEnvironment?.close()
        isInitialized = false
    }
}
