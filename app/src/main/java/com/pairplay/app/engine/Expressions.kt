package com.pairplay.app.engine

/**
 * 기분에 따라 바꿔 끼울 수 있는 표정.
 *
 * 이미지 한 장으로는 표정을 바꿀 수 없다. 그래서 사용자가 표정별로 그림을
 * 따로 등록하면, 지금 기분에 맞는 그림으로 갈아 끼운다.
 * 시메지 같은 앱들이 동작마다 그림을 따로 두는 것과 같은 방식이되,
 * **등록하지 않은 표정은 기본 그림으로 돌아가므로** 한 장만 있어도 그대로 쓸 수 있다.
 */
enum class Expression(val id: String, val label: String, val hint: String) {
    /** 등록한 표정이 없을 때 쓰는 기본 그림. 캐릭터를 만들 때 넣은 그 그림이다. */
    NEUTRAL("neutral", "기본", "평소 모습"),

    HAPPY("happy", "웃는 얼굴", "쓰다듬어 줄 때, 신날 때, 나란히 쉴 때"),

    SAD("sad", "시무룩한 얼굴", "수줍을 때, 혼자 기다릴 때"),

    ANGRY("angry", "화난 얼굴", "상대를 노려볼 때"),

    SURPRISED("surprised", "놀란 얼굴", "톡 쳤을 때, 부딪혔을 때, 집어 올렸을 때"),

    SLEEPY("sleepy", "졸린 얼굴", "졸 때");

    companion object {
        fun fromId(id: String?): Expression = entries.firstOrNull { it.id == id } ?: NEUTRAL

        /** 사용자가 따로 등록할 수 있는 표정들. 기본은 캐릭터 그림이라 빠진다. */
        val registerable: List<Expression> get() = entries.filter { it != NEUTRAL }
    }
}

/**
 * 동작에 맞는 표정을 고른다.
 *
 * 순수 함수라 화면 없이 확인할 수 있다. 모든 동작을 빠짐없이 나열해,
 * 동작이 늘면 컴파일러가 알려 준다.
 */
object ExpressionMapper {

    fun forAction(action: CharacterAction): Expression = when (action) {
        CharacterAction.SURPRISED,
        CharacterAction.BUMP,
        CharacterAction.DANGLE -> Expression.SURPRISED

        CharacterAction.PET,
        CharacterAction.REST,
        CharacterAction.JUMP,
        CharacterAction.RHYTHM,
        CharacterAction.TEASE,
        CharacterAction.APPROACH -> Expression.HAPPY

        CharacterAction.SHY,
        CharacterAction.SULK -> Expression.SAD

        CharacterAction.GLARE -> Expression.ANGRY

        CharacterAction.DIZZY,
        CharacterAction.FALL -> Expression.SURPRISED

        CharacterAction.DOZE -> Expression.SLEEPY

        // 평소 모습으로 두는 동작들.
        CharacterAction.IDLE,
        CharacterAction.BREATHE,
        CharacterAction.WALK,
        CharacterAction.LOOK_AT,
        CharacterAction.GLANCE,
        CharacterAction.LEAN -> Expression.NEUTRAL
    }
}

/**
 * 표정별 그림 경로를 한 줄짜리 문자열로 담고 꺼낸다.
 *
 * `표정id=경로` 를 줄바꿈으로 구분한다. 데이터베이스 칸 하나에 넣기 위해서다.
 * 표정이 늘어도 데이터베이스 구조를 바꾸지 않아도 된다.
 */
object ExpressionSlots {

    fun parse(text: String): Map<Expression, String> {
        if (text.isBlank()) return emptyMap()
        val out = LinkedHashMap<Expression, String>()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val at = trimmed.indexOf('=')
            if (at <= 0 || at == trimmed.length - 1) continue
            val expression = Expression.entries
                .firstOrNull { it.id == trimmed.substring(0, at).trim() }
                ?: continue
            if (expression == Expression.NEUTRAL) continue
            out[expression] = trimmed.substring(at + 1).trim()
        }
        return out
    }

    fun write(slots: Map<Expression, String>): String = slots.entries
        .filter { it.key != Expression.NEUTRAL && it.value.isNotBlank() }
        .sortedBy { it.key.ordinal }
        .joinToString("\n") { "${it.key.id}=${it.value}" }

    /** 한 표정의 그림을 바꾼다. [path] 가 null 이면 그 표정을 지운다. */
    fun with(text: String, expression: Expression, path: String?): String {
        val slots = LinkedHashMap(parse(text))
        if (path.isNullOrBlank()) slots.remove(expression) else slots[expression] = path
        return write(slots)
    }
}
