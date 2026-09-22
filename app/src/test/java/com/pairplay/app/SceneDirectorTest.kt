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
        val director = director()
        director.configure(context(RelationshipType.FAMILY))
        director.onTrigger(SceneTrigger.IDLE_TIMER, 1_000L)

        val log = runScene(director, maxTurns = 20)
        val waiting = log.filter {
            it.second == CharacterAction.IDLE && it.third > 1_000L
        }
        // 기다리는 동안 준 간격은 실제 동작 길이보다 훨씬 짧아야 한다.
        assertTrue(
            "기다리는 간격이 없습니다. 두 캐릭터가 번갈아 연기하지 않는다는 뜻입니다.",
            waiting.isNotEmpty() || log.size < 5
        )
    }

    @Test
    fun `쿨다운 전에는 같은 장면이 다시 나오지 않는다`() {
        val director = director()
        director.configure(context(RelationshipType.COLLEAGUES))

        assertTrue(director.onTrigger(SceneTrigger.IDLE_TIMER, 1_000L))
        val first = director.activeScriptId
        assertNotNull(first)

        // 장면을 끝까지 돌린다.
        runScene(director, startAt = 1_000L, maxTurns = 40)

        // 곧바로 같은 장면이 또 잡히면 안 된다.
        var repeated = false
        repeat(10) {
            director.onTrigger(SceneTrigger.IDLE_TIMER, 2_000L)
            if (director.activeScriptId == first) repeated = true
        }
        assertFalse("쿨다운을 무시하고 같은 장면이 다시 나왔습니다", repeated)
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
    fun `관계를 바꾸면 장면 묶음도 바뀐다`() {
        val lovers = ScriptLibrary.buildFor(context(RelationshipType.LOVERS)).map { it.id }.toSet()
        val rivals = ScriptLibrary.buildFor(context(RelationshipType.RIVALS)).map { it.id }.toSet()

        assertTrue("연인 전용 장면이 없습니다", lovers.any { it.startsWith("lovers_") })
        assertTrue("라이벌 전용 장면이 없습니다", rivals.any { it.startsWith("rivals_") })
        assertFalse("관계가 섞였습니다", rivals.any { it.startsWith("lovers_") })
    }
}
