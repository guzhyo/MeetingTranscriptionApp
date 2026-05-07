package com.example.meetingtranscription

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    companion object {
        private val REQUIRED_PERMISSIONS = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    private lateinit var btnToggleRecording: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var tvTranscription: TextView
    private var isRecording = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            tvStatus.text = "权限已授予，可以开始录音"
        } else {
            val deniedForever = REQUIRED_PERMISSIONS.any { perm ->
                !results[perm]!! && !shouldShowRequestPermissionRationale(perm)
            }
            if (deniedForever) {
                showPermissionSettingsDialog()
            } else {
                tvStatus.text = "需要授权才能使用录音功能"
                btnToggleRecording.isEnabled = true // 允许重试
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
    }

    private fun initViews() {
        btnToggleRecording = findViewById(R.id.btnToggleRecording)
        tvStatus = findViewById(R.id.tvStatus)
        tvTranscription = findViewById(R.id.tvTranscription)

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

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) {
            stopRecording()
        }
    }
}
