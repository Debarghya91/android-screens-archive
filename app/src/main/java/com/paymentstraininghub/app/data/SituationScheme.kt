// SituationScheme.kt
// The scheme named by a situation title, or null when the title
// names none. Port of scheme(for:) and situationSchemeCandidates
// in iOS SituationsView.swift. Never invented: word-boundary,
// case-insensitive matching over the fixed candidate list, in
// the same order as iOS, first match wins.

package com.paymentstraininghub.app.data

import com.paymentstraininghub.app.data.model.Situation

private val situationSchemeCandidates: List<Pair<String, String>> = listOf(
    "UPI" to "upi",
    "SEPA" to "sepa",
    "SWIFT" to "swift",
    "Cards" to "cards?",
    "Direct Debit" to "direct debit",
    "ACH" to "ach",
    "Faster Payments" to "faster payments",
    "CHAPS" to "chaps",
    "TARGET2" to "target2",
    "Fedwire" to "fedwire",
    "CHIPS" to "chips",
    "RuPay" to "rupay",
    "Visa" to "visa",
    "Mastercard" to "mastercard",
    "NEFT" to "neft",
    "RTGS" to "rtgs",
    "IMPS" to "imps",
    "BACS" to "bacs",
    "FedNow" to "fednow"
)

fun schemeForSituation(situation: Situation): String? {
    for ((label, pattern) in situationSchemeCandidates) {
        val regex = Regex("\\b$pattern\\b", RegexOption.IGNORE_CASE)
        if (regex.containsMatchIn(situation.title)) return label
    }
    return null
}

/** Scheme labels in candidate order, for the list filter chips. */
val schemeCandidateLabels: List<String>
    get() = situationSchemeCandidates.map { it.first }

/**
 * Schemes actually named by at least one situation title, in
 * candidate order. Only these get a chip; a scheme with no
 * situation never appears as a filter. Mirrors iOS availableSchemes.
 */
fun availableSchemes(situations: List<Situation>): List<String> =
    schemeCandidateLabels.filter { label ->
        situations.any { schemeForSituation(it) == label }
    }
