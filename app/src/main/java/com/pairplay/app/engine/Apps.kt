package com.pairplay.app.engine

/**
 * 지금 쓰는 앱에서 캐릭터가 숨어야 하는지.
 *
 * 앱 **이름(패키지)** 만 보고 정한다. 앱 안에서 무엇을 보고 있는지는 알 수 없고,
 * 알려고 하지도 않는다.
 *
 * 예전에는 영상·메신저·게임 같은 종류마다 다르게 반응했는데, 반응의 뜻을 알아보기
 * 어렵고 번거롭기만 해서 뺐다. 지금은 은행·결제처럼 남이 보면 곤란한 앱에서
 * 숨는 것만 한다.
 */
enum class AppCategory(val id: String, val label: String) {
    /** 은행·결제·인증처럼 남이 보면 곤란한 앱. 여기서는 캐릭터가 숨는다. */
    SENSITIVE("sensitive", "숨기"),

    /** 평소대로 둔다. */
    NONE("none", "숨지 않기");

    /** 이 앱을 쓰는 동안 캐릭터가 숨어야 하는지. */
    val hides: Boolean get() = this == SENSITIVE

    companion object {
        /** 예전 종류(video, game 등)로 저장된 값은 알아보지 못한 것으로 보고 버린다. */
        fun fromId(id: String?): AppCategory? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 앱에서 숨어야 하는지 정한다.
 *
 * 순서는 이렇다.
 * 1. 사용자가 직접 정한 것 (가장 우선)
 * 2. 미리 정리해 둔 목록
 * 3. 이름에 bank·pay·wallet 같은 말이 들어 있으면 숨는다
 * 4. 그래도 아니면 숨지 않는다
 *
 * 순수 함수라 화면 없이 확인할 수 있다.
 */
object AppRules {

    fun resolve(packageName: String, overrides: Map<String, AppCategory>): AppCategory {
        overrides[packageName]?.let { return it }
        DEFAULTS[packageName]?.let { return it }
        if (looksSensitive(packageName)) return AppCategory.SENSITIVE
        return AppCategory.NONE
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
     * 이름만으로는 알아볼 수 없는 은행·결제·증권 앱. 정확한 이름이 확실한 것만 넣었다.
     * 나머지 은행 앱은 대부분 위의 이름 짐작으로 잡힌다.
     */
    val DEFAULTS: Map<String, AppCategory> = mapOf(
        "viva.republica.toss" to AppCategory.SENSITIVE,
        "com.samsung.android.spay" to AppCategory.SENSITIVE,
        "com.kiwoom.heromts" to AppCategory.SENSITIVE
    )
}
