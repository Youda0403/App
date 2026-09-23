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
    fun `흔들면 어지러워한다`() {
        // 사용자 요청: 흔들면 어지러워하는 게 자연스럽다.
        val reaction = ReactionMapper.forEvent(DeviceEvent.SHAKE, plain)
        assertEquals(CharacterAction.DIZZY, reaction.action)
        assertEquals(EffectKind.SWIRL, reaction.effect)

        // 아주 팔팔한 아이는 금방 털고 일어난다.
        val lively = ReactionMapper.forEvent(DeviceEvent.SHAKE, CharacterTraits(energy = 95))
        assertNotEquals(CharacterAction.DIZZY, lively.action)
    }

    @Test
    fun `뺏기면 아쉬워한다`() {
        // 사용자 요청: 분리될 때 어리둥절해하는 것보다 아쉬워하는 쪽이 낫다.
        for (event in listOf(DeviceEvent.CHARGER_OFF, DeviceEvent.HEADSET_OFF)) {
            val reaction = ReactionMapper.forEvent(event, plain)
            assertEquals(
                "${event.id} 는 시무룩해야 합니다",
                CharacterAction.SULK,
                reaction.action
            )
            assertNotEquals(EffectKind.QUESTION, reaction.effect)
        }
    }

    /**
     * 만화에서 ✨ 는 '신난다' 가 아니라 '번뜩였다' 는 뜻이다.
     * 기쁨·신남은 꽃(FLOWER)이 맡는다.
     */
    @Test
    fun `반짝임은 번뜩이는 순간에만 쓴다`() {
        val sparkling = DeviceEvent.entries.filter {
            ReactionMapper.forEvent(it, playful).effect == EffectKind.SPARKLE
        }
        // 장난칠 생각이 떠오른 순간(톡톡 두 번 + 장난기) 하나뿐이어야 한다.
        assertEquals(listOf(DeviceEvent.DOUBLE_TAP), sparkling)

        // 신나는 일에는 꽃이 뜬다.
        assertEquals(
            EffectKind.FLOWER,
            ReactionMapper.forEvent(DeviceEvent.BATTERY_FULL, plain).effect
        )
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
