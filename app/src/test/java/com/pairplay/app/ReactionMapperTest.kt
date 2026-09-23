package com.pairplay.app

import com.pairplay.app.engine.CharacterAction
import com.pairplay.app.engine.CharacterTraits
import com.pairplay.app.engine.DeviceEvent
import com.pairplay.app.engine.EffectKind
import com.pairplay.app.engine.ReactionMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 휴대폰에 한 행동(흔들기·충전기·이어폰 등)에 대한 반응.
 *
 * 같은 일이 벌어져도 성격에 따라 다르게 반응해야 둘이 달라 보인다.
 * 사용자가 금지한 동작은 어떤 경로로도 나오면 안 된다.
 */
class ReactionMapperTest {

    private val plain = CharacterTraits()
    private val playful = CharacterTraits(mischief = 90)
    private val timid = CharacterTraits(shyness = 90)

    @Test
    fun `모든 사건에 반응이 있다`() {
        for (event in DeviceEvent.entries) {
            val reaction = ReactionMapper.forEvent(event, plain)
            assertTrue(
                "${event.id} 에 반응이 없습니다",
                reaction.effectCount >= 1
            )
        }
    }

    @Test
    fun `흔들면 성격에 따라 다르게 반응한다`() {
        val a = ReactionMapper.forEvent(DeviceEvent.SHAKE, playful)
        val b = ReactionMapper.forEvent(DeviceEvent.SHAKE, timid)
        assertEquals(CharacterAction.JUMP, a.action)
        assertEquals(CharacterAction.SHY, b.action)
        assertNotEquals(a.action, b.action)
    }

    @Test
    fun `톡톡 두 번은 애정 표현이다`() {
        val reaction = ReactionMapper.forEvent(DeviceEvent.DOUBLE_TAP, plain)
        assertEquals(EffectKind.HEART, reaction.effect)
        assertTrue("두 번 톡톡인데 표시가 너무 적습니다", reaction.effectCount >= 2)
    }

    @Test
    fun `이어폰을 꽂으면 음악을 기대한다`() {
        val reaction = ReactionMapper.forEvent(DeviceEvent.HEADSET_ON, plain)
        assertEquals(CharacterAction.RHYTHM, reaction.action)
        assertEquals(EffectKind.NOTE, reaction.effect)
    }

    @Test
    fun `금지한 동작은 어떤 사건에서도 나오지 않는다`() {
        // 사용자가 '뛰기' 와 '장난' 을 막아 두었다.
        val blocked = CharacterTraits(
            mischief = 90,
            energy = 90,
            warmth = 90,
            blockedActions = setOf(CharacterAction.JUMP.id, CharacterAction.TEASE.id)
        )
        for (event in DeviceEvent.entries) {
            val action = ReactionMapper.forEvent(event, blocked).action
            assertTrue(
                "${event.id} 에서 금지한 ${action.id} 이(가) 나왔습니다",
                blocked.allows(action)
            )
        }
    }

    @Test
    fun `전부 막아 두어도 터지지 않는다`() {
        val everything = CharacterTraits(
            blockedActions = CharacterAction.entries.map { it.id }.toSet()
        )
        for (event in DeviceEvent.entries) {
            // 고를 수 있는 동작이 하나도 없으면 가만히 있는 것으로 떨어진다.
            ReactionMapper.forEvent(event, everything)
        }
    }
}
