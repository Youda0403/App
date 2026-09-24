package com.pairplay.app

import com.pairplay.app.engine.PoofCalculator
import com.pairplay.app.engine.PoofMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 은행·결제 앱에서 연기와 함께 '뿅' 사라지고 나타나는 모습.
 */
class PoofCalculatorTest {

    @Test
    fun `사라질 때는 처음엔 보이고 끝나면 완전히 사라진다`() {
        val start = PoofCalculator.frame(PoofMode.VANISH, 0f)
        val end = PoofCalculator.frame(PoofMode.VANISH, 1f)
        assertEquals(1f, start.characterAlpha, 0.001f)
        assertEquals(0f, end.characterAlpha, 0.001f)
        assertEquals(0f, end.characterScale, 0.001f)
        // 끝나면 연기도 다 흩어진다. 남아 있으면 허공에 연기만 떠 있다.
        assertTrue(end.puffs.isEmpty())
    }

    @Test
    fun `나타날 때는 처음엔 안 보이고 끝나면 제 크기로 돌아온다`() {
        val start = PoofCalculator.frame(PoofMode.APPEAR, 0f)
        val end = PoofCalculator.frame(PoofMode.APPEAR, 1f)
        assertEquals(0f, start.characterAlpha, 0.001f)
        assertEquals(1f, end.characterAlpha, 0.001f)
        assertEquals(1f, end.characterScale, 0.001f)
        assertTrue(end.puffs.isEmpty())
    }

    @Test
    fun `중간에는 연기가 피어 있다`() {
        for (mode in PoofMode.entries) {
            val middle = PoofCalculator.frame(mode, 0.5f)
            assertEquals(PoofCalculator.PUFF_COUNT, middle.puffs.size)
            assertTrue(middle.puffs.all { it.alpha > 0f })
        }
    }

    @Test
    fun `연기가 정해진 범위 밖으로 나가지 않는다`() {
        // 그리는 쪽이 이 범위로 여백을 계산한다. 넘으면 연기가 잘려 보인다.
        val limit = PoofCalculator.MAX_SPREAD + PoofCalculator.MAX_PUFF_RADIUS + 0.06f
        for (mode in PoofMode.entries) {
            for (step in 0..100) {
                val frame = PoofCalculator.frame(mode, step / 100f)
                for (puff in frame.puffs) {
                    assertTrue(abs(puff.dxRatio) + puff.radiusRatio <= limit)
                    assertTrue(abs(puff.dyRatio) + puff.radiusRatio <= limit)
                    assertTrue(puff.alpha in 0f..1f)
                }
                assertTrue(frame.characterAlpha in 0f..1f)
                assertTrue(frame.characterScale in 0f..1.2f)
            }
        }
    }
}
