package com.familyvault.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        FamilyMemberEntity::class,
        MemberAliasEntity::class,
        DocumentEntity::class,
        DocumentAttachmentEntity::class,
        DocumentHistoryEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class VaultDatabase : RoomDatabase() {

    abstract fun vaultDao(): VaultDao

    companion object {
        private const val DB_NAME = "family_vault_v2_encrypted.db"

        @Volatile
        private var INSTANCE: VaultDatabase? = null

        fun getUnlockedInstance(context: Context, passphraseBytes: ByteArray): VaultDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildEncryptedDatabase(context.applicationContext, passphraseBytes).also {
                    INSTANCE = it
                }
            }
        }

        private fun buildEncryptedDatabase(
            appContext: Context,
            passphraseBytes: ByteArray
        ): VaultDatabase {
            // Load native SQLCipher 4.6.1 C++ library
            System.loadLibrary("sqlcipher")

            // Enforce 16KB encrypted database pages for Android 15/16 compatibility
            val cipherHook = object : SQLiteDatabaseHook {
                override fun preKey(connection: SQLiteConnection) {}
                override fun postKey(connection: SQLiteConnection) {
                    connection.execute("PRAGMA cipher_page_size = 16384;", null, null)
                    connection.execute("PRAGMA foreign_keys = ON;", null, null)
                }
            }

            // Copy key bytes so we can safely zero the caller's array after opening
            val keyCopy = passphraseBytes.copyOf()
            val factory = SupportOpenHelperFactory(keyCopy, cipherHook, false)

            return Room.databaseBuilder(appContext, VaultDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                .addCallback(Fts5BootstrapCallback())
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
                .also {
                    // Trigger immediate connection & wipe temporary key buffer
                    it.openHelper.writableDatabase
                    keyCopy.fill(0)
                }
        }

        fun lockAndCloseDatabase() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }

    /**
     * Automatically creates the SQLite FTS5 Virtual Table with the `trigram` tokenizer
     * and wires up automatic sync triggers on first database creation.
     */
    private class Fts5BootstrapCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)

            db.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS documents_fts USING fts5(
                    name_on_document,
                    document_number,
                    issuing_authority,
                    extra_attributes_json,
                    raw_extracted_text,
                    content='documents',
                    content_rowid='doc_id',
                    tokenize='trigram'
                );
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS documents_ai AFTER INSERT ON documents BEGIN
                    INSERT INTO documents_fts(
                        rowid, name_on_document, document_number,
                        issuing_authority, extra_attributes_json, raw_extracted_text
                    ) VALUES (
                        new.doc_id, new.name_on_document, new.document_number,
                        new.issuing_authority, new.extra_attributes_json, new.raw_extracted_text
                    );
                END;
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS documents_ad AFTER DELETE ON documents BEGIN
                    INSERT INTO documents_fts(
                        documents_fts, rowid, name_on_document, document_number,
                        issuing_authority, extra_attributes_json, raw_extracted_text
                    ) VALUES (
                        'delete', old.doc_id, old.name_on_document, old.document_number,
                        old.issuing_authority, old.extra_attributes_json, old.raw_extracted_text
                    );
                END;
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS documents_au AFTER UPDATE ON documents BEGIN
                    INSERT INTO documents_fts(
                        documents_fts, rowid, name_on_document, document_number,
                        issuing_authority, extra_attributes_json, raw_extracted_text
                    ) VALUES (
                        'delete', old.doc_id, old.name_on_document, old.document_number,
                        old.issuing_authority, old.extra_attributes_json, old.raw_extracted_text
                    );
                    INSERT INTO documents_fts(
                        rowid, name_on_document, document_number,
                        issuing_authority, extra_attributes_json, raw_extracted_text
                    ) VALUES (
                        new.doc_id, new.name_on_document, new.document_number,
                        new.issuing_authority, new.extra_attributes_json, new.raw_extracted_text
                    );
                END;
                """.trimIndent()
            )
        }
    }
}
