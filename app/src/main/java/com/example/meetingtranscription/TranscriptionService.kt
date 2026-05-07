package com.example.meetingtranscription

import android.app.*
import android.content.Context
import android.content.Intent
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
        private const val TAG = "TranscriptionService"
    }

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var outputMp3File: File? = null
    private var outputTxtFile: File? = null
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

        try {
            startForeground(NOTIFICATION_ID, createNotification())
            acquireWakeLock()

            // 创建输出文件
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val recordingsDir = getRecordingsDir()
            outputMp3File = File(recordingsDir, "录音_$timestamp.mp3")
            outputTxtFile = File(recordingsDir, "录音_$timestamp.txt")

            // 使用 MediaRecorder 直接录制 MP3
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(32000)
                setOutputFile(outputMp3File!!.absolutePath)

                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaRecorder 错误: what=$what, extra=$extra")
                    hasError = true
                }

                try {
                    prepare()
                } catch (e: Exception) {
                    throw IllegalStateException("录音准备失败: ${e.localizedMessage ?: "未知错误"}")
                }

                start()
            }

            isRecording = true
            Log.i(TAG, "录音已启动: ${outputMp3File!!.name}")

        } catch (e: Exception) {
            Log.e(TAG, "启动录音失败", e)
            handleStartError(e)
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
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID + 1, notification)
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
                // 停止 MediaRecorder
                mediaRecorder?.apply {
                    try {
                        stop()
                    } catch (e: Exception) {
                        Log.e(TAG, "停止 MediaRecorder 失败", e)
                    }
                    try {
                        release()
                    } catch (e: Exception) {
                        Log.e(TAG, "释放 MediaRecorder 失败", e)
                    }
                }
                mediaRecorder = null

                // 检查录音文件是否有效
                val mp3File = outputMp3File
                if (mp3File != null && mp3File.exists() && mp3File.length() > 1024) {
                    // 写入转录文件（模拟转录，实际应集成模型）
                    val durationSec = mp3File.length() / 32000 / 2 // 估算时长
                    outputTxtFile?.writeText("会议录音 ${mp3File.nameWithoutExtension}\n")
                    outputTxtFile?.appendText("录音时间: $timestamp\n")
                    outputTxtFile?.appendText("文件大小: ${mp3File.length() / 1024} KB\n")
                    outputTxtFile?.appendText("采样率: 16kHz, AAC编码\n")
                    outputTxtFile?.appendText("---\n")
                    outputTxtFile?.appendText("[注] 此处为模拟转录文本。\n")
                    outputTxtFile?.appendText("请将 paraformer-small.onnx 模型放入 assets 目录以启用真实语音识别。\n")

                    Log.i(TAG, "录音完成: ${mp3File.name}, ${mp3File.length() / 1024}KB")
                } else {
                    // 录音文件无效（太短或为空），删除
                    mp3File?.delete()
                    outputTxtFile?.delete()
                    Log.w(TAG, "录音文件过短或为空，已删除")
                }
            } catch (e: Exception) {
                Log.e(TAG, "停止录音时出错", e)
            }

            cleanupResources()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // 供 MainActivity 获取当前录音时间戳（用于文件名显示）
    private var timestamp: String = ""

    private fun cleanupResources() {
        mediaRecorder?.apply {
            try { release() } catch (_: Exception) {}
        }
        mediaRecorder = null

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
    }
}
