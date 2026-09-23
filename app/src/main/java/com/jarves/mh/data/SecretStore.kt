package com.jarves.mh.data

import com.jarves.mh.data.db.MhDatabase
import com.jarves.mh.data.db.SecretEntity

/** Abstraction over secret persistence so ApiKeyVault can use Room (or prefs during migration). */
interface SecretStore {
    fun put(storageId: String, iv: String, ciphertext: String)
    fun get(storageId: String): Pair<String, String>?
    fun remove(storageId: String)
}

class RoomSecretStore(database: MhDatabase) : SecretStore {
    private val dao = database.secretDao()

    override fun put(storageId: String, iv: String, ciphertext: String) {
        dao.put(SecretEntity(storageId = storageId, iv = iv, ciphertext = ciphertext))
    }

    override fun get(storageId: String): Pair<String, String>? =
        dao.get(storageId)?.let { it.iv to it.ciphertext }

    override fun remove(storageId: String) {
        dao.remove(storageId)
    }
}
