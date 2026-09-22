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
    SURPRISED("surprised", 800L);

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
 * 동작 진행도(0~1)를 받아 자세를 계산한다.
 * [heightPx] 는 캐릭터 표시 높이로, 흔들림 폭을 크기에 비례시키는 데 쓴다.
 */
object PoseCalculator {

    fun pose(action: CharacterAction, progress: Float, heightPx: Float, seed: Float = 0f): Pose {
        val t = progress.coerceIn(0f, 1f)
        return when (action) {
            CharacterAction.IDLE, CharacterAction.BREATHE -> breathe(t, seed)
            CharacterAction.JUMP -> Pose.NEUTRAL
            CharacterAction.WALK, CharacterAction.APPROACH -> walk(t, heightPx)
            CharacterAction.LOOK_AT -> lookAt(t)
            CharacterAction.DOZE -> doze(t, heightPx)
            CharacterAction.RHYTHM -> rhythm(t, heightPx)
            CharacterAction.SURPRISED -> surprised(t, heightPx)
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
            rotationDeg = 8f + phase * 2f,
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
            scaleY = 1f + pop * 0.10f,
            offsetY = -pop * heightPx * 0.05f
        )
    }
}
