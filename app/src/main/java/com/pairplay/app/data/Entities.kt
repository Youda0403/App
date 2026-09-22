package com.pairplay.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 자캐 한 명. 1차(오버레이/등록/기본 애니메이션)에서 쓰는 필드와
 * 2차(관계 엔진/상황극/장면 편집기)에서 쓸 필드를 처음부터 함께 가진다.
 * 2차 때 마이그레이션 없이 확장하기 위한 설계다.
 */
@Entity(tableName = "characters")
data class CharacterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,

    /** 여백을 잘라낸 표시용 이미지의 앱 전용 저장소 경로. */
    val imagePath: String,

    /** 사용자가 고른 원본 이미지 사본 경로. 크기/기준점을 다시 계산할 때 쓴다. */
    val originalImagePath: String? = null,

    /** 화면에 그릴 높이(dp). 가로는 원본 비율로 따라간다. */
    val displayHeightDp: Int = DEFAULT_HEIGHT_DP,

    /** 바닥 기준점. 이미지 가로폭 기준 0~1 비율. */
    val anchorXRatio: Float = 0.5f,

    /** 바닥 기준점. 이미지 세로 기준 0~1 비율(1이 이미지 맨 아래). */
    val anchorYRatio: Float = 1.0f,

    val flipHorizontal: Boolean = false,

    // --- 성격 (간단 키워드 + 고급 수치). 2차 관계 엔진이 읽는다. ---
    val traitKeyword: String? = null,
    val traitWarmth: Int = 50,
    val traitShyness: Int = 50,
    val traitEnergy: Int = 50,
    val traitMischief: Int = 50,
    val traitAssertiveness: Int = 50,

    /** 이 캐릭터에게 금지된 행동 id 목록. 쉼표로 구분. 2차에서 사용. */
    val blockedActions: String = "",

    /** 행동별 사용자 등록 이미지 슬롯. "action=path" 를 줄바꿈으로 구분. 2차에서 사용. */
    val animationSlots: String = "",

    /** 기본 제공 캐릭터인지 여부. 사용자가 이미지를 등록하지 않았을 때 쓴다. */
    val isBuiltIn: Boolean = false,

    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val DEFAULT_HEIGHT_DP = 140
    }
}

/**
 * 함께 띄울 두 캐릭터의 짝. 한 명만 등록했다면 [characterBId] 가 null 이다.
 */
@Entity(
    tableName = "pairs",
    indices = [Index("characterAId"), Index("characterBId")]
)
data class PairEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val characterAId: Long,
    val characterBId: Long? = null,

    /** [RelationshipType] 의 이름. 사용자 정의일 때는 CUSTOM. */
    val relationship: String = "FRIEND",

    /** 짝사랑처럼 방향이 있는 관계에서 누가 누구를 향하는지. [RelationshipDirection]. */
    val direction: String = "MUTUAL",

    /** relationship 이 CUSTOM 일 때 사용자가 붙인 이름. */
    val customRelationshipLabel: String? = null,

    val isActive: Boolean = false
)

/**
 * 상황극 장면. 2차에서 편집기와 스케줄러가 사용한다.
 * 1차에서는 테이블만 만들어 두고 기본 프리셋을 넣지 않는다.
 */
@Entity(tableName = "scenes", indices = [Index("pairId")])
data class SceneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pairId: Long? = null,
    val name: String,

    /** [SceneTrigger] 의 이름. */
    val trigger: String,

    /** 실행 조건. "key=value" 를 줄바꿈으로 구분. */
    val conditions: String = "",

    /** 순서대로 실행할 행동. "who:action:durationMs" 를 줄바꿈으로 구분. */
    val orderedActions: String = "",

    val cooldownMs: Long = 30_000L,
    val priority: Int = 0,
    val enabled: Boolean = true,
    val isBuiltIn: Boolean = false
)
