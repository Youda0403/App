package com.pairplay.app.engine

/**
 * 사용자가 휴대폰에 하는 행동들.
 *
 * 음악 재생처럼, 앱 바깥에서 벌어지는 일에 캐릭터가 반응하게 하는 재료다.
 * 모두 추가 권한 없이 알 수 있는 것들만 골랐다.
 */
enum class DeviceEvent(val id: String, val label: String) {
    /** 휴대폰을 흔들었다. */
    SHAKE("shake", "흔들기"),

    /** 캐릭터를 톡톡 두 번 쳤다. */
    DOUBLE_TAP("double_tap", "두 번 톡톡"),

    /** 충전기를 꽂았다. */
    CHARGER_ON("charger_on", "충전기 연결"),

    /** 충전기를 뺐다. */
    CHARGER_OFF("charger_off", "충전기 분리"),

    /** 이어폰을 꽂았다. */
    HEADSET_ON("headset_on", "이어폰 연결"),

    /** 이어폰을 뺐다. */
    HEADSET_OFF("headset_off", "이어폰 분리"),

    /** 잠금을 풀었다. 주인이 돌아온 셈이다. */
    UNLOCKED("unlocked", "잠금 해제")
}

/** 한 번의 반응. 무슨 동작을 하고 어떤 기호를 띄울지. */
data class Reaction(
    val action: CharacterAction,
    val effect: EffectKind?,
    val effectCount: Int = 1
)

/**
 * 사건과 성격을 보고 어떻게 반응할지 정한다.
 *
 * 같은 일이 벌어져도 성격에 따라 다르게 반응해야 둘이 달라 보인다.
 * 순수 함수라 화면 없이 확인할 수 있고, 사용자가 금지한 동작은 절대 고르지 않는다.
 */
object ReactionMapper {

    /** 이 수치를 넘으면 그 성격다운 반응이 나온다. */
    const val STRONG = 60

    fun forEvent(event: DeviceEvent, traits: CharacterTraits): Reaction {
        val raw = rawReaction(event, traits)
        // 사용자가 막아 둔 동작이면 가장 무난한 반응으로 바꾼다.
        if (traits.allows(raw.action)) return raw
        val fallback = CharacterAction.SURPRISED
        if (traits.allows(fallback)) return raw.copy(action = fallback)
        return raw.copy(action = CharacterAction.IDLE)
    }

    private fun rawReaction(event: DeviceEvent, traits: CharacterTraits): Reaction = when (event) {
        // 흔들면 장난기 많은 아이는 신나고, 수줍은 아이는 움츠러든다.
        DeviceEvent.SHAKE -> when {
            traits.mischief >= STRONG ->
                Reaction(CharacterAction.JUMP, EffectKind.SPARKLE, 3)

            traits.shyness >= STRONG ->
                Reaction(CharacterAction.SHY, EffectKind.SWEAT, 1)

            else -> Reaction(CharacterAction.SURPRISED, EffectKind.EXCLAIM, 2)
        }

        // 톡톡 두 번은 확실한 애정 표현이다.
        DeviceEvent.DOUBLE_TAP -> when {
            traits.mischief >= STRONG ->
                Reaction(CharacterAction.TEASE, EffectKind.SPARKLE, 2)

            traits.shyness >= STRONG ->
                Reaction(CharacterAction.SHY, EffectKind.HEART, 2)

            else -> Reaction(CharacterAction.JUMP, EffectKind.HEART, 3)
        }

        // 충전기는 밥 같은 것이다.
        DeviceEvent.CHARGER_ON -> if (traits.energy >= STRONG) {
            Reaction(CharacterAction.JUMP, EffectKind.SPARKLE, 3)
        } else {
            Reaction(CharacterAction.SURPRISED, EffectKind.SPARKLE, 2)
        }

        DeviceEvent.CHARGER_OFF -> Reaction(CharacterAction.LOOK_AT, EffectKind.QUESTION, 1)

        // 이어폰을 꽂으면 음악을 기대하며 리듬을 탄다.
        DeviceEvent.HEADSET_ON -> Reaction(CharacterAction.RHYTHM, EffectKind.NOTE, 3)

        DeviceEvent.HEADSET_OFF -> Reaction(CharacterAction.GLANCE, EffectKind.QUESTION, 1)

        // 잠금을 풀면 주인이 돌아온 것이다.
        DeviceEvent.UNLOCKED -> when {
            traits.warmth >= STRONG -> Reaction(CharacterAction.JUMP, EffectKind.HEART, 3)
            traits.shyness >= STRONG -> Reaction(CharacterAction.GLANCE, EffectKind.SPARKLE, 1)
            else -> Reaction(CharacterAction.SURPRISED, EffectKind.HEART, 2)
        }
    }
}
