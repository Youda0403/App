package com.pairplay.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import com.pairplay.app.engine.PoofFrame
import com.pairplay.app.engine.Pose

/**
 * 캐릭터 한 명을 그리는 뷰. 창 하나에 이 뷰 하나가 들어간다.
 *
 * 창 자체가 이미지 크기에 맞춰 작게 떠 있으므로, 이 뷰는 창 밖으로 나가는 이동을
 * 표현하지 않는다. 가로 이동과 점프는 창 위치를 옮겨서 처리한다.
 * 여기서는 숨쉬기/기울기 같은 제자리 변형만 그린다.
 */
@SuppressLint("ViewConstructor")
class CharacterView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = RectF()

    private var bitmap: Bitmap? = null

    /** 이미지를 그릴 크기(px). 창 여백을 뺀 값. */
    private var drawWidth = 0f
    private var drawHeight = 0f

    /**
     * 창 가장자리 여백(px). 기울기/확대로 그림이 잘리지 않게 둔다.
     * 가로와 세로가 필요한 양이 달라 따로 받는다.
     */
    private var edgePaddingX = 0f
    private var edgePaddingY = 0f

    var pose: Pose = Pose.NEUTRAL
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /**
     * 바라보는 방향. +1 이 오른쪽, -1 이 왼쪽.
     *
     * 불리언으로 즉시 뒤집으면 '탁' 하고 튀어 보인다. 0 을 지나가며 바뀌면
     * 몸을 돌리는 것처럼 보이므로 중간값을 받는다.
     */
    var facingFactor: Float = 1f
        set(value) {
            val clamped = value.coerceIn(-1f, 1f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    /**
     * 연기와 함께 '뿅' 사라지거나 나타나는 중인 모습. null 이면 평소대로 그린다.
     * 은행·결제 앱에 들어가고 나올 때 쓴다.
     */
    var poof: PoofFrame? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val smokePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 캐릭터가 좌우 반전 상태로 등록되었는지. facingRight 와 함께 최종 반전을 정한다. */
    var flippedByUser: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    fun setEdgePadding(x: Float, y: Float) {
        if (edgePaddingX == x && edgePaddingY == y) return
        edgePaddingX = x
        edgePaddingY = y
        requestLayout()
        invalidate()
    }

    fun setCharacterBitmap(bitmap: Bitmap?, drawWidthPx: Float, drawHeightPx: Float) {
        this.bitmap = bitmap
        this.drawWidth = drawWidthPx
        this.drawHeight = drawHeightPx
        bitmap?.let { srcRect.set(0, 0, it.width, it.height) }
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (drawWidth + edgePaddingX * 2f).toInt().coerceAtLeast(1)
        val h = (drawHeight + edgePaddingY * 2f).toInt().coerceAtLeast(1)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        if (bmp.isRecycled || drawWidth <= 0f || drawHeight <= 0f) return

        val centerX = width / 2f
        val centerY = height / 2f
        // 발밑을 회전·확대의 기준으로 삼는다. 그래야 캐릭터가 땅에 붙어 있는 느낌이 난다.
        val footY = centerY + drawHeight / 2f

        val poofFrame = poof
        if (poofFrame != null && poofFrame.characterAlpha <= 0.01f) {
            // 캐릭터는 다 사라지고 연기만 남은 순간.
            drawSmoke(canvas, poofFrame, centerX, centerY)
            return
        }

        val save = canvas.save()

        if (poofFrame != null) {
            // 뿅 하는 동안에는 몸 한가운데를 기준으로 쪼그라들거나 튀어나온다.
            canvas.scale(poofFrame.characterScale, poofFrame.characterScale, centerX, centerY)
            paint.alpha = (poofFrame.characterAlpha * 255).toInt().coerceIn(0, 255)
        }

        canvas.translate(0f, pose.offsetY)
        canvas.rotate(pose.rotationDeg, centerX, footY)

        // 완전히 0 이 되면 한 프레임 사라져 보이므로 아주 얇게 남긴다.
        val baseFlip = if (flippedByUser) -1f else 1f
        val mirror = (facingFactor * baseFlip).let {
            if (kotlin.math.abs(it) < MIN_MIRROR) MIN_MIRROR * (if (it < 0f) -1f else 1f) else it
        }
        canvas.scale(pose.scaleX * mirror, pose.scaleY, centerX, footY)

        dstRect.set(
            centerX - drawWidth / 2f,
            centerY - drawHeight / 2f,
            centerX + drawWidth / 2f,
            centerY + drawHeight / 2f
        )
        canvas.drawBitmap(bmp, srcRect, dstRect, paint)

        canvas.restoreToCount(save)
        paint.alpha = 255

        if (poofFrame != null) drawSmoke(canvas, poofFrame, centerX, centerY)
    }

    /**
     * 연기 뭉치들을 그린다. 뭉치 하나는 크고 작은 동그라미 셋을 겹쳐 뭉게뭉게 보이게 한다.
     * 창 가장자리에서 잘리면 네모난 연기가 되므로, 창 안에 들어오도록 줄여서 그린다.
     */
    private fun drawSmoke(canvas: Canvas, frame: PoofFrame, centerX: Float, centerY: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        for (puff in frame.puffs) {
            val cx = centerX + puff.dxRatio * drawHeight
            val cy = centerY + puff.dyRatio * drawHeight
            val room = minOf(cx, w - cx, cy, h - cy)
            if (room <= 1f) continue
            val r = (puff.radiusRatio * drawHeight).coerceAtMost(room)
            val alpha = (puff.alpha * 235).toInt().coerceIn(0, 255)

            smokePaint.color = SMOKE_EDGE
            smokePaint.alpha = alpha
            canvas.drawCircle(cx, cy, r, smokePaint)

            smokePaint.color = SMOKE_FILL
            smokePaint.alpha = alpha
            canvas.drawCircle(cx, cy, r * 0.86f, smokePaint)
            canvas.drawCircle(cx - r * 0.45f, cy + r * 0.2f, r * 0.55f, smokePaint)
            canvas.drawCircle(cx + r * 0.4f, cy + r * 0.25f, r * 0.5f, smokePaint)
        }
    }
}

private const val MIN_MIRROR = 0.04f

/** 연기 색. 안쪽은 하얗고 가장자리는 옅은 보라빛 회색이라 배경이 밝아도 보인다. */
private val SMOKE_FILL = Color.parseColor("#FAF8FC")
private val SMOKE_EDGE = Color.parseColor("#CFC6DA")
