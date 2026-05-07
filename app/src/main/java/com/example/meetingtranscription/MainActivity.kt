package com.example.meetingtranscription

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.RECORD_AUDIO
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    private lateinit var btnToggleRecording: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var tvTranscription: TextView
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        checkPermissions()
    }

    private fun initViews() {
        btnToggleRecording = findViewById(R.id.btnToggleRecording)
        tvStatus = findViewById(R.id.tvStatus)
        tvTranscription = findViewById(R.id.tvTranscription)

        btnToggleRecording.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }
    }

    private fun checkPermissions() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                tvStatus.text = "需要录音权限才能使用"
                btnToggleRecording.isEnabled = false
            }
        }
    }

    private fun startRecording() {
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
    }

    private fun stopRecording() {
        val serviceIntent = Intent(this, TranscriptionService::class.java).apply {
            action = TranscriptionService.ACTION_STOP
        }
        startService(serviceIntent)

        isRecording = false
        updateUI()
    }

    private fun updateUI() {
        if (isRecording) {
            btnToggleRecording.text = getString(R.string.stop_recording)
            btnToggleRecording.setBackgroundColor(getColor(R.color.red_500))
            tvStatus.text = getString(R.string.recording_status)
        } else {
            btnToggleRecording.text = getString(R.string.start_recording)
            btnToggleRecording.setBackgroundColor(getColor(R.color.green_500))
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
