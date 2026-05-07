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
        const val BUFFER_SIZE = 3200 // 100ms at 16kHz
        private const val TAG = "TranscriptionService"
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private val transcriptionBuffer = StringBuilder()
    private var outputFile: File? = null
    private var hasError = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
                acquire(10 * 60 * 1000L) // 10 minutes timeout
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取 WakeLock 失败", e)
        }
    }

    private fun startRecording() {
        if (isRecording) return
        hasError = false

        try {
            // 先启动前台服务
            startForeground(NOTIFICATION_ID, createNotification())
            acquireWakeLock()

            // 创建输出文件
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            outputFile = File(filesDir, "transcription_$timestamp.txt")

            // 初始化录音
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
                    recordAndTranscribe()
                } catch (e: Exception) {
                    Log.e(TAG, "录音循环异常", e)
                    hasError = true
                    withContext(Dispatchers.Main) {
                        stopRecording()
                    }
                }
            }

            // 启动转录保存循环
            serviceScope.launch {
                try {
                    processTranscription()
                } catch (e: Exception) {
                    Log.e(TAG, "转录保存异常", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动录音失败", e)
            hasError = true
            // 通知用户失败
            try {
                val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(getString(R.string.notification_title))
                    .setContentText("录音启动失败: ${e.localizedMessage ?: "未知错误"}")
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setOngoing(false)
                    .build()
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.notify(NOTIFICATION_ID + 1, notification)
            } catch (_: Exception) {}
            // 清理资源
            audioRecord?.apply {
                try { stop() } catch (_: Exception) {}
                try { release() } catch (_: Exception) {}
            }
            audioRecord = null
            wakeLock?.let {
                try { it.release() } catch (_: Exception) {}
            }
            wakeLock = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun recordAndTranscribe() {
        val buffer = ShortArray(BUFFER_SIZE)

        while (isRecording && !hasError) {
            val readSize = try {
                audioRecord?.read(buffer, 0, BUFFER_SIZE) ?: -1
            } catch (e: Exception) {
                Log.e(TAG, "读取音频数据失败", e)
                -1
            }

            if (readSize > 0) {
                processAudioChunk(buffer.copyOfRange(0, readSize))
            } else if (readSize < 0) {
                Log.w(TAG, "录音读取返回错误: $readSize")
                delay(100)
            } else {
                delay(10)
            }
        }
    }

    private fun processAudioChunk(audioData: ShortArray) {
        // 这里集成 Paraformer 模型进行实时转录
        // 由于模型需要预加载，这里使用模拟实现
        // 实际项目中需要加载 ONNX 模型并进行推理
        // TODO: 集成 Paraformer-small ONNX 模型
        // val result = paraformerModel.inference(audioData)
        // if (result.isNotEmpty()) {
        //     transcriptionBuffer.append(result).append(" ")
        // }
    }

    private suspend fun processTranscription() {
        while (isRecording && !hasError) {
            delay(5000) // 每5秒保存一次
            saveTranscription()
        }
        // 退出前保存剩余内容
        saveTranscription()
    }

    private fun saveTranscription() {
        if (transcriptionBuffer.isEmpty()) return
        try {
            outputFile?.appendText(transcriptionBuffer.toString())
            transcriptionBuffer.clear()
        } catch (e: IOException) {
            Log.e(TAG, "保存转录文件失败", e)
        }
    }

    private fun stopRecording() {
        isRecording = false
        hasError = true

        serviceScope.launch {
            saveTranscription()

            audioRecord?.apply {
                try {
                    if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        stop()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "停止录音时出错", e)
                }
                try {
                    release()
                } catch (e: Exception) {
                    Log.e(TAG, "释放录音资源时出错", e)
                }
            }
            audioRecord = null

            wakeLock?.let {
                try {
                    if (it.isHeld) {
                        it.release()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "释放 WakeLock 时出错", e)
                }
            }
            wakeLock = null

            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
                Log.e(TAG, "停止前台服务时出错", e)
            }
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        hasError = true
        serviceScope.cancel()

        audioRecord?.apply {
            try { stop() } catch (_: Exception) {}
            try { release() } catch (_: Exception) {}
        }
        audioRecord = null

        wakeLock?.let {
            try {
                if (it.isHeld) it.release()
            } catch (_: Exception) {}
        }
        wakeLock = null
    }
}
