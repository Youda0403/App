package com.pairplay.app

import com.pairplay.app.engine.BubbleMapper
import com.pairplay.app.engine.BubbleSymbol
import com.pairplay.app.engine.CharacterAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 이미지 한 장으로는 표정을 바꿀 수 없어서, 기분은 머리 위 말풍선으로 알린다.
 *
 * 다만 너무 자주 띄우면 표시가 흔해져 오히려 재미가 없어진다.
 * 그래서 '짧고 분명한 순간' 에만 띄우는지도 함께 확인한다.
 */
class BubbleMapperTest {

    @Test
    fun `짧고 분명한 순간에는 표시가 붙는다`() {
        val moments = listOf(
            CharacterAction.SURPRISED,
            CharacterAction.BUMP,
            CharacterAction.PET,
            CharacterAction.SHY,
            CharacterAction.GLARE,
            CharacterAction.TEASE,
            CharacterAction.DOZE
        )
        for (action in moments) {
            assertNotNull(
                "${action.id} 는 알려 줄 만한 순간인데 표시가 없습니다",
                BubbleMapper.forAction(action, inScene = false)
            )
        }
    }

    @Test
    fun `자주 일어나는 동작에는 표시가 붙지 않는다`() {
        // 쳐다보기나 걷기까지 알리면 표시가 흔해져 눈에 들어오지 않는다.
        val common = listOf(
            CharacterAction.IDLE,
            CharacterAction.BREATHE,
            CharacterAction.WALK,
            CharacterAction.LEAN,
            CharacterAction.LOOK_AT,
            CharacterAction.GLANCE,
            CharacterAction.RHYTHM,
            CharacterAction.JUMP,
            CharacterAction.DANGLE
        )
        for (action in common) {
            assertNull(
                "${action.id} 에 표시가 붙으면 너무 자주 나옵니다",
                BubbleMapper.forAction(action, inScene = true)
            )
        }
    }

    @Test
    fun `함께하는 동작은 장면 안에서만 알린다`() {
        assertNull(BubbleMapper.forAction(CharacterAction.APPROACH, inScene = false))
        assertNull(BubbleMapper.forAction(CharacterAction.REST, inScene = false))

        assertEquals(
            BubbleSymbol.HEART,
            BubbleMapper.forAction(CharacterAction.APPROACH, inScene = true)
        )
        assertEquals(
            BubbleSymbol.HEART,
            BubbleMapper.forAction(CharacterAction.REST, inScene = true)
        )
    }

    @Test
    fun `기분이 동작과 맞는다`() {
        assertEquals(BubbleSymbol.SLEEP, BubbleMapper.forAction(CharacterAction.DOZE, false))
        assertEquals(BubbleSymbol.ANGER, BubbleMapper.forAction(CharacterAction.GLARE, false))
        assertEquals(BubbleSymbol.SWEAT, BubbleMapper.forAction(CharacterAction.SHY, false))
        assertEquals(BubbleSymbol.HEART, BubbleMapper.forAction(CharacterAction.PET, false))
        assertEquals(BubbleSymbol.EXCLAIM, BubbleMapper.forAction(CharacterAction.SURPRISED, false))
        assertEquals(BubbleSymbol.SPARKLE, BubbleMapper.forAction(CharacterAction.TEASE, false))
    }

    @Test
    fun `표시가 붙는 동작은 전체의 절반을 넘지 않는다`() {
        // 표시는 가끔 나와야 눈에 들어온다. 절반 넘게 붙으면 너무 잦다는 뜻이다.
        val withBubble = CharacterAction.entries.count {
            BubbleMapper.forAction(it, inScene = true) != null
        }
        assertTrue(
            "말풍선이 붙는 동작이 너무 많습니다 ($withBubble / ${CharacterAction.entries.size})",
            withBubble * 2 <= CharacterAction.entries.size
        )
    }
}
