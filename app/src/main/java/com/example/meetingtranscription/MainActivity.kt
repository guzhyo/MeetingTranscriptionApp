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
    private lateinit var tvTranscript: TextView
    private lateinit var listView: ListView
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnModel: ImageButton
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
            if (deniedForever) showPermissionSettingsDialog()
            else Toast.makeText(this, "需要授权才能使用录音功能", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        refreshFileList()
        checkModel()
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
        tvTranscript = findViewById(R.id.tvTranscript)
        listView = findViewById(R.id.listView)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnModel = findViewById(R.id.btnModel)

        btnToggleRecording.setOnClickListener {
            if (isRecording) stopRecording()
            else if (hasAllPermissions()) startRecording()
            else requestPermissions()
        }
        btnRefresh.setOnClickListener { refreshFileList() }
        btnModel.setOnClickListener { showModelDialog() }

        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            if (pos < recordingFiles.size) showFileOptions(recordingFiles[pos])
        }
    }

    private fun findVoskModelDir(): File? {
        val paths = listOf(
            "/storage/emulated/0/VoskModels",
            "/sdcard/VoskModels"
        )
        for (basePath in paths) {
            val base = File(basePath)
            if (!base.exists()) continue
            val dirs = base.listFiles { f -> f.isDirectory } ?: emptyArray()
            for (dir in dirs) {
                if (File(dir, "am/final.mdl").exists()) return dir
            }
            if (File(base, "am/final.mdl").exists()) return base
        }
        return null
    }

    private fun checkModel() {
        val modelDir = findVoskModelDir()
        if (modelDir == null) {
            tvTranscript.text = "⚠ 未检测到 Vosk 模型\n点击 📦 按钮查看如何安装"
        } else {
            tvTranscript.text = "✅ Vosk 模型已就绪（${modelDir.name}）\n点击开始录音"
        }
    }

    private fun showModelDialog() {
        val modelDir = findVoskModelDir()
        val status = if (modelDir != null) "✅ 模型已安装: ${modelDir.name}" else "❌ 未检测到模型"

        AlertDialog.Builder(this)
            .setTitle("Vosk 离线语音模型")
            .setMessage(
                "$status\n\n" +
                "模型安装步骤：\n\n" +
                "1. 在手机浏览器打开：\n" +
                "   https://alphacephei.com/vosk/models\n\n" +
                "2. 下载 vosk-model-small-cn-0.22.zip\n" +
                "   （约 66MB，中文离线识别）\n\n" +
                "3. 在手机存储中创建 VoskModels/ 目录\n\n" +
                "4. 将 zip 解压到 VoskModels/ 下\n" +
                "   最终路径应为：\n" +
                "   /sdcard/VoskModels/vosk-model-small-cn-0.22/\n" +
                "   ├── am/\n" +
                "   │   └── final.mdl\n" +
                "   └── ...\n\n" +
                "💡 你手机里的 vosk-model-small-cn-0.22\n" +
                "   已经在 ~/workspace/vosk_models/ 下了，\n" +
                "   复制到 /sdcard/VoskModels/ 即可"
            )
            .setPositiveButton("知道了") { _, _ -> checkModel() }
            .show()
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
        recordingFiles.addAll(files.filter { it.name.endsWith(".wav") }.sortedByDescending { it.lastModified() })

        val names = recordingFiles.map { file ->
            val txtFile = File(file.parent, "${file.nameWithoutExtension}.txt")
            val mark = if (txtFile.exists()) " ✓" else ""
            "${file.nameWithoutExtension}$mark"
        }

        adapter?.clear()
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            if (names.isEmpty()) mutableListOf("暂无录音记录，点击下方按钮开始录音") else names.toMutableList())
        listView.adapter = adapter
    }

    private fun showFileOptions(file: File) {
        val txtFile = File(file.parent, "${file.nameWithoutExtension}.txt")
        AlertDialog.Builder(this)
            .setTitle(file.nameWithoutExtension)
            .setItems(arrayOf("▶ 播放录音", "📝 查看转录文字", "🗑 删除录音")) { _, which ->
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
                setOnErrorListener { _, _, _ ->
                    Toast.makeText(this@MainActivity, "播放失败", Toast.LENGTH_SHORT).show()
                    releaseMediaPlayer(); true
                }
                prepare()
                start()
            }
            Toast.makeText(this, "正在播放: ${file.nameWithoutExtension}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "播放失败: ${e.localizedMessage ?: ""}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTranscription(txtFile: File) {
        val content = if (txtFile.exists()) txtFile.readText() else "暂无转录文字"
        AlertDialog.Builder(this)
            .setTitle("转录文字 - ${txtFile.nameWithoutExtension}")
            .setMessage(content.ifBlank { "（录音中未检测到语音内容）" })
            .setPositiveButton("关闭", null)
            .setNeutralButton("分享") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, content)
                }, "分享转录文字"))
            }
            .show()
    }

    private fun deleteRecording(wavFile: File, txtFile: File) {
        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除「${wavFile.nameWithoutExtension}」吗？")
            .setPositiveButton("删除") { _, _ ->
                wavFile.delete(); txtFile.delete()
                refreshFileList()
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun releaseMediaPlayer() {
        try { mediaPlayer?.apply { if (isPlaying) stop(); release() } } catch (_: Exception) {}
        mediaPlayer = null
    }

    private fun hasAllPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        val needRationale = REQUIRED_PERMISSIONS.any { shouldShowRequestPermissionRationale(it) }
        if (needRationale) {
            AlertDialog.Builder(this)
                .setTitle("需要权限")
                .setMessage("录音和通知权限是会议转录功能必需的，请允许授权")
                .setPositiveButton("去授权") { _, _ -> permissionLauncher.launch(REQUIRED_PERMISSIONS) }
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
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                })
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startRecording() {
        try {
            tvTranscript.text = "正在录音..."
            startForegroundService(Intent(this, TranscriptionService::class.java).apply {
                action = TranscriptionService.ACTION_START
            })
            isRecording = true
            updateUI()
        } catch (e: Exception) {
            isRecording = false
            tvStatus.text = "启动录音失败: ${e.localizedMessage ?: "未知错误"}"
            Toast.makeText(this, "启动录音失败", Toast.LENGTH_SHORT).show()
            updateUI()
        }
    }

    private fun stopRecording() {
        try {
            startService(Intent(this, TranscriptionService::class.java).apply {
                action = TranscriptionService.ACTION_STOP
            })
            isRecording = false
            updateUI()
            tvTranscript.text = "正在识别中..."
            listView.postDelayed({ refreshFileList() }, 3000)
            listView.postDelayed({
                val dir = getRecordingsDir()
                val files = dir.listFiles()?.filter { it.name.endsWith(".txt") }
                    ?.sortedByDescending { it.lastModified() }
                if (files != null && files.isNotEmpty()) {
                    try { tvTranscript.text = files.first().readText().take(500) } catch (_: Exception) {}
                } else {
                    tvTranscript.text = "识别完成（点击录音列表查看）"
                }
            }, 3000)
        } catch (e: Exception) {
            tvStatus.text = "停止录音出错: ${e.localizedMessage ?: ""}"
        }
    }

    private fun updateUI() {
        if (isRecording) {
            btnToggleRecording.text = getString(R.string.stop_recording)
            btnToggleRecording.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.red_500))
            tvStatus.text = getString(R.string.recording_status)
        } else {
            btnToggleRecording.text = getString(R.string.start_recording)
            btnToggleRecording.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.green_500))
            tvStatus.text = getString(R.string.idle_status)
        }
    }
}
