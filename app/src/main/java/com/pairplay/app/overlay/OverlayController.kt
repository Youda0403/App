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
import com.pairplay.app.engine.BubbleMapper
import com.pairplay.app.engine.BubbleSymbol
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
    private val onPositionPersist: (slot: CharacterWindow.Slot, x: Int, y: Int) -> Unit,
    /** 지금 어떤 상황극이 도는지 알린다. 앱 화면에서 확인용으로 보여 준다. */
    private val onSceneChanged: (String?) -> Unit = {}
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

        /** 지금 그려지는 방향(-1~1). 즉시 뒤집지 않고 0 을 지나며 돌아선다. */
        var facingFrom: Float = 1f
        var facingChangedAt: Long = 0L

        var walkFromX: Float = 0f
        var walkToX: Float = 0f
        var walkFromY: Float = 0f
        var walkToY: Float = 0f

        /** 지금 하는 동작이 어떤 장면의 일부인지. 혼자 하는 동작이면 null. */
        var currentScriptId: String? = null

        /** 붙잡았을 때 손가락과 기준점의 간격. 절대 좌표로 끌기 위해 쓴다. */
        var grabOffsetX: Float = 0f
        var grabOffsetY: Float = 0f

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

    /** 직전 프레임에 둘이 닿아 있었는지. 새로 닿는 순간에만 부딪히는 연출을 한다. */
    private var charactersTouching = false

    private var lastSceneName: String? = null

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
        applyActivitySettings()
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
        applyActivitySettings()
        if (previous.effectsEnabled && !newSettings.effectsEnabled) {
            forEachRuntime {
                it.effects.clear()
                it.effectWindow.setContent(emptyList(), null, 0f)
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

    /** 활발함 설정을 스케줄러에 반영한다. */
    private fun applyActivitySettings() {
        val activity = settings.activity
        director.setEagerness(MIN_EAGERNESS + (MAX_EAGERNESS - MIN_EAGERNESS) * activity)
        director.setSpeedScale(MIN_SPEED + (MAX_SPEED - MIN_SPEED) * activity)
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
                it.effectWindow.setContent(emptyList(), null, 0f)
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

        // 서로 겹치지 않게 조금씩 밀어낸다. 새로 닿으면 부딪히는 연출을 한다.
        resolveOverlap(now)
        runtimeA?.window?.commit()
        runtimeB?.window?.commit()
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
                val y = runtime.walkFromY + (runtime.walkToY - runtime.walkFromY) * eased
                runtime.window.setAnchor(clampX(x, runtime), clampY(y))
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
                progress = poseProgress(runtime, progress, now),
                heightPx = runtime.displayHeight,
                seed = runtime.seed
            )
            val pose = blended(runtime, rawPose, now)
            runtime.lastPose = pose
            runtime.window.setPose(pose)
        }
        runtime.window.setFacingFactor(facingFactor(runtime, now))
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

    /**
     * 자세를 계산할 때 쓸 진행도.
     *
     * 가만히 있는 동작은 지시받은 길이와 상관없이 늘 같은 속도로 숨 쉬어야 한다.
     * 스케줄러가 상대를 기다리며 0.4초짜리 대기를 주면, 진행도를 그대로 쓸 경우
     * 숨쉬기가 6배 빨라져 덜덜 떠는 것처럼 보인다. 그래서 절대 시각으로 계산한다.
     */
    private fun poseProgress(runtime: Runtime, progress: Float, now: Long): Float {
        if (!runtime.action.loops) return progress
        val period = runtime.action.defaultDurationMs
        if (period <= 0L) return progress
        return (now % period).toFloat() / period
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
        val symbol = if (settings.bubblesEnabled) {
            BubbleMapper.forAction(runtime.action, runtime.currentScriptId != null)
        } else {
            null
        }
        val alpha = if (symbol == null) 0f else bubbleAlpha(runtime, now)

        runtime.effectWindow.setContent(rendered, symbol, alpha)
        if (rendered.isEmpty() && alpha <= 0.02f) return

        val width = runtime.displayWidth * EFFECT_WIDTH_FACTOR
        val height = runtime.displayHeight * EFFECT_HEIGHT_FACTOR
        runtime.effectWindow.setPosition(
            runtime.window.anchorX - width / 2f,
            runtime.window.headTopY - height + runtime.displayHeight * EFFECT_OVERLAP_RATIO
        )
    }

    /** 말풍선은 동작이 시작할 때 떠오르고 끝나기 직전에 사라진다. */
    private fun bubbleAlpha(runtime: Runtime, now: Long): Float {
        val elapsed = (now - runtime.actionStart).coerceAtLeast(0L)
        val fadeIn = (elapsed.toFloat() / BUBBLE_FADE_IN_MS).coerceIn(0f, 1f)
        if (runtime.interactionHeld) return fadeIn

        val remaining = runtime.actionDuration - elapsed
        val fadeOut = (remaining.toFloat() / BUBBLE_FADE_OUT_MS).coerceIn(0f, 1f)
        return minOf(fadeIn, fadeOut)
    }

    /** 방향을 바꾼다. 즉시 뒤집지 않고 몸을 돌리는 것처럼 보이게 한다. */
    private fun faceTo(runtime: Runtime, right: Boolean, now: Long) {
        if (runtime.facingRight == right) return
        runtime.facingFrom = facingFactor(runtime, now)
        runtime.facingChangedAt = now
        runtime.facingRight = right
    }

    private fun facingFactor(runtime: Runtime, now: Long): Float {
        val target = if (runtime.facingRight) 1f else -1f
        val elapsed = now - runtime.facingChangedAt
        if (elapsed >= FLIP_MS) return target
        val k = (elapsed.toFloat() / FLIP_MS).coerceIn(0f, 1f)
        return runtime.facingFrom + (target - runtime.facingFrom) * k
    }

    // ---------------------------------------------------------------- 서로 겹치지 않기

    /** 두 캐릭터가 이보다 가까워지면 겹쳐 보인다. */
    private fun minSeparation(a: Runtime, b: Runtime): Float =
        (a.displayWidth + b.displayWidth) * OVERLAP_GAP_RATIO

    /**
     * 둘이 겹치지 않게 조금씩 밀어내고, 새로 닿는 순간에는 부딪히는 연출을 한다.
     * 붙잡혀 있는 쪽은 밀지 않는다. 손가락을 따라가야 하기 때문이다.
     */
    private fun resolveOverlap(now: Long) {
        val a = runtimeA ?: return
        val b = runtimeB ?: return
        if (settings.mode != OverlayMode.PAIR) return

        val gap = minSeparation(a, b)
        val delta = b.window.anchorX - a.window.anchorX
        val distance = abs(delta)
        val touching = distance < gap

        if (touching && !charactersTouching) {
            onCharactersTouched(a, b, now)
        }
        charactersTouching = touching
        if (!touching) return

        // 완전히 겹친 경우에도 한쪽으로 밀어낼 방향이 필요하다.
        val direction = if (delta >= 0f) 1f else -1f
        val overlap = (gap - distance) * SEPARATION_STEP

        if (!a.interactionHeld) {
            a.window.setAnchor(
                clampX(a.window.anchorX - direction * overlap, a),
                a.window.anchorY
            )
        }
        if (!b.interactionHeld) {
            b.window.setAnchor(
                clampX(b.window.anchorX + direction * overlap, b),
                b.window.anchorY
            )
        }
    }

    /** 방금 닿았다. 서로 놀라며 콩 부딪힌다. */
    private fun onCharactersTouched(a: Runtime, b: Runtime, now: Long) {
        spawnEffect(a, EffectKind.EXCLAIM, 1, now)
        spawnEffect(b, EffectKind.EXCLAIM, 1, now)

        faceTo(a, b.window.anchorX >= a.window.anchorX, now)
        faceTo(b, a.window.anchorX >= b.window.anchorX, now)

        if (!a.interactionHeld) startAction(a, CharacterAction.BUMP, now)
        if (!b.interactionHeld) startAction(b, CharacterAction.BUMP, now)
    }

    /** 둘이 충분히 가까우면 '마주쳤을 때' 장면을 시작해 본다. */
    private fun tryMeetScene(now: Long) {
        val a = runtimeA ?: return
        val b = runtimeB ?: return
        if (settings.mode != OverlayMode.PAIR) return
        if (a.interactionHeld || b.interactionHeld) return

        val distance = abs(b.window.anchorX - a.window.anchorX)
        if (distance > minSeparation(a, b) * MEET_DISTANCE_FACTOR) return

        if (director.onTrigger(SceneTrigger.CHARACTERS_MET, now)) {
            applyDirection(a, now)
            applyDirection(b, now)
        }
    }

    /** 동작이 끝났다. 벽에 부딪혔으면 그 연출부터 하고, 아니면 스케줄러에게 묻는다. */
    private fun advanceAction(runtime: Runtime, now: Long) {
        if (runtime.willHitWall) {
            runtime.willHitWall = false
            faceTo(runtime, !runtime.facingRight, now)
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
        runtime.currentScriptId = direction.scriptId
        startAction(runtime, direction.action, now, direction.durationMs)
        notifyScene()
    }

    private fun notifyScene() {
        val name = director.activeScriptName
        if (name == lastSceneName) return
        lastSceneName = name
        onSceneChanged(name)
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
            CharacterAction.WALK -> setupWalk(runtime, now)
            CharacterAction.APPROACH -> setupApproach(runtime, now)
            CharacterAction.LOOK_AT -> {
                otherOf(runtime)?.let { other ->
                    faceTo(runtime, other.window.anchorX >= runtime.window.anchorX, now)
                }
            }

            else -> Unit
        }
    }

    private fun setupWalk(runtime: Runtime, now: Long) {
        // 활발할수록 한 번에 멀리 간다.
        val ratio = MIN_WALK_RATIO + (MAX_WALK_RATIO - MIN_WALK_RATIO) * settings.activity
        val distance = screenWidth * ratio
        val direction = if (Random.nextBoolean()) 1f else -1f
        val desired = runtime.window.anchorX + distance * direction
        val clamped = clampX(desired, runtime)
        runtime.walkFromX = runtime.window.anchorX
        runtime.walkToX = clamped

        // 좌우로만 왔다 갔다 하면 단조롭다. 위아래로도 조금씩 자리를 옮긴다.
        val drift = screenHeight * VERTICAL_DRIFT_RATIO * settings.activity
        runtime.walkFromY = runtime.window.anchorY
        runtime.walkToY = clampY(runtime.window.anchorY + (Random.nextFloat() - 0.5f) * 2f * drift)

        faceTo(runtime, clamped >= runtime.walkFromX, now)
        // 가고 싶은 곳까지 못 갔다면 화면 끝에 막힌 것이다.
        runtime.willHitWall = abs(desired - clamped) > WALL_TOLERANCE_PX
    }

    private fun setupApproach(runtime: Runtime, now: Long) {
        val other = otherOf(runtime)
        runtime.walkFromX = runtime.window.anchorX
        runtime.walkFromY = runtime.window.anchorY
        runtime.walkToY = runtime.window.anchorY
        runtime.willHitWall = false
        runtime.walkToX = if (other != null) {
            // 서로 밀어내는 최소 간격보다 살짝 넓게 선다. 그래야 다가간 뒤에
            // 밀려나며 덜컥거리지 않는다.
            val gap = minSeparation(runtime, other) * APPROACH_GAP_FACTOR
            val target = if (other.window.anchorX >= runtime.window.anchorX) {
                other.window.anchorX - gap
            } else {
                other.window.anchorX + gap
            }
            clampX(target, runtime)
        } else {
            runtime.window.anchorX
        }
        faceTo(runtime, runtime.walkToX >= runtime.walkFromX, now)
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
                    faceTo(other, runtime.window.anchorX >= other.window.anchorX, now)
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

    override fun onDragStart(slot: CharacterWindow.Slot, rawX: Float, rawY: Float) {
        val runtime = runtimeOf(slot) ?: return
        val now = System.currentTimeMillis()
        runtime.interactionHeld = true
        runtime.heldAnchorX = runtime.window.anchorX
        runtime.heldAnchorY = runtime.window.anchorY
        grab(runtime, rawX, rawY)
        // 한쪽을 붙잡으면 둘이 맞춰 가던 장면을 이어갈 수 없다.
        director.abandonCurrentScript()
        startAction(runtime, CharacterAction.IDLE, now)

        if (settings.linkedDrag) {
            otherOf(runtime)?.let { other ->
                other.interactionHeld = true
                grab(other, rawX, rawY)
                startAction(other, CharacterAction.IDLE, now)
            }
        }
    }

    /** 손가락과 기준점 사이 간격을 기억해 둔다. */
    private fun grab(runtime: Runtime, rawX: Float, rawY: Float) {
        runtime.grabOffsetX = runtime.window.anchorX - rawX
        runtime.grabOffsetY = runtime.window.anchorY - rawY
    }

    override fun onDrag(slot: CharacterWindow.Slot, rawX: Float, rawY: Float) {
        val dragged = runtimeOf(slot) ?: return
        moveToFinger(dragged, rawX, rawY)
        if (settings.linkedDrag) {
            otherOf(dragged)?.let { moveToFinger(it, rawX, rawY) }
        }
    }

    override fun onDragEnd(slot: CharacterWindow.Slot) {
        releaseHold()
        persistPositions()
        // 다른 캐릭터 옆에 데려다 놓았다면 서로 반응한다.
        tryMeetScene(System.currentTimeMillis())
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

    /**
     * 처음 잡은 지점을 기준으로 손가락 위치에 맞춘다.
     *
     * 이동량을 더해 가면 터치 이벤트가 한 번 빠질 때마다 오차가 쌓여서,
     * 화면 끝처럼 이벤트가 튀는 곳에서 캐릭터가 덜컥거린다.
     * 창을 실제로 옮기는 일은 매 프레임 한 번, tick 의 commit 이 한다.
     */
    private fun moveToFinger(runtime: Runtime, rawX: Float, rawY: Float) {
        runtime.window.setAnchor(
            clampX(rawX + runtime.grabOffsetX, runtime),
            clampY(rawY + runtime.grabOffsetY)
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

        /** 한 번에 걷는 거리(화면 가로 대비). 활발함 설정으로 이 사이를 오간다. */
        private const val MIN_WALK_RATIO = 0.10f
        private const val MAX_WALK_RATIO = 0.38f

        /** 걸을 때 위아래로 움직이는 폭(화면 세로 대비). */
        private const val VERTICAL_DRIFT_RATIO = 0.07f

        /** 두 캐릭터 사이 최소 간격(두 캐릭터 가로폭 합 대비). */
        private const val OVERLAP_GAP_RATIO = 0.42f

        /** 다가갈 때는 최소 간격보다 살짝 넓게 선다. */
        private const val APPROACH_GAP_FACTOR = 1.15f

        /** 한 프레임에 밀어내는 정도. 한 번에 다 밀면 순간이동처럼 보인다. */
        private const val SEPARATION_STEP = 0.2f

        /** 이 거리 안이면 '마주쳤다'고 본다. */
        private const val MEET_DISTANCE_FACTOR = 1.6f

        /** 몸을 돌리는 데 걸리는 시간. */
        private const val FLIP_MS = 220L

        private const val BUBBLE_FADE_IN_MS = 180f
        private const val BUBBLE_FADE_OUT_MS = 260f

        private const val MIN_EAGERNESS = 0.25f
        private const val MAX_EAGERNESS = 0.95f
        private const val MIN_SPEED = 0.75f
        private const val MAX_SPEED = 1.35f
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
