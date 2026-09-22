package com.pairplay.app

import com.pairplay.app.image.ImageImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleSizeTest {

    @Test
    fun `작은 이미지는 줄이지 않는다`() {
        assertEquals(1, ImageImporter.calculateSampleSize(800, 600, 1024))
        assertEquals(1, ImageImporter.calculateSampleSize(1024, 1024, 1024))
    }

    @Test
    fun `큰 이미지는 2의 거듭제곱으로 줄인다`() {
        assertEquals(4, ImageImporter.calculateSampleSize(4096, 4096, 1024))
        assertEquals(8, ImageImporter.calculateSampleSize(9000, 6000, 1024))
    }

    @Test
    fun `줄인 뒤 크기가 한계 안에 들어온다`() {
        val cases = listOf(
            4000 to 3000,
            12000 to 800,
            5000 to 9000,
            1025 to 1025
        )
        for ((width, height) in cases) {
            val sample = ImageImporter.calculateSampleSize(width, height, ImageImporter.MAX_DIMENSION)
            val resultWidth = width / sample
            val resultHeight = height / sample
            assertTrue(
                "${width}x$height 를 $sample 로 줄이면 ${resultWidth}x$resultHeight 로 여전히 큽니다",
                resultWidth <= ImageImporter.MAX_DIMENSION * 2 &&
                    resultHeight <= ImageImporter.MAX_DIMENSION * 2
            )
        }
    }

    @Test
    fun `이상한 값이 들어와도 1을 돌려준다`() {
        assertEquals(1, ImageImporter.calculateSampleSize(0, 0, 1024))
        assertEquals(1, ImageImporter.calculateSampleSize(-10, 100, 1024))
        assertEquals(1, ImageImporter.calculateSampleSize(100, 100, 0))
    }
}
