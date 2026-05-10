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
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "transcription_channel"
        const val SAMPLE_RATE = 16000
        const val BUFFER_SIZE = 3200
        private const val TAG = "TranscriptionService"

        const val ACTION_PARTIAL = "PARTIAL_RESULT"
        const val ACTION_FINAL = "FINAL_RESULT"
        const val EXTRA_TEXT = "text"
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var isPaused = false
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var outputWavFile: File? = null
    private var outputTxtFile: File? = null
    private var hasError = false
    private var voskEngine: VoskEngine? = null
    private var pcmBuffer = ByteArrayOutputStream()
    private var finalText = ""

    override fun onCreate() { super.onCreate(); createNotificationChannel(); voskEngine = VoskEngine(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
        }; return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun getDir() = File(filesDir, "recordings").also { if (!it.exists()) it.mkdirs() }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) try {
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_description); setSound(null, null); enableVibration(false)
            }; getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        } catch (_: Exception) {}
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(getString(R.string.notification_content))
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .setOngoing(true).build()

    private fun acquireWakeLock() {
        try { (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MT::Lock").apply { acquire(10*60*1000L) }.also { wakeLock = it } } catch (_: Exception) {}
    }

    private fun broadcastPartial(text: String) { sendBroadcast(Intent(ACTION_PARTIAL).putExtra(EXTRA_TEXT, text)) }
    private fun broadcastFinal(text: String) { sendBroadcast(Intent(ACTION_FINAL).putExtra(EXTRA_TEXT, text)) }

    private fun startRecording() {
        if (isRecording) return
        hasError = false; isPaused = false; pcmBuffer.reset(); finalText = ""

        try {
            startForeground(NOTIFICATION_ID, createNotification()); acquireWakeLock()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val dir = getDir()
            outputWavFile = File(dir, "录音_$ts.wav"); outputTxtFile = File(dir, "录音_$ts.txt")

            // 初始化 Vosk
            val engine = voskEngine
            if (engine != null && !engine.isReady()) {
                Thread { engine.initialize() }.start()
            } else if (engine != null) {
                engine.startSession()  // 开始新会话
            }

            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf in arrayOf(AudioRecord.ERROR, AudioRecord.ERROR_BAD_VALUE)) throw IllegalStateException()
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf.coerceAtLeast(BUFFER_SIZE*2))
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException()
            audioRecord?.startRecording()
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException()

            isRecording = true

            serviceScope.launch {
                try { recordAndRecognize() }
                catch (e: Exception) { Log.e(TAG, "异常", e); hasError = true; withContext(Dispatchers.Main) { stopRecording() } }
            }
        } catch (e: Exception) { Log.e(TAG, "启动失败", e); hasError = true; cleanup(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    private suspend fun recordAndRecognize() {
        val buf = ShortArray(BUFFER_SIZE)
        var lastPartial = ""
        val engine = voskEngine

        while (isRecording && !hasError) {
            if (isPaused) { delay(200); continue }
            val n = try { audioRecord?.read(buf, 0, BUFFER_SIZE) ?: -1 } catch (_: Exception) { -1 }
            if (n > 0) {
                // 保存 PCM 数据
                for (i in 0 until n) { val v = buf[i].toInt(); pcmBuffer.write(v and 0xFF); pcmBuffer.write((v shr 8) and 0xFF) }
                // 实时识别
                if (engine != null && engine.isReady()) {
                    val shortChunk = buf.copyOfRange(0, n)
                    val partial = engine.feedPcm(shortChunk)
                    if (partial.isNotEmpty() && partial != lastPartial) {
                        lastPartial = partial
                        broadcastPartial(partial)
                    }
                }
            } else if (n < 0) delay(100) else delay(10)
        }
    }

    private fun pauseRecording() { isPaused = true; audioRecord?.let { try { it.stop() } catch (_: Exception) {} } }

    private fun resumeRecording() {
        if (!isRecording || !isPaused) return
        audioRecord?.let { try { it.startRecording(); if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) isPaused = false } catch (_: Exception) {} }
    }

    private fun stopRecording() {
        if (!isRecording && !hasError) return; isRecording = false; isPaused = false
        serviceScope.launch {
            try {
                val pcm = pcmBuffer.toByteArray()
                if (pcm.size > 32000) {
                    saveWav(pcm)
                    val engine = voskEngine
                    if (engine != null && engine.isReady()) {
                        finalText = engine.stopSession()
                    } else finalText = engine?.getInitError() ?: "模型未就绪"
                    saveTranscript()
                    broadcastFinal(finalText)
                } else { outputWavFile?.delete(); outputTxtFile?.delete() }
            } catch (e: Exception) { Log.e(TAG, "停止出错", e) }
            cleanup(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }

    private fun saveWav(pcm: ByteArray) {
        val f = outputWavFile ?: return
        try {
            val total = 36 + pcm.size; RandomAccessFile(f, "rw").use { raf ->
                raf.write(byteArrayOf(82,73,70,70)); raf.writeInt(Integer.reverseBytes(total))
                raf.write(byteArrayOf(87,65,86,69)); raf.write(byteArrayOf(102,109,116,32)); raf.writeInt(Integer.reverseBytes(16))
                raf.write(byteArrayOf(1,0)); raf.write(byteArrayOf(1,0))
                raf.writeInt(Integer.reverseBytes(16000)); raf.writeInt(Integer.reverseBytes(32000))
                raf.write(byteArrayOf(2,0)); raf.write(byteArrayOf(16,0))
                raf.write(byteArrayOf(100,97,116,97)); raf.writeInt(Integer.reverseBytes(pcm.size)); raf.write(pcm)
            }
        } catch (_: Exception) {}
    }

    private fun saveTranscript() {
        val f = outputTxtFile ?: return
        try { f.writeText("会议录音 ${outputWavFile?.nameWithoutExtension}\n录音时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n识别引擎: Vosk\n---\n\n${finalText.ifEmpty { "[未检测到语音内容]" }}") } catch (_: Exception) {}
    }

    private fun cleanup() {
        audioRecord?.apply { try { if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop() } catch (_: Exception) {}; try { release() } catch (_: Exception) {} }; audioRecord = null
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}; wakeLock = null
    }

    override fun onDestroy() { super.onDestroy(); isRecording = false; hasError = true; serviceScope.cancel(); cleanup(); voskEngine?.release(); voskEngine = null }
}
