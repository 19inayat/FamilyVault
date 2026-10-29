package com.familyvault.app.domain

import com.familyvault.app.data.local.FamilyMemberEntity
import kotlin.math.max
import kotlin.math.min

sealed class NameResolutionResult {
    data class ExactOrAliasMatch(
        val member: FamilyMemberEntity,
        val matchedVia: String
    ) : NameResolutionResult()

    data class MinimalMismatchAlert(
        val candidateMember: FamilyMemberEntity,
        val extractedRawName: String,
        val normalizedAliasToSave: String,
        val similarityScore: Double,
        val editDistance: Int,
        val matchReason: String
    ) : NameResolutionResult()

    data class NewMemberDetected(
        val cleanedDisplayName: String,
        val sortedTokens: String,
        val phoneticCode: String
    ) : NameResolutionResult()
}

object NameResolutionEngine {

    private val HONORIFICS = setOf(
        "MR", "MRS", "MS", "MISS", "SRI", "SMT", "SHREE", "SHRI",
        "KUMARI", "KUM", "DR", "PROF", "LATE", "MASTER"
    )

    private val TOKEN_SYNONYMS = mapOf(
        "MOHD" to "MOHAMMED",
        "MD" to "MOHAMMED",
        "MAHAMMAD" to "MOHAMMED",
        "MUHAMMAD" to "MOHAMMED",
        "ABD" to "ABDUL"
    )

    fun normalizeToSortedTokens(rawName: String): String {
        return extractCleanTokens(rawName).sorted().joinToString(" ")
    }

    fun formatCanonicalDisplayName(rawName: String): String {
        val tokens = rawName
  .replace(Regex("[^A-Za-z.\\s]"), " ")
  .trim()
  .split(Regex("\\s+"))
  .filter { it.isNotBlank() && it.uppercase().removeSuffix(".") !in HONORIFICS }

        return tokens.joinToString(" ") { token ->
  token.lowercase().replaceFirstChar { it.uppercase() }
        }
    }

    private fun extractCleanTokens(rawName: String): List<String> {
        return rawName
  .uppercase()
  .replace(Regex("[^A-Z\\s]"), " ")
  .split(Regex("\\s+"))
  .map { it.trim() }
  .filter { it.isNotEmpty() && it !in HONORIFICS }
  .map { TOKEN_SYNONYMS[it] ?: it }
    }

    fun computePhoneticSignature(rawName: String): String {
        val joined = extractCleanTokens(rawName).sorted().joinToString("")
        if (joined.isEmpty()) return "UNK"
        val firstChar = joined.first()
        val normalized = joined
  .replace("PH", "F")
  .replace("BH", "B")
  .replace("DH", "D")
  .replace("TH", "T")
  .replace("KH", "K")
  .replace("GH", "G")
  .replace("SH", "S")
  .replace("Z", "S")
  .replace("V", "W")
  .replace("Y", "I")
  .replace(Regex("[AEIOUHW]"), "")
  .replace(Regex("(.)\\1+"), "$1")
        return (firstChar + normalized.drop(1)).take(8)
    }

    fun resolveIdentity(
        extractedRawName: String,
        existingMembers: List<FamilyMemberEntity>,
        aliasMemberIdIfFound: Long?
    ): NameResolutionResult {
        val cleanedDisplay = formatCanonicalDisplayName(extractedRawName).ifEmpty { "Unknown Name" }
        val inputSortedTokens = normalizeToSortedTokens(extractedRawName)
        val inputPhonetic = computePhoneticSignature(extractedRawName)

        if (aliasMemberIdIfFound != null) {
  val matchedMember = existingMembers.find { it.memberId == aliasMemberIdIfFound }
  if (matchedMember != null) {
      return NameResolutionResult.ExactOrAliasMatch(
          member = matchedMember,
          matchedVia = "Saved Alias ('$cleanedDisplay')"
      )
  }
        }

        for (member in existingMembers) {
  if (member.sortedTokens.equals(inputSortedTokens, ignoreCase = true)) {
      return NameResolutionResult.ExactOrAliasMatch(
          member = member,
          matchedVia = "Exact Canonical Match"
      )
  }
        }

        val inputTokenList = extractCleanTokens(extractedRawName)
        var bestCandidate: FamilyMemberEntity? = null
        var bestJaroWinkler = 0.0
        var bestEditDistance = Int.MAX_VALUE
        var bestReason = ""

        for (member in existingMembers) {
  val targetTokens = member.sortedTokens
  val ocrInput = normalizeOcrConfusables(inputSortedTokens)
  val ocrTarget = normalizeOcrConfusables(targetTokens)

  val jwScore = max(
      jaroWinklerSimilarity(inputSortedTokens, targetTokens),
      jaroWinklerSimilarity(ocrInput, ocrTarget)
  )
  val editDist = min(
      levenshteinDistance(inputSortedTokens, targetTokens),
      levenshteinDistance(ocrInput, ocrTarget)
  )
  val isInitialMatch = checkIndianInitialExpansion(
      inputTokenList,
      extractCleanTokens(member.canonicalName)
  )
  val isPhoneticMatch = member.phoneticPrimary == inputPhonetic && jwScore >= 0.78

  if (isInitialMatch || (editDist <= 2 && targetTokens.length >= 5) || jwScore >= 0.84 || isPhoneticMatch) {
      val effectiveScore = when {
          isInitialMatch -> max(jwScore, 0.92)
          isPhoneticMatch -> max(jwScore, 0.88)
          else -> jwScore
      }
      if (effectiveScore > bestJaroWinkler) {
          bestJaroWinkler = effectiveScore
          bestEditDistance = editDist
          bestCandidate = member
          bestReason = when {
              isInitialMatch -> "Initial expansion match ('$cleanedDisplay' <-> '${member.canonicalName}')"
              editDist <= 2 -> "Spelling typo detected (Edit distance: $editDist char)"
              isPhoneticMatch -> "Phonetic sound-alike match"
              else -> "Fuzzy similarity (${(effectiveScore * 100).toInt()}%)"
          }
      }
  }
        }

        if (bestCandidate != null) {
  return NameResolutionResult.MinimalMismatchAlert(
      candidateMember = bestCandidate,
      extractedRawName = cleanedDisplay,
      normalizedAliasToSave = inputSortedTokens,
      similarityScore = bestJaroWinkler,
      editDistance = bestEditDistance,
      matchReason = bestReason
  )
        }

        return NameResolutionResult.NewMemberDetected(
  cleanedDisplayName = cleanedDisplay,
  sortedTokens = inputSortedTokens,
  phoneticCode = inputPhonetic
        )
    }

    private fun checkIndianInitialExpansion(tokensA: List<String>, tokensB: List<String>): Boolean {
        if (tokensA.size < 2 || tokensB.size < 2 || tokensA.size != tokensB.size) return false
        val fullWordsA = tokensA.filter { it.length > 1 }
        val initialsA = tokensA.filter { it.length == 1 }
        if (initialsA.isNotEmpty() && fullWordsA.isNotEmpty()) {
  val remainingB = tokensB.toMutableList()
  for (wordA in fullWordsA) {
      val matchIdx = remainingB.indexOfFirst {
          levenshteinDistance(wordA, it) <= 1 || jaroWinklerSimilarity(wordA, it) >= 0.88
      }
      if (matchIdx == -1) return false
      remainingB.removeAt(matchIdx)
  }
  for (initA in initialsA) {
      val initIdx = remainingB.indexOfFirst { it.startsWith(initA) }
      if (initIdx == -1) return false
      remainingB.removeAt(initIdx)
  }
  return remainingB.isEmpty()
        }
        return false
    }

    private fun normalizeOcrConfusables(input: String): String {
        return input.replace('0', 'O').replace('1', 'I').replace('L', 'I').replace('5', 'S').replace('8', 'B')
    }

    fun levenshteinDistance(s1: String, s2: String): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length
        var prev = IntArray(s2.length + 1) { it }
        var curr = IntArray(s2.length + 1)
        for (i in 1..s1.length) {
  curr[0] = i
  for (j in 1..s2.length) {
      val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
      curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
  }
  val temp = prev; prev = curr; curr = temp
        }
        return prev[s2.length]
    }

    fun jaroWinklerSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        val matchDistance = max(s1.length, s2.length) / 2 - 1
        val s1Matches = BooleanArray(s1.length)
        val s2Matches = BooleanArray(s2.length)
        var matches = 0
        for (i in s1.indices) {
  val start = max(0, i - matchDistance)
  val end = min(i + matchDistance + 1, s2.length)
  for (j in start until end) {
      if (s2Matches[j] || s1[i] != s2[j]) continue
      s1Matches[i] = true; s2Matches[j] = true; matches++; break
  }
        }
        if (matches == 0) return 0.0
        var t = 0; var k = 0
        for (i in s1.indices) {
  if (!s1Matches[i]) continue
  while (!s2Matches[k]) k++
  if (s1[i] != s2[k]) t++
  k++
        }
        val m = matches.toDouble()
        val jaro = ((m / s1.length) + (m / s2.length) + ((m - t / 2.0) / m)) / 3.0
        var prefix = 0
        for (i in 0 until minOf(4, s1.length, s2.length)) {
  if (s1[i] == s2[i]) prefix++ else break
        }
        return jaro + (prefix * 0.1 * (1.0 - jaro))
    }
}
