package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.domain.tools.StopSignalBus
import com.example.features.macros.MacroManager
import com.example.features.safety.SafetyManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

class BabyAccessibilityService : AccessibilityService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AccessibilityEntryPoint {
        fun stopSignalBus(): StopSignalBus
        fun macroManager(): MacroManager
    }

    private var lastVolumeDownTapMs = 0L
    private var lastScrollRecordedMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        val pkg = ev.packageName?.toString().orEmpty()
        if (pkg == packageName) return
        val macroManager = runCatching {
            EntryPointAccessors.fromApplication(applicationContext, AccessibilityEntryPoint::class.java)
                .macroManager()
        }.getOrNull() ?: return

        if (!macroManager.isRecordingMacro.value) return

        val evText = ev.text.joinToString(" ").trim()
        SafetyManager.isBlockedScreen(pkg, evText)?.let { reason ->
            macroManager.reportSensitiveSkipped("$reason macro mein record nahi hoga.")
            return
        }

        when (ev.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val src = ev.source ?: return
                val rect = Rect()
                src.getBoundsInScreen(rect)
                val viewId = src.viewIdResourceName?.take(120)
                val nodeText = src.text?.toString()?.trim()?.take(60)
                val nodeDesc = src.contentDescription?.toString()?.trim()?.take(60)
                val hint = src.hintText?.toString()
                val isSensitive = SafetyManager.isSensitiveField(viewId, nodeText, hint, src.isPassword) || ev.isPassword
                @Suppress("DEPRECATION")
                src.recycle()

                if (isSensitive) {
                    macroManager.reportSensitiveSkipped("Password, OTP ya payment field skip kiya gaya.")
                    return
                }

                val label = nodeText?.takeIf { it.isNotBlank() }
                    ?: nodeDesc?.takeIf { it.isNotBlank() }
                    ?: viewId?.substringAfterLast('/')
                    ?: pkg.substringAfterLast('.').ifBlank { "Tap" }

                if (rect.width() > 0 && rect.height() > 0) {
                    macroManager.recordAccessibilityStep(
                        tool = "tap",
                        args = mapOf("x" to rect.centerX(), "y" to rect.centerY(), "description" to label),
                        label = "Tap $label",
                        viewId = viewId,
                        targetText = nodeText,
                        contentDesc = nodeDesc,
                        packageName = pkg.ifBlank { null }
                    )
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val now = System.currentTimeMillis()
                if (now - lastScrollRecordedMs < 750L) return
                lastScrollRecordedMs = now

                val src = ev.source
                val viewId = src?.viewIdResourceName?.take(120)
                val nodeDesc = src?.contentDescription?.toString()?.trim()?.take(60)
                @Suppress("DEPRECATION")
                src?.recycle()

                val deltaY = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ev.scrollDeltaY else 0
                val direction = when {
                    deltaY < 0 -> "up"
                    deltaY > 0 -> "down"
                    ev.fromIndex >= 0 && ev.toIndex >= 0 && ev.fromIndex > ev.toIndex -> "up"
                    else -> "down"
                }
                macroManager.recordAccessibilityStep(
                    tool = "scroll",
                    args = mapOf("direction" to direction),
                    label = "Scroll $direction",
                    viewId = viewId,
                    contentDesc = nodeDesc,
                    packageName = pkg.ifBlank { null }
                )
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val src = ev.source
                val viewId = src?.viewIdResourceName?.take(120)
                val nodeDesc = src?.contentDescription?.toString()?.trim()?.take(60)
                val hint = src?.hintText?.toString()
                val isSensitive = SafetyManager.isSensitiveField(viewId, evText, hint, src?.isPassword == true) || ev.isPassword
                @Suppress("DEPRECATION")
                src?.recycle()

                if (isSensitive) {
                    macroManager.reportSensitiveSkipped("Password ya OTP text record nahi kiya gaya.")
                    return
                }
                if (evText.isNotBlank()) {
                    macroManager.recordTextStepCoalesced(
                        text = evText,
                        viewId = viewId,
                        contentDesc = nodeDesc,
                        packageName = pkg.ifBlank { null }
                    )
                }
            }
        }
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val now = System.currentTimeMillis()
            if (now - lastVolumeDownTapMs <= 550L) {
                lastVolumeDownTapMs = 0L
                runCatching {
                    val ep = EntryPointAccessors.fromApplication(
                        applicationContext,
                        AccessibilityEntryPoint::class.java
                    )
                    ep.stopSignalBus().requestStop("Volume-down double-press shortcut")
                }
            } else {
                lastVolumeDownTapMs = now
            }
        }
        return super.onKeyEvent(event)
    }

    fun getActivePackageName(): String? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString()
        @Suppress("DEPRECATION")
        root.recycle()
        return pkg
    }

    suspend fun findAndActByIdentifier(
        tool: String,
        viewId: String?,
        targetText: String?,
        contentDesc: String?,
        expectedPackage: String?,
        textToType: String? = null,
        scrollDirection: String? = null
    ): Boolean {
        val root = rootInActiveWindow ?: return false
        val rootPkg = root.packageName?.toString().orEmpty()
        if (!expectedPackage.isNullOrBlank() && rootPkg.isNotBlank() &&
            !rootPkg.equals(expectedPackage, ignoreCase = true)
        ) {
            @Suppress("DEPRECATION")
            root.recycle()
            return false
        }

        if (tool == "scroll") {
            val dir = scrollDirection ?: "down"
            val targetNode = if (!viewId.isNullOrBlank()) findMatchingNode(root, viewId, null, null) else findScrollableNode(root)
            val action = if (dir.equals("up", true) || dir.equals("backward", true)) {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            val performed = targetNode?.performAction(action) == true
            @Suppress("DEPRECATION")
            targetNode?.recycle()
            @Suppress("DEPRECATION")
            root.recycle()
            return if (performed) true else scroll(dir)
        }

        val matched = findMatchingNode(root, viewId, targetText, contentDesc)
        if (matched == null) {
            @Suppress("DEPRECATION")
            root.recycle()
            return false
        }

        val result = when (tool) {
            "type_text" -> {
                matched.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType.orEmpty())
                }
                matched.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            "long_press" -> {
                if (matched.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                    true
                } else {
                    val rect = Rect()
                    matched.getBoundsInScreen(rect)
                    if (rect.width() > 0 && rect.height() > 0) longPress(rect.centerX().toFloat(), rect.centerY().toFloat())
                    else false
                }
            }
            else -> { // tap
                val clicked = tryClickNodeOrParent(matched)
                if (clicked) {
                    true
                } else {
                    val rect = Rect()
                    matched.getBoundsInScreen(rect)
                    if (rect.width() > 0 && rect.height() > 0) tap(rect.centerX().toFloat(), rect.centerY().toFloat())
                    else false
                }
            }
        }

        @Suppress("DEPRECATION")
        matched.recycle()
        @Suppress("DEPRECATION")
        root.recycle()
        return result
    }

    private fun tryClickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = node.parent
        var hops = 0
        while (parent != null && hops < 4) {
            val ok = parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val next = if (!ok) parent.parent else null
            @Suppress("DEPRECATION")
            parent.recycle()
            if (ok) return true
            parent = next
            hops++
        }
        return false
    }

    private fun findMatchingNode(
        root: AccessibilityNodeInfo,
        viewId: String?,
        targetText: String?,
        contentDesc: String?
    ): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
            if (n !== root) candidates.add(n)
        }

        fun scoreNode(n: AccessibilityNodeInfo): Int {
            var score = 0
            val nId = n.viewIdResourceName
            val nText = n.text?.toString()?.trim()
            val nDesc = n.contentDescription?.toString()?.trim()
            if (!viewId.isNullOrBlank() && nId == viewId) score += 4
            if (!targetText.isNullOrBlank() && nText.equals(targetText, ignoreCase = true)) score += 3
            if (!contentDesc.isNullOrBlank() && nDesc.equals(contentDesc, ignoreCase = true)) score += 3
            return score
        }

        val best = candidates
            .map { it to scoreNode(it) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first

        candidates.forEach { node ->
            if (node !== best) {
                @Suppress("DEPRECATION")
                node.recycle()
            }
        }
        return best
    }

    private fun findScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.isScrollable && n !== root) return n
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
            if (n !== root) {
                @Suppress("DEPRECATION")
                n.recycle()
            }
        }
        return null
    }

    fun readUiTree(maxNodes: Int = 90): String {
        val root = rootInActiveWindow ?: return "No active window visible."
        val sb = StringBuilder()
        val pkg = root.packageName?.toString().orEmpty()
        sb.appendLine("ActiveApp: $pkg")

        var count = 0
        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || count >= maxNodes) return
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val isActionable = node.isClickable || node.isEditable || node.isScrollable ||
                node.isLongClickable || node.isCheckable || text.isNotEmpty() || desc.isNotEmpty()

            if (isActionable && rect.width() > 0 && rect.height() > 0) {
                val cx = rect.centerX()
                val cy = rect.centerY()
                val shortClass = node.className?.toString()?.substringAfterLast('.') ?: "View"
                val flags = buildList {
                    if (node.isClickable) add("click")
                    if (node.isEditable) add("edit")
                    if (node.isScrollable) add("scroll")
                    if (node.isFocused) add("focused")
                    if (node.isChecked) add("checked")
                }.joinToString(",")
                val label = when {
                    text.isNotEmpty() && desc.isNotEmpty() && text != desc -> "\"$text\" ($desc)"
                    text.isNotEmpty() -> "\"$text\""
                    desc.isNotEmpty() -> "($desc)"
                    else -> ""
                }
                val idShort = node.viewIdResourceName?.substringAfterLast('/')?.let { "#$it" }.orEmpty()
                sb.appendLine("${"  ".repeat(depth.coerceAtMost(4))}[$shortClass$idShort] $label @($cx,$cy) [$flags]")
                count++
            }
            for (i in 0 until node.childCount) {
                if (count >= maxNodes) break
                val child = node.getChild(i)
                traverse(child, depth + 1)
                @Suppress("DEPRECATION")
                child?.recycle()
            }
        }
        traverse(root, 0)
        @Suppress("DEPRECATION")
        root.recycle()
        return sb.toString().trim()
    }

    suspend fun tap(x: Float, y: Float): Boolean = withContext(Dispatchers.Main) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        dispatchGestureSuspend(GestureDescription.Builder().addStroke(stroke).build())
    }

    suspend fun longPress(x: Float, y: Float): Boolean = withContext(Dispatchers.Main) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 650L)
        dispatchGestureSuspend(GestureDescription.Builder().addStroke(stroke).build())
    }

    suspend fun swipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 320L
    ): Boolean = withContext(Dispatchers.Main) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(80L, 2000L))
        dispatchGestureSuspend(GestureDescription.Builder().addStroke(stroke).build())
    }

    suspend fun scroll(direction: String): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        return when (direction.lowercase()) {
            "down", "forward" -> swipe(w * 0.5f, h * 0.72f, w * 0.5f, h * 0.28f, 340L)
            "up", "backward" -> swipe(w * 0.5f, h * 0.30f, w * 0.5f, h * 0.75f, 340L)
            "left" -> swipe(w * 0.80f, h * 0.5f, w * 0.20f, h * 0.5f, 300L)
            "right" -> swipe(w * 0.20f, h * 0.5f, w * 0.80f, h * 0.5f, 300L)
            else -> swipe(w * 0.5f, h * 0.72f, w * 0.5f, h * 0.28f, 340L)
        }
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findEditableNode(root) ?: run {
            @Suppress("DEPRECATION")
            root.recycle()
            return false
        }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        @Suppress("DEPRECATION")
        target.recycle()
        @Suppress("DEPRECATION")
        root.recycle()
        return ok
    }

    private fun findEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused
        @Suppress("DEPRECATION")
        focused?.recycle()

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    fun performBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun performHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun performRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    suspend fun takeScreenshotBase64(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            try {
                                val hwBitmap = Bitmap.wrapHardwareBuffer(
                                    screenshot.hardwareBuffer,
                                    screenshot.colorSpace
                                )
                                screenshot.hardwareBuffer.close()
                                if (hwBitmap == null) {
                                    cont.resume(null)
                                    return
                                }
                                val swBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                hwBitmap.recycle()
                                val maxDim = 768
                                val scale = minOf(
                                    1f,
                                    maxDim.toFloat() / maxOf(swBitmap.width, swBitmap.height).coerceAtLeast(1)
                                )
                                val scaled = if (scale < 1f) {
                                    Bitmap.createScaledBitmap(
                                        swBitmap,
                                        (swBitmap.width * scale).toInt().coerceAtLeast(1),
                                        (swBitmap.height * scale).toInt().coerceAtLeast(1),
                                        true
                                    ).also { swBitmap.recycle() }
                                } else {
                                    swBitmap
                                }
                                val out = ByteArrayOutputStream()
                                scaled.compress(Bitmap.CompressFormat.JPEG, 65, out)
                                scaled.recycle()
                                cont.resume(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                            } catch (_: Exception) {
                                cont.resume(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            cont.resume(null)
                        }
                    }
                )
            }
        }
    }

    private suspend fun dispatchGestureSuspend(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        }

    companion object {
        @Volatile
        var instance: BabyAccessibilityService? = null
            private set
    }
}
