package com.jarves.mh.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "secrets")
data class SecretEntity(
    @PrimaryKey val storageId: String,
    val iv: String,
    val ciphertext: String,
)
