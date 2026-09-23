package com.pairplay.app

import com.pairplay.app.data.RelationshipDirection
import com.pairplay.app.data.RelationshipType
import com.pairplay.app.data.SceneTrigger
import com.pairplay.app.engine.CharacterAction
import com.pairplay.app.engine.CharacterTraits
import com.pairplay.app.engine.Performer
import com.pairplay.app.engine.RelationshipContext
import com.pairplay.app.engine.SceneDirector
import com.pairplay.app.engine.ScriptLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 상황극 스케줄러가 설계서의 약속을 지키는지 본다.
 * - 마디가 순서대로 나온다
 * - 쿨다운 전에는 같은 장면이 다시 나오지 않는다
 * - 사용자가 금지한 행동은 어떤 경로로도 나오지 않는다
 */
class SceneDirectorTest {

    private companion object {
        /**
         * 장면 길이에 더해 줄 여유.
         *
         * 장면이 시작돼도 상대가 하던 동작을 중간에 끊지는 않는다. 그래서 상대가
         * 가장 긴 혼자 동작(졸기 6초)을 막 시작한 참이면 그만큼 첫 마디가 늦어진다.
         * 거기에 기다리는 간격(0.4초) 한 칸을 더한 값이 실제 상한이다.
         * 막으려는 것은 '장면이 영영 안 끝나는' 버그(45분을 붙잡고 있었다)이므로
         * 이 정도 여유로도 충분히 잡힌다.
         */
        const val STUCK_SLACK_MS = 7_000L
    }

    private fun context(
        type: RelationshipType = RelationshipType.LOVERS,
        direction: RelationshipDirection = RelationshipDirection.MUTUAL,
        blockedA: Set<String> = emptySet(),
        blockedB: Set<String> = emptySet(),
        partner: Boolean = true
    ) = RelationshipContext(
        type = type,
        direction = direction,
        a = CharacterTraits(blockedActions = blockedA),
        b = if (partner) CharacterTraits(blockedActions = blockedB) else null
    )

    private fun director(seed: Int = 7, alwaysStart: Boolean = true) =
        SceneDirector(random = Random(seed), scriptStartChance = if (alwaysStart) 1f else 0f)

    /** 장면 하나를 끝까지 돌려 나온 순서를 기록한다. */
    private fun runScene(
        director: SceneDirector,
        startAt: Long = 1_000L,
        maxTurns: Int = 60
    ): List<Triple<Performer, CharacterAction, Long>> {
        val log = mutableListOf<Triple<Performer, CharacterAction, Long>>()
        var nowA = startAt
        var nowB = startAt

        repeat(maxTurns) {
            // 먼저 끝난 쪽이 다음 지시를 받는다. 실제 오버레이와 같은 순서다.
            val performer = if (nowA <= nowB) Performer.A else Performer.B
            val now = minOf(nowA, nowB)
            val direction = director.nextDirection(performer, now)
            log.add(Triple(performer, direction.action, now))
            if (performer == Performer.A) nowA = now + direction.durationMs
            else nowB = now + direction.durationMs
        }
        return log
    }

    @Test
    fun `장면의 마디가 순서대로 나온다`() {
        val director = director()
        director.configure(context(RelationshipType.FAMILY))

        // 가족 관계의 '다가가 기대기' 는 B 가 다가간 뒤 둘이 함께 쉰다.
        var started = false
        repeat(20) {
            if (!started && director.onTrigger(SceneTrigger.IDLE_TIMER, 1_000L)) started = true
        }

        val log = runScene(director)
        val realActions = log.filter { it.second != CharacterAction.IDLE }

        assertTrue("장면이 전혀 나오지 않았습니다", realActions.isNotEmpty())

        // 같은 장면 안에서는 시각이 뒤로 가지 않는다.
        var previous = 0L
        for ((_, _, time) in log) {
            assertTrue("시간이 거꾸로 갔습니다", time >= previous)
            previous = time
        }
    }

    @Test
    fun `상대 마디를 기다리는 쪽은 짧게 기다린다`() {
        val ctx = context(RelationshipType.FAMILY)
        val director = director()
        director.configure(ctx)
        assertTrue(director.onTrigger(SceneTrigger.IDLE_TIMER, 1_000L))

        val script = ScriptLibrary.buildFor(ctx).first { it.id == director.activeScriptId }
        val firstStep = script.steps.first()
        assertTrue(
            "가족 장면의 첫 마디는 한 명이 먼저 움직여야 합니다",
            firstStep.performer != Performer.BOTH
        )

        val actor = firstStep.performer
        val waiter = if (actor == Performer.A) Performer.B else Performer.A

        assertEquals(
            "첫 마디를 맡은 쪽이 그 동작을 해야 합니다",
            firstStep.action,
            director.nextDirection(actor, 1_000L).action
        )
        assertEquals(
            "기다리는 쪽은 짧은 간격으로 다시 물어봐야 장면이 늘어지지 않습니다",
            SceneDirector.WAITING_MS,
            director.nextDirection(waiter, 1_000L).durationMs
        )
    }

    @Test
    fun `쿨다운 전에는 같은 장면이 다시 나오지 않는다`() {
        val ctx = context(RelationshipType.COLLEAGUES)
        val director = director()
        director.configure(ctx)
        val cooldowns = ScriptLibrary.buildFor(ctx).associate { it.id to it.cooldownMs }

        // 오래 돌리면서 각 장면이 언제 시작했는지 기록한다.
        val startTimes = HashMap<String, MutableList<Long>>()
        var previousId: String? = null
        var nowA = 1_000L
        var nowB = 1_000L

        repeat(3_000) {
            val performer = if (nowA <= nowB) Performer.A else Performer.B
            val now = minOf(nowA, nowB)
            val direction = director.nextDirection(performer, now)

            val id = director.activeScriptId
            if (id != null && id != previousId) {
                startTimes.getOrPut(id) { mutableListOf() }.add(now)
            }
            previousId = id

            if (performer == Performer.A) nowA = now + direction.durationMs
            else nowB = now + direction.durationMs
        }

        assertTrue("장면이 한 번도 시작되지 않았습니다", startTimes.isNotEmpty())
        assertTrue(
            "장면이 한 번밖에 안 나와서 쿨다운을 확인할 수 없습니다",
            startTimes.values.any { it.size >= 2 }
        )

        for ((id, times) in startTimes) {
            val cooldown = cooldowns[id] ?: continue
            for (i in 1 until times.size) {
                val gap = times[i] - times[i - 1]
                assertTrue(
                    "$id 이(가) 쿨다운(${cooldown}ms) 을 무시하고 ${gap}ms 만에 다시 나왔습니다",
                    gap >= cooldown
                )
            }
        }
    }

    /**
     * 사용자 피드백: "조는 게 너무 자주 나와... 특히 부딪히고 나서 자는 모션이 연달아"
     *
     * 조는 동작은 다른 동작보다 세 배 가까이 길다. 그래서 뽑히는 횟수가 적어도
     * 화면에 떠 있는 시간은 길다. 여기서는 '화면에 떠 있는 시간'으로 확인한다.
     */
    @Test
    fun `조는 시간이 전체의 일부에 그친다`() {
        val director = director(alwaysStart = false)
        director.configure(context(RelationshipType.FRIENDS))

        var dozeMs = 0L
        var totalMs = 0L
        var now = 1_000L
        repeat(4_000) {
            val direction = director.nextDirection(Performer.A, now)
            if (direction.action == CharacterAction.DOZE) dozeMs += direction.durationMs
            totalMs += direction.durationMs
            now += direction.durationMs
        }

        val share = dozeMs.toDouble() / totalMs
        assertTrue("조는 시간이 전체의 %.0f%% 나 됩니다".format(share * 100), share < 0.15)
    }

    @Test
    fun `졸고 나서 곧바로 또 졸지 않는다`() {
        val director = director(alwaysStart = false)
        director.configure(context(RelationshipType.FRIENDS))

        var previous: CharacterAction? = null
        var now = 1_000L
        repeat(3_000) {
            val action = director.nextDirection(Performer.A, now).action
            if (previous == CharacterAction.DOZE) {
                assertTrue("조는 동작이 연달아 나왔습니다", action != CharacterAction.DOZE)
            }
            previous = action
            now += 1_000L
        }
    }

    @Test
    fun `놀란 직후에는 졸지 않는다`() {
        val director = director(alwaysStart = false)
        director.configure(context(RelationshipType.FRIENDS))

        var now = 1_000L
        repeat(3_000) {
            val action = director.nextDirection(Performer.A, now, justStartled = true).action
            assertTrue("부딪힌 직후에 졸았습니다", action != CharacterAction.DOZE)
            now += 1_000L
        }
    }

    /**
     * 실제로 터졌던 버그의 재발 방지.
     *
     * 기다리는 동안 주던 짧은 간격이 '이번 마디가 끝나는 시각'까지 밀어 버려서,
     * 두 사람이 함께 하는 마디에서 서로의 끝 시각을 번갈아 미루며 장면이
     * 영원히 끝나지 않았다. 첫 장면 하나가 붙잡은 채 45분이 지나도 다음 장면이
     * 시작되지 않았다.
     */
    @Test
    fun `장면이 끝나지 않고 멈추는 일이 없다`() {
        val ctx = context(RelationshipType.FAMILY)
        val director = director()
        director.configure(ctx)
        val totals = ScriptLibrary.buildFor(ctx).associate { it.id to it.totalDurationMs }

        var nowA = 1_000L
        var nowB = 1_000L
        var currentId: String? = null
        var currentStart = 1_000L
        var finishedScenes = 0

        repeat(3_000) {
            val performer = if (nowA <= nowB) Performer.A else Performer.B
            val now = minOf(nowA, nowB)
            val direction = director.nextDirection(performer, now)

            val id = director.activeScriptId
            if (id != currentId) {
                if (currentId != null) {
                    val ran = now - currentStart
                    val expected = totals[currentId] ?: 0L
                    finishedScenes++
                    assertTrue(
                        "$currentId 이(가) ${ran}ms 나 붙잡고 있었습니다. " +
                            "원래 길이는 ${expected}ms 입니다",
                        ran <= expected + STUCK_SLACK_MS
                    )
                }
                currentId = id
                currentStart = now
            }

            if (performer == Performer.A) nowA = now + direction.durationMs
            else nowB = now + direction.durationMs
        }

        assertTrue(
            "장면이 하나도 끝나지 않았습니다. 스케줄러가 멈춰 있다는 뜻입니다",
            finishedScenes >= 5
        )
    }

    @Test
    fun `금지한 행동은 어떤 장면에도 나오지 않는다`() {
        val blocked = setOf(
            CharacterAction.APPROACH.id,
            CharacterAction.JUMP.id,
            CharacterAction.WALK.id
        )
        val ctx = context(RelationshipType.LOVERS, blockedA = blocked, blockedB = blocked)

        // 후보 장면 자체에서 빠져야 한다.
        val scripts = ScriptLibrary.buildFor(ctx)
        for (script in scripts) {
            for (step in script.steps) {
                assertFalse(
                    "금지한 ${step.action.id} 가 '${script.name}' 에 남아 있습니다",
                    step.action.id in blocked
                )
            }
        }

        // 실제로 돌려 봐도 나오지 않아야 한다.
        val director = director()
        director.configure(ctx)
        repeat(5) { director.onTrigger(SceneTrigger.IDLE_TIMER, 1_000L) }
        val log = runScene(director, maxTurns = 80)
        for ((_, action, _) in log) {
            assertFalse("금지한 ${action.id} 가 나왔습니다", action.id in blocked)
        }
    }

    @Test
    fun `한 명뿐이면 상대가 필요한 장면은 나오지 않는다`() {
        val ctx = context(partner = false)
        val scripts = ScriptLibrary.buildFor(ctx)

        assertTrue("혼자 있을 때 쓸 장면이 없습니다", scripts.isNotEmpty())
        for (script in scripts) {
            assertFalse("${script.name} 은 상대가 필요합니다", script.requiresPartner)
            for (step in script.steps) {
                assertTrue(
                    "${script.name} 에 상대 역할이 남아 있습니다",
                    step.performer == Performer.A || step.performer == Performer.BOTH
                )
            }
        }
    }

    @Test
    fun `짝사랑은 마음을 품은 쪽이 몰래 본다`() {
        val aLoves = context(
            RelationshipType.ONE_SIDED_LOVE,
            RelationshipDirection.A_TO_B
        )
        val scripts = ScriptLibrary.buildFor(aLoves)
        val secret = scripts.firstOrNull { it.id == "onesided_secret_glance" }
        assertNotNull("몰래 바라보는 장면이 없습니다", secret)

        val glanceStep = secret!!.steps.first { it.action == CharacterAction.GLANCE }
        assertEquals("마음을 품은 쪽이 봐야 합니다", Performer.A, glanceStep.performer)

        // 방향을 반대로 하면 역할도 뒤집힌다.
        val bLoves = context(RelationshipType.ONE_SIDED_LOVE, RelationshipDirection.B_TO_A)
        val flipped = ScriptLibrary.buildFor(bLoves)
            .first { it.id == "onesided_secret_glance" }
            .steps.first { it.action == CharacterAction.GLANCE }
        assertEquals(Performer.B, flipped.performer)
    }

    @Test
    fun `장면이 없으면 혼자 하는 동작을 돌려준다`() {
        val director = director(alwaysStart = false)
        director.configure(context())

        val direction = director.nextDirection(Performer.A, 1_000L)
        assertNull("장면이 시작되면 안 됩니다", direction.scriptId)
        assertTrue("길이가 0 이면 멈춰 버립니다", direction.durationMs > 0L)
    }

    @Test
    fun `붙잡으면 진행 중이던 장면을 버린다`() {
        val director = director()
        director.configure(context(RelationshipType.FRIENDS))
        director.onTrigger(SceneTrigger.IDLE_TIMER, 1_000L)
        assertNotNull(director.activeScriptId)

        director.abandonCurrentScript()
        assertNull("붙잡았는데 장면이 남아 있습니다", director.activeScriptId)
    }

    @Test
    fun `모든 관계에 마주쳤을 때 장면이 있다`() {
        for (type in RelationshipType.entries) {
            val scripts = ScriptLibrary.buildFor(context(type))
            val met = scripts.filter { it.trigger == SceneTrigger.CHARACTERS_MET }
            assertTrue("$type 에 마주쳤을 때 장면이 없습니다", met.isNotEmpty())
            assertTrue(
                "$type 의 마주침 장면이 비어 있습니다",
                met.all { it.steps.isNotEmpty() }
            )
        }
    }

    @Test
    fun `끌어다 붙여 놓으면 마주친 장면이 시작된다`() {
        val director = director()
        director.configure(context(RelationshipType.FRIENDS))

        assertTrue(
            "마주쳤는데 아무 장면도 시작되지 않았습니다",
            director.onTrigger(SceneTrigger.CHARACTERS_MET, 1_000L)
        )
        assertTrue(
            "마주침 장면이 아닌 다른 장면이 잡혔습니다",
            director.activeScriptId?.startsWith("met_") == true
        )
    }

    @Test
    fun `활발함을 낮추면 장면이 시작되지 않는다`() {
        val director = director()
        director.configure(context())
        director.setEagerness(0f)

        repeat(50) {
            val direction = director.nextDirection(Performer.A, 1_000L + it * 3_000L)
            assertNull("조용히 있으라고 했는데 장면이 돌았습니다", direction.scriptId)
        }
    }

    @Test
    fun `활발하게 설정하면 동작이 더 빨리 지나간다`() {
        val slow = director(alwaysStart = false)
        slow.configure(context())
        slow.setSpeedScale(0.75f)

        val fast = director(alwaysStart = false)
        fast.configure(context())
        fast.setSpeedScale(1.35f)

        val slowTotal = (0 until 30).sumOf {
            slow.nextDirection(Performer.A, 1_000L + it * 60_000L).durationMs
        }
        val fastTotal = (0 until 30).sumOf {
            fast.nextDirection(Performer.A, 1_000L + it * 60_000L).durationMs
        }
        assertTrue(
            "속도 설정이 동작 길이에 반영되지 않았습니다 (느림 $slowTotal, 빠름 $fastTotal)",
            fastTotal < slowTotal
        )
    }

    @Test
    fun `활동적인 캐릭터가 더 빠르게 움직인다`() {
        val lively = RelationshipContext(
            RelationshipType.FRIENDS,
            RelationshipDirection.MUTUAL,
            a = CharacterTraits(energy = 100),
            b = CharacterTraits(energy = 0)
        )
        val director = director(alwaysStart = false)
        director.configure(lively)

        // 같은 시점에 각자 물어보면, 활동적인 쪽이 더 짧은 동작을 받는다.
        val energetic = (0 until 40).sumOf {
            director.nextDirection(Performer.A, 1_000L + it * 60_000L).durationMs
        }
        val calm = (0 until 40).sumOf {
            director.nextDirection(Performer.B, 1_000L + it * 60_000L).durationMs
        }
        assertTrue(
            "성격 차이가 속도에 드러나지 않습니다 (활동적 $energetic, 차분함 $calm)",
            energetic < calm
        )
    }

    @Test
    fun `관계를 바꾸면 장면 묶음도 바뀐다`() {
        val lovers = ScriptLibrary.buildFor(context(RelationshipType.LOVERS)).map { it.id }.toSet()
        val rivals = ScriptLibrary.buildFor(context(RelationshipType.RIVALS)).map { it.id }.toSet()

        assertTrue("연인 전용 장면이 없습니다", lovers.any { it.startsWith("lovers_") })
        assertTrue("라이벌 전용 장면이 없습니다", rivals.any { it.startsWith("rivals_") })
        assertFalse("관계가 섞였습니다", rivals.any { it.startsWith("lovers_") })
    }
}
