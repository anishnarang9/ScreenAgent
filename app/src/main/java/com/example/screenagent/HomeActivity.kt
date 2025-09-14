package com.example.screenagent

import android.content.ComponentName
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.screenagent.agent.AccessAgentService
import android.speech.SpeechRecognizer
import android.speech.RecognizerIntent
import android.view.MotionEvent
import android.view.View
import android.os.Handler
import android.os.Looper

class HomeActivity : AppCompatActivity() {

    private var speechRecognizer: SpeechRecognizer? = null
    private var speechIntent: Intent? = null
    private var isListening: Boolean = false
    private val mainHandler = Handler(Looper.getMainLooper())

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

        // Prepare speech recognizer
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { isListening = false }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onResults(results: Bundle?) {
                    isListening = false
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (text != null) {
                        // Go to home screen then send broadcast with transcript
                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(homeIntent)
                        mainHandler.postDelayed({
                            // Run the system home flow (screenshot + webhook) and include transcript
                            val i = Intent("com.example.screenagent.TEST_SYSTEM_HOME").setPackage(packageName)
                            i.putExtra("transcript_text", text)
                            sendBroadcast(i)
                        }, 1200)
                        Toast.makeText(this@HomeActivity, "Transcribed: $text", Toast.LENGTH_SHORT).show()
                    }
                }
            })

            speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
        }

        val speakBtn = findViewById<Button>(R.id.btnHoldToSpeak)
        speakBtn.setOnTouchListener { v: View, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (ensureAudioPermission()) {
                        if (!isListening) {
                            speechRecognizer?.startListening(speechIntent)
                            isListening = true
                            Toast.makeText(this, "Listening... release to stop", Toast.LENGTH_SHORT).show()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isListening) {
                        speechRecognizer?.stopListening()
                        isListening = false
                    }
                    true
                }
                else -> false
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

    private fun ensureAudioPermission(): Boolean {
        return if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            true
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
            false
        }
    }
}
