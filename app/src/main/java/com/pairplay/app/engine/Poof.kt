package com.pairplay.app.engine

import kotlin.math.cos
import kotlin.math.sin

/** 연기와 함께 사라지는지, 나타나는지. */
enum class PoofMode { VANISH, APPEAR }

/**
 * 연기 한 뭉치. 캐릭터 한가운데를 기준으로, 캐릭터 키에 대한 비율로 나타낸다.
 */
data class Puff(
    val dxRatio: Float,
    val dyRatio: Float,
    val radiusRatio: Float,
    val alpha: Float
)

/** 한 프레임 분량의 '뿅'. 캐릭터를 얼마나 줄이고 흐리게 그릴지와 연기 뭉치들. */
data class PoofFrame(
    val characterScale: Float,
    val characterAlpha: Float,
    val puffs: List<Puff>
)

/**
 * 연기가 펑 피어오르며 캐릭터가 사라지거나 나타나는 모습을 계산한다.
 *
 * 사라질 때: 살짝 부풀었다가 순식간에 쪼그라들고, 그 자리에서 연기가 퍼져 흩어진다.
 * 나타날 때: 연기가 먼저 퍼지고, 그 속에서 캐릭터가 톡 튀어나와 제 크기로 돌아온다.
 *
 * 그리기와 분리해 두어 화면 없이 확인할 수 있다.
 */
object PoofCalculator {

    /** 한 번 '뿅' 하는 데 걸리는 시간. */
    const val DURATION_MS = 520L

    /** 연기 뭉치 수. 너무 많으면 뿌옇기만 하고 적으면 연기처럼 안 보인다. */
    const val PUFF_COUNT = 7

    /** 연기가 퍼져 나가는 최대 거리(캐릭터 키 대비). */
    const val MAX_SPREAD = 0.3f

    /** 연기 뭉치 하나의 최대 반지름(캐릭터 키 대비). */
    const val MAX_PUFF_RADIUS = 0.2f

    fun frame(mode: PoofMode, progress: Float): PoofFrame {
        val t = progress.coerceIn(0f, 1f)
        return when (mode) {
            PoofMode.VANISH -> vanish(t)
            PoofMode.APPEAR -> appear(t)
        }
    }

    private fun vanish(t: Float): PoofFrame {
        // 0~0.2: 깜짝 놀란 듯 살짝 부푼다. 0.2~0.5: 쪼그라들며 사라진다.
        val scale = when {
            t < 0.2f -> 1f + 0.1f * (t / 0.2f)
            t < 0.5f -> 1.1f * (1f - (t - 0.2f) / 0.3f)
            else -> 0f
        }
        val alpha = if (t < 0.3f) 1f else (1f - (t - 0.3f) / 0.2f).coerceIn(0f, 1f)
        // 연기는 캐릭터가 쪼그라들기 시작할 때 피어오른다.
        return PoofFrame(scale.coerceAtLeast(0f), alpha, puffs(((t - 0.15f) / 0.85f)))
    }

    private fun appear(t: Float): PoofFrame {
        // 연기가 먼저 펑 퍼지고, 0.3 부터 캐릭터가 톡 튀어나온다(살짝 넘쳤다가 제자리).
        val scale = when {
            t < 0.3f -> 0f
            t < 0.7f -> 1.12f * ((t - 0.3f) / 0.4f)
            else -> 1.12f - 0.12f * ((t - 0.7f) / 0.3f)
        }
        val alpha = if (t < 0.3f) 0f else ((t - 0.3f) / 0.15f).coerceIn(0f, 1f)
        return PoofFrame(scale.coerceAtLeast(0f), alpha, puffs(t / 0.85f))
    }

    /**
     * 연기 뭉치들. [p] 가 0 이면 막 피어오르는 순간, 1 이면 다 흩어진 뒤다.
     * 뭉치마다 방향과 크기를 조금씩 달리해 뭉게뭉게 보이게 한다.
     */
    private fun puffs(p: Float): List<Puff> {
        if (p <= 0f || p >= 1f) return emptyList()
        // 빠르게 퍼지다 천천히 멈춘다.
        val spread = 1f - (1f - p) * (1f - p)
        // 처음엔 진하게, 끝으로 갈수록 옅어진다.
        val alpha = if (p < 0.2f) p / 0.2f else (1f - (p - 0.2f) / 0.8f)
        return List(PUFF_COUNT) { i ->
            val angle = (i.toDouble() / PUFF_COUNT) * 2.0 * Math.PI + 0.35
            // 짝수·홀수 뭉치의 거리와 크기를 달리한다.
            val reach = MAX_SPREAD * spread * (if (i % 2 == 0) 1f else 0.72f)
            val size = MAX_PUFF_RADIUS * (0.55f + 0.45f * spread) * (if (i % 2 == 0) 0.8f else 1f)
            Puff(
                dxRatio = (cos(angle) * reach).toFloat(),
                // 연기는 위로 조금 더 올라간다.
                dyRatio = (sin(angle) * reach * 0.85f).toFloat() - 0.05f * spread,
                radiusRatio = size,
                alpha = alpha.coerceIn(0f, 1f)
            )
        }
    }
}
