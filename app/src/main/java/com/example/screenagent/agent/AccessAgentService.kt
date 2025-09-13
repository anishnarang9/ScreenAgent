package com.example.screenagent.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.util.Log
import android.content.IntentFilter
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL


class AccessAgentService : AccessibilityService() {

    companion object {
        private const val MAX_AFFORDANCES = 20 // Maximum number of clickable elements to detect
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var hud: OverlayHud
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var screenshotter: Screenshotter
    private var nodeMap: Map<String, AccessibilityNodeInfo> = emptyMap()

    // ===== Broadcast receiver: MainActivity sends Intents we react to here
    private val nudge = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            when (i?.action) {
                "com.example.screenagent.RUN_ONCE" -> doOneStep()
                "com.example.screenagent.TEST_NODES" -> testNodesOverlay()
                "com.example.screenagent.TEST_SCREENSHOT" -> testScreenshotSave()
                "com.example.screenagent.TEST_SCREENSHOT_ONLY" -> testScreenshotOnly()
                "com.example.screenagent.SHOW_NODES" -> showDetectedNodes()
                "com.example.screenagent.TEST_SYSTEM_HOME" -> testSystemHome()
                "com.example.screenagent.SCREENSHOT_PERMISSION_GRANTED" -> handleScreenshotPermissionGranted(i)
            }
        }
    }


    override fun onServiceConnected() {
        try {
            // 1) Build HUD safely (OverlayHud is already the "safe" class with attached flag)
            hud = OverlayHud(this)

            // 2) Build screenshotter (does nothing until you grant capture)
            screenshotter = Screenshotter(this)

            // 3) Initialize MediaProjectionManager
            mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            // 4) Register in-process receiver using positional args (not named)
            val filter = IntentFilter().apply {
                addAction("com.example.screenagent.RUN_ONCE")
                addAction("com.example.screenagent.TEST_NODES")
                addAction("com.example.screenagent.TEST_SCREENSHOT")
                addAction("com.example.screenagent.TEST_SCREENSHOT_ONLY")
                addAction("com.example.screenagent.SHOW_NODES")
                addAction("com.example.screenagent.TEST_SYSTEM_HOME")
                addAction("com.example.screenagent.SCREENSHOT_PERMISSION_GRANTED")
            }
            registerReceiver(nudge, filter, RECEIVER_NOT_EXPORTED)

            hud.show("Agent connected. Use the app buttons to test.")
        } catch (t: Throwable) {
            // If anything goes wrong here we log instead of crashing the service
            android.util.Log.e("ScreenAgent", "onServiceConnected failed", t)
            try { if (::hud.isInitialized) hud.show("Service init error: ${t.javaClass.simpleName}") } catch (_: Throwable) {}
        }
    }



    override fun onDestroy() {
        try { unregisterReceiver(nudge) } catch (_: Exception) {}
        try { if (::hud.isInitialized) hud.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }


    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    
    // Helper function to check if service is properly connected
    private fun isServiceConnected(): Boolean {
        return try {
            // Try to get service info - if this throws, service isn't connected
            serviceInfo != null
        } catch (e: Exception) {
            Log.e("ScreenAgent", "Service not connected: ${e.message}")
            false
        }
    }

    // ====== MAIN MVP STEP (kept for when backend is ready)
    fun doOneStep() {
        scope.launch {
            hud.show("Observing…")
            val affordances = dumpAffordances(rootInActiveWindow)
            hud.drawAffordances(affordances)

            // Try to capture screenshot using accessibility service method
            val shot = try {
                Log.d("ScreenAgent", "Attempting to capture screenshot...")
                val result = screenshotter.captureBase64()
                Log.d("ScreenAgent", "Screenshot capture result: ${if (result != null) "SUCCESS (${result.length} chars)" else "FAILED"}")
                result
            } catch (e: Exception) {
                Log.e("ScreenAgent", "Screenshot capture failed", e)
                null
            }
            
            if (shot != null) {
                Log.d("ScreenAgent", "Screenshot captured successfully, size: ${shot.length} characters")
                Log.d("ScreenAgent", "Screenshot preview: ${shot.take(100)}...")
            } else {
                Log.w("ScreenAgent", "Screenshot capture failed - check screen capture permission")
            }

            val req = PlanRequest("mvp-1", affordances, shot)

            hud.show("Posting to planner…")
            val resp = postPlan(req)
            val action = resp?.action
            if (action == null) {
                hud.show("No action returned (is n8n running?)")
                return@launch
            }

            when (action["type"]) {
                "tap" -> {
                    val id = action["node_id"] as? String
                    if (id != null) {
                        hud.show("Do: tap $id")
                        val ok = tap(id)
                        hud.show(if (ok) "Tapped $id ✓" else "Tap failed")
                    } else hud.show("Planner reply missing node_id")
                }
                "open_url" -> {
                    val url = action["url"] as? String
                    if (url != null) {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        hud.show("Opened URL")
                    } else hud.show("Planner reply missing url")
                }
                else -> hud.show("Unsupported action")
            }
            
            // Clear affordances after action
            kotlinx.coroutines.delay(2000)
            hud.clearAffordances()
            hud.show("Agent idle")
        }
    }

    // ====== TEST 1: Show nodes overlay (no backend)
    private fun testNodesOverlay() {
        val root = rootInActiveWindow
        val aff = dumpAffordances(root)
        hud.drawAffordances(aff)
        hud.show("Nodes: ${aff.size} shown")
        aff.forEach { a ->
            Log.d("ScreenAgent", "Node ${a.id} text=${a.text} desc=${a.content_desc} clazz=${a.clazz} bounds=${a.bounds}")
        }
        
        // Clear affordances after 3 seconds
        scope.launch {
            kotlinx.coroutines.delay(3000)
            hud.clearAffordances()
            hud.show("Agent idle")
        }
    }

    // ====== TEST 2: Save screenshot locally (no backend)
    private fun testScreenshotSave() {
        scope.launch(Dispatchers.IO) {
            // Check if we need to request permission first
            if (MediaProjectionHolder.mediaProjection == null) {
                hud.show("Screenshot permission needed!\nGo to Main App → Grant Screen Capture")
                return@launch
            }
            
            val b64 = screenshotter.captureBase64()
            if (b64 == null) {
                hud.show("Screenshot: not granted (tap Grant Screen Capture)")
                return@launch
            }
            try {
                val base = b64.substringAfter("base64,", b64)
                val bytes = android.util.Base64.decode(base, android.util.Base64.DEFAULT)
                val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                if (dir?.exists() == false) dir.mkdirs()
                val file = java.io.File(dir, "shot_${System.currentTimeMillis()}.png")
                file.outputStream().use { it.write(bytes) }
                hud.show("Screenshot saved: ${file.name}")
                Log.d("ScreenAgent", "Screenshot path: ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e("ScreenAgent", "Screenshot save failed", e)
                hud.show("Screenshot save failed")
            }
        }
    }

    // ====== TEST 3: Show detected nodes in system popup
    private fun showDetectedNodes() {
        scope.launch {
            try {
                // Check if service is properly connected
                if (!isServiceConnected()) {
                    hud.show("Service not connected - restart app")
                    Log.e("ScreenAgent", "AccessibilityService not connected")
                    return@launch
                }
            
            var root = rootInActiveWindow
            Log.d("ScreenAgent", "Root node: $root")
            
            // Fallback: try to get root from global window list
            if (root == null) {
                Log.d("ScreenAgent", "rootInActiveWindow is null, trying global windows")
                val windows = windows
                if (windows != null && windows.isNotEmpty()) {
                    val windowInfo = windows.lastOrNull()
                    root = windowInfo?.root
                    Log.d("ScreenAgent", "Using root from window: $root")
                }
            }
            
            if (root == null) {
                hud.show("No root node - try different screen")
                Log.e("ScreenAgent", "No root node available")
                return@launch
            }
            
            val affordances = dumpAffordances(root)
            Log.d("ScreenAgent", "Found ${affordances.size} affordances")
            
            if (affordances.isEmpty()) {
                hud.show("No clickable nodes found - check accessibility")
                return@launch
            }
            
            // Create JSON representation
            val json = JSONObject().apply {
                put("session_id", "mvp-1")
                put("screenshot_b64", "data:image/jpeg;base64,...")
                put("affordances", JSONArray().apply {
                    affordances.forEach { a ->
                        put(JSONObject().apply {
                            put("id", a.id)
                            put("text", a.text)
                            put("content_desc", a.content_desc)
                            put("clazz", a.clazz)
                            put("clickable", a.clickable)
                            put("editable", a.editable)
                            put("bounds", JSONArray(a.bounds.toTypedArray()))
                        })
                    }
                })
            }
            
            val jsonString = json.toString(2) // Pretty print with 2-space indentation
            
            // Copy to clipboard and show in HUD instead of AlertDialog
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Detected Nodes", jsonString)
            clipboard.setPrimaryClip(clip)
            
            // Show detailed info in HUD
            val nodeInfo = affordances.joinToString("\n") { 
                "${it.id}: ${it.text} (${it.clazz})"
            }
            hud.show("Found ${affordances.size} nodes:\n$nodeInfo\n\nJSON copied to clipboard")
            
            // Also log the full JSON for debugging
            Log.d("ScreenAgent", "Detected nodes JSON:\n$jsonString")
            
            } catch (e: Exception) {
                Log.e("ScreenAgent", "Error in showDetectedNodes", e)
                hud.show("Error: ${e.message}")
            }
        }
    }

    // ====== TEST 4: Test webhook on system home screen
    private fun testSystemHome() {
        scope.launch {
            try {
                hud.show("Testing system home screen...")
                
                // Wait a bit more for home screen to fully load
                kotlinx.coroutines.delay(1000)
                
                val root = rootInActiveWindow
                Log.d("ScreenAgent", "System home root node: $root")
                
                // Fallback: try to get root from global window list
                var actualRoot = root
                if (actualRoot == null) {
                    Log.d("ScreenAgent", "rootInActiveWindow is null, trying global windows")
                    val windows = windows
                    if (windows != null && windows.isNotEmpty()) {
                        val windowInfo = windows.lastOrNull()
                        actualRoot = windowInfo?.root
                        Log.d("ScreenAgent", "Using root from window: $actualRoot")
                    }
                }
                
                if (actualRoot == null) {
                    hud.show("No root node on home screen")
                    return@launch
                }
                
                val affordances = dumpAffordances(actualRoot)
                Log.d("ScreenAgent", "Found ${affordances.size} affordances on home screen")
                
                if (affordances.isEmpty()) {
                    hud.show("No clickable nodes found on home screen")
                    return@launch
                }
                
                hud.drawAffordances(affordances)
                hud.show("Found ${affordances.size} nodes on home screen")
                
                // Capture screenshot
                val shot = try {
                    Log.d("ScreenAgent", "Attempting system home screenshot...")
                    val result = screenshotter.captureBase64()
                    Log.d("ScreenAgent", "System home screenshot result: ${if (result != null) "SUCCESS (${result.length} chars)" else "FAILED"}")
                    result
                } catch (e: Exception) {
                    Log.e("ScreenAgent", "System home screenshot failed", e)
                    null
                }
                
                if (shot != null) {
                    Log.d("ScreenAgent", "System home screenshot captured successfully, size: ${shot.length} characters")
                } else {
                    Log.w("ScreenAgent", "System home screenshot capture failed")
                }
                
                // Create request and call webhook
                val req = PlanRequest("system-home-test", affordances, shot)
                
                hud.show("Posting to planner…")
                val resp = postPlan(req)
                val action = resp?.action
                if (action == null) {
                    hud.show("No action returned (is n8n running?)")
                    return@launch
                }

                when (action["type"]) {
                    "tap" -> {
                        val id = action["node_id"] as? String
                        if (id != null) {
                            hud.show("Do: tap $id on home screen")
                            val ok = tap(id)
                            hud.show(if (ok) "Tapped $id ✓" else "Tap failed")
                        } else hud.show("Planner reply missing node_id")
                    }
                    "open_url" -> {
                        val url = action["url"] as? String
                        if (url != null) {
                            startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                            hud.show("Opened URL")
                        } else hud.show("Planner reply missing url")
                    }
                    else -> hud.show("Unsupported action")
                }
                
                // Clear after action
                kotlinx.coroutines.delay(2000)
                hud.clearAffordances()
                hud.show("Agent idle")
                
            } catch (e: Exception) {
                Log.e("ScreenAgent", "Error in testSystemHome", e)
                hud.show("Error: ${e.message}")
            }
        }
    }

    // ====== Node dump (collect up to MAX_AFFORDANCES clickable, visible nodes; assign n0..n{MAX_AFFORDANCES-1})
    private fun dumpAffordances(root: AccessibilityNodeInfo?): List<Affordance> {
        if (root == null) {
            Log.d("ScreenAgent", "dumpAffordances: root is null")
            return emptyList()
        }
        
        Log.d("ScreenAgent", "dumpAffordances: scanning tree with ${root.childCount} children")
        val picked = mutableListOf<Affordance>()
        val map = mutableMapOf<String, AccessibilityNodeInfo>()
        val q: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        q.add(root)
        var idx = 0
        var totalNodes = 0
        var clickableNodes = 0
        var visibleNodes = 0
        
        while (q.isNotEmpty() && picked.size < MAX_AFFORDANCES) {
            val n = q.removeFirst()
            totalNodes++
            
            Log.d("ScreenAgent", "Node $totalNodes: clickable=${n.isClickable}, visible=${n.isVisibleToUser}, text='${n.text}', class='${n.className}'")
            
            if (n.isClickable) clickableNodes++
            if (n.isVisibleToUser) visibleNodes++
            
            if (n.isVisibleToUser && n.isClickable) {
                val r = Rect(); n.getBoundsInScreen(r)
                Log.d("ScreenAgent", "Clickable node: bounds=$r, size=${r.width()}x${r.height()}")
                if (r.width() > 10 && r.height() > 10) {
                    // Check if this is the webhook button that should be excluded
                    val text = n.text?.toString() ?: ""
                    val contentDesc = n.contentDescription?.toString() ?: ""
                    val isWebhookButton = text.contains("Trigger Webhook", ignoreCase = true) || 
                                        text.contains("EXCLUDED", ignoreCase = true) ||
                                        contentDesc.contains("Trigger Webhook", ignoreCase = true)
                    
                    if (isWebhookButton) {
                        Log.d("ScreenAgent", "Excluding webhook button from detection")
                        continue
                    }
                    
                    val id = "n$idx"; idx++
                    picked.add(
                        Affordance(
                            id = id,
                            text = text,
                            content_desc = contentDesc,
                            clazz = n.className?.toString(),
                            clickable = true,
                            editable = n.isEditable,
                            bounds = listOf(r.left, r.top, r.right, r.bottom)
                        )
                    )
                    map[id] = n
                    Log.d("ScreenAgent", "Added affordance: $id")
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(q::addLast)
        }
        
        Log.d("ScreenAgent", "dumpAffordances: total=$totalNodes, clickable=$clickableNodes, visible=$visibleNodes, picked=${picked.size} (max $MAX_AFFORDANCES)")
        nodeMap = map
        return picked
    }

    // ====== Tap (click first, then gesture fallback)
    private fun tap(nodeId: String): Boolean {
        val node = nodeMap[nodeId] ?: return false
        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) return true

        val r = Rect(); node.getBoundsInScreen(r)
        val cx = (r.left + r.right) / 2f
        val cy = (r.top + r.bottom) / 2f
        val path = Path().apply { moveTo(cx, cy) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        var ok = false
        val latch = java.util.concurrent.CountDownLatch(1)
        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { ok = true; latch.countDown() }
                override fun onCancelled(g: GestureDescription?) { latch.countDown() }
            }, null
        )
        latch.await(800, java.util.concurrent.TimeUnit.MILLISECONDS)
        return ok
    }

    // ====== Planner POST (kept for when backend is ready)
    private fun 
    postPlan(body: PlanRequest): PlanResponse? = runBlocking(Dispatchers.IO) {
        try {
            val url = URL("https://suhani22.app.n8n.cloud/webhook-test/c54d4207-c2f5-485f-892b-95094e6c30ea")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 12000
                readTimeout = 12000
            }
            val json = JSONObject().apply {
                put("session_id", body.session_id)
                put("screenshot_b64", body.screenshot_b64)
                Log.d("ScreenAgent", "Sending JSON with screenshot_b64: ${if (body.screenshot_b64 != null) "PRESENT (${body.screenshot_b64.length} chars)" else "NULL"}")
                Log.d("ScreenAgent", "Screenshot_b64 value: ${body.screenshot_b64?.take(50)}...")
                Log.d("ScreenAgent", "Full screenshot_b64: ${body.screenshot_b64}")
                put("affordances", JSONArray().apply {
                    body.affordances.forEach { a ->
                        put(JSONObject().apply {
                            put("id", a.id)
                            put("text", a.text)
                            put("content_desc", a.content_desc)
                            put("clazz", a.clazz)
                            put("clickable", a.clickable)
                            put("editable", a.editable)
                            put("bounds", JSONArray(a.bounds.toTypedArray()))
                        })
                    }
                })
            }
            val jsonString = json.toString()
            Log.d("ScreenAgent", "Sending JSON to n8n: $jsonString")
            conn.outputStream.use { it.write(jsonString.toByteArray()) }
            val resp = conn.inputStream.bufferedReader().readText()
            Log.d("ScreenAgent", "n8n response: $resp")
            val obj = JSONObject(resp)
            val actionObj = obj.getJSONObject("action")

            // Manual conversion to Map (Android's JSONObject has no toMap())
            val map = mutableMapOf<String, Any?>()
            if (actionObj.has("type")) map["type"] = actionObj.getString("type")
            if (actionObj.has("node_id")) map["node_id"] = actionObj.getString("node_id")
            if (actionObj.has("url")) map["url"] = actionObj.getString("url")

            PlanResponse(action = map)
        } catch (e: Exception) {
            Log.e("ScreenAgent", "Post failed", e)
            null
        }
    }

    private fun testScreenshotOnly() {
        scope.launch(Dispatchers.IO) {
            Log.d("ScreenAgent", "Testing screenshot only...")
            
            val b64 = screenshotter.captureBase64()
            if (b64 == null) {
                hud.show("Screenshot capture failed!")
                Log.w("ScreenAgent", "Screenshot capture failed")
            } else {
                hud.show("Screenshot captured! Length: ${b64.length} chars")
                Log.d("ScreenAgent", "Screenshot captured successfully: ${b64.take(50)}...")
                Log.d("ScreenAgent", "Full screenshot: $b64")
            }
        }
    }

    private fun handleScreenshotPermissionGranted(intent: Intent) {
        try {
            val resultCode = intent.getIntExtra("result_code", -1)
            val resultData = intent.getParcelableExtra<Intent>("result_data")
            
            Log.d("ScreenAgent", "Received screenshot permission broadcast: resultCode=$resultCode, resultData=$resultData")
            
            if (resultCode == Activity.RESULT_OK && resultData != null) {
                // Create MediaProjection in accessibility service using the broadcasted data
                try {
                    val mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
                    MediaProjectionHolder.mediaProjection = mediaProjection
                    Log.d("ScreenAgent", "MediaProjection created in accessibility service: ${MediaProjectionHolder.mediaProjection != null}")
                    hud.show("Screenshot permission granted!")
                } catch (e: Exception) {
                    Log.e("ScreenAgent", "Failed to create MediaProjection in accessibility service", e)
                    hud.show("Failed to create MediaProjection")
                }
            } else {
                hud.show("Screenshot permission denied")
                Log.w("ScreenAgent", "Screenshot permission denied")
            }
        } catch (e: Exception) {
            Log.e("ScreenAgent", "Failed to handle screenshot permission", e)
            hud.show("Failed to handle screenshot permission")
        }
    }

}

