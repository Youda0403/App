package com.pairplay.app.engine

import kotlin.math.sin
import kotlin.random.Random

/**
 * 캐릭터 위에 잠깐 떠오르는 작은 표시들.
 *
 * 말풍선 대신 기호만 띄운다. 말풍선은 캐릭터보다 눈에 띄고, 두 캐릭터의
 * 크기가 다르면 말풍선 크기까지 달라 보여 어수선했다.
 */
enum class EffectKind {
    /** 좋아함, 애정 */
    HEART,

    /** 음악 */
    NOTE,

    /** 신남, 장난 */
    SPARKLE,

    /** 놀람 */
    EXCLAIM,

    /** 궁금함 */
    QUESTION,

    /** 졸림, 잠 */
    SLEEP,

    /** 머쓱함. 캐릭터 얼굴 옆에 붙는다. */
    SWEAT,

    /** 못마땅함. 캐릭터 머리 위에 붙는다. */
    ANGER
}

/** 표시가 움직이는 방식. */
enum class EffectStyle {
    /** 위로 떠오르며 사라진다. */
    RISE,

    /** 캐릭터에 붙어서 살짝 흔들리다 사라진다. 땀이나 핏대처럼. */
    CLING
}

/** 떠 있는 표시 하나. 시간이 지나면 저절로 사라진다. */
data class EffectParticle(
    val kind: EffectKind,
    val style: EffectStyle,
    val startMs: Long,
    val durationMs: Long,
    /** 가로 시작 위치. 0 이 왼쪽 끝, 1 이 오른쪽 끝. */
    val startXRatio: Float,
    /** 떠오르면서 좌우로 흔들리는 폭. */
    val swayRatio: Float,
    val sizeRatio: Float,
    val tiltDeg: Float
)

/**
 * 그릴 준비가 끝난 한 프레임 분량의 표시.
 *
 * [yRatio] 의 기준을 잘 볼 것.
 * - 0 : 그릴 수 있는 가장 높은 곳
 * - 1 : **캐릭터 머리 꼭대기**
 * - 1 보다 크면 그만큼 캐릭터 그림 위로 내려온다 (땀처럼 얼굴에 붙는 것)
 *
 * 예전에는 0~1 이 창의 위아래였다. 그러면 '캐릭터 위에 붙는' 표시를 놓을 자리가
 * 없어서 땀이 머리 위 허공에 떴다.
 */
data class RenderedEffect(
    val kind: EffectKind,
    val xRatio: Float,
    val yRatio: Float,
    val alpha: Float,
    val scale: Float,
    val rotationDeg: Float
)

/**
 * 표시를 만들고 시간에 따라 움직이게 하는 계산기.
 * 그리기와 분리해 두어 단위 테스트로 확인할 수 있다.
 */
class EffectEmitter(
    private val maxParticles: Int = MAX_PARTICLES,
    private val random: Random = Random.Default
) {

    private val particles = ArrayList<EffectParticle>(MAX_PARTICLES)

    val hasActive: Boolean get() = particles.isNotEmpty()

    fun spawn(
        kind: EffectKind,
        count: Int,
        nowMs: Long,
        style: EffectStyle = defaultStyleFor(kind)
    ) {
        repeat(count.coerceIn(1, maxParticles)) { index ->
            if (particles.size >= maxParticles) {
                // 가장 오래된 것을 밀어낸다. 무한정 쌓여 느려지지 않게 한다.
                particles.removeAt(0)
            }
            particles.add(
                EffectParticle(
                    kind = kind,
                    style = style,
                    // 여러 개가 동시에 뜨면 겹쳐 보이므로 조금씩 늦게 띄운다.
                    startMs = nowMs + index * STAGGER_MS,
                    durationMs = durationFor(style) + random.nextInt(0, 400),
                    startXRatio = startXFor(style, random),
                    swayRatio = if (style == EffectStyle.CLING) {
                        0f
                    } else {
                        (random.nextFloat() - 0.5f) * 0.28f
                    },
                    sizeRatio = 0.85f + random.nextFloat() * 0.35f,
                    tiltDeg = if (style == EffectStyle.CLING) {
                        0f
                    } else {
                        (random.nextFloat() - 0.5f) * 36f
                    }
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
            out.add(renderOne(particle, t))
        }
        return out
    }

    private fun renderOne(particle: EffectParticle, t: Float): RenderedEffect = when (particle.style) {
        EffectStyle.RISE -> {
            val sway = sin(t * 2f * Math.PI.toFloat()) * particle.swayRatio
            RenderedEffect(
                kind = particle.kind,
                xRatio = (particle.startXRatio + sway).coerceIn(0.05f, 0.95f),
                // 아래에서 위로 떠오른다.
                yRatio = 1f - t * 0.8f,
                alpha = alphaAt(t),
                scale = particle.sizeRatio * scaleAt(t),
                rotationDeg = particle.tiltDeg * t
            )
        }

        EffectStyle.CLING -> {
            // 캐릭터 얼굴 위에 붙어서 살짝 떨린다.
            val bob = sin(t * 5f * Math.PI.toFloat()) * 0.05f
            RenderedEffect(
                kind = particle.kind,
                xRatio = particle.startXRatio,
                yRatio = (CLING_Y_RATIO + bob).coerceIn(1f, MAX_CLING_Y_RATIO),
                alpha = alphaAt(t),
                scale = particle.sizeRatio * scaleAt(t),
                rotationDeg = 0f
            )
        }
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
         */
        const val MAX_RENDER_SCALE = 1.45f

        /**
         * 붙어 있는 표시가 놓이는 높이.
         * 1 이 머리 꼭대기이므로, 1 보다 커야 캐릭터 얼굴 위에 얹힌다.
         */
        const val CLING_Y_RATIO = 1.35f

        /** 붙는 표시가 내려갈 수 있는 한계. 더 내려가면 창 밖으로 나간다. */
        const val MAX_CLING_Y_RATIO = 1.6f

        private const val RISE_DURATION_MS = 1_100L
        private const val CLING_DURATION_MS = 1_500L
        private const val STAGGER_MS = 110L

        /** 땀과 핏대는 캐릭터에 붙는다. 나머지는 떠오른다. */
        fun defaultStyleFor(kind: EffectKind): EffectStyle = when (kind) {
            EffectKind.SWEAT, EffectKind.ANGER -> EffectStyle.CLING
            else -> EffectStyle.RISE
        }

        private fun durationFor(style: EffectStyle): Long = when (style) {
            EffectStyle.RISE -> RISE_DURATION_MS
            EffectStyle.CLING -> CLING_DURATION_MS
        }

        private fun startXFor(style: EffectStyle, random: Random): Float = when (style) {
            // 붙는 표시는 얼굴 옆쪽 한자리에 고정한다.
            // 0.5 가 캐릭터 한가운데다. 너무 키우면 그림 밖으로 나간다.
            EffectStyle.CLING -> 0.66f
            EffectStyle.RISE -> 0.5f + (random.nextFloat() - 0.5f) * 0.5f
        }
    }
}
