package com.familyvault.app.crypto

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom

class VaultCryptoManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val masterAead: Aead
    private val fileStreamingAead: StreamingAead

    init {
        AeadConfig.register()
        StreamingAeadConfig.register()

        val handles = try {
            buildKeysets()
        } catch (t: Throwable) {
            resetCorruptedKeystore()
            buildKeysets()
        }
        masterAead = handles.first
        fileStreamingAead = handles.second
    }

    private fun buildKeysets(): Pair<Aead, StreamingAead> {
        val aeadHandle = AndroidKeysetManager.Builder()
            .withSharedPref(appContext, KEYSET_AEAD_NAME, PREFS_FILE)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        val aead = aeadHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)

        val streamHandle = AndroidKeysetManager.Builder()
            .withSharedPref(appContext, KEYSET_STREAM_NAME, PREFS_FILE)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM_HKDF_4KB"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        val stream = streamHandle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)

        return Pair(aead, stream)
    }

    private fun resetCorruptedKeystore() {
        prefs.edit().clear().commit()
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            ks.deleteEntry("family_vault_hardware_master_key")
        } catch (_: Throwable) {}
    }

    fun getOrCreateDatabasePassphrase(): ByteArray {
        val existingCiphertextB64 = prefs.getString(ENCRYPTED_DB_PASS_KEY, null)
        if (existingCiphertextB64 != null) {
            try {
                val ciphertext = Base64.decode(existingCiphertextB64, Base64.NO_WRAP)
                return masterAead.decrypt(ciphertext, DB_AAD)
            } catch (_: Throwable) {
                prefs.edit().remove(ENCRYPTED_DB_PASS_KEY).commit()
            }
        }
        val newPassphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val ciphertext = masterAead.encrypt(newPassphrase, DB_AAD)
        prefs.edit()
            .putString(ENCRYPTED_DB_PASS_KEY, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .commit()
        return newPassphrase
    }

    fun encryptStreamToVaultFile(
        inputStream: InputStream,
        destinationFile: File,
        associatedData: String
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(destinationFile).use { fileOut ->
            fileStreamingAead.newEncryptingStream(fileOut, associatedData.toByteArray()).use { cipherOut ->
                val buffer = ByteArray(4096)
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    cipherOut.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun openDecryptedStream(encryptedFile: File, associatedData: String): InputStream {
        val fileIn = FileInputStream(encryptedFile)
        return fileStreamingAead.newDecryptingStream(fileIn, associatedData.toByteArray())
    }

    companion object {
        private const val PREFS_FILE = "family_vault_keyset_prefs"
        private const val KEYSET_AEAD_NAME = "master_db_keyset"
        private const val KEYSET_STREAM_NAME = "file_stream_keyset"
        private const val ENCRYPTED_DB_PASS_KEY = "wrapped_sqlcipher_pass"
        private const val MASTER_KEY_URI = "android-keystore://family_vault_hardware_master_key"
        private val DB_AAD = "SQLCipher_FamilyVault_v2".toByteArray()
    }
}
