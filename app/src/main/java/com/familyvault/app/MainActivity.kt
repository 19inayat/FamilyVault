package com.familyvault.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
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
import java.util.Locale

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
              Sprint3ScannerWorkbench(
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
private fun Sprint3ScannerWorkbench(
    activity: Activity,
    db: VaultDatabase,
    onLock: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val dao = remember { db.vaultDao() }
    val cryptoManager = remember { VaultCryptoManager(activity.applicationContext) }
    val members by dao.observeAllFamilyMembers().collectAsState(initial = emptyList())
    val docs by dao.observeAllDocuments().collectAsState(initial = emptyList())

    var ocrInput by remember {
        mutableStateOf("INCOME TAX DEPARTMENT\nGOVT. OF INDIA\nName : Mohammed Riyaz\nPermanent Account Number\nABCDE1234F")
    }
    var banner by remember { mutableStateOf("Tap 'Scan Document' or 'Voice Input' to capture real data.") }
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
      banner = "Running Blur/Glare Check + Parallel OCR & QR Decoder..."
      scope.launch {
          try {
              val processed = ScanAndVoiceProcessor.processScannedDocumentUri(
                  context = activity,
                  imageUri = firstPageUri,
                  cryptoManager = cryptoManager
              )
              ocrInput = processed.parsedPayload.rawText
              pendingEncryptedScanPath = Pair(processed.encryptedFilePath, processed.sha256Hash)
              banner = "Scan Encrypted! [${processed.qualityReport}] " +
                  (if (processed.qrVerified) "• QR Verified!" else "• OCR Extracted")
          } catch (t: Throwable) {
              banner = "Scan Error: ${t.localizedMessage}"
          }
      }
  }
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
  val spokenList = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
  val bestTranscript = spokenList?.firstOrNull()
  if (!bestTranscript.isNullOrBlank()) {
      ocrInput = ScanAndVoiceProcessor.formatVoiceTranscriptForParser(bestTranscript)
      banner = "Voice Captured! Review below and tap 'Parse & Route'."
  }
        }
    }

    Scaffold(
        topBar = {
  TopAppBar(
      title = { Text("FamilyVault • Sprint 3 Scanner") },
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
                  "Sprint 3 Active: ML Kit Camera Scanner + QR + Voice Input",
                  fontWeight = FontWeight.Bold,
                  style = MaterialTheme.typography.bodyMedium
              )
          }
      }
  }

  item {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
              modifier = Modifier.weight(1f)
          ) { Text("Scan Document") }

          ElevatedButton(
              onClick = {
                  try {
                      val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                          putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                          putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                          putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak: Name, Document, Number, Authority")
                      }
                      voiceLauncher.launch(intent)
                  } catch (t: Throwable) {
                      banner = "Voice Input unavailable: ${t.localizedMessage}"
                  }
              },
              modifier = Modifier.weight(1f)
          ) { Text("Voice Input") }
      }
  }

  item {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          OutlinedButton(
              onClick = {
                  ocrInput = "INCOME TAX DEPARTMENT\nGOVT. OF INDIA\nName : Mohammed Riyaz\nPermanent Account Number\nABCDE1234F"
              },
              modifier = Modifier.weight(1f)
          ) { Text("1. PAN\n(Exact)", style = MaterialTheme.typography.labelSmall) }

          OutlinedButton(
              onClick = {
                  ocrInput = "STATE BANK OF INDIA\nPASSBOOK\nAccount Holder Name : Mohd Rlyaz\nA/c No : 30948571234\nIFSC : SBIN0040123"
              },
              modifier = Modifier.weight(1f)
          ) { Text("2. Bank\n(Typo)", style = MaterialTheme.typography.labelSmall) }

          OutlinedButton(
              onClick = {
                  ocrInput = "UNION OF INDIA\nDRIVING LICENCE\nKA25 20200098765\nName : M. Riyaz\nValid Till : 09/01/2040"
              },
              modifier = Modifier.weight(1f)
          ) { Text("3. DL\n(Initial)", style = MaterialTheme.typography.labelSmall) }
      }
  }

  item {
      OutlinedTextField(
          value = ocrInput,
          onValueChange = { ocrInput = it },
          label = { Text("Extracted OCR / QR / Voice Text (Editable)") },
          modifier = Modifier.fillMaxWidth(),
          minLines = 4
      )
  }

  item {
      Button(
          onClick = {
              scope.launch(Dispatchers.IO) {
                  val parsed = DocumentParserEngine.parseDocumentText(ocrInput)
                  val norm = NameResolutionEngine.normalizeToSortedTokens(parsed.extractedName)
                  val aliasId = dao.findMemberIdByAlias(norm)
                  val existing = dao.getAllFamilyMembersOnce()
                  val scanAttachment = pendingEncryptedScanPath

                  when (val res = NameResolutionEngine.resolveIdentity(parsed.extractedName, existing, aliasId)) {
                      is NameResolutionResult.ExactOrAliasMatch -> {
                          val (docId, msg) = dao.upsertDocumentWithVersionAudit(
                              DocumentEntity(
                                  memberId = res.member.memberId,
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
                                  extractionSource = if (scanAttachment != null) "CAMERA_SCAN" else "TEXT_OR_VOICE"
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
                              banner = "Routed to '${res.member.canonicalName}' • $msg"
                          }
                      }
                      is NameResolutionResult.MinimalMismatchAlert -> {
                          withContext(Dispatchers.Main) { mismatchState = Pair(res, parsed) }
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
                                  extractionSource = if (scanAttachment != null) "CAMERA_SCAN" else "TEXT_OR_VOICE"
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
      ) { Text("Parse Columns & Route to Database") }
  }

  item {
      Text(banner, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium)
      HorizontalDivider(Modifier.padding(vertical = 4.dp))
      Text("Profiles (${members.size}) • Documents (${docs.size})", fontWeight = FontWeight.Bold)
  }

  items(members) { m ->
      val mDocs = docs.filter { it.memberId == m.memberId }
      OutlinedCard(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Text("👤 ${m.canonicalName} (Phonetic: ${m.phoneticPrimary})", fontWeight = FontWeight.Bold)
              mDocs.forEach { d ->
                  Text(
                      "📄 ${d.docSubType} (v${d.versionNumber}) | No: ${d.documentNumber} | ${d.issuingAuthority}",
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
