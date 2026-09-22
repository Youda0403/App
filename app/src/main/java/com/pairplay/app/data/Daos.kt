package com.pairplay.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CharacterDao {

    @Query("SELECT * FROM characters ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<CharacterEntity>>

    @Query("SELECT * FROM characters ORDER BY createdAt ASC")
    suspend fun getAll(): List<CharacterEntity>

    @Query("SELECT * FROM characters WHERE id = :id")
    suspend fun getById(id: Long): CharacterEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(character: CharacterEntity): Long

    @Update
    suspend fun update(character: CharacterEntity)

    @Delete
    suspend fun delete(character: CharacterEntity)

    @Query("SELECT COUNT(*) FROM characters")
    suspend fun count(): Int
}

@Dao
interface PairDao {

    @Query("SELECT * FROM pairs ORDER BY id ASC")
    fun observeAll(): Flow<List<PairEntity>>

    @Query("SELECT * FROM pairs WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<PairEntity?>

    @Query("SELECT * FROM pairs WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): PairEntity?

    @Query("SELECT * FROM pairs WHERE id = :id")
    suspend fun getById(id: Long): PairEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(pair: PairEntity): Long

    @Update
    suspend fun update(pair: PairEntity)

    @Delete
    suspend fun delete(pair: PairEntity)

    @Query("UPDATE pairs SET isActive = 0")
    suspend fun clearActive()

    @Query("UPDATE pairs SET isActive = 1 WHERE id = :id")
    suspend fun markActive(id: Long)

    /** 삭제된 캐릭터를 참조하는 짝을 정리한다. */
    @Query("DELETE FROM pairs WHERE characterAId = :characterId OR characterBId = :characterId")
    suspend fun deleteReferencing(characterId: Long)
}

@Dao
interface SceneDao {

    @Query("SELECT * FROM scenes ORDER BY priority DESC, id ASC")
    fun observeAll(): Flow<List<SceneEntity>>

    @Query("SELECT * FROM scenes WHERE enabled = 1 ORDER BY priority DESC, id ASC")
    suspend fun getEnabled(): List<SceneEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(scene: SceneEntity): Long

    @Update
    suspend fun update(scene: SceneEntity)

    @Delete
    suspend fun delete(scene: SceneEntity)
}
