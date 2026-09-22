package com.pairplay.app

import com.pairplay.app.engine.EffectEmitter
import com.pairplay.app.engine.EffectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EffectEmitterTest {

    private fun emitter() = EffectEmitter(random = Random(42))

    @Test
    fun `띄우기 전에는 아무것도 그리지 않는다`() {
        val emitter = emitter()
        assertFalse(emitter.hasActive)
        assertTrue(emitter.render(0L).isEmpty())
    }

    @Test
    fun `하트를 띄우면 시간이 지나며 위로 올라간다`() {
        val emitter = emitter()
        emitter.spawn(EffectKind.HEART, 1, 0L)

        val early = emitter.render(200L).first()
        val later = emitter.render(700L).first()

        assertEquals(EffectKind.HEART, early.kind)
        assertTrue("시간이 지나면 더 위로 가야 합니다", later.yRatio < early.yRatio)
    }

    @Test
    fun `수명이 끝나면 저절로 사라진다`() {
        val emitter = emitter()
        emitter.spawn(EffectKind.HEART, 2, 0L)
        assertTrue(emitter.hasActive)

        // 수명(최대 1.5초)과 시작 지연을 넉넉히 넘긴 시점
        assertTrue(emitter.render(10_000L).isEmpty())
        assertFalse(emitter.hasActive)
    }

    @Test
    fun `아무리 많이 띄워도 개수가 제한된다`() {
        val emitter = emitter()
        repeat(50) { emitter.spawn(EffectKind.HEART, 5, 0L) }
        // 계속 쌓이면 그리기가 느려진다.
        assertTrue(emitter.render(300L).size <= EffectEmitter.MAX_PARTICLES)
    }

    @Test
    fun `그릴 값들이 화면 밖으로 튀지 않는다`() {
        val emitter = emitter()
        for (kind in EffectKind.entries) {
            emitter.spawn(kind, 3, 0L)
        }
        for (time in 0..1500 step 50) {
            for (effect in emitter.render(time.toLong())) {
                assertTrue("가로 위치가 벗어남: ${effect.xRatio}", effect.xRatio in 0f..1f)
                assertTrue("세로 위치가 벗어남: ${effect.yRatio}", effect.yRatio in -0.01f..1.01f)
                assertTrue("투명도가 벗어남: ${effect.alpha}", effect.alpha in 0f..1f)
                assertTrue("크기가 이상함: ${effect.scale}", effect.scale > 0f && effect.scale < 3f)
            }
        }
    }

    /**
     * 그리는 쪽은 MAX_RENDER_SCALE 로 창 여백을 잡는다.
     * 실제 크기가 이 값을 넘으면 표시가 창 가장자리에서 잘려 보인다.
     */
    @Test
    fun `크기가 그리기 쪽 약속을 넘지 않는다`() {
        val emitter = emitter()
        for (kind in EffectKind.entries) {
            emitter.spawn(kind, EffectEmitter.MAX_PARTICLES, 0L)
        }
        var seen = 0
        for (time in 0..2000 step 10) {
            for (effect in emitter.render(time.toLong())) {
                seen++
                assertTrue(
                    "크기 ${effect.scale} 가 약속(${EffectEmitter.MAX_RENDER_SCALE})을 넘었습니다",
                    effect.scale <= EffectEmitter.MAX_RENDER_SCALE
                )
            }
        }
        assertTrue("검사한 표시가 하나도 없습니다", seen > 0)
    }

    @Test
    fun `지우면 즉시 비워진다`() {
        val emitter = emitter()
        emitter.spawn(EffectKind.NOTE, 4, 0L)
        emitter.clear()
        assertFalse(emitter.hasActive)
        assertTrue(emitter.render(100L).isEmpty())
    }
}
