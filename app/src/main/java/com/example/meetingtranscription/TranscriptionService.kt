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
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private val transcriptionBuffer = StringBuilder()
    private var outputFile: File? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MeetingTranscription::RecordingWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minutes timeout
        }
    }

    private fun startRecording() {
        if (isRecording) return

        startForeground(NOTIFICATION_ID, createNotification())
        
        // 创建输出文件
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        outputFile = File(filesDir, "transcription_$timestamp.txt")
        
        // 初始化录音
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize.coerceAtLeast(BUFFER_SIZE * 2)
        )

        audioRecord?.startRecording()
        isRecording = true

        // 启动录音循环
        serviceScope.launch {
            recordAndTranscribe()
        }

        // 启动转录处理
        serviceScope.launch {
            processTranscription()
        }
    }

    private suspend fun recordAndTranscribe() {
        val buffer = ShortArray(BUFFER_SIZE)
        
        while (isRecording && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            val readSize = audioRecord?.read(buffer, 0, BUFFER_SIZE) ?: 0
            if (readSize > 0) {
                // 将音频数据传递给转录引擎
                processAudioChunk(buffer.copyOfRange(0, readSize))
            }
            delay(10) // 10ms 间隔
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
        while (isRecording) {
            delay(5000) // 每5秒保存一次
            saveTranscription()
        }
    }

    private fun saveTranscription() {
        try {
            outputFile?.appendText(transcriptionBuffer.toString())
            transcriptionBuffer.clear()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun stopRecording() {
        isRecording = false
        
        serviceScope.launch {
            saveTranscription()
            
            audioRecord?.apply {
                stop()
                release()
            }
            audioRecord = null
            
            wakeLock?.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (isRecording) {
            stopRecording()
        }
    }

    // 获取转录内容供UI显示
    fun getCurrentTranscription(): String = transcriptionBuffer.toString()
}
