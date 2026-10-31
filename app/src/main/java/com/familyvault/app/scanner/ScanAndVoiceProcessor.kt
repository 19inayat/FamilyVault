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
import kotlin.math.min

data class ScanExecutionResult(
    val parsedPayload: ParsedDocumentPayload,
    val qualityReport: String,
    val encryptedFilePath: String,
    val sha256Hash: String,
    val qrVerified: Boolean
)

object ScanAndVoiceProcessor {

    private data class SpatialLine(
        val text: String,
        val box: Rect,
        val centerY: Int,
        val height: Int
    )

    suspend fun processScannedDocumentUri(
        context: Context,
        imageUri: Uri,
        cryptoManager: VaultCryptoManager
    ): ScanExecutionResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext

        val rawBitmap = appContext.contentResolver.openInputStream(imageUri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: throw IllegalStateException("Unable to decode scanned image")

        val normalizedBitmap = scaleToOptimalOcrResolution(rawBitmap)
        val contrastBitmap = createHighContrastDocumentBitmap(normalizedBitmap)
        val qualityReport = evaluateBlurAndGlare(normalizedBitmap)

        val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val barcodeScanner = BarcodeScanning.getClient()

        val primaryImage = InputImage.fromBitmap(normalizedBitmap, 0)
        val enhancedImage = InputImage.fromBitmap(contrastBitmap, 0)

        val primaryOcr = textRecognizer.process(primaryImage).awaitTask()
        val enhancedOcr = textRecognizer.process(enhancedImage).awaitTask()

        val barcodes: List<Barcode> = try {
            barcodeScanner.process(primaryImage).awaitTask()
        } catch (_: Throwable) {
            emptyList()
        }

        val qrLines = barcodes.mapNotNull { it.rawValue }.map { decodeGovernmentQrPayload(it) }
        val hasQr = qrLines.isNotEmpty()

        val primarySpatialText = reconstructSpatialDocumentLines(primaryOcr)
        val enhancedSpatialText = reconstructSpatialDocumentLines(enhancedOcr)

        val mergedOcrText = buildString {
            if (hasQr) {
                appendLine(qrLines.joinToString("\n"))
            }
            appendLine(primarySpatialText)
            if (enhancedSpatialText.isNotBlank()) {
                appendLine("---ENHANCED_PASS---")
                appendLine(enhancedSpatialText)
            }
        }.trim()

        val parsed = DocumentParserEngine.parseDocumentText(
            mergedOcrText.ifEmpty { "General Document\nName : Unknown Member" }
        )

        val webpStream = ByteArrayOutputStream()
        val compressFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        normalizedBitmap.compress(compressFormat, 90, webpStream)
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

    private fun reconstructSpatialDocumentLines(visionText: Text): String {
        val rawLines = mutableListOf<SpatialLine>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val cleanWords = line.elements
                    .filter { el ->
                        val conf = el.confidence
                        conf == null || conf == 0f || conf >= 0.42f
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
                            height = box.height().coerceAtLeast(12)
                        )
                    )
                }
            }
        }

        if (rawLines.isEmpty()) return ""

        val sortedByY = rawLines.sortedBy { it.centerY }
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

        val verticalPairs = mutableListOf<String>()
        val labelTriggerRegex = Regex(
            "^(?:NAME|FULL\\s*NAME|FATHER'?S?\\s*NAME|MOTHER'?S?\\s*NAME|HOLDER\\s*NAME|ELECTOR'?S?\\s*NAME|PERMANENT\\s*ACCOUNT\\s*NUMBER|DATE\\s*OF\\s*BIRTH|DOB|VALID\\s*TILL|DATE\\s*OF\\s*EXPIRY|DATE\\s*OF\\s*ISSUE|PASSPORT\\s*NO|LICENCE\\s*NO|ACCOUNT\\s*NO)$",
            RegexOption.IGNORE_CASE
        )

        for (i in sortedByY.indices) {
            val topLine = sortedByY[i]
            val cleanedLabel = topLine.text.replace(Regex("[^A-Za-z\\s']"), "").trim()
            if (labelTriggerRegex.matches(cleanedLabel)) {
                val candidateBelow = sortedByY.drop(i + 1).firstOrNull { below ->
                    val verticalGap = below.box.top - topLine.box.bottom
                    val horizontalOffset = abs(below.box.left - topLine.box.left)
                    verticalGap in -10..(topLine.height * 3) &&
                        horizontalOffset <= (topLine.box.width().coerceAtLeast(180))
                }
                if (candidateBelow != null) {
                    verticalPairs.add("${cleanedLabel.uppercase()} : ${candidateBelow.text}")
                }
            }
        }

        return buildString {
            horizontalRows.forEach { appendLine(it) }
            if (verticalPairs.isNotEmpty()) {
                appendLine("---SPATIAL_PAIRS---")
                verticalPairs.forEach { appendLine(it) }
            }
        }.trim()
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
        val focusLabel = if (sharpnessScore >= 180) "Sharp Focus" else "Soft Focus"
        val glareLabel = if (glarePct < 12.0) "Low Glare (${"%.1f".format(glarePct)}%)" else "Glare Warning (${"%.1f".format(glarePct)}%)"
        return "$focusLabel • $glareLabel"
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
        addOnFailureListener { exc -> if (cont.isActive) cont.resumeWithException(exc) }
        addOnCanceledListener { if (cont.isActive) cont.cancel() }
    }
}
