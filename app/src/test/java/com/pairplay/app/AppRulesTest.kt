package com.pairplay.app

import com.pairplay.app.engine.AppCategory
import com.pairplay.app.engine.AppReactionMapper
import com.pairplay.app.engine.AppRules
import com.pairplay.app.engine.CharacterAction
import com.pairplay.app.engine.CharacterTraits
import com.pairplay.app.engine.EffectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 지금 쓰는 앱을 알아보고 반응하는 부분.
 *
 * 기본 분류 + 사용자가 직접 정한 것. 사용자가 정한 게 늘 이겨야 한다.
 * 은행·결제 앱에서는 캐릭터가 숨어야 한다.
 */
class AppRulesTest {

    private val none = emptyMap<String, AppCategory>()

    @Test
    fun `자주 쓰는 앱은 미리 분류되어 있다`() {
        assertEquals(AppCategory.VIDEO, AppRules.resolve("com.google.android.youtube", none, null))
        assertEquals(AppCategory.MESSENGER, AppRules.resolve("com.kakao.talk", none, null))
        assertEquals(AppCategory.SENSITIVE, AppRules.resolve("viva.republica.toss", none, null))
    }

    @Test
    fun `이름에 bank 나 pay 가 들어가면 숨는 앱으로 본다`() {
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
            assertEquals("$pkg 는 숨어야 합니다", AppCategory.SENSITIVE, AppRules.resolve(pkg, none, null))
        }
    }

    @Test
    fun `평범한 앱이 은행으로 잘못 걸리지 않는다`() {
        for (pkg in listOf(
            "com.google.android.youtube",
            "com.android.chrome",
            "com.sec.android.app.launcher",
            "com.google.android.apps.photos",
            "com.android.settings",
            "com.samsung.android.app.display",
            "com.example.hotplace",
            "com.nhn.android.search"
        )) {
            assertFalse("$pkg 가 은행으로 잘못 걸렸습니다", AppRules.looksSensitive(pkg))
        }
    }

    @Test
    fun `사용자가 정한 것이 늘 이긴다`() {
        // 유튜브를 '반응 안 함' 으로, 은행을 '게임' 으로 바꿔도 그대로 따라야 한다.
        val mine = mapOf(
            "com.google.android.youtube" to AppCategory.NONE,
            "com.kbstar.kbbank" to AppCategory.GAME
        )
        assertEquals(AppCategory.NONE, AppRules.resolve("com.google.android.youtube", mine, null))
        assertEquals(AppCategory.GAME, AppRules.resolve("com.kbstar.kbbank", mine, null))
    }

    @Test
    fun `목록에 없는 앱은 안드로이드가 알려 준 종류를 쓴다`() {
        assertEquals(
            AppCategory.GAME,
            AppRules.resolve("com.example.somegame", none, AppCategory.GAME)
        )
        // 아무 단서도 없으면 반응하지 않는다.
        assertEquals(AppCategory.NONE, AppRules.resolve("com.example.memo", none, null))
    }

    @Test
    fun `앱별 설정을 저장하고 다시 읽는다`() {
        var text = AppRules.with("", "com.kakao.talk", AppCategory.NONE)
        text = AppRules.with(text, "com.example.bank", AppCategory.SENSITIVE)
        val back = AppRules.parse(text)
        assertEquals(AppCategory.NONE, back["com.kakao.talk"])
        assertEquals(AppCategory.SENSITIVE, back["com.example.bank"])

        // 지우면 기본값으로 돌아간다.
        text = AppRules.with(text, "com.kakao.talk", null)
        assertNull(AppRules.parse(text)["com.kakao.talk"])
    }

    @Test
    fun `저장된 값이 깨져 있어도 터지지 않는다`() {
        val rules = AppRules.parse("\n=\nno-equals\ncom.a=video\ncom.b=unknown\n=game\n")
        assertEquals(1, rules.size)
        assertEquals(AppCategory.VIDEO, rules["com.a"])
    }

    @Test
    fun `숨는 앱과 반응 안 함에는 반응이 없다`() {
        assertTrue(AppCategory.SENSITIVE.hides)
        assertNull(AppReactionMapper.forCategory(AppCategory.SENSITIVE, CharacterTraits()))
        assertNull(AppReactionMapper.forCategory(AppCategory.NONE, CharacterTraits()))
    }

    @Test
    fun `나머지 종류는 모두 반응이 있다`() {
        for (category in AppCategory.entries) {
            if (category == AppCategory.SENSITIVE || category == AppCategory.NONE) continue
            assertFalse(category.hides)
            assertNotNull(
                "${category.id} 에 반응이 없습니다",
                AppReactionMapper.forCategory(category, CharacterTraits())
            )
        }
    }

    @Test
    fun `앱 반응에는 반짝임을 쓰지 않는다`() {
        // ✨ 는 '번뜩' 이라는 뜻이라 장난이 떠오른 순간에만 쓴다.
        for (category in AppCategory.entries) {
            for (traits in listOf(
                CharacterTraits(),
                CharacterTraits(shyness = 90),
                CharacterTraits(energy = 10),
                CharacterTraits(mischief = 95)
            )) {
                val effect = AppReactionMapper.forCategory(category, traits)?.effect
                assertTrue("${category.id} 에 반짝임이 떴습니다", effect != EffectKind.SPARKLE)
            }
        }
    }

    @Test
    fun `금지한 동작은 앱 반응에서도 나오지 않는다`() {
        val blocked = CharacterTraits(
            blockedActions = setOf(
                CharacterAction.JUMP.id,
                CharacterAction.REST.id,
                CharacterAction.RHYTHM.id,
                CharacterAction.GLANCE.id
            )
        )
        for (category in AppCategory.entries) {
            val action = AppReactionMapper.forCategory(category, blocked)?.action ?: continue
            assertTrue("${category.id} 에서 금지한 ${action.id} 가 나왔습니다", blocked.allows(action))
        }
    }
}
