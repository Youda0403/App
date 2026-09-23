package com.pairplay.app

import com.pairplay.app.engine.CharacterAction
import com.pairplay.app.engine.Expression
import com.pairplay.app.engine.ExpressionMapper
import com.pairplay.app.engine.ExpressionSlots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 표정 갈아 끼우기.
 *
 * 이미지 한 장으로는 표정을 바꿀 수 없어서, 사용자가 표정별 그림을 등록하면
 * 기분에 맞춰 바꿔 끼운다. 등록하지 않아도 그대로 돌아가야 한다.
 */
class ExpressionTest {

    @Test
    fun `동작마다 어울리는 표정이 정해져 있다`() {
        assertEquals(Expression.HAPPY, ExpressionMapper.forAction(CharacterAction.PET))
        assertEquals(Expression.ANGRY, ExpressionMapper.forAction(CharacterAction.GLARE))
        assertEquals(Expression.SAD, ExpressionMapper.forAction(CharacterAction.SHY))
        assertEquals(Expression.SLEEPY, ExpressionMapper.forAction(CharacterAction.DOZE))
        assertEquals(Expression.SURPRISED, ExpressionMapper.forAction(CharacterAction.SURPRISED))
        assertEquals(Expression.SURPRISED, ExpressionMapper.forAction(CharacterAction.BUMP))
    }

    @Test
    fun `평범한 동작은 기본 얼굴로 둔다`() {
        // 걷기나 숨쉬기까지 표정을 바꾸면 그림이 쉴 새 없이 깜빡인다.
        for (action in listOf(
            CharacterAction.IDLE,
            CharacterAction.BREATHE,
            CharacterAction.WALK,
            CharacterAction.LOOK_AT,
            CharacterAction.GLANCE,
            CharacterAction.LEAN
        )) {
            assertEquals(
                "${action.id} 는 기본 얼굴이어야 합니다",
                Expression.NEUTRAL,
                ExpressionMapper.forAction(action)
            )
        }
    }

    @Test
    fun `모든 동작이 표정을 하나씩 갖는다`() {
        // when 을 빠짐없이 나열해 두었으므로 동작이 늘면 컴파일이 막힌다.
        // 여기서는 실제로 불러 봐서 터지지 않는지까지 본다.
        for (action in CharacterAction.entries) {
            ExpressionMapper.forAction(action)
        }
    }

    @Test
    fun `등록할 수 있는 표정에 기본은 들어 있지 않다`() {
        // 기본은 캐릭터를 만들 때 넣은 그림이라 따로 등록하지 않는다.
        assertTrue(Expression.NEUTRAL !in Expression.registerable)
        assertEquals(Expression.entries.size - 1, Expression.registerable.size)
    }

    @Test
    fun `표정 그림 목록을 저장하고 다시 읽는다`() {
        val text = ExpressionSlots.write(
            mapOf(
                Expression.HAPPY to "/data/happy.png",
                Expression.ANGRY to "/data/angry.png"
            )
        )
        val back = ExpressionSlots.parse(text)
        assertEquals("/data/happy.png", back[Expression.HAPPY])
        assertEquals("/data/angry.png", back[Expression.ANGRY])
        assertNull(back[Expression.SAD])
    }

    @Test
    fun `표정 하나만 바꾸거나 지울 수 있다`() {
        var text = ExpressionSlots.with("", Expression.HAPPY, "/a.png")
        text = ExpressionSlots.with(text, Expression.SAD, "/b.png")
        assertEquals(2, ExpressionSlots.parse(text).size)

        text = ExpressionSlots.with(text, Expression.HAPPY, null)
        val left = ExpressionSlots.parse(text)
        assertEquals(1, left.size)
        assertEquals("/b.png", left[Expression.SAD])
    }

    @Test
    fun `저장된 값이 깨져 있어도 터지지 않는다`() {
        // 사용자가 손댈 수 있는 곳은 아니지만, 예전 형식이 남아 있을 수 있다.
        val broken = "\n= \nnope=/x.png\nhappy\nhappy=/ok.png\nneutral=/skip.png\n"
        val slots = ExpressionSlots.parse(broken)
        assertEquals(1, slots.size)
        assertEquals("/ok.png", slots[Expression.HAPPY])
        // 기본은 캐릭터 그림이므로 여기에 들어오면 안 된다.
        assertNull(slots[Expression.NEUTRAL])
    }
}
