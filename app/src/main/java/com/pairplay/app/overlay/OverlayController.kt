package com.pairplay.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import android.view.Choreographer
import android.view.WindowManager
import com.pairplay.app.data.CharacterEntity
import com.pairplay.app.data.OverlayMode
import com.pairplay.app.data.OverlaySettings
import com.pairplay.app.engine.CharacterAction
import com.pairplay.app.engine.Pose
import com.pairplay.app.engine.PoseCalculator
import java.io.File
import kotlin.random.Random

/**
 * 오버레이에 떠 있는 캐릭터들을 한 곳에서 조정한다.
 * 창 두 개의 위치, 동작 전환, 드래그, 화면 경계, 음악 반응이 모두 여기를 거친다.
 */
class OverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onHideRequested: () -> Unit,
    private val onPositionPersist: (slot: CharacterWindow.Slot, x: Int, y: Int) -> Unit
) : CharacterWindow.Callbacks {

    private class Runtime(
        val entity: CharacterEntity,
        val window: CharacterWindow,
        val bitmap: Bitmap?
    ) {
        var action: CharacterAction = CharacterAction.IDLE
        var actionStart: Long = 0L
        var actionDuration: Long = CharacterAction.IDLE.defaultDurationMs
        var facingRight: Boolean = true
        var walkFromX: Float = 0f
        var walkToX: Float = 0f
        val seed: Float = Random.nextFloat()
        var displayHeight: Float = 0f
        var displayWidth: Float = 0f
    }

    private val choreographer = Choreographer.getInstance()
    private var running = false

    private var runtimeA: Runtime? = null
    private var runtimeB: Runtime? = null

    private var settings = OverlaySettings()
    private var musicPlaying = false

    /** '일정 시간 숨김'이 끝났는지 주기적으로 확인하기 위한 시각. */
    private var lastVisibilityCheck = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            tick(System.currentTimeMillis())
            choreographer.postFrameCallback(this)
        }
    }

    // ---------------------------------------------------------------- 구성

    /**
     * 표시할 캐릭터를 갈아끼운다. [a] 만 주면 한 명만 띄운다.
     * 설정을 바꿀 때마다 오버레이를 껐다 켤 필요가 없도록 이 메서드로 즉시 반영한다.
     */
    fun setCharacters(a: CharacterEntity?, b: CharacterEntity?) {
        val keepA = runtimeA?.entity?.id == a?.id && a != null
        val keepB = runtimeB?.entity?.id == b?.id && b != null

        if (!keepA) {
            runtimeA?.let { teardown(it) }
            runtimeA = a?.let { build(it, CharacterWindow.Slot.A) }
        }
        if (!keepB) {
            runtimeB?.let { teardown(it) }
            runtimeB = b?.let { build(it, CharacterWindow.Slot.B) }
        }
        applySettingsToWindows()
        placeInitialPositions()
    }

    fun updateSettings(newSettings: OverlaySettings) {
        val previous = settings
        settings = newSettings
        applySettingsToWindows()
        if (previous.scalePercent != newSettings.scalePercent) {
            // 크기가 바뀌면 이미지 크기도 다시 계산해야 한다.
            listOfNotNull(runtimeA, runtimeB).forEach { applyBitmapSize(it) }
        }
        updateVisibility()
    }

    fun setMusicPlaying(playing: Boolean) {
        if (musicPlaying == playing) return
        musicPlaying = playing
        if (!settings.musicReactionEnabled) return
        listOfNotNull(runtimeA, runtimeB).forEach { runtime ->
            startAction(
                runtime,
                if (playing) CharacterAction.RHYTHM else CharacterAction.IDLE
            )
        }
    }

    fun start() {
        if (running) return
        running = true
        choreographer.postFrameCallback(frameCallback)
    }

    fun stop() {
        running = false
        choreographer.removeFrameCallback(frameCallback)
    }

    fun release() {
        stop()
        runtimeA?.let { teardown(it) }
        runtimeB?.let { teardown(it) }
        runtimeA = null
        runtimeB = null
    }

    // ---------------------------------------------------------------- 내부 구성

    private fun build(entity: CharacterEntity, slot: CharacterWindow.Slot): Runtime? {
        val bitmap = loadBitmap(entity.imagePath)
        if (bitmap == null) {
            Log.w(TAG, "캐릭터 이미지를 불러오지 못했습니다: ${entity.name}")
            return null
        }
        val window = CharacterWindow(context, windowManager, slot, this)
        val runtime = Runtime(entity, window, bitmap)
        applyBitmapSize(runtime)
        window.attach()
        startAction(runtime, CharacterAction.IDLE)
        return runtime
    }

    private fun teardown(runtime: Runtime) {
        runtime.window.detach()
        runtime.bitmap?.takeIf { !it.isRecycled }?.recycle()
    }

    private fun loadBitmap(path: String): Bitmap? = try {
        val file = File(path)
        if (!file.exists()) {
            null
        } else {
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "캐릭터 이미지를 담을 메모리가 부족합니다", e)
        null
    }

    private fun applyBitmapSize(runtime: Runtime) {
        val bitmap = runtime.bitmap ?: return
        val density = context.resources.displayMetrics.density
        val heightPx = runtime.entity.displayHeightDp * density * settings.scale
        val ratio = if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height else 1f
        val widthPx = heightPx * ratio

        runtime.displayHeight = heightPx
        runtime.displayWidth = widthPx

        runtime.window.setCharacter(
            bitmap = bitmap,
            displayWidthPx = widthPx,
            displayHeightPx = heightPx,
            anchorXRatio = runtime.entity.anchorXRatio,
            anchorYRatio = runtime.entity.anchorYRatio,
            flipped = runtime.entity.flipHorizontal
        )
    }

    private fun applySettingsToWindows() {
        listOfNotNull(runtimeA, runtimeB).forEach { runtime ->
            runtime.window.setOpacity(settings.opacity)
        }
        runtimeB?.window?.setVisible(settings.mode == OverlayMode.PAIR)
    }

    private fun updateVisibility() {
        val hidden = settings.isHiddenAt(System.currentTimeMillis())
        runtimeA?.window?.setVisible(!hidden)
        runtimeB?.window?.setVisible(!hidden && settings.mode == OverlayMode.PAIR)
    }

    private fun placeInitialPositions() {
        val bounds = screenSize()
        val floorY = bounds.second * FLOOR_RATIO

        runtimeA?.let { runtime ->
            val x = if (settings.positionAX != OverlaySettings.UNSET_POSITION) {
                settings.positionAX.toFloat()
            } else {
                bounds.first * 0.32f
            }
            val y = if (settings.positionAY != OverlaySettings.UNSET_POSITION) {
                settings.positionAY.toFloat()
            } else {
                floorY
            }
            runtime.window.setAnchor(clampX(x, runtime), clampY(y))
        }

        runtimeB?.let { runtime ->
            val x = if (settings.positionBX != OverlaySettings.UNSET_POSITION) {
                settings.positionBX.toFloat()
            } else {
                bounds.first * 0.62f
            }
            val y = if (settings.positionBY != OverlaySettings.UNSET_POSITION) {
                settings.positionBY.toFloat()
            } else {
                floorY
            }
            runtime.window.setAnchor(clampX(x, runtime), clampY(y))
        }
    }

    // ---------------------------------------------------------------- 애니메이션 루프

    private fun tick(now: Long) {
        // 숨김 시간이 지났는지 매 프레임 확인할 필요는 없다.
        if (now - lastVisibilityCheck > VISIBILITY_CHECK_INTERVAL_MS) {
            lastVisibilityCheck = now
            updateVisibility()
        }

        listOfNotNull(runtimeA, runtimeB).forEach { runtime ->
            val elapsed = now - runtime.actionStart
            val progress = if (runtime.actionDuration <= 0L) {
                1f
            } else {
                (elapsed.toFloat() / runtime.actionDuration).coerceIn(0f, 1f)
            }

            if (runtime.action.moves) {
                val eased = easeInOut(progress)
                val x = runtime.walkFromX + (runtime.walkToX - runtime.walkFromX) * eased
                runtime.window.setAnchor(clampX(x, runtime), runtime.window.anchorY)
            }

            runtime.window.liftPx = if (runtime.action == CharacterAction.JUMP) {
                PoseCalculator.jumpHeight(progress, runtime.displayHeight)
            } else {
                0f
            }

            runtime.window.setPose(
                PoseCalculator.pose(
                    action = runtime.action,
                    progress = progress,
                    heightPx = runtime.displayHeight,
                    seed = runtime.seed
                )
            )
            runtime.window.setFacingRight(runtime.facingRight)

            if (progress >= 1f) {
                startAction(runtime, chooseNextAction(runtime))
            }
        }
    }

    private fun startAction(runtime: Runtime, action: CharacterAction) {
        runtime.action = action
        runtime.actionStart = System.currentTimeMillis()
        runtime.actionDuration = action.defaultDurationMs
        runtime.window.setPose(Pose.NEUTRAL)

        when (action) {
            CharacterAction.WALK -> {
                val bounds = screenSize()
                val distance = bounds.first * WALK_DISTANCE_RATIO
                val direction = if (Random.nextBoolean()) 1f else -1f
                runtime.walkFromX = runtime.window.anchorX
                runtime.walkToX = clampX(runtime.window.anchorX + distance * direction, runtime)
                runtime.facingRight = runtime.walkToX >= runtime.walkFromX
            }

            CharacterAction.APPROACH -> {
                val other = otherOf(runtime)
                runtime.walkFromX = runtime.window.anchorX
                runtime.walkToX = if (other != null) {
                    val gap = (runtime.displayWidth + other.displayWidth) * 0.55f
                    val target = if (other.window.anchorX >= runtime.window.anchorX) {
                        other.window.anchorX - gap
                    } else {
                        other.window.anchorX + gap
                    }
                    clampX(target, runtime)
                } else {
                    runtime.window.anchorX
                }
                runtime.facingRight = runtime.walkToX >= runtime.walkFromX
            }

            CharacterAction.LOOK_AT -> {
                val other = otherOf(runtime)
                if (other != null) {
                    runtime.facingRight = other.window.anchorX >= runtime.window.anchorX
                }
            }

            else -> Unit
        }
    }

    /**
     * 다음 동작을 고른다. 1차에서는 성격 수치로 가중치만 조정하는 단순한 방식이다.
     * 2차에서 장면 스케줄러가 이 자리를 대신한다.
     */
    private fun chooseNextAction(runtime: Runtime): CharacterAction {
        if (musicPlaying && settings.musicReactionEnabled) {
            return if (Random.nextFloat() < 0.75f) CharacterAction.RHYTHM else CharacterAction.JUMP
        }

        val traits = runtime.entity
        val hasPartner = otherOf(runtime) != null && settings.mode == OverlayMode.PAIR

        val candidates = buildList {
            add(CharacterAction.IDLE to 30)
            add(CharacterAction.BREATHE to 25)
            add(CharacterAction.WALK to 10 + traits.traitEnergy / 5)
            add(CharacterAction.JUMP to 4 + traits.traitMischief / 8)
            add(CharacterAction.DOZE to 6 + (100 - traits.traitEnergy) / 8)
            if (hasPartner) {
                add(CharacterAction.LOOK_AT to 8 + traits.traitWarmth / 8)
                add(CharacterAction.APPROACH to 4 + traits.traitAssertiveness / 10)
            }
        }

        val total = candidates.sumOf { it.second }
        if (total <= 0) return CharacterAction.IDLE
        var roll = Random.nextInt(total)
        for ((action, weight) in candidates) {
            roll -= weight
            if (roll < 0) return action
        }
        return CharacterAction.IDLE
    }

    private fun otherOf(runtime: Runtime): Runtime? =
        if (runtime === runtimeA) runtimeB else runtimeA

    // ---------------------------------------------------------------- 터치

    override fun onTap(slot: CharacterWindow.Slot) {
        val runtime = runtimeOf(slot) ?: return
        startAction(runtime, CharacterAction.SURPRISED)
        // 짝이 있으면 놀란 쪽을 바라본다.
        otherOf(runtime)?.let { other ->
            other.facingRight = runtime.window.anchorX >= other.window.anchorX
            startAction(other, CharacterAction.LOOK_AT)
        }
    }

    override fun onLongPress(slot: CharacterWindow.Slot) {
        // 문서 요구사항: 민감한 화면을 자동으로 피할 수 없으므로
        // 사용자가 즉시 비킬 수 있는 수단(길게 누르기)을 제공한다.
        onHideRequested()
    }

    override fun onDragStart(slot: CharacterWindow.Slot) {
        runtimeOf(slot)?.let { startAction(it, CharacterAction.IDLE) }
    }

    override fun onDrag(slot: CharacterWindow.Slot, deltaX: Float, deltaY: Float) {
        val dragged = runtimeOf(slot) ?: return
        moveBy(dragged, deltaX, deltaY)
        if (settings.linkedDrag) {
            otherOf(dragged)?.let { moveBy(it, deltaX, deltaY) }
        }
    }

    override fun onDragEnd(slot: CharacterWindow.Slot) {
        runtimeA?.let {
            onPositionPersist(
                CharacterWindow.Slot.A,
                it.window.anchorX.toInt(),
                it.window.anchorY.toInt()
            )
        }
        runtimeB?.let {
            onPositionPersist(
                CharacterWindow.Slot.B,
                it.window.anchorX.toInt(),
                it.window.anchorY.toInt()
            )
        }
    }

    private fun moveBy(runtime: Runtime, deltaX: Float, deltaY: Float) {
        runtime.window.setAnchor(
            clampX(runtime.window.anchorX + deltaX, runtime),
            clampY(runtime.window.anchorY + deltaY)
        )
    }

    private fun runtimeOf(slot: CharacterWindow.Slot): Runtime? = when (slot) {
        CharacterWindow.Slot.A -> runtimeA
        CharacterWindow.Slot.B -> runtimeB
    }

    // ---------------------------------------------------------------- 화면 경계

    private fun screenSize(): Pair<Float, Float> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width().toFloat() to bounds.height().toFloat()
        } else {
            val metrics = context.resources.displayMetrics
            metrics.widthPixels.toFloat() to metrics.heightPixels.toFloat()
        }
    }

    /** 캐릭터가 화면 밖으로 나가지 않게 기준점을 가둔다. */
    private fun clampX(x: Float, runtime: Runtime): Float {
        val (screenWidth, _) = screenSize()
        val anchorRatio = runtime.entity.anchorXRatio.coerceIn(0f, 1f)
        val left = runtime.displayWidth * anchorRatio
        val right = runtime.displayWidth * (1f - anchorRatio)
        val min = left
        val max = screenWidth - right
        return if (min > max) screenWidth / 2f else x.coerceIn(min, max)
    }

    private fun clampY(y: Float): Float {
        val (_, screenHeight) = screenSize()
        return y.coerceIn(screenHeight * MIN_Y_RATIO, screenHeight * MAX_Y_RATIO)
    }

    private fun easeInOut(t: Float): Float =
        if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f) * (-2f * t + 2f) / 2f

    companion object {
        private const val TAG = "OverlayController"

        /** 처음 띄울 때 캐릭터가 설 높이(화면 세로 대비). */
        private const val FLOOR_RATIO = 0.78f

        private const val WALK_DISTANCE_RATIO = 0.18f
        private const val VISIBILITY_CHECK_INTERVAL_MS = 500L
        private const val MIN_Y_RATIO = 0.08f
        private const val MAX_Y_RATIO = 0.95f
    }
}
