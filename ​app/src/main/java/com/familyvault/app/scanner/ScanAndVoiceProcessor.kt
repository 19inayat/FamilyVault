package com.familyvault.app.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import com.familyvault.app.crypto.VaultCryptoManager
import com.familyvault.app.domain.DocumentParserEngine
import com.familyvault.app.domain.ParsedDocumentPayload
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
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
import kotlin.math.abs
import kotlin.math.max

data class EncryptedPageAttachment(
    val pageOrder: Int,
    val encryptedFilePath: String,
    val sha256Hash: String
)

data class ScanExecutionResult(
    val parsedPayload: ParsedDocumentPayload,
    val qualityReport: String,
    val attachments: List<EncryptedPageAttachment>,
    val qrVerified: Boolean
)

object ScanAndVoiceProcessor {

    private data class SpatialLine(
        val text: String,
        val box: Rect,
        val centerY: Int,
        val centerX: Int,
        val height: Int
    )

    suspend fun processScannedDocumentPages(
        context: Context,
        imageUris: List<Uri>,
        cryptoManager: VaultCryptoManager
    ): ScanExecutionResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val barcodeScanner = BarcodeScanning.getClient()

        val combinedTextBuilder = StringBuilder()
        val qrLines = mutableListOf<String>()
        val spatialNameHints = mutableListOf<String>()
        val qualityReports = mutableListOf<String>()
        val encryptedAttachments = mutableListOf<EncryptedPageAttachment>()

        val vaultDir = File(appContext.filesDir, "vault_encrypted_scans").apply { mkdirs() }

        for ((index, uri) in imageUris.withIndex()) {
            val rawBitmap = appContext.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: continue

            val normalizedBitmap = scaleToOptimalOcrResolution(rawBitmap)
            val contrastBitmap = createHighContrastDocumentBitmap(normalizedBitmap)
            qualityReports.add("P${index + 1}: ${evaluateBlurAndGlare(normalizedBitmap)}")

            val primaryImage = InputImage.fromBitmap(normalizedBitmap, 0)
            val enhancedImage = InputImage.fromBitmap(contrastBitmap, 0)

            val primaryOcr = textRecognizer.process(primaryImage).awaitTask()
            val enhancedOcr = textRecognizer.process(enhancedImage).awaitTask()

            val barcodes: List<Barcode> = try {
                barcodeScanner.process(primaryImage).awaitTask()
            } catch (_: Throwable) {
                emptyList()
            }

            barcodes.mapNotNull { it.rawValue }.forEach { rawQr ->
                qrLines.add(decodeGovernmentQrPayload(rawQr))
            }

            val (primarySpatialText, detectedBadgeName) = reconstructSpatialDocumentLines(primaryOcr)
            val (enhancedSpatialText, detectedEnhancedName) = reconstructSpatialDocumentLines(enhancedOcr)

            if (detectedBadgeName != null) spatialNameHints.add(detectedBadgeName)
            if (detectedEnhancedName != null) spatialNameHints.add(detectedEnhancedName)

            combinedTextBuilder.appendLine(primarySpatialText)
            if (enhancedSpatialText.isNotBlank()) {
                combinedTextBuilder.appendLine("---ENHANCED_PASS---")
                combinedTextBuilder.appendLine(enhancedSpatialText)
            }

            val webpStream = ByteArrayOutputStream()
            val compressFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            normalizedBitmap.compress(compressFormat, 90, webpStream)
            val webpBytes = webpStream.toByteArray()

            val destFile = File(vaultDir, "scan_${System.currentTimeMillis()}_p${index + 1}.enc")
            val sha256 = cryptoManager.encryptStreamToVaultFile(
                inputStream = ByteArrayInputStream(webpBytes),
                destinationFile = destFile,
                associatedData = "VaultScan_v2"
            )

            encryptedAttachments.add(
                EncryptedPageAttachment(
                    pageOrder = index + 1,
                    encryptedFilePath = destFile.absolutePath,
                    sha256Hash = sha256
                )
            )
        }

        val fullMergedText = buildString {
            if (qrLines.isNotEmpty()) {
                appendLine(qrLines.joinToString("\n"))
            }
            spatialNameHints.distinct().forEach { hint ->
                appendLine("SPATIAL_BADGE_NAME : $hint")
            }
            append(combinedTextBuilder.toString())
        }.trim()

        val parsed = DocumentParserEngine.parseDocumentText(
            fullMergedText.ifEmpty { "General Document\nName : Unknown Member" }
        )

        ScanExecutionResult(
            parsedPayload = parsed,
            qualityReport = qualityReports.joinToString(" | "),
            attachments = encryptedAttachments,
            qrVerified = qrLines.isNotEmpty()
        )
    }

    private fun scaleToOptimalOcrResolution(source: Bitmap): Bitmap {
        val maxDim = max(source.width, source.height)
        if (maxDim in 1400..2400) return source
        val scale = 2000f / maxDim.coerceAtLeast(1)
        val targetW = (source.width * scale).toInt().coerceAtLeast(1)
        val targetH = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }

    private fun createHighContrastDocumentBitmap(source: Bitmap): Bitmap {
        val bmp = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val contrast = 1.45f
        val translate = -30f
        val matrix = ColorMatrix(
            floatArrayOf(
                0.299f * contrast, 0.587f * contrast, 0.114f * contrast, 0f, translate,
                0.299f * contrast, 0.587f * contrast, 0.114f * contrast, 0f, translate,
                0.299f * contrast, 0.587f * contrast, 0.114f * contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return bmp
    }

    /**
     * Reconstructs spatial rows and detects tagless stacked names placed directly above an ID number
     * or printed in large badge typography on ID cards.
     */
    private fun reconstructSpatialDocumentLines(visionText: Text): Pair<String, String?> {
        val rawLines = mutableListOf<SpatialLine>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val cleanWords = line.elements
                    .filter { el ->
                        val conf = el.confidence
                        conf == null || conf == 0f || conf >= 0.40f
                    }
                    .map { sanitizeOcrToken(it.text) }
                    .filter { it.isNotBlank() }

                val joinedLine = cleanWords.joinToString(" ").trim()
                if (joinedLine.length >= 2 && joinedLine.any { it.isLetterOrDigit() }) {
                    rawLines.add(
                        SpatialLine(
                            text = joinedLine,
                            box = box,
                            centerY = box.centerY(),
                            centerX = box.centerX(),
                            height = box.height().coerceAtLeast(12)
                        )
                    )
                }
            }
        }

        if (rawLines.isEmpty()) return Pair("", null)

        val sortedByY = rawLines.sortedBy { it.centerY }

        // Detect tagless stacked name directly above a standalone 6-to-12 digit ID number
        var badgeNameCandidate: String? = null
        val standaloneIdRegex = Regex("^[0-9]{6,12}$")
        for (i in sortedByY.indices) {
            val current = sortedByY[i]
            if (standaloneIdRegex.matches(current.text.replace(" ", ""))) {
                val above1 = sortedByY.getOrNull(i - 1)
                val above2 = sortedByY.getOrNull(i - 2)
                if (above1 != null && isCleanAlphaNameLine(above1.text)) {
                    badgeNameCandidate = if (above2 != null &&
                        isCleanAlphaNameLine(above2.text) &&
                        abs(above2.height - above1.height) <= (max(above1.height, above2.height) * 0.45).toInt()
                    ) {
                        "${above2.text} ${above1.text}"
                    } else {
                        above1.text
                    }
                    break
                }
            }
        }

        val mergedRows = mutableListOf<MutableList<SpatialLine>>()
        for (line in sortedByY) {
            val matchingRow = mergedRows.lastOrNull { row ->
                val rowAvgY = row.map { it.centerY }.average().toInt()
                val rowAvgH = row.map { it.height }.average().toInt()
                abs(line.centerY - rowAvgY) <= (rowAvgH * 0.55).toInt()
            }
            if (matchingRow != null) {
                matchingRow.add(line)
            } else {
                mergedRows.add(mutableListOf(line))
            }
        }

        val horizontalRows = mergedRows.map { row ->
            row.sortedBy { it.box.left }.joinToString("   ") { it.text }
        }

        val textOutput = horizontalRows.joinToString("\n").trim()
        return Pair(textOutput, badgeNameCandidate)
    }

    private fun isCleanAlphaNameLine(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length !in 3..32) return false
        if (!Regex("^[A-Za-z.\\s]+$").matches(trimmed)) return false
        val upper = trimmed.uppercase()
        val blocked = setOf(
            "JIO", "RELIANCE", "SIGNATURE", "AUTHORITY", "ISSUING", "HOLDER",
            "INDIA", "GOVT", "LIMITED", "LTD", "CORP", "PARK", "ROAD", "MUMBAI"
        )
        return upper.split("\\s+".toRegex()).none { it in blocked }
    }

    private fun sanitizeOcrToken(token: String): String {
        val stripped = token
            .replace(Regex("[^\\x20-\\x7E]"), "")
            .replace(Regex("[|~^`•§¤«»©®™]"), "")
            .trim()
        if (stripped.length == 1 && !stripped[0].isLetterOrDigit()) return ""
        return stripped
    }

    private fun decodeGovernmentQrPayload(rawQr: String): String {
        val lines = mutableListOf<String>()
        Regex("name\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(rawQr)?.let {
            lines.add("QR_NAME : ${it.groupValues[1].trim()}")
        }
        Regex("(?:dob|yob)\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(rawQr)?.let {
            lines.add("QR_DOB : ${it.groupValues[1].trim()}")
        }
        Regex("uid\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(rawQr)?.let {
            val digits = it.groupValues[1].filter { c -> c.isDigit() }
            if (digits.length >= 4) {
                lines.add("QR_UID_LAST4 : ${digits.takeLast(4)}")
            }
        }
        if (lines.isEmpty()) {
            lines.add(rawQr)
        }
        return lines.joinToString("\n")
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
        val focusLabel = if (sharpnessScore >= 180) "Sharp" else "Soft"
        val glareLabel = if (glarePct < 12.0) "Low Glare" else "Glare(${"%.0f".format(glarePct)}%)"
        return "$focusLabel/$glareLabel"
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
        addOnFailureListener { exc -> if (cont.isActive) cont.resumeWithException(exc) }
        addOnCanceledListener { if (cont.isActive) cont.cancel() }
    }
}