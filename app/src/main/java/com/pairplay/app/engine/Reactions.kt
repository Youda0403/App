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
    UNLOCKED("unlocked", "잠금 해제"),

    /** 소리를 키웠다. */
    VOLUME_UP("volume_up", "소리 키우기"),

    /** 소리를 줄였다. */
    VOLUME_DOWN("volume_down", "소리 줄이기"),

    /** 배터리가 얼마 남지 않았다. */
    BATTERY_LOW("battery_low", "배터리 부족"),

    /** 충전이 다 됐다. */
    BATTERY_FULL("battery_full", "충전 완료"),

    /** 화면을 가로/세로로 돌렸다. */
    ROTATED("rotated", "화면 돌리기")
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

    /** 어지러움을 털어낼 만큼 팔팔한 기준. */
    const val VERY_STRONG = 75

    fun forEvent(event: DeviceEvent, traits: CharacterTraits): Reaction {
        val raw = rawReaction(event, traits)
        // 사용자가 막아 둔 동작이면 가장 무난한 반응으로 바꾼다.
        if (traits.allows(raw.action)) return raw
        val fallback = CharacterAction.SURPRISED
        if (traits.allows(fallback)) return raw.copy(action = fallback)
        return raw.copy(action = CharacterAction.IDLE)
    }

    private fun rawReaction(event: DeviceEvent, traits: CharacterTraits): Reaction = when (event) {
        // 흔들면 어지러워한다. 활동적인 아이는 금방 털고 일어나 신나한다.
        DeviceEvent.SHAKE -> if (traits.energy >= VERY_STRONG) {
            Reaction(CharacterAction.JUMP, EffectKind.FLOWER, 2)
        } else {
            Reaction(CharacterAction.DIZZY, EffectKind.SWIRL, 1)
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
            Reaction(CharacterAction.JUMP, EffectKind.FLOWER, 3)
        } else {
            Reaction(CharacterAction.SURPRISED, EffectKind.FLOWER, 2)
        }

        // 뺏기면 아쉬워한다. 어리둥절해하는 것보다 이쪽이 사람 같다.
        DeviceEvent.CHARGER_OFF -> if (traits.mischief >= STRONG) {
            Reaction(CharacterAction.GLARE, EffectKind.ANGER, 1)
        } else {
            Reaction(CharacterAction.SULK, EffectKind.SWEAT, 1)
        }

        // 이어폰을 꽂으면 음악을 기대하며 리듬을 탄다.
        DeviceEvent.HEADSET_ON -> Reaction(CharacterAction.RHYTHM, EffectKind.NOTE, 3)

        // 음악이 끊기면 아쉬워한다.
        DeviceEvent.HEADSET_OFF -> Reaction(CharacterAction.SULK, EffectKind.SWEAT, 1)

        // 잠금을 풀면 주인이 돌아온 것이다.
        DeviceEvent.UNLOCKED -> when {
            traits.warmth >= STRONG -> Reaction(CharacterAction.JUMP, EffectKind.HEART, 3)
            traits.shyness >= STRONG -> Reaction(CharacterAction.GLANCE, EffectKind.HEART, 1)
            else -> Reaction(CharacterAction.SURPRISED, EffectKind.HEART, 2)
        }

        // 소리를 키우면 신나서 리듬을 타고, 줄이면 조용해진다.
        DeviceEvent.VOLUME_UP -> Reaction(CharacterAction.RHYTHM, EffectKind.NOTE, 2)
        DeviceEvent.VOLUME_DOWN -> Reaction(CharacterAction.GLANCE, EffectKind.NOTE, 1)

        // 배터리가 얼마 없으면 기운이 빠진다.
        DeviceEvent.BATTERY_LOW -> Reaction(CharacterAction.SULK, EffectKind.SWEAT, 2)

        // 다 충전되면 기분이 좋다.
        DeviceEvent.BATTERY_FULL -> Reaction(CharacterAction.JUMP, EffectKind.FLOWER, 3)

        // 화면을 돌리면 휘청한다.
        DeviceEvent.ROTATED -> Reaction(CharacterAction.SURPRISED, EffectKind.EXCLAIM, 1)
    }
}
