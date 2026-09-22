package com.pairplay.app.engine

import kotlin.math.sin
import kotlin.random.Random

/** 캐릭터 위에 잠깐 떠오르는 작은 표시들. */
enum class EffectKind {
    HEART,
    NOTE,
    SPARKLE,
    EXCLAIM
}

/** 떠 있는 표시 하나. 시간이 지나면 저절로 사라진다. */
data class EffectParticle(
    val kind: EffectKind,
    val startMs: Long,
    val durationMs: Long,
    /** 가로 시작 위치. 0 이 왼쪽 끝, 1 이 오른쪽 끝. */
    val startXRatio: Float,
    /** 떠오르면서 좌우로 흔들리는 폭. */
    val swayRatio: Float,
    val sizeRatio: Float,
    val tiltDeg: Float
)

/** 그릴 준비가 끝난 한 프레임 분량의 표시. */
data class RenderedEffect(
    val kind: EffectKind,
    val xRatio: Float,
    val yRatio: Float,
    val alpha: Float,
    val scale: Float,
    val rotationDeg: Float
)

/**
 * 표시를 만들고 시간에 따라 위로 떠오르게 하는 계산기.
 * 그리기와 분리해 두어 단위 테스트로 확인할 수 있다.
 */
class EffectEmitter(
    private val maxParticles: Int = MAX_PARTICLES,
    private val random: Random = Random.Default
) {

    private val particles = ArrayList<EffectParticle>(MAX_PARTICLES)

    val hasActive: Boolean get() = particles.isNotEmpty()

    fun spawn(kind: EffectKind, count: Int, nowMs: Long) {
        repeat(count.coerceIn(1, maxParticles)) { index ->
            if (particles.size >= maxParticles) {
                // 가장 오래된 것을 밀어낸다. 무한정 쌓여 느려지지 않게 한다.
                particles.removeAt(0)
            }
            particles.add(
                EffectParticle(
                    kind = kind,
                    // 여러 개가 동시에 뜨면 겹쳐 보이므로 조금씩 늦게 띄운다.
                    startMs = nowMs + index * STAGGER_MS,
                    durationMs = DURATION_MS + random.nextInt(0, 400),
                    startXRatio = 0.5f + (random.nextFloat() - 0.5f) * 0.5f,
                    swayRatio = (random.nextFloat() - 0.5f) * 0.28f,
                    sizeRatio = 0.75f + random.nextFloat() * 0.5f,
                    tiltDeg = (random.nextFloat() - 0.5f) * 36f
                )
            )
        }
    }

    fun clear() {
        particles.clear()
    }

    /** 지금 그려야 할 표시들을 돌려주고, 수명이 끝난 것은 치운다. */
    fun render(nowMs: Long): List<RenderedEffect> {
        if (particles.isEmpty()) return emptyList()

        particles.removeAll { nowMs - it.startMs > it.durationMs }
        if (particles.isEmpty()) return emptyList()

        val out = ArrayList<RenderedEffect>(particles.size)
        for (particle in particles) {
            val elapsed = nowMs - particle.startMs
            if (elapsed < 0) continue
            val t = (elapsed.toFloat() / particle.durationMs).coerceIn(0f, 1f)

            val sway = sin(t * 2f * Math.PI.toFloat()) * particle.swayRatio
            out.add(
                RenderedEffect(
                    kind = particle.kind,
                    xRatio = (particle.startXRatio + sway).coerceIn(0.05f, 0.95f),
                    // 아래에서 위로 떠오른다.
                    yRatio = 1f - t * 0.85f,
                    alpha = alphaAt(t),
                    scale = particle.sizeRatio * scaleAt(t),
                    rotationDeg = particle.tiltDeg * t
                )
            )
        }
        return out
    }

    private fun alphaAt(t: Float): Float = when {
        // 처음엔 빠르게 나타나고
        t < 0.15f -> t / 0.15f
        // 끝에서 천천히 사라진다
        t > 0.6f -> ((1f - t) / 0.4f).coerceIn(0f, 1f)
        else -> 1f
    }

    private fun scaleAt(t: Float): Float = when {
        // 톡 튀어나오는 느낌
        t < 0.2f -> 0.6f + (t / 0.2f) * 0.55f
        else -> 1.15f - (t - 0.2f) / 0.8f * 0.25f
    }

    companion object {
        const val MAX_PARTICLES = 12

        /**
         * [render] 가 돌려주는 scale 의 상한.
         * 그리는 쪽에서 표시가 창 밖으로 잘리지 않을 여백을 잡는 데 쓴다.
         * (크기 0.75~1.25 에 튀어나오는 효과 1.15 를 곱한 값보다 조금 넉넉하게)
         */
        const val MAX_RENDER_SCALE = 1.45f
        private const val DURATION_MS = 1_100L
        private const val STAGGER_MS = 110L
    }
}
