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
import android.content.ClipData
import android.content.ClipboardManager
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
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
                "com.example.screenagent.RUN_ONCE" -> doOneStep(i.getStringExtra("transcript_text"))
                "com.example.screenagent.TEST_NODES" -> testNodesOverlay()
                "com.example.screenagent.TEST_SCREENSHOT" -> testScreenshotSave()
                "com.example.screenagent.TEST_SCREENSHOT_ONLY" -> testScreenshotOnly()
                "com.example.screenagent.SHOW_NODES" -> showDetectedNodes()
                "com.example.screenagent.TEST_SYSTEM_HOME" -> testSystemHome(i.getStringExtra("transcript_text"))
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

    // Helper: get current root with fallback to last window root
    private fun currentRoot(): AccessibilityNodeInfo? {
        var r = rootInActiveWindow
        if (r == null) {
            try {
                val ws = windows
                if (ws != null && ws.isNotEmpty()) {
                    r = ws.lastOrNull()?.root
                }
            } catch (_: Throwable) {}
        }
        return r
    }

    // ====== MAIN MVP STEP (kept for when backend is ready)
    fun doOneStep(transcriptText: String? = null) {
        scope.launch {
            hud.show("Observing…")
            val affordances = dumpAffordances(currentRoot())
            // hud.drawAffordances(affordances) // disabled debug overlay

            val isNewTask = !transcriptText.isNullOrBlank()
            // Always re-collect affordances and screenshot immediately before send
            val freshAff = dumpAffordances(currentRoot())
            val freshShot = try { createAnnotatedScreenshotBase64(freshAff) } catch (_: Throwable) { null }
            val req = PlanRequest("mvp-1", freshAff, freshShot, transcriptText, isNewTask)
            hud.show("Posting to planner…")
            val resp = postPlan(req)
            val action = resp?.action
            if (action == null) {
                hud.show("No action returned (is n8n running?)")
                return@launch
            }

            val type = (action["type"] as? String)?.lowercase(java.util.Locale.US)
            when (type) {
                "tap" -> {
                    val id = action["node_id"] as? String
                    if (id != null) {
                        hud.show("Do: tap $id")
                        if (!nodeMap.containsKey(id)) {
                            Log.w("ScreenAgent", "Unknown node_id $id. Known ids: ${nodeMap.keys}")
                        }
                        val ok = tap(id)
                        hud.show(if (ok) "Tapped $id ✓" else "Tap failed")

                        // If task not complete, loop: recapture and post again with same transcript
                        var complete = resp?.isComplete == true
                        var safety = 0
                        while (!complete) {
                            // Give UI time to update after action
                            kotlinx.coroutines.delay(800)
                            val newAff = dumpAffordances(currentRoot())
                            val newShot = try { createAnnotatedScreenshotBase64(newAff) } catch (_: Throwable) { null }
                            val followReq = PlanRequest("mvp-1", newAff, newShot, transcriptText, false)
                            hud.show("Posting follow-up…")
                            val follow = postPlan(followReq)
                            complete = follow?.isComplete == true
                            val act = follow?.action
                            if (act != null && act["type"] == "tap") {
                                val nextId = act["node_id"] as? String
                                if (nextId != null) {
                                    hud.show("Do: tap $nextId")
                                    val ok2 = tap(nextId)
                                    hud.show(if (ok2) "Tapped $nextId ✓" else "Tap failed")
                                }
                            }
                            safety++
                            if (safety % 10 == 0) Log.i("ScreenAgent", "Follow-up loop iteration=$safety (waiting for isComplete=true)")
                        }
                    } else hud.show("Planner reply missing node_id")
                }
                "set_text" -> {
                    val id = action["node_id"] as? String
                    val text = action["text"] as? String
                    if (id != null && text != null) {
                        hud.show("Do: set_text $id")
                        val typed = setTextSmart(id, text)
                        hud.show(if (typed) "Text set ✓" else "Set text failed")
                        // Always try to press IME action afterwards
                        pressImeAction()
                        // After local input, post the follow-up
                        kotlinx.coroutines.delay(500)
                        val newAff = dumpAffordances(currentRoot())
                        val newShot = try { createAnnotatedScreenshotBase64(newAff) } catch (_: Throwable) { null }
                        val followReq = PlanRequest("mvp-1", newAff, newShot, transcriptText, false)
                        hud.show("Posting follow-up…")
                        postPlan(followReq)
                    } else hud.show("Planner reply missing node_id/text")
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
    private fun testSystemHome(transcriptText: String? = null) {
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
                
                // hud.drawAffordances(affordances) // disabled debug overlay
                hud.show("Found ${affordances.size} nodes on home screen")
                
                // Capture annotated screenshot
                val shot = try {
                    Log.d("ScreenAgent", "Attempting system home annotated screenshot...")
                    val result = createAnnotatedScreenshotBase64(affordances)
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
                val req = PlanRequest("system-home-test", affordances, shot, transcriptText)
                
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

                            // If task not complete, loop with same transcript
                            var complete = resp?.isComplete == true
                            var safety = 0
                            while (!complete) {
                                // Give launcher time to render the next frame
                                kotlinx.coroutines.delay(800)
                                val newAff = dumpAffordances(currentRoot())
                                val newShot = try { createAnnotatedScreenshotBase64(newAff) } catch (_: Throwable) { null }
                                val followReq = PlanRequest("system-home-test", newAff, newShot, transcriptText, false)
                                hud.show("Posting follow-up…")
                                val follow = postPlan(followReq)
                                complete = follow?.isComplete == true
                                val act = follow?.action
                                if (act != null && act["type"] == "tap") {
                                    val nextId = act["node_id"] as? String
                                    if (nextId != null) {
                                        hud.show("Do: tap $nextId on home screen")
                                        val ok2 = tap(nextId)
                                        hud.show(if (ok2) "Tapped $nextId ✓" else "Tap failed")
                                    }
                                }
                                safety++
                                if (safety % 10 == 0) Log.i("ScreenAgent", "Home follow-up loop iteration=$safety (waiting for isComplete=true)")
                            }
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

    private fun isLikelyTextInput(n: AccessibilityNodeInfo): Boolean {
        val cls = n.className?.toString() ?: return n.isEditable
        val lower = cls.lowercase(java.util.Locale.US)
        if (n.isEditable) return true
        return lower.contains("edittext") ||
                lower.contains("autocompletetextview") ||
                lower.contains("searchautocomplete") ||
                lower.contains("textinputedittext") ||
                lower.contains("urlbar") ||
                lower.contains("omnibox")
    }

    // ====== Node dump (collect up to MAX_AFFORDANCES actionable + inputs; assign n0..n{MAX_AFFORDANCES-1})
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
            
            val isInput = isLikelyTextInput(n)
            val isActionable = n.isClickable || isInput
            if (n.isVisibleToUser && isActionable) {
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
                            clickable = n.isClickable,
                            editable = n.isEditable || isInput,
                            bounds = listOf(r.left, r.top, r.right, r.bottom)
                        )
                    )
                    map[id] = n
                    Log.d("ScreenAgent", "Added affordance: $id (clickable=${n.isClickable}, editable=${n.isEditable || isInput}, class=${n.className})")
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(q::addLast)
        }
        
        Log.d("ScreenAgent", "dumpAffordances: total=$totalNodes, clickable=$clickableNodes, visible=$visibleNodes, picked=${picked.size} (max $MAX_AFFORDANCES)")
        nodeMap = map
        return picked
    }

    // ====== Tap (robust): clickable ancestor → focus → click → gesture → long-press
    private fun tap(nodeId: String): Boolean {
        val original = nodeMap[nodeId] ?: return false

        // Try a clickable ancestor if the node is not directly clickable
        var node = original
        var hopCount = 0
        while (!node.isClickable && node.parent != null && hopCount < 5) {
            node = node.parent
            hopCount++
        }

        // Give it accessibility focus, then regular focus
        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        // Try direct click first
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true

        // Gesture tap at the node's center (use a tiny 1px line, some OS versions ignore zero-length paths)
        val r = Rect(); node.getBoundsInScreen(r)
        val cx = (r.left + r.right) / 2f
        val cy = (r.top + r.bottom) / 2f
        val path = Path().apply { moveTo(cx, cy); lineTo(cx + 1f, cy + 1f) }

        // Short tap
        var ok = false
        var latch = java.util.concurrent.CountDownLatch(1)
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 120))
                .build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; latch.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
            },
            null
        )
        latch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (ok) return true

        // Long press fallback
        ok = false
        latch = java.util.concurrent.CountDownLatch(1)
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
                .build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; latch.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
            },
            null
        )
        latch.await(1200, java.util.concurrent.TimeUnit.MILLISECONDS)
        return ok
    }

    // Center-tap utility for a specific node (no lookup)
    private fun tapCenter(node: AccessibilityNodeInfo, pressMs: Long = 120): Boolean {
        val r = Rect(); node.getBoundsInScreen(r)
        val cx = (r.left + r.right) / 2f
        val cy = (r.top + r.bottom) / 2f
        val path = Path().apply { moveTo(cx, cy); lineTo(cx + 1f, cy + 1f) }
        var ok = false
        val latch = java.util.concurrent.CountDownLatch(1)
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, pressMs))
                .build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; latch.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
            },
            null
        )
        latch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        return ok
    }

    // ====== Annotate screenshot with affordance bounds and ids, return base64 JPEG
    private fun createAnnotatedScreenshotBase64(affordances: List<Affordance>): String? {
        val base = screenshotter.captureBitmap() ?: return null
        val mutable = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)
        val paintRect = Paint().apply {
            style = Paint.Style.STROKE
            color = 0xFF00FF00.toInt()
            strokeWidth = 3f
            isAntiAlias = true
        }
        val paintTextBg = Paint().apply {
            style = Paint.Style.FILL
            color = 0xAA000000.toInt()
            isAntiAlias = true
        }
        val paintText = Paint().apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 28f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            isAntiAlias = true
        }

        affordances.forEach { a ->
            if (a.bounds.size == 4) {
                val l = a.bounds[0].toFloat()
                val t = a.bounds[1].toFloat()
                val r = a.bounds[2].toFloat()
                val b = a.bounds[3].toFloat()
                canvas.drawRect(l, t, r, b, paintRect)
                val label = a.id
                val tw = paintText.measureText(label)
                val th = paintText.fontMetrics.run { bottom - top }
                val px = l + 6f
                val py = t + th + 6f
                canvas.drawRect(px - 4f, py - th - 6f, px + tw + 4f, py + 4f, paintTextBg)
                canvas.drawText(label, px, py, paintText)
            }
        }

        return try {
            screenshotter.convertBitmapToBase64(mutable)
        } catch (_: Throwable) { null }
    }

    // ====== Set text (smart): focus/activate field → select-all → setText → clipboard paste fallback
    private fun setTextSmart(nodeId: String, text: String): Boolean {
        // 1) Resolve target node
        val node: AccessibilityNodeInfo = nodeMap[nodeId] ?: run {
            Log.w("ScreenAgent", "setText: node_id=$nodeId not found in nodeMap. Known ids=${nodeMap.keys}")
            return false
        }

        // Prefer an editable ancestor for focus/tap
        var target: AccessibilityNodeInfo = node
        var hops = 0
        while (!target.isEditable && target.parent != null && hops < 5) {
            Log.i("ScreenAgent", "setText: climb parent hop=${hops + 1} from=${target.className}")
            target = target.parent!!
            hops++
        }
        Log.i("ScreenAgent", "setText: target class=${target.className} editable=${target.isEditable}")

        // 2) Tap the field and ensure focus (bring up IME)
        tapCenter(target, 120)
        android.os.SystemClock.sleep(1200)
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        if (!target.isFocused && target.isClickable) target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        try { target.refresh() } catch (_: Throwable) {}
        android.os.SystemClock.sleep(350)

        // 3) Type using on-screen keyboard keys
        var ok = typeWithOnscreenKeyboard(text)
        if (!ok) {
            // Fallback: clipboard + explicit Paste menu tap
            ok = pasteViaContextMenu(target, text)
        }
        Log.i("ScreenAgent", "setText: keyboard type result=$ok")
        return ok
    }

    private fun typeWithOnscreenKeyboard(text: String): Boolean {
        var allOk = true
        for (ch in text) {
            val labels = when (ch) {
                ' ' -> listOf("space", " ")
                '.' -> listOf(".", "period")
                ',' -> listOf(",", "comma")
                '-' -> listOf("-", "dash")
                '\n' -> listOf("enter", "return")
                else -> listOf(ch.lowercaseChar().toString(), ch.uppercaseChar().toString())
            }
            val key = findKeyboardKey(labels)
            if (key != null) {
                tapCenter(key, 120)
                android.os.SystemClock.sleep(80)
            } else {
                Log.w("ScreenAgent", "setText: key not found for '$ch'")
                allOk = false
            }
        }
        return allOk
    }

    private fun findKeyboardKey(labels: List<String>): AccessibilityNodeInfo? {
        val ws = windows
        if (ws != null && ws.isNotEmpty()) {
            // Prefer the input method window first
            val ordered = ws.sortedBy { w -> if (android.os.Build.VERSION.SDK_INT >= 21) w.type else 0 }
            for (w in ordered.reversed()) {
                val root = w.root ?: continue
                val q: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
                q.add(root)
                while (q.isNotEmpty()) {
                    val n = q.removeFirst()
                    val text = n.text?.toString()
                    val desc = n.contentDescription?.toString()
                    val cls = n.className?.toString()?.lowercase(java.util.Locale.US) ?: ""
                    val matches = labels.any { lbl ->
                        (text != null && text.equals(lbl, true)) ||
                        (desc != null && desc.equals(lbl, true)) ||
                        (lbl == "space" && (desc?.contains("space", true) == true || text?.contains("space", true) == true))
                    }
                    if (matches && n.isVisibleToUser && (cls.contains("button") || cls.contains("image") || cls.contains("key") || cls.contains("view"))) {
                        return n
                    }
                    for (i in 0 until n.childCount) n.getChild(i)?.let(q::addLast)
                }
            }
        }
        return null
    }

    private fun pasteViaContextMenu(target: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("a11y", text))
            // Long-press to show context menu
            tapCenter(target, 500)
            android.os.SystemClock.sleep(300)
            val pasteNode = findNodeByLabels(listOf("Paste", "PASTE"))
            return if (pasteNode != null) {
                tapCenter(pasteNode, 120)
                true
            } else false
        } catch (_: Throwable) { false }
    }

    private fun findNodeByLabels(labels: List<String>): AccessibilityNodeInfo? {
        val ws = windows
        if (ws != null && ws.isNotEmpty()) {
            for (w in ws.reversed()) {
                val root = w.root ?: continue
                val q: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
                q.add(root)
                while (q.isNotEmpty()) {
                    val n = q.removeFirst()
                    val text = n.text?.toString()
                    val desc = n.contentDescription?.toString()
                    if (labels.any { lbl -> text?.equals(lbl, true) == true || desc?.equals(lbl, true) == true }) {
                        return n
                    }
                    for (i in 0 until n.childCount) n.getChild(i)?.let(q::addLast)
                }
            }
        }
        return null
    }

    private fun pressImeAction() {
        // Try to find common IME action buttons and tap them
        val candidates = listOf("enter", "search", "go", "done", "send", "↵")
        val node = findKeyboardKey(candidates)
        if (node != null) {
            tapCenter(node, 120)
        } else {
            // As a fallback, try to find a view labeled with Search/Enter in the window tree
            val alt = findNodeByLabels(listOf("Search", "Enter", "Go", "Done", "Send"))
            if (alt != null) tapCenter(alt, 120)
        }
    }

    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        val root = currentRoot() ?: return null
        val q: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        q.add(root)
        var fallback: AccessibilityNodeInfo? = null
        while (q.isNotEmpty()) {
            val n = q.removeFirst()
            if (n.isEditable) {
                if (n.isFocused || n.isAccessibilityFocused) return n
                if (fallback == null) fallback = n
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(q::addLast)
        }
        return fallback
    }

    // ====== Planner POST (kept for when backend is ready)
    private fun 
    postPlan(body: PlanRequest): PlanResponse? = runBlocking(Dispatchers.IO) {
        try {
            val url = URL("https://suhani22.app.n8n.cloud/webhook/c54d4207-c2f5-485f-892b-95094e6c30ea")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                // Larger timeouts to handle big screenshots and n8n processing
                connectTimeout = 30000
                readTimeout = 60000
                // Use chunked streaming to avoid buffering the entire body
                setChunkedStreamingMode(0)
            }
            val json = JSONObject().apply {
                put("session_id", body.session_id)
                put("screenshot_b64", body.screenshot_b64)
                if (body.transcript_text != null) put("transcript_text", body.transcript_text)
                put("is_new_task", body.is_new_task)
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
                if (body.transcript_text != null) {
                    put("transcript_text", body.transcript_text)
                }
            }
            // Serialize JSON and post
            val jsonString = json.toString()
            val bytes = jsonString.toByteArray()
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val resp = stream.bufferedReader().readText()
            
            // Parse response that may be an array, or object, possibly wrapped in output_json and/or output
            var rootAny: Any = try { org.json.JSONTokener(resp).nextValue() } catch (_: Throwable) { JSONObject(resp) }
            var rootObj: JSONObject = when (rootAny) {
                is JSONArray -> (rootAny as JSONArray).optJSONObject(0) ?: JSONObject()
                is JSONObject -> rootAny as JSONObject
                else -> JSONObject()
            }
            // Unwrap optional { "output_json": ... }
            if (rootObj.has("output_json")) {
                val inner = rootObj.get("output_json")
                rootObj = when (inner) {
                    is JSONObject -> inner
                    is String -> try { JSONObject(inner) } catch (_: Throwable) { rootObj }
                    else -> rootObj
                }
            }
            // Some flows return { "output": { type, node_id, isComplete, ... } }
            val payloadObj: JSONObject = when {
                rootObj.has("action") -> rootObj // preferred
                rootObj.has("output") -> {
                    val out = rootObj.get("output")
                    when (out) {
                        is JSONObject -> out
                        is String -> try { JSONObject(out) } catch (_: Throwable) { JSONObject() }
                        else -> JSONObject()
                    }
                }
                else -> rootObj
            }

            val actionSource = if (payloadObj.has("action")) payloadObj.getJSONObject("action") else payloadObj
            val isComplete = when {
                rootObj.has("isComplete") -> rootObj.optBoolean("isComplete", false)
                payloadObj.has("isComplete") -> payloadObj.optBoolean("isComplete", false)
                else -> false
            }

            val actionObj = actionSource

            // Manual conversion to Map (Android's JSONObject has no toMap())
            val map = mutableMapOf<String, Any?>()
            // Normalize schema per your spec: top-level may directly be {type, node_id|text|url, isComplete}
            if (actionObj.has("type")) map["type"] = actionObj.getString("type")
            if (actionObj.has("node_id")) map["node_id"] = actionObj.getString("node_id")
            if (actionObj.has("text")) map["text"] = actionObj.getString("text")
            if (actionObj.has("url")) map["url"] = actionObj.getString("url")

            PlanResponse(action = map, isComplete = isComplete)
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

