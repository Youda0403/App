package com.pairplay.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CharacterEntity::class, PairEntity::class, SceneEntity::class],
    version = 1,
    exportSchema = true
)
abstract class PairPlayDatabase : RoomDatabase() {

    abstract fun characterDao(): CharacterDao
    abstract fun pairDao(): PairDao
    abstract fun sceneDao(): SceneDao

    companion object {
        private const val NAME = "pairplay.db"

        @Volatile
        private var instance: PairPlayDatabase? = null

        fun get(context: Context): PairPlayDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PairPlayDatabase::class.java,
                    NAME
                )
                    // 업데이트 시 사용자 데이터가 날아가지 않도록 파괴적 마이그레이션을 쓰지 않는다.
                    // 스키마가 바뀌면 여기에 Migration 을 추가한다.
                    .build()
                    .also { instance = it }
            }
    }
}
