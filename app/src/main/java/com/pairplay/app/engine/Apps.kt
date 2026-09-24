package com.pairplay.app.engine

/**
 * 지금 쓰고 있는 앱의 종류.
 *
 * 앱 **이름(패키지)** 만 보고 정한다. 앱 안에서 무엇을 보고 있는지는 알 수 없고,
 * 알려고 하지도 않는다.
 */
enum class AppCategory(val id: String, val label: String) {
    VIDEO("video", "영상"),
    MESSENGER("messenger", "메신저"),
    SOCIAL("social", "SNS"),
    GAME("game", "게임"),
    MUSIC("music", "음악"),
    CAMERA("camera", "카메라"),

    /** 은행·결제·증권처럼 남이 보면 곤란한 앱. 여기서는 캐릭터가 숨는다. */
    SENSITIVE("sensitive", "은행·결제 (숨기)"),

    /** 반응하지 않는다. 사용자가 '이 앱에선 가만히 있어' 라고 정할 때도 쓴다. */
    NONE("none", "반응 안 함");

    /** 이 앱을 쓰는 동안 캐릭터가 숨어야 하는지. */
    val hides: Boolean get() = this == SENSITIVE

    companion object {
        fun fromId(id: String?): AppCategory? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 앱이 어떤 종류인지 정한다.
 *
 * 순서는 이렇다.
 * 1. 사용자가 직접 정한 것 (가장 우선)
 * 2. 자주 쓰는 앱을 미리 정리해 둔 목록
 * 3. 이름에 bank·pay·wallet 같은 말이 들어 있으면 은행·결제로 본다
 * 4. 앱이 스스로 밝힌 종류 (게임/영상/음악 등, 안드로이드가 알려 줌)
 * 5. 그래도 모르면 반응하지 않는다
 *
 * 순수 함수라 화면 없이 확인할 수 있다.
 */
object AppRules {

    fun resolve(
        packageName: String,
        overrides: Map<String, AppCategory>,
        platformHint: AppCategory?
    ): AppCategory {
        overrides[packageName]?.let { return it }
        DEFAULTS[packageName]?.let { return it }
        if (looksSensitive(packageName)) return AppCategory.SENSITIVE
        return platformHint ?: AppCategory.NONE
    }

    /**
     * 이름만 보고 은행·결제·본인 인증 앱인지 짐작한다.
     *
     * 목록에 없는 은행 앱도 많아서, 이름 조각에 bank 가 들어가거나 pay 로 시작·끝나면
     * 숨는 쪽으로 판단한다. 은행 앱이 본인 확인을 위해 여는 인증 앱(PASS, OTP,
     * 인증서)도 이름·생년월일이 뜨는 화면이라 함께 숨는다.
     * 잘못 걸려도 사용자가 앱별 설정에서 바꿀 수 있다.
     * 숨지 않아야 할 때 숨는 것이, 숨어야 할 때 안 숨는 것보다 덜 곤란하다.
     */
    fun looksSensitive(packageName: String): Boolean =
        packageName.lowercase().split('.').any { part ->
            "bank" in part ||
                "wallet" in part ||
                "auth" in part ||
                "cert" in part ||
                part == "pay" || part.startsWith("pay") || part.endsWith("pay") ||
                part == "otp" || part.startsWith("otp") || part.endsWith("otp") ||
                part.endsWith("card")
        }

    // ------------------------------------------------------------------ 저장 형식

    /** `패키지=종류` 를 줄바꿈으로 구분한다. 설정 한 칸에 넣기 위해서다. */
    fun parse(text: String): Map<String, AppCategory> {
        if (text.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, AppCategory>()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            val at = trimmed.indexOf('=')
            if (at <= 0 || at == trimmed.length - 1) continue
            val category = AppCategory.fromId(trimmed.substring(at + 1).trim()) ?: continue
            out[trimmed.substring(0, at).trim()] = category
        }
        return out
    }

    fun write(rules: Map<String, AppCategory>): String = rules.entries
        .filter { it.key.isNotBlank() }
        .sortedBy { it.key }
        .joinToString("\n") { "${it.key}=${it.value.id}" }

    /** 한 앱의 종류를 정한다. [category] 가 null 이면 직접 정한 것을 지우고 기본값으로 돌린다. */
    fun with(text: String, packageName: String, category: AppCategory?): String {
        val rules = LinkedHashMap(parse(text))
        if (category == null) rules.remove(packageName) else rules[packageName] = category
        return write(rules)
    }

    /**
     * 자주 쓰는 앱들. 정확한 이름이 확실한 것만 넣었다.
     * 여기에 없는 앱도 위의 이름 짐작과 안드로이드가 알려 주는 종류로 대부분 잡힌다.
     */
    val DEFAULTS: Map<String, AppCategory> = mapOf(
        // 영상
        "com.google.android.youtube" to AppCategory.VIDEO,
        "com.google.android.apps.youtube.kids" to AppCategory.VIDEO,
        "com.netflix.mediaclient" to AppCategory.VIDEO,
        "com.disney.disneyplus" to AppCategory.VIDEO,
        "tv.twitch.android.app" to AppCategory.VIDEO,
        "com.frograms.watcha" to AppCategory.VIDEO,
        "net.cj.cjhv.gs.tving" to AppCategory.VIDEO,
        "kr.co.captv.pooqV2" to AppCategory.VIDEO,
        "com.coupang.mobile.play" to AppCategory.VIDEO,

        // 메신저
        "com.kakao.talk" to AppCategory.MESSENGER,
        "com.discord" to AppCategory.MESSENGER,
        "org.telegram.messenger" to AppCategory.MESSENGER,
        "jp.naver.line.android" to AppCategory.MESSENGER,
        "com.whatsapp" to AppCategory.MESSENGER,
        "com.facebook.orca" to AppCategory.MESSENGER,
        "com.google.android.apps.messaging" to AppCategory.MESSENGER,
        "com.samsung.android.messaging" to AppCategory.MESSENGER,

        // SNS
        "com.instagram.android" to AppCategory.SOCIAL,
        "com.instagram.barcelona" to AppCategory.SOCIAL,
        "com.twitter.android" to AppCategory.SOCIAL,
        "com.zhiliaoapp.musically" to AppCategory.SOCIAL,
        "com.ss.android.ugc.trill" to AppCategory.SOCIAL,
        "com.facebook.katana" to AppCategory.SOCIAL,
        "com.pinterest" to AppCategory.SOCIAL,

        // 음악
        "com.spotify.music" to AppCategory.MUSIC,
        "com.google.android.apps.youtube.music" to AppCategory.MUSIC,
        "com.iloen.melon" to AppCategory.MUSIC,
        "com.ktmusic.geniemusic" to AppCategory.MUSIC,
        "skplanet.musicmate" to AppCategory.MUSIC,
        "com.neowiz.android.bugs" to AppCategory.MUSIC,
        "com.apple.android.music" to AppCategory.MUSIC,

        // 카메라
        "com.sec.android.app.camera" to AppCategory.CAMERA,
        "com.google.android.GoogleCamera" to AppCategory.CAMERA,
        "com.android.camera" to AppCategory.CAMERA,
        "com.android.camera2" to AppCategory.CAMERA,
        "com.snowcorp.snow" to AppCategory.CAMERA,
        "com.linecorp.b612.android" to AppCategory.CAMERA,

        // 은행·결제·인증 (이름 짐작으로 안 잡히는 것)
        "viva.republica.toss" to AppCategory.SENSITIVE,
        "com.samsung.android.spay" to AppCategory.SENSITIVE,
        "com.kiwoom.heromts" to AppCategory.SENSITIVE
    )
}

/**
 * 앱 종류에 맞춰 어떻게 반응할지 정한다.
 *
 * 앱에 들어간 순간 한 번 반응한다. 숨어야 하는 앱이나 '반응 안 함' 이면 null.
 * 성격에 따라 반응이 달라지고, 금지한 동작은 절대 고르지 않는다.
 * ✨ 는 '번뜩' 이라는 뜻이라 여기서는 쓰지 않는다.
 */
object AppReactionMapper {

    fun forCategory(category: AppCategory, traits: CharacterTraits): Reaction? {
        val raw = rawReaction(category, traits) ?: return null
        if (traits.allows(raw.action)) return raw
        val fallback = CharacterAction.LOOK_AT
        if (traits.allows(fallback)) return raw.copy(action = fallback)
        return raw.copy(action = CharacterAction.IDLE)
    }

    private fun rawReaction(category: AppCategory, traits: CharacterTraits): Reaction? =
        when (category) {
            // 옆에 자리 잡고 같이 본다.
            AppCategory.VIDEO -> Reaction(CharacterAction.REST, EffectKind.FLOWER, 1)

            // 누구랑 얘기하나 궁금해서 힐끗 본다. 수줍은 아이는 괜히 머쓱해한다.
            AppCategory.MESSENGER -> if (traits.shyness >= ReactionMapper.STRONG) {
                Reaction(CharacterAction.SHY, EffectKind.SWEAT, 1)
            } else {
                Reaction(CharacterAction.GLANCE, EffectKind.QUESTION, 1)
            }

            // 구경한다.
            AppCategory.SOCIAL -> Reaction(CharacterAction.LOOK_AT, EffectKind.HEART, 1)

            // 신나서 응원한다. 기운 없는 아이는 박자만 탄다.
            AppCategory.GAME -> if (traits.energy >= ReactionMapper.STRONG / 2) {
                Reaction(CharacterAction.JUMP, EffectKind.FLOWER, 2)
            } else {
                Reaction(CharacterAction.RHYTHM, EffectKind.FLOWER, 1)
            }

            AppCategory.MUSIC -> Reaction(CharacterAction.RHYTHM, EffectKind.NOTE, 2)

            // 사진 찍는다니 폴짝 포즈를 잡는다. 수줍은 아이는 부끄러워한다.
            AppCategory.CAMERA -> if (traits.shyness >= ReactionMapper.STRONG) {
                Reaction(CharacterAction.SHY, EffectKind.SWEAT, 1)
            } else {
                Reaction(CharacterAction.JUMP, EffectKind.HEART, 2)
            }

            // 숨는 건 반응이 아니라 사라지는 것이다. 컨트롤러가 따로 처리한다.
            AppCategory.SENSITIVE -> null
            AppCategory.NONE -> null
        }
}
