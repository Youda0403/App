package com.pairplay.app

import com.pairplay.app.engine.CharacterAction
import com.pairplay.app.engine.Pose
import com.pairplay.app.engine.PoseCalculator
import com.pairplay.app.engine.WindowPaddingCalculator
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 캐릭터가 창 밖으로 잘리지 않는지 확인한다.
 *
 * 실제 그리기(CharacterView)와 같은 순서로 변형을 계산해서, 변형된 네 모서리가
 * 모두 창 안에 들어오는지 본다. 여백이 모자라면 기기에서 캐릭터 끝이 잘려 보인다.
 */
class WindowPaddingTest {

    @Test
    fun `어떤 동작에서도 캐릭터가 창 밖으로 나가지 않는다`() {
        val sizes = listOf(
            80f to 140f,
            140f to 140f,
            60f to 300f,
            300f to 120f,
            220f to 260f,
            100f to 180f,
            // 극단적인 비율도 확인한다. 납작한 캐릭터는 회전할 때 발밑이,
            // 길쭉한 캐릭터는 머리가 가장 많이 밀려난다.
            400f to 80f,
            50f to 400f
        )

        for ((width, height) in sizes) {
            val padding = WindowPaddingCalculator.forCharacter(width, height)
            val limitX = width / 2f + padding.x
            val limitY = height / 2f + padding.y

            for (action in CharacterAction.entries) {
                for (step in 0..30) {
                    for (seed in listOf(0f, 0.5f, 0.9f)) {
                        val pose = PoseCalculator.pose(action, step / 30f, height, seed)
                        for (mirror in listOf(1f, -1f)) {
                            val corners = transformedCorners(pose, width, height, mirror)
                            for ((x, y) in corners) {
                                assertTrue(
                                    "${action.id}: ${width}x$height 에서 가로로 ${abs(x)} 까지 나갔는데 창은 $limitX 까지입니다",
                                    abs(x) <= limitX + TOLERANCE
                                )
                                assertTrue(
                                    "${action.id}: ${width}x$height 에서 세로로 ${abs(y)} 까지 나갔는데 창은 $limitY 까지입니다",
                                    abs(y) <= limitY + TOLERANCE
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `크기가 0 이어도 터지지 않는다`() {
        val padding = WindowPaddingCalculator.forCharacter(0f, 0f)
        assertTrue(padding.x >= WindowPaddingCalculator.MIN_PADDING_PX)
        assertTrue(padding.y >= WindowPaddingCalculator.MIN_PADDING_PX)
    }

    @Test
    fun `여백이 캐릭터보다 커지지는 않는다`() {
        // 여백은 곧 다른 앱의 터치를 가로채는 면적이다. 캐릭터보다 커지면 설계가 어긋난 것.
        val padding = WindowPaddingCalculator.forCharacter(100f, 180f)
        assertTrue("가로 여백이 과합니다: ${padding.x}", padding.x < 100f)
        assertTrue("세로 여백이 과합니다: ${padding.y}", padding.y < 180f)
    }

    /**
     * CharacterView.onDraw 와 같은 순서로 네 모서리를 변형한다.
     * 캔버스는 translate -> rotate -> scale 순서로 쌓이므로,
     * 점에는 scale -> rotate -> translate 순으로 적용된다.
     */
    private fun transformedCorners(
        pose: Pose,
        width: Float,
        height: Float,
        mirror: Float
    ): List<Pair<Float, Float>> {
        val halfW = width / 2f
        val halfH = height / 2f
        // 뷰 한가운데를 원점으로 둔 좌표계. 회전/확대의 기준점은 발밑 가운데.
        val pivotY = halfH

        val radians = Math.toRadians(pose.rotationDeg.toDouble())
        val cosT = cos(radians).toFloat()
        val sinT = sin(radians).toFloat()

        return listOf(
            -halfW to -halfH,
            halfW to -halfH,
            -halfW to halfH,
            halfW to halfH
        ).map { (px, py) ->
            // 1. 발밑 기준 확대 (좌우 반전은 부호만 바꾸므로 경계에는 영향이 없다)
            val sx = px * pose.scaleX * mirror
            val sy = pivotY + (py - pivotY) * pose.scaleY

            // 2. 발밑 기준 회전
            val dx = sx
            val dy = sy - pivotY
            val rx = dx * cosT - dy * sinT
            val ry = dx * sinT + dy * cosT + pivotY

            // 3. 세로 이동
            rx to (ry + pose.offsetY)
        }
    }

    private companion object {
        const val TOLERANCE = 0.5f
    }
}
