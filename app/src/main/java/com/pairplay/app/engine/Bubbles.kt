package com.pairplay.app.engine

/**
 * 캐릭터 머리 위에 띄우는 기분 표시.
 *
 * 동작만으로는 "둘이 방금 뭘 주고받았구나" 를 알아채기 어렵다.
 * 이미지 한 장으로는 표정을 바꿀 수 없으니, 작은 말풍선으로 대신 알린다.
 */
enum class BubbleSymbol {
    /** 좋아함, 애정 */
    HEART,

    /** 놀람 */
    EXCLAIM,

    /** 궁금함, 갸웃 */
    QUESTION,

    /** 졸림, 잠 */
    SLEEP,

    /** 신남, 장난 */
    SPARKLE,

    /** 음악 */
    NOTE,

    /** 머쓱함, 수줍음 */
    SWEAT,

    /** 못마땅함 */
    ANGER,

    /** 말없이 쳐다보는 중 */
    ELLIPSIS
}

/**
 * 동작에 맞는 기분 표시를 고른다.
 *
 * 순수 함수라 화면 없이 확인할 수 있다. 모든 동작을 빠짐없이 다루도록
 * when 을 전부 나열해, 동작이 늘면 컴파일러가 알려 준다.
 */
object BubbleMapper {

    fun forAction(action: CharacterAction, inScene: Boolean): BubbleSymbol? = when (action) {
        CharacterAction.SURPRISED -> BubbleSymbol.EXCLAIM
        CharacterAction.BUMP -> BubbleSymbol.EXCLAIM
        CharacterAction.DOZE -> BubbleSymbol.SLEEP
        CharacterAction.REST -> BubbleSymbol.HEART
        CharacterAction.RHYTHM -> BubbleSymbol.NOTE
        CharacterAction.PET -> BubbleSymbol.HEART
        CharacterAction.SHY -> BubbleSymbol.SWEAT
        CharacterAction.GLARE -> BubbleSymbol.ANGER
        CharacterAction.TEASE -> BubbleSymbol.SPARKLE
        CharacterAction.GLANCE -> BubbleSymbol.ELLIPSIS
        CharacterAction.LOOK_AT -> BubbleSymbol.ELLIPSIS

        // 아래는 장면 안에서 벌어질 때만 알린다.
        // 혼자 걷거나 뛰는 것까지 일일이 표시하면 화면이 시끄럽다.
        CharacterAction.APPROACH -> if (inScene) BubbleSymbol.HEART else null
        CharacterAction.JUMP -> if (inScene) BubbleSymbol.SPARKLE else null

        CharacterAction.IDLE,
        CharacterAction.BREATHE,
        CharacterAction.WALK,
        CharacterAction.LEAN -> null
    }
}
