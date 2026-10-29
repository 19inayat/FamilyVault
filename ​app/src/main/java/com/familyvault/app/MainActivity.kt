package com.familyvault.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.familyvault.app.domain.DocumentParserEngine
import com.familyvault.app.domain.NameResolutionEngine
import com.familyvault.app.domain.NameResolutionResult
import com.familyvault.app.domain.ParsedDocumentPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {

    private var unlockedDb by mutableStateOf<VaultDatabase?>(null)
    private var authStatusMessage by mutableStateOf("Vault Locked — Biometric Verification Required")
    private var fatalDiagnosticLog by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runOnUiThread {
                fatalDiagnosticLog = "Thread: ${thread.name}\n" +
                    "Error: ${throwable.javaClass.simpleName}: ${throwable.localizedMessage}\n\n" +
                    throwable.stackTraceToString().take(1500)
            }
        }

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val crashLog = fatalDiagnosticLog
                    val currentDb = unlockedDb

                    when {
                        crashLog != null -> {
                            DiagnosticErrorScreen(
                                log = crashLog,
                                onDismiss = { fatalDiagnosticLog = null }
                            )
                        }
                        currentDb == null -> {
                            LockedVaultScreen(
                                statusMessage = authStatusMessage,
                                onUnlockRequest = { triggerBiometricPromptSafe() }
                            )
                        }
                        else -> {
                            Sprint2ParserWorkbenchScreen(
                                db = currentDb,
                                onLockVault = {
                                    VaultDatabase.lockAndCloseDatabase()
                                    unlockedDb = null
                                    authStatusMessage = "Vault Locked & RAM Keys Scrubbed"
                                }
                            )
                        }
                    }
                }
            }
        }

        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed) {
                triggerBiometricPromptSafe()
            }
        }, 250L)
    }

    private fun triggerBiometricPromptSafe() {
        try {
            val biometricManager = BiometricManager.from(this)
            val strongOrPin = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
            val weakOrPin = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

            val promptBuilder = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock FamilyVault")
                .setSubtitle("Verify fingerprint or device PIN")

            when {
                biometricManager.canAuthenticate(strongOrPin) == BiometricManager.BIOMETRIC_SUCCESS -> {
                    promptBuilder.setAllowedAuthenticators(strongOrPin)
                }
                biometricManager.canAuthenticate(weakOrPin) == BiometricManager.BIOMETRIC_SUCCESS -> {
                    promptBuilder.setAllowedAuthenticators(weakOrPin)
                }
                biometricManager.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS -> {
                    promptBuilder.setAllowedAuthenticators(BIOMETRIC_STRONG)
                    promptBuilder.setNegativeButtonText("Cancel")
                }
                else -> {
                    unlockSqlCipherDatabase()
                    return
                }
            }

            val executor = ContextCompat.getMainExecutor(this)
            val biometricPrompt = BiometricPrompt(
                this,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        unlockSqlCipherDatabase()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        authStatusMessage = "Biometric Locked: $errString"
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        authStatusMessage = "Fingerprint not recognized. Try again."
                    }
                }
            )
            biometricPrompt.authenticate(promptBuilder.build())
        } catch (t: Throwable) {
            authStatusMessage = "Biometric Error: ${t.localizedMessage}"
        }
    }

    private fun unlockSqlCipherDatabase() {
        authStatusMessage = "Decrypting SQLCipher 256-bit Vault..."
        lifecycleScope.launch(Dispatchers.IO) {
            var passphraseBytes: ByteArray? = null
            try {
                val crypto = VaultCryptoManager(applicationContext)
                passphraseBytes = crypto.getOrCreateDatabasePassphrase()
                val db = VaultDatabase.getUnlockedInstance(applicationContext, passphraseBytes)
                // Clean up Sprint 1 dummy test profile automatically
                db.vaultDao().cleanupSprint1TestProfile()
                withContext(Dispatchers.Main) {
                    unlockedDb = db
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    fatalDiagnosticLog = "Vault Decryption Error:\n${t.stackTraceToString().take(1200)}"
                }
            } finally {
                passphraseBytes?.fill(0)
            }
        }
    }
}

@Composable
private fun DiagnosticErrorScreen(log: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "On-Screen Diagnostic Report",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold
        )
        Text(text = log, style = MaterialTheme.typography.bodySmall)
        Button(onClick = onDismiss) {
            Text("Return to Lock Screen")
        }
    }
}

@Composable
private fun LockedVaultScreen(
    statusMessage: String,
    onUnlockRequest: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = "Locked",
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "FamilyVault Industrial",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = statusMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(28.dp))
        Button(
            onClick = onUnlockRequest,
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)
        ) {
            Icon(Icons.Default.Lock, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Authenticate with Biometrics")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sprint2ParserWorkbenchScreen(
    db: VaultDatabase,
    onLockVault: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val dao = remember { db.vaultDao() }
    val members by dao.observeAllFamilyMembers().collectAsState(initial = emptyList())
    val allDocs by dao.observeAllDocuments().collectAsState(initial = emptyList())

    var rawOcrInput by remember {
        mutableStateOf(
            "INCOME TAX DEPARTMENT\nGOVT. OF INDIA\nName : Mohammed Riyaz\nFather's Name : Abdul Karim\nDOB : 14/05/1992\nPermanent Account Number\nABCDE1234F"
        )
    }
    var statusBanner by remember {
        mutableStateOf("Select a preset scan below or edit text, then tap 'Parse & Route to Database'.")
    }

    // State for Fuzzy Mismatch Alert Dialog
    var pendingMismatchAlert by remember {
        mutableStateOf<Pair<NameResolutionResult.MinimalMismatchAlert, ParsedDocumentPayload>?>(null)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FamilyVault • Sprint 2 Engine") },
                actions = {
                    TextButton(onClick = onLockVault) {
                        Text("Lock Vault")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Sprint 2: Fuzzy Name & Document Parser Active",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Jaro-Winkler • Levenshtein • Initial Expansion • Smart Version Audit",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = "1. Load Simulated OCR / Voice Scan Preset:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            rawOcrInput = "INCOME TAX DEPARTMENT\nGOVT. OF INDIA\n" +
                                "Name : Mohammed Riyaz\nFather's Name : Abdul Karim\n" +
                                "DOB : 14/05/1992\nPermanent Account Number\nABCDE1234F"
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("1. Exact PAN\n(Mohammed Riyaz)", style = MaterialTheme.typography.labelSmall)
                    }

                    OutlinedButton(
                        onClick = {
                            rawOcrInput = "STATE BANK OF INDIA\nPASSBOOK\n" +
                                "Account Holder Name : Mohd Rlyaz\n" +
                                "A/c No : 30948571234\nIFSC : SBIN0040123\n" +
                                "Mobile : 9876543210"
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("2. Typo Bank\n(Mohd Rlyaz)", style = MaterialTheme.typography.labelSmall)
                    }

                    OutlinedButton(
                        onClick = {
                            rawOcrInput = "UNION OF INDIA\nDRIVING LICENCE\n" +
                                "KA25 20200098765\nName : M. Riyaz\n" +
                                "Issue Date : 10/01/2020\nValid Till : 09/01/2040"
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("3. Initial DL\n(M. Riyaz)", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = rawOcrInput,
                    onValueChange = { rawOcrInput = it },
                    label = { Text("Simulated OCR / Voice Transcript Input") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5
                )
            }

            item {
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val parsed = DocumentParserEngine.parseDocumentText(rawOcrInput)
                            val normalizedTokens = NameResolutionEngine.normalizeToSortedTokens(parsed.extractedName)
                            val aliasHitId = dao.findMemberIdByAlias(normalizedTokens)
                            val currentMembers = dao.getAllFamilyMembersOnce()

                            val resolution = NameResolutionEngine.resolveIdentity(
                                extractedRawName = parsed.extractedName,
                                existingMembers = currentMembers,
                                aliasMemberIdIfFound = aliasHitId
                            )

                            when (resolution) {
                                is NameResolutionResult.ExactOrAliasMatch -> {
                                    val (_, auditMsg) = dao.upsertDocumentWithVersionAudit(
                                        DocumentEntity(
                                            memberId = resolution.member.memberId,
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
                                            extractionSource = "SPRINT2_PARSER"
                                        )
                                    )
                                    withContext(Dispatchers.Main) {
                                        statusBanner = "✅ Auto-Routed to '${resolution.member.canonicalName}' " +
                                            "(${resolution.matchedVia}) • $auditMsg"
                                    }
                                }

                                is NameResolutionResult.MinimalMismatchAlert -> {
                                    withContext(Dispatchers.Main) {
                                        pendingMismatchAlert = Pair(resolution, parsed)
                                    }
                                }

                                is NameResolutionResult.NewMemberDetected -> {
                                    val newMemberId = dao.insertFamilyMember(
                                        FamilyMemberEntity(
                                            canonicalName = resolution.cleanedDisplayName,
                                            sortedTokens = resolution.sortedTokens,
                                            phoneticPrimary = resolution.phoneticCode,
                                            relationshipRole = "Family Member"
                                        )
                                    )
                                    val (_, auditMsg) = dao.upsertDocumentWithVersionAudit(
                                        DocumentEntity(
                                            memberId = newMemberId,
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
                                            extractionSource = "SPRINT2_PARSER"
                                        )
                                    )
                                    withContext(Dispatchers.Main) {
                                        statusBanner = "🆕 Created New Family Member '${resolution.cleanedDisplayName}' • $auditMsg"
                                    }
                                }
                            }
                        }
                    }
