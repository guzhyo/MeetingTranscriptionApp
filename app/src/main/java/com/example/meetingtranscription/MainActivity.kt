package com.example.meetingtranscription

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
    private var paraformerReady = false

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

        btnModel.setOnClickListener { showModelGuideDialog() }

        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            if (position < recordingFiles.size) {
                showFileOptions(recordingFiles[position])
            }
        }
    }

    /**
     * 检查模型是否存在
     */
    private fun checkModel() {
        val modelPaths = listOf(
            "/storage/emulated/0/ParaformerModels/paraformer-small.onnx",
            "/sdcard/ParaformerModels/paraformer-small.onnx",
            "${filesDir.absolutePath}/paraformer-small.onnx"
        )

        paraformerReady = modelPaths.any { File(it).exists() }

        if (!paraformerReady) {
            tvTranscript.text = "⚠ 未检测到语音模型\n点击 📦 按钮下载 paraformer-small.onnx"
        }
    }

    /**
     * 显示模型下载引导
     */
    private fun showModelGuideDialog() {
        val modelStatus = if (paraformerReady) "✅ 模型已就绪" else "❌ 未检测到模型"

        AlertDialog.Builder(this)
            .setTitle("Paraformer 语音模型")
            .setMessage(
                "$modelStatus\n\n" +
                "下载 paraformer-small.onnx 模型：\n\n" +
                "方法一（推荐）：\n" +
                "1. 在手机浏览器打开：\n" +
                "   https://www.modelscope.cn/models/damo/\n" +
                "   speech_paraformer_asr_nat-zh-cn-16k-\n" +
                "   common-vocab8404-pytorch/summary\n" +
                "2. 下载并导出为 ONNX 格式\n" +
                "3. 将 paraformer-small.onnx 放到手机存储的\n" +
                "   ParaformerModels/ 目录\n\n" +
                "方法二：\n" +
                "也可以使用其他 ONNX 格式的中文语音识别模型，\n" +
                "放入 ParaformerModels/ 目录即可\n\n" +
                "⚠ 模型约 100MB，需要 16kHz 单声道输入"
            )
            .setPositiveButton("知道了") { _, _ -> }
            .setNeutralButton("检查模型") { _, _ ->
                checkModel()
                val msg = if (paraformerReady) "✅ 模型已就绪" else "❌ 仍未找到模型"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
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

        val displayNames = recordingFiles.map { file ->
            val name = file.nameWithoutExtension
            val txtFile = File(file.parent, "${name}.txt")
            val hasText = if (txtFile.exists()) " ✓" else ""
            "$name$hasText"
        }

        adapter?.clear()
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
                    Toast.makeText(this@MainActivity, "播放失败", Toast.LENGTH_SHORT).show()
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
            tvTranscript.text = "正在录音..."
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
            tvTranscript.text = "正在识别中，请稍候..."
            // 延迟刷新列表（等识别完成）
            listView.postDelayed({ refreshFileList() }, 5000)
            listView.postDelayed({
                // 尝试读取最新的转录文件
                val dir = getRecordingsDir()
                val files = dir.listFiles()?.filter { it.name.endsWith(".txt") }
                    ?.sortedByDescending { it.lastModified() }
                if (files != null && files.isNotEmpty()) {
                    try {
                        tvTranscript.text = files.first().readText()
                    } catch (_: Exception) {}
                } else {
                    tvTranscript.text = "转录完成（查看录音列表中的 ✓ 标记条目）"
                }
            }, 5000)
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
