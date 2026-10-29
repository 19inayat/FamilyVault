package com.familyvault.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFamilyMember(member: FamilyMemberEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlias(alias: MemberAliasEntity): Long

    @Query("SELECT * FROM family_members ORDER BY canonical_name ASC")
    fun observeAllFamilyMembers(): Flow<List<FamilyMemberEntity>>

    @Query("SELECT * FROM family_members")
    suspend fun getAllFamilyMembersOnce(): List<FamilyMemberEntity>

    @Query("SELECT member_id FROM member_aliases WHERE alias_normalized = :normalizedAlias LIMIT 1")
    suspend fun findMemberIdByAlias(normalizedAlias: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: DocumentEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAttachment(attachment: DocumentAttachmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocumentHistory(history: DocumentHistoryEntity): Long

    @Query("SELECT * FROM documents WHERE member_id = :memberId ORDER BY doc_category, doc_sub_type")
    fun observeDocumentsForMember(memberId: Long): Flow<List<DocumentEntity>>

    @RawQuery(observedEntities = [DocumentEntity::class])
    suspend fun executeRawDocumentSearch(query: SupportSQLiteQuery): List<DocumentEntity>

    suspend fun searchDocumentsFts5(searchTerm: String): List<DocumentEntity> {
        val sanitized = searchTerm.replace("\"", "\"\"").trim()
        if (sanitized.isEmpty()) return emptyList()
        return try {
            val sql = """
                SELECT d.* FROM documents d
                INNER JOIN documents_fts fts ON d.doc_id = fts.rowid
                WHERE documents_fts MATCH '"$sanitized"'
                ORDER BY d.updated_at DESC
            """.trimIndent()
            executeRawDocumentSearch(SimpleSQLiteQuery(sql))
        } catch (_: Throwable) {
            val fallbackSql = """
                SELECT * FROM documents
                WHERE raw_extracted_text LIKE '%$sanitized%'
                   OR document_number LIKE '%$sanitized%'
                   OR name_on_document LIKE '%$sanitized%'
                ORDER BY updated_at DESC
            """.trimIndent()
            executeRawDocumentSearch(SimpleSQLiteQuery(fallbackSql))
        }
    }
}
