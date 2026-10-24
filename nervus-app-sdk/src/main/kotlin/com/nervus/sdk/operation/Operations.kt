// This file is the SDK side of long tasks (Operations): the Provider completes asynchronously, the caller waits synchronously.
//
// # The gap it fills
//
// When a method declares returns_operation = true in method_meta, nervud builds the Operation before Dispatch
// and puts the operation_id in the ExecutionContext. The Provider should reply
// ACCEPTED at once (not holding that IPC request) and then report progress and the final state through
// the three methods of operation.control.
//
// Before this, neither side of the SDK could do that:
//
//	Provider side: ServiceStub could only build STATUS_CODE_OK, with no way to reply ACCEPTED;
//	            DispatchContext didn't carry execution_context either, so there was no operation_id.
//	Caller side: after ACCEPTED it had to decode the OperationHandle itself, subscribe to OperationChanged
//	         with operation_id as the scope, then pick the final state out of the events. Every developer had to
//	         rewrite all of that, and a mistake in any step showed up as "the task never finishes".
//
// This file turns each side into one call.
//
// # Why the caller side subscribes instead of polling
//
// OperationChanged is DELIVERY_CLASS_RELIABLE and [deliberately not coalesced] (see the note in the proto:
// coalescing could let a later event overwrite the final state, and no event follows the final state). So
// subscribing is the reliable way to get the final state. Polling GetOperation means picking an interval yourself: too often wastes work,
// too rarely adds half an interval of delay to every long task for nothing.
package com.nervus.sdk.operation

import io.github.nervusos.iface.operation.v1.AcceptOperationRequest
import io.github.nervusos.iface.operation.v1.CompleteOperationRequest
import io.github.nervusos.iface.operation.v1.OperationEvent
import io.github.nervusos.iface.operation.v1.OperationEventKind
import io.github.nervusos.iface.operation.v1.OperationState
import io.github.nervusos.iface.operation.v1.ReportProgressRequest
import io.github.nervusos.ipc.v1.StatusCode

/**
 * Interface ID and method IDs of `nervus.interface.operation.control`.
 *
 * The values come from the `OperationControlMethod` enum in
 * `nervus-ipc/proto/nervus/interface/operation/v1/operation.proto`, and **the proto wins**. Hand-written constants rather
 * than the generated enum, for the same reason as the launcher's PkgManagerMethods: the generated enum reads awkwardly, and if the two
 * ever disagree the call gets a precise NOT_FOUND instead of silently calling the wrong method.
 */
object OperationControl {
    const val INTERFACE_ID = "nervus.interface.operation.control"

    const val GET_OPERATION = 1
    const val CANCEL_OPERATION = 2
    const val ACCEPT_OPERATION = 3
    const val REPORT_PROGRESS = 4
    const val COMPLETE_OPERATION = 5

    /** event_id of OperationChanged. When subscribing, set scope to the operation_id. */
    const val EVENT_OPERATION_CHANGED = 1
}

/**
 * The Provider's "this is a long task, I'll report back later" marker to nervud.
 *
 * When a handler returns this, [com.nervus.sdk.runtime.ServiceStub] builds
 * `DispatchResult{Success{code = ACCEPTED}}` instead of the usual OK.
 *
 * # Why a type rather than a boolean on the annotation
 *
 * `returnsOperation = true` on the annotation could say the same thing, but then a method declared as a long task
 * [could still] accidentally return a protobuf message, and whether that means OK or ACCEPTED
 * would depend on implementation details. Expressed as a return type, "I'll complete asynchronously" is visible at compile time.
 *
 * # The payload must be empty
 *
 * This marker deliberately carries no data: for an operation that's ACCEPTED the kernel requires
 * `Success.payload` to be empty and treats a non-empty one as a Provider violation (see nervud's
 * `operationAcceptedResponse`: non-empty means the Provider tried to make its own handle,
 * which would give the caller a number nervud doesn't know, and cancel and subscribe would silently fail).
 * The real handle is taken from the ExecutionContext by nervud and wrapped into an OperationHandle.
 */
class OperationPending private constructor() {
    companion object {
        /** The single instance. Stateless, so no need to create one each time. */
        val INSTANCE = OperationPending()
    }
}

/**
 * The final state of a long task.
 *
 * @property state the final state: SUCCEEDED / FAILED / CANCELLED.
 * @property code the final StatusCode recorded by nervud.
 * @property result the typed result payload on success; its type is the method's response_type.
 * @property errorDetail the typed error detail on failure; its type is the method's error_detail_type.
 */
data class OperationOutcome(
    val state: OperationState,
    val code: StatusCode,
    val result: ByteArray,
    val errorDetail: ByteArray,
) {
    /** Whether it ended in SUCCEEDED. */
    val isSuccess: Boolean get() = state == OperationState.OPERATION_STATE_SUCCEEDED

    // A data class compares ByteArray by reference in its generated equals; compare contents instead.
    // A result type that's "equal in content but not equal" is very hard to track down in tests
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OperationOutcome) return false
        return state == other.state &&
            code == other.code &&
            result.contentEquals(other.result) &&
            errorDetail.contentEquals(other.errorDetail)
    }

    override fun hashCode(): Int {
        var h = state.hashCode()
        h = 31 * h + code.hashCode()
        h = 31 * h + result.contentHashCode()
        h = 31 * h + errorDetail.contentHashCode()
        return h
    }
}

/**
 * Decides whether an OperationEvent is a final-state event; if so, projects it to an [OperationOutcome].
 *
 * No event follows the final state, so the caller should stop watching once it gets one.
 *
 * Only KIND_STATE counts: in KIND_PROGRESS, state is "the current state before any transition" (progress doesn't change
 * state), and using it as a state test would misjudge progress during RUNNING.
 */
fun OperationEvent.terminalOutcome(): OperationOutcome? {
    if (kind != OperationEventKind.OPERATION_EVENT_KIND_STATE) return null
    if (!state.isTerminal()) return null
    return OperationOutcome(
        state = state,
        code = terminalCode,
        result = terminalResult.toByteArray(),
        errorDetail = terminalError.toByteArray(),
    )
}

/**
 * Whether this is a final state.
 *
 * CANCEL_REQUESTED is [not] final: physical motion takes time to stop, and the Provider still sends
 * CANCELLED (or a SUCCEEDED / FAILED that got there first). Treating it as final would make the caller think it's all over
 * while the machine is still moving.
 */
fun OperationState.isTerminal(): Boolean = when (this) {
    OperationState.OPERATION_STATE_SUCCEEDED,
    OperationState.OPERATION_STATE_FAILED,
    OperationState.OPERATION_STATE_CANCELLED -> true

    else -> false
}

// ---- Helpers that build the Provider's report payloads ---------------------
//
// These only assemble protobuf; actually sending is done by NervusApp / NervusService's
// acceptOperation / reportProgress / completeOperation (which hold the connection).

/**
 * Builds the AcceptOperation payload.
 *
 * **Must be called before completeOperation**: the state machine doesn't allow PENDING straight to SUCCEEDED
 * (see nervud's `canTransition`: a real success always goes through RUNNING). Progress is likewise only valid
 * during RUNNING / CANCEL_REQUESTED.
 *
 * @param motionEpoch the current motion epoch for motion tasks; 0 for non-motion tasks.
 */
fun acceptPayload(operationId: Long, motionEpoch: Long = 0): ByteArray =
    AcceptOperationRequest.newBuilder()
        .setOperationId(operationId)
        .setMotionEpoch(motionEpoch)
        .build()
        .toByteArray()

/** Builds the ReportProgress payload. The type of progress is set by the method's progress contract. */
fun progressPayload(operationId: Long, progress: ByteArray): ByteArray =
    ReportProgressRequest.newBuilder()
        .setOperationId(operationId)
        .setProgress(com.google.protobuf.ByteString.copyFrom(progress))
        .build()
        .toByteArray()

/**
 * Builds a CompleteOperation payload that ends in success.
 *
 * # "The user said no" goes here too
 *
 * A confirmation screen where the user pressed cancel is [a confirmation that finished normally, with the answer "no"], not a failure and
 * not a cancellation: it should end with OK and put "the user didn't agree" in a field of the result
 * (such as ConfirmInstallResult.accepted = false).
 *
 * There are two reasons. Semantically, a failure code would leave the caller unable to tell "the user said no" from "the confirmation screen crashed",
 * and the two deserve completely different reactions in the UI. Mechanically, the CANCELLED final state requires going through
 * CANCEL_REQUESTED first, which only happens when [the caller] cancels: the user pressing cancel in a dialog
 * and the caller withdrawing its request are two different things, and merging them into one final state would make them impossible to separate.
 */
fun completeSuccessPayload(operationId: Long, result: ByteArray = ByteArray(0)): ByteArray =
    CompleteOperationRequest.newBuilder()
        .setOperationId(operationId)
        .setCode(StatusCode.STATUS_CODE_OK)
        .setResult(com.google.protobuf.ByteString.copyFrom(result))
        .build()
        .toByteArray()

/**
 * Builds a CompleteOperation payload that ends in failure.
 *
 * code must be a failure reason a Provider may legitimately produce. nervud normalises any code outside that
 * to INTERNAL, fail-closed, and audits it as a Provider contract violation;
 * the allowlist is nervud's `providerFailCode`:
 *
 *	INTERNAL / INVALID_ARGUMENT / FAILED_PRECONDITION /
 *	DEADLINE_EXCEEDED / UNAVAILABLE / RESOURCE_EXHAUSTED / NOT_FOUND
 *
 * In particular PERMISSION_DENIED and UNAUTHENTICATED are [not] on the list: authorisation decisions can only
 * come from nervud, and a Provider claiming one is a violation.
 */
fun completeFailurePayload(
    operationId: Long,
    code: StatusCode,
    errorDetail: ByteArray = ByteArray(0),
): ByteArray =
    CompleteOperationRequest.newBuilder()
        .setOperationId(operationId)
        .setCode(code)
        .setErrorDetail(com.google.protobuf.ByteString.copyFrom(errorDetail))
        .build()
        .toByteArray()

// ---- The operation context of the current dispatch ----------------------------

/**
 * Lets an `@Method` handler get the operation_id of the long task this call belongs to.
 *
 * # Why such an "implicit" channel
 *
 * nervud puts the operation_id in the ExecutionContext with the Dispatch, while the handler's
 * signature is "take a request message, return a response message", with no parameter to carry the value.
 *
 * All three alternatives are worse: an extra Long parameter on every long-task method would break the one-to-one
 * match between the method signature and the proto's request_type (reflection decodes payloads by parameter type, so an extra parameter
 * needs its own special case); making handlers implement an interface to receive the context would force every implementation
 * to inherit an SDK type; putting operation_id in the request message would mix kernel facts with
 * caller payload in the same field space.
 *
 * A ThreadLocal fits here: one Dispatch runs the handler synchronously on one thread,
 * and what happens after the handler returns [OperationPending] (a dialog, an install) is usually on another
 * thread, so [take the id inside the handler body]; don't expect it to still be there in a callback.
 */
object CurrentOperation {

    private val holder = ThreadLocal<Long>()

    /** This call's operation_id; 0 for methods that aren't long tasks. */
    val id: Long get() = holder.get() ?: 0L

    /**
     * Binds operation_id to the current thread while [block] runs.
     *
     * It must be cleared in finally: thread pools reuse threads, and a leftover id would let the next [non-long-task]
     * call see somebody else's operation_id, and completing with that id would end
     * a long task that isn't its own.
     */
    internal fun <T> with(operationId: Long, block: () -> T): T {
        val previous = holder.get()
        holder.set(operationId)
        try {
            return block()
        } finally {
            if (previous == null) holder.remove() else holder.set(previous)
        }
    }
}

/**
 * The operation_id of the long task this call belongs to; 0 for methods that aren't long tasks.
 *
 * Only valid [while an `@Method` handler runs synchronously]. To use it in an async callback, read it first in the handler
 * body into a local variable:
 *
 * ```kotlin
 * @Method(id = 1)
 * fun confirmInstall(req: ConfirmInstallRequest): OperationPending {
 *     val opId = currentOperationId()   // must be read here
 *     acceptOperation(opId)
 *     showDialogAsync { accepted -> completeOperation(opId, ...) }
 *     return OperationPending.INSTANCE
 * }
 * ```
 */
fun currentOperationId(): Long = CurrentOperation.id

/**
 * The current caller's identity, for `@Method` handlers to read inside the method body.
 *
 * # Why it's needed
 *
 * Some methods depend on "who is calling" to be correct. `ConfirmInstall` on
 * `nervus.interface.permission.ui` is one: the request carries a `handoff_package_id` (the package that owns the hand-off directory),
 * and the confirmation screen [must] check that it matches the caller's identity, or one package could make the dialog read an install
 * package another one prepared and pass its own install off as theirs.
 *
 * The caller's identity is a **trusted fact** nervud gives in `Dispatch.caller`, not something the request
 * claims. But the handler signature only has room for the request message, with no parameter for it; it's in the same
 * position as [CurrentOperation], so it uses the same approach.
 *
 * # Why not change the handler signature
 *
 * Letting `@Method` methods take an extra `CallerContext` parameter would mean every handler's
 * argument resolution first checks "is the last parameter a CallerContext", tangled up with protobuf
 * message parsing rules (`resolveArguments` already guesses by type).
 * Most methods don't care about the caller; adding an ambiguous rule for everyone for a few methods isn't worth it.
 */
object CurrentCaller {

    private val holder = ThreadLocal<io.github.nervusos.ipc.v1.CallerContext>()

    /**
     * This call's caller identity; the default instance (empty package_id) outside a dispatch context.
     *
     * [An empty package_id must be treated as "unknown caller" and refused], never as "trusted local":
     * a check that depends on the caller's identity and lets things through when it can't get one is no check at all.
     */
    val context: io.github.nervusos.ipc.v1.CallerContext
        get() = holder.get() ?: io.github.nervusos.ipc.v1.CallerContext.getDefaultInstance()

    /** The caller's package_id; an empty string when unknown. */
    val packageId: String get() = context.packageId

    internal fun <T> with(caller: io.github.nervusos.ipc.v1.CallerContext, block: () -> T): T {
        val previous = holder.get()
        holder.set(caller)
        try {
            return block()
        } finally {
            if (previous == null) holder.remove() else holder.set(previous)
        }
    }
}

/**
 * This call's caller identity, supplied by the kernel.
 *
 * Only valid [while an `@Method` handler runs synchronously], the same position as [currentOperationId]
 * (a ThreadLocal). To use it in an async callback, read it first in the handler body into a local
 * variable:
 *
 * ```kotlin
 * @Method(id = 3)
 * fun requestPermission(req: RequestPermissionRequest): OperationPending {
 *     val caller = currentCaller().packageId   // must be read here
 *     val opId = currentOperationId()
 *     showDialogAsync(caller) { … }
 *     return OperationPending.INSTANCE
 * }
 * ```
 *
 * **An empty package_id must be refused as "unknown caller"**, never treated as "trusted local": a check that
 * depends on the caller's identity and lets things through when it can't get one is no check at all.
 */
fun currentCaller(): io.github.nervusos.ipc.v1.CallerContext = CurrentCaller.context
