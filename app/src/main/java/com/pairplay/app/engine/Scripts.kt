package com.pairplay.app.engine

import com.pairplay.app.data.RelationshipDirection
import com.pairplay.app.data.RelationshipType
import com.pairplay.app.data.SceneTrigger

/** 장면에서 누가 움직이는지. */
enum class Performer {
    A,
    B,
    BOTH
}

/**
 * 장면을 이루는 한 마디.
 * 앞 마디가 끝나야 다음 마디가 시작된다. 그래서 'A가 졸기 -> B가 바라보기 ->
 * 다가가기 -> 나란히 쉬기' 같은 연속 동작이 순서대로 나온다.
 */
data class ScriptStep(
    val performer: Performer,
    val action: CharacterAction,
    /** 0 이면 동작의 기본 길이를 쓴다. */
    val durationMs: Long = 0L
) {
    fun resolvedDuration(): Long = if (durationMs > 0L) durationMs else action.defaultDurationMs
}

/**
 * 상황극 한 편.
 *
 * 설계서의 '행동별 실행 조건·재생 시간·쿨다운·우선순위'를 담는다.
 * 사용자가 만든 장면([com.pairplay.app.data.SceneEntity])도 같은 모양으로 바뀌어
 * 같은 스케줄러를 탄다.
 */
data class InteractionScript(
    val id: String,
    val name: String,
    val trigger: SceneTrigger,
    val steps: List<ScriptStep>,
    /** 한 번 나온 뒤 다시 나오기까지 최소한 기다릴 시간. */
    val cooldownMs: Long = 45_000L,
    /** 높을수록 먼저 잡힌다. 같은 순위 안에서는 가중치로 뽑는다. */
    val priority: Int = 0,
    val weight: Int = 10,
    /** 두 명이 있어야 말이 되는 장면인지. */
    val requiresPartner: Boolean = true
) {
    /** 이 장면에서 [performer] 가 실제로 하는 동작들. */
    fun actionsFor(performer: Performer): Set<CharacterAction> =
        steps.filter { it.performer == performer || it.performer == Performer.BOTH }
            .map { it.action }
            .toSet()

    val totalDurationMs: Long get() = steps.sumOf { it.resolvedDuration() }
}

/** 관계 엔진이 보는 캐릭터 한 명의 성향. */
data class CharacterTraits(
    val warmth: Int = 50,
    val shyness: Int = 50,
    val energy: Int = 50,
    val mischief: Int = 50,
    val assertiveness: Int = 50,
    /** 사용자가 이 캐릭터에게 금지한 동작 id. */
    val blockedActions: Set<String> = emptySet()
) {
    fun allows(action: CharacterAction): Boolean = action.id !in blockedActions
}

/**
 * 어떤 관계의 두 캐릭터인지.
 * 관계는 사용자가 정하며 앱이 마음대로 바꾸지 않는다.
 */
data class RelationshipContext(
    val type: RelationshipType,
    val direction: RelationshipDirection,
    val a: CharacterTraits,
    val b: CharacterTraits?
) {
    val hasPartner: Boolean get() = b != null

    /** 짝사랑에서 마음을 품은 쪽. 방향이 없는 관계면 null. */
    val admirer: Performer?
        get() = when {
            type != RelationshipType.ONE_SIDED_LOVE -> null
            direction == RelationshipDirection.A_TO_B -> Performer.A
            direction == RelationshipDirection.B_TO_A -> Performer.B
            else -> null
        }
}
