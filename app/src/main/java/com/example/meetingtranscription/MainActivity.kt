package com.example.meetingtranscription

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.io.File
import java.util.*
import kotlin.concurrent.schedule

class MainActivity : AppCompatActivity() {

    companion object {
        private val REQUIRED_PERMISSIONS = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.toTypedArray()
    }

    private lateinit var btnToggleRecording: MaterialButton
    private lateinit var btnPauseResume: ImageButton
    private lateinit var tvStatus: TextView
    private lateinit var tvTranscript: TextView
    private lateinit var listView: ListView
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnModel: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var tvProgress: TextView
    private var isRecording = false
    private var isPaused = false
    private val recordingFiles = mutableListOf<File>()
    private var adapter: ArrayAdapter<String>? = null
    private var mediaPlayer: MediaPlayer? = null
    private var playingFile: File? = null
    private val handler = Handler(Looper.getMainLooper())
    private val progressUpdater = object : Runnable {
        override fun run() {
            val mp = mediaPlayer
            if (mp != null && mp.isPlaying) {
                val pos = mp.currentPosition
                val dur = mp.duration
                if (dur > 0) {
                    seekBar.progress = pos * 100 / dur
                    tvProgress.text = "${pos / 1000}/${dur / 1000}"
                }
                handler.postDelayed(this, 200)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val allGranted = results.values.all { it }
        if (!allGranted) {
            val deniedForever = REQUIRED_PERMISSIONS.any { perm -> !results[perm]!! && !shouldShowRequestPermissionRationale(perm) }
            if (deniedForever) showPermissionSettingsDialog()
            else Toast.makeText(this, "需要授权才能使用录音功能", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(R.layout.activity_main); initViews(); refreshFileList(); checkModel() }
    override fun onResume() { super.onResume(); refreshFileList() }
    override fun onDestroy() { super.onDestroy(); releaseMediaPlayer() }

    private fun initViews() {
        btnToggleRecording = findViewById(R.id.btnToggleRecording)
        btnPauseResume = findViewById(R.id.btnPauseResume)
        tvStatus = findViewById(R.id.tvStatus)
        tvTranscript = findViewById(R.id.tvTranscript)
        listView = findViewById(R.id.listView)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnModel = findViewById(R.id.btnModel)
        seekBar = findViewById(R.id.seekBar)
        tvProgress = findViewById(R.id.tvProgress)

        btnToggleRecording.setOnClickListener {
            if (isRecording) stopRecording()
            else if (hasAllPermissions()) startRecording() else requestPermissions()
        }
        btnPauseResume.setOnClickListener {
            if (!isRecording) return@setOnClickListener
            if (isPaused) resumeRecording() else pauseRecording()
        }
        btnPauseResume.visibility = android.view.View.GONE
        btnRefresh.setOnClickListener { refreshFileList() }
        btnModel.setOnClickListener { showModelDialog() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && mediaPlayer != null && mediaPlayer!!.isPlaying) {
                    val dur = mediaPlayer!!.duration
                    if (dur > 0) mediaPlayer!!.seekTo(dur * progress / 100)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            if (pos < recordingFiles.size) showFileOptions(recordingFiles[pos])
        }
    }

    private fun findVoskModelDir(): File? {
        val dir = File(filesDir, "vosk-model-cn")
        return if (File(dir, "am/final.mdl").exists()) dir else null
    }

    private fun checkModel() {
        if (findVoskModelDir() != null) {
            tvTranscript.text = "✅ Vosk 模型已就绪\n点击开始录音"
        } else {
            tvTranscript.text = "首次启动需要解压模型..."
            VoskEngine(this).also { it.initialize(); it.release() }
            handler.postDelayed({
                if (findVoskModelDir() != null) tvTranscript.text = "✅ Vosk 模型已就绪\n点击开始录音"
                else tvTranscript.text = "⚠ 模型解压中，请稍后..."
            }, 500)
        }
    }

    private fun showModelDialog() {
        val status = if (findVoskModelDir() != null) "✅ 模型已安装" else "❌ 模型未准备好"
        AlertDialog.Builder(this).setTitle("Vosk 离线语音模型")
            .setMessage("$status\n\n模型已内置在 App 中，首次启动自动解压。\n解压完成即可离线识别。\n模型约 42MB。")
            .setPositiveButton("知道了") { _, _ -> checkModel() }.show()
    }

    private fun getRecordingsDir(): File { val dir = File(filesDir, "recordings"); if (!dir.exists()) dir.mkdirs(); return dir }

    private fun refreshFileList() {
        recordingFiles.clear()
        val dir = getRecordingsDir()
        val files = dir.listFiles() ?: emptyArray()
        recordingFiles.addAll(files.filter { it.name.endsWith(".wav") }.sortedByDescending { it.lastModified() })

        val names = recordingFiles.map { file ->
            val txtFile = File(file.parent, "${file.nameWithoutExtension}.txt")
            val mark = if (txtFile.exists()) " ✓" else ""
            val nowPlaying = if (file == playingFile) " ▶" else ""
            "${file.nameWithoutExtension}$mark$nowPlaying"
        }

        adapter?.clear()
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            if (names.isEmpty()) mutableListOf("暂无录音记录") else names.toMutableList())
        listView.adapter = adapter
    }

    private fun showFileOptions(file: File) {
        val txtFile = File(file.parent, "${file.nameWithoutExtension}.txt")
        val isPlaying = file == playingFile && mediaPlayer?.isPlaying == true
        val options = mutableListOf<String>().apply {
            if (isPlaying) add("⏸ 暂停播放") else add("▶ 播放录音")
            add("📝 查看转录文字")
            add("🗑 删除录音")
        }
        AlertDialog.Builder(this).setTitle(file.nameWithoutExtension)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> if (isPlaying) pausePlayback() else playRecording(file)
                    1 -> showTranscription(txtFile)
                    2 -> deleteRecording(file, txtFile)
                }
            }.show()
    }

    private fun playRecording(file: File) {
        releaseMediaPlayer(); playingFile = file
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stopPlayback() }
                setOnErrorListener { _, _, _ -> stopPlayback(); true }
                prepare()
                start()
            }
            seekBar.visibility = android.view.View.VISIBLE
            tvProgress.visibility = android.view.View.VISIBLE
            handler.post(progressUpdater)
            tvStatus.text = "▶ 播放: ${file.nameWithoutExtension}"
            refreshFileList()
        } catch (e: Exception) { Toast.makeText(this, "播放失败", Toast.LENGTH_SHORT).show(); stopPlayback() }
    }

    private fun pausePlayback() {
        mediaPlayer?.let {
            if (it.isPlaying) { it.pause(); tvStatus.text = "⏸ 已暂停: ${playingFile?.nameWithoutExtension}" }
            else { it.start(); tvStatus.text = "▶ 继续播放: ${playingFile?.nameWithoutExtension}"; handler.post(progressUpdater) }
        }
    }

    private fun stopPlayback() {
        releaseMediaPlayer(); playingFile = null
        seekBar.visibility = android.view.View.GONE; tvProgress.visibility = android.view.View.GONE
        tvStatus.text = getString(R.string.idle_status); refreshFileList()
    }

    private fun showTranscription(txtFile: File) {
        val content = if (txtFile.exists()) txtFile.readText() else "暂无转录文字"
        AlertDialog.Builder(this).setTitle("转录文字 - ${txtFile.nameWithoutExtension}")
            .setMessage(content.ifBlank { "（录音中未检测到语音内容）" })
            .setPositiveButton("关闭", null)
            .setNeutralButton("分享") { _, _ -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, content) }, "分享转录文字")) }
            .show()
    }

    private fun deleteRecording(wavFile: File, txtFile: File) {
        if (wavFile == playingFile) stopPlayback()
        AlertDialog.Builder(this).setTitle("确认删除").setMessage("确定要删除「${wavFile.nameWithoutExtension}」吗？")
            .setPositiveButton("删除") { _, _ -> wavFile.delete(); txtFile.delete(); refreshFileList(); Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show() }
            .setNegativeButton("取消", null).show()
    }

    private fun releaseMediaPlayer() { try { handler.removeCallbacks(progressUpdater); mediaPlayer?.apply { if (isPlaying) stop(); release() } } catch (_: Exception) {}; mediaPlayer = null }

    private fun hasAllPermissions(): Boolean = REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun requestPermissions() {
        val needRationale = REQUIRED_PERMISSIONS.any { shouldShowRequestPermissionRationale(it) }
        if (needRationale) AlertDialog.Builder(this).setTitle("需要权限").setMessage("录音和通知权限是会议转录功能必需的").setPositiveButton("去授权") { _, _ -> permissionLauncher.launch(REQUIRED_PERMISSIONS) }.setNegativeButton("取消", null).show()
        else permissionLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this).setTitle("权限被永久拒绝").setMessage("录音权限已被永久拒绝，请在系统设置中手动开启")
            .setPositiveButton("去设置") { _, _ -> startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = android.net.Uri.fromParts("package", packageName, null) }) }
            .setNegativeButton("取消", null).show()
    }

    private fun startRecording() {
        try {
            tvTranscript.text = "正在录音..."
            startForegroundService(Intent(this, TranscriptionService::class.java).apply { action = TranscriptionService.ACTION_START })
            isRecording = true; isPaused = false; updateUI()
        } catch (e: Exception) { isRecording = false; tvStatus.text = "启动录音失败"; Toast.makeText(this, "启动录音失败", Toast.LENGTH_SHORT).show(); updateUI() }
    }

    private fun pauseRecording() {
        startService(Intent(this, TranscriptionService::class.java).apply { action = TranscriptionService.ACTION_PAUSE })
        isPaused = true; updateUI()
    }

    private fun resumeRecording() {
        startService(Intent(this, TranscriptionService::class.java).apply { action = TranscriptionService.ACTION_RESUME })
        isPaused = false; updateUI()
    }

    private fun stopRecording() {
        try {
            startService(Intent(this, TranscriptionService::class.java).apply { action = TranscriptionService.ACTION_STOP })
            isRecording = false; isPaused = false; updateUI()
            tvTranscript.text = "正在识别中..."
            handler.postDelayed({ refreshFileList()
                val dir = getRecordingsDir(); val files = dir.listFiles()?.filter { it.name.endsWith(".txt") }?.sortedByDescending { it.lastModified() }
                if (files != null && files.isNotEmpty()) { try { tvTranscript.text = files.first().readText().take(500) } catch (_: Exception) {} }
                else tvTranscript.text = "识别完成（点击录音列表查看）"
            }, 3000)
        } catch (e: Exception) { tvStatus.text = "停止录音出错" }
    }

    private fun updateUI() {
        if (isRecording) {
            btnToggleRecording.text = if (isPaused) "继续" else "停止"
            btnToggleRecording.setBackgroundTintList(ContextCompat.getColorStateList(this, if (isPaused) R.color.teal_700 else R.color.red_500))
            btnPauseResume.visibility = android.view.View.VISIBLE
            btnPauseResume.setImageResource(if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
            tvStatus.text = if (isPaused) "⏸ 录音已暂停" else getString(R.string.recording_status)
        } else {
            btnToggleRecording.text = getString(R.string.start_recording)
            btnToggleRecording.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.green_500))
            btnPauseResume.visibility = android.view.View.GONE
            tvStatus.text = getString(R.string.idle_status)
        }
    }
}
