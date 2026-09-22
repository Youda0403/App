package com.pairplay.app.data

/** 두 캐릭터의 관계. 사용자가 고르며 앱이 임의로 바꾸지 않는다. */
enum class RelationshipType {
    LOVERS,
    FRIENDS,
    ONE_SIDED_LOVE,
    RIVALS,
    FAMILY,
    COLLEAGUES,
    CUSTOM;

    /** 방향(누가 누구를)을 지정해야 의미가 있는 관계인지. */
    val needsDirection: Boolean
        get() = this == ONE_SIDED_LOVE

    companion object {
        fun fromName(name: String?): RelationshipType =
            entries.firstOrNull { it.name == name } ?: FRIENDS
    }
}

enum class RelationshipDirection {
    A_TO_B,
    B_TO_A,
    MUTUAL;

    companion object {
        fun fromName(name: String?): RelationshipDirection =
            entries.firstOrNull { it.name == name } ?: MUTUAL
    }
}

/** 장면을 시작시키는 사건. 2차 장면 편집기에서 사용한다. */
enum class SceneTrigger {
    TAP_CHARACTER,
    IDLE_TIMER,
    MUSIC_STARTED,
    MUSIC_STOPPED,
    MUSIC_TRACK_CHANGED,
    MANUAL;

    companion object {
        fun fromName(name: String?): SceneTrigger =
            entries.firstOrNull { it.name == name } ?: MANUAL
    }
}

/** 오버레이에 누구를 띄울지. */
enum class OverlayMode {
    SINGLE,
    PAIR
}
