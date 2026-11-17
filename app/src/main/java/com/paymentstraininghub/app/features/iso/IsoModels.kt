// Port of ISO20022Messages.swift (iOS). Teaches message structure from the
// ISO 20022 base schema only. Mandatory and optional follow the base schema.
// Scheme usage guides, for example CBPR+ or SEPA, decide what is actually
// mandatory in a live payment; this file states no scheme rules, no scheme
// code lists and no usage mandates. Every sample value is fictional.
// No em dashes.
package com.paymentstraininghub.app.features.iso

/** Whether an element is required by the ISO 20022 base schema. */
enum class IsoRequirement(val shortLabel: String, val label: String) {
    MANDATORY("Mandatory", "Mandatory in base schema"),
    OPTIONAL("Optional", "Optional in base schema")
}

/** The three ISO 20022 business areas covered by this explorer. */
enum class IsoFamily(val displayName: String, val description: String) {
    PAIN(
        "Payment initiation",
        "Messages a customer sends to its bank, and the status reports that come back."
    ),
    PACS(
        "Payments clearing and settlement",
        "Messages banks send to each other to move money, report status, return funds or reverse a payment."
    ),
    CAMT(
        "Cash management",
        "Reports and notifications a bank sends to its customer about account balances and entries."
    )
}

/**
 * One XML element in a message: a block that groups other elements, or a
 * leaf field that carries a value. Blocks carry children; leaves carry an
 * example value.
 */
data class IsoField(
    val id: String,
    val element: String,
    val definition: String,
    val exampleValue: String? = null,
    val usageNote: String,
    val requirement: IsoRequirement,
    val children: List<IsoField> = emptyList()
) {
    val isBlock: Boolean get() = children.isNotEmpty()

    /** This element plus every descendant, in tree order. */
    fun flattened(): List<IsoField> = listOf(this) + children.flatMap { it.flattened() }
}

data class IsoRelationship(
    val relatedMessageID: String,
    val kind: String,
    val note: String
)

/** One common structural validation problem, phrased as a general example. */
data class IsoValidationProblem(val title: String, val explanation: String)

/** One ISO 20022 message taught by the explorer. */
data class IsoMessage(
    val id: String,
    val isoName: String,
    val displayName: String,
    val family: IsoFamily,
    val purpose: String,
    val rootElement: String,
    val sampleXML: String,
    val fields: List<IsoField>,
    val relationships: List<IsoRelationship>
) {
    /** Every element in the field tree, in tree order. */
    fun allFields(): List<IsoField> = fields.flatMap { it.flattened() }
}

data class IsoSharedValue(val label: String, val value: String)

data class IsoJourneyStep(
    val id: String,
    val messageID: String,
    val stepTitle: String,
    val narrative: String,
    val sampleXML: String
)

data class IsoMappingRow(
    val concept: String,
    val pain001: String,
    val pacs008: String,
    val pacs002: String,
    val note: String
)

/** The ten messages covered by this explorer, in teaching order. */
object IsoCatalogue {
    val messages: List<IsoMessage> = IsoCatalogueData.messages
    val commonProblems: List<IsoValidationProblem> = IsoCatalogueData.commonProblems
    val sharedValues: List<IsoSharedValue> = IsoCatalogueData.sharedValues
    val journeySteps: List<IsoJourneyStep> = IsoCatalogueData.journeySteps
    val mappingRows: List<IsoMappingRow> = IsoCatalogueData.mappingRows

    fun message(id: String): IsoMessage? = messages.firstOrNull { it.id.equals(id, ignoreCase = true) }

    /** Field elements across all messages (235), plus the 10 messages: 245 elements. */
    val totalFieldCount: Int get() = messages.sumOf { it.allFields().size }
}
