package com.familyvault.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "family_members",
    indices = [
        Index(value = ["canonical_name"], unique = true),
        Index(value = ["sorted_tokens"])
    ]
)
data class FamilyMemberEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "member_id") val memberId: Long = 0,
    @ColumnInfo(name = "canonical_name") val canonicalName: String,
    @ColumnInfo(name = "sorted_tokens") val sortedTokens: String,
    @ColumnInfo(name = "phonetic_primary") val phoneticPrimary: String,
    @ColumnInfo(name = "phonetic_secondary") val phoneticSecondary: String? = null,
    @ColumnInfo(name = "relationship_role") val relationshipRole: String,
    @ColumnInfo(name = "blood_group") val bloodGroup: String? = null,
    @ColumnInfo(name = "legacy_folder_uri") val legacyFolderUri: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "member_aliases",
    foreignKeys = [
        ForeignKey(
  entity = FamilyMemberEntity::class,
  parentColumns = ["member_id"],
  childColumns = ["member_id"],
  onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["member_id"]),
        Index(value = ["alias_normalized"], unique = true)
    ]
)
data class MemberAliasEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "alias_id") val aliasId: Long = 0,
    @ColumnInfo(name = "member_id") val memberId: Long,
    @ColumnInfo(name = "alias_normalized") val aliasNormalized: String
)

@Entity(
    tableName = "documents",
    foreignKeys = [
        ForeignKey(
  entity = FamilyMemberEntity::class,
  parentColumns = ["member_id"],
  childColumns = ["member_id"],
  onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["member_id"]),
        Index(value = ["doc_category", "doc_sub_type"]),
        Index(value = ["expiry_date"])
    ]
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "doc_id") val docId: Long = 0,
    @ColumnInfo(name = "member_id") val memberId: Long,
    @ColumnInfo(name = "doc_category") val docCategory: String,
    @ColumnInfo(name = "doc_sub_type") val docSubType: String,
    @ColumnInfo(name = "name_on_document") val nameOnDocument: String,
    @ColumnInfo(name = "document_number") val documentNumber: String,
    @ColumnInfo(name = "issuing_authority") val issuingAuthority: String,
    @ColumnInfo(name = "issue_date") val issueDate: String? = null,
    @ColumnInfo(name = "expiry_date") val expiryDate: String? = null,
    @ColumnInfo(name = "linked_mobile") val linkedMobile: String? = null,
    @ColumnInfo(name = "extra_attributes_json") val extraAttributesJson: String = "{}",
    @ColumnInfo(name = "raw_extracted_text") val rawExtractedText: String,
    @ColumnInfo(name = "extraction_source") val extractionSource: String,
    @ColumnInfo(name = "version_number") val versionNumber: Int = 1,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "document_attachments",
    foreignKeys = [
        ForeignKey(
  entity = DocumentEntity::class,
  parentColumns = ["doc_id"],
  childColumns = ["doc_id"],
  onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["doc_id"]),
        Index(value = ["sha256_hash"], unique = true)
    ]
)
data class DocumentAttachmentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "attachment_id") val attachmentId: Long = 0,
    @ColumnInfo(name = "doc_id") val docId: Long,
    @ColumnInfo(name = "page_order") val pageOrder: Int,
    @ColumnInfo(name = "file_role") val fileRole: String,
    @ColumnInfo(name = "tink_encrypted_path") val tinkEncryptedPath: String,
    @ColumnInfo(name = "sha256_hash") val sha256Hash: String
)

@Entity(
    tableName = "document_history",
    foreignKeys = [
        ForeignKey(
  entity = DocumentEntity::class,
  parentColumns = ["doc_id"],
  childColumns = ["doc_id"],
  onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["doc_id"])]
)
data class DocumentHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "history_id") val historyId: Long = 0,
    @ColumnInfo(name = "doc_id") val docId: Long,
    @ColumnInfo(name = "version_number") val versionNumber: Int,
    @ColumnInfo(name = "old_document_number") val oldDocumentNumber: String,
    @ColumnInfo(name = "old_issue_date") val oldIssueDate: String? = null,
    @ColumnInfo(name = "old_expiry_date") val oldExpiryDate: String? = null,
    @ColumnInfo(name = "old_encrypted_path") val oldEncryptedPath: String,
    @ColumnInfo(name = "archived_at") val archivedAt: Long = System.currentTimeMillis()
)
