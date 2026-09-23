package com.jarves.mh.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SecretDao {
    @Query("SELECT * FROM secrets WHERE storageId = :storageId LIMIT 1")
    fun get(storageId: String): SecretEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(entity: SecretEntity)

    @Query("DELETE FROM secrets WHERE storageId = :storageId")
    fun remove(storageId: String)

    @Query("SELECT storageId FROM secrets")
    fun allIds(): List<String>

    @Delete
    fun delete(entity: SecretEntity)
}
