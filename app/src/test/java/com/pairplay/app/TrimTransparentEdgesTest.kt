package com.pairplay.app

import android.graphics.Bitmap
import android.graphics.Color
import com.pairplay.app.image.ImageImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 투명 여백 잘라내기는 오버레이 창이 쓸데없이 터치를 가로채지 않게 하는 핵심이라
 * 실제 비트맵으로 확인한다.
 */
@RunWith(RobolectricTestRunner::class)
class TrimTransparentEdgesTest {

    @Test
    fun `가장자리 투명 여백을 잘라낸다`() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        // 가운데 20x30 만 불투명하게 칠한다.
        for (x in 40 until 60) {
            for (y in 35 until 65) {
                bitmap.setPixel(x, y, Color.RED)
            }
        }

        val trimmed = ImageImporter.trimTransparentEdges(bitmap)

        assertNotNull(trimmed)
        assertEquals(20, trimmed!!.width)
        assertEquals(30, trimmed.height)
    }

    @Test
    fun `전부 투명하면 null 을 돌려준다`() {
        val bitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        assertNull(ImageImporter.trimTransparentEdges(bitmap))
    }

    @Test
    fun `잘라낼 여백이 없으면 원본을 그대로 돌려준다`() {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        for (x in 0 until 10) {
            for (y in 0 until 10) {
                bitmap.setPixel(x, y, Color.BLUE)
            }
        }
        assertSame(bitmap, ImageImporter.trimTransparentEdges(bitmap))
    }

    @Test
    fun `거의 투명한 픽셀은 여백으로 본다`() {
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        // 알파 4 는 임계값 8 보다 작아 여백으로 취급되어야 한다.
        bitmap.setPixel(0, 0, Color.argb(4, 255, 0, 0))
        bitmap.setPixel(10, 10, Color.argb(255, 255, 0, 0))

        val trimmed = ImageImporter.trimTransparentEdges(bitmap)

        assertNotNull(trimmed)
        assertEquals(1, trimmed!!.width)
        assertEquals(1, trimmed.height)
    }
}
