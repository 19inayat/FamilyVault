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
    val encryptedFilePath: String,
    val sha256Hash: String,
    val qrVerified: Boolean
)

object ScanAndVoiceProcessor {

    private data class SpatialLine(
        val text: String,
        val box: Rect,
        val centerY: Int,
        val centerX: Int,
        val height: Int,
        val width: Int
    )

    suspend fun processScannedDocumentUri(
        context: Context,
        imageUri: Uri,
        cryptoManager: VaultCryptoManager
    ): ScanExecutionResult = processScannedDocumentPages(context, listOf(imageUri), cryptoManager)

    suspend fun processScannedDocumentPages(
        context: Context,
        imageUris: List<Uri>,
        cryptoManager: VaultCryptoManager
    ): ScanExecutionResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val barcodeScanner = BarcodeScanning.getClient()

        val combinedText = StringBuilder()
        val qrHints = mutableListOf<String>()
        val spatialHints = mutableListOf<String>()
        val encryptedAttachments = mutableListOf<EncryptedPageAttachment>()
        val vaultDir = File(appContext.filesDir, "vault_encrypted_scans").apply { mkdirs() }

        for ((idx, uri) in imageUris.withIndex()) {
            val rawBmp = appContext.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: continue

            val normBmp = scaleBitmap(rawBmp)
            val contrastBmp = enhanceContrast(normBmp)

            val primaryImage = InputImage.fromBitmap(normBmp, 0)
            val enhancedImage = InputImage.fromBitmap(contrastBmp, 0)

            val primaryOcr = textRecognizer.process(primaryImage).awaitTask()
            val enhancedOcr = textRecognizer.process(enhancedImage).awaitTask()

            val barcodes: List<Barcode> = try {
                barcodeScanner.process(primaryImage).awaitTask()
            } catch (_: Throwable) {
                emptyList()
            }
            barcodes.mapNotNull { it.rawValue }.forEach { qrHints.add(decodeQrPayload(it)) }

            val (pText, pHints) = analyzeSpatialGeometry(primaryOcr)
            val (eText, eHints) = analyzeSpatialGeometry(enhancedOcr)
            spatialHints.addAll(pHints)
            spatialHints.addAll(eHints)

            combinedText.appendLine(pText)
            if (eText.isNotBlank()) {
                combinedText.appendLine("---ENHANCED_PASS---")
                combinedText.appendLine(eText)
            }

            val out = ByteArrayOutputStream()
            val fmt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            normBmp.compress(fmt, 90, out)
            val destFile = File(vaultDir, "scan_${System.currentTimeMillis()}_p${idx + 1}.enc")
            val sha = cryptoManager.encryptStreamToVaultFile(
                ByteArrayInputStream(out.toByteArray()),
                destFile,
                "VaultScan_v2"
            )
            encryptedAttachments.add(EncryptedPageAttachment(idx + 1, destFile.absolutePath, sha))
        }

        val fullMergedInput = buildString {
            if (qrHints.isNotEmpty()) appendLine(qrHints.distinct().joinToString("\n"))
            if (spatialHints.isNotEmpty()) appendLine(spatialHints.distinct().joinToString("\n"))
            append(combinedText.toString())
        }.trim()

        val parsed = DocumentParserEngine.parseDocumentText(
            fullMergedInput.ifEmpty { "General Document\nName : Unknown Member" }
        )
        val firstAtt = encryptedAttachments.firstOrNull()

        ScanExecutionResult(
            parsedPayload = parsed,
            qualityReport = "${encryptedAttachments.size} Page(s) • Spatial+DualPass",
            attachments = encryptedAttachments,
            encryptedFilePath = firstAtt?.encryptedFilePath.orEmpty(),
            sha256Hash = firstAtt?.sha256Hash.orEmpty(),
            qrVerified = qrHints.isNotEmpty()
        )
    }

    private fun scaleBitmap(src: Bitmap): Bitmap {
        val maxD = max(src.width, src.height)
        if (maxD in 1500..2400) return src
        val s = 2100f / maxD.coerceAtLeast(1)
        return Bitmap.createScaledBitmap(
            src,
            (src.width * s).toInt().coerceAtLeast(1),
            (src.height * s).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun enhanceContrast(src: Bitmap): Bitmap {
        val bmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = 1.50f
        val t = -35f
        val cm = ColorMatrix(
            floatArrayOf(
                0.299f * c, 0.587f * c, 0.114f * c, 0f, t,
                0.299f * c, 0.587f * c, 0.114f * c, 0f, t,
                0.299f * c, 0.587f * c, 0.114f * c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
        Canvas(bmp).drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        })
        return bmp
    }

    /**
     * Reconstructs visual table rows, pairs vertical label-value blocks, and identifies
     * tagless prominent name stacks (by font height & proximity to standalone ID numbers).
     */
    private fun analyzeSpatialGeometry(visionText: Text): Pair<String, List<String>> {
        val rawLines = mutableListOf<SpatialLine>()
        val hints = mutableListOf<String>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val cleanWords = line.elements
                    .filter { it.confidence == null || it.confidence == 0f || it.confidence >= 0.38f }
                    .map { sanitizeToken(it.text) }
                    .filter { it.isNotEmpty() }

                val joined = cleanWords.joinToString(" ").trim()
                if (joined.length >= 2 && joined.any { it.isLetterOrDigit() }) {
                    rawLines.add(
                        SpatialLine(
                            text = joined,
                            box = box,
                            centerY = box.centerY(),
                            centerX = box.centerX(),
                            height = box.height().coerceAtLeast(10),
                            width = box.width().coerceAtLeast(10)
                        )
                    )
                }
            }
        }

        if (rawLines.isEmpty()) return Pair("", emptyList())

        val sortedByY = rawLines.sortedBy { it.centerY }
        val medianHeight = sortedByY.map { it.height }.sorted()[sortedByY.size / 2].coerceAtLeast(12)

        // 1. Detect Tagless Stacked Name directly above or below a standalone 6-to-12 digit ID number
        val standaloneIdRegex = Regex("^[A-Z0-9]{6,12}$")
        for (i in sortedByY.indices) {
            val curText = sortedByY[i].text.replace(" ", "")
            if (standaloneIdRegex.matches(curText) && curText.count { it.isDigit() } >= 6) {
                hints.add("SPATIAL_ID_CANDIDATE : $curText")
                val above1 = sortedByY.getOrNull(i - 1)
                val above2 = sortedByY.getOrNull(i - 2)
                if (above1 != null && isPotentialNameLine(above1.text)) {
                    val stackedName = if (above2 != null &&
                        isPotentialNameLine(above2.text) &&
                        abs(above2.centerX - above1.centerX) <= max(above1.width, above2.width) &&
                        (above1.box.top - above2.box.bottom) <= above1.height * 2
                    ) {
                        "${above2.text} ${above1.text}"
                    } else {
                        above1.text
                    }
                    hints.add("SPATIAL_BADGE_NAME : $stackedName")
                }
            }
        }

        // 2. Detect Prominent Font-Height Name Lines (Large typography on front of ID cards)
        val prominentLines = sortedByY.filter {
            it.height >= (medianHeight * 1.15).toInt() && isPotentialNameLine(it.text)
        }
        if (prominentLines.isNotEmpty()) {
            val combinedProminent = mutableListOf<String>()
            for (p in prominentLines) {
                if (combinedProminent.size < 2) combinedProminent.add(p.text)
            }
            if (combinedProminent.isNotEmpty()) {
                hints.add("SPATIAL_PROMINENT_NAME : ${combinedProminent.joinToString(" ")}")
            }
        }

        // 3. Reconstruct Horizontal Rows (Left Label -> Right Value on same Y line)
        val rows = mutableListOf<MutableList<SpatialLine>>()
        for (l in sortedByY) {
            val existingRow = rows.lastOrNull { r ->
                val avgY = r.map { it.centerY }.average().toInt()
                val avgH = r.map { it.height }.average().toInt()
                abs(l.centerY - avgY) <= (avgH * 0.55).toInt()
            }
            if (existingRow != null) existingRow.add(l) else rows.add(mutableListOf(l))
        }

        val reconstructedLines = rows.map { r ->
            val sortedRow = r.sortedBy { it.box.left }
            if (sortedRow.size == 2 && !sortedRow[0].text.contains(":") && !sortedRow[1].text.contains(":")) {
                "${sortedRow[0].text} : ${sortedRow[1].text}"
            } else {
                sortedRow.joinToString("   ") { it.text }
            }
        }

        return Pair(reconstructedLines.joinToString("\n"), hints)
    }

    private fun isPotentialNameLine(text: String): Boolean {
        val t = text.trim()
        if (t.length !in 3..35 || t.contains(":") || !Regex("^[A-Za-z.\\s]+$").matches(t)) return false
        val blocked = setOf(
            "JIO", "RELIANCE", "SIGNATURE", "AUTHORITY", "ISSUING", "HOLDER", "INDIA",
            "GOVT", "GOVERNMENT", "LIMITED", "LTD", "PVT", "CORP", "CORPORATE", "PARK",
            "ROAD", "MUMBAI", "THANE", "BELAPUR", "INCOME", "TAX", "DEPARTMENT", "CARD",
            "UNIQUE", "IDENTIFICATION", "ELECTION", "COMMISSION", "DRIVING", "LICENCE",
            "PASSPORT", "REPUBLIC", "PASSBOOK", "BANK", "CENTRAL", "CELL", "DUTY", "LOSS"
        )
        val words = t.uppercase().replace(".", "").split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 4) return false
        return words.none { it in blocked }
    }

    private fun sanitizeToken(token: String): String {
        val s = token.replace(Regex("[^\\x20-\\x7E]"), "")
            .replace(Regex("[|~^`•§¤«»©®™]"), "")
            .trim()
        return if (s.length == 1 && !s[0].isLetterOrDigit()) "" else s
    }

    private fun decodeQrPayload(raw: String): String {
        val out = mutableListOf<String>()
        Regex("name\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(raw)?.let {
            out.add("QR_NAME : ${it.groupValues[1].trim()}")
        }
        Regex("(?:dob|yob)\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(raw)?.let {
            out.add("QR_DOB : ${it.groupValues[1].trim()}")
        }
        Regex("uid\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(raw)?.let {
            val d = it.groupValues[1].filter { c -> c.isDigit() }
            if (d.length >= 4) out.add("QR_UID_LAST4 : ${d.takeLast(4)}")
        }
        return if (out.isEmpty()) raw else out.joinToString("\n")
    }

    fun formatVoiceTranscriptForParser(spokenText: String): String = spokenText.trim()

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { if (cont.isActive) cont.resume(it) }
        addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        addOnCanceledListener { if (cont.isActive) cont.cancel() }
    }
}