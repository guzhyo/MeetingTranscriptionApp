package com.example.meetingtranscription

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.*

class TranscriptionService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "transcription_channel"
        const val SAMPLE_RATE = 16000
        const val BUFFER_SIZE = 3200
        private const val TAG = "TranscriptionService"
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var outputWavFile: File? = null
    private var outputTxtFile: File? = null
    private var hasError = false
    private var pcmBuffer = ByteArrayOutputStream()
    private var totalSamples = 0
    private var paraformerEngine: ParaformerEngine? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 初始化 Paraformer 引擎
        paraformerEngine = ParaformerEngine(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun getRecordingsDir(): File {
        val dir = File(filesDir, "recordings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.channel_description)
                    setSound(null, null)
                    enableVibration(false)
                }
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.createNotificationChannel(channel)
            } catch (e: Exception) {
                Log.e(TAG, "创建通知渠道失败", e)
            }
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_content))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MeetingTranscription::RecordingWakeLock"
            ).apply {
                acquire(10 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取 WakeLock 失败", e)
        }
    }

    private fun startRecording() {
        if (isRecording) return
        hasError = false
        pcmBuffer.reset()
        totalSamples = 0

        try {
            startForeground(NOTIFICATION_ID, createNotification())
            acquireWakeLock()

            // 检查 Paraformer 引擎
            val engine = paraformerEngine
            if (engine != null && !engine.isReady()) {
                Log.i(TAG, "初始化 Paraformer 引擎...")
                engine.initialize()
            }

            // 创建输出文件
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val recordingsDir = getRecordingsDir()
            outputWavFile = File(recordingsDir, "录音_$timestamp.wav")
            outputTxtFile = File(recordingsDir, "录音_$timestamp.txt")

            // 初始化为 PCM 16-bit 16kHz 格式
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                throw IllegalStateException("不支持的录音参数")
            }

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize.coerceAtLeast(BUFFER_SIZE * 2)
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("录音初始化失败，请检查麦克风权限")
            }

            audioRecord?.startRecording()
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("无法开始录音，麦克风可能被占用")
            }

            isRecording = true

            // 启动录音循环
            serviceScope.launch {
                try {
                    recordAudio()
                } catch (e: Exception) {
                    Log.e(TAG, "录音循环异常", e)
                    hasError = true
                    withContext(Dispatchers.Main) {
                        stopRecording()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动录音失败", e)
            handleStartError(e)
        }
    }

    private suspend fun recordAudio() {
        val buffer = ShortArray(BUFFER_SIZE)

        while (isRecording && !hasError) {
            val readSize = try {
                audioRecord?.read(buffer, 0, BUFFER_SIZE) ?: -1
            } catch (e: Exception) {
                Log.e(TAG, "读取音频数据失败", e)
                -1
            }

            if (readSize > 0) {
                // 保存 PCM 数据
                for (i in 0 until readSize) {
                    val v = buffer[i].toInt()
                    pcmBuffer.write(v and 0xFF)
                    pcmBuffer.write((v shr 8) and 0xFF)
                }
                totalSamples += readSize
            } else if (readSize < 0) {
                delay(100)
            } else {
                delay(10)
            }
        }
    }

    private fun handleStartError(e: Exception) {
        hasError = true
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("录音启动失败")
                .setContentText(e.localizedMessage ?: "未知错误")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(false)
                .build()
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID + 1, notification)
        } catch (_: Exception) {}
        cleanupResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopRecording() {
        if (!isRecording && !hasError) return
        isRecording = false

        serviceScope.launch {
            try {
                val pcmData = pcmBuffer.toByteArray()

                if (pcmData.size > 32000) { // 至少 1 秒
                    // 1. 保存 WAV 文件
                    saveWavFile(pcmData)

                    // 2. 用 Paraformer 进行语音识别
                    val transcript = recognizeWithParaformer(pcmData)

                    // 3. 保存转录文件
                    saveTranscriptFile(transcript)

                } else {
                    Log.w(TAG, "录音太短，不保存")
                    outputWavFile?.delete()
                    outputTxtFile?.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "停止录音时出错", e)
            }

            cleanupResources()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * 保存 WAV 文件
     */
    private fun saveWavFile(pcmData: ByteArray) {
        val wavFile = outputWavFile ?: return
        try {
            RandomAccessFile(wavFile, "rw").use { raf ->
                raf.writeBytes("RIFF")
                raf.writeInt(Integer.reverseBytes(36 + pcmData.size))
                raf.writeBytes("WAVE")
                raf.writeBytes("fmt ")
                raf.writeInt(Integer.reverseBytes(16))
                raf.writeShort(Integer.reverseBytes(1)) // PCM
                raf.writeShort(Integer.reverseBytes(1)) // mono
                raf.writeInt(Integer.reverseBytes(16000)) // sample rate
                raf.writeInt(Integer.reverseBytes(32000)) // byte rate
                raf.writeShort(Integer.reverseBytes(2)) // block align
                raf.writeShort(Integer.reverseBytes(16)) // bits per sample
                raf.writeBytes("data")
                raf.writeInt(Integer.reverseBytes(pcmData.size))
                raf.write(pcmData)
            }
            Log.i(TAG, "WAV 文件已保存: ${wavFile.name}, ${pcmData.size / 32000}秒")
        } catch (e: Exception) {
            Log.e(TAG, "保存 WAV 文件失败", e)
        }
    }

    /**
     * 用 Paraformer 识别语音
     */
    private fun recognizeWithParaformer(pcmData: ByteArray): String {
        val engine = paraformerEngine
        if (engine == null) {
            return "[错误: 引擎未初始化]"
        }

        // 确保引擎已初始化
        if (!engine.isReady()) {
            val ok = engine.initialize()
            if (!ok) {
                return engine.getInitError() ?: "模型初始化失败，请将 paraformer-small.onnx 放入手机存储的 ParaformerModels/ 目录"
            }
        }

        // 将 PCM 字节数据转为 ShortArray
        val shortArray = ShortArray(pcmData.size / 2)
        for (i in shortArray.indices) {
            val low = pcmData[i * 2].toInt() and 0xFF
            val high = pcmData[i * 2 + 1].toInt() and 0xFF
            shortArray[i] = (low or (high shl 8)).toShort()
        }

        val text = engine.recognize(shortArray)
        return text
    }

    /**
     * 保存转录文字文件
     */
    private fun saveTranscriptFile(transcript: String) {
        val txtFile = outputTxtFile ?: return
        val wavFile = outputWavFile ?: return

        try {
            val durationSec = if (wavFile.exists()) {
                wavFile.length() / 32000
            } else 0

            txtFile.writeText("会议录音 ${wavFile.nameWithoutExtension}\n")
            txtFile.appendText("录音时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
            txtFile.appendText("录音时长: ${durationSec}秒\n")
            txtFile.appendText("采样率: 16kHz, 16bit, 单声道\n")
            txtFile.appendText("识别引擎: Paraformer-small (ONNX Runtime)\n")
            txtFile.appendText("---\n\n")

            if (transcript.isNotEmpty() && !transcript.startsWith("[") && !transcript.startsWith("模型")) {
                txtFile.appendText(transcript)
            } else if (transcript.isNotEmpty()) {
                txtFile.appendText("[识别结果] $transcript")
            } else {
                txtFile.appendText("[未检测到语音内容]")
            }

            Log.i(TAG, "转录文件已保存: ${txtFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "保存转录文件失败", e)
        }
    }

    private fun cleanupResources() {
        audioRecord?.apply {
            try { stop() } catch (_: Exception) {}
            try { release() } catch (_: Exception) {}
        }
        audioRecord = null

        wakeLock?.let {
            try { if (it.isHeld) it.release() } catch (_: Exception) {}
        }
        wakeLock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        hasError = true
        serviceScope.cancel()
        cleanupResources()
        paraformerEngine?.release()
        paraformerEngine = null
    }
}
