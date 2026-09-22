package com.pairplay.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.pairplay.app.R
import com.pairplay.app.image.ImageImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 캐릭터와 짝을 다루는 단일 창구.
 * 화면과 오버레이 서비스가 모두 여기를 통해 데이터를 읽고 쓴다.
 */
class CharacterRepository(private val context: Context) {

    private val database = PairPlayDatabase.get(context)
    private val characterDao = database.characterDao()
    private val pairDao = database.pairDao()

    fun observeCharacters(): Flow<List<CharacterEntity>> = characterDao.observeAll()

    fun observePairs(): Flow<List<PairEntity>> = pairDao.observeAll()

    fun observeActivePair(): Flow<PairEntity?> = pairDao.observeActive()

    suspend fun getCharacter(id: Long): CharacterEntity? = characterDao.getById(id)

    /**
     * 사용자가 고른 이미지를 들여와 캐릭터로 등록한다.
     * 실패하면 이유를 담은 [ImageImporter.Result.Failure] 를 돌려준다.
     */
    suspend fun addCharacterFromUri(
        uri: Uri,
        name: String
    ): AddResult = withContext(Dispatchers.IO) {
        val key = "char_${System.currentTimeMillis()}"
        when (val result = ImageImporter.import(context, uri, key)) {
            is ImageImporter.Result.Failure -> AddResult.Failed(result.reason)
            is ImageImporter.Result.Success -> {
                val id = characterDao.insert(
                    CharacterEntity(
                        name = name.ifBlank { DEFAULT_NAME },
                        imagePath = result.imagePath,
                        originalImagePath = result.originalPath
                    )
                )
                AddResult.Added(id)
            }
        }
    }

    suspend fun updateCharacter(character: CharacterEntity) = withContext(Dispatchers.IO) {
        characterDao.update(character)
    }

    suspend fun deleteCharacter(character: CharacterEntity) = withContext(Dispatchers.IO) {
        pairDao.deleteReferencing(character.id)
        characterDao.delete(character)
        ImageImporter.deleteFiles(character.imagePath, character.originalImagePath)
    }

    suspend fun characterCount(): Int = withContext(Dispatchers.IO) { characterDao.count() }

    // ------------------------------------------------------------------ 짝

    suspend fun setActivePair(characterAId: Long, characterBId: Long?) =
        withContext(Dispatchers.IO) {
            val existing = pairDao.getActive()
            pairDao.clearActive()
            if (existing != null) {
                pairDao.update(
                    existing.copy(
                        characterAId = characterAId,
                        characterBId = characterBId,
                        isActive = true
                    )
                )
            } else {
                val id = pairDao.insert(
                    PairEntity(
                        characterAId = characterAId,
                        characterBId = characterBId,
                        isActive = true
                    )
                )
                pairDao.markActive(id)
            }
        }

    suspend fun updatePair(pair: PairEntity) = withContext(Dispatchers.IO) {
        pairDao.update(pair)
    }

    suspend fun getActivePair(): PairEntity? = withContext(Dispatchers.IO) { pairDao.getActive() }

    // ------------------------------------------------------------------ 기본 캐릭터

    /**
     * 등록된 캐릭터가 하나도 없으면 기본 캐릭터 두 명을 만들어 둔다.
     * 사용자가 자기 이미지를 넣기 전에도 오버레이가 동작하게 하기 위함이다.
     */
    suspend fun ensureDefaultCharacters() = withContext(Dispatchers.IO) {
        if (characterDao.count() > 0) return@withContext

        val a = createBuiltIn("기본 캐릭터 1", R.drawable.default_character_a, "builtin_a")
        val b = createBuiltIn("기본 캐릭터 2", R.drawable.default_character_b, "builtin_b")

        if (a != null && b != null) {
            setActivePair(a, b)
        } else if (a != null) {
            setActivePair(a, null)
        }
    }

    private suspend fun createBuiltIn(name: String, drawableRes: Int, key: String): Long? {
        val path = rasterizeDrawable(drawableRes, key) ?: return null
        return try {
            characterDao.insert(
                CharacterEntity(
                    name = name,
                    imagePath = path,
                    originalImagePath = null,
                    isBuiltIn = true
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "기본 캐릭터를 만들지 못했습니다", e)
            null
        }
    }

    /** 벡터 기본 캐릭터를 PNG 로 구워 사용자 이미지와 같은 경로 체계로 다룬다. */
    private fun rasterizeDrawable(drawableRes: Int, key: String): String? {
        val drawable = ContextCompat.getDrawable(context, drawableRes) ?: return null
        val density = context.resources.displayMetrics.density
        val width = (drawable.intrinsicWidth * density).toInt().coerceAtLeast(1)
        val height = (drawable.intrinsicHeight * density).toInt().coerceAtLeast(1)

        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)

            val dir = File(context.filesDir, "characters").apply { mkdirs() }
            val file = File(dir, "$key.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            file.absolutePath
        } catch (e: IOException) {
            Log.w(TAG, "기본 캐릭터 이미지를 저장하지 못했습니다", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "기본 캐릭터 이미지를 만들 메모리가 부족합니다", e)
            null
        }
    }

    sealed interface AddResult {
        data class Added(val id: Long) : AddResult
        data class Failed(val reason: ImageImporter.Reason) : AddResult
    }

    companion object {
        private const val TAG = "CharacterRepository"
        private const val DEFAULT_NAME = "이름 없는 자캐"
    }
}
