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
import com.pairplay.app.data.PairEntity
import com.pairplay.app.data.RelationshipDirection
import com.pairplay.app.data.RelationshipType
import com.pairplay.app.data.SceneTrigger
import com.pairplay.app.engine.CharacterAction
import com.pairplay.app.engine.EffectEmitter
import com.pairplay.app.engine.EffectKind
import com.pairplay.app.engine.Pose
import com.pairplay.app.engine.CharacterTraits
import com.pairplay.app.engine.Performer
import com.pairplay.app.engine.PoseCalculator
import com.pairplay.app.engine.RelationshipContext
import com.pairplay.app.engine.SceneDirector
import java.io.File
import kotlin.math.abs
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
        val effectWindow: EffectWindow,
        val bitmap: Bitmap?
    ) {
        var action: CharacterAction = CharacterAction.IDLE
        var actionStart: Long = 0L
        var actionDuration: Long = CharacterAction.IDLE.defaultDurationMs
        var facingRight: Boolean = true
        var walkFromX: Float = 0f
        var walkToX: Float = 0f

        /** 이번 이동이 화면 가장자리에 막혀서 끝나는지. 끝나면 부딪히는 연출을 한다. */
        var willHitWall: Boolean = false

        val seed: Float = Random.nextFloat()
        var displayHeight: Float = 0f
        var displayWidth: Float = 0f

        /**
         * 손가락이 붙잡고 있는 중(끌기/쓰다듬기).
         * 이때는 스스로 걸어다니거나 동작을 바꾸지 않는다. 그러지 않으면
         * 손가락과 자동 이동이 서로 잡아당겨 화면이 튄다.
         */
        var interactionHeld: Boolean = false

        var heldAnchorX: Float = 0f
        var heldAnchorY: Float = 0f

        /** 동작이 바뀔 때 뚝 끊기지 않도록 직전 자세에서 부드럽게 넘어간다. */
        var blendFrom: Pose = Pose.NEUTRAL
        var blendStart: Long = 0L
        var lastPose: Pose = Pose.NEUTRAL

        val effects = EffectEmitter()
    }

    /** 상황극 스케줄러. 누가 언제 무엇을 할지는 전부 여기가 정한다. */
    private val director = SceneDirector()

    private val choreographer = Choreographer.getInstance()
    private var running = false

    private var runtimeA: Runtime? = null
    private var runtimeB: Runtime? = null

    private var settings = OverlaySettings()
    private var musicPlaying = false

    /**
     * 마지막으로 스케줄러에 넘긴 관계 정보.
     * 설정을 조금 건드릴 때마다 장면이 끊기고 쿨다운이 풀리면 곤란하므로,
     * 실제로 달라졌을 때만 다시 넘긴다.
     */
    private var lastContext: RelationshipContext? = null

    /** '일정 시간 숨김'이 끝났는지 주기적으로 확인하기 위한 시각. */
    private var lastVisibilityCheck = 0L

    // 화면 크기는 매 프레임 물어볼 필요가 없다. 주기적으로만 갱신한다.
    private var screenWidth = 0f
    private var screenHeight = 0f

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            tick(System.currentTimeMillis())
            choreographer.postFrameCallback(this)
        }
    }

    init {
        refreshScreenSize()
    }

    // ---------------------------------------------------------------- 구성

    /**
     * 표시할 캐릭터를 갈아끼운다. [a] 만 주면 한 명만 띄운다.
     * 설정을 바꿀 때마다 오버레이를 껐다 켤 필요가 없도록 이 메서드로 즉시 반영한다.
     */
    fun setCharacters(a: CharacterEntity?, b: CharacterEntity?, pair: PairEntity?) {
        // id 가 아니라 내용 전체를 비교한다. 이름/크기/기준점/반전을 고치면
        // 오버레이를 껐다 켜지 않아도 바로 다시 그려져야 하기 때문이다.
        val keepA = a != null && runtimeA?.entity == a
        val keepB = b != null && runtimeB?.entity == b

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
        configureDirector(a, b, pair)
    }

    /**
     * 관계와 성격을 스케줄러에 넘긴다.
     * 관계는 사용자가 정한 대로만 쓰고, 앱이 스스로 바꾸지 않는다.
     */
    private fun configureDirector(a: CharacterEntity?, b: CharacterEntity?, pair: PairEntity?) {
        if (a == null) return
        val partner = b.takeIf { settings.mode == OverlayMode.PAIR }
        val context = RelationshipContext(
            type = RelationshipType.fromName(pair?.relationship),
            direction = RelationshipDirection.fromName(pair?.direction),
            a = a.toTraits(),
            b = partner?.toTraits()
        )
        if (context == lastContext) return

        lastContext = context
        director.configure(context)
        director.reset()
    }

    private fun CharacterEntity.toTraits(): CharacterTraits = CharacterTraits(
        warmth = traitWarmth,
        shyness = traitShyness,
        energy = traitEnergy,
        mischief = traitMischief,
        assertiveness = traitAssertiveness,
        blockedActions = blockedActions.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    )

    fun updateSettings(newSettings: OverlaySettings) {
        val previous = settings
        settings = newSettings
        applySettingsToWindows()
        if (previous.effectsEnabled && !newSettings.effectsEnabled) {
            forEachRuntime {
                it.effects.clear()
                it.effectWindow.setEffects(emptyList())
            }
        }
        if (previous.scalePercent != newSettings.scalePercent) {
            // 크기가 바뀌면 이미지 크기도 다시 계산해야 한다.
            runtimeA?.let { applyBitmapSize(it) }
            runtimeB?.let { applyBitmapSize(it) }
        }
        updateVisibility()
    }

    /**
     * 음악 재생 상태가 바뀌었다.
     *
     * 재생이 시작되면 바로 리듬을 타게 한다(반응이 빨라야 자연스럽다).
     * 멈출 때는 동작을 중간에 끊지 않는다. 하던 동작을 끝까지 마치고 나서
     * 다음 동작부터 리듬을 고르지 않게 한다. 그래야 '뚝' 끊기지 않는다.
     */
    fun setMusicPlaying(playing: Boolean) {
        if (musicPlaying == playing) return
        musicPlaying = playing
        if (!settings.musicReactionEnabled) return

        if (playing) {
            val now = System.currentTimeMillis()
            val sceneStarted = director.onTrigger(SceneTrigger.MUSIC_STARTED, now)
            forEachRuntime { runtime ->
                if (!runtime.interactionHeld) {
                    if (sceneStarted) {
                        applyDirection(runtime, now)
                    } else {
                        startAction(runtime, CharacterAction.RHYTHM, now)
                    }
                    spawnEffect(runtime, EffectKind.NOTE, 3, now)
                }
            }
        }
        // 멈출 때는 아무것도 끊지 않는다. tick 이 동작을 끝까지 재생한 뒤
        // 스케줄러가 다음 동작부터 리듬 대신 다른 것을 고른다.
    }

    fun start() {
        if (running) return
        running = true
        refreshScreenSize()
        choreographer.postFrameCallback(frameCallback)
    }

    fun stop() {
        running = false
        choreographer.removeFrameCallback(frameCallback)
    }

    fun release() {
        stop()
        director.reset()
        runtimeA?.let { teardown(it) }
        runtimeB?.let { teardown(it) }
        runtimeA = null
        runtimeB = null
    }

    // ---------------------------------------------------------------- 내부 구성

    private inline fun forEachRuntime(block: (Runtime) -> Unit) {
        runtimeA?.let(block)
        runtimeB?.let(block)
    }

    private fun build(entity: CharacterEntity, slot: CharacterWindow.Slot): Runtime? {
        val bitmap = loadBitmap(entity.imagePath)
        if (bitmap == null) {
            Log.w(TAG, "캐릭터 이미지를 불러오지 못했습니다: ${entity.name}")
            return null
        }
        val window = CharacterWindow(context, windowManager, slot, this)
        val effectWindow = EffectWindow(context, windowManager)
        val runtime = Runtime(entity, window, effectWindow, bitmap)
        applyBitmapSize(runtime)
        window.attach()
        effectWindow.attach()
        startAction(runtime, CharacterAction.IDLE, System.currentTimeMillis())
        return runtime
    }

    private fun teardown(runtime: Runtime) {
        runtime.effectWindow.detach()
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

        runtime.effectWindow.setSize(
            (widthPx * EFFECT_WIDTH_FACTOR).toInt().coerceAtLeast(MIN_EFFECT_SIZE_PX),
            (heightPx * EFFECT_HEIGHT_FACTOR).toInt().coerceAtLeast(MIN_EFFECT_SIZE_PX)
        )
    }

    private fun applySettingsToWindows() {
        forEachRuntime { it.window.setOpacity(settings.opacity) }
        runtimeB?.window?.setVisible(settings.mode == OverlayMode.PAIR)
    }

    private fun updateVisibility() {
        val hidden = settings.isHiddenAt(System.currentTimeMillis())
        runtimeA?.window?.setVisible(!hidden)
        runtimeB?.window?.setVisible(!hidden && settings.mode == OverlayMode.PAIR)
        if (hidden) {
            forEachRuntime {
                it.effects.clear()
                it.effectWindow.setEffects(emptyList())
            }
        }
    }

    private fun placeInitialPositions() {
        refreshScreenSize()
        val floorY = screenHeight * FLOOR_RATIO

        runtimeA?.let { runtime ->
            val x = if (settings.positionAX != OverlaySettings.UNSET_POSITION) {
                settings.positionAX.toFloat()
            } else {
                screenWidth * 0.32f
            }
            val y = if (settings.positionAY != OverlaySettings.UNSET_POSITION) {
                settings.positionAY.toFloat()
            } else {
                floorY
            }
            runtime.window.setAnchor(clampX(x, runtime), clampY(y))
            runtime.window.commit()
        }

        runtimeB?.let { runtime ->
            val x = if (settings.positionBX != OverlaySettings.UNSET_POSITION) {
                settings.positionBX.toFloat()
            } else {
                screenWidth * 0.62f
            }
            val y = if (settings.positionBY != OverlaySettings.UNSET_POSITION) {
                settings.positionBY.toFloat()
            } else {
                floorY
            }
            runtime.window.setAnchor(clampX(x, runtime), clampY(y))
            runtime.window.commit()
        }
    }

    // ---------------------------------------------------------------- 애니메이션 루프

    private fun tick(now: Long) {
        // 숨김 시간이 지났는지, 화면이 돌아갔는지를 매 프레임 확인할 필요는 없다.
        if (now - lastVisibilityCheck > VISIBILITY_CHECK_INTERVAL_MS) {
            lastVisibilityCheck = now
            refreshScreenSize()
            updateVisibility()
        }

        runtimeA?.let { updateRuntime(it, now) }
        runtimeB?.let { updateRuntime(it, now) }
    }

    private fun updateRuntime(runtime: Runtime, now: Long) {
        val elapsed = now - runtime.actionStart
        val duration = runtime.actionDuration.coerceAtLeast(1L)

        val progress: Float
        if (runtime.interactionHeld) {
            // 붙잡고 있는 동안에는 같은 동작을 반복 재생만 한다.
            progress = (elapsed % duration).toFloat() / duration
        } else {
            progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)

            if (runtime.action.moves) {
                val eased = easeInOut(progress)
                val x = runtime.walkFromX + (runtime.walkToX - runtime.walkFromX) * eased
                runtime.window.setAnchor(clampX(x, runtime), runtime.window.anchorY)
            }
        }

        if (runtime.interactionHeld && runtime.action != CharacterAction.PET) {
            // 끌고 있는 동안에는 집어 든 자세로 고정한다.
            // 창이 빠르게 움직이는 중에 안쪽 그림까지 매 프레임 다시 그리면,
            // 창 이동과 그리기가 서로 다른 프레임에 반영되면서 튀어 보인다.
            if (now - runtime.blendStart < BLEND_MS) {
                val settle = blended(runtime, Pose.NEUTRAL, now)
                runtime.lastPose = settle
                runtime.window.setPose(settle)
            }
        } else {
            val rawPose = PoseCalculator.pose(
                action = runtime.action,
                progress = progress,
                heightPx = runtime.displayHeight,
                seed = runtime.seed
            )
            val pose = blended(runtime, rawPose, now)
            runtime.lastPose = pose
            runtime.window.setPose(pose)
        }
        runtime.window.setFacingRight(runtime.facingRight)
        runtime.window.setLift(
            if (runtime.action == CharacterAction.JUMP) {
                PoseCalculator.jumpHeight(progress, runtime.displayHeight)
            } else {
                0f
            }
        )
        // 위치와 점프 높이를 한 번에 반영한다.
        runtime.window.commit()

        syncEffectWindow(runtime, now)

        if (!runtime.interactionHeld && progress >= 1f) {
            advanceAction(runtime, now)
        }
    }

    /** 동작이 바뀐 직후에는 직전 자세에서 새 자세로 부드럽게 건너간다. */
    private fun blended(runtime: Runtime, target: Pose, now: Long): Pose {
        val sinceBlend = now - runtime.blendStart
        if (sinceBlend >= BLEND_MS) return target
        val k = easeInOut((sinceBlend.toFloat() / BLEND_MS).coerceIn(0f, 1f))
        val from = runtime.blendFrom
        return Pose(
            scaleX = from.scaleX + (target.scaleX - from.scaleX) * k,
            scaleY = from.scaleY + (target.scaleY - from.scaleY) * k,
            rotationDeg = from.rotationDeg + (target.rotationDeg - from.rotationDeg) * k,
            offsetY = from.offsetY + (target.offsetY - from.offsetY) * k
        )
    }

    private fun syncEffectWindow(runtime: Runtime, now: Long) {
        val rendered = runtime.effects.render(now)
        runtime.effectWindow.setEffects(rendered)
        if (rendered.isEmpty()) return

        val width = runtime.displayWidth * EFFECT_WIDTH_FACTOR
        val height = runtime.displayHeight * EFFECT_HEIGHT_FACTOR
        runtime.effectWindow.setPosition(
            runtime.window.anchorX - width / 2f,
            runtime.window.headTopY - height + runtime.displayHeight * EFFECT_OVERLAP_RATIO
        )
    }

    /** 동작이 끝났다. 벽에 부딪혔으면 그 연출부터 하고, 아니면 스케줄러에게 묻는다. */
    private fun advanceAction(runtime: Runtime, now: Long) {
        if (runtime.willHitWall) {
            runtime.willHitWall = false
            runtime.facingRight = !runtime.facingRight
            spawnEffect(runtime, EffectKind.EXCLAIM, 1, now)
            startAction(runtime, CharacterAction.BUMP, now)
            return
        }
        applyDirection(runtime, now)
    }

    /** 스케줄러가 정해 준 동작을 시작한다. */
    private fun applyDirection(runtime: Runtime, now: Long) {
        val direction = director.nextDirection(
            performer = performerOf(runtime),
            now = now,
            musicPlaying = musicPlaying && settings.musicReactionEnabled,
            nearEdge = isNearEdge(runtime)
        )
        startAction(runtime, direction.action, now, direction.durationMs)
    }

    private fun performerOf(runtime: Runtime): Performer =
        if (runtime === runtimeA) Performer.A else Performer.B

    private fun startAction(
        runtime: Runtime,
        action: CharacterAction,
        now: Long,
        durationMs: Long = 0L
    ) {
        runtime.blendFrom = runtime.lastPose
        runtime.blendStart = now
        runtime.action = action
        runtime.actionStart = now
        runtime.actionDuration = if (durationMs > 0L) durationMs else action.defaultDurationMs

        when (action) {
            CharacterAction.WALK -> setupWalk(runtime)
            CharacterAction.APPROACH -> setupApproach(runtime)
            CharacterAction.LOOK_AT -> {
                otherOf(runtime)?.let { other ->
                    runtime.facingRight = other.window.anchorX >= runtime.window.anchorX
                }
            }

            else -> Unit
        }
    }

    private fun setupWalk(runtime: Runtime) {
        val distance = screenWidth * WALK_DISTANCE_RATIO
        val direction = if (Random.nextBoolean()) 1f else -1f
        val desired = runtime.window.anchorX + distance * direction
        val clamped = clampX(desired, runtime)
        runtime.walkFromX = runtime.window.anchorX
        runtime.walkToX = clamped
        runtime.facingRight = clamped >= runtime.walkFromX
        // 가고 싶은 곳까지 못 갔다면 화면 끝에 막힌 것이다.
        runtime.willHitWall = abs(desired - clamped) > WALL_TOLERANCE_PX
    }

    private fun setupApproach(runtime: Runtime) {
        val other = otherOf(runtime)
        runtime.walkFromX = runtime.window.anchorX
        runtime.willHitWall = false
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

    private fun isNearEdge(runtime: Runtime): Boolean {
        if (screenWidth <= 0f) return false
        val x = runtime.window.anchorX
        val margin = runtime.displayWidth * 0.6f + screenWidth * 0.03f
        return x < margin || x > screenWidth - margin
    }

    /** 표시는 설정에서 끌 수 있다. 띄우는 곳은 전부 이 창구를 거친다. */
    private fun spawnEffect(runtime: Runtime, kind: EffectKind, count: Int, now: Long) {
        if (!settings.effectsEnabled) return
        runtime.effects.spawn(kind, count, now)
    }

    private fun otherOf(runtime: Runtime): Runtime? =
        if (runtime === runtimeA) runtimeB else runtimeA

    // ---------------------------------------------------------------- 터치

    override fun onTap(slot: CharacterWindow.Slot) {
        val runtime = runtimeOf(slot) ?: return
        val now = System.currentTimeMillis()
        startAction(runtime, CharacterAction.SURPRISED, now)
        spawnEffect(runtime, EffectKind.HEART, 2, now)

        // 터치에 반응하는 장면이 있으면 놀란 직후 그 장면이 이어진다.
        val sceneStarted = director.onTrigger(SceneTrigger.TAP_CHARACTER, now)

        // 장면이 잡히지 않았을 때만 짝이 직접 쳐다본다.
        if (!sceneStarted) {
            otherOf(runtime)?.let { other ->
                if (!other.interactionHeld) {
                    other.facingRight = runtime.window.anchorX >= other.window.anchorX
                    startAction(other, CharacterAction.LOOK_AT, now)
                }
            }
        }
    }

    override fun onLongPress(slot: CharacterWindow.Slot) {
        // 문서 요구사항: 민감한 화면을 자동으로 피할 수 없으므로
        // 사용자가 즉시 비킬 수 있는 수단(길게 누르기)을 제공한다.
        onHideRequested()
    }

    override fun onDragStart(slot: CharacterWindow.Slot) {
        val runtime = runtimeOf(slot) ?: return
        val now = System.currentTimeMillis()
        runtime.interactionHeld = true
        runtime.heldAnchorX = runtime.window.anchorX
        runtime.heldAnchorY = runtime.window.anchorY
        // 한쪽을 붙잡으면 둘이 맞춰 가던 장면을 이어갈 수 없다.
        director.abandonCurrentScript()
        startAction(runtime, CharacterAction.IDLE, now)

        if (settings.linkedDrag) {
            otherOf(runtime)?.let { other ->
                other.interactionHeld = true
                startAction(other, CharacterAction.IDLE, now)
            }
        }
    }

    override fun onDrag(slot: CharacterWindow.Slot, deltaX: Float, deltaY: Float) {
        val dragged = runtimeOf(slot) ?: return
        moveBy(dragged, deltaX, deltaY)
        if (settings.linkedDrag) {
            otherOf(dragged)?.let { moveBy(it, deltaX, deltaY) }
        }
    }

    override fun onDragEnd(slot: CharacterWindow.Slot) {
        releaseHold()
        persistPositions()
    }

    override fun onPetStart(slot: CharacterWindow.Slot) {
        val runtime = runtimeOf(slot) ?: return
        val now = System.currentTimeMillis()
        // 쓰다듬기로 판정되기 전까지 조금 끌려간 만큼을 되돌린다.
        runtime.window.setAnchor(runtime.heldAnchorX, runtime.heldAnchorY)
        runtime.window.commit()
        runtime.interactionHeld = true
        director.abandonCurrentScript()
        startAction(runtime, CharacterAction.PET, now)
    }

    override fun onPetTick(slot: CharacterWindow.Slot) {
        val runtime = runtimeOf(slot) ?: return
        spawnEffect(runtime, EffectKind.HEART, 1, System.currentTimeMillis())
    }

    override fun onPetEnd(slot: CharacterWindow.Slot) {
        releaseHold()
    }

    private fun releaseHold() {
        val now = System.currentTimeMillis()
        forEachRuntime { runtime ->
            if (runtime.interactionHeld) {
                runtime.interactionHeld = false
                startAction(runtime, CharacterAction.IDLE, now)
            }
        }
    }

    private fun persistPositions() {
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
        // 위치만 기록하고 창은 옮기지 않는다.
        // 터치 이벤트는 화면 주사율보다 자주 들어오는데, 그때마다 창을 옮기면
        // 한 프레임 안에 여러 번 옮기라는 요청이 쌓여 빠르게 끌 때 튄다.
        // 실제 이동은 매 프레임 한 번, tick 의 commit 에서만 한다.
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

    private fun refreshScreenSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width().toFloat()
            screenHeight = bounds.height().toFloat()
        } else {
            val metrics = context.resources.displayMetrics
            screenWidth = metrics.widthPixels.toFloat()
            screenHeight = metrics.heightPixels.toFloat()
        }
    }

    /** 캐릭터가 화면 밖으로 나가지 않게 기준점을 가둔다. */
    private fun clampX(x: Float, runtime: Runtime): Float {
        val anchorRatio = runtime.entity.anchorXRatio.coerceIn(0f, 1f)
        val min = runtime.displayWidth * anchorRatio
        val max = screenWidth - runtime.displayWidth * (1f - anchorRatio)
        return if (min > max) screenWidth / 2f else x.coerceIn(min, max)
    }

    private fun clampY(y: Float): Float =
        y.coerceIn(screenHeight * MIN_Y_RATIO, screenHeight * MAX_Y_RATIO)

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

        /** 동작 전환을 부드럽게 잇는 시간. */
        private const val BLEND_MS = 260L

        /** 이만큼 못 갔으면 화면 끝에 막힌 것으로 본다. */
        private const val WALL_TOLERANCE_PX = 2f

        private const val EFFECT_WIDTH_FACTOR = 1.9f
        private const val EFFECT_HEIGHT_FACTOR = 0.9f
        private const val EFFECT_OVERLAP_RATIO = 0.12f
        private const val MIN_EFFECT_SIZE_PX = 120
    }
}
