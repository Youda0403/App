package com.pairplay.app

import com.pairplay.app.engine.AppCategory
import com.pairplay.app.engine.AppRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 은행·결제 앱에서 숨기.
 *
 * 기본 분류 + 사용자가 직접 정한 것. 사용자가 정한 게 늘 이겨야 한다.
 */
class AppRulesTest {

    private val none = emptyMap<String, AppCategory>()

    @Test
    fun `이름으로 알아볼 수 없는 은행 앱도 목록에 있다`() {
        assertEquals(AppCategory.SENSITIVE, AppRules.resolve("viva.republica.toss", none))
        assertEquals(AppCategory.SENSITIVE, AppRules.resolve("com.samsung.android.spay", none))
    }

    @Test
    fun `이름에 bank 나 pay 가 들어가면 숨는다`() {
        for (pkg in listOf(
            "com.kbstar.kbbank",
            "com.shinhan.sbanking",
            "nh.smart.banking",
            "com.kakaopay.app",
            "com.nhnent.payapp",
            "com.paypal.android.p2pmobile",
            "com.google.android.apps.walletnfcrel",
            "com.hyundaicard.appcard",
            // 은행 앱이 본인 확인할 때 여는 인증 앱들. 이름·생년월일이 뜬다.
            "com.sktelecom.tauth",
            "com.kt.ktauth",
            "com.lguplus.smartotp",
            "com.google.android.apps.authenticator2"
        )) {
            assertEquals("$pkg 는 숨어야 합니다", AppCategory.SENSITIVE, AppRules.resolve(pkg, none))
        }
    }

    @Test
    fun `평범한 앱에서는 숨지 않는다`() {
        for (pkg in listOf(
            "com.google.android.youtube",
            "com.kakao.talk",
            "com.android.chrome",
            "com.sec.android.app.launcher",
            "com.google.android.apps.photos",
            "com.android.settings",
            "com.samsung.android.app.display",
            "com.example.hotplace",
            "com.nhn.android.search"
        )) {
            assertFalse("$pkg 에서 숨으면 안 됩니다", AppRules.resolve(pkg, none).hides)
        }
    }

    @Test
    fun `사용자가 정한 것이 늘 이긴다`() {
        val mine = mapOf(
            "com.kbstar.kbbank" to AppCategory.NONE,
            "com.example.diary" to AppCategory.SENSITIVE
        )
        assertEquals(AppCategory.NONE, AppRules.resolve("com.kbstar.kbbank", mine))
        assertEquals(AppCategory.SENSITIVE, AppRules.resolve("com.example.diary", mine))
    }

    @Test
    fun `설정을 저장하고 다시 읽는다`() {
        var text = AppRules.with("", "com.kbstar.kbbank", AppCategory.NONE)
        text = AppRules.with(text, "com.example.diary", AppCategory.SENSITIVE)
        val back = AppRules.parse(text)
        assertEquals(AppCategory.NONE, back["com.kbstar.kbbank"])
        assertEquals(AppCategory.SENSITIVE, back["com.example.diary"])

        // 지우면 기본값으로 돌아간다.
        text = AppRules.with(text, "com.kbstar.kbbank", null)
        assertNull(AppRules.parse(text)["com.kbstar.kbbank"])
    }

    @Test
    fun `예전 종류로 저장된 값과 깨진 값은 버린다`() {
        // 예전에는 video·game 같은 종류도 저장했다. 지금은 알아보지 못하니 버린다.
        val rules = AppRules.parse("\n=\nno-equals\ncom.a=video\ncom.b=sensitive\n=none\n")
        assertEquals(1, rules.size)
        assertEquals(AppCategory.SENSITIVE, rules["com.b"])
    }

    @Test
    fun `숨는 건 숨기 하나뿐이다`() {
        assertTrue(AppCategory.SENSITIVE.hides)
        assertFalse(AppCategory.NONE.hides)
    }
}
