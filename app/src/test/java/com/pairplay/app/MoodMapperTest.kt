package com.pairplay.app

import com.pairplay.app.engine.CharacterAction
import com.pairplay.app.engine.EffectKind
import com.pairplay.app.engine.MoodMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 이미지 한 장으로는 표정을 바꿀 수 없어서, 기분은 머리 위 기호로 알린다.
 * (말풍선은 캐릭터보다 커 보이고 두 캐릭터 크기가 다르면 짝이 안 맞아 보여서 뺐다)
 *
 * 다만 너무 자주 띄우면 기호가 흔해져 오히려 재미가 없어진다.
 * 그래서 '짧고 분명한 순간' 에만 띄우는지도 함께 확인한다.
 */
class MoodMapperTest {

    @Test
    fun `짧고 분명한 순간에는 기호가 붙는다`() {
        val moments = listOf(
            CharacterAction.SURPRISED,
            CharacterAction.PET,
            CharacterAction.SHY,
            CharacterAction.GLARE,
            CharacterAction.TEASE,
            CharacterAction.DOZE
        )
        for (action in moments) {
            assertNotNull(
                "${action.id} 는 알려 줄 만한 순간인데 기호가 없습니다",
                MoodMapper.forAction(action, inScene = false)
            )
        }
    }

    @Test
    fun `자주 일어나는 동작에는 기호가 붙지 않는다`() {
        // 쳐다보기나 걷기까지 알리면 기호가 흔해져 눈에 들어오지 않는다.
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
                "${action.id} 에 기호가 붙으면 너무 자주 나옵니다",
                MoodMapper.forAction(action, inScene = true)
            )
        }
    }

    @Test
    fun `부딪힘은 여기서 띄우지 않는다`() {
        // 부딪히는 그 순간에 컨트롤러가 직접 느낌표를 띄운다.
        // 여기서도 띄우면 느낌표가 두 개씩 뜬다.
        assertNull(MoodMapper.forAction(CharacterAction.BUMP, inScene = false))
        assertNull(MoodMapper.forAction(CharacterAction.BUMP, inScene = true))
    }

    @Test
    fun `함께하는 동작은 장면 안에서만 알린다`() {
        assertNull(MoodMapper.forAction(CharacterAction.APPROACH, inScene = false))
        assertNull(MoodMapper.forAction(CharacterAction.REST, inScene = false))

        assertEquals(
            EffectKind.HEART,
            MoodMapper.forAction(CharacterAction.APPROACH, inScene = true)
        )
        assertEquals(
            EffectKind.HEART,
            MoodMapper.forAction(CharacterAction.REST, inScene = true)
        )
    }

    @Test
    fun `기분이 동작과 맞는다`() {
        assertEquals(EffectKind.SLEEP, MoodMapper.forAction(CharacterAction.DOZE, false))
        assertEquals(EffectKind.ANGER, MoodMapper.forAction(CharacterAction.GLARE, false))
        assertEquals(EffectKind.SWEAT, MoodMapper.forAction(CharacterAction.SHY, false))
        assertEquals(EffectKind.HEART, MoodMapper.forAction(CharacterAction.PET, false))
        assertEquals(EffectKind.EXCLAIM, MoodMapper.forAction(CharacterAction.SURPRISED, false))
        assertEquals(EffectKind.SPARKLE, MoodMapper.forAction(CharacterAction.TEASE, false))
    }

    @Test
    fun `기호가 붙는 동작은 전체의 절반을 넘지 않는다`() {
        // 기호는 가끔 나와야 눈에 들어온다. 절반 넘게 붙으면 너무 잦다는 뜻이다.
        val withMood = CharacterAction.entries.count {
            MoodMapper.forAction(it, inScene = true) != null
        }
        assertTrue(
            "기호가 붙는 동작이 너무 많습니다 ($withMood / ${CharacterAction.entries.size})",
            withMood * 2 <= CharacterAction.entries.size
        )
    }
}
