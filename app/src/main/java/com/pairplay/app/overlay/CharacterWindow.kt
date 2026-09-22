package com.pairplay.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.pairplay.app.engine.Pose
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 캐릭터 한 명을 담는 오버레이 창.
 *
 * 문서의 설계 결정을 그대로 따른다. 전체 화면 투명 창을 띄우고 터치를 통과시키는 대신
 * **캐릭터 이미지 크기에 맞춘 작은 창을 캐릭터마다 하나씩** 띄운다.
 * Android 12 부터는 불투명한 창을 통과하는 터치가 차단되기 때문에,
 * 작은 창만 띄워야 창 밖 영역에서 원래 앱을 그대로 쓸 수 있다.
 */
class CharacterWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    val slot: Slot,
    private val callbacks: Callbacks
) {

    enum class Slot { A, B }

    interface Callbacks {
        fun onTap(slot: Slot)
        fun onLongPress(slot: Slot)
        fun onDragStart(slot: Slot)
        fun onDrag(slot: Slot, deltaX: Float, deltaY: Float)
        fun onDragEnd(slot: Slot)
    }

    val view = CharacterView(context)

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        overlayWindowType(),
        // NOT_FOCUSABLE: 키보드 포커스와 뒤로 가기를 가져가지 않는다.
        // LAYOUT_NO_LIMITS: 화면 가장자리에 붙어도 잘리지 않는다.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    private var added = false

    /** 화면상 기준점(발밑) 위치. */
    var anchorX = 0f
        private set
    var anchorY = 0f
        private set

    /** 점프 등으로 잠깐 띄우는 높이(px). 창 위치에 더해진다. */
    var liftPx = 0f
        set(value) {
            field = value
            applyPosition()
        }

    private var displayWidth = 0f
    private var displayHeight = 0f
    private var anchorXRatio = 0.5f
    private var anchorYRatio = 1f
    private var padding = 0f

    // --- 터치 처리 상태 ---
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var longPressFired = false

    private val longPressRunnable = Runnable {
        if (!dragging) {
            longPressFired = true
            callbacks.onLongPress(slot)
        }
    }

    init {
        view.setOnTouchListener { v, event -> handleTouch(v, event) }
    }

    fun setCharacter(
        bitmap: Bitmap?,
        displayWidthPx: Float,
        displayHeightPx: Float,
        anchorXRatio: Float,
        anchorYRatio: Float,
        flipped: Boolean
    ) {
        this.displayWidth = displayWidthPx
        this.displayHeight = displayHeightPx
        this.anchorXRatio = anchorXRatio.coerceIn(0f, 1f)
        this.anchorYRatio = anchorYRatio.coerceIn(0f, 1f)
        // 기울기와 확대로 이미지가 창 밖으로 잘리지 않을 만큼만 여백을 둔다.
        // 여백은 터치를 가로채는 영역이므로 최소한으로 잡는다.
        this.padding = (maxOf(displayWidthPx, displayHeightPx) * EDGE_PADDING_RATIO)
        view.flippedByUser = flipped
        view.edgePadding = padding
        view.setCharacterBitmap(bitmap, displayWidthPx, displayHeightPx)
        applyPosition()
    }

    fun setPose(pose: Pose) {
        view.pose = pose
    }

    fun setFacingRight(facingRight: Boolean) {
        view.facingRight = facingRight
    }

    /** 창이 아니라 뷰에 투명도를 적용한다. 창 불투명도를 건드리면 터치 규칙과 얽힌다. */
    fun setOpacity(opacity: Float) {
        view.alpha = opacity.coerceIn(0.05f, 1f)
    }

    fun setAnchor(x: Float, y: Float) {
        anchorX = x
        anchorY = y
        applyPosition()
    }

    fun attach() {
        if (added) return
        try {
            windowManager.addView(view, params)
            added = true
        } catch (e: WindowManager.BadTokenException) {
            // 오버레이 권한이 없거나 취소된 경우.
            Log.w(TAG, "오버레이 창을 추가할 수 없음 (권한 확인 필요)", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "오버레이 창이 이미 추가되어 있음", e)
            added = true
        }
    }

    fun detach() {
        if (!added) return
        view.removeCallbacks(longPressRunnable)
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "이미 제거된 창", e)
        }
        added = false
    }

    fun setVisible(visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }

    val isAttached: Boolean get() = added

    /** 창이 차지하는 전체 크기(여백 포함). 화면 밖으로 나가지 않게 하는 데 쓴다. */
    val windowWidth: Float get() = displayWidth + padding * 2f
    val windowHeight: Float get() = displayHeight + padding * 2f

    private fun applyPosition() {
        params.x = (anchorX - displayWidth * anchorXRatio - padding).roundToInt()
        params.y = (anchorY - displayHeight * anchorYRatio - padding - liftPx).roundToInt()
        if (added) {
            try {
                windowManager.updateViewLayout(view, params)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "창 위치를 갱신할 수 없음", e)
            }
        }
    }

    private fun handleTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                dragging = false
                longPressFired = false
                v.postDelayed(longPressRunnable, longPressTimeout)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                    v.removeCallbacks(longPressRunnable)
                    callbacks.onDragStart(slot)
                }
                if (dragging) {
                    callbacks.onDrag(slot, dx, dy)
                    downX = event.rawX
                    downY = event.rawY
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                v.removeCallbacks(longPressRunnable)
                if (dragging) {
                    callbacks.onDragEnd(slot)
                } else if (!longPressFired) {
                    v.performClick()
                    callbacks.onTap(slot)
                }
                dragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                v.removeCallbacks(longPressRunnable)
                if (dragging) callbacks.onDragEnd(slot)
                dragging = false
                return true
            }
        }
        return false
    }

    companion object {
        private const val TAG = "CharacterWindow"

        /** 창 여백 비율. 회전과 확대로 잘리지 않을 최소한. */
        const val EDGE_PADDING_RATIO = 0.08f

        private fun overlayWindowType(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
    }
}
