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
                val ch = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.channel_description)
                    setSound(null, null); enableVibration(false)
                }
                getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
            } catch (_: Exception) {}
        }
    }

    private fun createNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_content))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi).setOngoing(true).build()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "MeetingTranscription::RecordingWakeLock"
            ).apply { acquire(10 * 60 * 1000L) }
        } catch (_: Exception) {}
    }

    private fun startRecording() {
        if (isRecording) return
        hasError = false
        pcmBuffer.reset()

        try {
            startForeground(NOTIFICATION_ID, createNotification())
            acquireWakeLock()

            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val dir = getRecordingsDir()
            outputWavFile = File(dir, "录音_$ts.wav")
            outputTxtFile = File(dir, "录音_$ts.txt")

            // 初始化 AudioRecord
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf in arrayOf(AudioRecord.ERROR, AudioRecord.ERROR_BAD_VALUE))
                throw IllegalStateException("不支持的录音参数")

            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                minBuf.coerceAtLeast(BUFFER_SIZE * 2))
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED)
                throw IllegalStateException("录音初始化失败")
            audioRecord?.startRecording()
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING)
                throw IllegalStateException("无法开始录音")

            isRecording = true

            serviceScope.launch {
                try { recordAudio() }
                catch (e: Exception) {
                    Log.e(TAG, "录音异常", e)
                    hasError = true
                    withContext(Dispatchers.Main) { stopRecording() }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动录音失败", e)
            hasError = true
            cleanupResources()
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }

    private suspend fun recordAudio() {
        val buf = ShortArray(BUFFER_SIZE)
        while (isRecording && !hasError) {
            val n = try { audioRecord?.read(buf, 0, BUFFER_SIZE) ?: -1 } catch (_: Exception) { -1 }
            if (n > 0) {
                for (i in 0 until n) {
                    val v = buf[i].toInt()
                    pcmBuffer.write(v and 0xFF)
                    pcmBuffer.write((v shr 8) and 0xFF)
                }
            } else if (n < 0) delay(100) else delay(10)
        }
    }

    private fun stopRecording() {
        if (!isRecording && !hasError) return
        isRecording = false

        serviceScope.launch {
            try {
                val pcmData = pcmBuffer.toByteArray()
                if (pcmData.size > 32000) {
                    saveWav(pcmData)
                    val transcript = recognizePcm(pcmData)
                    saveTranscript(transcript)
                } else {
                    outputWavFile?.delete(); outputTxtFile?.delete()
                }
            } catch (e: Exception) { Log.e(TAG, "停止录音出错", e) }
            cleanupResources()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /** 写入标准 16-bit PCM WAV 文件 */
    private fun saveWav(pcm: ByteArray) {
        val f = outputWavFile ?: return
        try {
            val totalSize = 36 + pcm.size
            RandomAccessFile(f, "rw").use { raf ->
                raf.write(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()))
                raf.writeInt(Integer.reverseBytes(totalSize))
                raf.write(byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()))
                raf.write(byteArrayOf('f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte()))
                raf.writeInt(Integer.reverseBytes(16))
                raf.writeShort(java.lang.Short.reverseBytes(1))       // PCM
                raf.writeShort(java.lang.Short.reverseBytes(1))       // mono
                raf.writeInt(Integer.reverseBytes(16000))             // sample rate
                raf.writeInt(Integer.reverseBytes(32000))             // byte rate
                raf.writeShort(java.lang.Short.reverseBytes(2))       // block align
                raf.writeShort(java.lang.Short.reverseBytes(16))      // bits per sample
                raf.write(byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()))
                raf.writeInt(Integer.reverseBytes(pcm.size))
                raf.write(pcm)
            }
        } catch (e: Exception) { Log.e(TAG, "保存 WAV 失败", e) }
    }

    /** Vosk 识别 PCM 数据 */
    private fun recognizePcm(pcmData: ByteArray): String {
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

    private fun saveTranscript(text: String) {
        val f = outputTxtFile ?: return
        val wav = outputWavFile ?: return
        try {
            f.writeText("会议录音 ${wav.nameWithoutExtension}\n")
            f.appendText("录音时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
            f.appendText("识别引擎: Vosk (vosk-model-small-cn-0.22)\n---\n\n")
            f.appendText(text.ifEmpty { "[未检测到语音内容]" })
        } catch (_: Exception) {}
    }

    private fun cleanupResources() {
        audioRecord?.apply {
            try { stop() } catch (_: Exception) {}
            try { release() } catch (_: Exception) {}
        }
        audioRecord = null
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false; hasError = true
        serviceScope.cancel()
        cleanupResources()
        voskEngine?.release()
        voskEngine = null
    }
}
