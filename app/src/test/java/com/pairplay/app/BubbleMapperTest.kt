package com.pairplay.app

import com.pairplay.app.engine.BubbleMapper
import com.pairplay.app.engine.BubbleSymbol
import com.pairplay.app.engine.CharacterAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 이미지 한 장으로는 표정을 바꿀 수 없어서, 기분은 머리 위 말풍선으로 알린다.
 * "둘이 방금 뭘 주고받았구나" 를 알아볼 수 있어야 하므로 매핑이 중요하다.
 */
class BubbleMapperTest {

    @Test
    fun `마음이 드러나는 동작에는 표시가 붙는다`() {
        val expressive = listOf(
            CharacterAction.SURPRISED,
            CharacterAction.DOZE,
            CharacterAction.REST,
            CharacterAction.RHYTHM,
            CharacterAction.PET,
            CharacterAction.SHY,
            CharacterAction.GLARE,
            CharacterAction.TEASE,
            CharacterAction.GLANCE,
            CharacterAction.LOOK_AT,
            CharacterAction.BUMP
        )
        for (action in expressive) {
            assertNotNull(
                "${action.id} 는 기분이 드러나는 동작인데 표시가 없습니다",
                BubbleMapper.forAction(action, inScene = false)
            )
        }
    }

    @Test
    fun `그냥 서 있거나 걷는 것에는 표시가 붙지 않는다`() {
        // 일일이 표시하면 화면이 시끄러워진다.
        val quiet = listOf(
            CharacterAction.IDLE,
            CharacterAction.BREATHE,
            CharacterAction.WALK,
            CharacterAction.LEAN
        )
        for (action in quiet) {
            assertNull(
                "${action.id} 에 표시가 붙으면 화면이 시끄럽습니다",
                BubbleMapper.forAction(action, inScene = true)
            )
        }
    }

    @Test
    fun `다가가기와 점프는 장면 안에서만 알린다`() {
        assertNull(BubbleMapper.forAction(CharacterAction.APPROACH, inScene = false))
        assertNull(BubbleMapper.forAction(CharacterAction.JUMP, inScene = false))

        assertEquals(
            BubbleSymbol.HEART,
            BubbleMapper.forAction(CharacterAction.APPROACH, inScene = true)
        )
        assertEquals(
            BubbleSymbol.SPARKLE,
            BubbleMapper.forAction(CharacterAction.JUMP, inScene = true)
        )
    }

    @Test
    fun `기분이 동작과 맞는다`() {
        assertEquals(BubbleSymbol.SLEEP, BubbleMapper.forAction(CharacterAction.DOZE, false))
        assertEquals(BubbleSymbol.ANGER, BubbleMapper.forAction(CharacterAction.GLARE, false))
        assertEquals(BubbleSymbol.SWEAT, BubbleMapper.forAction(CharacterAction.SHY, false))
        assertEquals(BubbleSymbol.NOTE, BubbleMapper.forAction(CharacterAction.RHYTHM, false))
        assertEquals(BubbleSymbol.HEART, BubbleMapper.forAction(CharacterAction.PET, false))
        assertEquals(BubbleSymbol.EXCLAIM, BubbleMapper.forAction(CharacterAction.SURPRISED, false))
    }
}
