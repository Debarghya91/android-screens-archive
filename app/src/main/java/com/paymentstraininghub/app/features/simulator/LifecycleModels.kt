// Port of PaymentLifecycle.swift (iOS). A simplified educational model of a
// payment journey. It teaches the shape of a journey, not any one bank, scheme
// or rail. Real architectures vary by bank, scheme and rail. All sample data
// is fictional and labelled SAMPLE. No em dashes.
package com.paymentstraininghub.app.features.simulator

/** One stage of the simplified payment lifecycle, in journey order. */
data class LifecycleStage(
    val number: Int,
    val key: String,
    val title: String,
    val explanation: String,
    val incomingEvent: String,
    val processingComponent: String,
    val stateTransition: String,
    val dataTransformation: String,
    val outgoingEvent: String,
    val expectedResult: String,
    val failureOutcome: String
)

/** All stages in journey order. Text lives in LifecycleStageData.kt. */
object LifecycleStages {
    val ordered: List<LifecycleStage> = LIFECYCLE_STAGE_DATA
    fun stage(number: Int): LifecycleStage? = ordered.firstOrNull { it.number == number }
    fun previousOf(stage: LifecycleStage): LifecycleStage? = stage(stage.number - 1)
    fun nextOf(stage: LifecycleStage): LifecycleStage? = stage(stage.number + 1)
}

/** One fictional sample payment. Every name and reference is invented. */
data class SamplePayment(
    val id: String,
    val label: String,
    val payerName: String,
    val payeeName: String,
    val payerAccountReference: String,
    val payeeAccountReference: String,
    val amountText: String,
    val paymentReference: String,
    val purpose: String,
    val channel: String
)

object SamplePaymentCatalogue {
    val samples: List<SamplePayment> = listOf(
        SamplePayment(
            id = "sample-a",
            label = "Sample supplier invoice",
            payerName = "Asha Example (fictional)",
            payeeName = "Northwind Supplies Ltd (fictional)",
            payerAccountReference = "SAMPLE-ACC-1001",
            payeeAccountReference = "SAMPLE-ACC-2001",
            amountText = "1,250.00 sample units",
            paymentReference = "SAMPLE-PAY-001",
            purpose = "Sample invoice payment for training",
            channel = "Sample online banking (fictional)"
        ),
        SamplePayment(
            id = "sample-b",
            label = "Sample payroll style payment",
            payerName = "Example Payroll Services (fictional)",
            payeeName = "Ben Example (fictional)",
            payerAccountReference = "SAMPLE-ACC-1002",
            payeeAccountReference = "SAMPLE-ACC-2002",
            amountText = "3,400.00 sample units",
            paymentReference = "SAMPLE-PAY-002",
            purpose = "Sample payroll style payment for training",
            channel = "Sample business payment file (fictional)"
        ),
        SamplePayment(
            id = "sample-c",
            label = "Sample customer refund",
            payerName = "Example Retail Group (fictional)",
            payeeName = "Cara Example (fictional)",
            payerAccountReference = "SAMPLE-ACC-1003",
            payeeAccountReference = "SAMPLE-ACC-2003",
            amountText = "89.50 sample units",
            paymentReference = "SAMPLE-PAY-003",
            purpose = "Sample customer refund for training",
            channel = "Sample phone channel (fictional)"
        )
    )
    val defaultSample: SamplePayment = samples[0]
}

/** What an injected fault does to the payment journey. */
enum class LifecycleFaultEffect(val title: String, val downstreamWording: String) {
    STOPPED("Payment stops", "These stages do not run."),
    HELD("Payment held for review", "These stages wait until a reviewer decides."),
    WAITING_RETRY("Payment waits, retry may be possible", "These stages wait until the operator knows what happened.")
}

/** One error the learner can inject in Investigation mode. */
enum class LifecycleFault(
    val title: String,
    val description: String,
    val applicableStageNumbers: List<Int>,
    val effect: LifecycleFaultEffect,
    val operatorNextSteps: List<String>,
    val learnerNote: String
) {
    VALIDATION_FAILURE(
        title = "Validation failure",
        description = "A detail is missing or inconsistent, so validation rejects the instruction.",
        applicableStageNumbers = listOf(3),
        effect = LifecycleFaultEffect.STOPPED,
        operatorNextSteps = listOf(
            "Read the rejection reason on the payment record.",
            "Tell the payer which detail to correct.",
            "Wait for a corrected instruction. Do not edit the payer entries yourself."
        ),
        learnerNote = "Validation protects later stages from working on a broken instruction. Failing early is cheaper than failing late."
    ),
    SANCTIONS_HOLD(
        title = "Sanctions hold",
        description = "A possible list match is found, so the payment is held for a compliance review.",
        applicableStageNumbers = listOf(5),
        effect = LifecycleFaultEffect.HELD,
        operatorNextSteps = listOf(
            "Open the screening case and read the match detail.",
            "A compliance reviewer decides: release the payment or escalate it.",
            "Keep the payment held until the reviewer decides."
        ),
        learnerNote = "A hold is not a finding of wrongdoing. It means a person needs to decide before money moves."
    ),
    INSUFFICIENT_FUNDS(
        title = "Insufficient funds",
        description = "The payer account cannot fund the payment in this model, so the payment is rejected.",
        applicableStageNumbers = listOf(7),
        effect = LifecycleFaultEffect.STOPPED,
        operatorNextSteps = listOf(
            "Confirm the rejection reason on the payment record.",
            "Tell the payer the payment could not be funded.",
            "Take no further action on this payment unless the payer sends a new instruction."
        ),
        learnerNote = "Funding is checked before processing, so a payment that cannot be funded never reaches routing or settlement."
    ),
    DUPLICATE_PAYMENT(
        title = "Duplicate payment",
        description = "The payment looks like one already sent, so it is held until an operator confirms whether it is a duplicate.",
        applicableStageNumbers = listOf(3, 8),
        effect = LifecycleFaultEffect.HELD,
        operatorNextSteps = listOf(
            "Find the earlier payment this one resembles.",
            "Confirm with the payer whether this is a second genuine payment.",
            "Release it only when confirmed genuine. Cancel it when confirmed a duplicate."
        ),
        learnerNote = "A duplicate check asks whether this payment already happened. Releasing a real duplicate would move the money twice."
    ),
    DOWNSTREAM_TIMEOUT(
        title = "Downstream timeout",
        description = "The next system does not respond in time, so the stage cannot confirm what happened.",
        applicableStageNumbers = listOf(9, 10, 11),
        effect = LifecycleFaultEffect.WAITING_RETRY,
        operatorNextSteps = listOf(
            "Check whether the downstream system already acted before retrying.",
            "Retry only when it is safe to do so.",
            "Raise an incident when the system stays silent."
        ),
        learnerNote = "A timeout means unknown, not failed. Retrying blindly can run the payment twice, so the operator checks first."
    ),
    CLEARING_REJECTION(
        title = "Clearing rejection",
        description = "Clearing does not accept the payment, so it cannot settle.",
        applicableStageNumbers = listOf(10),
        effect = LifecycleFaultEffect.STOPPED,
        operatorNextSteps = listOf(
            "Read the rejection reason returned by clearing.",
            "Tell the payer the payment was not accepted.",
            "Correct the cause and start a new payment when the payer asks."
        ),
        learnerNote = "When clearing does not accept a payment, the payment cannot settle. The reason is recorded so it can be fixed."
    );

    fun canInjectAt(stage: LifecycleStage): Boolean = stage.number in applicableStageNumbers
}

/** One injected fault, with the stage where it was injected. */
data class LifecycleFaultInjection(val fault: LifecycleFault, val stage: LifecycleStage)

/** The overall outcome of a simulator run. */
enum class LifecycleJourneyOutcome(val title: String, val detail: String) {
    IN_PROGRESS(
        "Run in progress",
        "The run is still going. Keep stepping through the stages, or end the run to see the journey summary."
    ),
    HAPPY_PATH_COMPLETE(
        "Happy path complete",
        "The sample payment passed every stage with no injected fault. Every stage ran in order."
    ),
    STOPPED_BY_FAULT(
        "Payment stopped",
        "An injected fault stopped the payment. The stages after the fault did not run."
    ),
    HELD_BY_FAULT(
        "Payment held for review",
        "An injected fault held the payment for review. The stages after the fault waited for a decision."
    ),
    WAITING_AFTER_FAULT(
        "Payment waiting after a timeout",
        "An injected timeout left the outcome unknown. The stages after the fault waited while the operator checked what happened."
    ),
    ENDED_EARLY(
        "Run ended early",
        "The run was ended before the last stage. Only the stages visited are listed in the summary."
    )
}

/**
 * The record of one simulator run. Session only: nothing here is saved,
 * stored or synced; leaving the screen discards the run.
 */
class LifecycleJourney {
    private val _visitedStages = mutableListOf<LifecycleStage>()
    val visitedStages: List<LifecycleStage> get() = _visitedStages.toList()
    var injection: LifecycleFaultInjection? = null
        private set
    var reachedEnd: Boolean = false
        private set
    var endedEarly: Boolean = false
        private set

    fun visit(stage: LifecycleStage) {
        if (stage !in _visitedStages) _visitedStages.add(stage)
    }

    fun inject(fault: LifecycleFault, at: LifecycleStage) {
        visit(at)
        injection = LifecycleFaultInjection(fault, at)
    }

    /** Clears the injected fault so the learner can keep exploring. */
    fun clearInjection() {
        injection = null
    }

    fun markReachedEnd() {
        reachedEnd = true
    }

    fun markEndedEarly() {
        endedEarly = true
    }

    fun reset() {
        _visitedStages.clear()
        injection = null
        reachedEnd = false
        endedEarly = false
    }

    /** Stages after the fault stage, in journey order. */
    val blockedStages: List<LifecycleStage>
        get() {
            val faultStage = injection?.stage ?: return emptyList()
            return LifecycleStages.ordered.filter { it.number > faultStage.number }
        }

    /** Stages never visited in this run, in journey order. */
    val unvisitedStages: List<LifecycleStage>
        get() = LifecycleStages.ordered.filter { it !in _visitedStages }

    val outcome: LifecycleJourneyOutcome
        get() {
            val fault = injection?.fault
            if (fault != null) {
                return when (fault.effect) {
                    LifecycleFaultEffect.STOPPED -> LifecycleJourneyOutcome.STOPPED_BY_FAULT
                    LifecycleFaultEffect.HELD -> LifecycleJourneyOutcome.HELD_BY_FAULT
                    LifecycleFaultEffect.WAITING_RETRY -> LifecycleJourneyOutcome.WAITING_AFTER_FAULT
                }
            }
            if (reachedEnd) return LifecycleJourneyOutcome.HAPPY_PATH_COMPLETE
            if (endedEarly) return LifecycleJourneyOutcome.ENDED_EARLY
            return LifecycleJourneyOutcome.IN_PROGRESS
        }

    /** True when the run has an ending worth summarising. */
    val hasEnding: Boolean get() = injection != null || reachedEnd || endedEarly
}
