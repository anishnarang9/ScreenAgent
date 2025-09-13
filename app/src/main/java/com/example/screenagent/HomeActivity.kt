package com.example.screenagent

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.screenagent.agent.AccessAgentService

class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_home)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.home)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<Button>(R.id.btnGoHomeAndTrigger).setOnClickListener {
            if (!isAccessibilityEnabled()) {
                openAccessibilitySettings()
            } else {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)

                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val webhookIntent = Intent("com.example.screenagent.TEST_SYSTEM_HOME").setPackage(packageName)
                    sendBroadcast(webhookIntent)
                    Toast.makeText(this, "Running webhook on system home...", Toast.LENGTH_SHORT).show()
                }, 1500)
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
