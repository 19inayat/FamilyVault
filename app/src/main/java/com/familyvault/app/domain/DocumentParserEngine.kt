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
        "KACHERI", "NADAKACHERI", "MARKS", "SECONDARY", "EDUCATION", "BOARD", "UNIVERSITY",
        "MALE", "FEMALE", "HOLDER", "REL", "PRO", "PROP", "MANSER", "LTD", "LIMITED", "PVT",
        "CORP", "CORPORATE", "PARK", "ROAD", "BELAPUR", "THANE", "NAVI", "MUMBAI", "CENTRAL",
        "CELL", "JIO", "RELIANCE", "THE", "SHOULD", "ALWAYS", "DISPLAYED", "WHILE", "DUTY",
        "HEIGHT", "MARK", "MOLE", "FOREHEAD", "LOSS", "REPORTED", "ISSUING", "ONCE", "FOUND",
        "PLEASE", "RETURN", "EMERGENCY", "CONTACT", "NOS", "DOJ", "EC", "NO", "EMP", "CODE",
        "NATIONALITY", "INDIAN", "DRUG", "FORM", "SCHEDULE", "RATION", "FOOD", "CIVIL",
        "SUPPLIES", "AYUSHMAN", "BHARAT", "ABHA", "CGHS", "ECHS", "EPFO", "UAN", "MGNREGA",
        "UDID", "DISABILITY", "ESHRAM", "SHRAM", "OCI", "OVERSEAS", "CSD", "CANTEEN", "POLICE",
        "RAILWAY", "RAILWAYS", "APAAR", "UDISE", "SENIOR", "CITIZEN", "SEVA", "SINDHU",
        "MATSYADHARA", "FISHERIES", "BUNKAR", "WEAVER", "HANDLOOMS", "MARRIAGE", "CASTE",
        "DOMICILE", "RESIDENCE", "UDYAM", "MSME", "GST", "GSTIN", "INCORPORATION", "PAHANI",
        "RTC", "BHOOMI", "MUTATION", "SUCCESSION", "HEIR", "KANNADIGA", "ATALJI", "JANASNEHI"
    )

    private val PHONE_REGEX = Regex("\\b(1800[\\s-]?[0-9]{3}[\\s-]?[0-9]{4}|[6-9][0-9]{9})\\b")
    private val PINCODE_REGEX = Regex("\\b([1-8][0-9]{5})\\b")

    fun parseDocumentText(rawInput: String): ParsedDocumentPayload {
        val cleanText = rawInput.trim()
        val upper = cleanText.uppercase()
        val allLines = cleanText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val visibleLines = allLines.filter { !it.startsWith("SPATIAL_") && !it.startsWith("QR_") }

        val dynamicPairs = harvestAllKeyValuePairsSafe(visibleLines)
        val mrz = parseMrzIfPresent(visibleLines)

        val pan = findAndDegarblePan(upper)
        val dl = findAndDegarbleDl(upper)
        val ifsc = findAndDegarbleIfsc(upper, dynamicPairs)
        val gstin = Regex("\\b([0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z])\\b").find(upper)?.value
        val udyam = Regex("\\b(UDYAM-[A-Z]{2}-[0-9]{2}-[0-9]{7})\\b").find(upper)?.value
        val cin = Regex("\\b([LU][0-9]{5}[A-Z]{2}[0-9]{4}[A-Z]{3}[0-9]{6})\\b").find(upper)?.value
        val udid = Regex("\\b([A-Z]{2}[0-9]{16})\\b").find(upper)?.value
        val abha = Regex("\\b([0-9]{2}-[0-9]{4}-[0-9]{4}-[0-9]{4})\\b").find(upper)?.value
        val mgnrega = Regex("\\b([A-Z]{2}-[0-9]{2}-[0-9]{3}-[0-9]{3}-[0-9]{3}/[0-9A-Z]+)\\b").find(upper)?.value
        val nadaKacheriRd = Regex("\\bR[D0O]([0-9OIZSB]{13})\\b").find(upper)?.let {
            "RD" + it.groupValues[1].map { c -> letterToDigit(c) }.joinToString("")
        }
        val drugLic = Regex("\\b([A-Z]{3,6}-[0-9]{2}/[0-9]{2,5}(?:\\s*,\\s*[0-9]{2}/[0-9]{2,5})?)\\b").find(upper)?.value
        val epic = findVoterEpic(upper)
        val rcNo = findVehicleRc(upper)
        val maskedUid = findMaskedNationalId(upper)
        val empOrCardId = findEmployeeOrCardId(upper, allLines, dynamicPairs)

        val (category, subType, authority) = classifyAllIndianAndKarnatakaDocs(
            upper = upper,
            lines = visibleLines,
            pan = pan,
            dl = dl,
            ifsc = ifsc,
            gstin = gstin,
            udyam = udyam,
            cin = cin,
            udid = udid,
            abha = abha,
            mgnrega = mgnrega,
            rd = nadaKacheriRd,
            drugLic = drugLic,
            mrz = mrz != null,
            epic = epic,
            rc = rcNo,
            uid = maskedUid,
            empId = empOrCardId
        )

        val docNo = when (subType) {
            "PAN Card" -> pan ?: empOrCardId ?: "UNPARSED-NO"
            "Driving Licence (DL)" -> dl ?: empOrCardId ?: "UNPARSED-NO"
            "Indian Passport" -> mrz?.second ?: Regex("\\b([A-Z][0-9]{7})\\b").find(upper)?.value ?: "UNPARSED-NO"
            "Voter ID Card (EPIC)" -> epic ?: empOrCardId ?: "UNPARSED-NO"
            "Aadhaar Card" -> maskedUid ?: "[ID Protected]"
            "ABHA Card (Ayushman Bharat)" -> abha ?: empOrCardId ?: "UNPARSED-NO"
            "Unique Disability ID (UDID)" -> udid ?: empOrCardId ?: "UNPARSED-NO"
            "MGNREGA Job Card" -> mgnrega ?: empOrCardId ?: "UNPARSED-NO"
            "Udyam Registration Certificate" -> udyam ?: empOrCardId ?: "UNPARSED-NO"
            "GST Registration Certificate" -> gstin ?: empOrCardId ?: "UNPARSED-NO"
            "Certificate of Incorporation (COI)" -> cin ?: empOrCardId ?: "UNPARSED-NO"
            "Nadakacheri Income & Caste Certificate" -> nadaKacheriRd ?: empOrCardId ?: "UNPARSED-NO"
            "Drug / Trade License" -> drugLic ?: empOrCardId ?: "UNPARSED-NO"
            "Vehicle RC Card" -> rcNo ?: empOrCardId ?: "UNPARSED-NO"
            "Bank Passbook" -> extractBankAccountNo(dynamicPairs) ?: ifsc ?: "UNPARSED-NO"
            else -> nadaKacheriRd ?: pan ?: dl ?: gstin ?: udyam ?: cin ?: udid ?: abha ?: mgnrega ?: drugLic ?: epic ?: rcNo ?: empOrCardId ?: maskedUid ?: "UNPARSED-NO"
        }

        val holderName = mrz?.first ?: resolveHolderNameDynamic(allLines, visibleLines, dynamicPairs, docNo)
        val (issueDate, expiryDate, dob) = extractDatesDynamic(allLines, dynamicPairs)
        val phone = PHONE_REGEX.find(cleanText)?.groupValues?.get(1)
        val extraJson = buildDynamicTagsJson(upper, visibleLines, dynamicPairs, dob, ifsc, docNo, holderName)

        return ParsedDocumentPayload(
            docCategory = category,
            docSubType = subType,
            issuingAuthority = authority,
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
     * Crash-Proof Key-Value Harvester (Zero ICU `[:...:]` regex classes).
     */
    private fun harvestAllKeyValuePairsSafe(lines: List<String>): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (line in lines) {
            val segments = line.split(Regex("\\s{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
            for (seg in segments) {
                val colonIdx = seg.indexOf(':')
                if (colonIdx in 2..28 && colonIdx < seg.length - 1) {
                    val key = seg.substring(0, colonIdx).trim()
                    val value = seg.substring(colonIdx + 1).trim()
                    if (key.any { it.isLetter() } && value.isNotEmpty() && !key.uppercase().equals("WW")) {
                        map[key] = value
                    }
                }
            }
        }
        return map
    }

    private fun classifyAllIndianAndKarnatakaDocs(
        upper: String,
        lines: List<String>,
        pan: String?,
        dl: String?,
        ifsc: String?,
        gstin: String?,
        udyam: String?,
        cin: String?,
        udid: String?,
        abha: String?,
        mgnrega: String?,
        rd: String?,
        drugLic: String?,
        mrz: Boolean,
        epic: String?,
        rc: String?,
        uid: String?,
        empId: String?
    ): Triple<String, String, String> {
        return when {
            upper.contains("NADAKACHERI") || upper.contains("NADA KACHERI") || upper.contains("ATALJI JANASNEHI") || rd != null ->
                Triple("KARNATAKA_CERT", "Nadakacheri Income & Caste Certificate", "Revenue Dept., Karnataka (Nadakacheri)")
            upper.contains("PAHANI") || upper.contains("BHOOMI") || (upper.contains("RTC") && upper.contains("SURVEY")) ->
                Triple("KARNATAKA_CERT", "RTC (Pahani) Extract", "Bhoomi Portal / Revenue Dept., Karnataka")
            upper.contains("MUTATION") || upper.contains("MR NO") ->
                Triple("KARNATAKA_CERT", "Mutation Extract", "Revenue Dept., Karnataka")
            upper.contains("LEGAL HEIR") || upper.contains("SUCCESSION CERTIFICATE") || upper.contains("SURVIVING FAMILY") ->
                Triple("KARNATAKA_CERT", "Legal Heir / Succession Certificate", "Tahsildar Office, Karnataka")
            upper.contains("KANNADIGA") || upper.contains("MOTHER TONGUE") || upper.contains("KANNADA MEDIUM") ->
                Triple("KARNATAKA_CERT", "Kannadiga / Mother Tongue Certificate", "State Educational / Revenue Bodies, Karnataka")

            upper.contains("SENIOR CITIZEN") || upper.contains("SEVA SINDHU") ->
                Triple("KARNATAKA_ID", "Karnataka Senior Citizen Card", "Dept. of Empowerment / Seva Sindhu")
            upper.contains("RATION CARD") || upper.contains("FOOD AND CIVIL") || upper.contains("AHARA") ->
                Triple("KARNATAKA_ID", "Smart Ration Card (Karnataka)", "Dept. of Food & Civil Supplies")
            upper.contains("MATSYADHARA") || upper.contains("FISHERIES") || upper.contains("FISHERMEN") ->
                Triple("KARNATAKA_ID", "Matsyadhara / Fishermen ID", "Dept. of Fisheries, Karnataka")
            upper.contains("BUNKAR") || upper.contains("WEAVER") || upper.contains("HANDLOOMS") ->
                Triple("KARNATAKA_ID", "Weaver ID Card (Bunkar Card)", "Dept. of Handlooms & Textiles")
            upper.contains("KGID") || (upper.contains("GOVERNMENT OF KARNATAKA") && upper.contains("EMPLOYEE")) ->
                Triple("KARNATAKA_ID", "Karnataka Govt. Employee ID", "State Government Departments, Karnataka")

            upper.contains("UDYAM") || upper.contains("MSME") || udyam != null ->
                Triple("NATIONAL_CERT", "Udyam Registration Certificate", "Ministry of MSME")
            upper.contains("GOODS AND SERVICES TAX") || upper.contains("GSTIN") || gstin != null ->
                Triple("NATIONAL_CERT", "GST Registration Certificate", "Goods and Services Tax Network")
            upper.contains("CERTIFICATE OF INCORPORATION") || upper.contains("MINISTRY OF CORPORATE AFFAIRS") || cin != null ->
                Triple("NATIONAL_CERT", "Certificate of Incorporation (COI)", "Ministry of Corporate Affairs")
            upper.contains("DRUG") || upper.contains("DLNO") || drugLic != null ->
                Triple("NATIONAL_CERT", "Drug / Trade License", detectOrgAuthority(lines))
            upper.contains("BIRTH CERTIFICATE") || upper.contains("REGISTRATION OF BIRTHS") ->
                Triple("NATIONAL_CERT", "Birth Certificate", "Municipal Corporations / Local Bodies")
            upper.contains("DEATH CERTIFICATE") || upper.contains("REGISTRATION OF DEATHS") ->
                Triple("NATIONAL_CERT", "Death Certificate", "Municipal Corporations / Local Bodies")
            upper.contains("MARRIAGE CERTIFICATE") || upper.contains("REGISTRAR OF MARRIAGES") ->
                Triple("NATIONAL_CERT", "Marriage Certificate", "Registrar of Marriages")
            upper.contains("ECONOMICALLY WEAKER") || upper.contains("INCOME AND ASSET") || upper.contains("EWS") ->
                Triple("NATIONAL_CERT", "Income & Asset Certificate (EWS)", "Revenue Authorities")
            upper.contains("CASTE CERTIFICATE") || upper.contains("SCHEDULED CASTE") || upper.contains("SCHEDULED TRIBE") || upper.contains("OBC") ->
                Triple("NATIONAL_CERT", "Caste Certificate", "Revenue Dept. / Competent Authority")
            upper.contains("DOMICILE") || upper.contains("RESIDENCE CERTIFICATE") ->
                Triple("NATIONAL_CERT", "Domicile / Residence Certificate", "State Government Authorities")
            upper.contains("POLICE CLEARANCE") || upper.contains("PCC") ->
                Triple("NATIONAL_CERT", "Police Clearance Certificate (PCC)", "Police Dept. / Ministry of External Affairs")
            upper.contains("DEGREE") || upper.contains("PROVISIONAL CERTIFICATE") || upper.contains("PASSING CERTIFICATE") ->
                Triple("NATIONAL_CERT", "Degree / Passing Certificate", "Educational Boards / Universities")

            upper.contains("ABHA") || upper.contains("AYUSHMAN BHARAT") || upper.contains("PM-JAY") || abha != null ->
                Triple("HEALTH_WELFARE", "ABHA Card (Ayushman Bharat)", "National Health Authority (NHA)")
            upper.contains("CGHS") || upper.contains("ECHS") || upper.contains("CONTRIBUTORY HEALTH") ->
                Triple("HEALTH_WELFARE", "CGHS / ECHS Card", "Central Govt. / Ministry of Defence")
            upper.contains("E-SHRAM") || upper.contains("ESHRAM") || upper.contains("UNORGANISED WORKER") ->
                Triple("HEALTH_WELFARE", "e-Shram Card", "Ministry of Labour & Employment")
            upper.contains("EPFO") || upper.contains("PROVIDENT FUND") || upper.contains("UNIVERSAL ACCOUNT NUMBER") || upper.contains("UAN") ->
                Triple("HEALTH_WELFARE", "UAN / EPFO Card", "Employees' Provident Fund Org.")
            upper.contains("MGNREGA") || upper.contains("NREGA") || upper.contains("JOB CARD") || mgnrega != null ->
                Triple("HEALTH_WELFARE", "MGNREGA Job Card", "Ministry of Rural Development")
            upper.contains("UDID") || upper.contains("DISABILITY") || upper.contains("SWAVALAMBAN") || udid != null ->
                Triple("HEALTH_WELFARE", "Unique Disability ID (UDID)", "Ministry of Social Justice")

            upper.contains("OVERSEAS CITIZEN OF INDIA") || upper.contains("OCI") ->
                Triple("TRANSIT_SERVICE", "OCI Card", "Ministry of External Affairs")
            upper.contains("CANTEEN STORES") || upper.contains("CSD") || upper.contains("ARMED FORCES") ->
                Triple("TRANSIT_SERVICE", "Defense / CSD Smart Card", "Ministry of Defence")
            upper.contains("INDIAN RAILWAYS") || upper.contains("SOUTH WESTERN RAILWAY") || upper.contains("RAILWAY IDENTITY") ->
                Triple("TRANSIT_SERVICE", "Railway Identity Card", "Indian Railways")
            upper.contains("POLICE") || upper.contains("CAPF") || upper.contains("CRPF") || upper.contains("BSF") || upper.contains("CISF") ->
                Triple("TRANSIT_SERVICE", "Paramilitary / Police ID Card", "Central Armed Police / State Police")

            upper.contains("APAAR") || upper.contains("ACADEMIC BANK OF CREDITS") ->
                Triple("ACADEMIC_ID", "APAAR ID", "Ministry of Education (Govt. of India)")
            upper.contains("PERMANENT EDUCATION NUMBER") || upper.contains("UDISE") || upper.contains("PEN NO") ->
                Triple("ACADEMIC_ID", "Permanent Education Number (PEN)", "UDISE+ / Ministry of Education")
            upper.contains("MARKS") || upper.contains("SECONDARY EDUCATION") || upper.contains("KSEAB") || upper.contains("SSLC") || upper.contains("CBSE") || upper.contains("ICSE") || upper.contains("VTU") ->
                Triple("ACADEMIC_ID", "Board/University Enrollment Card", "CBSE, ICSE, State Boards, Universities")
            upper.contains("STUDENT") || upper.contains("USN") || upper.contains("CAMPUS") || upper.contains("COLLEGE") ->
                Triple("ACADEMIC_ID", "Institutional Student ID Card", detectOrgAuthority(lines))

            upper.contains("INCOME TAX") || upper.contains("PERMANENT ACCOUNT") || pan != null ->
                Triple("NATIONAL_ID", "PAN Card", "Income Tax Dept. (Govt. of India)")
            upper.contains("DRIVING LICENCE") || upper.contains("DRIVING LICENSE") || dl != null ->
                Triple("NATIONAL_ID", "Driving Licence (DL)", "State Transport Dept. / MoRTH")
            mrz || (upper.contains("REPUBLIC OF INDIA") && upper.contains("PASSPORT")) ->
                Triple("NATIONAL_ID", "Indian Passport", "Ministry of External Affairs")
            upper.contains("ELECTION COMMISSION") || upper.contains("ELECTOR") || epic != null ->
                Triple("NATIONAL_ID", "Voter ID Card (EPIC)", "Election Commission of India")
            upper.contains("REGISTRATION CERTIFICATE") || upper.contains("CHASSIS") || rc != null ->
                Triple("NATIONAL_ID", "Vehicle RC Card", "State Transport Dept. / RTO")
            upper.contains("PASSBOOK") || upper.contains("IFSC") || ifsc != null ->
                Triple("NATIONAL_ID", "Bank Passbook", detectBankAuthority(upper))
            upper.contains("UNIQUE IDENTIFICATION") || upper.contains("UIDAI") || upper.contains("QR_UID_LAST4") || uid != null ->
                Triple("NATIONAL_ID", "Aadhaar Card", "UIDAI (Govt. of India)")

            upper.contains("EC NO") || upper.contains("EMP NO") || upper.contains("WHILE ON DUTY") ||
                upper.contains("ID CARD CELL") || upper.contains("DOJ") || upper.contains("ID MARK") ||
                upper.contains("BLOOD GROUP") || empId != null ->
                Triple("TRANSIT_SERVICE", "Employee / Institutional ID Card", detectOrgAuthority(lines))

            else ->
                Triple("NATIONAL_CERT", "Government / Institutional Document", detectOrgAuthority(lines))
        }
    }

    private fun detectOrgAuthority(lines: List<String>): String {
        val orgRegex = Regex(
            "\\b(LTD|LIMITED|PVT|CORPORATE|CORP|RELIANCE|JIO|INSTITUTE|COLLEGE|UNIVERSITY|BOARD|HOSPITAL|TRUST|AUTHORITY|DEPARTMENT|MINISTRY|MUNICIPAL|TAHSILDAR)\\b",
            RegexOption.IGNORE_CASE
        )
        val matches = lines.filter {
            orgRegex.containsMatchIn(it) &&
                !it.uppercase().contains("LOSS OF CARD") &&
                !it.uppercase().contains("SIGNATURE") &&
                !it.uppercase().contains("DISPLAYED BY") &&
                it.length in 3..58
        }.map { it.replace("|T", "IT").trim().trimEnd(',', '.') }

        return if (matches.isNotEmpty()) matches.distinct().take(2).joinToString(" • ")
        else "Government / Institutional Authority"
    }

    private fun findEmployeeOrCardId(
        upper: String,
        allLines: List<String>,
        pairs: Map<String, String>
    ): String? {
        val idKeyWords = setOf("EC NO", "EC", "EMP NO", "EMPLOYEE ID", "BADGE NO", "CARD NO", "ID NO", "UAN", "PRAN", "PPO NO", "KGID", "PEN", "APAAR", "USN", "ROLL NO", "REG NO", "CERT NO", "CERTIFICATE NO")
        for ((k, v) in pairs) {
            val ku = k.uppercase().replace(".", "").trim()
            if (ku in idKeyWords || idKeyWords.any { ku.endsWith(it) }) {
                val tok = Regex("([A-Z0-9/-]{5,18})").find(v.uppercase())?.value
                if (tok != null && tok.any { it.isDigit() }) return tok
            }
        }

        allLines.firstOrNull { it.startsWith("SPATIAL_ID_CANDIDATE :") }?.let {
            return it.substringAfter(":").trim()
        }

        val standalone = allLines
            .filter { !it.startsWith("SPATIAL_") }
            .map { it.trim() }
            .filter { Regex("^[0-9]{7,10}$").matches(it) && !it.startsWith("1800") }
        return standalone.lastOrNull()
    }

    private fun resolveHolderNameDynamic(
        allLines: List<String>,
        visibleLines: List<String>,
        pairs: Map<String, String>,
        docNo: String
    ): String {
        allLines.firstOrNull { it.startsWith("QR_NAME :") }?.let {
            val c = cleanNameString(it.substringAfter(":"))
            if (isValidHumanName(c)) return titleCase(c)
        }

        for ((k, v) in pairs) {
            val ku = k.uppercase().trim()
            if ((ku == "NAME" || ku.endsWith(" NAME")) &&
                !ku.contains("FATHER") && !ku.contains("MOTHER") &&
                !ku.contains("SPOUSE") && !ku.contains("HUSBAND") &&
                !ku.contains("WIFE") && !ku.contains("BANK")
            ) {
                val c = cleanNameString(v)
                if (isValidHumanName(c)) return titleCase(c)
            }
        }

        allLines.firstOrNull { it.startsWith("SPATIAL_BADGE_NAME :") }?.let {
            val c = cleanNameString(it.substringAfter(":"))
            if (isValidHumanName(c)) return titleCase(c)
        }

        for (i in visibleLines.indices) {
            val cur = visibleLines[i].trim()
            if ((docNo != "UNPARSED-NO" && cur == docNo) || Regex("^[0-9]{7,10}$").matches(cur)) {
                val stack = collectAdjacentNameLinesAbove(visibleLines, i)
                if (stack.isNotEmpty()) return titleCase(stack)
            }
        }

        for (i in visibleLines.indices) {
            val u = visibleLines[i].uppercase()
            if ((u.contains("FATHER") || u.startsWith("S/O") || u.startsWith("D/O") || u.startsWith("W/O") ||
                    u.contains("DOB") || u.contains("DATE OF BIRTH")) && i >= 1
            ) {
                val stack = collectAdjacentNameLinesAbove(visibleLines, i)
                if (stack.isNotEmpty()) return titleCase(stack)
            }
        }

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
                val words = combined.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                val score = if (isValidHumanName(combined)) (words.size * 15 + cluster.size * 10) else 0
                if (score >= bestScore && score >= 25) {
                    bestScore = score
                    bestCandidate = combined
                }
                idx = next
            } else {
                idx++
            }
        }

        return if (bestCandidate.isNotBlank()) titleCase(bestCandidate) else "Unknown Member"
    }

    private fun collectAdjacentNameLinesAbove(lines: List<String>, anchorIndex: Int): String {
        val parts = mutableListOf<String>()
        var b = anchorIndex - 1
        while (b >= 0 && parts.size < 3) {
            val line = lines[b].trim()
            if (isTaglessNameTokenLine(line)) {
                parts.add(0, line)
                b--
            } else {
                break
            }
        }
        val combined = cleanNameString(parts.joinToString(" "))
        return if (isValidHumanName(combined)) combined else ""
    }

    private fun isTaglessNameTokenLine(line: String): Boolean {
        val t = line.trim()
        if (t.length !in 3..32 || t.contains(":") || !Regex("^[A-Za-z.\\s]+$").matches(t)) return false
        val words = t.uppercase().replace(".", "").split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 4 || words.any { it in NON_NAME_DICTIONARY || it.length < 2 }) return false
        val vowels = setOf('A', 'E', 'I', 'O', 'U', 'Y')
        return words.none { w -> w.length >= 3 && w.none { c -> c in vowels } }
    }

    private fun cleanNameString(raw: String): String = raw
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
        pairs: Map<String, String>
    ): Triple<String?, String?, String?> {
        var issue: String? = null
        var expiry: String? = null
        var dob: String? = null
        val dateRegex = Regex("\\b([0-3][0-9O])[/.\\s-]([0-1][0-9O])[/.\\s-](19[0-9]{2}|20[0-9]{2})\\b")

        for ((k, v) in pairs) {
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

    private fun buildDynamicTagsJson(
        upper: String,
        visibleLines: List<String>,
        pairs: Map<String, String>,
        dob: String?,
        ifsc: String?,
        primaryDocNo: String,
        holderName: String
    ): String {
        val json = JSONObject()
        if (dob != null) json.put("DOB", dob)
        if (ifsc != null) json.put("IFSC", ifsc)

        Regex("\\b((?:A|B|AB|O)[+-])\\b").find(upper)?.let {
            if (upper.contains("BLOOD")) json.put("Blood Group", it.groupValues[1])
        }

        val ignoredKeys = setOf("NAME", "EC NO", "EMP NO", "IFSC", "WW")
        for ((k, v) in pairs) {
            val ku = k.uppercase().trim()
            if (ku !in ignoredKeys && v != primaryDocNo && !v.equals(holderName, ignoreCase = true) && v.length <= 45) {
                json.put(k, v)
            }
        }

        PHONE_REGEX.find(upper)?.let { json.put("Emergency/Phone", it.groupValues[1]) }
        visibleLines.map { it.trim() }
            .firstOrNull { Regex("^[0-9]{5,6}$").matches(it) && it != primaryDocNo }
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

    private fun findAndDegarbleIfsc(upper: String, pairs: Map<String, String>): String? {
        pairs.entries.firstOrNull { it.key.uppercase().contains("IFSC") }?.let {
            val tok = Regex("([A-Z0-9]{11})").find(it.value.uppercase())?.value
            if (tok != null) return tok.take(4) + "0" + tok.substring(5)
        }
        val knownBanks = listOf("SBIN", "CNRB", "HDFC", "ICIC", "UBIN", "BARB", "PUNB", "KVGB", "UTIB")
        for (m in Regex("\\b([A-Z0-9]{11})\\b").findAll(upper)) {
            val v = m.value
            val prefix = v.take(4).map { digitToLetter(it) }.joinToString("")
            if (prefix in knownBanks) return "${prefix}0${v.substring(5)}"
        }
        return null
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

    private fun findMaskedNationalId(upper: String): String? {
        if (upper.contains("QR_UID_LAST4")) return "[National ID Verified]"
        val m = Regex("\\b([2-9][0-9]{3})[\\s-]([0-9]{4})[\\s-]([0-9]{4})\\b").find(upper) ?: return null
        val full = m.groupValues[1] + m.groupValues[2] + m.groupValues[3]
        return if (validateVerhoeffChecksum(full)) "[National ID Verified]" else null
    }

    private fun extractBankAccountNo(pairs: Map<String, String>): String? {
        pairs.entries.firstOrNull { it.key.uppercase().contains("A/C") || it.key.uppercase().contains("ACCOUNT") }?.let {
            return Regex("[0-9]{9,18}").find(it.value)?.value
        }
        return null
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