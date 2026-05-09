package com.example.meetingtranscription

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private val REQUIRED_PERMISSIONS = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.toTypedArray()
    }

    private lateinit var btnToggleRecording: MaterialButton
    private lateinit var btnPauseResume: ImageButton
    private lateinit var btnCamera: ImageButton
    private lateinit var tvStatus: TextView
    private lateinit var etTranscript: EditText
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
    private var currentPhotoFile: File? = null
    private val handler = Handler(Looper.getMainLooper())
    private val progressUpdater = object : Runnable {
        override fun run() {
            val mp = mediaPlayer
            if (mp != null && mp.isPlaying) {
                val pos = mp.currentPosition; val dur = mp.duration
                if (dur > 0) { seekBar.progress = pos * 100 / dur; tvProgress.text = "${pos/1000}/${dur/1000}" }
                handler.postDelayed(this, 200)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val allGranted = results.values.all { it }
        if (!allGranted) {
            val deniedForever = REQUIRED_PERMISSIONS.any { perm -> !results[perm]!! && !shouldShowRequestPermissionRationale(perm) }
            if (deniedForever) showPermissionSettingsDialog() else Toast.makeText(this, "需要授权", Toast.LENGTH_SHORT).show()
        }
    }

    private val photoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && currentPhotoFile?.exists() == true) {
            insertPhotoIntoText(currentPhotoFile!!)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(R.layout.activity_main); initViews(); refreshFileList(); checkModel() }
    override fun onResume() { super.onResume(); refreshFileList() }
    override fun onDestroy() { super.onDestroy(); releaseMediaPlayer() }

    private fun initViews() {
        btnToggleRecording = findViewById(R.id.btnToggleRecording)
        btnPauseResume = findViewById(R.id.btnPauseResume)
        btnCamera = findViewById(R.id.btnCamera)
        tvStatus = findViewById(R.id.tvStatus)
        etTranscript = findViewById(R.id.etTranscript)
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

        btnCamera.setOnClickListener { takePhoto() }

        btnRefresh.setOnClickListener { refreshFileList() }
        btnModel.setOnClickListener { showModelDialog() }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && mediaPlayer != null && mediaPlayer!!.isPlaying) {
                    val dur = mediaPlayer!!.duration; if (dur > 0) mediaPlayer!!.seekTo(dur * progress / 100)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            if (pos < recordingFiles.size) showFileOptions(recordingFiles[pos])
        }
    }

    private fun takePhoto() {
        if (!hasAllPermissions()) { requestPermissions(); return }
        try {
            val photoFile = createPhotoFile()
            currentPhotoFile = photoFile
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile)
            photoLauncher.launch(uri)
        } catch (e: Exception) { Toast.makeText(this, "拍照失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show() }
    }

    private fun createPhotoFile(): File {
        val dir = File(filesDir, "photos"); if (!dir.exists()) dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(dir, "IMG_$ts.jpg")
    }

    private fun insertPhotoIntoText(photoFile: File) {
        try {
            // 在光标位置插入图片引用标记 [IMG:文件名]
            val cursorPos = etTranscript.selectionStart
            val text = etTranscript.text.toString()
            val photoTag = "\n[图片: ${photoFile.name}]\n"
            val newText = StringBuilder(text).insert(if (cursorPos >= 0) cursorPos else text.length, photoTag).toString()
            etTranscript.setText(newText)
            etTranscript.setSelection(if (cursorPos >= 0) cursorPos + photoTag.length else newText.length)

            // 显示照片缩略图提示
            Toast.makeText(this, "照片已插入到文字中", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "插入照片失败", Toast.LENGTH_SHORT).show() }
    }

    // --- 模型 ---
    private fun findModelDir(): File? { val d = File(filesDir, "vosk-model-cn"); return if (File(d, "am/final.mdl").exists()) d else null }
    private fun checkModel() {
        if (findModelDir() != null) { etTranscript.hint = "✅ 模型已就绪，开始录音吧" }
        else { etTranscript.setText("首次启动需要解压模型..."); VoskEngine(this).also { it.initialize(); it.release() }; handler.postDelayed({ if (findModelDir() != null) etTranscript.hint = "✅ 模型已就绪" else etTranscript.setText("⚠ 模型解压中，请稍后...") }, 500) }
    }
    private fun showModelDialog() {
        val s = if (findModelDir() != null) "✅ 已安装" else "❌ 未就绪"
        AlertDialog.Builder(this).setTitle("Vosk 模型").setMessage("$s\n\n模型已内置，首次启动自动解压。").setPositiveButton("知道了") { _, _ -> checkModel() }.show()
    }

    // --- 录音文件 ---
    private fun getRecordingsDir(): File { val d = File(filesDir, "recordings"); if (!d.exists()) d.mkdirs(); return d }

    private fun refreshFileList() {
        recordingFiles.clear()
        val files = getRecordingsDir().listFiles() ?: emptyArray()
        recordingFiles.addAll(files.filter { it.name.endsWith(".wav") }.sortedByDescending { it.lastModified() })
        val names = recordingFiles.map { "${it.nameWithoutExtension}${if (File(it.parent, "${it.nameWithoutExtension}.txt").exists()) " ✓" else ""}${if (it == playingFile) " ▶" else ""}" }
        adapter?.clear()
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, if (names.isEmpty()) mutableListOf("暂无录音记录") else names.toMutableList())
        listView.adapter = adapter
    }

    private fun showFileOptions(file: File) {
        val txtFile = File(file.parent, "${file.nameWithoutExtension}.txt")
        val isPlaying = file == playingFile && mediaPlayer?.isPlaying == true
        AlertDialog.Builder(this).setTitle(file.nameWithoutExtension)
            .setItems(arrayOf(if (isPlaying) "⏸ 暂停" else "▶ 播放", "📝 查看转录", "📋 复制文字到编辑框", "🗑 删除")) { _, which ->
                when (which) {
                    0 -> if (isPlaying) pausePlayback() else playRecording(file)
                    1 -> showTranscription(txtFile)
                    2 -> copyToEditor(txtFile)
                    3 -> deleteRecording(file, txtFile)
                }
            }.show()
    }

    private fun copyToEditor(txtFile: File) {
        if (txtFile.exists()) {
            etTranscript.setText(txtFile.readText())
            Toast.makeText(this, "已复制到编辑框", Toast.LENGTH_SHORT).show()
        } else Toast.makeText(this, "暂无转录文字", Toast.LENGTH_SHORT).show()
    }

    private fun playRecording(file: File) {
        releaseMediaPlayer(); playingFile = file
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stopPlayback() }
                setOnErrorListener { _, _, _ -> stopPlayback(); true }; prepare(); start()
            }
            seekBar.visibility = android.view.View.VISIBLE; tvProgress.visibility = android.view.View.VISIBLE
            handler.post(progressUpdater); tvStatus.text = "▶ ${file.nameWithoutExtension}"; refreshFileList()
        } catch (e: Exception) { Toast.makeText(this, "播放失败", Toast.LENGTH_SHORT).show(); stopPlayback() }
    }

    private fun pausePlayback() {
        mediaPlayer?.let { if (it.isPlaying) { it.pause(); tvStatus.text = "⏸ 已暂停" } else { it.start(); tvStatus.text = "▶ 继续"; handler.post(progressUpdater) } }
    }

    private fun stopPlayback() { releaseMediaPlayer(); playingFile = null; seekBar.visibility = android.view.View.GONE; tvProgress.visibility = android.view.View.GONE; tvStatus.text = getString(R.string.idle_status); refreshFileList() }

    private fun showTranscription(txtFile: File) {
        val content = if (txtFile.exists()) txtFile.readText() else "暂无转录文字"
        AlertDialog.Builder(this).setTitle("转录文字 - ${txtFile.nameWithoutExtension}")
            .setMessage(content.ifBlank { "（无内容）" }).setPositiveButton("关闭", null)
            .setNeutralButton("复制到编辑框") { _, _ -> etTranscript.setText(content) }
            .setNegativeButton("分享") { _, _ -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, content) }, "分享")) }.show()
    }

    private fun deleteRecording(wavFile: File, txtFile: File) {
        if (wavFile == playingFile) stopPlayback()
        AlertDialog.Builder(this).setTitle("确认删除").setMessage("确定删除「${wavFile.nameWithoutExtension}」？")
            .setPositiveButton("删除") { _, _ -> wavFile.delete(); txtFile.delete(); refreshFileList(); Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show() }
            .setNegativeButton("取消", null).show()
    }

    private fun releaseMediaPlayer() { try { handler.removeCallbacks(progressUpdater); mediaPlayer?.apply { if (isPlaying) stop(); release() } } catch (_: Exception) {}; mediaPlayer = null }

    // --- 权限 ---
    private fun hasAllPermissions(): Boolean = REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    private fun requestPermissions() {
        val needRationale = REQUIRED_PERMISSIONS.any { shouldShowRequestPermissionRationale(it) }
        if (needRationale) AlertDialog.Builder(this).setTitle("需要权限").setMessage("录音、相机和通知权限是必需的").setPositiveButton("去授权") { _, _ -> permissionLauncher.launch(REQUIRED_PERMISSIONS) }.setNegativeButton("取消", null).show()
        else permissionLauncher.launch(REQUIRED_PERMISSIONS)
    }
    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this).setTitle("权限被永久拒绝").setMessage("请在系统设置中手动开启权限")
            .setPositiveButton("去设置") { _, _ -> startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = android.net.Uri.fromParts("package", packageName, null) }) }
            .setNegativeButton("取消", null).show()
    }

    // --- 录音控制 ---
    private fun startRecording() {
        try {
            etTranscript.setText("正在录音...")
            startForegroundService(Intent(this, TranscriptionService::class.java).apply { action = TranscriptionService.ACTION_START })
            isRecording = true; isPaused = false; updateUI()
        } catch (e: Exception) { isRecording = false; Toast.makeText(this, "启动失败", Toast.LENGTH_SHORT).show(); updateUI() }
    }
    private fun pauseRecording() { startService(Intent(this, TranscriptionService::class.java).apply { action = TranscriptionService.ACTION_PAUSE }); isPaused = true; updateUI() }
    private fun resumeRecording() { startService(Intent(this, TranscriptionService::class.java).apply { action = TranscriptionService.ACTION_RESUME }); isPaused = false; updateUI() }

    private val partialReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = intent.getStringExtra(TranscriptionService.EXTRA_TEXT) ?: return
            // 在编辑框中追加实时识别结果
            val current = etTranscript.text.toString()
            if (current.startsWith("正在录音...") || current.startsWith("正在识别")) {
                etTranscript.setText(text)
            } else {
                // 只更新最后一段
                val lines = current.split("\n")
                val sb = StringBuilder()
                for (i in 0 until lines.size - 1) sb.appendLine(lines[i])
                sb.append(text)
                etTranscript.setText(sb.toString())
            }
        }
    }

    private val finalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = intent.getStringExtra(TranscriptionService.EXTRA_TEXT) ?: return
            etTranscript.setText(text)
            // 保存在录音的转录文件中
            refreshFileList()
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(partialReceiver, IntentFilter(TranscriptionService.ACTION_PARTIAL),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_EXPORTED else 0)
        registerReceiver(finalReceiver, IntentFilter(TranscriptionService.ACTION_FINAL),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_EXPORTED else 0)
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(partialReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(finalReceiver) } catch (_: Exception) {}
    }

    private fun stopRecording() {
        try {
            startService(Intent(this, TranscriptionService::class.java).apply { action = TranscriptionService.ACTION_STOP })
            isRecording = false; isPaused = false; updateUI()
            handler.postDelayed({ refreshFileList() }, 3000)
        } catch (_: Exception) {}
    }

    private fun updateUI() {
        if (isRecording) {
            btnToggleRecording.text = if (isPaused) "继续" else "停止"
            btnToggleRecording.setBackgroundTintList(ContextCompat.getColorStateList(this, if (isPaused) R.color.teal_700 else R.color.red_500))
            btnPauseResume.visibility = android.view.View.VISIBLE
            btnPauseResume.setImageResource(if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
            tvStatus.text = if (isPaused) "⏸ 已暂停" else getString(R.string.recording_status)
        } else {
            btnToggleRecording.text = getString(R.string.start_recording)
            btnToggleRecording.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.green_500))
            btnPauseResume.visibility = android.view.View.GONE
            tvStatus.text = getString(R.string.idle_status)
        }
    }
}
