package com.pairplay.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.pairplay.app.engine.EffectEmitter
import com.pairplay.app.engine.EffectKind
import com.pairplay.app.engine.RenderedEffect
import kotlin.math.roundToInt

/**
 * 캐릭터 머리 위에 하트·느낌표 같은 작은 기호를 띄우는 창.
 *
 * 말풍선은 쓰지 않는다. 말풍선은 캐릭터보다 눈에 띄는 데다, 캐릭터 크기에 맞춰
 * 그리면 두 캐릭터의 말풍선 크기까지 달라 보여 어수선했다. 기호만 띄우고
 * 크기는 두 캐릭터가 똑같이 쓰도록 바깥에서 정해 준다.
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

    /**
     * 기호 하나의 기준 크기(px).
     *
     * 창 크기에서 뽑아 쓰면 큰 캐릭터의 기호만 커진다. 두 캐릭터가 같은 크기로
     * 보이도록 바깥에서 같은 값을 넣어 준다.
     */
    fun setUnitSize(px: Float) {
        view.setUnitSize(px)
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

    /** 지금 떠 있는 기호들을 갱신한다. */
    fun setContent(effects: List<RenderedEffect>) {
        val shouldShow = effects.isNotEmpty()
        if (!shouldShow && view.visibility == View.GONE) return

        view.setContent(effects)
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

/** 떠 있는 기호들을 그리는 뷰. */
@SuppressLint("ViewConstructor")
private class EffectView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private var effects: List<RenderedEffect> = emptyList()

    /** 기호 하나의 기준 크기(px). 0 이면 창 크기에서 적당히 뽑아 쓴다. */
    private var unitSize = 0f

    fun setUnitSize(px: Float) {
        val value = px.coerceAtLeast(0f)
        if (unitSize == value) return
        unitSize = value
        invalidate()
    }

    fun setContent(effects: List<RenderedEffect>) {
        this.effects = effects
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (effects.isEmpty()) return

        // 기호 하나의 기준 크기. 두 캐릭터가 같은 크기를 쓰도록 바깥에서 받는다.
        // 창이 너무 작으면 잘리므로 창 크기 안으로 한 번 더 줄인다.
        val fallback = minOf(w, h) * FALLBACK_UNIT_RATIO
        val unit = (if (unitSize > 0f) unitSize else fallback)
            .coerceAtMost(minOf(w, h) * MAX_UNIT_RATIO)
        val maxSize = unit * EffectEmitter.MAX_RENDER_SCALE

        // 기호가 창 가장자리에서 잘리면 '투명한 선 위로 떠오르는' 것처럼 보인다.
        // 가장 큰 기호가 통째로 들어갈 만큼 안쪽으로 밀어 넣고 그 안에서만 움직인다.
        val marginTop = maxSize * TOP_EXTENT
        val marginBottom = maxSize * BOTTOM_EXTENT
        val marginX = maxSize * SIDE_EXTENT

        val bandWidth = (w - marginX * 2f).coerceAtLeast(1f)
        val bandHeight = (h - marginTop - marginBottom).coerceAtLeast(1f)

        for (effect in effects) {
            val cx = marginX + bandWidth * effect.xRatio
            val cy = marginTop + bandHeight * effect.yRatio
            val size = unit * effect.scale
            if (size <= 0.5f) continue

            paint.color = colorFor(effect.kind)
            paint.alpha = (effect.alpha.coerceIn(0f, 1f) * 255).roundToInt()

            val save = canvas.save()
            canvas.rotate(effect.rotationDeg, cx, cy)
            drawSymbol(canvas, effect.kind, cx, cy, size)
            canvas.restoreToCount(save)
        }
    }

    private fun colorFor(kind: EffectKind): Int = when (kind) {
        EffectKind.HEART -> HEART_COLOR
        EffectKind.NOTE -> NOTE_COLOR
        EffectKind.SPARKLE -> SPARKLE_COLOR
        EffectKind.EXCLAIM -> EXCLAIM_COLOR
        EffectKind.QUESTION -> INK_COLOR
        EffectKind.SLEEP -> SLEEP_COLOR
        EffectKind.SWEAT -> SWEAT_COLOR
        EffectKind.ANGER -> ANGER_COLOR
    }

    private fun drawSymbol(canvas: Canvas, kind: EffectKind, cx: Float, cy: Float, size: Float) {
        when (kind) {
            EffectKind.HEART -> drawHeart(canvas, cx, cy, size)
            EffectKind.NOTE -> drawNote(canvas, cx, cy, size)
            EffectKind.SPARKLE -> drawSparkle(canvas, cx, cy, size)
            EffectKind.EXCLAIM -> drawExclaim(canvas, cx, cy, size)
            EffectKind.QUESTION -> drawQuestion(canvas, cx, cy, size)
            EffectKind.SLEEP -> drawSleep(canvas, cx, cy, size)
            EffectKind.SWEAT -> drawSweat(canvas, cx, cy, size)
            EffectKind.ANGER -> drawAnger(canvas, cx, cy, size)
        }
    }

    // ---------------------------------------------------------------- 기호 그리기

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

    private fun drawQuestion(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val stroke = size * 0.15f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        path.reset()
        path.moveTo(cx - size * 0.22f, cy - size * 0.28f)
        path.quadTo(cx + size * 0.34f, cy - size * 0.55f, cx + size * 0.08f, cy - size * 0.02f)
        path.quadTo(cx - size * 0.02f, cy + size * 0.08f, cx, cy + size * 0.16f)
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy + size * 0.38f, stroke * 0.62f, paint)
    }

    /** 졸음: 크기가 다른 z 두 개. */
    private fun drawSleep(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        drawZ(canvas, cx - size * 0.16f, cy + size * 0.18f, size * 0.42f)
        drawZ(canvas, cx + size * 0.22f, cy - size * 0.2f, size * 0.3f)
    }

    private fun drawZ(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val half = size / 2f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.22f
        path.reset()
        path.moveTo(cx - half, cy - half)
        path.lineTo(cx + half, cy - half)
        path.lineTo(cx - half, cy + half)
        path.lineTo(cx + half, cy + half)
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }

    /** 머쓱함: 물방울 하나. 캐릭터 그림 위에 붙어 흔들린다. */
    private fun drawSweat(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        path.reset()
        path.moveTo(cx, cy - size * 0.45f)
        path.quadTo(cx + size * 0.38f, cy + size * 0.1f, cx, cy + size * 0.42f)
        path.quadTo(cx - size * 0.38f, cy + size * 0.1f, cx, cy - size * 0.45f)
        path.close()
        canvas.drawPath(path, paint)
    }

    /** 못마땅함: 핏대 모양의 네 갈래. */
    private fun drawAnger(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        val arm = size * 0.42f
        val thickness = size * 0.13f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = thickness
        canvas.drawLine(cx - arm, cy - arm, cx + arm * 0.1f, cy - arm * 0.1f, paint)
        canvas.drawLine(cx + arm, cy - arm, cx - arm * 0.1f, cy - arm * 0.1f, paint)
        canvas.drawLine(cx - arm, cy + arm, cx + arm * 0.1f, cy + arm * 0.1f, paint)
        canvas.drawLine(cx + arm, cy + arm, cx - arm * 0.1f, cy + arm * 0.1f, paint)
        paint.style = Paint.Style.FILL
    }
}

/** 바깥에서 크기를 주지 않았을 때 쓰는 값. */
private const val FALLBACK_UNIT_RATIO = 0.17f

/** 창에 견줘 이보다 큰 기호는 잘리므로 여기서 멈춘다. */
private const val MAX_UNIT_RATIO = 0.3f

/** 표시가 중심에서 위로 뻗는 최대 비율 (하트가 가장 크다). */
private const val TOP_EXTENT = 0.95f

/** 아래로 뻗는 최대 비율. */
private const val BOTTOM_EXTENT = 0.6f

private const val SIDE_EXTENT = 0.6f

private val HEART_COLOR = Color.parseColor("#FF6B8A")
private val NOTE_COLOR = Color.parseColor("#7B5EA7")
private val SPARKLE_COLOR = Color.parseColor("#F5A623")
private val EXCLAIM_COLOR = Color.parseColor("#FF8A3D")
private val INK_COLOR = Color.parseColor("#4A3B63")
private val SLEEP_COLOR = Color.parseColor("#6E86C4")
private val SWEAT_COLOR = Color.parseColor("#59B6D8")
private val ANGER_COLOR = Color.parseColor("#E0503C")
