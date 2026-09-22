package com.pairplay.app

import com.pairplay.app.data.SceneEntity
import com.pairplay.app.data.SceneTrigger
import com.pairplay.app.engine.CharacterAction
import com.pairplay.app.engine.Performer
import com.pairplay.app.engine.SceneCodec
import com.pairplay.app.engine.ScriptStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 사용자가 만든 장면을 저장했다 다시 읽는 부분.
 * 여기가 깨지면 공들여 만든 장면이 통째로 사라지므로 꼼꼼히 확인한다.
 */
class SceneCodecTest {

    @Test
    fun `저장했다 읽으면 그대로 돌아온다`() {
        val steps = listOf(
            ScriptStep(Performer.A, CharacterAction.LOOK_AT),
            ScriptStep(Performer.B, CharacterAction.APPROACH, 1_500L),
            ScriptStep(Performer.BOTH, CharacterAction.REST, 3_000L)
        )
        val restored = SceneCodec.decodeSteps(SceneCodec.encodeSteps(steps))
        assertEquals(steps, restored)
    }

    @Test
    fun `알아볼 수 없는 줄은 버리고 나머지는 살린다`() {
        // 앱을 되돌려 설치했거나 값이 깨졌을 때 장면 전체를 잃으면 안 된다.
        val encoded = listOf(
            "A:look_at:0",
            "쓰레기",
            "C:look_at:0",          // 없는 역할
            "A:존재하지_않는_동작:0",  // 없는 동작
            "",
            "B:jump:900"
        ).joinToString("\n")

        val steps = SceneCodec.decodeSteps(encoded)
        assertEquals(2, steps.size)
        assertEquals(CharacterAction.LOOK_AT, steps[0].action)
        assertEquals(CharacterAction.JUMP, steps[1].action)
        assertEquals(900L, steps[1].durationMs)
    }

    @Test
    fun `길이를 적지 않으면 기본 길이를 쓴다`() {
        val steps = SceneCodec.decodeSteps("A:jump:0")
        assertEquals(CharacterAction.JUMP.defaultDurationMs, steps.first().resolvedDuration())
    }

    @Test
    fun `마디가 없는 장면은 돌릴 수 없다`() {
        val empty = SceneEntity(
            name = "빈 장면",
            trigger = SceneTrigger.IDLE_TIMER.name,
            orderedActions = ""
        )
        assertNull(SceneCodec.toScript(empty))
    }

    @Test
    fun `사용자 장면이 기본 장면보다 우선한다`() {
        val entity = SceneEntity(
            id = 7,
            name = "내 장면",
            trigger = SceneTrigger.IDLE_TIMER.name,
            orderedActions = "A:look_at:0\nB:approach:0",
            priority = 0
        )
        val script = SceneCodec.toScript(entity)
        assertNotNull(script)
        assertTrue(
            "사용자가 만든 장면이 기본 장면에 밀리면 안 됩니다",
            script!!.priority > 3
        )
        assertEquals(SceneCodec.userScriptId(7), script.id)
        assertTrue(SceneCodec.isUserScript(script.id))
    }

    @Test
    fun `혼자 하는 장면은 상대를 요구하지 않는다`() {
        val solo = SceneEntity(
            name = "혼자",
            trigger = SceneTrigger.IDLE_TIMER.name,
            orderedActions = "A:walk:0\nA:doze:0"
        )
        assertTrue(SceneCodec.toScript(solo)?.requiresPartner == false)

        val together = SceneEntity(
            name = "둘이",
            trigger = SceneTrigger.IDLE_TIMER.name,
            orderedActions = "A:walk:0\nBOTH:rest:0"
        )
        assertTrue(SceneCodec.toScript(together)?.requiresPartner == true)
    }

    @Test
    fun `쿨다운이 너무 짧으면 최소값으로 올린다`() {
        val entity = SceneEntity(
            name = "빠른 장면",
            trigger = SceneTrigger.TAP_CHARACTER.name,
            orderedActions = "A:jump:0",
            cooldownMs = 0
        )
        assertEquals(SceneCodec.MIN_COOLDOWN_MS, SceneCodec.toScript(entity)?.cooldownMs)
    }

    @Test
    fun `기본 뼈대는 바로 쓸 수 있다`() {
        val steps = SceneCodec.defaultSteps()
        assertTrue(steps.isNotEmpty())
        val restored = SceneCodec.decodeSteps(SceneCodec.encodeSteps(steps))
        assertEquals(steps, restored)
    }
}
