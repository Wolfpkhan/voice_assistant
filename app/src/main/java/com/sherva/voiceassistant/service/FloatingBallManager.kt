package com.sherva.voiceassistant.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import com.sherva.voiceassistant.AppLog
import com.sherva.voiceassistant.R
import com.sherva.voiceassistant.pipeline.VoiceAssistant
import kotlin.math.abs

/**
 * 悬浮球管理器：在屏幕上添加一个可拖拽的小球，点击触发语音开始/停止。
 *
 * 生命周期：创建后调用 show()，关闭时调用 dismiss()。
 * Activity 与 Service 共用：通过 WindowManager 添加全局 view。
 */
class FloatingBallManager(
    private val ctx: Context,
    private val onClick: () -> Unit,
) {
    companion object {
        private const val TAG = "FloatingBall"
    }

    private var view: View? = null
    private var icon: ImageView? = null
    private var wm: WindowManager? = null
    private val params: WindowManager.LayoutParams

    init {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 200
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (view != null) return
        wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val sizeDp = 56
        val sizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, sizeDp.toFloat(),
            ctx.resources.displayMetrics
        ).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 4f, ctx.resources.displayMetrics
            ).toInt()
            setPadding(pad, pad, pad, pad)
            background = makeBackground(0xFF10A37F.toInt())
        }
        val img = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            setImageResource(R.drawable.ic_mic)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        container.addView(img)
        icon = img
        view = container

        // 拖拽 + 点击
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    wm?.updateViewLayout(container, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = abs(event.rawX - touchX)
                    val dy = abs(event.rawY - touchY)
                    if (dx < 10 && dy < 10) onClick()
                    true
                }
                else -> false
            }
        }

        wm?.addView(container, params)
        AppLog.i(TAG, "悬浮球已显示")
    }

    fun dismiss() {
        view?.let {
            try { wm?.removeView(it) } catch (_: Throwable) {}
        }
        view = null
        icon = null
    }

    fun setState(state: VoiceAssistant.State) {
        val (bgColor, iconRes) = when (state) {
            VoiceAssistant.State.IDLE -> 0xFF6E6E6E.toInt() to R.drawable.ic_mic
            VoiceAssistant.State.LISTENING -> 0xFF10A37F.toInt() to R.drawable.ic_mic
            VoiceAssistant.State.THINKING -> 0xFFFF8C00.toInt() to R.drawable.ic_stop_gen
            VoiceAssistant.State.SPEAKING -> 0xFF0A84FF.toInt() to R.drawable.ic_mic
            VoiceAssistant.State.WAKE_WORD -> 0xFFF59E0B.toInt() to R.drawable.ic_mic
        }
        view?.post {
            icon?.setImageResource(iconRes)
            (view as? LinearLayout)?.background?.let {
                (it as? GradientDrawable)?.setColor(bgColor)
            }
        }
    }

    private fun makeBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 3f, ctx.resources.displayMetrics
            ).toInt(),
            0xFFFFFFFF.toInt()
        )
    }
}