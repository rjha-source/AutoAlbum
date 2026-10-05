package com.autoalbum

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnScan: Button
    private lateinit var swAuto: SwitchCompat
    private var scanning = false

    private val mediaPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { updateStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        btnScan = findViewById(R.id.btnScan)
        swAuto = findViewById(R.id.swAuto)

        findViewById<Button>(R.id.btnPerm).setOnClickListener { requestPermissions() }
        btnScan.setOnClickListener { scanAll() }

        val prefs = getSharedPreferences("auto", Context.MODE_PRIVATE)
        swAuto.isChecked = prefs.getBoolean("enabled", false)
        swAuto.setOnCheckedChangeListener { _, on ->
            if (on && !hasPermissions()) {
                swAuto.isChecked = false
                status.text = "Grant permissions first."
                return@setOnCheckedChangeListener
            }
            val e = prefs.edit().putBoolean("enabled", on)
            if (on) {
                e.putLong("last_ts", System.currentTimeMillis() / 1000).apply()
                AutoSortWorker.schedule(this)
                status.text = "Auto-sort ON: new photos will be sorted automatically."
            } else {
                e.apply()
                AutoSortWorker.cancel(this)
                status.text = "Auto-sort OFF."
            }
        }
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun hasMedia() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.READ_MEDIA_IMAGES
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasPermissions() = hasMedia() && Environment.isExternalStorageManager()

    private fun requestPermissions() {
        if (!hasMedia()) {
            mediaPermission.launch(Manifest.permission.READ_MEDIA_IMAGES)
        } else if (!Environment.isExternalStorageManager()) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun updateStatus() {
        if (scanning) return
        status.text = when {
            !hasMedia() -> "Tap 'Grant permissions' (photos access)."
            !Environment.isExternalStorageManager() ->
                "Tap 'Grant permissions' again and allow 'All files access'."
            else -> "Permissions OK. Ready."
        }
    }

    private fun scanAll() {
        if (scanning) return
        if (!hasPermissions()) { updateStatus(); return }
        scanning = true
        btnScan.isEnabled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        lifecycleScope.launch {
            val (_, photos) = withContext(Dispatchers.IO) { Sorter.query(this@MainActivity, 0L) }
            progress.max = photos.size
            progress.progress = 0
            val counts = linkedMapOf<String, Int>()
            var left = 0

            photos.forEachIndexed { i, p ->
                val album = Sorter.process(this@MainActivity, p)
                if (album != null) counts[album] = (counts[album] ?: 0) + 1 else left++
                progress.progress = i + 1
                status.text = "Sorting ${i + 1} / ${photos.size}\n" + summary(counts, left)
            }

            status.text = "Done!\n" + summary(counts, left)
            scanning = false
            btnScan.isEnabled = true
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun summary(counts: Map<String, Int>, left: Int): String =
        counts.entries.joinToString("\n") { "${it.key}: ${it.value}" } + "\nLeft in place: $left"
}
