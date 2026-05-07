package com.example.meetingtranscription

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private val REQUIRED_PERMISSIONS = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

    private lateinit var btnToggleRecording: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var listView: ListView
    private lateinit var btnRefresh: ImageButton
    private var isRecording = false
    private val recordingFiles = mutableListOf<File>()
    private var adapter: ArrayAdapter<String>? = null
    private var mediaPlayer: MediaPlayer? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (!allGranted) {
            val deniedForever = REQUIRED_PERMISSIONS.any { perm ->
                !results[perm]!! && !shouldShowRequestPermissionRationale(perm)
            }
            if (deniedForever) {
                showPermissionSettingsDialog()
            } else {
                Toast.makeText(this, "需要授权才能使用录音功能", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        refreshFileList()
    }

    override fun onResume() {
        super.onResume()
        refreshFileList()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseMediaPlayer()
    }

    private fun initViews() {
        btnToggleRecording = findViewById(R.id.btnToggleRecording)
        tvStatus = findViewById(R.id.tvStatus)
        listView = findViewById(R.id.listView)
        btnRefresh = findViewById(R.id.btnRefresh)

        btnToggleRecording.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                if (hasAllPermissions()) {
                    startRecording()
                } else {
                    requestPermissions()
                }
            }
        }

        btnRefresh.setOnClickListener { refreshFileList() }

        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            if (position < recordingFiles.size) {
                showFileOptions(recordingFiles[position])
            }
        }
    }

    private fun getRecordingsDir(): File {
        val dir = File(filesDir, "recordings")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun refreshFileList() {
        recordingFiles.clear()
        val dir = getRecordingsDir()
        val files = dir.listFiles() ?: emptyArray()
        // 按修改时间倒序排列
        recordingFiles.addAll(files.filter { it.name.endsWith(".mp3") }.sortedByDescending { it.lastModified() })

        val displayNames = recordingFiles.map { file ->
            val name = file.nameWithoutExtension
            val txtFile = File(file.parent, "${name}.txt")
            val hasText = if (txtFile.exists()) " ✓有转录" else ""
            "$name$hasText"
        }

        if (displayNames.isEmpty()) {
            adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
                mutableListOf("暂无录音记录，点击下方按钮开始录音"))
        } else {
            adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayNames)
        }
        listView.adapter = adapter
    }

    private fun showFileOptions(file: File) {
        val name = file.nameWithoutExtension
        val txtFile = File(file.parent, "${name}.txt")
        val options = mutableListOf<String>().apply {
            add("▶ 播放录音")
            add("📝 查看转录文字")
            add("🗑 删除录音")
        }

        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> playRecording(file)
                    1 -> showTranscription(txtFile)
                    2 -> deleteRecording(file, txtFile)
                }
            }
            .show()
    }

    private fun playRecording(file: File) {
        releaseMediaPlayer()
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    Toast.makeText(this@MainActivity, "播放完毕", Toast.LENGTH_SHORT).show()
                    releaseMediaPlayer()
                }
                setOnErrorListener { _, what, extra ->
                    Toast.makeText(this@MainActivity, "播放失败: $what", Toast.LENGTH_SHORT).show()
                    releaseMediaPlayer()
                    true
                }
                prepare()
                start()
            }
            Toast.makeText(this, "正在播放: ${file.nameWithoutExtension}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "播放失败: ${e.localizedMessage ?: "未知错误"}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTranscription(txtFile: File) {
        val content = if (txtFile.exists()) {
            txtFile.readText()
        } else {
            "暂无转录文字"
        }
        AlertDialog.Builder(this)
            .setTitle("转录文字 - ${txtFile.nameWithoutExtension}")
            .setMessage(if (content.isBlank()) "（录音中未检测到语音内容）" else content)
            .setPositiveButton("关闭", null)
            .setNeutralButton("分享") { _, _ ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, content)
                }
                startActivity(Intent.createChooser(shareIntent, "分享转录文字"))
            }
            .show()
    }

    private fun deleteRecording(wavFile: File, txtFile: File) {
        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除录音「${wavFile.nameWithoutExtension}」吗？")
            .setPositiveButton("删除") { _, _ ->
                wavFile.delete()
                if (txtFile.exists()) txtFile.delete()
                refreshFileList()
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun releaseMediaPlayer() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    private fun hasAllPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val needRationale = REQUIRED_PERMISSIONS.any {
            shouldShowRequestPermissionRationale(it)
        }
        if (needRationale) {
            AlertDialog.Builder(this)
                .setTitle("需要权限")
                .setMessage("录音和通知权限是会议转录功能必需的，请允许授权")
                .setPositiveButton("去授权") { _, _ ->
                    permissionLauncher.launch(REQUIRED_PERMISSIONS)
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("权限被永久拒绝")
            .setMessage("录音权限已被永久拒绝，请在系统设置中手动开启")
            .setPositiveButton("去设置") { _, _ ->
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startRecording() {
        try {
            val serviceIntent = Intent(this, TranscriptionService::class.java).apply {
                action = TranscriptionService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            isRecording = true
            updateUI()
        } catch (e: Exception) {
            isRecording = false
            tvStatus.text = "启动录音失败: ${e.localizedMessage ?: "未知错误"}"
            Toast.makeText(this, "启动录音失败，请重试", Toast.LENGTH_SHORT).show()
            updateUI()
        }
    }

    private fun stopRecording() {
        try {
            val serviceIntent = Intent(this, TranscriptionService::class.java).apply {
                action = TranscriptionService.ACTION_STOP
            }
            startService(serviceIntent)
            isRecording = false
            updateUI()
            // 刷新列表显示新录音
            refreshFileList()
        } catch (e: Exception) {
            tvStatus.text = "停止录音出错: ${e.localizedMessage ?: "未知错误"}"
        }
    }

    private fun updateUI() {
        if (isRecording) {
            btnToggleRecording.text = getString(R.string.stop_recording)
            btnToggleRecording.setBackgroundTintList(
                ContextCompat.getColorStateList(this, R.color.red_500)
            )
            tvStatus.text = getString(R.string.recording_status)
        } else {
            btnToggleRecording.text = getString(R.string.start_recording)
            btnToggleRecording.setBackgroundTintList(
                ContextCompat.getColorStateList(this, R.color.green_500)
            )
            tvStatus.text = getString(R.string.idle_status)
        }
    }
}
