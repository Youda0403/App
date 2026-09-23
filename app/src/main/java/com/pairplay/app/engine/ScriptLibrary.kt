package com.pairplay.app.engine

import com.pairplay.app.data.RelationshipType
import com.pairplay.app.data.SceneTrigger

/**
 * 관계와 성격에 맞는 기본 상황극 묶음.
 *
 * 설계서 원칙을 지킨다.
 * - 관계는 사용자가 정한 대로만 쓴다. 여기서 관계를 바꾸지 않는다.
 * - 사용자가 금지한 동작이 들어간 장면은 아예 후보에서 뺀다.
 * - 성격 수치는 '어떤 장면이 자주 나오는가'만 바꾼다. 없는 장면을 만들어내지 않는다.
 */
object ScriptLibrary {

    /** 한 명만 있을 때 쓰는 장면들. */
    private fun soloScripts(): List<InteractionScript> = listOf(
        InteractionScript(
            id = "solo_stroll",
            name = "혼자 거닐기",
            trigger = SceneTrigger.IDLE_TIMER,
            steps = listOf(
                ScriptStep(Performer.A, CharacterAction.WALK),
                ScriptStep(Performer.A, CharacterAction.IDLE, 1_200L)
            ),
            cooldownMs = 14_000L,
            requiresPartner = false
        ),
        InteractionScript(
            id = "solo_nap",
            name = "혼자 졸기",
            trigger = SceneTrigger.IDLE_TIMER,
            steps = listOf(
                ScriptStep(Performer.A, CharacterAction.DOZE),
                ScriptStep(Performer.A, CharacterAction.SURPRISED, 600L),
                ScriptStep(Performer.A, CharacterAction.IDLE, 1_000L)
            ),
            cooldownMs = 45_000L,
            requiresPartner = false
        ),
        InteractionScript(
            id = "solo_hop",
            name = "혼자 신나기",
            trigger = SceneTrigger.IDLE_TIMER,
            steps = listOf(
                ScriptStep(Performer.A, CharacterAction.JUMP),
                ScriptStep(Performer.A, CharacterAction.JUMP),
                ScriptStep(Performer.A, CharacterAction.BREATHE, 1_500L)
            ),
            cooldownMs = 30_000L,
            requiresPartner = false
        )
    )

    /** 관계와 상관없이 두 명이면 나올 수 있는 장면들. */
    private fun commonPairScripts(): List<InteractionScript> = listOf(
        InteractionScript(
            id = "pair_notice",
            name = "서로 알아차리기",
            trigger = SceneTrigger.IDLE_TIMER,
            steps = listOf(
                ScriptStep(Performer.A, CharacterAction.LOOK_AT),
                ScriptStep(Performer.B, CharacterAction.LOOK_AT),
                ScriptStep(Performer.BOTH, CharacterAction.BREATHE, 1_600L)
            ),
            cooldownMs = 22_000L
        ),
        InteractionScript(
            id = "pair_doze_watch",
            name = "졸면 지켜보기",
            trigger = SceneTrigger.IDLE_TIMER,
            steps = listOf(
                ScriptStep(Performer.A, CharacterAction.DOZE),
                ScriptStep(Performer.B, CharacterAction.LOOK_AT),
                ScriptStep(Performer.B, CharacterAction.APPROACH),
                ScriptStep(Performer.BOTH, CharacterAction.REST)
            ),
            cooldownMs = 50_000L,
            priority = 1
        ),
        InteractionScript(
            id = "pair_music",
            name = "같이 리듬 타기",
            trigger = SceneTrigger.MUSIC_STARTED,
            steps = listOf(
                ScriptStep(Performer.BOTH, CharacterAction.RHYTHM),
                ScriptStep(Performer.BOTH, CharacterAction.RHYTHM)
            ),
            cooldownMs = 20_000L,
            priority = 2
        )
    )

    private fun scriptsFor(type: RelationshipType): List<InteractionScript> = when (type) {
        RelationshipType.LOVERS -> listOf(
            InteractionScript(
                id = "lovers_side_by_side",
                name = "다가가 나란히 쉬기",
                trigger = SceneTrigger.IDLE_TIMER,
                steps = listOf(
                    ScriptStep(Performer.A, CharacterAction.LOOK_AT),
                    ScriptStep(Performer.A, CharacterAction.APPROACH),
                    ScriptStep(Performer.B, CharacterAction.LOOK_AT),
                    ScriptStep(Performer.BOTH, CharacterAction.REST)
                ),
                cooldownMs = 40_000L,
                priority = 1,
                weight = 16
            ),
            InteractionScript(
                id = "lovers_shy_glance",
                name = "눈 마주치고 수줍어하기",
                trigger = SceneTrigger.IDLE_TIMER,
                steps = listOf(
                    ScriptStep(Performer.BOTH, CharacterAction.LOOK_AT),
                    ScriptStep(Performer.A, CharacterAction.SHY),
                    ScriptStep(Performer.B, CharacterAction.SHY)
                ),
                cooldownMs = 30_000L,
                weight = 12
            ),
            InteractionScript(
                id = "lovers_happy_hop",
                name = "같이 폴짝",
                trigger = SceneTrigger.IDLE_TIMER,
                steps = listOf(
                    ScriptStep(Performer.A, CharacterAction.APPROACH),
                    ScriptStep(Performer.BOTH, CharacterAction.JUMP)
                ),
                cooldownMs = 36_000L,
                weight = 10
            )
        )

        RelationshipType.FRIENDS -> listOf(
            InteractionScript(
                id = "friends_tease_run",
                name = "장난치고 도망가기",
                trigger = SceneTrigger.IDLE_TIMER,
                steps = listOf(
                    ScriptStep(Performer.A, CharacterAction.APPROACH),
                    ScriptStep(Performer.A, CharacterAction.TEASE),
                    ScriptStep(Performer.B, CharacterAction.SURPRISED),
                    ScriptStep(Performer.A, CharacterAction.WALK)
                ),
                cooldownMs = 34_000L,
                priority = 1,
                weight = 16
            ),
            InteractionScript(
                id = "friends_chat",
                name = "마주 보고 떠들기",
                trigger = SceneTrigger.IDLE_TIMER,
                steps = listOf(
                    ScriptStep(Performer.A, CharacterAction.APPROACH),
                    ScriptStep(Performer.BOTH, CharacterAction.LOOK_AT),
                    ScriptStep(Performer.A, CharacterAction.TEASE),
                    ScriptStep(Performer.B, CharacterAction.TEASE)
                ),
                cooldownMs = 26_000L,
                weight = 14
            )
        )

        RelationshipType.ONE_SIDED_LOVE -> emptyList() // 방향이 있어 따로 만든다

        RelationshipType.RIVALS -> listOf(
            InteractionScript(
                id = "rivals_stare",
                name = "노려보고 등 돌리기",
                trigger = SceneTrigger.IDLE_TIMER,
                steps = listOf(
                    ScriptStep(Performer.BOTH, CharacterAction.LOOK_AT),
                    ScriptStep(Performer.BOTH, CharacterAction.GLARE),
                    ScriptStep(Performer.A, CharacterAction.WALK),
                    ScriptStep(Performer.B, CharacterAction.WALK)
                ),
                cooldownMs = 30_000L,
                priority = 1,
                weight = 16
            ),
            InteractionScript(
                id = "rivals_contest",
                name = "점프 대결",
                trigger = SceneTrigger.IDLE_TIMER,
                steps = listOf(
                    ScriptStep(Performer.A, CharacterAction.JUMP),
                    ScriptStep(Performer.B, CharacterAction.JUMP),
                    ScriptStep(Performer.A, CharacterAction.GLARE),
                    ScriptStep(Performer.B, CharacterAction.GLARE)
                ),
                cooldownMs = 36_000L,
                weight = 12
            )
        )

        RelationshipType.FAMILY -> listOf(
            InteractionScript(
                id = "family_lean",
                name = "다가가 기대기",
                trigger = SceneTrigger.IDLE_TIMER,
                steps = listOf(
                    ScriptStep(Performer.B, CharacterAction.APPROACH),
                    ScriptStep(Performer.BOTH, CharacterAction.REST)
                ),
                cooldownMs = 40_000L,
                priority = 1,
                weight = 16
            ),
            InteractionScript(
                id = "family_nap",
                name = "나란히 졸기",
                trigger = SceneTrigger.IDLE_TIMER,
                steps = listOf(
                    ScriptStep(Performer.A, CharacterAction.APPROACH),
                    ScriptStep(Performer.BOTH, CharacterAction.DOZE)
                ),
                cooldownMs = 50_000L,
                weight = 12
            )
        )

        RelationshipType.COLLEAGUES -> listOf(
            InteractionScript(
                id = "colleagues_greet",
                name = "가볍게 인사하기",
                trigger = SceneTrigger.IDLE_TIMER,
                steps = listOf(
                    ScriptStep(Performer.A, CharacterAction.LOOK_AT),
                    ScriptStep(Performer.A, CharacterAction.GLANCE),
                    ScriptStep(Performer.B, CharacterAction.GLANCE)
                ),
                cooldownMs = 24_000L,
                priority = 1,
                weight = 14
            ),
            InteractionScript(
                id = "colleagues_apart",
                name = "각자 할 일 하기",
                trigger = SceneTrigger.IDLE_TIMER,
                steps = listOf(
                    ScriptStep(Performer.A, CharacterAction.WALK),
                    ScriptStep(Performer.B, CharacterAction.WALK),
                    ScriptStep(Performer.BOTH, CharacterAction.BREATHE, 2_000L)
                ),
                cooldownMs = 22_000L,
                weight = 12
            )
        )

        RelationshipType.CUSTOM -> listOf(
            InteractionScript(
                id = "custom_together",
                name = "같이 있기",
                trigger = SceneTrigger.IDLE_TIMER,
                steps = listOf(
                    ScriptStep(Performer.A, CharacterAction.LOOK_AT),
                    ScriptStep(Performer.A, CharacterAction.APPROACH),
                    ScriptStep(Performer.BOTH, CharacterAction.REST)
                ),
                cooldownMs = 32_000L,
                priority = 1,
                weight = 14
            )
        )
    }

    /** 짝사랑은 누가 누구를 좋아하는지에 따라 장면이 달라진다. */
    private fun oneSidedScripts(admirer: Performer?): List<InteractionScript> {
        if (admirer == null) {
            // 방향을 정하지 않았으면 서로 조심스러운 장면만 쓴다.
            return listOf(
                InteractionScript(
                    id = "onesided_mutual_glance",
                    name = "서로 힐끗 보기",
                    trigger = SceneTrigger.IDLE_TIMER,
                    steps = listOf(
                        ScriptStep(Performer.A, CharacterAction.GLANCE),
                        ScriptStep(Performer.B, CharacterAction.GLANCE),
                        ScriptStep(Performer.BOTH, CharacterAction.SHY)
                    ),
                    cooldownMs = 28_000L,
                    priority = 1,
                    weight = 14
                )
            )
        }

        val other = if (admirer == Performer.A) Performer.B else Performer.A
        return listOf(
            InteractionScript(
                id = "onesided_secret_glance",
                name = "몰래 바라보기",
                trigger = SceneTrigger.IDLE_TIMER,
                steps = listOf(
                    ScriptStep(admirer, CharacterAction.GLANCE),
                    ScriptStep(admirer, CharacterAction.SHY),
                    ScriptStep(other, CharacterAction.BREATHE, 1_500L)
                ),
                cooldownMs = 25_000L,
                priority = 1,
                weight = 18
            ),
            InteractionScript(
                id = "onesided_almost",
                name = "다가가려다 멈추기",
                trigger = SceneTrigger.IDLE_TIMER,
                steps = listOf(
                    ScriptStep(admirer, CharacterAction.APPROACH),
                    ScriptStep(admirer, CharacterAction.SHY),
                    ScriptStep(other, CharacterAction.LOOK_AT),
                    ScriptStep(admirer, CharacterAction.WALK)
                ),
                cooldownMs = 42_000L,
                weight = 14
            ),
            InteractionScript(
                id = "onesided_caught",
                name = "눈 마주치기",
                trigger = SceneTrigger.TAP_CHARACTER,
                steps = listOf(
                    ScriptStep(other, CharacterAction.LOOK_AT),
                    ScriptStep(admirer, CharacterAction.SURPRISED),
                    ScriptStep(admirer, CharacterAction.SHY)
                ),
                cooldownMs = 20_000L,
                priority = 2,
                weight = 12
            )
        )
    }

    /**
     * 두 캐릭터가 가까워졌을 때의 장면.
     * 사용자가 한 명을 끌어다 다른 한 명 옆에 놓는 경우가 대부분이라,
     * 반응이 바로 나오도록 우선순위를 높이고 쿨다운을 짧게 잡았다.
     */
    private fun metScripts(type: RelationshipType, admirer: Performer?): List<InteractionScript> {
        val steps = when (type) {
            RelationshipType.LOVERS -> listOf(
                ScriptStep(Performer.BOTH, CharacterAction.LOOK_AT),
                ScriptStep(Performer.BOTH, CharacterAction.REST)
            )

            RelationshipType.FRIENDS -> listOf(
                ScriptStep(Performer.BOTH, CharacterAction.SURPRISED),
                ScriptStep(Performer.A, CharacterAction.TEASE),
                ScriptStep(Performer.B, CharacterAction.TEASE)
            )

            RelationshipType.ONE_SIDED_LOVE -> {
                val shy = admirer ?: Performer.A
                val other = if (shy == Performer.A) Performer.B else Performer.A
                listOf(
                    ScriptStep(shy, CharacterAction.SURPRISED),
                    ScriptStep(other, CharacterAction.LOOK_AT),
                    ScriptStep(shy, CharacterAction.SHY)
                )
            }

            RelationshipType.RIVALS -> listOf(
                ScriptStep(Performer.BOTH, CharacterAction.SURPRISED),
                ScriptStep(Performer.BOTH, CharacterAction.GLARE)
            )

            RelationshipType.FAMILY -> listOf(
                ScriptStep(Performer.BOTH, CharacterAction.LOOK_AT),
                ScriptStep(Performer.BOTH, CharacterAction.REST)
            )

            RelationshipType.COLLEAGUES -> listOf(
                ScriptStep(Performer.A, CharacterAction.GLANCE),
                ScriptStep(Performer.B, CharacterAction.GLANCE)
            )

            RelationshipType.CUSTOM -> listOf(
                ScriptStep(Performer.BOTH, CharacterAction.LOOK_AT),
                ScriptStep(Performer.BOTH, CharacterAction.BREATHE, 1_600L)
            )
        }

        return listOf(
            InteractionScript(
                id = "met_${type.name.lowercase()}",
                name = "마주쳤을 때",
                trigger = SceneTrigger.CHARACTERS_MET,
                steps = steps,
                cooldownMs = 9_000L,
                priority = 3,
                weight = 20
            )
        )
    }

    /**
     * 이 관계에서 쓸 수 있는 장면을 모두 모은다.
     * 금지된 동작이 하나라도 들어간 장면은 빼고, 성격에 따라 가중치를 조절한다.
     */
    fun buildFor(context: RelationshipContext): List<InteractionScript> {
        val directional: List<InteractionScript> =
            if (context.type == RelationshipType.ONE_SIDED_LOVE) {
                oneSidedScripts(context.admirer)
            } else {
                emptyList()
            }

        val candidates: List<InteractionScript> = if (context.hasPartner) {
            commonPairScripts() +
                scriptsFor(context.type) +
                directional +
                metScripts(context.type, context.admirer)
        } else {
            soloScripts()
        }

        return candidates
            .filter { it.requiresPartner == context.hasPartner || !it.requiresPartner }
            .filter { isAllowed(it, context) }
            .map { it.copy(weight = adjustedWeight(it, context)) }
    }

    /**
     * 사용자가 만든 장면도 같은 잣대로 거른다.
     * 금지한 동작이 들어갔거나, 혼자 있는데 상대가 필요한 장면은 쓰지 않는다.
     */
    fun filterUsable(
        scripts: List<InteractionScript>,
        context: RelationshipContext
    ): List<InteractionScript> = scripts
        .filter { !it.requiresPartner || context.hasPartner }
        .filter { isAllowed(it, context) }

    /** 사용자가 금지한 동작이 들어간 장면은 후보에서 뺀다. 사용자 설정이 우선이다. */
    private fun isAllowed(script: InteractionScript, context: RelationshipContext): Boolean {
        val aOk = script.actionsFor(Performer.A).all { context.a.allows(it) }
        val bOk = context.b?.let { b -> script.actionsFor(Performer.B).all { b.allows(it) } } ?: true
        return aOk && bOk
    }

    /**
     * 성격이 '얼마나 자주 나오는가'를 바꾼다.
     * 활동적이면 움직이는 장면이, 수줍으면 조심스러운 장면이 더 자주 나온다.
     */
    private fun adjustedWeight(script: InteractionScript, context: RelationshipContext): Int {
        val actions = script.steps.map { it.action }.toSet()
        var weight = script.weight

        val energy = average(context.a.energy, context.b?.energy)
        val shyness = average(context.a.shyness, context.b?.shyness)
        val mischief = average(context.a.mischief, context.b?.mischief)
        val warmth = average(context.a.warmth, context.b?.warmth)

        // 나누는 수가 클수록 성격이 결과에 덜 드러난다.
        // 기본 가중치가 10 안팎이므로 2 로 나누면 수치를 0/100 으로 몰았을 때
        // 그 장면이 거의 안 나오거나 거의 그것만 나오는 수준까지 벌어진다.
        if (actions.any { it == CharacterAction.WALK || it == CharacterAction.JUMP }) {
            weight += (energy - 50) / 2
        }
        if (actions.any { it == CharacterAction.SHY || it == CharacterAction.GLANCE }) {
            weight += (shyness - 50) / 2
        }
        if (actions.contains(CharacterAction.TEASE)) {
            weight += (mischief - 50) / 2
        }
        if (actions.any { it == CharacterAction.REST || it == CharacterAction.APPROACH }) {
            weight += (warmth - 50) / 2
        }
        return weight.coerceAtLeast(1)
    }

    private fun average(first: Int, second: Int?): Int =
        if (second == null) first else (first + second) / 2
}
