package com.pairplay.app.engine

import com.pairplay.app.data.SceneTrigger
import kotlin.random.Random

/**
 * 상황극 스케줄러.
 *
 * 단독 행동, 두 캐릭터의 연속 행동, 이벤트 반응을 하나로 관리한다.
 * 장면은 마디(step) 단위로 순서대로 진행되며, 앞 마디가 끝나야 다음 마디가 시작된다.
 * 그래서 'A가 졸기 -> B가 바라보기 -> 다가가기 -> 나란히 쉬기' 가 순서대로 나온다.
 *
 * 화면에 실제로 그리는 일은 하지 않는다. "지금 이 캐릭터는 무엇을 얼마나 해야 하는가"
 * 만 알려 준다. 덕분에 안드로이드 없이 단위 테스트로 확인할 수 있다.
 */
class SceneDirector(
    private val random: Random = Random.Default,
    /** 쉬는 시간이 왔을 때 장면을 시작할 확률. 테스트에서 1 로 고정해 쓴다. */
    private val scriptStartChance: Float = SCRIPT_START_CHANCE
) {

    /** 한 캐릭터가 지금 할 일. */
    data class Direction(
        val action: CharacterAction,
        val durationMs: Long,
        /** 어떤 장면의 일부인지. 장면 없이 혼자 하는 동작이면 null. */
        val scriptId: String? = null
    )

    /**
     * 쉬는 시간에 장면을 시작할 확률.
     * 사용자가 '활발함'을 올리면 장면이 더 자주 나온다.
     */
    private var eagerness: Float = scriptStartChance

    /**
     * 동작 속도 배율. 클수록 빠르다.
     * 사용자의 '활발함' 설정이 여기로 들어온다.
     */
    private var speedScale: Float = 1f

    private var context: RelationshipContext? = null
    private var scripts: List<InteractionScript> = emptyList()

    /** 장면 id -> 이 시각 전에는 다시 쓰지 않는다. */
    private val cooldownUntil = HashMap<String, Long>()

    private var active: InteractionScript? = null
    private var stepIndex = 0
    private val stepHandedTo = HashSet<Performer>()
    /**
     * 이 사람이 맡은 '이번 마디'가 끝나는 시각.
     *
     * 기다리는 동안 주는 짧은 간격은 여기에 반영하지 않는다. 반영하면 두 사람이
     * 번갈아 기다리며 서로의 끝 시각을 계속 뒤로 밀어, 마디가 영원히 끝나지 않는다.
     */
    private var stepEndsA = 0L
    private var stepEndsB = 0L

    val activeScriptId: String? get() = active?.id
    val activeScriptName: String? get() = active?.name

    /** 활발함 설정을 반영한다. 0 이면 장면 없이 각자 있고, 1 이면 쉴 틈 없이 장면이 돈다. */
    fun setEagerness(value: Float) {
        eagerness = value.coerceIn(0f, 1f)
    }

    /** 동작이 얼마나 빨리 지나갈지. 1 이 기본이다. */
    fun setSpeedScale(value: Float) {
        speedScale = value.coerceIn(0.5f, 2f)
    }

    /**
     * 실제로 쓸 동작 길이.
     *
     * 활발함 설정과 캐릭터의 활동성 수치를 함께 반영한다.
     * 활동적인 캐릭터가 눈에 띄게 빠릿하게 움직여야 둘의 성격 차이가 보인다.
     */
    private fun scaledDuration(duration: Long, performer: Performer): Long {
        val traits = traitsOf(performer) ?: CharacterTraits()
        val energyFactor = 0.8f + 0.4f * (traits.energy.coerceIn(0, 100) / 100f)
        return (duration / (speedScale * energyFactor)).toLong().coerceAtLeast(150L)
    }

    fun configure(newContext: RelationshipContext) {
        context = newContext
        scripts = ScriptLibrary.buildFor(newContext)
        abandonScript()
    }

    /**
     * 진행 중이던 장면만 버린다. 쿨다운은 그대로 둔다.
     * 사용자가 캐릭터를 붙잡으면 장면을 이어갈 수 없으므로 이때 쓴다.
     */
    fun abandonCurrentScript() {
        abandonScript()
    }

    /** 오버레이를 다시 시작하거나 캐릭터가 바뀌었을 때 전부 초기화한다. */
    fun reset() {
        abandonScript()
        cooldownUntil.clear()
        stepEndsA = 0L
        stepEndsB = 0L
    }

    /**
     * 터치나 음악 같은 사건으로 장면을 시작해 본다.
     * 이미 더 중요한 장면이 돌고 있으면 건드리지 않는다.
     * @return 새 장면이 시작되었는지
     */
    fun onTrigger(trigger: SceneTrigger, now: Long): Boolean {
        val current = active
        val candidates = scripts.filter {
            it.trigger == trigger && isOffCooldown(it, now)
        }
        if (candidates.isEmpty()) return false

        val picked = pick(candidates) ?: return false
        if (current != null && current.priority > picked.priority) return false

        startScript(picked, now)
        return true
    }

    /**
     * [performer] 가 다음에 할 일을 정한다.
     * 직전 동작이 끝났을 때만 부른다.
     */
    fun nextDirection(
        performer: Performer,
        now: Long,
        musicPlaying: Boolean = false,
        nearEdge: Boolean = false
    ): Direction {
        advance(now)

        if (active == null) {
            tryStartAmbientScript(now)
        }

        val script = active
        if (script != null) {
            val step = script.steps[stepIndex]
            if (performer in participants(step) && performer !in stepHandedTo) {
                stepHandedTo.add(performer)
                val duration = scaledDuration(step.resolvedDuration(), performer)
                markStepEnd(performer, now + duration)
                return Direction(step.action, duration, script.id)
            }
            // 상대가 연기하는 마디를 기다리는 중. 짧게 숨만 쉬며 기다린다.
            // 끝 시각은 건드리지 않는다. 기다림이 마디를 뒤로 밀면 안 되기 때문이다.
            return Direction(CharacterAction.IDLE, WAITING_MS)
        }

        val action = ambientAction(performer, musicPlaying, nearEdge)
        val duration = scaledDuration(action.defaultDurationMs, performer)
        markStepEnd(performer, now + duration)
        return Direction(action, duration)
    }

    // ------------------------------------------------------------------ 장면 진행

    /** 지금 마디가 끝났으면 다음 마디로 넘어간다. 마지막이면 장면을 마친다. */
    private fun advance(now: Long) {
        if (active == null) return

        while (true) {
            val current = active ?: return
            val step = current.steps[stepIndex]
            val actors = participants(step)

            val everyoneGotIt = actors.all { it in stepHandedTo }
            val everyoneDone = actors.all { stepEndsAt(it) <= now }

            if (!everyoneGotIt || !everyoneDone) return

            stepIndex++
            stepHandedTo.clear()
            if (stepIndex >= current.steps.size) {
                finishScript(current, now)
                return
            }
        }
    }

    private fun tryStartAmbientScript(now: Long) {
        val candidates = scripts.filter {
            it.trigger == SceneTrigger.IDLE_TIMER && isOffCooldown(it, now)
        }
        if (candidates.isEmpty()) return
        // 매번 장면이 나오면 부산스럽다. 가끔은 그냥 각자 있게 둔다.
        if (random.nextFloat() >= eagerness) return
        val picked = pick(candidates) ?: return
        startScript(picked, now)
    }

    private fun startScript(script: InteractionScript, now: Long) {
        if (script.steps.isEmpty()) return
        active = script
        stepIndex = 0
        stepHandedTo.clear()
        // 새 장면의 첫 마디는 바로 나갈 수 있어야 한다.
        stepEndsA = minOf(stepEndsA, now)
        stepEndsB = minOf(stepEndsB, now)
    }

    private fun finishScript(script: InteractionScript, now: Long) {
        cooldownUntil[script.id] = now + script.cooldownMs
        abandonScript()
    }

    private fun abandonScript() {
        active = null
        stepIndex = 0
        stepHandedTo.clear()
    }

    private fun isOffCooldown(script: InteractionScript, now: Long): Boolean =
        (cooldownUntil[script.id] ?: 0L) <= now

    private fun pick(candidates: List<InteractionScript>): InteractionScript? {
        if (candidates.isEmpty()) return null
        val topPriority = candidates.maxOf { it.priority }
        val pool = candidates.filter { it.priority == topPriority }
        val total = pool.sumOf { it.weight }
        if (total <= 0) return pool.firstOrNull()
        var roll = random.nextInt(total)
        for (script in pool) {
            roll -= script.weight
            if (roll < 0) return script
        }
        return pool.lastOrNull()
    }

    private fun participants(step: ScriptStep): List<Performer> = when (step.performer) {
        Performer.A -> listOf(Performer.A)
        Performer.B -> listOf(Performer.B)
        Performer.BOTH -> if (context?.hasPartner == true) {
            listOf(Performer.A, Performer.B)
        } else {
            listOf(Performer.A)
        }
    }

    private fun stepEndsAt(performer: Performer): Long = when (performer) {
        Performer.B -> stepEndsB
        else -> stepEndsA
    }

    private fun markStepEnd(performer: Performer, until: Long) {
        when (performer) {
            Performer.B -> stepEndsB = until
            else -> stepEndsA = until
        }
    }

    // ------------------------------------------------------------------ 장면이 없을 때

    /**
     * 장면이 돌고 있지 않을 때의 혼자 동작.
     * 성격 수치로 빈도만 조절하고, 금지된 동작은 절대 고르지 않는다.
     */
    private fun ambientAction(
        performer: Performer,
        musicPlaying: Boolean,
        nearEdge: Boolean
    ): CharacterAction {
        val traits = traitsOf(performer) ?: CharacterTraits()

        if (musicPlaying) {
            val musical = listOf(CharacterAction.RHYTHM, CharacterAction.JUMP)
                .filter { traits.allows(it) }
            if (musical.isNotEmpty()) return musical.random(random)
        }

        val candidates = buildList {
            add(CharacterAction.IDLE to 30)
            add(CharacterAction.BREATHE to 25)
            add(CharacterAction.WALK to 10 + traits.energy / 5)
            add(CharacterAction.JUMP to 4 + traits.mischief / 8)
            add(CharacterAction.DOZE to 6 + (100 - traits.energy) / 8)
            if (nearEdge) add(CharacterAction.LEAN to 14)
            if (context?.hasPartner == true) {
                add(CharacterAction.LOOK_AT to 6 + traits.warmth / 10)
            }
        }.filter { traits.allows(it.first) }

        val total = candidates.sumOf { it.second }
        if (candidates.isEmpty() || total <= 0) return CharacterAction.IDLE

        var roll = random.nextInt(total)
        for ((action, weight) in candidates) {
            roll -= weight
            if (roll < 0) return action
        }
        return CharacterAction.IDLE
    }

    private fun traitsOf(performer: Performer): CharacterTraits? = when (performer) {
        Performer.B -> context?.b
        else -> context?.a
    }

    companion object {
        /** 상대 마디를 기다리는 동안 쓰는 짧은 간격. */
        const val WAITING_MS = 400L

        /** 쉬는 시간이 왔을 때 장면을 시작할 기본 확률. 1 이면 쉴 틈 없이 장면만 돈다. */
        const val SCRIPT_START_CHANCE = 0.8f
    }
}
