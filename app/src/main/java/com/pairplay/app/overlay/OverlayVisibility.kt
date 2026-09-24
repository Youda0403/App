package com.pairplay.app.overlay

/**
 * 캐릭터가 지금 보이는지, 숨었다면 왜 숨었는지.
 * 위젯이 '숨어 있어요' 를 보여 줄 때 쓴다.
 */
enum class OverlayVisibility {
    /** 화면에 떠 있다. */
    SHOWN,

    /** 사용자가 직접 숨겼다(길게 누르기·알림). */
    HIDDEN_BY_USER,

    /** 은행·결제처럼 민감한 앱을 쓰는 동안 알아서 숨었다. */
    HIDDEN_BY_APP
}
