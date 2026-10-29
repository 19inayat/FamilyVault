package com.familyvault.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
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
    exportSchema = false
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
            System.loadLibrary("sqlcipher")

            val keyCopy = passphraseBytes.copyOf()
            val factory = SupportOpenHelperFactory(keyCopy)

            return Room.databaseBuilder(appContext, VaultDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                .addCallback(Fts5BootstrapCallback())
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
                .also { db ->
                    db.openHelper.writableDatabase
                }
        }

        fun lockAndCloseDatabase() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }

    private class Fts5BootstrapCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            try {
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
            } catch (_: Throwable) {
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS documents_fts USING fts5(
                        name_on_document,
                        document_number,
                        issuing_authority,
                        extra_attributes_json,
                        raw_extracted_text,
                        content='documents',
                        content_rowid='doc_id'
                    );
                    """.trimIndent()
                )
            }

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
