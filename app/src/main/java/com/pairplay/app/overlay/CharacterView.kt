package com.pairplay.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
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

    /** 창 가장자리 여백(px). 기울기/확대로 이미지가 잘리지 않게 둔다. */
    var edgePadding = 0f
        set(value) {
            field = value
            invalidate()
        }

    var pose: Pose = Pose.NEUTRAL
        set(value) {
            field = value
            invalidate()
        }

    var facingRight: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 캐릭터가 좌우 반전 상태로 등록되었는지. facingRight 와 함께 최종 반전을 정한다. */
    var flippedByUser: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
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
        val w = (drawWidth + edgePadding * 2f).toInt().coerceAtLeast(1)
        val h = (drawHeight + edgePadding * 2f).toInt().coerceAtLeast(1)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        if (bmp.isRecycled || drawWidth <= 0f || drawHeight <= 0f) return

        val centerX = width / 2f
        val centerY = height / 2f

        val save = canvas.save()

        canvas.translate(0f, pose.offsetY)
        canvas.rotate(pose.rotationDeg, centerX, centerY + drawHeight / 2f)

        // 아래쪽(발끝)을 고정한 채 늘어나도록 세로 기준점을 바닥에 둔다.
        val mirror = if (facingRight != flippedByUser) 1f else -1f
        canvas.scale(pose.scaleX * mirror, pose.scaleY, centerX, centerY + drawHeight / 2f)

        dstRect.set(
            centerX - drawWidth / 2f,
            centerY - drawHeight / 2f,
            centerX + drawWidth / 2f,
            centerY + drawHeight / 2f
        )
        canvas.drawBitmap(bmp, srcRect, dstRect, paint)

        canvas.restoreToCount(save)
    }
}
