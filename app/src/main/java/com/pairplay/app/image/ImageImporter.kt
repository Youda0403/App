package com.pairplay.app.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 사용자가 고른 이미지를 앱 전용 저장소로 들여오는 담당.
 *
 * 문서 요구사항에 맞춰 두 가지를 한다.
 * 1. 큰 이미지를 표시 크기에 맞게 줄여 저장한다 (메모리 폭증 방지).
 * 2. 가장자리 투명 여백을 잘라낸다. 오버레이 창이 이미지 크기에 맞춰 뜨기 때문에,
 *    여백이 남아 있으면 그만큼 다른 앱의 터치를 쓸데없이 가로챈다.
 */
object ImageImporter {

    private const val TAG = "ImageImporter"

    /** 저장할 이미지의 최대 변 길이(px). 고해상도 화면에서도 충분하다. */
    const val MAX_DIMENSION = 1024

    /** 이 값 이하의 알파는 투명한 것으로 보고 잘라낸다. */
    const val ALPHA_THRESHOLD = 8

    private const val CHARACTER_DIR = "characters"

    sealed interface Result {
        data class Success(
            val imagePath: String,
            val originalPath: String,
            val width: Int,
            val height: Int
        ) : Result

        data class Failure(val reason: Reason) : Result
    }

    enum class Reason {
        /** 이미지를 열 수 없음 (손상되었거나 지원하지 않는 형식). */
        UNREADABLE,

        /** 잘라내고 나니 남는 픽셀이 없음 (전부 투명). */
        FULLY_TRANSPARENT,

        /** 저장 실패 (저장 공간 부족 등). */
        WRITE_FAILED,

        /** 메모리가 부족해 처리하지 못함. */
        OUT_OF_MEMORY
    }

    /**
     * [uri] 의 이미지를 읽어 여백을 자르고 앱 전용 저장소에 저장한다.
     * 원본 사본도 함께 저장해 두어 나중에 기준점/크기를 다시 계산할 수 있게 한다.
     */
    fun import(context: Context, uri: Uri, characterKey: String): Result {
        val dir = File(context.filesDir, CHARACTER_DIR).apply { mkdirs() }

        return try {
            val bounds = readBounds(context, uri)
                ?: return Result.Failure(Reason.UNREADABLE)

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return Result.Failure(Reason.UNREADABLE)
            }

            val decoded = decodeDownsampled(context, uri, bounds)
                ?: return Result.Failure(Reason.UNREADABLE)

            val originalFile = File(dir, "${characterKey}_original.png")
            if (!writePng(decoded, originalFile)) {
                decoded.recycle()
                return Result.Failure(Reason.WRITE_FAILED)
            }

            val trimmed = trimTransparentEdges(decoded)
            if (trimmed == null) {
                decoded.recycle()
                originalFile.delete()
                return Result.Failure(Reason.FULLY_TRANSPARENT)
            }

            val trimmedFile = File(dir, "${characterKey}.png")
            val written = writePng(trimmed, trimmedFile)
            val width = trimmed.width
            val height = trimmed.height

            if (trimmed !== decoded) trimmed.recycle()
            decoded.recycle()

            if (!written) {
                originalFile.delete()
                Result.Failure(Reason.WRITE_FAILED)
            } else {
                Result.Success(
                    imagePath = trimmedFile.absolutePath,
                    originalPath = originalFile.absolutePath,
                    width = width,
                    height = height
                )
            }
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "이미지 처리 중 메모리 부족", e)
            Result.Failure(Reason.OUT_OF_MEMORY)
        } catch (e: IOException) {
            Log.w(TAG, "이미지 처리 실패", e)
            Result.Failure(Reason.UNREADABLE)
        } catch (e: SecurityException) {
            Log.w(TAG, "이미지에 접근할 수 없음", e)
            Result.Failure(Reason.UNREADABLE)
        }
    }

    /** 캐릭터가 지워질 때 남은 파일을 정리한다. */
    fun deleteFiles(vararg paths: String?) {
        paths.filterNotNull().forEach { path ->
            runCatching { File(path).delete() }
        }
    }

    private fun readBounds(context: Context, uri: Uri): BitmapFactory.Options? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val opened = context.contentResolver.openInputStream(uri) ?: return null
        opened.use { BitmapFactory.decodeStream(it, null, options) }
        return options.takeIf { it.outWidth > 0 && it.outHeight > 0 }
    }

    private fun decodeDownsampled(
        context: Context,
        uri: Uri,
        bounds: BitmapFactory.Options
    ): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val stream = context.contentResolver.openInputStream(uri) ?: return null
        return stream.use { BitmapFactory.decodeStream(it, null, options) }
    }

    /**
     * 긴 변이 [maxDimension] 이하가 되는 가장 작은 2의 거듭제곱 축소 배율을 고른다.
     * BitmapFactory 는 2의 거듭제곱만 제대로 지원한다.
     */
    fun calculateSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= maxDimension || height / (sample * 2) >= maxDimension) {
            sample *= 2
        }
        return sample
    }

    /**
     * 가장자리의 완전 투명한 줄을 잘라낸 비트맵을 돌려준다.
     * 잘라낼 것이 없으면 원본을 그대로 돌려주고, 전부 투명하면 null 을 돌려준다.
     */
    fun trimTransparentEdges(source: Bitmap): Bitmap? {
        if (!source.hasAlpha()) return source

        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return null

        val row = IntArray(width)
        var top = -1
        var bottom = -1
        var left = width
        var right = -1

        for (y in 0 until height) {
            source.getPixels(row, 0, width, 0, y, width, 1)
            var rowLeft = -1
            var rowRight = -1
            for (x in 0 until width) {
                if ((row[x] ushr 24) > ALPHA_THRESHOLD) {
                    if (rowLeft < 0) rowLeft = x
                    rowRight = x
                }
            }
            if (rowLeft >= 0) {
                if (top < 0) top = y
                bottom = y
                if (rowLeft < left) left = rowLeft
                if (rowRight > right) right = rowRight
            }
        }

        if (top < 0 || right < 0) return null

        val newWidth = right - left + 1
        val newHeight = bottom - top + 1
        if (newWidth == width && newHeight == height) return source

        return Bitmap.createBitmap(source, left, top, newWidth, newHeight)
    }

    private fun writePng(bitmap: Bitmap, target: File): Boolean = try {
        FileOutputStream(target).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    } catch (e: IOException) {
        Log.w(TAG, "이미지 저장 실패: ${target.name}", e)
        target.delete()
        false
    }
}
