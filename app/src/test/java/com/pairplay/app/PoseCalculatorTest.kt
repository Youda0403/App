package com.pairplay.app

import com.pairplay.app.engine.CharacterAction
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

    @Test
    fun `모든 동작이 말이 되는 범위의 자세를 낸다`() {
        for (action in CharacterAction.entries) {
            for (step in 0..10) {
                val pose = PoseCalculator.pose(action, step / 10f, height)
                assertTrue(
                    "${action.id} 의 가로 배율이 이상합니다: ${pose.scaleX}",
                    pose.scaleX in -1.3f..1.3f && pose.scaleX != 0f
                )
                assertTrue(
                    "${action.id} 의 세로 배율이 이상합니다: ${pose.scaleY}",
                    pose.scaleY in 0.8f..1.3f
                )
                assertTrue(
                    "${action.id} 의 회전이 과합니다: ${pose.rotationDeg}",
                    pose.rotationDeg in -20f..20f
                )
                assertTrue(
                    "${action.id} 의 세로 이동이 과합니다: ${pose.offsetY}",
                    pose.offsetY in -height * 0.2f..height * 0.2f
                )
            }
        }
    }

    @Test
    fun `이동 동작만 창을 옮긴다`() {
        assertTrue(CharacterAction.WALK.moves)
        assertTrue(CharacterAction.APPROACH.moves)
        assertTrue(!CharacterAction.IDLE.moves)
        assertTrue(!CharacterAction.JUMP.moves)
    }
}
