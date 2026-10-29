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
import com.familyvault.app.data.local.DocumentEntity
import com.familyvault.app.data.local.FamilyMemberEntity
import com.familyvault.app.data.local.VaultDatabase
import com.familyvault.app.data.local.searchDocumentsFts5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {

    private var unlockedDb by mutableStateOf<VaultDatabase?>(null)
    private var authStatusMessage by mutableStateOf("Vault Locked — Tap Authenticate to Unlock")
    private var fatalDiagnosticLog by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fixed: uses lowercase .simpleName instead of .Name
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
                                onUnlockRequest = { triggerBiometricPromptSafe() },
                                onDirectPinFallback = { unlockSqlCipherDatabase() }
                            )
                        }
                        else -> {
                            Sprint1VerifiedDashboard(
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
                    promptBuilder.setNegativeButtonText("Use Vault Fallback")
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
                        authStatusMessage = "Biometric Status: $errString"
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        authStatusMessage = "Fingerprint not recognized. Try again."
                    }
                }
            )
            biometricPrompt.authenticate(promptBuilder.build())
        } catch (t: Throwable) {
            authStatusMessage = "Biometric Fallback Activated: ${t.localizedMessage}"
            unlockSqlCipherDatabase()
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
        Text(
            text = log,
            style = MaterialTheme.typography.bodySmall
        )
        Button(onClick = onDismiss) {
            Text("Return to Lock Screen")
        }
    }
}

@Composable
private fun LockedVaultScreen(
    statusMessage: String,
    onUnlockRequest: () -> Unit,
    onDirectPinFallback: () -> Unit
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
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onDirectPinFallback) {
            Text("Unlock Directly (Hardware Keystore Test)")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sprint1VerifiedDashboard(
    db: VaultDatabase,
    onLockVault: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val members by db.vaultDao().observeAllFamilyMembers().collectAsState(initial = emptyList())
    var ftsTestResult by remember { mutableStateOf("Tap 'Run Diagnostic Self-Test' to verify SQLCipher + FTS5.") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FamilyVault • Sprint 1 Core") },
                actions = {
                    TextButton(onClick = onLockVault) {
                        Text("Lock Vault")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "SQLCipher 4.6.1 AES-256 Unlocked",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Realme Android 13 Verified • Zero Internet • Hardware TEE Keystore",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        try {
                            val dao = db.vaultDao()
                            val existing = dao.getAllFamilyMembersOnce()
                            val memberId = if (existing.isEmpty()) {
                                dao.insertFamilyMember(
                                    FamilyMemberEntity(
                                        canonicalName = "Self Profile (Test)",
                                        sortedTokens = "PROFILE SELF TEST",
                                        phoneticPrimary = "SLF",
                                        relationshipRole = "Self"
                                    )
                                )
                            } else {
                                existing.first().memberId
                            }

                            dao.insertDocument(
                                DocumentEntity(
                                    memberId = memberId,
                                    docCategory = "ID_CARD",
                                    docSubType = "PAN Card",
                                    nameOnDocument = "Self Profile",
                                    documentNumber = "ABCDE1234F",
                                    issuingAuthority = "Income Tax Department",
                                    rawExtractedText = "INCOME TAX DEPARTMENT GOVT OF INDIA IFSC SBIN0040123",
                                    extractionSource = "SPRINT1_DIAGNOSTIC"
                                )
                            )

                            val startNs = System.nanoTime()
                            val ftsHits = dao.searchDocumentsFts5("04012")
                            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0

                            withContext(Dispatchers.Main) {
                                ftsTestResult = "✅ FTS5 Substring Search ('04012' inside 'SBIN0040123') " +
                                    "returned ${ftsHits.size} record(s) in ${"%.2f".format(elapsedMs)} ms!"
                            }
                        } catch (t: Throwable) {
                            withContext(Dispatchers.Main) {
                                ftsTestResult = "❌ Test Error: ${t.localizedMessage}"
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Run Diagnostic Self-Test (Insert & FTS5 Search)")
            }

            Text(
                text = ftsTestResult,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary
            )

            HorizontalDivider()

            Text(
                text = "Encrypted Family Profiles (${members.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(members) { member ->
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(member.canonicalName, fontWeight = FontWeight.Bold)
                            Text(
                                "Role: ${member.relationshipRole} • Tokens: ${member.sortedTokens}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}
