package com.pairplay.app.engine

import kotlin.math.abs
import kotlin.math.sin

/**
 * 이미지 한 장으로 표현할 수 있는 기본 동작들.
 * 팔다리를 접는 동작은 한 장으로 만들 수 없으므로 넣지 않는다.
 * 사용자가 행동별 이미지를 등록하면 2차에서 그 슬롯을 쓴다.
 */
enum class CharacterAction(
    val id: String,
    val defaultDurationMs: Long,
    /** 이동 동작인지. 이동 동작은 창 위치 자체를 옮긴다. */
    val moves: Boolean = false
) {
    IDLE("idle", 2_400L),
    BREATHE("breathe", 3_200L),
    JUMP("jump", 900L),
    WALK("walk", 2_600L, moves = true),
    APPROACH("approach", 2_800L, moves = true),
    LOOK_AT("look_at", 1_400L),
    DOZE("doze", 6_000L),
    RHYTHM("rhythm", 2_000L),
    SURPRISED("surprised", 800L),

    /** 화면 가장자리에 부딪혔을 때. 살짝 찌그러졌다 돌아선다. */
    BUMP("bump", 700L),

    /** 가장자리에 기대어 쉰다. */
    LEAN("lean", 4_000L),

    /** 쓰다듬어 줄 때. 기분 좋게 몸을 흔든다. */
    PET("pet", 1_600L);

    companion object {
        fun fromId(id: String?): CharacterAction =
            entries.firstOrNull { it.id == id } ?: IDLE
    }
}

/**
 * 한 프레임에서 캐릭터를 어떻게 그릴지.
 * 가로 이동은 창을 옮겨서 처리하므로 여기에는 창 안에서의 변형만 담는다.
 */
data class Pose(
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotationDeg: Float = 0f,
    /** 창 안에서의 세로 미세 이동(px). 위로 갈수록 음수. */
    val offsetY: Float = 0f
) {
    companion object {
        val NEUTRAL = Pose()
    }
}

/**
 * 어떤 동작도 이 범위를 넘지 않는다는 약속.
 *
 * 창 여백을 이 값들로 계산하기 때문에 중요하다. 여기를 넘는 자세가 생기면
 * 캐릭터가 창 밖으로 잘려 나간다. 단위 테스트가 이 약속을 지키는지 검사한다.
 * 반대로 값을 키우면 창이 커져서 다른 앱의 터치를 더 많이 가로채므로,
 * 꼭 필요한 만큼만 잡는다.
 */
object PoseBounds {
    const val MAX_SCALE_X = 1.07f
    const val MAX_SCALE_Y = 1.08f

    /** 위로 뜨는 최대치 (캐릭터 높이 대비). */
    const val MAX_OFFSET_UP_RATIO = 0.05f

    /** 아래로 가라앉는 최대치 (캐릭터 높이 대비). */
    const val MAX_OFFSET_DOWN_RATIO = 0.03f

    const val MAX_ROTATION_DEG = 8f
}

/**
 * 동작 진행도(0~1)를 받아 자세를 계산한다.
 * [heightPx] 는 캐릭터 표시 높이로, 흔들림 폭을 크기에 비례시키는 데 쓴다.
 */
object PoseCalculator {

    fun pose(action: CharacterAction, progress: Float, heightPx: Float, seed: Float = 0f): Pose {
        val t = progress.coerceIn(0f, 1f)
        return when (action) {
            CharacterAction.IDLE, CharacterAction.BREATHE -> breathe(t, seed)
            CharacterAction.JUMP -> jumpPose(t)
            CharacterAction.WALK, CharacterAction.APPROACH -> walk(t, heightPx)
            CharacterAction.LOOK_AT -> lookAt(t)
            CharacterAction.DOZE -> doze(t, heightPx)
            CharacterAction.RHYTHM -> rhythm(t, heightPx)
            CharacterAction.SURPRISED -> surprised(t, heightPx)
            CharacterAction.BUMP -> bump(t)
            CharacterAction.LEAN -> lean(t, seed)
            CharacterAction.PET -> pet(t, heightPx)
        }
    }

    /**
     * 점프 높이(px). 창 자체를 올려 그리므로 창 여백에 영향받지 않는다.
     * 올라갔다 내려오는 포물선.
     */
    fun jumpHeight(progress: Float, heightPx: Float): Float {
        val t = progress.coerceIn(0f, 1f)
        return 0.28f * heightPx * (1f - (2f * t - 1f) * (2f * t - 1f))
    }

    private fun breathe(t: Float, seed: Float): Pose {
        val phase = (t + seed) * 2f * Math.PI.toFloat()
        val breath = sin(phase)
        return Pose(
            scaleX = 1f - breath * 0.012f,
            scaleY = 1f + breath * 0.018f,
            rotationDeg = sin(phase * 0.5f) * 1.2f
        )
    }

    /** 뛰어오를 때 살짝 늘어나고 착지하며 눌린다. */
    private fun jumpPose(t: Float): Pose {
        val stretch = sin(t * Math.PI.toFloat())
        return Pose(
            scaleX = 1f - stretch * 0.03f,
            scaleY = 1f + stretch * 0.05f
        )
    }

    private fun walk(t: Float, heightPx: Float): Pose {
        // 걸을 때 위아래로 살짝 튀고 몸이 진행 방향으로 기운다.
        val bob = abs(sin(t * 6f * Math.PI.toFloat()))
        return Pose(
            scaleY = 1f + bob * 0.02f,
            rotationDeg = sin(t * 6f * Math.PI.toFloat()) * 2.5f,
            offsetY = -bob * heightPx * 0.02f
        )
    }

    private fun lookAt(t: Float): Pose {
        // 고개를 돌리듯 좌우로 한 번 기울인다.
        val swing = sin(t * Math.PI.toFloat())
        return Pose(rotationDeg = swing * 6f)
    }

    private fun doze(t: Float, heightPx: Float): Pose {
        // 느리게 기울며 아래로 가라앉는다.
        val phase = sin(t * 2f * Math.PI.toFloat())
        return Pose(
            scaleY = 0.97f + phase * 0.01f,
            rotationDeg = 6f + phase * 1.5f,
            offsetY = heightPx * 0.015f
        )
    }

    private fun rhythm(t: Float, heightPx: Float): Pose {
        val phase = sin(t * 4f * Math.PI.toFloat())
        return Pose(
            scaleY = 1f + abs(phase) * 0.05f,
            rotationDeg = phase * 7f,
            offsetY = -abs(phase) * heightPx * 0.04f
        )
    }

    private fun surprised(t: Float, heightPx: Float): Pose {
        val pop = sin(t * Math.PI.toFloat())
        return Pose(
            scaleX = 1f + pop * 0.06f,
            scaleY = 1f + pop * 0.07f,
            offsetY = -pop * heightPx * 0.05f
        )
    }

    /** 벽에 부딪혀 찌그러졌다가 반대로 돌아서는 느낌. */
    private fun bump(t: Float): Pose {
        val squash = sin(t * Math.PI.toFloat())
        return Pose(
            scaleX = 1f + squash * 0.06f,
            scaleY = 1f - squash * 0.05f,
            rotationDeg = squash * -4f
        )
    }

    /** 가장자리에 비스듬히 기대어 쉰다. */
    private fun lean(t: Float, seed: Float): Pose {
        val sway = sin((t + seed) * 2f * Math.PI.toFloat())
        return Pose(
            scaleY = 1f + sway * 0.008f,
            rotationDeg = 5f + sway * 1.5f
        )
    }

    /** 쓰다듬어 줄 때 기분 좋게 몸을 흔든다. */
    private fun pet(t: Float, heightPx: Float): Pose {
        val wiggle = sin(t * 6f * Math.PI.toFloat())
        return Pose(
            scaleY = 1f + abs(wiggle) * 0.03f,
            rotationDeg = wiggle * 5f,
            offsetY = -abs(wiggle) * heightPx * 0.02f
        )
    }
}

/** 창 여백 계산 결과. */
data class WindowPadding(val x: Float, val y: Float)

/**
 * 캐릭터 창에 얼마만큼의 여백이 필요한지 계산한다.
 *
 * 여백은 그대로 '다른 앱의 터치를 가로채는 면적'이 되므로 넉넉히 잡으면 안 되고,
 * 모자라면 캐릭터가 잘려 보인다. 그래서 [PoseBounds] 가 약속한 최대 변형에서
 * 실제로 필요한 만큼만 계산한다.
 *
 * 회전과 확대의 기준점은 모두 발밑 가운데다. 그래서 어디가 가장 많이 튀어나오는지가
 * 캐릭터 비율에 따라 달라진다.
 * - 세로 확대는 위쪽으로만 커진다.
 * - 가로로는 발밑에서 가장 먼 머리 쪽 모서리가 가장 많이 밀려난다.
 * - 세로로는, 가로로 넓은 캐릭터일수록 회전 때문에 발밑 모서리가 크게 내려간다.
 *   (이걸 놓치면 납작한 캐릭터의 아래쪽이 잘린다)
 */
object WindowPaddingCalculator {

    /** 여백이 0 이 되지 않도록 하는 최소치(px). */
    const val MIN_PADDING_PX = 2f

    fun forCharacter(width: Float, height: Float): WindowPadding {
        if (width <= 0f || height <= 0f) {
            return WindowPadding(MIN_PADDING_PX, MIN_PADDING_PX)
        }

        val half = width / 2f
        val sinTheta = kotlin.math.sin(
            Math.toRadians(PoseBounds.MAX_ROTATION_DEG.toDouble())
        ).toFloat()

        // 회전 뒤 좌표: x' = x·cos - y·sin, y' = x·sin + y·cos (기준점은 발밑 가운데)
        // cos 는 1 이하이므로 1 로 잡으면 안전한 상한이 된다.

        // 가로로 가장 멀리 나가는 곳은 머리 쪽 모서리다.
        val padX = half * (PoseBounds.MAX_SCALE_X - 1f) +
            height * PoseBounds.MAX_SCALE_Y * sinTheta

        // 위로는 머리 쪽 모서리가, 아래로는 발밑 모서리가 가장 멀리 나간다.
        // 가로로 넓은 캐릭터일수록 발밑 모서리가 회전으로 크게 내려간다.
        val padTop = half * PoseBounds.MAX_SCALE_X * sinTheta +
            height * PoseBounds.MAX_SCALE_Y +
            height * PoseBounds.MAX_OFFSET_UP_RATIO -
            height
        val padBottom = half * PoseBounds.MAX_SCALE_X * sinTheta +
            height * PoseBounds.MAX_OFFSET_DOWN_RATIO

        return WindowPadding(
            x = padX.coerceAtLeast(MIN_PADDING_PX),
            // 세로 여백은 위아래가 같아야 그림이 창 한가운데에 온다.
            y = maxOf(padTop, padBottom).coerceAtLeast(MIN_PADDING_PX)
        )
    }
}
