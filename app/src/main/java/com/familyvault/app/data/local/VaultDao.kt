package com.familyvault.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
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

    @Query("SELECT * FROM documents ORDER BY updated_at DESC")
    fun observeAllDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE member_id = :memberId AND doc_sub_type = :subType LIMIT 1")
    suspend fun findExistingDocumentBySubType(memberId: Long, subType: String): DocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: DocumentEntity): Long

    @Update
    suspend fun updateDocument(document: DocumentEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAttachment(attachment: DocumentAttachmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocumentHistory(history: DocumentHistoryEntity): Long

    @Query("SELECT * FROM documents WHERE member_id = :memberId ORDER BY doc_category, doc_sub_type")
    fun observeDocumentsForMember(memberId: Long): Flow<List<DocumentEntity>>

    @Query("DELETE FROM family_members WHERE canonical_name = 'Self Profile (Test)'")
    suspend fun cleanupSprint1TestProfile()

    @RawQuery(observedEntities = [DocumentEntity::class])
    suspend fun executeRawDocumentSearch(query: SupportSQLiteQuery): List<DocumentEntity>
}

suspend fun VaultDao.upsertDocumentWithVersionAudit(incoming: DocumentEntity): Pair<Long, String> {
    val existing = findExistingDocumentBySubType(incoming.memberId, incoming.docSubType)
    if (existing == null) {
        val id = insertDocument(incoming)
        return Pair(id, "Inserted v1 (${incoming.docSubType})")
    }
    return if (existing.documentNumber.equals(incoming.documentNumber, ignoreCase = true)) {
        val updated = incoming.copy(
  docId = existing.docId,
  versionNumber = existing.versionNumber,
  updatedAt = System.currentTimeMillis()
        )
        updateDocument(updated)
        Pair(existing.docId, "Updated existing ${incoming.docSubType}")
    } else {
        insertDocumentHistory(
  DocumentHistoryEntity(
      docId = existing.docId,
      versionNumber = existing.versionNumber,
      oldDocumentNumber = existing.documentNumber,
      oldIssueDate = existing.issueDate,
      oldExpiryDate = existing.expiryDate,
      oldEncryptedPath = "vault_archive_v${existing.versionNumber}"
  )
        )
        val newVersion = existing.versionNumber + 1
        updateDocument(incoming.copy(docId = existing.docId, versionNumber = newVersion, updatedAt = System.currentTimeMillis()))
        Pair(existing.docId, "Archived v${existing.versionNumber} & Upgraded to v$newVersion")
    }
}

suspend fun VaultDao.searchDocumentsFts5(searchTerm: String): List<DocumentEntity> {
    val sanitized = searchTerm.replace("\"", "\"\"").trim()
    if (sanitized.isEmpty()) return emptyList()
    return try {
        val sql = "SELECT d.* FROM documents d INNER JOIN documents_fts fts ON d.doc_id = fts.rowid WHERE documents_fts MATCH '\"$sanitized\"' ORDER BY d.updated_at DESC"
        executeRawDocumentSearch(SimpleSQLiteQuery(sql))
    } catch (_: Throwable) {
        val fallbackSql = "SELECT * FROM documents WHERE raw_extracted_text LIKE '%$sanitized%' OR document_number LIKE '%$sanitized%' ORDER BY updated_at DESC"
        executeRawDocumentSearch(SimpleSQLiteQuery(fallbackSql))
    }
}
