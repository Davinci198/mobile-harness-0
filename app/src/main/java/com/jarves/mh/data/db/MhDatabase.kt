package com.jarves.mh.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SecretEntity::class], version = 1, exportSchema = false)
abstract class MhDatabase : RoomDatabase() {
    abstract fun secretDao(): SecretDao

    companion object {
        const val DB_NAME = "mh_secrets.db"

        @Volatile
        private var instance: MhDatabase? = null

        fun get(context: Context): MhDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MhDatabase::class.java,
                    DB_NAME,
                )
                    // ApiKeyVault's public API is synchronous and is called from
                    // the main thread by Compose/ViewModel code paths.
                    .allowMainThreadQueries()
                    .build().also { instance = it }
            }
    }
}
