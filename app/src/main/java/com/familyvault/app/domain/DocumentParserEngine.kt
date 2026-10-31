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

    private val VALID_STATE_CODES = setOf(
        "AN", "AP", "AR", "AS", "BR", "CG", "CH", "DD", "DL", "DN", "GA", "GJ",
        "HP", "HR", "JH", "JK", "KA", "KL", "LA", "LD", "MH", "ML", "MN", "MP",
        "MZ", "NL", "OD", "OR", "PB", "PY", "RJ", "SK", "TN", "TR", "TS", "UK",
        "UP", "WB"
    )

    private val NOISE_HEADER_WORDS = setOf(
        "INCOME", "TAX", "DEPARTMENT", "GOVT", "GOVERNMENT", "INDIA", "UNIQUE",
        "IDENTIFICATION", "AUTHORITY", "UIDAI", "ELECTION", "COMMISSION", "ELECTOR",
        "PHOTO", "IDENTITY", "CARD", "REPUBLIC", "PASSPORT", "DRIVING", "LICENCE",
        "LICENSE", "UNION", "KARNATAKA", "STATE", "MOTOR", "VEHICLE", "REGISTRATION",
        "CERTIFICATE", "PASSBOOK", "BANK", "BRANCH", "IFSC", "MICR", "ACCOUNT",
        "PERMANENT", "NUMBER", "SIGNATURE", "ADDRESS", "DATE", "BIRTH", "VALID",
        "TILL", "UPTO", "EXPIRY", "ISSUE", "BLOOD", "GROUP", "ORGAN", "DONOR",
        "TAHSILDAR", "REVENUE", "NADA", "KACHERI", "CASTE", "DOMICILE", "MARKS",
        "SECONDARY", "EDUCATION", "BOARD", "UNIVERSITY", "EXAMINATION", "RESULT",
        "MALE", "FEMALE", "TRANSGENDER", "NATIONALITY", "INDIAN", "HOLDER", "SPECIMEN"
    )

    private val MOBILE_REGEX = Regex("\\b([6-9][0-9]{9})\\b")
    private val PINCODE_REGEX = Regex("\\b([5][0-9]{5}|[1-8][0-9]{5})\\b")

    private val MONTH_MAP = mapOf(
        "JAN" to "01", "FEB" to "02", "MAR" to "03", "APR" to "04",
        "MAY" to "05", "JUN" to "06", "JUL" to "07", "AUG" to "08",
        "SEP" to "09", "OCT" to "10", "NOV" to "11", "DEC" to "12"
    )

    fun parseDocumentText(rawInput: String): ParsedDocumentPayload {
        val cleanText = rawInput.trim()
        val upperText = cleanText.uppercase()
        val allLines = cleanText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("---ENHANCED") && !it.startsWith("---SPATIAL") }

        val mrzParsed = parsePassportMrzIfPresent(allLines)

        val degarbledPan = findAndDegarblePan(upperText)
        val degarbledDl = findAndDegarbleDrivingLicense(upperText)
        val degarbledIfsc = findAndDegarbleIfsc(upperText)
        val degarbledRd = findAndDegarbleNadaKacheriRd(upperText)
        val degarbledEpic = findAndDegarbleVoterEpic(upperText)
        val degarbledRc = findAndDegarbleVehicleRc(upperText)
        val maskedUid = findVerifiedMaskedUid(upperText)

        val (category, subType, authority) = classifyDocument(
            upperText = upperText,
            hasPan = degarbledPan != null,
            hasDl = degarbledDl != null,
            hasIfsc = degarbledIfsc != null,
            hasRd = degarbledRd != null,
            hasMrz = mrzParsed != null,
            hasEpic = degarbledEpic != null,
            hasRc = degarbledRc != null,
            hasUid = maskedUid != null
        )

        val docNumber = when (subType) {
            "PAN Card" -> degarbledPan ?: extractFallbackNumber(upperText)
            "Driving License" -> degarbledDl ?: extractFallbackNumber(upperText)
            "Indian Passport" -> mrzParsed?.passportNo ?: findPassportNumber(upperText) ?: extractFallbackNumber(upperText)
            "Nada Kacheri Certificate" -> degarbledRd ?: extractFallbackNumber(upperText)
            "Voter ID (EPIC)" -> degarbledEpic ?: extractFallbackNumber(upperText)
            "Vehicle RC Card" -> degarbledRc ?: extractFallbackNumber(upperText)
            "Bank Passbook" -> extractBankAccountNumber(upperText) ?: degarbledIfsc ?: extractFallbackNumber(upperText)
            "Academic Marks Card" -> extractMarksRegisterNumber(upperText) ?: extractFallbackNumber(upperText)
            "National ID Card" -> maskedUid ?: extractFallbackNumber(upperText)
            else -> degarbledPan ?: degarbledDl ?: degarbledRd ?: degarbledEpic ?: maskedUid ?: extractFallbackNumber(upperText)
        }

        val extractedName = mrzParsed?.fullName
            ?: extractHighPrecisionPersonName(allLines, subType)

        val (issueDate, expiryDate, dob) = extractAllDocumentDates(
            lines = allLines,
            mrzDob = mrzParsed?.dob,
            mrzExpiry = mrzParsed?.expiryDate
        )

        val linkedMobile = MOBILE_REGEX.find(cleanText)?.groupValues?.get(1)
        val extraJson = buildExtraAttributesJson(
            upperText = upperText,
            lines = allLines,
            subType = subType,
            dob = dob,
            ifsc = degarbledIfsc
        )

        return ParsedDocumentPayload(
            docCategory = category,
            docSubType = subType,
            issuingAuthority = authority,
            extractedName = extractedName,
            documentNumber = docNumber,
            issueDate = issueDate,
            expiryDate = expiryDate,
            linkedMobile = linkedMobile,
            extraAttributesJson = extraJson,
            rawText = cleanText
        )
    }

    private fun classifyDocument(
        upperText: String,
        hasPan: Boolean,
        hasDl: Boolean,
        hasIfsc: Boolean,
        hasRd: Boolean,
        hasMrz: Boolean,
        hasEpic: Boolean,
        hasRc: Boolean,
        hasUid: Boolean
    ): Triple<String, String, String> {
        return when {
            upperText.contains("INCOME TAX") || upperText.contains("PERMANENT ACCOUNT") || hasPan ->
                Triple("ID_CARD", "PAN Card", "Income Tax Department, Govt. of India")

            upperText.contains("DRIVING LICENCE") || upperText.contains("DRIVING LICENSE") || hasDl ->
                Triple("ID_CARD", "Driving License", "Regional Transport Office (RTO)")

            hasMrz || (upperText.contains("REPUBLIC OF INDIA") && upperText.contains("PASSPORT")) ->
                Triple("PASSPORT", "Indian Passport", "Ministry of External Affairs, Govt. of India")

            upperText.contains("NADA KACHERI") || upperText.contains("TAHSILDAR") || hasRd ->
                Triple("GOVT_CERT", "Nada Kacheri Certificate", "Revenue Department (Nada Kacheri)")

            upperText.contains("ELECTION COMMISSION") || upperText.contains("ELECTOR") || hasEpic ->
                Triple("ID_CARD", "Voter ID (EPIC)", "Election Commission of India")

            upperText.contains("REGISTRATION CERTIFICATE") || upperText.contains("CHASSIS NO") || hasRc ->
                Triple("ID_CARD", "Vehicle RC Card", "Transport Department (RTO)")

            upperText.contains("PASSBOOK") || upperText.contains("IFSC") || hasIfsc ->
                Triple("BANK_PASSBOOK", "Bank Passbook", detectBankAuthority(upperText))

            upperText.contains("MARKS") || upperText.contains("SECONDARY EDUCATION") ||
                upperText.contains("UNIVERSITY") || upperText.contains("KSEAB") || upperText.contains("SSLC") ->
                Triple("MARKS_CARD", "Academic Marks Card", "Examination Board / University")

            upperText.contains("UNIQUE IDENTIFICATION") || upperText.contains("UIDAI") ||
                upperText.contains("QR_UID_LAST4") || hasUid ->
                Triple("ID_CARD", "National ID Card", "Unique Identification Authority of India")

            upperText.contains("RATION CARD") || upperText.contains("FOOD AND CIVIL") ->
                Triple("GOVT_CERT", "Ration Card", "Department of Food & Civil Supplies")

            upperText.contains("AYUSHMAN") || upperText.contains("PM-JAY") || upperText.contains("ABHA") ->
                Triple("MEDICAL", "Ayushman Bharat Health Card", "National Health Authority (NHA)")

            else ->
                Triple("GOVT_CERT", "Government Document", "Government / Institutional Authority")
        }
    }

    /**
     * Positional OCR De-Garbler for 10-character PAN (`LLLLLDDDDL`).
     * Corrects OCR confusables ('0'<->'O', '1'<->'I', '5'<->'S', '8'<->'B', '2'<->'Z', '6'<->'G').
     */
    private fun findAndDegarblePan(upperText: String): String? {
        val tokens = Regex("\\b[A-Z0-9]{10}\\b").findAll(upperText).map { it.value }.toList()
        for (token in tokens) {
            val chars = token.toCharArray()
            for (i in 0..4) chars[i] = digitToLetterConfusable(chars[i])
            for (i in 5..8) chars[i] = letterToDigitConfusable(chars[i])
            chars[9] = digitToLetterConfusable(chars[9])

            val candidate = String(chars)
            val validEntityCode = candidate[3] in setOf('P', 'C', 'H', 'F', 'A', 'T', 'B', 'L', 'J', 'G')
            if (Regex("^[A-Z]{5}[0-9]{4}[A-Z]$").matches(candidate) && validEntityCode) {
                return candidate
            }
        }
        return null
    }

    /**
     * Positional OCR De-Garbler for 15-character Indian Driving License (`LLDD YYYYDDDDDDD`).
     */
    private fun findAndDegarbleDrivingLicense(upperText: String): String? {
        val dlPattern = Regex("\\b([A-Z0-9]{2})[-\\s]?([0-9OIZSB]{2})[-\\s]?((?:19|20)[0-9OIZSB]{2})[-\\s]?([0-9OIZSB]{7})\\b")
        for (match in dlPattern.findAll(upperText)) {
            val rawState = match.groupValues[1].map { digitToLetterConfusable(it) }.joinToString("")
            if (rawState !in VALID_STATE_CODES) continue
            val rto = match.groupValues[2].map { letterToDigitConfusable(it) }.joinToString("")
            val yr = match.groupValues[3].map { letterToDigitConfusable(it) }.joinToString("")
            val serial = match.groupValues[4].map { letterToDigitConfusable(it) }.joinToString("")
            if (rto.all { it.isDigit() } && yr.all { it.isDigit() } && serial.all { it.isDigit() }) {
                return "$rawState$rto $yr$serial"
            }
        }
        return null
    }

    /**
     * Positional OCR De-Garbler for 11-character IFSC (`LLLL0XXXXXX`).
     */
    private fun findAndDegarbleIfsc(upperText: String): String? {
        val labeledMatch = Regex("(?:IFSC(?:\\s*CODE)?)[\\s.:-]*([A-Z0-9]{11})").find(upperText)
        val candidates = mutableListOf<String>()
        if (labeledMatch != null) candidates.add(labeledMatch.groupValues[1])

        val knownBanks = listOf("SBIN", "CNRB", "HDFC", "ICIC", "UBIN", "BARB", "PUNB", "KVGB", "AXIS", "IDIB", "BKID", "IOBA", "FDRL", "KKBK", "YESB")
        Regex("\\b([A-Z0-9]{11})\\b").findAll(upperText).forEach { m ->
            val v = m.value
            val prefix = v.take(4).map { digitToLetterConfusable(it) }.joinToString("")
            if (prefix in knownBanks) candidates.add(v)
        }

        for (raw in candidates) {
            val bank = raw.take(4).map { digitToLetterConfusable(it) }.joinToString("")
            val fifth = if (raw[4] == 'O' || raw[4] == 'Q' || raw[4] == 'D') '0' else raw[4]
            val branch = raw.substring(5)
            if (bank.all { it.isLetter() } && fifth == '0' && branch.all { it.isLetterOrDigit() }) {
                return "$bank$fifth$branch"
            }
        }
        return null
    }

    private fun findAndDegarbleNadaKacheriRd(upperText: String): String? {
        val match = Regex("\\bR[D0O]([0-9OIZSB]{13})\\b").find(upperText) ?: return null
        val digits = match.groupValues[1].map { letterToDigitConfusable(it) }.joinToString("")
        return if (digits.all { it.isDigit() }) "RD$digits" else null
    }

    private fun findAndDegarbleVoterEpic(upperText: String): String? {
        if (!upperText.contains("ELECTION") && !upperText.contains("ELECTOR")) return null
        val tokens = Regex("\\b([A-Z]{3}[0-9OIZSB]{7})\\b").findAll(upperText)
        for (m in tokens) {
            val raw = m.value
            val prefix = raw.take(3)
            val digits = raw.substring(3).map { letterToDigitConfusable(it) }.joinToString("")
            if (digits.all { it.isDigit() }) return "$prefix$digits"
        }
        return null
    }

    private fun findAndDegarbleVehicleRc(upperText: String): String? {
        if (!upperText.contains("REGISTRATION") && !upperText.contains("CHASSIS") && !upperText.contains("ENGINE")) return null
        val rcRegex = Regex("\\b([A-Z]{2})[-\\s]?([0-9]{2})[-\\s]?([A-Z]{1,3})[-\\s]?([0-9]{4})\\b")
        val match = rcRegex.find(upperText) ?: return null
        val state = match.groupValues[1]
        if (state !in VALID_STATE_CODES) return null
        return "${match.groupValues[1]}${match.groupValues[2]}${match.groupValues[3]}${match.groupValues[4]}"
    }

    private fun findVerifiedMaskedUid(upperText: String): String? {
        Regex("QR_UID_LAST4\\s*:\\s*([0-9]{4})").find(upperText)?.let {
            return "XXXX-XXXX-${it.groupValues[1]}"
        }
        val twelveDigitPattern = Regex("\\b([2-9][0-9OIZSB]{3})[\\s-]?([0-9OIZSB]{4})[\\s-]?([0-9OIZSB]{4})\\b")
        for (m in twelveDigitPattern.findAll(upperText)) {
            val raw = (m.groupValues[1] + m.groupValues[2] + m.groupValues[3])
                .map { letterToDigitConfusable(it) }
                .joinToString("")
            if (raw.length == 12 && raw.all { it.isDigit() } && validateVerhoeffChecksum(raw)) {
                return "XXXX-XXXX-${raw.takeLast(4)}"
            }
        }
        return null
    }

    private fun findPassportNumber(upperText: String): String? {
        return Regex("\\b([A-PR-WYa-pr-wy][1-9][0-9]{6})\\b").find(upperText)?.value
    }

    private fun extractBankAccountNumber(upperText: String): String? {
        val labeled = Regex("(?:A/C\\s*NO|ACCOUNT\\s*NO|ACC\\s*NO|A/C\\s*NUMBER)[\\s.:-]*([0-9]{9,18})").find(upperText)
        if (labeled != null) return labeled.groupValues[1]
        return Regex("\\b([0-9]{11,16})\\b").findAll(upperText)
            .map { it.value }
            .firstOrNull { !validateVerhoeffChecksum(it) }
    }

    private fun extractMarksRegisterNumber(upperText: String): String? {
        val regMatch = Regex("(?:REG\\.?\\s*NO|REGISTER\\s*NO|ROLL\\s*NO|STUDENT\\s*ID)[\\s.:-]*([A-Z0-9]{6,15})").find(upperText)
        return regMatch?.groupValues?.get(1)
    }

    private fun extractFallbackNumber(upperText: String): String {
        val genericNo = Regex("(?:DOC\\s*NO|CERT\\s*NO|CERTIFICATE\\s*NO|LICENCE\\s*NO|ID\\s*NO|NUMBER)[\\s.:-]*([A-Z0-9/-]{6,20})")
            .find(upperText)
        if (genericNo != null) return genericNo.groupValues[1]
        val alphanumericToken = Regex("\\b([A-Z0-9]{8,15})\\b").findAll(upperText)
            .map { it.value }
            .firstOrNull { token -> token.any { it.isDigit() } && token.any { it.isLetter() } }
        return alphanumericToken ?: "UNPARSED-NO"
    }

    private data class MrzData(
        val fullName: String,
        val passportNo: String,
        val dob: String?,
        val expiryDate: String?
    )

    private fun parsePassportMrzIfPresent(lines: List<String>): MrzData? {
        val cleanLines = lines.map { it.replace("\\s+".toRegex(), "").uppercase() }
        val line1Idx = cleanLines.indexOfFirst { it.startsWith("P<IND") && it.length >= 30 }
        if (line1Idx == -1) return null

        val line1 = cleanLines[line1Idx]
        val nameSection = line1.substring(5).substringBefore("<<<")
        val parts = nameSection.split("<<")
        val surname = parts.getOrNull(0)?.replace('<', ' ')?.trim().orEmpty()
        val givenName = parts.getOrNull(1)?.replace('<', ' ')?.trim().orEmpty()
        val fullName = "$givenName $surname".trim().ifEmpty { "Unknown Member" }

        val line2 = cleanLines.getOrNull(line1Idx + 1)
        if (line2 != null && line2.length >= 28) {
            val rawPassNo = line2.substring(0, 9).replace("<", "")
            val rawDob = line2.substring(13, 19)
            val rawExp = line2.substring(21, 27)
            return MrzData(
                fullName = fullName,
                passportNo = rawPassNo,
                dob = parseMrzSixDigitDate(rawDob, isExpiry = false),
                expiryDate = parseMrzSixDigitDate(rawExp, isExpiry = true)
            )
        }
        return MrzData(fullName = fullName, passportNo = "UNPARSED", dob = null, expiryDate = null)
    }

    private fun parseMrzSixDigitDate(yymmdd: String, isExpiry: Boolean): String? {
        val clean = yymmdd.map { letterToDigitConfusable(it) }.joinToString("")
        if (clean.length != 6 || !clean.all { it.isDigit() }) return null
        val yy = clean.substring(0, 2).toInt()
        val mm = clean.substring(2, 4)
        val dd = clean.substring(4, 6)
        val fullYear = if (isExpiry) {
            if (yy <= 65) 2000 + yy else 1900 + yy
        } else {
            if (yy <= 26) 2000 + yy else 1900 + yy
        }
        return "$fullYear-$mm-$dd"
    }

    /**
     * Multi-Strategy High-Precision Person Name Extractor.
     */
    private fun extractHighPrecisionPersonName(lines: List<String>, subType: String): String {
        // 1. Check QR Verified Name first
        for (line in lines) {
            val qrMatch = Regex("^QR_NAME\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE).find(line)
            if (qrMatch != null) {
                val cleaned = sanitizeExtractedName(qrMatch.groupValues[1])
                if (isValidHumanName(cleaned)) return cleaned
            }
        }

        // 2. Check Explicit Spatial/Horizontal Label Pairs (excluding Father/Mother/Spouse labels)
        val primaryNameRegex = Regex(
            "^(?!.*(?:FATHER|MOTHER|HUSBAND|WIFE|SPOUSE|GUARDIAN|BANK|BRANCH|AUTHORITY))(?:CANDIDATE'?S?\\s*NAME|STUDENT'?S?\\s*NAME|ACCOUNT\\s*HOLDER(?:\\s*NAME)?|ELECTOR'?S?\\s*NAME|APPLICANT'?S?\\s*NAME|OWNER'?S?\\s*NAME|FULL\\s*NAME|GIVEN\\s*NAME|NAME)\\s*[:.;-]+\\s*(.+)$",
            RegexOption.IGNORE_CASE
        )
        for (line in lines) {
            val match = primaryNameRegex.find(line)
            if (match != null) {
                val rawValue = match.groupValues[1].split("   ").first()
                val cleaned = sanitizeExtractedName(rawValue)
                if (isValidHumanName(cleaned)) return cleaned
            }
        }

        // 3. Spatial Line-Above Anchor (Standard on PAN, National ID, Driving License)
        for (i in lines.indices) {
            val upper = lines[i].uppercase()
            if ((upper.contains("FATHER") || upper.startsWith("S/O") || upper.startsWith("D/O") || upper.startsWith("W/O")) && i >= 1) {
                for (back in 1..2) {
                    if (i - back >= 0) {
                        val candidate = sanitizeExtractedName(lines[i - back])
                        if (isValidHumanName(candidate)) return candidate
                    }
                }
            }
            if ((upper.contains("DOB") || upper.contains("DATE OF BIRTH") || upper.contains("YEAR OF BIRTH")) && i >= 1) {
                for (back in 1..2) {
                    if (i - back >= 0) {
                        val prev = lines[i - back]
                        if (!prev.uppercase().contains("FATHER") && !prev.uppercase().contains("ADDRESS")) {
                            val candidate = sanitizeExtractedName(prev)
                            if (isValidHumanName(candidate)) return candidate
                        }
                    }
                }
            }
        }

        // 4. Fallback: Find the cleanest 2-to-4 word uppercase alphabetical line that contains zero noise headers
        for (line in lines) {
            val candidate = sanitizeExtractedName(line)
            val words = candidate.split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (words.size in 2..4 && isValidHumanName(candidate)) {
                return candidate
            }
        }

        return "Unknown Member"
    }

    private fun sanitizeExtractedName(raw: String): String {
        return raw
            .replace(Regex("^(?:NAME|MR|MRS|MS|SRI|SMT|SHRI|KUMARI|DR)\\.?\\s*[:.-]?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[^A-Za-z.\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isValidHumanName(candidate: String): Boolean {
        if (candidate.length < 3 || candidate.length > 45) return false
        val words = candidate.uppercase().replace(".", "").split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 5) return false
        if (words.any { it in NOISE_HEADER_WORDS }) return false
        val longWords = words.filter { it.length >= 3 }
        if (longWords.isEmpty()) return false
        // Reject garbled consonant clusters (every real name word has at least one vowel A, E, I, O, U, Y)
        val vowels = setOf('A', 'E', 'I', 'O', 'U', 'Y')
        if (longWords.any { w -> w.none { c -> c in vowels } }) return false
        return true
    }

    /**
     * Multi-Format Date Extractor: Separates Issue Date, Expiry/Validity Date, and DOB.
     */
    private fun extractAllDocumentDates(
        lines: List<String>,
        mrzDob: String?,
        mrzExpiry: String?
    ): Triple<String?, String?, String?> {
        var issueDate: String? = null
        var expiryDate: String? = mrzExpiry
        var dob: String? = mrzDob

        val numericDateRegex = Regex("\\b([0-3][0-9O])[/.\\s-]([0-1][0-9O])[/.\\s-](19[0-9]{2}|20[0-9]{2})\\b")
        val textMonthDateRegex = Regex(
            "\\b([0-3]?[0-9])[-\\s/]+(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*[-\\s/,]+(19[0-9]{2}|20[0-9]{2})\\b",
            RegexOption.IGNORE_CASE
        )

        val allChronologicalDates = mutableListOf<Pair<Int, String>>()

        for (i in lines.indices) {
            val line = lines[i]
            val upper = line.uppercase()
            val prevUpper = if (i > 0) lines[i - 1].uppercase() else ""
            val contextUpper = "$prevUpper $upper"

            if (upper.startsWith("QR_DOB")) {
                val rawQrDob = line.substringAfter(":").trim()
                dob = rawQrDob
                continue
            }

            val lineDates = mutableListOf<Pair<Int, String>>()

            numericDateRegex.findAll(line).forEach { m ->
                val dd = m.groupValues[1].replace('O', '0')
                val mm = m.groupValues[2].replace('O', '0')
                val yyyy = m.groupValues[3]
                val yearInt = yyyy.toIntOrNull() ?: 0
                val monthInt = mm.toIntOrNull() ?: 0
                val dayInt = dd.toIntOrNull() ?: 0
                if (yearInt in 1920..2065 && monthInt in 1..12 && dayInt in 1..31) {
                    val iso = "$yyyy-${mm.padStart(2, '0')}-${dd.padStart(2, '0')}"
                    lineDates.add(yearInt to iso)
                    allChronologicalDates.add(yearInt to iso)
                }
            }

            textMonthDateRegex.findAll(line).forEach { m ->
                val dd = m.groupValues[1].padStart(2, '0')
                val mon = MONTH_MAP[m.groupValues[2].uppercase().take(3)] ?: "01"
                val yyyy = m.groupValues[3]
                val yearInt = yyyy.toIntOrNull() ?: 0
                if (yearInt in 1920..2065) {
                    val iso = "$yyyy-$mon-$dd"
                    lineDates.add(yearInt to iso)
                    allChronologicalDates.add(yearInt to iso)
                }
            }

            if (lineDates.isEmpty()) continue

            when {
                contextUpper.contains("EXPIR") || contextUpper.contains("VALID TILL") ||
                    contextUpper.contains("VALID UPTO") || contextUpper.contains("VALIDITY") ||
                    contextUpper.contains("VALI") || contextUpper.contains("NT :") || contextUpper.contains("TR :") -> {
                    expiryDate = lineDates.maxByOrNull { it.first }?.second
                }
                contextUpper.contains("DOB") || contextUpper.contains("BIRTH") -> {
                    dob = lineDates.minByOrNull { it.first }?.second
                }
                contextUpper.contains("ISSUE") || contextUpper.contains("DOI") || contextUpper.contains("REGN DATE") -> {
                    issueDate = lineDates.first().second
                    if (lineDates.size >= 2 && expiryDate == null) {
                        expiryDate = lineDates.maxByOrNull { it.first }?.second
                    }
                }
            }
        }

        // Smart Chronological Fallback: Any date strictly in the future (> 2026) is an Expiry Date
        if (expiryDate == null) {
            expiryDate = allChronologicalDates.filter { it.first >= 2027 }.maxByOrNull { it.first }?.second
        }
        if (dob == null) {
            dob = allChronologicalDates.filter { it.first in 1930..2012 }.minByOrNull { it.first }?.second
        }
        if (issueDate == null) {
            issueDate = allChronologicalDates
                .map { it.second }
                .firstOrNull { it != dob && it != expiryDate }
        }

        return Triple(issueDate, expiryDate, dob)
    }

    private fun buildExtraAttributesJson(
        upperText: String,
        lines: List<String>,
        subType: String,
        dob: String?,
        ifsc: String?
    ): String {
        val json = JSONObject()
        if (dob != null) json.put("dob", dob)
        if (ifsc != null) json.put("ifsc", ifsc)

        for (line in lines) {
            val fatherMatch = Regex(
                "^(?:FATHER'?S?\\s*NAME|MOTHER'?S?\\s*NAME|HUSBAND'?S?\\s*NAME|CARE\\s*OF|S/O|D/O|W/O)\\s*[:.-]+\\s*(.+)$",
                RegexOption.IGNORE_CASE
            ).find(line)
            if (fatherMatch != null) {
                val cleaned = sanitizeExtractedName(fatherMatch.groupValues[1].split("   ").first())
                if (cleaned.length >= 3) json.put("relation_name", cleaned)
            }

            val bloodMatch = Regex("(?:BLOOD\\s*GROUP|B\\.?G\\.?)\\s*[:.-]*\\s*((?:A|B|AB|O)[+-])", RegexOption.IGNORE_CASE).find(line)
            if (bloodMatch != null) {
                json.put("blood_group", bloodMatch.groupValues[1].uppercase())
            }

            val pctMatch = Regex("(?:PERCENTAGE|RESULT|CGPA|TOTAL\\s*MARKS)\\s*[:.-]+\\s*([0-9.]+%?)", RegexOption.IGNORE_CASE).find(line)
            if (pctMatch != null) {
                json.put("score", pctMatch.groupValues[1].trim())
            }
        }

        PINCODE_REGEX.find(upperText)?.value?.let { json.put("pincode", it) }
        json.put("parsed_sub_type", subType)
        return json.toString()
    }

    private fun detectBankAuthority(upperText: String): String {
        return when {
            upperText.contains("STATE BANK OF INDIA") || upperText.contains("SBIN") -> "State Bank of India (SBI)"
            upperText.contains("CANARA BANK") || upperText.contains("CNRB") -> "Canara Bank"
            upperText.contains("HDFC BANK") || upperText.contains("HDFC") -> "HDFC Bank"
            upperText.contains("ICICI BANK") || upperText.contains("ICIC") -> "ICICI Bank"
            upperText.contains("UNION BANK") || upperText.contains("UBIN") -> "Union Bank of India"
            upperText.contains("BANK OF BARODA") || upperText.contains("BARB") -> "Bank of Baroda"
            upperText.contains("PUNJAB NATIONAL") || upperText.contains("PUNB") -> "Punjab National Bank"
            upperText.contains("KARNATAKA VIKAS") || upperText.contains("KVGB") -> "Karnataka Vikas Grameena Bank"
            upperText.contains("AXIS BANK") || upperText.contains("UTIB") -> "Axis Bank"
            else -> "Banking Institution"
        }
    }

    private fun digitToLetterConfusable(c: Char): Char = when (c) {
        '0' -> 'O'
        '1' -> 'I'
        '2' -> 'Z'
        '5' -> 'S'
        '8' -> 'B'
        '6' -> 'G'
        else -> c
    }

    private fun letterToDigitConfusable(c: Char): Char = when (c) {
        'O', 'Q', 'D' -> '0'
        'I', 'L' -> '1'
        'Z' -> '2'
        'S' -> '5'
        'B' -> '8'
        'G' -> '6'
        else -> c
    }

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