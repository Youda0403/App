package com.pairplay.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Path
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.pairplay.app.engine.EffectKind
import com.pairplay.app.engine.RenderedEffect
import kotlin.math.roundToInt

/**
 * 캐릭터 머리 위에 하트·음표 같은 표시를 띄우는 창.
 *
 * 캐릭터 창과 따로 두는 이유가 있다. 표시가 들어갈 자리를 캐릭터 창에 여백으로
 * 확보하면, 그 여백만큼 다른 앱의 터치를 가로채게 된다. 그래서 표시는
 * **터치를 받지 않는 별도 창**에 그린다.
 *
 * 다만 터치를 받지 않는 창이라도 Android 12 부터는 창의 불투명도가 기준(0.8)을
 * 넘으면 그 창을 통과하는 터치가 차단된다. 그래서 창 불투명도를 기준 아래로 고정한다.
 */
class EffectWindow(
    private val context: Context,
    private val windowManager: WindowManager
) {

    private val view = EffectView(context)

    private val params = WindowManager.LayoutParams(
        1,
        1,
        CharacterWindow.overlayWindowType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // 이 값이 0.8 을 넘으면 창 아래 앱이 터치를 받지 못한다. 절대 올리지 말 것.
        alpha = PASS_THROUGH_ALPHA
    }

    private var added = false
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE

    fun setSize(widthPx: Int, heightPx: Int) {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        if (params.width == w && params.height == h) return
        params.width = w
        params.height = h
        if (added) applyLayout()
    }

    /** 캐릭터 머리 위 중앙에 오도록 좌상단 위치를 정한다. */
    fun setPosition(x: Float, y: Float) {
        val nx = x.roundToInt()
        val ny = y.roundToInt()
        if (nx == lastX && ny == lastY) return
        lastX = nx
        lastY = ny
        params.x = nx
        params.y = ny
        if (added) applyLayout()
    }

    fun setEffects(effects: List<RenderedEffect>) {
        val shouldShow = effects.isNotEmpty()
        if (!shouldShow && view.visibility == View.GONE) return
        view.effects = effects
        view.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    fun attach() {
        if (added) return
        try {
            view.visibility = View.GONE
            windowManager.addView(view, params)
            added = true
        } catch (e: WindowManager.BadTokenException) {
            Log.w(TAG, "표시 창을 추가할 수 없습니다", e)
        } catch (e: IllegalStateException) {
            added = true
        }
    }

    fun detach() {
        if (!added) return
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "이미 제거된 표시 창", e)
        }
        added = false
    }

    private fun applyLayout() {
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "표시 창을 갱신할 수 없습니다", e)
        }
    }

    companion object {
        private const val TAG = "EffectWindow"

        /** Android 12 의 터치 통과 기준(0.8) 아래로 둔다. */
        const val PASS_THROUGH_ALPHA = 0.8f
    }
}

/** 하트·음표·반짝임을 그리는 뷰. */
@SuppressLint("ViewConstructor")
private class EffectView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    var effects: List<RenderedEffect> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        if (effects.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        // 표시 하나의 기준 크기. 창 너비에 비례시켜 캐릭터 크기를 따라가게 한다.
        val unit = w * 0.22f

        for (effect in effects) {
            val cx = w * effect.xRatio
            val cy = h * effect.yRatio
            val size = unit * effect.scale
            if (size <= 0.5f) continue

            paint.color = colorFor(effect.kind)
            paint.alpha = (effect.alpha.coerceIn(0f, 1f) * 255).roundToInt()

            val save = canvas.save()
            canvas.rotate(effect.rotationDeg, cx, cy)
            when (effect.kind) {
                EffectKind.HEART -> drawHeart(canvas, cx, cy, size)
                EffectKind.NOTE -> drawNote(canvas, cx, cy, size)
                EffectKind.SPARKLE -> drawSparkle(canvas, cx, cy, size)
                EffectKind.EXCLAIM -> drawExclaim(canvas, cx, cy, size)
            }
            canvas.restoreToCount(save)
        }
    }

    private fun colorFor(kind: EffectKind): Int = when (kind) {
        EffectKind.HEART -> Color.parseColor("#FF6B8A")
        EffectKind.NOTE -> Color.parseColor("#7B5EA7")
        EffectKind.SPARKLE -> Color.parseColor("#FFC94D")
        EffectKind.EXCLAIM -> Color.parseColor("#FF8A3D")
    }

    private fun drawHeart(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val half = size / 2f
        path.reset()
        path.moveTo(cx, cy + half)
        path.cubicTo(
            cx - size * 0.95f, cy - half * 0.1f,
            cx - size * 0.35f, cy - size * 0.85f,
            cx, cy - half * 0.28f
        )
        path.cubicTo(
            cx + size * 0.35f, cy - size * 0.85f,
            cx + size * 0.95f, cy - half * 0.1f,
            cx, cy + half
        )
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawNote(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val headR = size * 0.26f
        val stemX = cx + headR * 0.85f
        canvas.drawOval(
            cx - headR, cy + size * 0.1f - headR * 0.8f,
            cx + headR, cy + size * 0.1f + headR * 0.8f,
            paint
        )
        canvas.drawRect(
            stemX - size * 0.06f, cy - size * 0.45f,
            stemX + size * 0.06f, cy + size * 0.12f,
            paint
        )
        canvas.drawRect(
            stemX - size * 0.06f, cy - size * 0.45f,
            stemX + size * 0.3f, cy - size * 0.28f,
            paint
        )
    }

    private fun drawSparkle(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val arm = size * 0.5f
        val waist = size * 0.12f
        path.reset()
        path.moveTo(cx, cy - arm)
        path.quadTo(cx + waist, cy - waist, cx + arm, cy)
        path.quadTo(cx + waist, cy + waist, cx, cy + arm)
        path.quadTo(cx - waist, cy + waist, cx - arm, cy)
        path.quadTo(cx - waist, cy - waist, cx, cy - arm)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawExclaim(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val w = size * 0.16f
        canvas.drawRoundRect(
            cx - w, cy - size * 0.45f,
            cx + w, cy + size * 0.1f,
            w, w, paint
        )
        canvas.drawCircle(cx, cy + size * 0.35f, w * 1.1f, paint)
    }
}
