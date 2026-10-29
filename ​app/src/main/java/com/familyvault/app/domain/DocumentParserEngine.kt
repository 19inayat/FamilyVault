package com.familyvault.app.domain

import org.json.JSONObject

data class ParsedDocumentPayload(
    val docCategory: String,
    val docSubType: String,
    val issuingAuthority: String,
    val extractedName: String,
    val documentNumber: String,
    val issueDate: String?,
    val expiryDate: String?,
    val linkedMobile: String?,
    val extraAttributesJson: String,
    val rawText: String
)

object DocumentParserEngine {

    private val PAN_REGEX = Regex("\\b([A-Z]{5}[0-9]{4}[A-Z])\\b")
    private val IFSC_REGEX = Regex("\\b([A-Z]{4}0[A-Z0-9]{6})\\b")
    private val DL_REGEX = Regex("\\b([A-Z]{2}[-\\s]?[0-9]{2}[-\\s]?[0-9]{4}[-\\s]?[0-9]{7})\\b")
    private val NADA_KACHERI_RD_REGEX = Regex("\\b(RD[0-9]{13})\\b")
    private val PASSPORT_REGEX = Regex("\\b([A-Z][0-9]{7})\\b")
    private val EPIC_VOTER_REGEX = Regex("\\b([A-Z]{3}[0-9]{7})\\b")
    private val MOBILE_REGEX = Regex("\\b([6-9][0-9]{9})\\b")
    private val DATE_REGEX = Regex("\\b([0-3][0-9])[/-]([0-1][0-9])[/-](19[0-9]{2}|20[0-9]{2})\\b")
    private val TWELVE_DIGIT_REGEX = Regex("\\b([2-9][0-9]{3}[\\s-]?[0-9]{4}[\\s-]?[0-9]{4})\\b")

    /**
     * Parses raw OCR text or PDF text stream into structured database columns.
     */
    fun parseDocumentText(rawInput: String): ParsedDocumentPayload {
        val cleanText = rawInput.trim()
        val upperText = cleanText.uppercase()
        val lines = cleanText.lines().map { it.trim() }.filter { it.isNotEmpty() }

        // Stage A: Document Type & Authority Classification
        val (category, subType, defaultAuthority) = classifyDocument(upperText)

        // Stage B: Primary Document Number & Checksum Extraction
        val docNumber = extractPrimaryDocumentNumber(upperText, subType)

        // Stage C: Name on Document Extraction
        val extractedName = extractPersonName(lines, subType)

        // Stage D: Dates (DOB, Issue Date, Expiry Date) & Linked Mobile
        val (issueDate, expiryDate, dob) = extractDates(lines)
        val linkedMobile = MOBILE_REGEX.find(cleanText)?.groupValues?.get(1)

        // Stage E: Category-Specific Extra JSON Attributes
        val extraJson = buildExtraAttributesJson(upperText, lines, subType, dob)

        return ParsedDocumentPayload(
            docCategory = category,
            docSubType = subType,
            issuingAuthority = defaultAuthority,
            extractedName = extractedName,
            documentNumber = docNumber,
            issueDate = issueDate,
            expiryDate = expiryDate,
            linkedMobile = linkedMobile,
            extraAttributesJson = extraJson,
            rawText = cleanText
        )
    }

    private fun classifyDocument(upperText: String): Triple<String, String, String> {
        return when {
            upperText.contains("INCOME TAX DEPARTMENT") ||
                upperText.contains("PERMANENT ACCOUNT NUMBER") ||
                PAN_REGEX.containsMatchIn(upperText) -> {
                Triple("ID_CARD", "PAN Card", "Income Tax Department, Govt. of India")
            }
            upperText.contains("DRIVING LICENCE") ||
                upperText.contains("DRIVING LICENSE") ||
                DL_REGEX.containsMatchIn(upperText) -> {
                Triple("ID_CARD", "Driving License", "Regional Transport Office (RTO)")
            }
            upperText.contains("NADA KACHERI") ||
                upperText.contains("TAHSILDAR") ||
                NADA_KACHERI_RD_REGEX.containsMatchIn(upperText) -> {
                Triple("GOVT_CERT", "Nada Kacheri Certificate", "Revenue Department (Nada Kacheri)")
            }
            upperText.contains("PASSBOOK") ||
                upperText.contains("IFSC") ||
                IFSC_REGEX.containsMatchIn(upperText) -> {
                val bankName = detectBankAuthority(upperText)
                Triple("BANK_PASSBOOK", "Bank Passbook", bankName)
            }
            upperText.contains("REPUBLIC OF INDIA") &&
                (upperText.contains("PASSPORT") || upperText.contains("P<IND")) -> {
                Triple("PASSPORT", "Indian Passport", "Ministry of External Affairs, Govt. of India")
            }
            upperText.contains("ELECTION COMMISSION") ||
                upperText.contains("ELECTOR PHOTO") -> {
                Triple("ID_CARD", "Voter ID (EPIC)", "Election Commission of India")
            }
            upperText.contains("MARKS") ||
                upperText.contains("SECONDARY EDUCATION") ||
                upperText.contains("UNIVERSITY") -> {
                Triple("MARKS_CARD", "Academic Marks Card", "Examination Board / University")
            }
            upperText.contains("UNIQUE IDENTIFICATION") ||
                upperText.contains("UIDAI") -> {
                Triple("ID_CARD", "National ID Card", "Unique Identification Authority of India")
            }
            else -> {
                Triple("GOVT_CERT", "General Document", "Government / Institutional Authority")
            }
        }
    }

    private fun extractPrimaryDocumentNumber(upperText: String, subType: String): String {
        PAN_REGEX.find(upperText)?.value?.let { return it }
        NADA_KACHERI_RD_REGEX.find(upperText)?.value?.let { return it }
        DL_REGEX.find(upperText)?.value?.let { return it.replace("\\s+".toRegex(), " ") }

        if (subType == "Bank Passbook") {
            val accMatch = Regex("(?:A/C\\s*NO|ACCOUNT\\s*NO|ACC\\s*NO)[\\s.:-]*([0-9]{9,18})")
                .find(upperText)
            if (accMatch != null) return accMatch.groupValues[1]
        }

        if (subType == "Academic Marks Card") {
            val regMatch = Regex("(?:REG\\.?\\s*NO|REGISTER\\s*NO|ROLL\\s*NO)[\\s.:-]*([A-Z0-9]{5,15})")
                .find(upperText)
            if (regMatch != null) return regMatch.groupValues[1]
        }

        PASSPORT_REGEX.find(upperText)?.value?.let {
            if (subType == "Indian Passport") return it
        }
        EPIC_VOTER_REGEX.find(upperText)?.value?.let {
            if (subType == "Voter ID (EPIC)") return it
        }

        // Check 12-digit pattern with Verhoeff Checksum validation
        TWELVE_DIGIT_REGEX.findAll(upperText).forEach { match ->
            val digitsOnly = match.value.replace(Regex("[^0-9]"), "")
            if (digitsOnly.length == 12 && validateVerhoeffChecksum(digitsOnly)) {
                return "XXXX-XXXX-${digitsOnly.takeLast(4)}"
            }
        }

        val genericNo = Regex("(?:DOC\\s*NO|CERT\\s*NO|NUMBER|ID\\s*NO)[\\s.:-]*([A-Z0-9/-]{5,20})")
            .find(upperText)
        return genericNo?.groupValues?.get(1) ?: "UNPARSED-NO"
    }

    private fun extractPersonName(lines: List<String>, subType: String): String {
        // 1. Check explicit label anchors ("Name :", "Account Holder :", "Candidate's Name :")
        val nameLabelRegex = Regex(
            "^(?:CANDIDATE'?S?\\s*NAME|STUDENT\\s*NAME|ACCOUNT\\s*HOLDER(?:\\s*NAME)?|APPLICANT\\s*NAME|NAME)\\s*[:.-]+\\s*(.+)$",
            RegexOption.IGNORE_CASE
        )
        for (line in lines) {
            val match = nameLabelRegex.find(line)
            if (match != null) {
                val candidate = match.groupValues[1].trim()
                if (candidate.length >= 3) return candidate
            }
        }

        // 2. PAN Card Spatial Rule: Line immediately above "Father's Name" or after "Name" header
        for (i in lines.indices) {
            val upper = lines[i].uppercase()
            if (upper == "NAME" && i + 1 < lines.size) {
                return lines[i + 1]
            }
            if (upper.contains("FATHER") && i >= 1) {
                val prevLine = lines[i - 1]
                if (!prevLine.uppercase().contains("INCOME TAX") &&
                    !prevLine.uppercase().contains("GOVT") &&
                    prevLine.any { it.isLetter() }
                ) {
                    return prevLine
                }
            }
        }

        return "Unknown Member"
    }

    private fun extractDates(lines: List<String>): Triple<String?, String?, String?> {
        var issueDate: String? = null
        var expiryDate: String? = null
        var dob: String? = null

        for (line in lines) {
            val upper = line.uppercase()
            val datesInLine = DATE_REGEX.findAll(line).map { match ->
                val (dd, mm, yyyy) = match.destructured
                "$yyyy-$mm-$dd"
            }.toList()

            if (datesInLine.isEmpty()) continue

            when {
                upper.contains("EXPIR") || upper.contains("VALID TILL") || upper.contains("VALID UPTO") -> {
                    expiryDate = datesInLine.last()
                }
                upper.contains("DOB") || upper.contains("BIRTH") -> {
                    dob = datesInLine.first()
                }
                upper.contains("ISSUE") || upper.contains("DATE") -> {
                    if (issueDate == null) issueDate = datesInLine.first()
                }
            }
        }
        return Triple(issueDate, expiryDate, dob)
    }

    private fun buildExtraAttributesJson(
        upperText: String,
        lines: List<String>,
        subType: String,
        dob: String?
    ): String {
        val json = JSONObject()
        if (dob != null) json.put("dob", dob)

        IFSC_REGEX.find(upperText)?.value?.let { json.put("ifsc", it) }

        for (line in lines) {
            val fatherMatch = Regex("^(?:FATHER'?S?\\s*NAME|CARE\\s*OF|S/O|D/O|W/O)\\s*[:.-]+\\s*(.+)$", RegexOption.IGNORE_CASE)
                .find(line)
            if (fatherMatch != null) {
                json.put("father_or_guardian", fatherMatch.groupValues[1].trim())
            }
            val pctMatch = Regex("(?:PERCENTAGE|RESULT|CGPA)\\s*[:.-]+\\s*([0-9.]+%?)", RegexOption.IGNORE_CASE)
                .find(line)
            if (pctMatch != null) {
                json.put("score", pctMatch.groupValues[1].trim())
            }
        }
        json.put("parsed_sub_type", subType)
        return json.toString()
    }

    private fun detectBankAuthority(upperText: String): String {
        return when {
            upperText.contains("STATE BANK OF INDIA") || upperText.contains("SBIN0") -> "State Bank of India (SBI)"
            upperText.contains("CANARA BANK") || upperText.contains("CNRB0") -> "Canara Bank"
            upperText.contains("HDFC BANK") || upperText.contains("HDFC0") -> "HDFC Bank"
            upperText.contains("ICICI BANK") || upperText.contains("ICIC0") -> "ICICI Bank"
            upperText.contains("UNION BANK") || upperText.contains("UBIN0") -> "Union Bank of India"
            upperText.contains("KARNATAKA VIKAS") || upperText.contains("KVGB") -> "Karnataka Vikas Grameena Bank"
            else -> "Banking Institution"
        }
    }

    /**
     * Verhoeff Dihedral Group D5 Checksum Validator (catches 100% of single-digit OCR misreads).
     */
    fun validateVerhoeffChecksum(digits: String): Boolean {
        if (digits.length != 12 || !digits.all { it.isDigit() }) return false
        val d = arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
            intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
            intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6),
            intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
            intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8),
            intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
            intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2),
            intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
            intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4),
            intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0)
        )
        val p = arrayOf(
            intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9),
            intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
            intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2),
            intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
            intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0),
            intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
            intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5),
            intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8)
        )
        var c = 0
        val reversed = digits.reversed()
        for (i in reversed.indices) {
            c = d[c][p[i % 8][reversed[i].digitToInt()]]
        }
        return c == 0
    }
}
