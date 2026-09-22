package com.pairplay.app.ui.screens

import com.pairplay.app.data.SceneTrigger
import com.pairplay.app.engine.CharacterAction
import com.pairplay.app.engine.Performer

/**
 * 화면에 보여 줄 이름들.
 *
 * when 을 빠짐없이 나열해 두어, 동작이나 사건이 늘어나면 컴파일러가
 * 이름을 빠뜨렸다고 알려 준다.
 */
internal fun actionLabel(action: CharacterAction): String = when (action) {
    CharacterAction.IDLE -> "가만히 있기"
    CharacterAction.BREATHE -> "숨쉬기"
    CharacterAction.JUMP -> "점프"
    CharacterAction.WALK -> "걷기"
    CharacterAction.APPROACH -> "다가가기"
    CharacterAction.LOOK_AT -> "바라보기"
    CharacterAction.DOZE -> "졸기"
    CharacterAction.RHYTHM -> "리듬 타기"
    CharacterAction.SURPRISED -> "놀라기"
    CharacterAction.BUMP -> "부딪히기"
    CharacterAction.LEAN -> "기대기"
    CharacterAction.PET -> "쓰다듬김"
    CharacterAction.DANGLE -> "매달리기"
    CharacterAction.GLANCE -> "힐끗 보기"
    CharacterAction.TEASE -> "장난치기"
    CharacterAction.SHY -> "수줍어하기"
    CharacterAction.REST -> "나란히 쉬기"
    CharacterAction.GLARE -> "노려보기"
}

internal fun triggerLabel(trigger: SceneTrigger): String = when (trigger) {
    SceneTrigger.TAP_CHARACTER -> "캐릭터를 톡 쳤을 때"
    SceneTrigger.IDLE_TIMER -> "가만히 있을 때 가끔"
    SceneTrigger.MUSIC_STARTED -> "음악이 시작될 때"
    SceneTrigger.MUSIC_STOPPED -> "음악이 멈출 때"
    SceneTrigger.MUSIC_TRACK_CHANGED -> "곡이 바뀔 때"
    SceneTrigger.CHARACTERS_MET -> "둘이 가까워졌을 때"
    SceneTrigger.MANUAL -> "직접 실행할 때만"
}

/** 장면 편집기에서 고를 수 있는 사건. 아직 동작하지 않는 것은 빼 두었다. */
internal val EDITABLE_TRIGGERS = listOf(
    SceneTrigger.IDLE_TIMER,
    SceneTrigger.TAP_CHARACTER,
    SceneTrigger.CHARACTERS_MET,
    SceneTrigger.MUSIC_STARTED,
    SceneTrigger.MANUAL
)

/**
 * 사용자가 막거나 장면에 넣을 수 있는 행동.
 * 숨쉬기처럼 막으면 캐릭터가 멈춰 보이는 기본 동작과,
 * 앱이 알아서 넣는 동작(부딪히기, 쓰다듬김)은 뺐다.
 */
internal val CONFIGURABLE_ACTIONS = listOf(
    CharacterAction.WALK,
    CharacterAction.JUMP,
    CharacterAction.APPROACH,
    CharacterAction.LOOK_AT,
    CharacterAction.GLANCE,
    CharacterAction.DOZE,
    CharacterAction.RHYTHM,
    CharacterAction.TEASE,
    CharacterAction.SHY,
    CharacterAction.REST,
    CharacterAction.GLARE,
    CharacterAction.LEAN
)

/** 장면에 넣을 수 있는 행동. 위 목록에 '놀라기', '가만히 있기' 를 더한다. */
internal val SCENE_ACTIONS = listOf(
    CharacterAction.IDLE,
    CharacterAction.SURPRISED
) + CONFIGURABLE_ACTIONS

internal fun performerLabel(
    performer: Performer,
    nameA: String,
    nameB: String?
): String = when (performer) {
    Performer.A -> nameA
    Performer.B -> nameB ?: "둘째"
    Performer.BOTH -> "함께"
}
