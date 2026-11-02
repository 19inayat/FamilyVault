package com.familyvault.app

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.familyvault.app.crypto.VaultCryptoManager
import com.familyvault.app.data.local.*
import com.familyvault.app.domain.*
import com.familyvault.app.scanner.EncryptedPageAttachment
import com.familyvault.app.scanner.ScanAndVoiceProcessor
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {

    private var unlockedDb by mutableStateOf<VaultDatabase?>(null)
    private var authStatus by mutableStateOf("Vault Locked — Biometric Verification Required")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val db = unlockedDb
                    if (db == null) {
                        LockedScreen(authStatus) { triggerBiometricPromptSafe() }
                    } else {
                        DynamicOcrWorkbench(this@MainActivity, db) {
                            VaultDatabase.lockAndCloseDatabase()
                            unlockedDb = null
                            authStatus = "Vault Locked & RAM Keys Scrubbed"
                        }
                    }
                }
            }
        }
        window.decorView.postDelayed({ if (!isFinishing && !isDestroyed) triggerBiometricPromptSafe() }, 250L)
    }

    private fun triggerBiometricPromptSafe() {
        try {
            val bm = BiometricManager.from(this)
            val strongPin = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
            val weakPin = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
            val builder = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock FamilyVault")
                .setSubtitle("Verify fingerprint or device PIN")

            when {
                bm.canAuthenticate(strongPin) == BiometricManager.BIOMETRIC_SUCCESS -> builder.setAllowedAuthenticators(strongPin)
                bm.canAuthenticate(weakPin) == BiometricManager.BIOMETRIC_SUCCESS -> builder.setAllowedAuthenticators(weakPin)
                bm.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS -> {
                    builder.setAllowedAuthenticators(BIOMETRIC_STRONG)
                    builder.setNegativeButtonText("Cancel")
                }
                else -> { unlockDatabase(); return }
            }

            BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = unlockDatabase()
                override fun onAuthenticationError(code: Int, err: CharSequence) { authStatus = "Locked: $err" }
            }).authenticate(builder.build())
        } catch (t: Throwable) {
            authStatus = "Auth Error: ${t.localizedMessage}"
        }
    }

    private fun unlockDatabase() {
        lifecycleScope.launch(Dispatchers.IO) {
            var pass: ByteArray? = null
            try {
                pass = VaultCryptoManager(applicationContext).getOrCreateDatabasePassphrase()
                val db = VaultDatabase.getUnlockedInstance(applicationContext, pass)
                db.vaultDao().cleanupSprint1TestProfile()
                withContext(Dispatchers.Main) { unlockedDb = db }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) { authStatus = "DB Error: ${t.localizedMessage}" }
            } finally {
                pass?.fill(0)
            }
        }
    }
}

@Composable
private fun LockedScreen(status: String, onUnlock: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Icon(Icons.Default.Lock, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("FamilyVault Industrial", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(28.dp))
        Button(onClick = onUnlock) { Text("Authenticate with Biometrics") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DynamicOcrWorkbench(activity: Activity, db: VaultDatabase, onLock: () -> Unit) {
    val scope = rememberCoroutineScope()
    val dao = remember { db.vaultDao() }
    val crypto = remember { VaultCryptoManager(activity.applicationContext) }
    val members by dao.observeAllFamilyMembers().collectAsState(initial = emptyList())
    val docs by dao.observeAllDocuments().collectAsState(initial = emptyList())

    var editableName by remember { mutableStateOf("") }
    var editableSubType by remember { mutableStateOf("") }
    var editableCategory by remember { mutableStateOf("ID_CARD") }
    var editableNumber by remember { mutableStateOf("") }
    var editableAuthority by remember { mutableStateOf("") }
    var editableIssue by remember { mutableStateOf("") }
    var editableExpiry by remember { mutableStateOf("") }
    var editableTags by remember { mutableStateOf("{}") }
    var rawText by remember { mutableStateOf("") }
    var banner by remember { mutableStateOf("Tap 'Scan Any Personal / Govt ID' to auto-detect all fields.") }
    var pendingAttachments by remember { mutableStateOf<List<EncryptedPageAttachment>>(emptyList()) }
    var mismatchState by remember { mutableStateOf<Pair<NameResolutionResult.MinimalMismatchAlert, ParsedDocumentPayload>?>(null) }

    val scannerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val uris = GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pages?.map { it.imageUri }.orEmpty()
            if (uris.isNotEmpty()) {
                banner = "Analyzing ${uris.size} page(s) with Spatial & Dynamic Tag Engine..."
                scope.launch {
                    try {
                        val out = ScanAndVoiceProcessor.processScannedDocumentPages(activity, uris, crypto)
                        val p = out.parsedPayload
                        editableName = p.extractedName
                        editableCategory = p.docCategory
                        editableSubType = p.docSubType
                        editableNumber = p.documentNumber
                        editableAuthority = p.issuingAuthority
                        editableIssue = p.issueDate.orEmpty()
                        editableExpiry = p.expiryDate.orEmpty()
                        editableTags = p.extraAttributesJson
                        rawText = p.rawText
                        pendingAttachments = out.attachments
                        banner = "Auto-Detected: ${p.docSubType} • Holder: '${p.extractedName}' • ID: ${p.documentNumber}"
                    } catch (t: Throwable) {
                        banner = "Scan Error: ${t.localizedMessage}"
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FamilyVault • Dynamic ID Engine") },
                actions = { TextButton(onClick = onLock) { Text("Lock Vault") } }
            )
        }
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Button(
                    onClick = {
                        val opts = GmsDocumentScannerOptions.Builder()
                            .setGalleryImportAllowed(true)
                            .setPageLimit(4)
                            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                            .build()
                        GmsDocumentScanning.getClient(opts).getStartScanIntent(activity)
                            .addOnSuccessListener { scannerLauncher.launch(IntentSenderRequest.Builder(it).build()) }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Scan Any Personal / Govt ID (Front & Back)", fontWeight = FontWeight.Bold) }
            }
            item { Text(banner, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(editableName, { editableName = it }, label = { Text("Holder Name (Auto-Detected)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(editableSubType, { editableSubType = it }, label = { Text("Document Type") }, modifier = Modifier.weight(1f), singleLine = true)
                            OutlinedTextField(editableNumber, { editableNumber = it }, label = { Text("Document / EC / ID No") }, modifier = Modifier.weight(1f), singleLine = true)
                        }
                        OutlinedTextField(editableAuthority, { editableAuthority = it }, label = { Text("Issuing Authority / Organization") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(editableIssue, { editableIssue = it }, label = { Text("Issue / DOJ Date") }, modifier = Modifier.weight(1f), singleLine = true)
                            OutlinedTextField(editableExpiry, { editableExpiry = it }, label = { Text("Expiry Date") }, modifier = Modifier.weight(1f), singleLine = true)
                        }
                        OutlinedTextField(editableTags, { editableTags = it }, label = { Text("Dynamic Tags (Blood Group, Height, ID Mark, IFSC, etc.)") }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val payload = ParsedDocumentPayload(
                                editableCategory,
                                editableSubType.ifBlank { "General Document" },
                                editableAuthority.ifBlank { "Institutional Authority" },
                                editableName.ifBlank { "Unknown Member" },
                                editableNumber.ifBlank { "UNPARSED-NO" },
                                editableIssue.ifBlank { null },
                                editableExpiry.ifBlank { null },
                                null,
                                editableTags,
                                rawText.ifBlank { "$editableName $editableNumber" }
                            )
                            val norm = NameResolutionEngine.normalizeToSortedTokens(payload.extractedName)
                            val aliasId = dao.findMemberIdByAlias(norm)
                            val existing = dao.getAllFamilyMembersOnce()
                            val savedAtts = pendingAttachments

                            when (val res = NameResolutionEngine.resolveIdentity(payload.extractedName, existing, aliasId)) {
                                is NameResolutionResult.ExactOrAliasMatch -> {
                                    val (docId, msg) = dao.upsertDocumentWithVersionAudit(
                                        DocumentEntity(0, res.member.memberId, payload.docCategory, payload.docSubType, payload.extractedName, payload.documentNumber, payload.issuingAuthority, payload.issueDate, payload.expiryDate, null, payload.extraAttributesJson, payload.rawText, "DYNAMIC_OCR")
                                    )
                                    savedAtts.forEach { a -> dao.insertAttachment(DocumentAttachmentEntity(0, docId, a.pageOrder, "SCAN_WEBP", a.encryptedFilePath, a.sha256Hash)) }
                                    pendingAttachments = emptyList()
                                    withContext(Dispatchers.Main) { banner = "Saved to '${res.member.canonicalName}' • $msg" }
                                }
                                is NameResolutionResult.MinimalMismatchAlert -> withContext(Dispatchers.Main) { mismatchState = Pair(res, payload) }
                                is NameResolutionResult.NewMemberDetected -> {
                                    val newId = dao.insertFamilyMember(FamilyMemberEntity(0, res.cleanedDisplayName, res.sortedTokens, res.phoneticCode, null, "Family Member"))
                                    val (docId, msg) = dao.upsertDocumentWithVersionAudit(
                                        DocumentEntity(0, newId, payload.docCategory, payload.docSubType, payload.extractedName, payload.documentNumber, payload.issuingAuthority, payload.issueDate, payload.expiryDate, null, payload.extraAttributesJson, payload.rawText, "DYNAMIC_OCR")
                                    )
                                    savedAtts.forEach { a -> dao.insertAttachment(DocumentAttachmentEntity(0, docId, a.pageOrder, "SCAN_WEBP", a.encryptedFilePath, a.sha256Hash)) }
                                    pendingAttachments = emptyList()
                                    withContext(Dispatchers.Main) { banner = "Created Profile '${res.cleanedDisplayName}' • $msg" }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save Segregated Record to Encrypted Database") }
            }
            items(members) { m ->
                val mDocs = docs.filter { it.memberId == m.memberId }
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("👤 ${m.canonicalName}", fontWeight = FontWeight.Bold)
                        mDocs.forEach { d ->
                            Text(
                                "📄 ${d.docSubType} (v${d.versionNumber}) | No: ${d.documentNumber}" +
                                    (d.issueDate?.let { " | Issue/DOJ: $it" } ?: "") +
                                    (d.expiryDate?.let { " | Exp: $it" } ?: "") +
                                    "\n   Authority: ${d.issuingAuthority}\n   Tags: ${d.extraAttributesJson}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }

    val alert = mismatchState
    if (alert != null) {
        val (mismatch, parsed) = alert
        AlertDialog(
            onDismissRequest = { mismatchState = null },
            icon = { Icon(Icons.Default.Warning, null) },
            title = { Text("Spelling / Initial Mismatch") },
            text = {
                Text("Scanned: '${mismatch.extractedRawName}'\nMatches Existing: '${mismatch.candidateMember.canonicalName}'\nReason: ${mismatch.matchReason}")
            },
            confirmButton = {
                Button(onClick = {
                    mismatchState = null
                    scope.launch(Dispatchers.IO) {
                        dao.insertAlias(MemberAliasEntity(0, mismatch.candidateMember.memberId, mismatch.normalizedAliasToSave))
                        val (_, msg) = dao.upsertDocumentWithVersionAudit(
                            DocumentEntity(0, mismatch.candidateMember.memberId, parsed.docCategory, parsed.docSubType, parsed.extractedName, parsed.documentNumber, parsed.issuingAuthority, parsed.issueDate, parsed.expiryDate, null, parsed.extraAttributesJson, parsed.rawText, "ALIAS_MERGED")
                        )
                        withContext(Dispatchers.Main) { banner = "Merged into '${mismatch.candidateMember.canonicalName}' • $msg" }
                    }
                }) { Text("Move to Existing") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    mismatchState = null
                    scope.launch(Dispatchers.IO) {
                        val id = dao.insertFamilyMember(
                            FamilyMemberEntity(0, mismatch.extractedRawName, NameResolutionEngine.normalizeToSortedTokens(parsed.extractedName), NameResolutionEngine.computePhoneticSignature(parsed.extractedName), null, "Family Member")
                        )
                        dao.upsertDocumentWithVersionAudit(
                            DocumentEntity(0, id, parsed.docCategory, parsed.docSubType, parsed.extractedName, parsed.documentNumber, parsed.issuingAuthority, parsed.issueDate, parsed.expiryDate, null, parsed.extraAttributesJson, parsed.rawText, "NEW_PROFILE")
                        )
                        withContext(Dispatchers.Main) { banner = "Created Separate Profile '${mismatch.extractedRawName}'" }
                    }
                }) { Text("Create New") }
            }
        )
    }
}