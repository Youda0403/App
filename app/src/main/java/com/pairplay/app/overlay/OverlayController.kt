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
import com.pairplay.app.data.SceneEntity
import com.pairplay.app.data.SceneTrigger
import com.pairplay.app.engine.CharacterAction
import com.pairplay.app.engine.DeviceEvent
import com.pairplay.app.engine.ReactionMapper
import com.pairplay.app.engine.Expression
import com.pairplay.app.engine.ExpressionMapper
import com.pairplay.app.engine.ExpressionSlots
import com.pairplay.app.engine.EffectEmitter
import com.pairplay.app.engine.EffectKind
import com.pairplay.app.engine.MoodMapper
import com.pairplay.app.engine.Pose
import com.pairplay.app.engine.CharacterTraits
import com.pairplay.app.engine.Performer
import com.pairplay.app.engine.PoseCalculator
import com.pairplay.app.engine.RelationshipContext
import com.pairplay.app.engine.SceneCodec
import com.pairplay.app.engine.SceneDirector
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
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
        /**
         * 표정별 그림. 기본(NEUTRAL)은 반드시 있고, 나머지는 사용자가 등록한 것만 있다.
         * 등록하지 않은 표정은 기본 그림으로 돌아간다.
         */
        val bitmaps: Map<Expression, Bitmap>
    ) {
        /** 지금 그려지고 있는 표정. */
        var expression: Expression = Expression.NEUTRAL

        /** 그 표정으로 바꾼 시각. 너무 빨리 되돌아가 깜빡이지 않게 하려고 본다. */
        var expressionSince: Long = 0L

        /** 지금 그려야 할 그림. 등록하지 않은 표정이면 기본 그림. */
        val bitmap: Bitmap?
            get() = bitmaps[expression] ?: bitmaps[Expression.NEUTRAL]

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

        /** 끌리는 속도(px/프레임). 매달려 흔들리는 정도와 던지는 세기를 정한다. */
        var dragVelocity: Float = 0f
        var dragVelocityY: Float = 0f

        /**
         * 날아가는 중의 속도(px/프레임). 0 이 아니면 던져졌거나 떨어지는 중이다.
         * 위치를 스스로 정하므로 걷기·밀어내기와 겹치지 않게 다뤄야 한다.
         */
        var flyVX: Float = 0f
        var flyVY: Float = 0f
        var flying: Boolean = false

        /**
         * 매달렸을 때의 흔들림. 각도와 각속도를 함께 들고 있어야 손을 멈춰도
         * 그네처럼 몇 번 더 흔들리다 잦아든다.
         */
        var swingDeg: Float = 0f
        var swingVel: Float = 0f

        /** 부딪혀 좌우로 튕겨 나가는 속도(px/프레임). 0 에 가까워지면 멈춘다. */
        var knockVX: Float = 0f

        /** 마지막으로 기분 기호를 띄운 시각. 너무 자주 띄우지 않으려고 본다. */
        var lastMoodAt: Long = 0L

        /** 쓰다듬기로 판정되기 직전 위치. 여기서 제자리로 부드럽게 돌아온다. */
        var petReturnFromX: Float = 0f
        var petReturnFromY: Float = 0f

        /** 이번 이동이 화면 가장자리에 막혀서 끝나는지. 끝나면 부딪히는 연출을 한다. */
        var willHitWall: Boolean = false

        /**
         * 지금 화면에 보이는지.
         * 숨겨 두었거나 한 명만 쓰는 모드의 둘째라면 아예 움직이지 않는다.
         * 보이지 않는데 속으로 움직이면 표시만 허공에 뜬다.
         */
        var visible: Boolean = true

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

    /**
     * 서로 밀어낼 방향. 닿기 시작한 순간에 한 번 정하고 떨어질 때까지 유지한다.
     * 매 프레임 다시 정하면 거의 겹쳤을 때 좌우가 뒤집혀 상대가 반대편으로 튄다.
     */
    private var separationDirection = 1f

    /** 마지막으로 콩 부딪힌 시각. 경계에서 여러 번 연속으로 부딪히지 않게 한다. */
    private var lastBumpAt = 0L

    /** 직전 [shiftRuntime] 이 실제로 옮긴 가로 거리. 화면 끝에 막히면 부탁한 값보다 작다. */
    private var lastShiftX = 0f

    private var lastSceneName: String? = null

    private var lastUserScripts: List<com.pairplay.app.engine.InteractionScript> = emptyList()

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
            // 자리 잡기는 **새로 만든 캐릭터에게만** 한다.
            // 예전에는 여기서 둘 다 저장된 자리로 되돌렸다. 그런데 캐릭터를 놓을
            // 때마다 위치가 저장되고, 저장이 곧 설정 변경이라 이 메서드가 다시 불린다.
            // 그래서 하나를 놓을 때마다 둘 다 저장된 자리로 순간이동했다.
            // (한쪽을 옮겼는데 다른 쪽이 움직이고, 놓으면 튀던 원인)
            runtimeA?.let { placeInitial(it, isFirst = true) }
        }
        if (!keepB) {
            runtimeB?.let { teardown(it) }
            runtimeB = b?.let { build(it, CharacterWindow.Slot.B) }
            runtimeB?.let { placeInitial(it, isFirst = false) }
        }
        applySettingsToWindows()
        updateVisibility()
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

    /**
     * 장면 편집기에서 만든 장면을 반영한다.
     * 꺼 둔 장면과 마디가 없는 장면은 빼고 넘긴다.
     */
    fun setUserScenes(scenes: List<SceneEntity>) {
        val scripts = scenes
            .filter { it.enabled }
            .mapNotNull { SceneCodec.toScript(it) }
        if (scripts == lastUserScripts) return
        lastUserScripts = scripts
        director.setUserScripts(scripts)
    }

    /** 장면 편집기의 '실행해 보기'. 쿨다운을 무시하고 지금 바로 보여 준다. */
    fun runSceneNow(sceneId: Long) {
        val now = System.currentTimeMillis()
        if (!director.startScriptById(SceneCodec.userScriptId(sceneId), now)) return
        runtimeA?.takeIf { !it.interactionHeld }?.let { applyDirection(it, now) }
        runtimeB?.takeIf { !it.interactionHeld }?.let { applyDirection(it, now) }
    }

    fun updateSettings(newSettings: OverlaySettings) {
        val previous = settings
        settings = newSettings
        applySettingsToWindows()
        applyActivitySettings()
        if (previous.effectsEnabled && !newSettings.effectsEnabled) {
            forEachRuntime {
                it.effects.clear()
                it.effectWindow.setContent(emptyList())
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
    /**
     * 휴대폰에서 벌어진 일(흔들기·충전기·이어폰·잠금 해제)에 둘 다 반응한다.
     *
     * 음악 반응과 같은 결이다. 앱 바깥에서 사용자가 한 행동에 캐릭터가 반응해야
     * 살아 있는 느낌이 난다.
     */
    fun onDeviceEvent(event: DeviceEvent) {
        if (!settings.deviceReactionsEnabled) return
        val now = System.currentTimeMillis()
        forEachRuntime { runtime ->
            if (runtime.visible && !runtime.interactionHeld) react(runtime, event, now)
        }
    }

    /** 성격에 맞는 반응을 골라 실제로 보여 준다. */
    private fun react(runtime: Runtime, event: DeviceEvent, now: Long) {
        val reaction = ReactionMapper.forEvent(event, runtime.entity.toTraits())
        startAction(runtime, reaction.action, now)
        reaction.effect?.let { spawnEffect(runtime, it, reaction.effectCount, now) }
    }

    fun setMusicPlaying(playing: Boolean) {
        if (musicPlaying == playing) return
        musicPlaying = playing
        if (!settings.musicReactionEnabled) return

        if (playing) {
            val now = System.currentTimeMillis()
            val sceneStarted = director.onTrigger(SceneTrigger.MUSIC_STARTED, now)
            forEachRuntime { runtime ->
                if (!runtime.interactionHeld && runtime.visible) {
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
        val bitmaps = loadExpressionBitmaps(entity)
        if (bitmaps[Expression.NEUTRAL] == null) {
            Log.w(TAG, "캐릭터 이미지를 불러오지 못했습니다: ${entity.name}")
            return null
        }
        val window = CharacterWindow(context, windowManager, slot, this)
        val effectWindow = EffectWindow(context, windowManager)
        val runtime = Runtime(entity, window, effectWindow, bitmaps)
        applyBitmapSize(runtime)
        window.attach()
        effectWindow.attach()
        startAction(runtime, CharacterAction.IDLE, System.currentTimeMillis())
        return runtime
    }

    private fun teardown(runtime: Runtime) {
        runtime.effectWindow.detach()
        runtime.window.detach()
        runtime.bitmaps.values.forEach { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * 기본 그림과, 사용자가 등록한 표정 그림들을 함께 읽는다.
     * 등록한 표정이 없으면 기본 그림 하나만 들어 있다.
     */
    private fun loadExpressionBitmaps(entity: CharacterEntity): Map<Expression, Bitmap> {
        val target = targetHeightPx(entity)
        val out = LinkedHashMap<Expression, Bitmap>()
        loadBitmap(entity.imagePath, target)?.let { out[Expression.NEUTRAL] = it }
        for ((expression, path) in ExpressionSlots.parse(entity.animationSlots)) {
            val bitmap = loadBitmap(path, target)
            if (bitmap == null) {
                Log.w(TAG, "표정 이미지를 불러오지 못했습니다: ${expression.id}")
            } else {
                out[expression] = bitmap
            }
        }
        return out
    }

    /**
     * 화면에 그려질 크기. 이보다 훨씬 큰 그림을 통째로 메모리에 올릴 필요가 없다.
     * 표정을 여러 장 등록하면 장수만큼 메모리를 쓰므로 여기서 미리 줄인다.
     * 크기 조절 슬라이더를 올릴 수 있으므로 여유를 두 배 둔다.
     */
    private fun targetHeightPx(entity: CharacterEntity): Int {
        val density = context.resources.displayMetrics.density
        return (entity.displayHeightDp * density * BITMAP_HEADROOM).toInt().coerceAtLeast(1)
    }

    private fun loadBitmap(path: String, targetHeightPx: Int): Bitmap? = try {
        val file = File(path)
        if (!file.exists()) {
            null
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)

            var sample = 1
            while (bounds.outHeight / (sample * 2) >= targetHeightPx) {
                sample *= 2
            }

            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "캐릭터 이미지를 담을 메모리가 부족합니다", e)
        null
    }

    /**
     * 동작에 맞는 표정으로 갈아 끼운다.
     * 등록하지 않은 표정이면 기본 그림 그대로 둔다.
     */
    private fun applyExpression(runtime: Runtime, action: CharacterAction, now: Long) {
        val wanted = ExpressionMapper.forAction(action)
        // 등록한 그림이 없으면 기본으로 돌아간다. 같은 그림을 다시 끼울 필요는 없다.
        val next = if (runtime.bitmaps.containsKey(wanted)) wanted else Expression.NEUTRAL
        if (next == runtime.expression) return

        // 상황극 중에는 상대를 기다리며 0.4초짜리 대기 동작이 끼어든다.
        // 그때마다 기본 얼굴로 되돌리면 표정이 깜빡인다. 잠깐은 그대로 둔다.
        if (next == Expression.NEUTRAL && now - runtime.expressionSince < EXPRESSION_HOLD_MS) {
            return
        }

        runtime.expression = next
        runtime.expressionSince = now
        applyBitmapSize(runtime)
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
        // 기호 크기는 캐릭터 크기와 상관없이 둘이 똑같아야 한다.
        // 창 크기에서 뽑아 쓰면 큰 쪽 기호만 커져서 짝이 맞지 않아 보인다.
        runtime.effectWindow.setUnitSize(density * EFFECT_UNIT_DP)
        // 창 안에서 머리 꼭대기가 어디인지 알려 준다.
        // 떠오르는 기호는 이 선에서 출발하고, 땀은 이 선보다 아래(= 얼굴 위)에 붙는다.
        runtime.effectWindow.setHeadLineRatio(1f - EFFECT_OVERLAP_RATIO / EFFECT_HEIGHT_FACTOR)
    }

    /** 활발함 설정을 스케줄러에 반영한다. */
    private fun applyActivitySettings() {
        val activity = settings.activity
        director.setEagerness(MIN_EAGERNESS + (MAX_EAGERNESS - MIN_EAGERNESS) * activity)
        director.setSpeedScale(MIN_SPEED + (MAX_SPEED - MIN_SPEED) * activity)
    }

    private fun applySettingsToWindows() {
        forEachRuntime { it.window.setOpacity(settings.opacity) }
    }

    /**
     * 지금 누구를 보여 줄지 정한다.
     *
     * 숨긴 캐릭터는 **완전히 멈춘다.** 예전에는 그림만 감추고 속으로는 계속
     * 움직여서, 숨겨 놓아도 하트와 느낌표가 허공에 계속 떴다.
     * 한 명만 쓰는 모드에서 둘째가 보이지 않을 때도 마찬가지였다.
     */
    private fun updateVisibility() {
        val hidden = settings.isHiddenAt(System.currentTimeMillis())
        runtimeA?.let { setRuntimeVisible(it, !hidden) }
        runtimeB?.let {
            setRuntimeVisible(it, !hidden && settings.mode == OverlayMode.PAIR)
        }
    }

    private fun setRuntimeVisible(runtime: Runtime, visible: Boolean) {
        runtime.window.setVisible(visible)
        runtime.effectWindow.setVisible(visible)
        if (runtime.visible == visible) return
        runtime.visible = visible
        if (!visible) {
            // 떠 있던 표시를 남겨 두면 다시 보일 때 옛날 하트가 그대로 떠 있다.
            runtime.effects.clear()
            runtime.effectWindow.setContent(emptyList())
            runtime.interactionHeld = false
            runtime.knockVX = 0f
        }
    }

    /**
     * 캐릭터를 처음 만들 때 한 번만 자리를 잡아 준다.
     * 저장해 둔 자리가 있으면 거기로, 없으면 화면 아래쪽 적당한 곳에 세운다.
     */
    private fun placeInitial(runtime: Runtime, isFirst: Boolean) {
        refreshScreenSize()
        val savedX = if (isFirst) settings.positionAX else settings.positionBX
        val savedY = if (isFirst) settings.positionAY else settings.positionBY

        val x = if (savedX != OverlaySettings.UNSET_POSITION) {
            savedX.toFloat()
        } else {
            screenWidth * if (isFirst) 0.32f else 0.62f
        }
        val y = if (savedY != OverlaySettings.UNSET_POSITION) {
            savedY.toFloat()
        } else {
            screenHeight * FLOOR_RATIO
        }
        runtime.window.setAnchor(clampX(x, runtime), clampY(y))
        runtime.window.commit()
    }

    // ---------------------------------------------------------------- 애니메이션 루프

    private fun tick(now: Long) {
        // 숨김 시간이 지났는지, 화면이 돌아갔는지를 매 프레임 확인할 필요는 없다.
        if (now - lastVisibilityCheck > VISIBILITY_CHECK_INTERVAL_MS) {
            lastVisibilityCheck = now
            refreshScreenSize()
            updateVisibility()
            keepOnScreen()
        }

        val a = runtimeA?.takeIf { it.visible }
        val b = runtimeB?.takeIf { it.visible }
        if (a == null && b == null) return

        a?.let { updateRuntime(it, now) }
        b?.let { updateRuntime(it, now) }

        // 부딪혀 튕겨 나가는 중이면 그만큼 더 밀린다.
        a?.let { applyKnockback(it) }
        b?.let { applyKnockback(it) }

        // 절대 겹치지 않게 밀어낸다. 새로 닿으면 콩 부딪히며 튕겨 나간다.
        resolveOverlap(now)

        // 창을 실제로 옮기는 일은 한 프레임에 **여기 한 번**뿐이다.
        // 위치를 정하는 도중에 옮기면(동작 계산 따로, 밀어내기 따로) 한 프레임에
        // 창이 두 번 움직여 화면이 튄다.
        a?.let {
            it.window.commit()
            syncEffectWindow(it, now)
        }
        b?.let {
            it.window.commit()
            syncEffectWindow(it, now)
        }
    }

    /**
     * 화면이 돌아가거나 크기가 바뀌면 캐릭터가 화면 밖에 남을 수 있다.
     * 자리 잡기는 처음 한 번만 하므로, 여기서 주기적으로 화면 안쪽으로 들인다.
     */
    private fun keepOnScreen() {
        forEachRuntime { runtime ->
            if (runtime.interactionHeld) return@forEachRuntime
            val x = clampX(runtime.window.anchorX, runtime)
            val y = clampY(runtime.window.anchorY)
            if (x != runtime.window.anchorX || y != runtime.window.anchorY) {
                shiftRuntime(runtime, x - runtime.window.anchorX, y - runtime.window.anchorY)
            }
        }
    }

    private fun updateRuntime(runtime: Runtime, now: Long) {
        // 던져져 날아가는 중에는 물리 계산이 위치를 정한다.
        // 걷기나 밀어내기가 끼어들면 서로 잡아당겨 화면이 튄다.
        if (runtime.flying) {
            updateFlight(runtime, now)
            runtime.window.setFacingFactor(facingFactor(runtime, now))
            runtime.window.setLift(0f)
            return
        }

        val elapsed = now - runtime.actionStart
        val duration = runtime.actionDuration.coerceAtLeast(1L)

        // 쓰다듬기로 바뀌면 그 전에 조금 끌려간 만큼을 되돌린다.
        // 한 번에 되돌리면 순간이동처럼 보이므로 짧게 미끄러져 제자리로 온다.
        if (runtime.action == CharacterAction.PET && elapsed < PET_RETURN_MS) {
            val k = easeInOut((elapsed.toFloat() / PET_RETURN_MS).coerceIn(0f, 1f))
            runtime.window.setAnchor(
                runtime.petReturnFromX + (runtime.heldAnchorX - runtime.petReturnFromX) * k,
                runtime.petReturnFromY + (runtime.heldAnchorY - runtime.petReturnFromY) * k
            )
        }

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

        if (runtime.action == CharacterAction.DANGLE) {
            updateDangle(runtime, now)
        } else if (runtime.interactionHeld && runtime.action != CharacterAction.PET) {
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
        // 제자리에 있는 동안에는 매 프레임 짝 쪽을 다시 본다.
        // 동작이 시작할 때만 보면, 그 뒤에 상대가 옮겨 가도 계속 엉뚱한 곳을
        // 보고 서 있게 된다. (짝을 앞에 놓아도 등을 돌리고 있던 원인)
        if (!runtime.action.moves && runtime.action != CharacterAction.BUMP) {
            faceOther(runtime, now)
        }
        runtime.window.setFacingFactor(facingFactor(runtime, now))
        runtime.window.setLift(
            if (runtime.action == CharacterAction.JUMP) {
                PoseCalculator.jumpHeight(progress, runtime.displayHeight)
            } else {
                0f
            }
        )
        // 여기서는 창을 옮기지 않는다. 밀어내기까지 다 끝난 뒤 tick 이 한 번에 옮긴다.

        if (!runtime.interactionHeld && progress >= 1f) {
            advanceAction(runtime, now)
        }
    }

    /**
     * 손가락에 매달려 흔들리는 모습을 갱신한다.
     *
     * **창 위치는 절대 건드리지 않는다.** 한때 회전에 맞춰 창을 좌우로 밀어
     * '머리는 손가락에 붙고 몸이 흔들리는' 그림을 만들었는데, 그 밀림이
     * 손가락 이동과 겹쳐 캐릭터가 사방으로 튀는 것처럼 보였다.
     * 지금은 창 안에서 몸을 기울이기만 한다. 위치가 흔들릴 일이 없다.
     *
     * 기울기는 그네처럼 움직인다. 끌리는 속도를 향해 당겨지되 지나쳤다가
     * 되돌아오므로, 손을 멈춰도 몇 번 더 흔들리다 잦아든다.
     */
    private fun updateDangle(runtime: Runtime, now: Long) {
        runtime.dragVelocity *= VELOCITY_DECAY

        val target = (-runtime.dragVelocity * SWING_PER_PX)
            .coerceIn(-MAX_SWING_DEG, MAX_SWING_DEG)

        runtime.swingVel =
            (runtime.swingVel + (target - runtime.swingDeg) * SWING_STIFFNESS) * SWING_DAMPING
        runtime.swingDeg = (runtime.swingDeg + runtime.swingVel)
            .coerceIn(-MAX_SWING_DEG, MAX_SWING_DEG)

        val pose = PoseCalculator.danglePose(runtime.swingDeg, runtime.displayHeight)
        runtime.lastPose = pose
        runtime.window.setPose(pose)
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
        runtime.effectWindow.setContent(rendered)
        if (rendered.isEmpty()) return

        val width = runtime.displayWidth * EFFECT_WIDTH_FACTOR
        val height = runtime.displayHeight * EFFECT_HEIGHT_FACTOR
        // 창 아래쪽이 머리를 살짝 덮게 둔다. 그래야 기호가 바로 머리 위에서
        // 떠오르고, 땀처럼 붙는 기호는 캐릭터 그림 위에 얹힌다.
        runtime.effectWindow.setPosition(
            runtime.window.anchorX - width / 2f,
            runtime.window.headTopY - height + runtime.displayHeight * EFFECT_OVERLAP_RATIO
        )
    }

    /**
     * 이번 동작에 어울리는 기분 기호를 하나 띄운다.
     *
     * 말풍선을 씌우지 않는다. 말풍선은 캐릭터보다 커 보이고, 두 캐릭터의 크기가
     * 다르면 말풍선 크기까지 달라 보여 어수선했다.
     * 동작이 시작할 때 한 번만 띄운다. 매 프레임 따지면 같은 동작 안에서 깜빡인다.
     * 연달아 띄우지 않도록 최소 간격도 여기서 본다.
     */
    private fun spawnMood(runtime: Runtime, action: CharacterAction, now: Long) {
        if (!settings.bubblesEnabled || !runtime.visible) return
        val kind = MoodMapper.forAction(action, runtime.currentScriptId != null) ?: return
        if (now - runtime.lastMoodAt < MoodMapper.MIN_INTERVAL_MS) return
        runtime.lastMoodAt = now
        runtime.effects.spawn(kind, 1, now)
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

    /**
     * 두 캐릭터가 겹쳐 보이지 않는 최소 **가로** 간격.
     *
     * 세로 간격은 따지지 않는다. 한때 가로세로를 함께 봤는데, 캐릭터 그림은
     * 세로로 길어서 위아래로 조금 떨어져 있어도 그림이 그대로 겹쳐 보인다.
     * 그런데 계산상으로는 '떨어져 있다'가 되어 서로 밀어내지 않았고,
     * 결국 제멋대로 돌아다닐 때 둘이 포개져 버렸다.
     * 그림이 겹치지 않으려면 결국 가로로 떨어져 있어야 한다.
     */
    private fun minSeparationX(a: Runtime, b: Runtime): Float =
        (a.displayWidth + b.displayWidth) * OVERLAP_GAP_RATIO

    /**
     * 둘이 절대 겹치지 않게 밀어낸다.
     *
     * 겹친 만큼을 그 프레임에 전부 해소한다. 조금씩 밀면 손으로 밀어 넣는 동안
     * 계속 겹친 채로 덜덜 떨린다.
     * 한쪽이 화면 끝에 막혀 못 비키면 그만큼 다른 쪽이 더 비켜 준다.
     */
    private fun resolveOverlap(now: Long) {
        val a = runtimeA?.takeIf { it.visible } ?: return
        val b = runtimeB?.takeIf { it.visible } ?: return
        if (settings.mode != OverlayMode.PAIR) return

        val gap = minSeparationX(a, b)
        if (gap <= 0f) return

        val delta = b.window.anchorX - a.window.anchorX
        val distance = abs(delta)

        // 닿았다/떨어졌다를 다른 기준으로 본다. 기준이 하나뿐이면 경계에서
        // 붙었다 떨어졌다가 반복되어 부딪히는 연출이 계속 터지고 화면이 튄다.
        val touching = if (charactersTouching) distance < gap * TOUCH_RELEASE else distance < gap
        // 손에 잡혀 있거나 날아가는 중에는 서로 밀지 않는다.
        val held = a.interactionHeld || b.interactionHeld || a.flying || b.flying

        if (touching && !charactersTouching) {
            // 밀어낼 방향은 **닿기 시작한 지금 한 번만** 정하고 떨어질 때까지 유지한다.
            // 매 프레임 다시 정하면, 붙잡은 쪽이 상대를 가로지르는 순간 좌우 부호가
            // 뒤집혀 상대가 반대편으로 순간이동한다. 사방으로 튀어 보이던 원인이다.
            if (distance >= NEAR_ZERO_PX) {
                separationDirection = if (delta >= 0f) 1f else -1f
            }

            // 손으로 붙여 놓는 중에는 놀라거나 튕기지 않는다. 사용자가 일부러 하는
            // 일인데 거기에 튕겨 나가는 힘까지 더하면 화면이 사방으로 튄다.
            if (!held && now - lastBumpAt > BUMP_INTERVAL_MS) {
                lastBumpAt = now
                onCharactersTouched(a, b, now, separationDirection)
            }
        }
        charactersTouching = touching

        if (distance >= gap) return

        // 손에 잡혀 있는 동안에는 아무도 밀지 않는다.
        // 사용자가 일부러 붙여 놓는 중인데 상대를 밀어내면, 한쪽을 옮기는 것만으로
        // 다른 쪽이 화면을 가로질러 쫓겨난다. 손을 놓으면 그때 알아서 비켜선다.
        if (held) return

        // 겹친 만큼을 밀어내되, 한 프레임에 움직일 수 있는 거리를 제한한다.
        // 제한이 없으면 손을 놓는 순간 상대가 순간이동한 것처럼 튄다.
        val overlap = (gap - distance).coerceAtMost(MAX_SEPARATION_STEP_PX)
        val push = overlap * separationDirection
        shiftRuntime(a, -push / 2f, 0f)
        // a 가 화면 끝에 막혀 못 비킨 만큼은 b 가 대신 더 비켜 준다.
        shiftRuntime(b, push + lastShiftX, 0f)
    }

    /**
     * 캐릭터를 옮긴다. 걷는 중이면 목적지도 같이 옮겨야 한다.
     * 목적지를 그대로 두면 다음 프레임에 원래 자리로 끌려가 덜덜 떨린다.
     *
     * 실제로 옮겨진 가로 거리는 [lastShiftX] 에 남긴다. 화면 끝에 막혀
     * 부탁한 만큼 못 갔을 수 있어서, 부르는 쪽이 그 차이를 알아야 한다.
     */
    private fun shiftRuntime(runtime: Runtime, dx: Float, dy: Float) {
        lastShiftX = 0f
        if (dx == 0f && dy == 0f) return
        val fromX = runtime.window.anchorX
        val fromY = runtime.window.anchorY
        val toX = clampX(fromX + dx, runtime)
        val toY = clampY(fromY + dy)
        val movedX = toX - fromX
        val movedY = toY - fromY
        if (movedX == 0f && movedY == 0f) return

        runtime.window.setAnchor(toX, toY)
        runtime.walkFromX += movedX
        runtime.walkToX += movedX
        runtime.walkFromY += movedY
        runtime.walkToY += movedY
        lastShiftX = movedX
    }

    /** 부딪혀 튕겨 나가는 중이면 그만큼 더 밀린다. 점점 느려지다 멈춘다. */
    private fun applyKnockback(runtime: Runtime) {
        if (runtime.interactionHeld || runtime.flying || abs(runtime.knockVX) < KNOCK_MIN_PX) {
            runtime.knockVX = 0f
            return
        }
        shiftRuntime(runtime, runtime.knockVX, 0f)
        runtime.knockVX *= KNOCK_DECAY
    }

    /**
     * 제 발로 돌아다니다 서로 부딪혔다. 놀라며 콩 부딪히고 통 튕겨 나간다.
     * [direction] 은 A 에서 B 를 향하는 방향(+1 이면 B 가 오른쪽)이다.
     * 손으로 붙여 놓는 중에는 부르지 않는다.
     */
    private fun onCharactersTouched(a: Runtime, b: Runtime, now: Long, direction: Float) {
        spawnEffect(a, EffectKind.EXCLAIM, 1, now)
        spawnEffect(b, EffectKind.EXCLAIM, 1, now)

        faceTo(a, direction >= 0f, now)
        faceTo(b, direction < 0f, now)

        // 튕겨 나가는 세기는 캐릭터 크기에 비례시킨다. 큰 캐릭터가 조금만
        // 밀려나면 부딪힌 티가 나지 않는다.
        val impulse = (a.displayWidth + b.displayWidth) * KNOCK_IMPULSE_RATIO
        a.knockVX = -direction * impulse
        b.knockVX = direction * impulse
        startAction(a, CharacterAction.BUMP, now)
        startAction(b, CharacterAction.BUMP, now)
    }

    /** 둘이 충분히 가까우면 '마주쳤을 때' 장면을 시작해 본다. */
    private fun tryMeetScene(now: Long) {
        val a = runtimeA?.takeIf { it.visible } ?: return
        val b = runtimeB?.takeIf { it.visible } ?: return
        if (settings.mode != OverlayMode.PAIR) return
        if (a.interactionHeld || b.interactionHeld) return

        val gap = minSeparationX(a, b)
        if (gap <= 0f) return
        if (abs(b.window.anchorX - a.window.anchorX) > gap * MEET_DISTANCE_FACTOR) return
        // 위아래로 너무 벌어져 있으면 마주쳤다고 보기 어렵다.
        val reach = (a.displayHeight + b.displayHeight) * MEET_HEIGHT_FACTOR
        if (abs(b.window.anchorY - a.window.anchorY) > reach) return

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
            nearEdge = isNearEdge(runtime),
            // 놀라거나 부딪힌 직후에 곧바로 졸면 뜬금없다.
            justStartled = runtime.action == CharacterAction.BUMP ||
                runtime.action == CharacterAction.SURPRISED
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
        spawnMood(runtime, action, now)
        applyExpression(runtime, action, now)

        if (action == CharacterAction.DANGLE) {
            runtime.swingDeg = 0f
            runtime.swingVel = 0f
        }

        when (action) {
            CharacterAction.WALK -> setupWalk(runtime, now)
            CharacterAction.APPROACH -> setupApproach(runtime, now)
            else -> Unit
        }

        // 제자리 동작일 때는 짝을 바라본다.
        // 예전에는 쳐다보기 같은 몇몇 동작만 상대를 봤고, 나머지는 마지막으로
        // 걷던 방향을 그대로 보고 있었다. 그래서 둘이 서로 등을 지고 서 있었다.
        // 걷는 동작은 가는 방향을, 벽에 부딪힌 순간은 돌아서는 방향을 유지한다.
        if (!action.moves && action != CharacterAction.BUMP) {
            faceOther(runtime, now)
        }
    }

    /**
     * 짝이 있으면 그쪽을 바라본다.
     *
     * 거의 같은 자리에 있을 때는 그대로 둔다. 조금만 흔들려도 좌우가 뒤집혀
     * 몸을 계속 돌리게 되기 때문이다.
     */
    private fun faceOther(runtime: Runtime, now: Long) {
        val other = otherOf(runtime) ?: return
        val delta = other.window.anchorX - runtime.window.anchorX
        if (abs(delta) < runtime.displayWidth * FACE_DEADZONE_RATIO) return
        faceTo(runtime, delta >= 0f, now)
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

        // 좌우로만 왔다 갔다 하면 만날 일이 없다. 위아래로도 눈에 띄게 자리를 옮긴다.
        // 활동적인 캐릭터일수록 더 크게 움직인다.
        val energy = runtime.entity.traitEnergy.coerceIn(0, 100) / 100f
        val drift = screenHeight * VERTICAL_DRIFT_RATIO *
            (0.4f + 0.6f * settings.activity) * (0.6f + 0.8f * energy)
        runtime.walkFromY = runtime.window.anchorY
        runtime.walkToY = clampY(runtime.window.anchorY + (Random.nextFloat() - 0.5f) * 2f * drift)

        faceTo(runtime, clamped >= runtime.walkFromX, now)
        // 가고 싶은 곳까지 못 갔다면 화면 끝에 막힌 것이다.
        runtime.willHitWall = abs(desired - clamped) > WALL_TOLERANCE_PX
    }

    /**
     * 상대에게 다가간다.
     *
     * 상대와 **같은 높이로 내려가거나 올라가서** 그 옆에 선다.
     * 예전에는 가로로만 갔다. 그래서 둘이 위아래로 떨어져 있으면 영영 만나지
     * 못하고 각자 옆으로만 걸어 다녔다.
     */
    private fun setupApproach(runtime: Runtime, now: Long) {
        val other = otherOf(runtime)
        runtime.walkFromX = runtime.window.anchorX
        runtime.walkFromY = runtime.window.anchorY
        runtime.willHitWall = false

        if (other == null) {
            runtime.walkToX = runtime.window.anchorX
            runtime.walkToY = runtime.window.anchorY
            return
        }

        // 서로 밀어내는 최소 간격보다 살짝 넓게 선다. 그래야 다가간 뒤에
        // 밀려나며 덜컥거리지 않는다.
        val gap = minSeparationX(runtime, other) * APPROACH_GAP_FACTOR
        // 지금 내가 있는 쪽 옆에 선다. 상대를 가로질러 반대편으로 가지 않는다.
        val side = if (runtime.window.anchorX >= other.window.anchorX) 1f else -1f

        runtime.walkToX = clampX(other.window.anchorX + side * gap, runtime)
        runtime.walkToY = clampY(other.window.anchorY)
        // 다 가서는 상대를 바라본다.
        faceTo(runtime, other.window.anchorX >= runtime.walkToX, now)
    }

    private fun isNearEdge(runtime: Runtime): Boolean {
        if (screenWidth <= 0f) return false
        val x = runtime.window.anchorX
        val margin = runtime.displayWidth * 0.6f + screenWidth * 0.03f
        return x < margin || x > screenWidth - margin
    }

    /** 표시는 설정에서 끌 수 있다. 띄우는 곳은 전부 이 창구를 거친다. */
    private fun spawnEffect(runtime: Runtime, kind: EffectKind, count: Int, now: Long) {
        if (!settings.effectsEnabled || !runtime.visible) return
        runtime.effects.spawn(kind, count, now)
    }

    /** 짝. 숨겨져 있거나 한 명만 쓰는 모드라면 없는 것으로 본다. */
    private fun otherOf(runtime: Runtime): Runtime? =
        (if (runtime === runtimeA) runtimeB else runtimeA)?.takeIf { it.visible }

    // ---------------------------------------------------------------- 터치

    /**
     * 손가락이 닿았다. 끌기로 갈지 쓰다듬기로 갈지 아직 모르므로,
     * 어느 쪽이든 쓸 수 있게 기준 좌표를 지금 잡아 둔다.
     */
    override fun onTouchDown(slot: CharacterWindow.Slot, rawX: Float, rawY: Float) {
        val runtime = runtimeOf(slot) ?: return
        // 날아가는 중에 붙잡으면 그 자리에서 멈춘다.
        runtime.flying = false
        runtime.flyVX = 0f
        runtime.flyVY = 0f
        runtime.dragVelocityY = 0f
        runtime.heldAnchorX = runtime.window.anchorX
        runtime.heldAnchorY = runtime.window.anchorY
        runtime.grabOffsetX = runtime.window.anchorX - rawX
        runtime.grabOffsetY = runtime.window.anchorY - rawY
        runtime.dragVelocity = 0f
    }

    /** 화면을 돌리면 휘청한다. 화면 크기가 달라진 것으로 알아챈다. */
    private fun onScreenRotated() {
        onDeviceEvent(DeviceEvent.ROTATED)
    }

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

    /**
     * 톡톡 두 번. 한 번 칠 때보다 크게 반응하고, 짝도 같이 쳐다본다.
     */
    override fun onDoubleTap(slot: CharacterWindow.Slot) {
        val runtime = runtimeOf(slot) ?: return
        val now = System.currentTimeMillis()
        react(runtime, DeviceEvent.DOUBLE_TAP, now)

        otherOf(runtime)?.let { other ->
            if (!other.interactionHeld) {
                faceTo(other, runtime.window.anchorX >= other.window.anchorX, now)
                startAction(other, CharacterAction.LOOK_AT, now)
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
        runtime.knockVX = 0f
        // 기준 좌표는 손가락이 닿을 때 이미 잡아 두었다.
        // 한쪽을 붙잡으면 둘이 맞춰 가던 장면을 이어갈 수 없다.
        director.abandonCurrentScript()
        startAction(runtime, CharacterAction.DANGLE, now)

        if (settings.linkedDrag) {
            otherOf(runtime)?.let { other ->
                other.interactionHeld = true
                other.knockVX = 0f
                grab(other, rawX, rawY)
                startAction(other, CharacterAction.DANGLE, now)
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
        val thrown = tryThrow(runtimeOf(slot))
        releaseHold()
        if (!thrown) persistPositions()
        // 내려놓은 자리를 기준으로 둘 다 다시 서로를 본다.
        val now = System.currentTimeMillis()
        forEachRuntime { if (it.visible) faceOther(it, now) }
        // 다른 캐릭터 옆에 데려다 놓았다면 서로 반응한다.
        tryMeetScene(System.currentTimeMillis())
    }

    override fun onPetStart(slot: CharacterWindow.Slot) {
        val runtime = runtimeOf(slot) ?: return
        val now = System.currentTimeMillis()
        // 쓰다듬기로 판정되기 전까지 조금 끌려간 만큼을 되돌린다.
        // 한 번에 되돌리면 순간이동처럼 보이므로 출발점만 기억해 두고,
        // updateRuntime 이 짧게 미끄러뜨려 제자리로 돌려놓는다.
        runtime.petReturnFromX = runtime.window.anchorX
        runtime.petReturnFromY = runtime.window.anchorY
        runtime.knockVX = 0f
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

    /**
     * 손을 뗄 때 세게 뿌렸으면 던져진 것으로 본다.
     *
     * 살짝 내려놓는 것과 구별해야 한다. 그냥 놓을 때마다 바닥으로 떨어지면
     * 화면 위쪽에 캐릭터를 둘 수 없어 불편하다. 그래서 **빠르게 뿌렸을 때만**
     * 날아가고, 천천히 내려놓으면 그 자리에 그대로 선다.
     */
    private fun tryThrow(runtime: Runtime?): Boolean {
        if (runtime == null || !runtime.visible) return false
        val speed = hypot(runtime.dragVelocity, runtime.dragVelocityY)
        if (speed < THROW_MIN_SPEED_PX) return false

        runtime.flying = true
        runtime.flyVX = runtime.dragVelocity * THROW_BOOST
        runtime.flyVY = runtime.dragVelocityY * THROW_BOOST
        runtime.interactionHeld = false
        startAction(runtime, CharacterAction.FALL, System.currentTimeMillis())
        return true
    }

    /**
     * 날아가는 중의 한 프레임. 중력을 받아 아래로 휘고, 바닥과 벽에서 튄다.
     * 다 튀고 멈추면 착지 연출을 하고 평소대로 돌아간다.
     */
    private fun updateFlight(runtime: Runtime, now: Long) {
        runtime.flyVY += GRAVITY_PX
        runtime.flyVX *= AIR_DRAG

        val floor = screenHeight * FLOOR_RATIO
        var x = runtime.window.anchorX + runtime.flyVX
        var y = runtime.window.anchorY + runtime.flyVY

        // 화면 좌우 벽에서 튕긴다.
        val clampedX = clampX(x, runtime)
        if (clampedX != x) {
            x = clampedX
            runtime.flyVX = -runtime.flyVX * WALL_BOUNCE
            spawnEffect(runtime, EffectKind.EXCLAIM, 1, now)
        }

        var landed = false
        if (y >= floor) {
            y = floor
            if (abs(runtime.flyVY) > LAND_STOP_PX) {
                // 아직 튈 힘이 남았다.
                runtime.flyVY = -abs(runtime.flyVY) * FLOOR_BOUNCE
                runtime.flyVX *= FLOOR_FRICTION
            } else {
                landed = true
            }
        }

        runtime.window.setAnchor(x, clampY(y))
        // 걷기 목적지도 지금 자리로 맞춰 둔다. 두면 착지 후 원래 가던 곳으로 끌려간다.
        runtime.walkFromX = x
        runtime.walkToX = x
        runtime.walkFromY = runtime.window.anchorY
        runtime.walkToY = runtime.window.anchorY

        if (!landed) {
            val spin = (runtime.flyVX * SPIN_PER_PX)
                .coerceIn(-MAX_SWING_DEG, MAX_SWING_DEG)
            val pose = PoseCalculator.fallPose(spin)
            runtime.lastPose = pose
            runtime.window.setPose(pose)
            return
        }

        runtime.flying = false
        runtime.flyVX = 0f
        runtime.flyVY = 0f
        spawnEffect(runtime, EffectKind.EXCLAIM, 1, now)
        startAction(runtime, CharacterAction.BUMP, now)
        persistPositions()
    }

    private fun releaseHold() {
        val now = System.currentTimeMillis()
        forEachRuntime { runtime ->
            if (runtime.interactionHeld) {
                runtime.interactionHeld = false
                runtime.swingDeg = 0f
                runtime.swingVel = 0f
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
        val previousX = runtime.window.anchorX
        val previousY = runtime.window.anchorY
        val targetX = clampX(rawX + runtime.grabOffsetX, runtime)
        runtime.window.setAnchor(targetX, clampY(rawY + runtime.grabOffsetY))

        // 매달려 흔들리는 정도를 정하기 위해 끌리는 속도를 기억한다.
        // 손 떨림이 그대로 흔들림이 되지 않도록 조금씩 섞는다.
        val delta = targetX - previousX
        runtime.dragVelocity = runtime.dragVelocity * (1f - VELOCITY_SMOOTHING) +
            delta * VELOCITY_SMOOTHING

        // 던질 때 쓸 세로 속도도 같은 방식으로 기억한다.
        val deltaY = runtime.window.anchorY - previousY
        runtime.dragVelocityY = runtime.dragVelocityY * (1f - VELOCITY_SMOOTHING) +
            deltaY * VELOCITY_SMOOTHING
    }

    private fun runtimeOf(slot: CharacterWindow.Slot): Runtime? = when (slot) {
        CharacterWindow.Slot.A -> runtimeA
        CharacterWindow.Slot.B -> runtimeB
    }

    // ---------------------------------------------------------------- 화면 경계

    private fun refreshScreenSize() {
        val beforeWidth = screenWidth
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width().toFloat()
            screenHeight = bounds.height().toFloat()
        } else {
            val metrics = context.resources.displayMetrics
            screenWidth = metrics.widthPixels.toFloat()
            screenHeight = metrics.heightPixels.toFloat()
        }
        // 가로폭이 달라졌으면 화면을 돌린 것이다. 처음 재는 때는 빼고 알린다.
        if (beforeWidth > 0f && beforeWidth != screenWidth) {
            onScreenRotated()
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
        private const val VERTICAL_DRIFT_RATIO = 0.18f

        /** 두 캐릭터 사이 최소 가로 간격(두 캐릭터 가로폭 합 대비). */
        private const val OVERLAP_GAP_RATIO = 0.42f

        /** 다가갈 때는 최소 간격보다 살짝 넓게 선다. */
        private const val APPROACH_GAP_FACTOR = 1.15f

        /** 떨어졌다고 볼 거리. 닿는 기준(1)보다 넉넉해야 경계에서 떨리지 않는다. */
        private const val TOUCH_RELEASE = 1.2f

        /** 이 시간 안에는 다시 부딪히지 않는다. */
        private const val BUMP_INTERVAL_MS = 900L

        /** 튕겨 나가는 세기(두 캐릭터 가로폭 합 대비, 프레임당 이동량). */
        private const val KNOCK_IMPULSE_RATIO = 0.022f

        /** 튕김이 잦아드는 속도. */
        private const val KNOCK_DECAY = 0.86f

        /** 이보다 느려지면 멈춘 것으로 본다(px/프레임). */
        private const val KNOCK_MIN_PX = 0.3f

        /** 방향을 뽑을 수 없을 만큼 가까운지 판단하는 거리(px). */
        private const val NEAR_ZERO_PX = 1f

        /** 한 프레임에 밀어낼 수 있는 최대 거리(px). 넘으면 순간이동처럼 보인다. */
        private const val MAX_SEPARATION_STEP_PX = 14f

        /** 이 거리 안이면 '마주쳤다'고 본다. */
        private const val MEET_DISTANCE_FACTOR = 1.6f

        /** 마주쳤다고 보려면 위아래로도 이 정도 안에 있어야 한다(키 합 대비). */
        private const val MEET_HEIGHT_FACTOR = 0.3f

        /** 몸을 돌리는 데 걸리는 시간. */
        private const val FLIP_MS = 220L

        /** 이보다 가까이 겹쳐 서 있으면 어느 쪽을 볼지 다시 정하지 않는다. */
        private const val FACE_DEADZONE_RATIO = 0.25f

        /** 끌리는 속도를 얼마나 부드럽게 섞을지. 1 이면 손 떨림이 그대로 드러난다. */
        private const val VELOCITY_SMOOTHING = 0.35f

        /** 손가락이 멈추면 흔들림이 잦아드는 속도. */
        private const val VELOCITY_DECAY = 0.88f

        /**
         * 1px 끌릴 때 몇 도나 기울지.
         * 예전 값(0.35)으로는 아주 빠르게 끌어야 겨우 몇 도 기울어, 매달렸는지
         * 그냥 옮겨지는지 구별이 되지 않았다.
         */
        private const val SWING_PER_PX = 1.2f

        private const val MAX_SWING_DEG = 12f

        /** 그네가 제자리로 당겨지는 힘. 클수록 빨리 따라간다. */
        private const val SWING_STIFFNESS = 0.22f

        /** 그네가 잦아드는 정도. 1 에 가까울수록 오래 흔들린다. */
        private const val SWING_DAMPING = 0.9f

        /** 쓰다듬기로 바뀔 때 제자리로 미끄러져 돌아오는 시간. */
        private const val PET_RETURN_MS = 180L

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

        /**
         * 손을 뗄 때 이보다 빠르게 뿌려야 던진 것으로 본다(px/프레임).
         * 그냥 내려놓을 때마다 바닥으로 떨어지면 화면 위쪽에 둘 수가 없다.
         */
        private const val THROW_MIN_SPEED_PX = 12f

        /** 던질 때 손가락 속도에 곱하는 값. 1 이면 손 속도 그대로다. */
        private const val THROW_BOOST = 1.6f

        /** 한 프레임마다 아래로 더해지는 속도(px/프레임^2). */
        private const val GRAVITY_PX = 0.9f

        /** 공기 저항. 1 이면 가로 속도가 줄지 않는다. */
        private const val AIR_DRAG = 0.99f

        /** 벽과 바닥에서 튕길 때 남는 속도의 비율. */
        private const val WALL_BOUNCE = 0.5f
        private const val FLOOR_BOUNCE = 0.45f

        /** 바닥에 닿을 때 가로로 미끄러지는 정도. */
        private const val FLOOR_FRICTION = 0.6f

        /** 이보다 느리게 바닥에 닿으면 멈춘 것으로 본다. */
        private const val LAND_STOP_PX = 4f

        /** 날아갈 때 1px 속도마다 몇 도나 도는지. */
        private const val SPIN_PER_PX = 0.5f

        private const val EFFECT_WIDTH_FACTOR = 1.9f

        /** 표시 창 높이(캐릭터 키 대비). 머리 위와 얼굴 위를 함께 덮는다. */
        private const val EFFECT_HEIGHT_FACTOR = 1.15f

        /**
         * 표시 창 아래쪽이 캐릭터 머리를 덮는 정도(캐릭터 키 대비).
         * 기호가 바로 머리 위에서 떠오르고, 땀처럼 붙는 기호는 그림 위에 얹힌다.
         */
        private const val EFFECT_OVERLAP_RATIO = 0.45f
        private const val MIN_EFFECT_SIZE_PX = 120

        /** 기호 하나의 기준 크기(dp). 두 캐릭터가 같은 값을 써야 짝이 맞아 보인다. */
        private const val EFFECT_UNIT_DP = 26f

        /** 그림을 읽을 때 화면 크기 대비 남겨 둘 여유. 크기 슬라이더를 올려도 안 흐려진다. */
        private const val BITMAP_HEADROOM = 2f

        /** 표정을 바꾼 뒤 이 시간 안에는 기본 얼굴로 되돌리지 않는다. */
        private const val EXPRESSION_HOLD_MS = 1_200L
    }
}
