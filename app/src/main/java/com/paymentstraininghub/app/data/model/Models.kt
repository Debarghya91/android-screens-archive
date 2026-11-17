package com.paymentstraininghub.app.data.model

import com.paymentstraininghub.app.data.ReadingOrder
import kotlinx.serialization.Serializable

// Models.kt
// Payments Training Hub Android - data models for the learning content.
// Ported from the iOS ContentModels.swift. Content is loaded from the
// live website so it stays current. No em dashes in any user-facing text.

// MARK: - Search index

/// One entry from the live search index
/// (https://www.paymentstraininghub.com/assets/search-index.json).
/// The app fetches the compact index first
/// (search-index-compact.json): lesson and situation entries there
/// carry [tags], a short [excerpt], and the lesson [chapter] and
/// [domain] instead of the full [text], so the whole catalogue
/// text is never downloaded on first load. FAQ, guide, lab and
/// test entries keep their full text in both files. [text] is
/// blank for compact lesson and situation entries; [preview],
/// [chapterName] and [domainLabel] fall back to the compact
/// fields, so every reader works unchanged on either file.
@Serializable
data class SearchEntry(
    val id: String,
    val title: String,
    val url: String,
    val type: String,
    val text: String = "",
    /// Distinctive body keywords for compact entries, in body
    /// order. Search matches and ranks over these exactly as it
    /// does over [text]. Empty for full-index entries.
    val tags: List<String> = emptyList(),
    /// The first 140 characters of the entry text, with an
    /// ellipsis when truncated, for compact entries. Empty for
    /// full-index entries, whose [preview] comes from [text].
    val excerpt: String = "",
    /// The chapter name for compact lesson entries, parsed at
    /// generation time by the same rules as [chapterName]. Null
    /// for full-index entries and non-lessons.
    val chapter: String? = null,
    /// The domain label for compact lesson entries, parsed at
    /// generation time by the same rules as [domainLabel]. Null
    /// for full-index entries and non-lessons.
    val domain: String? = null
) {
    val isLesson: Boolean get() = type.equals("lesson", ignoreCase = true)
    val isGuide: Boolean get() = type.equals("guide", ignoreCase = true)
    val isSituation: Boolean get() = type.equals("situation", ignoreCase = true)
    val isLab: Boolean get() = type.equals("lab", ignoreCase = true)
    val isTest: Boolean get() = type.equals("test", ignoreCase = true)
    val isFaq: Boolean get() = type.equals("faq", ignoreCase = true)

    /// Display name for the entry type, for citations.
    val typeDisplayName: String
        get() = when (type.lowercase()) {
            "lesson" -> "Lesson"
            "guide" -> "Guide"
            "situation" -> "Situation"
            "lab" -> "Lab"
            "test" -> "Test"
            "faq" -> "FAQ"
            else -> type
        }

    /// A short preview of the entry text for search results.
    /// Compact entries have no text: their generated excerpt is
    /// the preview, unchanged.
    val preview: String
        get() {
            if (text.isBlank()) return excerpt
            val trimmed = text.trim()
            if (trimmed.length <= 140) return trimmed
            return trimmed.substring(0, 140) + "..."
        }

    /// True when this entry is a supplemental lesson written for v34
    /// to cover a CIPSP or Payment Guru syllabus topic that the
    /// website catalogue does not teach in depth.
    val isSupplemental: Boolean get() = id.endsWith("-supp")

    /// The audio file name for a lesson, for example "ch1s1.mp3".
    /// Supplemental lessons return their own file too: all 72 have
    /// recorded narration on the audio hosts.
    val audioFileName: String?
        get() = if (isLesson) "$id.mp3" else null

    /// The chapter number from a lesson id (ch<N>s<M>), or null.
    val chapterNumber: Int?
        get() {
            if (!id.startsWith("ch")) return null
            val digits = id.drop(2).takeWhile { it.isDigit() }
            return digits.toIntOrNull()
        }

    /// The lesson number from a lesson id (ch<N>s<M>), or null.
    val lessonNumber: Int?
        get() {
            if (!id.startsWith("ch")) return null
            val sIndex = id.lastIndexOf('s')
            if (sIndex < 0) return null
            val digits = id.substring(sIndex + 1).takeWhile { it.isDigit() }
            if (digits.isEmpty()) return null
            return digits.toIntOrNull()
        }

    /// The exact citation for a lesson or entry, in display
    /// numbers from the reading-order map: regular lessons use
    /// their book chapter and lesson position ("Chapter 26,
    /// Lesson 3: pacs.002 Explained"). The map lists each lesson's
    /// display number; lessons added after the map was built take
    /// the s-number position, which the map confirms. Supplemental
    /// lessons keep the chapter and the real title with no number
    /// ("Chapter 25: RMA: ..."): their s901+ slot numbers are not
    /// lesson numbers a learner recognises, and lessonNumber is
    /// nil for them anyway, because the last "s" in
    /// "ch54s901-supp" is the one in "-supp" and no digits follow
    /// it. Held-out chapters show the chapter name with no number.
    /// Anything else uses its type and title
    /// ("Guide: camt.029 Explained", "Situation: ...").
    val citation: String
        get() {
            if (isLesson) {
                return ReadingOrder.chapterLessonLabel(id, chapterName)
                    .let { label ->
                        if (label == "Lesson") "$typeDisplayName: $title" else "$label: $title"
                    }
            }
            return "$typeDisplayName: $title"
        }

    /// The catalogue text after the lesson title prefix, when the
    /// text starts with it.
    private val textAfterTitle: String
        get() {
            val trimmed = text.trim()
            val cleanTitle = title.trim()
            if (cleanTitle.isNotEmpty() && trimmed.startsWith(cleanTitle)) {
                return trimmed.substring(cleanTitle.length).trim()
            }
            return trimmed
        }

    /// The chapter name: the compact [chapter] field when the
    /// entry carries one, else the text between the lesson title
    /// and the section marker. Null when the text carries no marker.
    val chapterName: String?
        get() {
            chapter?.takeIf { it.isNotBlank() }?.let { return it }
            val rest = textAfterTitle
            val marker = sectionMarkerRange(rest) ?: return null
            val name = rest.substring(0, marker.first).trim()
            return if (name.isEmpty()) null else name
        }

    /// The domain label named in the catalogue text, when the text
    /// carries a section marker followed by a known domain label.
    /// Compact entries carry the [domain] field instead. Null when
    /// no label matches. Used to fill Chapter.domain.
    internal fun domainLabel(): String? {
        domain?.takeIf { it.isNotBlank() }?.let { return it }
        var rest = textAfterTitle
        val marker = sectionMarkerRange(rest) ?: return null
        rest = rest.substring(marker.second).trim()
        for (domain in domainLabels) {
            if (rest.startsWith(domain)) return domain
        }
        return null
    }

    /// The lesson body: the entry text with the lesson title
    /// (stripped as many times as it leads the text, because some
    /// guide entries carry it twice), the chapter name, the section
    /// marker and the domain label removed, so the text starts with
    /// the actual lesson content.
    val bodyText: String
        get() {
            var rest = text.trim()
            val cleanTitle = title.trim()
            if (cleanTitle.isNotEmpty()) {
                while (rest.startsWith(cleanTitle)) {
                    rest = rest.substring(cleanTitle.length).trim()
                }
            }
            val marker = sectionMarkerRange(rest)
            if (marker != null) {
                rest = rest.substring(marker.second).trim()
            }
            for (domain in domainLabels) {
                if (rest.startsWith(domain)) {
                    rest = rest.substring(domain.length).trim()
                    break
                }
            }
            return rest
        }

    companion object {
        /// Domain labels used by the site, longest first, so the body
        /// strip matches the full label before a shorter prefix.
        private val domainLabels: List<String> = listOf(
            "AI Product Owner and Product Manager for Payments",
            "System Design and Real Banks",
            "Domestic Payment Systems",
            "Compliance, Risk and Fraud",
            "BA Workshop and Capstone",
            "Cross-Border Payments",
            "Clearing and Settlement",
            "Technology and APIs",
            "Payments in Practice",
            "Payment Architecture",
            "Payment Operations",
            "Professional Masterclass",
            "Emerging Technologies",
            "APIs and Integration",
            "Global Payment Rails",
            "Card and Paper Payments",
            "Compliance and Risk",
            "Messaging Standards",
            "Messaging and rails",
            "Clearing, cards and liquidity",
            "Architecture, APIs and operations",
            "Risk, product, practice and emerging",
            "Risk, compliance and regulation",
            "Accounts, ledger and accounting",
            "Emerging Topics",
            "Foundations",
            "Cards"
        )

        /// The range of the section marker in the given text, or null.
        /// The index text is a single line:
        /// `<lesson title> <chapter name> Part N: <domain> <body>`,
        /// with `Domain NN:` or `Stage N:` for newer lessons and a
        /// domain code (`D12`) for the ch86-95 block. The marker is the
        /// divider between the chapter name and the domain label.
        /// Returns the marker start and end offsets.
        internal fun sectionMarkerRange(text: String): Pair<Int, Int>? {
            var best: Pair<Int, Int>? = null
            val wordMarkers = listOf(" Part ", " Domain ", " Stage ")
            for (marker in wordMarkers) {
                var from = 0
                while (true) {
                    val found = text.indexOf(marker, from)
                    if (found < 0) break
                    val afterStart = found + marker.length
                    val digits = text.substring(afterStart).takeWhile { it.isDigit() }
                    if (digits.isNotEmpty() && text.substring(afterStart + digits.length).startsWith(":")) {
                        val full = Pair(found, afterStart + digits.length + 1)
                        if (best == null || full.first < best.first) best = full
                        break
                    }
                    from = afterStart
                }
            }
            // Domain-code marker: a space, "D", digits, a space.
            var from = 0
            while (true) {
                val found = text.indexOf(" D", from)
                if (found < 0) break
                val afterStart = found + 2
                val digits = text.substring(afterStart).takeWhile { it.isDigit() }
                if (digits.isNotEmpty() && text.substring(afterStart + digits.length).startsWith(" ")) {
                    val full = Pair(found, afterStart + digits.length + 1)
                    if (best == null || full.first < best.first) best = full
                    break
                }
                from = afterStart
            }
            return best
        }
    }
}

/// The search index document, with per-type counts and all entries.
@Serializable
data class SearchIndex(
    val counts: SearchCounts,
    val entries: List<SearchEntry>
)

@Serializable
data class SearchCounts(
    val lesson: Int,
    val guide: Int,
    val situation: Int,
    val lab: Int,
    val test: Int,
    val faq: Int,
    val total: Int
)

// MARK: - Chapters and domains

/// A chapter in the curriculum. Chapters are grouped into domains.
/// Derived from the search index by ContentRepository.chapters().
data class Chapter(
    val id: String,
    val number: Int,
    val title: String,
    val domain: String,
    val lessonCount: Int,
    val lessonIds: List<String>
)

// MARK: - Lesson detail

/// One timed beat of a lesson: a section of the narration with a title
/// and a short note. Times are seconds into the lesson narration audio.
/// The lesson diagram highlights the beat that matches the current
/// playback time.
data class LessonBeat(
    val id: String,
    val startTime: Double,
    val endTime: Double,
    val title: String,
    val note: String
)

/// The full content of one lesson, loaded from the live lesson page.
data class LessonDetail(
    val id: String,
    val title: String,
    val chapterTitle: String,
    val audioFileName: String,
    /// The full narration text (transcript).
    val transcript: String,
    /// Key takeaways shown on the lesson page.
    val takeaways: List<String>,
    /// The "Do this at work" checklist items.
    val doThisAtWork: List<String>,
    /// Source footnotes, official sources only.
    val sources: List<String>,
    /// The auto-graded quiz questions for this lesson.
    val quiz: List<QuizQuestion>,
    /// The next lesson in the chapter, if any.
    val nextLessonId: String?,
    /// The timed beats of the lesson narration, in playback order.
    /// Beat times are seconds into the narration audio, with later
    /// scenes offset by the duration of the earlier scenes.
    val beats: List<LessonBeat>,
    /// The lesson diagram SVG from the lesson page, if the lesson has one.
    /// Kept in the model ONLY as the flow-lesson signal; the app never
    /// renders it.
    val diagramSvg: String?,
    /// Total narration length in seconds, summed across scenes.
    val durationSec: Double,
    /// The title of the next lesson, if the page names one.
    val nextLessonTitle: String?,
    /// The quiz intro line from the lesson page.
    val quizIntro: String
)

/// One auto-graded quiz question.
data class QuizQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val correctOptionIndex: Int,
    val explanation: String
)

// MARK: - Situations

/// A Payment Situation. The site has ContentManifest.situationCount, across 6 learning stages
/// and 3 levels (foundation, core, advanced). The full case (full
/// explain text and visual) comes from the live situations data file;
/// the search index carries only a short excerpt, so a Situation built
/// from the index has no visual.
/// [level] is the lowercase level string from the data file:
/// "foundation", "core" or "advanced".
data class Situation(
    val id: String,
    val title: String,
    val stage: Int,
    val stageName: String,
    val level: String,
    val explain: String,
    val takeaway: String,
    val isFreeSample: Boolean,
    /// True when [stage] and [stageName] came from the live situations
    /// data file itself (its `stage` and `stage_name` fields), which
    /// is authoritative. False when the stage was derived (from the
    /// website stage map, from the level fallback, or from a text
    /// hint in the search index entry). Derived stages are never
    /// presented as authoritative.
    val isStageAuthoritative: Boolean,
    /// The situation visual (flow, diagram or stacked), when the full
    /// data file supplied one. Null for index-only situations.
    /// Trailing defaulted field: construction with the nine core
    /// fields is unaffected.
    val visual: SituationVisual? = null
) {
    /// Display name for the level: "Foundation", "Core" or "Advanced".
    val levelDisplayName: String
        get() = when (level.lowercase()) {
            "foundation" -> "Foundation"
            "advanced" -> "Advanced"
            else -> "Core"
        }
}

/// The visual for one situation, as shipped in the live situations
/// data file. Only the branch matching [type] is present.
@Serializable
data class SituationVisual(
    val type: String = "",
    val flow: SituationFlow? = null,
    val diagram: SituationDiagram? = null,
    val stacked: SituationStacked? = null
)

/// A flow visual: ordered steps, each a step or a decision.
@Serializable
data class SituationFlow(
    val stepSeconds: Double? = null,
    val steps: List<SituationFlowStep> = emptyList()
)

@Serializable
data class SituationFlowStep(
    /// "step" or "decision". Anything else renders as a plain step.
    val kind: String? = null,
    val title: String = "",
    val note: String? = null
)

/// A labelled diagram: one centre box with its parts below it.
@Serializable
data class SituationDiagram(
    val centre: String = "",
    val parts: List<SituationDiagramPart> = emptyList()
)

@Serializable
data class SituationDiagramPart(
    val label: String = "",
    val note: String? = null
)

/// A stacked comparison: segments sized by value, normalised to 100.
@Serializable
data class SituationStacked(
    val segments: List<SituationStackedSegment> = emptyList(),
    val unit: String? = null
)

@Serializable
data class SituationStackedSegment(
    val label: String = "",
    val note: String? = null,
    val value: Double = 0.0
)

// MARK: - Products

/// The kind of product: one of the 10 book parts, a standalone
/// experience, or the everything bundle. Scopes decide what a
/// product unlocks; only PART products unlock chapters.
enum class ProductScope {
    PART,
    STUDIO,
    SIMULATOR,
    INVESTIGATION_LAB,
    PRACTICE,
    INTERVIEW,
    INTEGRATED
}

/// One buyable course product. A part's chapters are the chapters
/// it unlocks; standalone products have no chapters and unlock
/// their own experience instead. Product IDs follow
/// com.paymentstraininghub.app.<product-id>, and each product's renewal
/// subscription id is the purchase id plus ".renewal".
/// [priceInr] is the SALE reference price and [listPriceInr] is
/// the LIST reference price. Both are reference data only and are
/// NEVER shown as a purchase price: the only price a buyer ever
/// sees is the Play Billing product price once it has loaded, and
/// Play products carry the sale price only. The list price is
/// website display only and is never shown in this app.
/// [renewalPriceInr] is the renewal reference price, half of the
/// sale price. [validityDays] is 365 for every product: 1 year of
/// access from purchase, renewed for 1 more year by the renewal
/// subscription.
data class CourseProduct(
    val id: String,
    val productId: String,
    val name: String,
    val tagline: String,
    val description: String,
    /// Who the course is for, in plain words. Derived only from
    /// the catalogue description and the course agenda; shown on
    /// the course cards and the Courses page.
    val audience: String,
    val priceInr: Int,
    val listPriceInr: Int,
    val renewalPriceInr: Int,
    val chapterIds: List<String>,
    val includesSituations: Boolean,
    val scope: ProductScope,
    val validityDays: Int = 365
) {
    /// The Play subscription product id that renews this product
    /// for 1 more year: the purchase id plus ".renewal".
    val renewalProductId: String get() = "$productId.renewal"
}
