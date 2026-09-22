package com.pairplay.app.engine

import com.pairplay.app.data.SceneEntity
import com.pairplay.app.data.SceneTrigger

/**
 * 사용자가 만든 장면([SceneEntity])과 스케줄러가 쓰는 장면([InteractionScript]) 사이를 옮긴다.
 *
 * 저장은 사람이 읽을 수 있는 한 줄짜리 형식으로 한다.
 *   `누가:행동:길이ms`  (예: `A:approach:0`)
 * 길이가 0 이면 그 행동의 기본 길이를 쓴다.
 *
 * 화면이나 데이터베이스 없이 확인할 수 있도록 순수 함수로 두었다.
 */
object SceneCodec {

    private const val STEP_SEPARATOR = "\n"
    private const val FIELD_SEPARATOR = ":"

    fun encodeSteps(steps: List<ScriptStep>): String =
        steps.joinToString(STEP_SEPARATOR) { step ->
            listOf(
                step.performer.name,
                step.action.id,
                step.durationMs.toString()
            ).joinToString(FIELD_SEPARATOR)
        }

    /**
     * 저장된 문자열을 마디 목록으로 되돌린다.
     * 알아볼 수 없는 줄은 조용히 버린다. 앱을 되돌려 설치했거나 값이 깨졌을 때
     * 장면 전체를 잃는 것보다 낫다.
     */
    fun decodeSteps(encoded: String): List<ScriptStep> =
        encoded.split(STEP_SEPARATOR)
            .mapNotNull { line -> decodeStep(line.trim()) }

    private fun decodeStep(line: String): ScriptStep? {
        if (line.isEmpty()) return null
        val parts = line.split(FIELD_SEPARATOR)
        if (parts.size < 2) return null

        val performer = Performer.entries.firstOrNull { it.name == parts[0].uppercase() }
            ?: return null
        val action = CharacterAction.entries.firstOrNull { it.id == parts[1] }
            ?: return null
        val duration = parts.getOrNull(2)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

        return ScriptStep(performer, action, duration)
    }

    /**
     * 사용자가 만든 장면을 스케줄러가 쓸 수 있는 형태로 바꾼다.
     * 마디가 하나도 없으면 돌릴 수 없으므로 null 을 돌려준다.
     */
    fun toScript(entity: SceneEntity): InteractionScript? {
        val steps = decodeSteps(entity.orderedActions)
        if (steps.isEmpty()) return null

        return InteractionScript(
            id = userScriptId(entity.id),
            name = entity.name.ifBlank { "이름 없는 장면" },
            trigger = SceneTrigger.fromName(entity.trigger),
            steps = steps,
            cooldownMs = entity.cooldownMs.coerceAtLeast(MIN_COOLDOWN_MS),
            // 사용자가 만든 장면을 기본 장면보다 먼저 쓴다.
            // 설계서의 '사용자 설정이 기본 프리셋보다 우선한다' 원칙이다.
            priority = entity.priority + USER_PRIORITY_BONUS,
            weight = USER_WEIGHT,
            requiresPartner = steps.any {
                it.performer == Performer.B || it.performer == Performer.BOTH
            }
        )
    }

    fun userScriptId(sceneId: Long): String = "user_$sceneId"

    fun isUserScript(scriptId: String?): Boolean = scriptId?.startsWith("user_") == true

    /** 새 장면을 만들 때 쓰는 기본 뼈대. 빈 화면보다 고치기 쉽다. */
    fun defaultSteps(): List<ScriptStep> = listOf(
        ScriptStep(Performer.A, CharacterAction.LOOK_AT),
        ScriptStep(Performer.A, CharacterAction.APPROACH),
        ScriptStep(Performer.BOTH, CharacterAction.REST)
    )

    const val MIN_COOLDOWN_MS = 5_000L
    private const val USER_PRIORITY_BONUS = 5
    private const val USER_WEIGHT = 24
}
