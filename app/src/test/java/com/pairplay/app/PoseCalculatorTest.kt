package com.pairplay.app

import com.pairplay.app.engine.CharacterAction
import com.pairplay.app.engine.PoseBounds
import com.pairplay.app.engine.PoseCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseCalculatorTest {

    private val height = 400f

    @Test
    fun `점프는 시작과 끝에서 바닥에 붙고 중간에 가장 높다`() {
        assertEquals(0f, PoseCalculator.jumpHeight(0f, height), 0.01f)
        assertEquals(0f, PoseCalculator.jumpHeight(1f, height), 0.01f)

        val peak = PoseCalculator.jumpHeight(0.5f, height)
        assertTrue("중간 높이가 0보다 커야 한다", peak > 0f)
        assertTrue("점프가 키의 절반을 넘으면 어색하다", peak < height * 0.5f)
    }

    @Test
    fun `진행도가 범위를 벗어나도 안전하게 잘린다`() {
        assertEquals(0f, PoseCalculator.jumpHeight(-5f, height), 0.01f)
        assertEquals(0f, PoseCalculator.jumpHeight(9f, height), 0.01f)
    }

    /**
     * 창 여백을 PoseBounds 로 계산하기 때문에, 어떤 동작도 이 약속을 넘으면 안 된다.
     * 넘는 순간 캐릭터가 창 밖으로 잘려 보인다.
     */
    @Test
    fun `모든 동작이 약속한 변형 범위 안에 있다`() {
        for (action in CharacterAction.entries) {
            for (step in 0..40) {
                val progress = step / 40f
                for (seed in listOf(0f, 0.33f, 0.66f, 0.99f)) {
                    val pose = PoseCalculator.pose(action, progress, height, seed)

                    assertTrue(
                        "${action.id} 의 가로 배율 ${pose.scaleX} 가 약속(${PoseBounds.MAX_SCALE_X})을 넘었습니다",
                        pose.scaleX <= PoseBounds.MAX_SCALE_X + EPSILON
                    )
                    assertTrue(
                        "${action.id} 의 세로 배율 ${pose.scaleY} 가 약속(${PoseBounds.MAX_SCALE_Y})을 넘었습니다",
                        pose.scaleY <= PoseBounds.MAX_SCALE_Y + EPSILON
                    )
                    assertTrue(
                        "${action.id} 의 배율이 0 이하가 되면 캐릭터가 사라집니다",
                        pose.scaleX > 0f && pose.scaleY > 0f
                    )
                    assertTrue(
                        "${action.id} 의 회전 ${pose.rotationDeg} 가 약속(${PoseBounds.MAX_ROTATION_DEG})을 넘었습니다",
                        kotlin.math.abs(pose.rotationDeg) <= PoseBounds.MAX_ROTATION_DEG + EPSILON
                    )
                    assertTrue(
                        "${action.id} 가 위로 너무 많이 떴습니다: ${pose.offsetY}",
                        -pose.offsetY <= height * PoseBounds.MAX_OFFSET_UP_RATIO + EPSILON
                    )
                    assertTrue(
                        "${action.id} 가 아래로 너무 많이 내려갔습니다: ${pose.offsetY}",
                        pose.offsetY <= height * PoseBounds.MAX_OFFSET_DOWN_RATIO + EPSILON
                    )
                }
            }
        }
    }

    @Test
    fun `이동 동작만 창을 옮긴다`() {
        assertTrue(CharacterAction.WALK.moves)
        assertTrue(CharacterAction.APPROACH.moves)
        assertTrue(!CharacterAction.IDLE.moves)
        assertTrue(!CharacterAction.JUMP.moves)
        assertTrue(!CharacterAction.PET.moves)
        assertTrue(!CharacterAction.BUMP.moves)
    }

    /**
     * 가만히 있는 동작은 지시받은 길이와 상관없이 숨 쉬는 속도가 같아야 한다.
     * 스케줄러가 상대를 기다리며 짧은 대기를 줄 때 덜덜 떠는 것처럼 보이던 문제가 있었다.
     */
    @Test
    fun `가만히 있는 동작은 반복 동작으로 표시된다`() {
        assertTrue(CharacterAction.IDLE.loops)
        assertTrue(CharacterAction.BREATHE.loops)
        assertTrue(CharacterAction.DOZE.loops)
        assertTrue(CharacterAction.REST.loops)
        assertTrue(CharacterAction.LEAN.loops)

        // 한 번만 하고 끝나는 동작은 반복으로 표시하면 안 된다.
        assertTrue(!CharacterAction.JUMP.loops)
        assertTrue(!CharacterAction.SURPRISED.loops)
        assertTrue(!CharacterAction.WALK.loops)
        assertTrue(!CharacterAction.BUMP.loops)
    }

    /** 매달린 자세도 창 여백 약속을 넘지 않아야 한다. 넘으면 그림이 잘린다. */
    @Test
    fun `매달린 자세가 약속 범위를 지킨다`() {
        for (swing in -120..120 step 5) {
            val pose = PoseCalculator.danglePose(swing.toFloat(), height)
            assertTrue(
                "회전이 약속을 넘었습니다: ${pose.rotationDeg}",
                kotlin.math.abs(pose.rotationDeg) <= PoseBounds.MAX_ROTATION_DEG + EPSILON
            )
            assertTrue(pose.scaleX <= PoseBounds.MAX_SCALE_X + EPSILON)
            assertTrue(pose.scaleY <= PoseBounds.MAX_SCALE_Y + EPSILON)
            assertTrue(pose.offsetY <= height * PoseBounds.MAX_OFFSET_DOWN_RATIO + EPSILON)
        }
    }

    private companion object {
        const val EPSILON = 0.001f
    }
}
