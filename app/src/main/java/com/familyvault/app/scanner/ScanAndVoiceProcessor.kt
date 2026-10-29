package com.familyvault.app.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import com.familyvault.app.crypto.VaultCryptoManager
import com.familyvault.app.domain.DocumentParserEngine
import com.familyvault.app.domain.ParsedDocumentPayload
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ScanExecutionResult(
    val parsedPayload: ParsedDocumentPayload,
    val qualityReport: String,
    val encryptedFilePath: String,
    val sha256Hash: String,
    val qrVerified: Boolean
)

object ScanAndVoiceProcessor {

    suspend fun processScannedDocumentUri(
        context: Context,
        imageUri: Uri,
        cryptoManager: VaultCryptoManager
    ): ScanExecutionResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext

        val bitmap = appContext.contentResolver.openInputStream(imageUri)?.use {
  BitmapFactory.decodeStream(it)
        } ?: throw IllegalStateException("Unable to read scanned image URI")

        val qualityReport = evaluateBlurAndGlare(bitmap)

        val inputImage = InputImage.fromFilePath(appContext, imageUri)
        val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val barcodeScanner = BarcodeScanning.getClient()

        val ocrResult = textRecognizer.process(inputImage).awaitTask()
        val barcodes: List<Barcode> = try {
  barcodeScanner.process(inputImage).awaitTask()
        } catch (_: Throwable) {
  emptyList()
        }

        val qrExtractedLines = barcodes.mapNotNull { it.rawValue }.map { rawQr ->
  normalizeQrPayload(rawQr)
        }
        val hasQr = qrExtractedLines.isNotEmpty()

        val combinedRawText = buildString {
  if (hasQr) {
      appendLine(qrExtractedLines.joinToString("\n"))
  }
  append(ocrResult.text)
        }.trim()

        val parsed = DocumentParserEngine.parseDocumentText(
  combinedRawText.ifEmpty { "General Document\nName : Unknown Member" }
        )

        val webpStream = ByteArrayOutputStream()
        val compressFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
  Bitmap.CompressFormat.WEBP_LOSSY
        } else {
  @Suppress("DEPRECATION")
  Bitmap.CompressFormat.WEBP
        }
        bitmap.compress(compressFormat, 88, webpStream)
        val webpBytes = webpStream.toByteArray()

        val vaultDir = File(appContext.filesDir, "vault_encrypted_scans").apply { mkdirs() }
        val destFile = File(vaultDir, "scan_${System.currentTimeMillis()}.enc")
        val sha256 = cryptoManager.encryptStreamToVaultFile(
  inputStream = ByteArrayInputStream(webpBytes),
  destinationFile = destFile,
  associatedData = "VaultScan_v2"
        )

        ScanExecutionResult(
  parsedPayload = parsed,
  qualityReport = qualityReport,
  encryptedFilePath = destFile.absolutePath,
  sha256Hash = sha256,
  qrVerified = hasQr
        )
    }

    private fun evaluateBlurAndGlare(bitmap: Bitmap): String {
        val scaled = Bitmap.createScaledBitmap(bitmap, 160, 160, true)
        val w = scaled.width
        val h = scaled.height
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)

        val luma = IntArray(w * h)
        var glarePixels = 0
        for (i in pixels.indices) {
  val p = pixels[i]
  val r = (p shr 16) and 0xFF
  val g = (p shr 8) and 0xFF
  val b = p and 0xFF
  val y = (r * 299 + g * 587 + b * 114) / 1000
  luma[i] = y
  if (y >= 248) glarePixels++
        }

        var laplacianSum = 0.0
        var count = 0
        for (y in 1 until h - 1) {
  for (x in 1 until w - 1) {
      val idx = y * w + x
      val lap = -4 * luma[idx] + luma[idx - 1] + luma[idx + 1] + luma[idx - w] + luma[idx + w]
      laplacianSum += (lap * lap).toDouble()
      count++
  }
        }

        val sharpnessScore = (laplacianSum / count.coerceAtLeast(1)).toInt()
        val glarePct = (glarePixels * 100.0) / pixels.size.coerceAtLeast(1)

        val focusLabel = if (sharpnessScore >= 180) "Sharp Focus" else "Soft Focus"
        val glareLabel = if (glarePct < 12.0) "Low Glare (${"%.1f".format(glarePct)}%)" else "Glare Warning (${"%.1f".format(glarePct)}%)"
        return "$focusLabel • $glareLabel"
    }

    private fun normalizeQrPayload(rawQr: String): String {
        val lines = mutableListOf<String>()
        Regex("name\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(rawQr)?.let {
  lines.add("Name : ${it.groupValues[1]}")
        }
        Regex("dob\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(rawQr)?.let {
  lines.add("DOB : ${it.groupValues[1]}")
        }
        if (lines.isEmpty()) lines.add(rawQr)
        return lines.joinToString("\n")
    }

    fun formatVoiceTranscriptForParser(spokenText: String): String {
        var normalized = spokenText.trim()
        val replacements = listOf(
  Regex("\\b(?:person\\s+name|holder\\s+name|member\\s+name|name\\s+is|name)\\b", RegexOption.IGNORE_CASE) to "\nName : ",
  Regex("\\b(?:document\\s+type|category\\s+is|category|document)\\b", RegexOption.IGNORE_CASE) to "\nDocument : ",
  Regex("\\b(?:document\\s+number|account\\s+number|pan\\s+number|license\\s+number|number\\s+is|number)\\b", RegexOption.IGNORE_CASE) to "\nNumber : ",
  Regex("\\b(?:issuing\\s+authority|authority\\s+is|authority|bank\\s+name)\\b", RegexOption.IGNORE_CASE) to "\nAuthority : ",
  Regex("\\b(?:expiry\\s+date|valid\\s+till|expires\\s+on|expiry)\\b", RegexOption.IGNORE_CASE) to "\nExpiry Date : ",
  Regex("\\b(?:mobile\\s+number|phone\\s+number|mobile)\\b", RegexOption.IGNORE_CASE) to "\nMobile : ",
  Regex("\\b(?:ifsc\\s+code|ifsc)\\b", RegexOption.IGNORE_CASE) to "\nIFSC : "
        )
        for ((pattern, target) in replacements) {
  normalized = normalized.replace(pattern, target)
        }
        normalized = normalized.replace(
  Regex("(?<=\\b[A-Z0-9])\\s+(?=[A-Z0-9]\\b)", RegexOption.IGNORE_CASE),
  ""
        )
        return normalized.trim()
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
        addOnFailureListener { exc -> if (cont.isActive) cont.resumeWithException(exc) }
        addOnCanceledListener { if (cont.isActive) cont.cancel() }
    }
}
