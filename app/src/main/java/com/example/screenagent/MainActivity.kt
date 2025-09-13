package com.example.screenagent

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.screenagent.agent.AccessAgentService
import com.example.screenagent.agent.MediaProjectionHolder

class MainActivity : AppCompatActivity() {

    private lateinit var mpm: MediaProjectionManager

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            MediaProjectionHolder.mediaProjection =
                mpm.getMediaProjection(res.resultCode, res.data!!)
            
            // Broadcast the result to the accessibility service
            val intent = Intent("com.example.screenagent.SCREENSHOT_PERMISSION_GRANTED").apply {
                putExtra("result_code", res.resultCode)
                putExtra("result_data", res.data)
                setPackage(packageName)
            }
            sendBroadcast(intent)
            
            Toast.makeText(this, "Screen capture granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Screen capture denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mpm = getSystemService(MediaProjectionManager::class.java)

        val pkg = packageName

        findViewById<Button>(R.id.btnGrantCapture).setOnClickListener {
            captureLauncher.launch(mpm.createScreenCaptureIntent())
        }
        findViewById<Button>(R.id.btnRunOnce).setOnClickListener {
            if (!isAccessibilityEnabled()) openAccessibilitySettings() else {
                sendBroadcast(Intent("com.example.screenagent.RUN_ONCE").setPackage(pkg))
                Toast.makeText(this, "Running MVP step…", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnTestNodes).setOnClickListener {
            if (!isAccessibilityEnabled()) openAccessibilitySettings() else {
                sendBroadcast(Intent("com.example.screenagent.TEST_NODES").setPackage(pkg))
                Toast.makeText(this, "Testing nodes overlay…", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnTestScreenshot).setOnClickListener {
            if (!isAccessibilityEnabled()) openAccessibilitySettings() else {
                sendBroadcast(Intent("com.example.screenagent.TEST_SCREENSHOT").setPackage(pkg))
                Toast.makeText(this, "Testing screenshot capture…", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnShowNodes).setOnClickListener {
            if (!isAccessibilityEnabled()) openAccessibilitySettings() else {
                sendBroadcast(Intent("com.example.screenagent.SHOW_NODES").setPackage(pkg))
                Toast.makeText(this, "Showing detected nodes…", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "Enable ScreenAgent in Accessibility.", Toast.LENGTH_LONG).show()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(this, AccessAgentService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, true) }
    }
}
