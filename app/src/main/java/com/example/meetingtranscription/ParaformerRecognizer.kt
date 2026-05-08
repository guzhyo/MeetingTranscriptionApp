package com.example.meetingtranscription

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*

/**
 * Paraformer-small 离线语音识别模型封装
 * 使用 ONNX Runtime 在移动端进行本地推理
 */
class ParaformerRecognizer(context: Context) {

    companion object {
        const val SAMPLE_RATE = 16000
        const val N_MELS = 80
        const val N_FFT = 400
        const val HOP_LENGTH = 160
        const val CHUNK_LENGTH = 30
        private const val TAG = "ParaformerRecognizer"
    }

    private var isInitialized = false
    private var initError: String? = null

    init {
        try {
            initModel(context)
        } catch (e: Exception) {
            Log.e(TAG, "模型初始化失败", e)
            initError = e.localizedMessage ?: "初始化失败"
        }
    }

    private fun initModel(context: Context) {
        // 检查模型文件是否存在
        val modelFile = File(context.filesDir, "paraformer-small.onnx")
        if (!modelFile.exists()) {
            // 从 assets 复制
            val copied = copyModelFromAssets(context, "paraformer-small.onnx")
            if (copied == null || !copied.exists()) {
                Log.w(TAG, "模型文件不存在，请将 paraformer-small.onnx 放入 assets 目录")
                initError = "模型文件未找到"
                return
            }
        }
        isInitialized = true
    }

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
            Log.e(TAG, "复制模型文件失败", e)
            null
        }
    }

    fun recognize(audioData: ShortArray): String {
        if (!isInitialized) {
            return initError ?: "引擎未就绪"
        }

        return try {
            val melSpectrogram = audioToMelSpectrogram(audioData)
            val numFrames = melSpectrogram[0].size
            // 推理逻辑 - 需要 ONNX Runtime 加载模型后执行
            // TODO: 实际 ONNX 推理
            "[待集成模型推理]"
        } catch (e: Exception) {
            Log.e(TAG, "识别失败", e)
            ""
        }
    }

    private fun audioToMelSpectrogram(audioData: ShortArray): Array<FloatArray> {
        val normalized = audioData.map { it / 32768.0f }.toFloatArray()

        val preemphasized = FloatArray(normalized.size)
        preemphasized[0] = normalized[0]
        for (i in 1 until normalized.size) {
            preemphasized[i] = normalized[i] - 0.97f * normalized[i - 1]
        }

        val numFrames = (preemphasized.size - N_FFT) / HOP_LENGTH + 1
        if (numFrames <= 0) return Array(N_MELS) { FloatArray(1) { 0f } }

        val frames = Array(numFrames) { frameIdx ->
            val frame = FloatArray(N_FFT)
            for (i in 0 until N_FFT) {
                val sampleIdx = frameIdx * HOP_LENGTH + i
                val window = 0.54f - 0.46f * cos(2 * PI * i / (N_FFT - 1)).toFloat()
                frame[i] = if (sampleIdx < preemphasized.size) preemphasized[sampleIdx] * window else 0f
            }
            frame
        }

        val melSpec = Array(N_MELS) { FloatArray(numFrames) { 0f } }

        for (frameIdx in 0 until numFrames) {
            val frame = frames[frameIdx]
            val fft = performFFT(frame)

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

    private fun performFFT(frame: FloatArray): FloatArray {
        val magnitude = FloatArray(frame.size / 2)
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

    private fun getMelWeight(freqBin: Int, melBin: Int): Float {
        val melFreq = 2595 * log10(1 + freqBin * SAMPLE_RATE / N_FFT / 700.0)
        val melCenter = melBin * 2595 * log10(1 + SAMPLE_RATE / 2 / 700.0) / N_MELS
        val diff = abs(melFreq - melCenter)
        return max(0f, 1 - diff.toFloat() / 100)
    }

    fun isReady(): Boolean = isInitialized

    fun getInitError(): String? = initError

    fun release() {
        isInitialized = false
    }
}
