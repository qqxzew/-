package com.meemaw.defender

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.meemaw.defender.DefenderSettings.grandmaName
import com.meemaw.defender.DefenderSettings.isActive
import com.meemaw.defender.DefenderSettings.serverUrl
import com.meemaw.defender.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Launch foreground service
        DefenderService.start(this)

        // Prefill settings
        b.editName.setText(grandmaName)
        b.editServer.setText(serverUrl)
        b.switchActive.isChecked = isActive

        b.switchActive.setOnCheckedChangeListener { _, checked ->
            isActive = checked
            if (checked) DefenderService.start(this)
        }

        b.btnSave.setOnClickListener {
            grandmaName = b.editName.text.toString().trim().ifBlank { "Meemaw" }
            serverUrl   = b.editServer.text.toString().trim().ifBlank { "http://10.0.2.2:3000" }
            b.txtHeader.text = getString(R.string.protecting, grandmaName)
        }

        b.btnPermissions.setOnClickListener { requestPermissions() }
        b.btnNotifAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        b.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        b.btnOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !Settings.canDrawOverlays(this)) {
                val i = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(i)
            }
        }

        b.txtHeader.text = getString(R.string.protecting, grandmaName)
    }

    private fun requestPermissions() {
        val needed = mutableListOf<String>()

        listOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        ).forEach {
            if (ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED) {
                needed += it
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 42)
        }
    }
}
