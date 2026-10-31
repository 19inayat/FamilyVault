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
import androidx.compose.material.icons.filled.CheckCircle
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
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val db = unlockedDb
                    if (db == null) {
                        LockedScreen(authStatus) { triggerBiometricPromptSafe() }
                    } else {
                        HighPrecisionOcrWorkbench(
                            activity = this@MainActivity,
                            db = db,
                            onLock = {
                                VaultDatabase.lockAndCloseDatabase()
                                unlockedDb = null
                                authStatus = "Vault Locked & RAM Keys Scrubbed"
                            }
                        )
                    }
                }
            }
        }

        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed) triggerBiometricPromptSafe()
        }, 250L)
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
                bm.canAuthenticate(strongPin) == BiometricManager.BIOMETRIC_SUCCESS ->
                    builder.setAllowedAuthenticators(strongPin)
                bm.canAuthenticate(weakPin) == BiometricManager.BIOMETRIC_SUCCESS ->
                    builder.setAllowedAuthenticators(weakPin)
                bm.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS -> {
                    builder.setAllowedAuthenticators(BIOMETRIC_STRONG)
                    builder.setNegativeButtonText("Cancel")
                }
                else -> {
                    unlockDatabase()
                    return
                }
            }

            val prompt = BiometricPrompt(
                this,
                ContextCompat.getMainExecutor(this),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        unlockDatabase()
                    }
                    override fun onAuthenticationError(code: Int, err: CharSequence) {
                        authStatus = "Biometric Locked: $err"
                    }
                }
            )
            prompt.authenticate(builder.build())
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
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Lock, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("FamilyVault Industrial", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(28.dp))
        Button(onClick = onUnlock) {
            Icon(Icons.Default.Lock, null)
            Spacer(Modifier.width(8.dp))
            Text("Authenticate with Biometrics")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HighPrecisionOcrWorkbench(
    activity: Activity,
    db: VaultDatabase,
    onLock: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val dao = remember { db.vaultDao() }
    val cryptoManager = remember { VaultCryptoManager(activity.applicationContext) }
    val members by dao.observeAllFamilyMembers().collectAsState(initial = emptyList())
    val docs by dao.observeAllDocuments().collectAsState(initial = emptyList())

    var editableName by remember { mutableStateOf("Mohammed Riyaz") }
    var editableDocSubType by remember { mutableStateOf("PAN Card") }
    var editableDocCategory by remember { mutableStateOf("ID_CARD") }
    var editableDocNumber by remember { mutableStateOf("ABCDE1234F") }
    var editableAuthority by remember { mutableStateOf("Income Tax Department, Govt. of India") }
    var editableIssueDate by remember { mutableStateOf("") }
    var editableExpiryDate by remember { mutableStateOf("") }
    var editableExtraJson by remember { mutableStateOf("{}") }
    var rawOcrTranscript by remember { mutableStateOf("") }

    var banner by remember { mutableStateOf("Tap 'Scan Government Document' to run Dual-Pass Spatial OCR + QR.") }
    var pendingEncryptedScanPath by remember { mutableStateOf<Pair<String, String>?>(null) }
    var mismatchState by remember {
        mutableStateOf<Pair<NameResolutionResult.MinimalMismatchAlert, ParsedDocumentPayload>?>(null)
    }

    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val firstPageUri = scanResult?.pages?.firstOrNull()?.imageUri
            if (firstPageUri != null) {
                banner = "Running Dual-Pass Contrast OCR + Spatial Bounding-Box Alignment + QR..."
                scope.launch {
                    try {
                        val processed = ScanAndVoiceProcessor.processScannedDocumentUri(
                            context = activity,
                            imageUri = firstPageUri,
                            cryptoManager = cryptoManager
                        )
                        val p = processed.parsedPayload
                        editableName = p.extractedName
                        editableDocCategory = p.docCategory
                        editableDocSubType = p.docSubType
                        editableDocNumber = p.documentNumber
                        editableAuthority = p.issuingAuthority
                        editableIssueDate = p.issueDate.orEmpty()
                        editableExpiryDate = p.expiryDate.orEmpty()
                        editableExtraJson = p.extraAttributesJson
                        rawOcrTranscript = p.rawText
                        pendingEncryptedScanPath = Pair(processed.encryptedFilePath, processed.sha256Hash)

                        banner = "Extracted: ${p.docSubType} [${processed.qualityReport}] " +
                            (if (processed.qrVerified) "• QR Verified" else "• Spatial OCR Verified")
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
                title = { Text("FamilyVault • Precision OCR") },
                actions = { TextButton(onClick = onLock) { Text("Lock Vault") } }
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, Modifier.size(28.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Dual-Pass Spatial OCR + Positional De-Garbler + QR Active",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        try {
                            val options = GmsDocumentScannerOptions.Builder()
                                .setGalleryImportAllowed(true)
                                .setPageLimit(2)
                                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                                .build()
                            GmsDocumentScanning.getClient(options)
                                .getStartScanIntent(activity)
                                .addOnSuccessListener { intentSender ->
                                    scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                                }
                                .addOnFailureListener { e ->
                                    banner = "Scanner Init Error: ${e.localizedMessage}"
                                }
                        } catch (t: Throwable) {
                            banner = "Scanner Error: ${t.localizedMessage}"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    Text("Scan Government Document (Camera / Gallery)", fontWeight = FontWeight.Bold)
                }
            }

            item {
                Text(banner, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
            }

            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Segregated Database Columns (Verify / Edit Before Save)", fontWeight = FontWeight.Bold)

                        OutlinedTextField(
                            value = editableName,
                            onValueChange = { editableName = it },
                            label = { Text("Name on Document") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = editableDocSubType,
                                onValueChange = { editableDocSubType = it },
                                label = { Text("Document Type") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = editableDocNumber,
                                onValueChange = { editableDocNumber = it },
                                label = { Text("Document Number") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }

                        OutlinedTextField(
                            value = editableAuthority,
                            onValueChange = { editableAuthority = it },
                            label = { Text("Issuing Authority") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = editableIssueDate,
                                onValueChange = { editableIssueDate = it },
                                label = { Text("Issue Date (YYYY-MM-DD)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = editableExpiryDate,
                                onValueChange = { editableExpiryDate = it },
                                label = { Text("Expiry Date (YYYY-MM-DD)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val currentPayload = ParsedDocumentPayload(
                                docCategory = editableDocCategory,
                                docSubType = editableDocSubType.trim(),
                                issuingAuthority = editableAuthority.trim(),
                                extractedName = editableName.trim(),
                                documentNumber = editableDocNumber.trim(),
                                issueDate = editableIssueDate.trim().ifEmpty { null },
                                expiryDate = editableExpiryDate.trim().ifEmpty { null },
                                linkedMobile = null,
                                extraAttributesJson = editableExtraJson,
                                rawText = rawOcrTranscript.ifEmpty { "$editableDocSubType $editableName $editableDocNumber" }
                            )
                            val norm = NameResolutionEngine.normalizeToSortedTokens(currentPayload.extractedName)
                            val aliasId = dao.findMemberIdByAlias(norm)
                            val existing = dao.getAllFamilyMembersOnce()
                            val scanAttachment = pendingEncryptedScanPath

                            when (val res = NameResolutionEngine.resolveIdentity(currentPayload.extractedName, existing, aliasId)) {
                                is NameResolutionResult.ExactOrAliasMatch -> {
                                    val (docId, msg) = dao.upsertDocumentWithVersionAudit(
                                        DocumentEntity(
                                            memberId = res.member.memberId,
                                            docCategory = currentPayload.docCategory,
                                            docSubType = currentPayload.docSubType,
                                            nameOnDocument = currentPayload.extractedName,
                                            documentNumber = currentPayload.documentNumber,
                                            issuingAuthority = currentPayload.issuingAuthority,
                                            issueDate = currentPayload.issueDate,
                                            expiryDate = currentPayload.expiryDate,
                                            linkedMobile = currentPayload.linkedMobile,
                                            extraAttributesJson = currentPayload.extraAttributesJson,
                                            rawExtractedText = currentPayload.rawText,
                                            extractionSource = if (scanAttachment != null) "PRECISION_SCAN" else "MANUAL_VERIFIED"
                                        )
                                    )
                                    if (scanAttachment != null) {
                                        dao.insertAttachment(
                                            DocumentAttachmentEntity(
                                                docId = docId,
                                                pageOrder = 1,
                                                fileRole = "SCAN_WEBP",
                                                tinkEncryptedPath = scanAttachment.first,
                                                sha256Hash = scanAttachment.second
                                            )
                                        )
                                        pendingEncryptedScanPath = null
                                    }
                                    withContext(Dispatchers.Main) {
                                        banner = "Saved to '${res.member.canonicalName}' • $msg"
                                    }
                                }
                                is NameResolutionResult.MinimalMismatchAlert -> {
                                    withContext(Dispatchers.Main) { mismatchState = Pair(res, currentPayload) }
                                }
                                is NameResolutionResult.NewMemberDetected -> {
                                    val newId = dao.insertFamilyMember(
                                        FamilyMemberEntity(
                                            canonicalName = res.cleanedDisplayName,
                                            sortedTokens = res.sortedTokens,
                                            phoneticPrimary = res.phoneticCode,
                                            relationshipRole = "Family Member"
                                        )
                                    )
                                    val (docId, msg) = dao.upsertDocumentWithVersionAudit(
                                        DocumentEntity(
                                            memberId = newId,
                                            docCategory = currentPayload.docCategory,
                                            docSubType = currentPayload.docSubType,
                                            nameOnDocument = currentPayload.extractedName,
                                            documentNumber = currentPayload.documentNumber,
                                            issuingAuthority = currentPayload.issuingAuthority,
                                            issueDate = currentPayload.issueDate,
                                            expiryDate = currentPayload.expiryDate,
                                            linkedMobile = currentPayload.linkedMobile,
                                            extraAttributesJson = currentPayload.extraAttributesJson,
                                            rawExtractedText = currentPayload.rawText,
                                            extractionSource = if (scanAttachment != null) "PRECISION_SCAN" else "MANUAL_VERIFIED"
                                        )
                                    )
                                    if (scanAttachment != null) {
                                        dao.insertAttachment(
                                            DocumentAttachmentEntity(
                                                docId = docId,
                                                pageOrder = 1,
                                                fileRole = "SCAN_WEBP",
                                                tinkEncryptedPath = scanAttachment.first,
                                                sha256Hash = scanAttachment.second
                                            )
                                        )
                                        pendingEncryptedScanPath = null
                                    }
                                    withContext(Dispatchers.Main) {
                                        banner = "Created Profile '${res.cleanedDisplayName}' • $msg"
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save Segregated Record to Encrypted Database") }
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Profiles (${members.size}) • Documents (${docs.size})", fontWeight = FontWeight.Bold)
            }

            items(members) { m ->
                val mDocs = docs.filter { it.memberId == m.memberId }
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("👤 ${m.canonicalName}", fontWeight = FontWeight.Bold)
                        mDocs.forEach { d ->
                            Text(
                                "📄 ${d.docSubType} (v${d.versionNumber}) | No: ${d.documentNumber}" +
                                    (d.expiryDate?.let { " | Exp: $it" } ?: "") +
                                    "\n   Authority: ${d.issuingAuthority}",
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
                Text(
                    "Scanned: '${mismatch.extractedRawName}'\n" +
                        "Matches Existing: '${mismatch.candidateMember.canonicalName}'\n" +
                        "Reason: ${mismatch.matchReason} (${(mismatch.similarityScore * 100).toInt()}%)"
                )
            },
            confirmButton = {
                Button(onClick = {
                    mismatchState = null
                    scope.launch(Dispatchers.IO) {
                        dao.insertAlias(
                            MemberAliasEntity(
                                memberId = mismatch.candidateMember.memberId,
                                aliasNormalized = mismatch.normalizedAliasToSave
                            )
                        )
                        val (_, msg) = dao.upsertDocumentWithVersionAudit(
                            DocumentEntity(
                                memberId = mismatch.candidateMember.memberId,
                                docCategory = parsed.docCategory,
                                docSubType = parsed.docSubType,
                                nameOnDocument = parsed.extractedName,
                                documentNumber = parsed.documentNumber,
                                issuingAuthority = parsed.issuingAuthority,
                                issueDate = parsed.issueDate,
                                expiryDate = parsed.expiryDate,
                                linkedMobile = parsed.linkedMobile,
                                extraAttributesJson = parsed.extraAttributesJson,
                                rawExtractedText = parsed.rawText,
                                extractionSource = "ALIAS_MERGED"
                            )
                        )
                        withContext(Dispatchers.Main) {
                            banner = "Merged into '${mismatch.candidateMember.canonicalName}' & Alias Saved! ($msg)"
                        }
                    }
                }) { Text("Move to Existing") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    mismatchState = null
                    scope.launch(Dispatchers.IO) {
                        val id = dao.insertFamilyMember(
                            FamilyMemberEntity(
                                canonicalName = mismatch.extractedRawName,
                                sortedTokens = NameResolutionEngine.normalizeToSortedTokens(parsed.extractedName),
                                phoneticPrimary = NameResolutionEngine.computePhoneticSignature(parsed.extractedName),
                                relationshipRole = "Family Member"
                            )
                        )
                        dao.upsertDocumentWithVersionAudit(
                            DocumentEntity(
                                memberId = id,
                                docCategory = parsed.docCategory,
                                docSubType = parsed.docSubType,
                                nameOnDocument = parsed.extractedName,
                                documentNumber = parsed.documentNumber,
                                issuingAuthority = parsed.issuingAuthority,
                                issueDate = parsed.issueDate,
                                expiryDate = parsed.expiryDate,
                                linkedMobile = parsed.linkedMobile,
                                extraAttributesJson = parsed.extraAttributesJson,
                                rawExtractedText = parsed.rawText,
                                extractionSource = "NEW_PROFILE"
                            )
                        )
                        withContext(Dispatchers.Main) {
                            banner = "Created Separate Profile '${mismatch.extractedRawName}'"
                        }
                    }
                }) { Text("Create New") }
            }
        )
    }
}