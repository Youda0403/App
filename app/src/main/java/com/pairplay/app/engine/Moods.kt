package com.pairplay.app.engine

/**
 * 동작에 맞는 기분 표시를 고른다.
 *
 * 말풍선을 씌우지 않고 기호만 띄운다. 말풍선은 캐릭터보다 커 보이고,
 * 두 캐릭터의 크기가 다르면 말풍선 크기까지 달라 보여 어수선했다.
 *
 * 순수 함수라 화면 없이 확인할 수 있다. 모든 동작을 빠짐없이 다루도록
 * when 을 전부 나열해, 동작이 늘면 컴파일러가 알려 준다.
 */
object MoodMapper {

    fun forAction(action: CharacterAction, inScene: Boolean): EffectKind? = when (action) {
        // 짧고 분명한 순간에만 띄운다.
        CharacterAction.SURPRISED -> EffectKind.EXCLAIM
        CharacterAction.PET -> EffectKind.HEART
        CharacterAction.SHY -> EffectKind.SWEAT
        CharacterAction.GLARE -> EffectKind.ANGER
        // ✨ 는 '신난다' 가 아니라 '번뜩였다' 는 뜻이다.
        // 장난칠 생각이 떠오른 순간이 여기에 딱 맞는다.
        CharacterAction.TEASE -> EffectKind.SPARKLE
        CharacterAction.DOZE -> EffectKind.SLEEP
        CharacterAction.DIZZY -> EffectKind.SWIRL
        CharacterAction.SULK -> EffectKind.SWEAT

        // 장면 안에서 벌어질 때만 알린다.
        CharacterAction.REST -> if (inScene) EffectKind.HEART else null
        CharacterAction.APPROACH -> if (inScene) EffectKind.HEART else null

        // 아래는 너무 자주 나와서 띄우지 않는다.
        // 쳐다보거나 걷는 것까지 일일이 알리면 표시가 흔해져 재미가 없어진다.
        // 부딪힘은 컨트롤러가 부딪히는 그 순간에 직접 느낌표를 띄운다.
        // 여기서 또 띄우면 느낌표가 두 개씩 뜬다.
        CharacterAction.BUMP,
        CharacterAction.GLANCE,
        CharacterAction.LOOK_AT,
        CharacterAction.RHYTHM,
        CharacterAction.JUMP,
        CharacterAction.IDLE,
        CharacterAction.BREATHE,
        CharacterAction.WALK,
        CharacterAction.DANGLE,
        // 떨어지는 중에는 표시가 따라오지 못한다. 착지할 때 컨트롤러가 띄운다.
        CharacterAction.FALL,
        CharacterAction.LEAN -> null
    }

    /**
     * 같은 표시를 연달아 띄우지 않기 위한 최소 간격.
     * 표시는 가끔 나와야 눈에 들어온다.
     */
    const val MIN_INTERVAL_MS = 9_000L
}
