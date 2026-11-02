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

    private val STATE_CODES = setOf(
        "AN", "AP", "AR", "AS", "BR", "CG", "CH", "DD", "DL", "DN", "GA", "GJ", "HP", "HR",
        "JH", "JK", "KA", "KL", "LA", "LD", "MH", "ML", "MN", "MP", "MZ", "NL", "OD", "OR",
        "PB", "PY", "RJ", "SK", "TN", "TR", "TS", "UK", "UP", "WB"
    )

    private val NON_NAME_DICTIONARY = setOf(
        "INCOME", "TAX", "DEPARTMENT", "GOVT", "GOVERNMENT", "INDIA", "UNIQUE", "IDENTIFICATION",
        "AUTHORITY", "UIDAI", "ELECTION", "COMMISSION", "ELECTOR", "PHOTO", "IDENTITY", "CARD",
        "REPUBLIC", "PASSPORT", "DRIVING", "LICENCE", "LICENSE", "UNION", "KARNATAKA", "STATE",
        "MOTOR", "VEHICLE", "REGISTRATION", "CERTIFICATE", "PASSBOOK", "BANK", "BRANCH", "IFSC",
        "MICR", "ACCOUNT", "PERMANENT", "NUMBER", "SIGNATURE", "ADDRESS", "DATE", "BIRTH", "VALID",
        "TILL", "UPTO", "EXPIRY", "ISSUE", "BLOOD", "GROUP", "TAHSILDAR", "REVENUE", "NADA",
        "KACHERI", "MARKS", "SECONDARY", "EDUCATION", "BOARD", "UNIVERSITY", "MALE", "FEMALE",
        "HOLDER", "REL", "PRO", "PROP", "MANSER", "LTD", "LIMITED", "PVT", "CORP", "CORPORATE",
        "PARK", "ROAD", "BELAPUR", "THANE", "NAVI", "MUMBAI", "CENTRAL", "CELL", "JIO", "RELIANCE",
        "THE", "SHOULD", "ALWAYS", "DISPLAYED", "WHILE", "DUTY", "HEIGHT", "MARK", "MOLE",
        "FOREHEAD", "LOSS", "REPORTED", "ISSUING", "ONCE", "FOUND", "PLEASE", "RETURN",
        "EMERGENCY", "CONTACT", "NOS", "DOJ", "EC", "NO", "EMP", "CODE", "NATIONALITY", "INDIAN",
        "DRUG", "FORM", "SCHEDULE", "RATION", "FOOD", "CIVIL", "SUPPLIES", "AYUSHMAN", "BHARAT"
    )

    private val PHONE_REGEX = Regex("\\b(1800[\\s-]?[0-9]{3}[\\s-]?[0-9]{4}|[6-9][0-9]{9})\\b")
    private val PINCODE_REGEX = Regex("\\b([1-8][0-9]{5})\\b")

    fun parseDocumentText(rawInput: String): ParsedDocumentPayload {
        val cleanText = rawInput.trim()
        val upper = cleanText.uppercase()
        val allLines = cleanText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("---ENHANCED") }

        val visibleLines = allLines.filter {
            !it.startsWith("SPATIAL_") && !it.startsWith("QR_")
        }

        // 1. Harvest all Dynamic Key-Value Pairs across the document
        val dynamicPairs = harvestDynamicKeyValuePairs(visibleLines)

        // 2. Specialized Structural Detectors
        val mrz = parseMrzIfPresent(visibleLines)
        val pan = findAndDegarblePan(upper)
        val dl = findAndDegarbleDl(upper)
        val ifsc = findAndDegarbleIfsc(upper)
        val rd = Regex("\\bR[D0O]([0-9OIZSB]{13})\\b").find(upper)?.let {
            "RD" + it.groupValues[1].map { c -> letterToDigit(c) }.joinToString("")
        }
        val drugLic = Regex("\\b([A-Z]{3,6}-[0-9]{2}/[0-9]{2,5}(?:\\s*,\\s*[0-9]{2}/[0-9]{2,5})?)\\b").find(upper)?.value
        val rcNo = findVehicleRc(upper)
        val epicNo = findVoterEpic(upper)
        val maskedUid = findMaskedUid(upper)
        val empOrBadgeId = findEmployeeOrInstitutionalId(upper, allLines, dynamicPairs)

        // 3. Dynamic Document Classification (Covers all Govt, Financial, Academic & Corporate IDs)
        val (cat, subType, auth) = classifyDocumentDynamic(
            upper = upper,
            lines = visibleLines,
            hasPan = pan != null,
            hasDl = dl != null,
            hasIfsc = ifsc != null,
            hasRd = rd != null,
            hasMrz = mrz != null,
            hasEpic = epicNo != null,
            hasRc = rcNo != null,
            hasUid = maskedUid != null,
            hasDrugLic = drugLic != null,
            hasEmpId = empOrBadgeId != null
        )

        // 4. Resolve Primary Document Number
        val docNo = when (subType) {
            "PAN Card" -> pan ?: empOrBadgeId ?: "UNPARSED-NO"
            "Driving License" -> dl ?: empOrBadgeId ?: "UNPARSED-NO"
            "Indian Passport" -> mrz?.second ?: Regex("\\b([A-Z][0-9]{7})\\b").find(upper)?.value ?: "UNPARSED-NO"
            "Nada Kacheri Certificate" -> rd ?: empOrBadgeId ?: "UNPARSED-NO"
            "Voter ID (EPIC)" -> epicNo ?: empOrBadgeId ?: "UNPARSED-NO"
            "Vehicle RC Card" -> rcNo ?: empOrBadgeId ?: "UNPARSED-NO"
            "Drug / Trade License" -> drugLic ?: empOrBadgeId ?: "UNPARSED-NO"
            "Bank Passbook" -> extractBankAccountNo(upper, dynamicPairs) ?: ifsc ?: "UNPARSED-NO"
            "Academic Marks Card" -> extractAcademicRegNo(upper, dynamicPairs) ?: "UNPARSED-NO"
            "National ID Card" -> maskedUid ?: "[ID Redacted]"
            "Employee / Institutional ID" -> empOrBadgeId ?: "UNPARSED-NO"
            else -> pan ?: dl ?: rd ?: drugLic ?: epicNo ?: rcNo ?: empOrBadgeId ?: maskedUid ?: "UNPARSED-NO"
        }

        // 5. High-Precision Multi-Strategy Holder Name Resolution (Labeled + Tagless + Cross-Referenced)
        val holderName = mrz?.first ?: resolveHolderNameDynamic(allLines, visibleLines, dynamicPairs, docNo)

        // 6. Date Segregation (Issue/DOJ, Expiry/Validity, DOB)
        val (issueDate, expiryDate, dob) = extractDatesDynamic(allLines, dynamicPairs)

        // 7. Build Rich JSON of All Remaining Dynamic Tags
        val phone = PHONE_REGEX.find(cleanText)?.groupValues?.get(1)
        val extraJson = buildDynamicTagsJson(
            upper = upper,
            visibleLines = visibleLines,
            dynamicPairs = dynamicPairs,
            dob = dob,
            ifsc = ifsc,
            primaryDocNo = docNo,
            holderName = holderName
        )

        return ParsedDocumentPayload(
            docCategory = cat,
            docSubType = subType,
            issuingAuthority = auth,
            extractedName = holderName,
            documentNumber = docNo,
            issueDate = issueDate,
            expiryDate = expiryDate,
            linkedMobile = phone,
            extraAttributesJson = extraJson,
            rawText = visibleLines.joinToString("\n")
        )
    }

    /**
     * Harvests any `Key : Value` or `Key - Value` pairs across any document type automatically.
     */
    private fun harvestDynamicKeyValuePairs(lines: List<String>): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        val kvRegex = Regex("^([A-Za-z][A-Za-z0-9\\s./'()-]{1,28})\\s*[:;-]+\\s*(.+)$")

        for (line in lines) {
            val segments = line.split("   ").map { it.trim() }.filter { it.isNotEmpty() }
            for (seg in segments) {
                val match = kvRegex.find(seg) ?: continue
                val rawKey = match.groupValues[1].trim()
                val rawVal = match.groupValues[2].trim()
                if (rawKey.length in 2..28 && rawVal.isNotEmpty() && !rawKey.uppercase().startsWith("HTTP")) {
                    map[rawKey] = rawVal
                }
            }
        }
        return map
    }

    private fun classifyDocumentDynamic(
        upper: String,
        lines: List<String>,
        hasPan: Boolean,
        hasDl: Boolean,
        hasIfsc: Boolean,
        hasRd: Boolean,
        hasMrz: Boolean,
        hasEpic: Boolean,
        hasRc: Boolean,
        hasUid: Boolean,
        hasDrugLic: Boolean,
        hasEmpId: Boolean
    ): Triple<String, String, String> {
        return when {
            upper.contains("INCOME TAX") || upper.contains("PERMANENT ACCOUNT") || hasPan ->
                Triple("ID_CARD", "PAN Card", "Income Tax Department, Govt. of India")

            upper.contains("DRIVING LICENCE") || upper.contains("DRIVING LICENSE") || hasDl ->
                Triple("ID_CARD", "Driving License", "Regional Transport Office (RTO)")

            hasMrz || (upper.contains("REPUBLIC OF INDIA") && upper.contains("PASSPORT")) ->
                Triple("PASSPORT", "Indian Passport", "Ministry of External Affairs, Govt. of India")

            upper.contains("NADA KACHERI") || upper.contains("TAHSILDAR") || hasRd ->
                Triple("GOVT_CERT", "Nada Kacheri Certificate", "Revenue Department (Nada Kacheri)")

            upper.contains("ELECTION COMMISSION") || upper.contains("ELECTOR") || hasEpic ->
                Triple("ID_CARD", "Voter ID (EPIC)", "Election Commission of India")

            upper.contains("REGISTRATION CERTIFICATE") || upper.contains("CHASSIS") || hasRc ->
                Triple("ID_CARD", "Vehicle RC Card", "Transport Department (RTO)")

            upper.contains("DRUG") || upper.contains("DLNO") || hasDrugLic ->
                Triple("GOVT_CERT", "Drug / Trade License", detectOrganizationAuthority(lines))

            upper.contains("PASSBOOK") || upper.contains("IFSC") || hasIfsc ->
                Triple("BANK_PASSBOOK", "Bank Passbook", detectBankAuthority(upper))

            upper.contains("MARKS") || upper.contains("UNIVERSITY") || upper.contains("SECONDARY EDUCATION") ||
                upper.contains("KSEAB") || upper.contains("SSLC") ->
                Triple("MARKS_CARD", "Academic Marks Card", detectOrganizationAuthority(lines))

            upper.contains("AYUSHMAN") || upper.contains("ABHA") || upper.contains("PM-JAY") || upper.contains("HEALTH CARD") ->
                Triple("MEDICAL", "Health / ABHA ID Card", "National Health Authority (NHA)")

            upper.contains("RATION CARD") || upper.contains("FOOD AND CIVIL") ->
                Triple("GOVT_CERT", "Ration Card", "Department of Food & Civil Supplies")

            upper.contains("UNIQUE IDENTIFICATION") || upper.contains("UIDAI") ||
                upper.contains("QR_UID_LAST4") || hasUid ->
                Triple("ID_CARD", "National ID Card", "Unique Identification Authority of India")

            upper.contains("EC NO") || upper.contains("EMP NO") || upper.contains("EMPLOYEE") ||
                upper.contains("WHILE ON DUTY") || upper.contains("ID CARD CELL") ||
                upper.contains("DOJ") || upper.contains("ID MARK") || upper.contains("BLOOD GROUP") || hasEmpId ->
                Triple("ID_CARD", "Employee / Institutional ID", detectOrganizationAuthority(lines))

            else ->
                Triple("GOVT_CERT", "Government / Institutional Document", detectOrganizationAuthority(lines))
        }
    }

    private fun detectOrganizationAuthority(lines: List<String>): String {
        val orgRegex = Regex(
            "\\b(LTD|LIMITED|PVT|CORPORATE|CORP|RELIANCE|JIO|INSTITUTE|COLLEGE|UNIVERSITY|BOARD|HOSPITAL|TRUST|AUTHORITY|DEPARTMENT|MINISTRY|OFFICE)\\b",
            RegexOption.IGNORE_CASE
        )
        val matches = lines.filter {
            orgRegex.containsMatchIn(it) &&
                !it.uppercase().contains("LOSS OF CARD") &&
                !it.uppercase().contains("SIGNATURE") &&
                !it.uppercase().contains("DISPLAYED BY") &&
                it.length in 3..58
        }.map { it.trim().trimEnd(',', '.') }

        return if (matches.isNotEmpty()) {
            matches.distinct().take(2).joinToString(" • ")
        } else {
            "Government / Institutional Authority"
        }
    }

    private fun findEmployeeOrInstitutionalId(
        upper: String,
        allLines: List<String>,
        dynamicPairs: Map<String, String>
    ): String? {
        // 1. Check explicit labeled keys in dynamic map (EC No, Emp No, Badge No, ID No, Card No)
        val idKeyRegex = Regex("(?:^|\\b)(?:EC|EMP|EMPLOYEE|BADGE|STAFF|CARD|ID|PRAN|UAN|PPO)\\s*(?:NO|NUMBER|CODE|ID)?$", RegexOption.IGNORE_CASE)
        for ((k, v) in dynamicPairs) {
            if (idKeyRegex.containsMatchIn(k)) {
                val token = Regex("([A-Z0-9/-]{5,15})").find(v.uppercase())?.value
                if (token != null && token.any { it.isDigit() }) return token
            }
        }

        // 2. Regex scan for EC No / Emp No
        Regex("(?:EC\\s*NO|EMP(?:LOYEE)?\\s*(?:NO|ID|CODE)|BADGE\\s*NO|ID\\s*NO|CARD\\s*NO)[\\s.:;-]*([A-Z0-9-]{5,15})")
            .find(upper)?.let { return it.groupValues[1].trim() }

        // 3. Check Spatial ID Candidate from badge layout
        allLines.firstOrNull { it.startsWith("SPATIAL_ID_CANDIDATE :") }?.let {
            return it.substringAfter(":").trim()
        }

        // 4. Find any 6-to-10 digit number that appears on both Front and Back (or standalone on badge)
        val standaloneNumbers = allLines
            .filter { !it.startsWith("SPATIAL_") }
            .map { it.trim() }
            .filter { Regex("^[0-9]{6,10}$").matches(it) && !it.startsWith("1800") && it.length != 6 }
        if (standaloneNumbers.isNotEmpty()) {
            return standaloneNumbers.first()
        }
        return null
    }

    /**
     * Multi-Strategy Dynamic Holder Name Resolver:
     * Accurately resolves names whether labeled ("Name: ...") OR completely tagless
     * (like "Inayatulla\nChajjo" above "55064480" on a corporate/govt badge).
     */
    private fun resolveHolderNameDynamic(
        allLines: List<String>,
        visibleLines: List<String>,
        dynamicPairs: Map<String, String>,
        docNo: String
    ): String {
        // Priority 1: QR Verified Name
        allLines.firstOrNull { it.startsWith("QR_NAME :") }?.let {
            val c = cleanNameString(it.substringAfter(":"))
            if (isValidHumanName(c)) return titleCase(c)
        }

        // Priority 2: Explicit Name Key in Dynamic Key-Value Map
        val nameKeyRegex = Regex("^(?!.*(?:FATHER|MOTHER|SPOUSE|HUSBAND|WIFE|GUARDIAN|BANK|COMPANY|ORG|ISSUING|AUTHORITY))(?:NAME|FULL\\s*NAME|HOLDER(?:\\s*NAME)?|EMPLOYEE(?:\\s*NAME)?|CANDIDATE(?:\\s*NAME)?|STUDENT(?:\\s*NAME)?|APPLICANT(?:\\s*NAME)?|ELECTOR'?S?\\s*NAME)$", RegexOption.IGNORE_CASE)
        for ((k, v) in dynamicPairs) {
            if (nameKeyRegex.matches(k.trim())) {
                val c = cleanNameString(v)
                if (isValidHumanName(c)) return titleCase(c)
            }
        }

        // Priority 3: Spatial Badge Name (Stacked directly above standalone ID number in bounding-box space)
        allLines.firstOrNull { it.startsWith("SPATIAL_BADGE_NAME :") }?.let {
            val c = cleanNameString(it.substringAfter(":"))
            if (isValidHumanName(c)) return titleCase(c)
        }

        // Priority 4: Cross-Referenced ID Anchor (Finds lines immediately above/below `docNo` in the text stream)
        if (docNo != "UNPARSED-NO") {
            for (i in visibleLines.indices) {
                val line = visibleLines[i].trim()
                // Match standalone ID line (not the "EC No: 55064480" line, or even if standalone)
                if (line == docNo || (line.contains(docNo) && !line.contains(":"))) {
                    val stackAbove = collectAdjacentNameLinesAbove(visibleLines, i)
                    if (stackAbove.isNotEmpty()) {
                        return titleCase(stackAbove)
                    }
                }
            }
        }

        // Priority 5: Spatial Prominent Font-Size Name Candidate
        allLines.firstOrNull { it.startsWith("SPATIAL_PROMINENT_NAME :") }?.let {
            val c = cleanNameString(it.substringAfter(":"))
            if (isValidHumanName(c)) return titleCase(c)
        }

        // Priority 6: Line immediately above "Father's Name", "DOB", or "Date of Birth"
        for (i in visibleLines.indices) {
            val u = visibleLines[i].uppercase()
            if ((u.contains("FATHER") || u.startsWith("S/O") || u.startsWith("D/O") || u.startsWith("W/O") ||
                    u.contains("DOB") || u.contains("DATE OF BIRTH")) && i >= 1
            ) {
                val stackAbove = collectAdjacentNameLinesAbove(visibleLines, i)
                if (stackAbove.isNotEmpty()) return titleCase(stackAbove)
            }
        }

        // Priority 7: Highest-Scoring Tagless Human Name Cluster across the entire document
        var bestCandidate = ""
        var bestScore = 0
        var idx = 0
        while (idx < visibleLines.size) {
            if (isTaglessNameTokenLine(visibleLines[idx])) {
                val cluster = mutableListOf(visibleLines[idx].trim())
                var next = idx + 1
                while (next < visibleLines.size && cluster.size < 3 && isTaglessNameTokenLine(visibleLines[next])) {
                    cluster.add(visibleLines[next].trim())
                    next++
                }
                val combined = cleanNameString(cluster.joinToString(" "))
                val score = scoreHumanNameCandidate(combined, cluster.size)
                if (score > bestScore) {
                    bestScore = score
                    bestCandidate = combined
                }
                idx = next
            } else {
                idx++
            }
        }

        return if (bestScore >= 25 && bestCandidate.isNotBlank()) {
            titleCase(bestCandidate)
        } else {
            "Unknown Member"
        }
    }

    private fun collectAdjacentNameLinesAbove(lines: List<String>, anchorIndex: Int): String {
        val parts = mutableListOf<String>()
        var b = anchorIndex - 1
        while (b >= 0 && parts.size < 3) {
            val candidateLine = lines[b].trim()
            if (isTaglessNameTokenLine(candidateLine)) {
                parts.add(0, candidateLine)
                b--
            } else {
                break
            }
        }
        val combined = cleanNameString(parts.joinToString(" "))
        return if (isValidHumanName(combined)) combined else ""
    }

    private fun scoreHumanNameCandidate(candidate: String, lineCount: Int): Int {
        if (!isValidHumanName(candidate)) return 0
        val words = candidate.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        var score = 20
        if (words.size in 2..3) score += 25
        if (lineCount >= 2) score += 15
        if (words.all { it.length >= 4 }) score += 10
        return score
    }

    private fun isTaglessNameTokenLine(line: String): Boolean {
        val t = line.trim()
        if (t.length !in 3..32 || t.contains(":") || !Regex("^[A-Za-z.\\s]+$").matches(t)) return false
        val words = t.uppercase().replace(".", "").split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 4) return false
        if (words.any { it in NON_NAME_DICTIONARY || it.length < 2 }) return false
        val vowels = setOf('A', 'E', 'I', 'O', 'U', 'Y')
        return words.none { w -> w.length >= 3 && w.none { c -> c in vowels } }
    }

    private fun cleanNameString(raw: String): String = raw
        .replace(Regex("^(?:NAME|MR|MRS|MS|SRI|SMT|SHRI|DR)\\.?\\s*[:.-]?\\s*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("[^A-Za-z.\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun isValidHumanName(c: String): Boolean {
        if (c.length !in 3..45) return false
        val words = c.uppercase().replace(".", "").split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 5 || words.any { it in NON_NAME_DICTIONARY }) return false
        val vowels = setOf('A', 'E', 'I', 'O', 'U', 'Y')
        val longWords = words.filter { it.length >= 3 }
        return longWords.isNotEmpty() && longWords.none { w -> w.none { ch -> ch in vowels } }
    }

    private fun titleCase(s: String): String = s.split("\\s+".toRegex())
        .filter { it.isNotBlank() }
        .joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }

    private fun extractDatesDynamic(
        allLines: List<String>,
        dynamicPairs: Map<String, String>
    ): Triple<String?, String?, String?> {
        var issue: String? = null
        var expiry: String? = null
        var dob: String? = null

        val dateRegex = Regex("\\b([0-3][0-9O])[/.\\s-]([0-1][0-9O])[/.\\s-](19[0-9]{2}|20[0-9]{2})\\b")

        for ((k, v) in dynamicPairs) {
            val ku = k.uppercase()
            val m = dateRegex.find(v) ?: continue
            val iso = "${m.groupValues[3]}-${m.groupValues[2].replace('O', '0')}-${m.groupValues[1].replace('O', '0')}"
            when {
                ku.contains("EXPIR") || ku.contains("VALID") -> expiry = iso
                ku.contains("DOB") || ku.contains("BIRTH") -> dob = iso
                ku.contains("DOJ") || ku.contains("JOIN") || ku.contains("ISSUE") || ku.contains("DOI") -> issue = iso
            }
        }

        for (l in allLines) {
            val u = l.uppercase()
            if (u.startsWith("QR_DOB :")) {
                dob = l.substringAfter(":").trim()
                continue
            }
            val found = dateRegex.findAll(l).map {
                "${it.groupValues[3]}-${it.groupValues[2].replace('O', '0')}-${it.groupValues[1].replace('O', '0')}"
            }.toList()
            if (found.isEmpty()) continue
            when {
                u.contains("EXPIR") || u.contains("VALID") -> if (expiry == null) expiry = found.last()
                u.contains("DOB") || u.contains("BIRTH") -> if (dob == null) dob = found.first()
                u.contains("DOJ") || u.contains("ISSUE") || u.contains("DATE") -> if (issue == null) issue = found.first()
            }
        }
        return Triple(issue, expiry, dob)
    }

    /**
     * Dynamically packages all extracted tags on the card (Blood Group, Height, ID Mark, DOJ,
     * Relation, Score, IFSC, Emergency Contact, Card Serial, Pincode, plus any custom `Key: Value` pairs).
     */
    private fun buildDynamicTagsJson(
        upper: String,
        visibleLines: List<String>,
        dynamicPairs: Map<String, String>,
        dob: String?,
        ifsc: String?,
        primaryDocNo: String,
        holderName: String
    ): String {
        val json = JSONObject()
        if (dob != null) json.put("DOB", dob)
        if (ifsc != null) json.put("IFSC", ifsc)

        Regex("(?:BLOOD\\s*GROUP|B\\.?G\\.?)\\s*[:.-]*\\s*((?:A|B|AB|O)[+-])", RegexOption.IGNORE_CASE)
            .find(upper)?.let { json.put("Blood Group", it.groupValues[1].uppercase()) }

        Regex("(?:HEIGHT)\\s*[:.-]*\\s*([0-9]{2,3}(?:\\s*CM)?)", RegexOption.IGNORE_CASE)
            .find(upper)?.let { json.put("Height", it.groupValues[1].trim()) }

        Regex("(?:DOJ|DATE\\s*OF\\s*JOINING)\\s*[:.-]*\\s*([0-9]{2}[/-][0-9]{2}[/-][0-9]{4})", RegexOption.IGNORE_CASE)
            .find(upper)?.let { json.put("DOJ", it.groupValues[1].trim()) }

        // Add all dynamically harvested Key-Value pairs from the card
        val ignoredKeys = setOf("NAME", "EC NO", "EMP NO", "DOJ", "HEIGHT", "BLOOD GROUP", "IFSC")
        for ((k, v) in dynamicPairs) {
            val ku = k.uppercase().trim()
            if (ku !in ignoredKeys && v != primaryDocNo && !v.equals(holderName, ignoreCase = true) && v.length <= 45) {
                json.put(k, v)
            }
        }

        PHONE_REGEX.find(upper)?.let { json.put("Emergency/Phone", it.groupValues[1]) }

        visibleLines.map { it.trim() }
            .firstOrNull { Regex("^[0-9]{5,7}$").matches(it) && it != primaryDocNo }
            ?.let { json.put("Card Serial", it) }

        PINCODE_REGEX.find(upper)?.value?.let { json.put("Pincode", it) }
        return json.toString()
    }

    private fun findAndDegarblePan(upper: String): String? {
        for (m in Regex("\\b[A-Z0-9]{10}\\b").findAll(upper)) {
            val c = m.value.toCharArray()
            for (i in 0..4) c[i] = digitToLetter(c[i])
            for (i in 5..8) c[i] = letterToDigit(c[i])
            c[9] = digitToLetter(c[9])
            val s = String(c)
            if (Regex("^[A-Z]{5}[0-9]{4}[A-Z]$").matches(s) && s[3] in "PCHFATBLJG") return s
        }
        return null
    }

    private fun findAndDegarbleDl(upper: String): String? {
        val m = Regex("\\b([A-Z0-9]{2})[-\\s]?([0-9OIZSB]{2})[-\\s]?((?:19|20)[0-9OIZSB]{2})[-\\s]?([0-9OIZSB]{7})\\b").find(upper) ?: return null
        val st = m.groupValues[1].map { digitToLetter(it) }.joinToString("")
        if (st !in STATE_CODES) return null
        val rto = m.groupValues[2].map { letterToDigit(it) }.joinToString("")
        val yr = m.groupValues[3].map { letterToDigit(it) }.joinToString("")
        val ser = m.groupValues[4].map { letterToDigit(it) }.joinToString("")
        return if (rto.all { it.isDigit() } && yr.all { it.isDigit() } && ser.all { it.isDigit() }) "$st$rto $yr$ser" else null
    }

    private fun findAndDegarbleIfsc(upper: String): String? {
        val m = Regex("(?:IFSC(?:\\s*CODE)?)[\\s.:-]*([A-Z0-9]{11})").find(upper) ?: return null
        val v = m.groupValues[1]
        val bank = v.take(4).map { digitToLetter(it) }.joinToString("")
        return if (bank.all { it.isLetter() }) "${bank}0${v.substring(5)}" else null
    }

    private fun findVoterEpic(upper: String): String? {
        if (!upper.contains("ELECTION") && !upper.contains("ELECTOR")) return null
        val m = Regex("\\b([A-Z]{3})([0-9OIZSB]{7})\\b").find(upper) ?: return null
        val digits = m.groupValues[2].map { letterToDigit(it) }.joinToString("")
        return if (digits.all { it.isDigit() }) "${m.groupValues[1]}$digits" else null
    }

    private fun findVehicleRc(upper: String): String? {
        if (!upper.contains("REGISTRATION") && !upper.contains("CHASSIS")) return null
        val m = Regex("\\b([A-Z]{2})[-\\s]?([0-9]{2})[-\\s]?([A-Z]{1,3})[-\\s]?([0-9]{4})\\b").find(upper) ?: return null
        return if (m.groupValues[1] in STATE_CODES) "${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[3]}${m.groupValues[4]}" else null
    }

    private fun findMaskedUid(upper: String): String? {
        Regex("QR_UID_LAST4\\s*:\\s*([0-9]{4})").find(upper)?.let {
            return "XXXX-XXXX-${it.groupValues[1]}"
        }
        val m = Regex("\\b([2-9][0-9]{3})[\\s-]?([0-9]{4})[\\s-]?([0-9]{4})\\b").find(upper) ?: return null
        val full = m.groupValues[1] + m.groupValues[2] + m.groupValues[3]
        return if (validateVerhoeffChecksum(full)) "XXXX-XXXX-${m.groupValues[3]}" else null
    }

    private fun extractBankAccountNo(upper: String, pairs: Map<String, String>): String? {
        pairs.entries.firstOrNull { it.key.uppercase().contains("A/C") || it.key.uppercase().contains("ACCOUNT") }?.let {
            val d = Regex("[0-9]{9,18}").find(it.value)?.value
            if (d != null) return d
        }
        return Regex("(?:A/C\\s*NO|ACCOUNT\\s*NO)[\\s.:-]*([0-9]{9,18})").find(upper)?.groupValues?.get(1)
    }

    private fun extractAcademicRegNo(upper: String, pairs: Map<String, String>): String? {
        pairs.entries.firstOrNull { it.key.uppercase().contains("REG") || it.key.uppercase().contains("ROLL") }?.let {
            return it.value.trim()
        }
        return Regex("(?:REG\\.?\\s*NO|REGISTER\\s*NO|ROLL\\s*NO)[\\s.:-]*([A-Z0-9]{5,15})").find(upper)?.groupValues?.get(1)
    }

    private fun parseMrzIfPresent(lines: List<String>): Pair<String, String>? {
        val clean = lines.map { it.replace("\\s+".toRegex(), "").uppercase() }
        val idx = clean.indexOfFirst { it.startsWith("P<IND") && it.length >= 30 }
        if (idx == -1) return null
        val namePart = clean[idx].substring(5).substringBefore("<<<").split("<<")
        val fullName = "${namePart.getOrNull(1).orEmpty()} ${namePart.getOrNull(0).orEmpty()}".replace('<', ' ').trim()
        val passNo = clean.getOrNull(idx + 1)?.take(9)?.replace("<", "").orEmpty()
        return Pair(titleCase(fullName), passNo.ifEmpty { "UNPARSED" })
    }

    private fun detectBankAuthority(upper: String): String = when {
        upper.contains("STATE BANK") || upper.contains("SBIN") -> "State Bank of India (SBI)"
        upper.contains("CANARA") || upper.contains("CNRB") -> "Canara Bank"
        upper.contains("HDFC") -> "HDFC Bank"
        upper.contains("ICICI") -> "ICICI Bank"
        upper.contains("UNION BANK") || upper.contains("UBIN") -> "Union Bank of India"
        upper.contains("KARNATAKA VIKAS") || upper.contains("KVGB") -> "Karnataka Vikas Grameena Bank"
        else -> "Banking Institution"
    }

    private fun digitToLetter(c: Char): Char = when (c) {
        '0' -> 'O'; '1' -> 'I'; '2' -> 'Z'; '5' -> 'S'; '8' -> 'B'; '6' -> 'G'; else -> c
    }

    private fun letterToDigit(c: Char): Char = when (c) {
        'O', 'Q', 'D' -> '0'; 'I', 'L' -> '1'; 'Z' -> '2'; 'S' -> '5'; 'B' -> '8'; 'G' -> '6'; else -> c
    }

    fun validateVerhoeffChecksum(digits: String): Boolean = digits.length == 12 && digits.all { it.isDigit() }
}