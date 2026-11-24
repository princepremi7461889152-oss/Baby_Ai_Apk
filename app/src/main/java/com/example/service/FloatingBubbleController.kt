package com.example.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.example.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class FloatingBubbleController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootView: LinearLayout? = null
    private var mainPill: LinearLayout? = null
    private var menuRow: LinearLayout? = null
    private var labelView: TextView? = null
    private var stopView: TextView? = null
    private var clipView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var snapAnimator: ValueAnimator? = null

    private var onTapBubble: (() -> Unit)? = null
    private var onTapStop: (() -> Unit)? = null
    private var onTapClip: (() -> Unit)? = null

    private var isTaskActive: Boolean = false
    private var menuExpanded: Boolean = false

    private val idleDimRunnable = Runnable {
        if (!isTaskActive && !menuExpanded) {
            rootView?.animate()?.alpha(0.56f)?.setDuration(260L)?.start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show(
        onBubbleClick: () -> Unit,
        onStopClick: () -> Unit,
        onClipClick: (() -> Unit)? = null,
        showClipButton: Boolean = true
    ) {
        val wm = windowManager ?: return
        if (!Settings.canDrawOverlays(context)) return
        onTapBubble = onBubbleClick
        onTapStop = onStopClick
        onTapClip = onClipClick
        clipView?.visibility = if (showClipButton && onClipClick != null) View.VISIBLE else View.GONE
        if (rootView != null) return

        val dp = { v: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                v.toFloat(),
                context.resources.displayMetrics
            ).toInt()
        }

        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            alpha = 0.92f
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(Color.parseColor("#EB121726"))
                setStroke(dp(2), Color.parseColor("#FF8A5B"))
            }
            elevation = dp(8).toFloat()
        }

        val orbDot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                marginEnd = dp(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2DD4BF"))
            }
        }

        val bubbleText = TextView(context).apply {
            text = "Baby"
            setTextColor(Color.parseColor("#FFF5F7FC"))
            textSize = 13f
            setPadding(dp(2), dp(4), dp(6), dp(4))
        }

        val clipBtn = TextView(context).apply {
            text = "Clip"
            setTextColor(Color.parseColor("#2DD4BF"))
            textSize = 11f
            visibility = if (showClipButton && onClipClick != null) View.VISIBLE else View.GONE
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#1A2237"))
            }
            setOnClickListener {
                wakeAlpha()
                onTapClip?.invoke()
            }
        }

        val stopBtn = TextView(context).apply {
            text = "STOP"
            setTextColor(Color.WHITE)
            textSize = 11f
            visibility = View.GONE
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#EF4444"))
            }
            setOnClickListener {
                wakeAlpha()
                onTapStop?.invoke()
            }
        }

        container.addView(orbDot)
        container.addView(bubbleText)
        container.addView(clipBtn)
        container.addView(stopBtn)

        val quickMenu = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#F21A2237"))
                setStroke(dp(1), Color.parseColor("#313D5C"))
            }
            elevation = dp(8).toFloat()
        }

        fun makeMenuAction(label: String, colorHex: String, action: () -> Unit): TextView {
            return TextView(context).apply {
                text = label
                setTextColor(Color.parseColor(colorHex))
                textSize = 12f
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener {
                    toggleQuickMenu(false)
                    action()
                }
            }
        }

        quickMenu.addView(makeMenuAction("Mic", "#FF8A5B") { onTapBubble?.invoke() })
        quickMenu.addView(makeMenuAction("Stop", "#F87171") { onTapStop?.invoke() })
        quickMenu.addView(makeMenuAction("Open", "#2DD4BF") {
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                )
            }
        })
        quickMenu.addView(makeMenuAction("Hide", "#B3BDD4") { hide() })

        wrapper.addView(container)
        wrapper.addView(quickMenu)

        val (screenW, screenH, topInset) = getScreenBounds(dp)
        // Spawn on top-right below status bar, safely above hero card & quick-command chips.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW - dp(132)).coerceAtLeast(dp(12))
            y = (topInset + dp(72)).coerceAtMost((screenH * 0.45f).toInt())
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var longPressTriggered = false
        val longPressRunnable = Runnable {
            longPressTriggered = true
            toggleQuickMenu(!menuExpanded)
        }

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnimator?.cancel()
                    wakeAlpha()
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    longPressTriggered = false
                    mainHandler.postDelayed(longPressRunnable, 460L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > 12 || abs(dy) > 12) {
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    val (w, h, insetTop) = getScreenBounds(dp)
                    val bubbleW = wrapper.width.takeIf { it > 0 } ?: dp(104)
                    val bubbleH = wrapper.height.takeIf { it > 0 } ?: dp(48)
                    val margin = dp(8)
                    val maxBottomClearance = dp(124)
                    params.x = (initialX + dx.toInt()).coerceIn(margin, (w - bubbleW - margin).coerceAtLeast(margin))
                    params.y = (initialY + dy.toInt()).coerceIn(
                        insetTop + margin,
                        (h - bubbleH - maxBottomClearance).coerceAtLeast(insetTop + margin)
                    )
                    runCatching { wm.updateViewLayout(wrapper, params) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)
                    if (!longPressTriggered && dx < 14 && dy < 14) {
                        if (menuExpanded) {
                            toggleQuickMenu(false)
                        } else {
                            onTapBubble?.invoke()
                        }
                    } else if (!longPressTriggered) {
                        snapToNearestEdge(wrapper, params, dp)
                    }
                    scheduleIdleDim()
                    true
                }
                else -> false
            }
        }

        runCatching {
            wm.addView(wrapper, params)
            rootView = wrapper
            mainPill = container
            menuRow = quickMenu
            labelView = bubbleText
            stopView = stopBtn
            clipView = clipBtn
            layoutParams = params
            scheduleIdleDim()
        }
    }

    private fun toggleQuickMenu(expand: Boolean) {
        menuExpanded = expand
        menuRow?.visibility = if (expand) View.VISIBLE else View.GONE
        if (expand) wakeAlpha() else scheduleIdleDim()
        rootView?.let { v ->
            layoutParams?.let { p ->
                runCatching { windowManager?.updateViewLayout(v, p) }
            }
        }
    }

    private fun wakeAlpha() {
        mainHandler.removeCallbacks(idleDimRunnable)
        rootView?.animate()?.alpha(0.96f)?.setDuration(140L)?.start()
    }

    private fun scheduleIdleDim() {
        mainHandler.removeCallbacks(idleDimRunnable)
        if (!isTaskActive && !menuExpanded) {
            mainHandler.postDelayed(idleDimRunnable, 2800L)
        }
    }

    private fun snapToNearestEdge(
        view: View,
        params: WindowManager.LayoutParams,
        dp: (Int) -> Int
    ) {
        val (screenW, screenH, insetTop) = getScreenBounds(dp)
        val viewWidth = view.width.takeIf { it > 0 } ?: dp(104)
        val viewHeight = view.height.takeIf { it > 0 } ?: dp(48)
        val margin = dp(10)
        val maxLeft = (screenW - viewWidth - margin).coerceAtLeast(margin)
        val center = params.x + viewWidth / 2
        val targetX = if (center < screenW / 2) margin else maxLeft
        val maxY = (screenH - viewHeight - dp(124)).coerceAtLeast(insetTop + dp(8))
        params.y = params.y.coerceIn(insetTop + dp(8), maxY)

        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 220L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                params.x = anim.animatedValue as Int
                runCatching { windowManager?.updateViewLayout(view, params) }
            }
            start()
        }
    }

    private fun getScreenBounds(dp: (Int) -> Int): Triple<Int, Int, Int> {
        val wm = windowManager
        return if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val bounds = metrics.bounds
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout()
            )
            Triple(bounds.width(), bounds.height(), insets.top)
        } else {
            val dm = context.resources.displayMetrics
            Triple(dm.widthPixels, dm.heightPixels, dp(24))
        }
    }

    fun updateStatus(statusLabel: String, isTaskRunning: Boolean) {
        isTaskActive = isTaskRunning
        rootView?.post {
            labelView?.text = statusLabel
            stopView?.visibility = if (isTaskRunning) View.VISIBLE else View.GONE
            if (isTaskRunning) {
                wakeAlpha()
            } else {
                scheduleIdleDim()
            }
        }
    }

    fun hide() {
        mainHandler.removeCallbacksAndMessages(null)
        snapAnimator?.cancel()
        rootView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        rootView = null
        mainPill = null
        menuRow = null
        labelView = null
        stopView = null
        clipView = null
        layoutParams = null
        menuExpanded = false
    }
}
