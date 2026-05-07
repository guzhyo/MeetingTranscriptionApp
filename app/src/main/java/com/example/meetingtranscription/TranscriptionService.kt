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
    private var voskEngine: VoskEngine? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        voskEngine = VoskEngine(this)
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
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(channel)
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
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MeetingTranscription::RecordingWakeLock"
            ).apply { acquire(10 * 60 * 1000L) }
        } catch (e: Exception) {
            Log.w(TAG, "获取 WakeLock 失败", e)
        }
    }

    private fun startRecording() {
        if (isRecording) return
        hasError = false
        pcmBuffer.reset()

        try {
            startForeground(NOTIFICATION_ID, createNotification())
            acquireWakeLock()

            // 创建输出文件
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val dir = getRecordingsDir()
            outputWavFile = File(dir, "录音_$ts.wav")
            outputTxtFile = File(dir, "录音_$ts.txt")

            // 初始化录音
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
                throw IllegalStateException("不支持的录音参数")
            }

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf.coerceAtLeast(BUFFER_SIZE * 2)
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("录音初始化失败，请检查麦克风权限")
            }
            audioRecord?.startRecording()
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("无法开始录音，麦克风可能被占用")
            }

            isRecording = true

            // 录音循环
            serviceScope.launch {
                try {
                    recordAudio()
                } catch (e: Exception) {
                    Log.e(TAG, "录音循环异常", e)
                    hasError = true
                    withContext(Dispatchers.Main) { stopRecording() }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动录音失败", e)
            hasError = true
            cleanupResources()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun recordAudio() {
        val buffer = ShortArray(BUFFER_SIZE)
        while (isRecording && !hasError) {
            val n = try {
                audioRecord?.read(buffer, 0, BUFFER_SIZE) ?: -1
            } catch (e: Exception) { -1 }

            if (n > 0) {
                for (i in 0 until n) {
                    val v = buffer[i].toInt()
                    pcmBuffer.write(v and 0xFF)
                    pcmBuffer.write((v shr 8) and 0xFF)
                }
            } else if (n < 0) {
                delay(100)
            } else {
                delay(10)
            }
        }
    }

    private fun stopRecording() {
        if (!isRecording && !hasError) return
        isRecording = false

        serviceScope.launch {
            try {
                val pcmData = pcmBuffer.toByteArray()
                if (pcmData.size > 32000) {
                    saveWavFile(pcmData)
                    val transcript = recognizeWithVosk(pcmData)
                    saveTranscriptFile(transcript)
                } else {
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

    private fun saveWavFile(pcmData: ByteArray) {
        val wavFile = outputWavFile ?: return
        try {
            RandomAccessFile(wavFile, "rw").use { raf ->
                raf.writeBytes("RIFF")
                raf.writeInt(Integer.reverseBytes(36 + pcmData.size))
                raf.writeBytes("WAVE")
                raf.writeBytes("fmt ")
                raf.writeInt(Integer.reverseBytes(16))
                raf.writeShort(Integer.reverseBytes(1))
                raf.writeShort(Integer.reverseBytes(1))
                raf.writeInt(Integer.reverseBytes(16000))
                raf.writeInt(Integer.reverseBytes(32000))
                raf.writeShort(Integer.reverseBytes(2))
                raf.writeShort(Integer.reverseBytes(16))
                raf.writeBytes("data")
                raf.writeInt(Integer.reverseBytes(pcmData.size))
                raf.write(pcmData)
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存 WAV 失败", e)
        }
    }

    private fun recognizeWithVosk(pcmData: ByteArray): String {
        val engine = voskEngine ?: return "引擎未初始化"
        if (!engine.isReady()) {
            val ok = engine.initialize()
            if (!ok) return engine.getInitError() ?: "模型未就绪"
        }

        val shortArray = ShortArray(pcmData.size / 2)
        for (i in shortArray.indices) {
            val low = pcmData[i * 2].toInt() and 0xFF
            val high = pcmData[i * 2 + 1].toInt() and 0xFF
            shortArray[i] = (low or (high shl 8)).toShort()
        }
        return engine.recognize(shortArray)
    }

    private fun saveTranscriptFile(transcript: String) {
        val txtFile = outputTxtFile ?: return
        val wavFile = outputWavFile ?: return
        try {
            val sec = if (wavFile.exists()) wavFile.length() / 32000 else 0
            txtFile.writeText("会议录音 ${wavFile.nameWithoutExtension}\n")
            txtFile.appendText("录音时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
            txtFile.appendText("录音时长: ${sec}秒\n")
            txtFile.appendText("识别引擎: Vosk (vosk-model-small-cn-0.22)\n")
            txtFile.appendText("---\n\n")
            txtFile.appendText(transcript.ifEmpty { "[未检测到语音内容]" })
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
        voskEngine?.release()
        voskEngine = null
    }
}
