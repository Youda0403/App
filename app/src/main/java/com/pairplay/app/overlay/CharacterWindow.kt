package com.pairplay.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.pairplay.app.engine.Pose
import com.pairplay.app.engine.WindowPaddingCalculator
import kotlin.math.abs
import kotlin.math.hypot
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

        /** 문지르는 동작이 감지됨. 끌기를 취소하고 쓰다듬기로 바꿔야 한다. */
        fun onPetStart(slot: Slot)

        /** 쓰다듬는 중. 표시를 띄우기 좋은 시점마다 불린다. */
        fun onPetTick(slot: Slot)

        fun onPetEnd(slot: Slot)
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
        private set

    private var displayWidth = 0f
    private var displayHeight = 0f
    private var anchorXRatio = 0.5f
    private var anchorYRatio = 1f
    private var paddingX = 0f
    private var paddingY = 0f

    /**
     * 마지막으로 실제 창에 반영한 위치.
     * 값이 그대로면 updateViewLayout 을 부르지 않는다. 매 프레임 불필요하게 창을
     * 옮기면 화면이 튀고 버벅인다.
     */
    private var committedX = Int.MIN_VALUE
    private var committedY = Int.MIN_VALUE

    // --- 터치 처리 상태 ---
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private var lastX = 0f
    private var lastY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragging = false
    private var longPressFired = false

    // --- 쓰다듬기 감지 ---
    private var petting = false
    private var pettingBlocked = false
    private var reversalCount = 0
    private var firstReversalMs = 0L
    private var lastMoveSign = 0
    private var lastPetTickMs = 0L
    private var maxDistFromDown = 0f

    private val longPressRunnable = Runnable {
        if (!dragging && !petting) {
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

        val padding = WindowPaddingCalculator.forCharacter(displayWidthPx, displayHeightPx)
        this.paddingX = padding.x
        this.paddingY = padding.y

        view.flippedByUser = flipped
        view.setEdgePadding(paddingX, paddingY)
        view.setCharacterBitmap(bitmap, displayWidthPx, displayHeightPx)
        committedX = Int.MIN_VALUE
        committedY = Int.MIN_VALUE
        commit()
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

    /** 위치만 기록한다. 실제 창 이동은 [commit] 에서 한 번에 한다. */
    fun setAnchor(x: Float, y: Float) {
        anchorX = x
        anchorY = y
    }

    fun setLift(lift: Float) {
        liftPx = lift
    }

    /**
     * 기록해 둔 위치를 실제 창에 반영한다.
     * 한 프레임에 한 번만 불러야 한다. 위치와 점프 높이를 따로 반영하면
     * 프레임당 창을 두 번 옮기게 되어 화면이 튄다.
     */
    fun commit() {
        val x = (anchorX - displayWidth * anchorXRatio - paddingX).roundToInt()
        val y = (anchorY - displayHeight * anchorYRatio - paddingY - liftPx).roundToInt()
        if (x == committedX && y == committedY) return

        committedX = x
        committedY = y
        params.x = x
        params.y = y
        if (added) {
            try {
                windowManager.updateViewLayout(view, params)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "창 위치를 갱신할 수 없습니다", e)
            }
        }
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

    /** 캐릭터 그림이 실제로 차지하는 크기(여백 제외). */
    val characterWidth: Float get() = displayWidth
    val characterHeight: Float get() = displayHeight

    /** 머리 꼭대기의 화면상 세로 위치. 표시 창을 올려놓을 자리를 잡는 데 쓴다. */
    val headTopY: Float
        get() = anchorY - displayHeight * anchorYRatio - liftPx

    private fun handleTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.rawX
                lastY = event.rawY
                downRawX = event.rawX
                downRawY = event.rawY
                dragging = false
                petting = false
                pettingBlocked = false
                longPressFired = false
                reversalCount = 0
                firstReversalMs = 0L
                lastMoveSign = 0
                maxDistFromDown = 0f
                v.postDelayed(longPressRunnable, longPressTimeout)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastX
                val dy = event.rawY - lastY

                updatePettingDetection(dx, event.rawX, event.rawY)

                if (petting) {
                    lastX = event.rawX
                    lastY = event.rawY
                    maybePetTick()
                    return true
                }

                if (!dragging &&
                    (abs(event.rawX - downRawX) > touchSlop || abs(event.rawY - downRawY) > touchSlop)
                ) {
                    dragging = true
                    v.removeCallbacks(longPressRunnable)
                    callbacks.onDragStart(slot)
                }
                if (dragging) {
                    callbacks.onDrag(slot, dx, dy)
                    lastX = event.rawX
                    lastY = event.rawY
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                v.removeCallbacks(longPressRunnable)
                when {
                    petting -> callbacks.onPetEnd(slot)
                    dragging -> callbacks.onDragEnd(slot)
                    !longPressFired -> {
                        v.performClick()
                        callbacks.onTap(slot)
                    }
                }
                dragging = false
                petting = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                v.removeCallbacks(longPressRunnable)
                if (petting) callbacks.onPetEnd(slot) else if (dragging) callbacks.onDragEnd(slot)
                dragging = false
                petting = false
                return true
            }
        }
        return false
    }

    /**
     * 제자리에서 좌우로 **빠르게** 여러 번 문지르면 끌기가 아니라 쓰다듬기로 본다.
     *
     * 옮기려는 동작이 쓰다듬기로 잘못 인식되지 않도록 세 겹으로 막는다.
     * 1. 손가락이 처음 자리에서 한 번이라도 크게 벗어나면, 그 뒤로는 아무리
     *    문질러도 쓰다듬기로 보지 않는다. 옮기는 중이라는 뜻이기 때문이다.
     * 2. 방향 전환은 짧은 시간 안에 몰려서 일어나야 센다. 천천히 끌다가
     *    우연히 몇 번 흔들린 것은 세지 않는다.
     * 3. 아주 작은 흔들림은 손 떨림으로 보고 무시한다.
     */
    private fun updatePettingDetection(dx: Float, rawX: Float, rawY: Float) {
        if (petting || pettingBlocked) return

        val distance = hypot(rawX - downRawX, rawY - downRawY)
        if (distance > maxDistFromDown) maxDistFromDown = distance
        if (maxDistFromDown > touchSlop * PET_MAX_TRAVEL_SLOPS) {
            // 여기까지 왔으면 옮기려는 동작이다. 이번 터치에서는 쓰다듬기를 포기한다.
            pettingBlocked = true
            return
        }

        if (abs(dx) < MIN_RUB_PX) return

        val now = SystemClock.uptimeMillis()
        val sign = if (dx > 0) 1 else -1
        if (lastMoveSign != 0 && sign != lastMoveSign) {
            if (reversalCount == 0 || now - firstReversalMs > PET_REVERSAL_WINDOW_MS) {
                reversalCount = 1
                firstReversalMs = now
            } else {
                reversalCount++
            }
        }
        lastMoveSign = sign

        if (reversalCount >= REVERSALS_FOR_PET) {
            petting = true
            dragging = false
            lastPetTickMs = 0L
            callbacks.onPetStart(slot)
            maybePetTick()
        }
    }

    private fun maybePetTick() {
        val now = SystemClock.uptimeMillis()
        if (now - lastPetTickMs < PET_TICK_INTERVAL_MS) return
        lastPetTickMs = now
        callbacks.onPetTick(slot)
    }

    companion object {
        private const val TAG = "CharacterWindow"

        /** 문지르기로 셀 최소 이동량(px). 손 떨림을 걸러낸다. */
        private const val MIN_RUB_PX = 10f

        /** 이만큼 방향이 바뀌면 쓰다듬는 것으로 본다. */
        private const val REVERSALS_FOR_PET = 3

        /** 방향 전환이 이 시간 안에 몰려야 쓰다듬기로 센다. */
        private const val PET_REVERSAL_WINDOW_MS = 650L

        /** 처음 자리에서 이 정도(터치 여유 배수)를 벗어나면 옮기는 동작으로 확정한다. */
        private const val PET_MAX_TRAVEL_SLOPS = 3f

        private const val PET_TICK_INTERVAL_MS = 260L

        fun overlayWindowType(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
    }
}
