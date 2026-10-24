package com.nervus.sdk.ipc

import io.github.nervusos.ipc.v1.Failure
import io.github.nervusos.ipc.v1.ResolveEndpointErrorDetail
import io.github.nervusos.ipc.v1.ResolveEndpointReason

/**
 * Renders a [Failure] as one line people can understand.
 *
 * # Why it's needed
 *
 * nervud [doesn't fill] `public_message`: that field may only come from audited templates, and most
 * built-in paths leave it empty. The real detail is in `error_detail`, a typed protobuf.
 *
 * Without this layer, `"${failure.publicMessage} (code=${failure.code})"` renders as
 * a bare
 *
 *     (code=STATUS_CODE_FAILED_PRECONDITION)
 *
 * and on the Resolve path FAILED_PRECONDITION maps to at least four completely different fixes:
 * the interface isn't registered (wait for the service / find out why it isn't up), a version mismatch (change min/maxMajor),
 * the resource wasn't found (change the selector), or the resource is ambiguous (add labels or change the policy).
 * A code alone means trying all four.
 *
 * The detail is right there on the wire; the client was throwing it away.
 */
internal fun describeFailure(failure: Failure): String {
    val parts = mutableListOf<String>()
    decodeReason(failure)?.let { parts += it }
    failure.publicMessage.takeIf { it.isNotBlank() }?.let { parts += it }
    parts += "code=${failure.code}"
    return parts.joinToString(" ")
}

/**
 * Decodes `error_detail`.
 *
 * [The type is decided by the enclosing message, not by the field describing itself]: this is only called on Resolve's
 * failure path, so it decodes [ResolveEndpointErrorDetail]. Another path needs another decoder; no
 * guessing here. Decoding by a type the sender claims is exactly what status.proto keeps guarding against.
 *
 * If it can't be decoded, return null: a newer kernel adding a field shouldn't make the client turn a usable error report
 * into an exception.
 */
private fun decodeReason(failure: Failure): String? {
    if (failure.errorDetail.isEmpty) return null
    return try {
        val detail = ResolveEndpointErrorDetail.parseFrom(failure.errorDetail)
        detail.reason
            .takeIf { it != ResolveEndpointReason.RESOLVE_ENDPOINT_REASON_UNSPECIFIED }
            ?.let { "${it.name}${hintFor(it.name)}" }
    } catch (_: Exception) {
        null
    }
}

/**
 * Attaches "so what do I do" to each reason.
 *
 * These lines are a rare place in this SDK where ops knowledge is written into code. The reason is that their reader
 * is the person with the least context: an app author looking at an enum name from the kernel usually doesn't know
 * whether to change their own manifest or go and check some system service.
 */
private fun hintFor(reason: String): String = when (reason) {
    "RESOLVE_ENDPOINT_REASON_INTERFACE_NOT_FOUND" ->
        " (hint: no service registered this interface; make sure the provider is running, or that it wasn't refused at registration: check nervud's endpoint.RegisterEndpoint audit)"
    "RESOLVE_ENDPOINT_REASON_VERSION_MISMATCH" ->
        " (hint: interface major version mismatch; adjust minMajor/maxMajor in InterfaceRequirement)"
    "RESOLVE_ENDPOINT_REASON_RESOURCE_NOT_FOUND" ->
        " (hint: the selector matches no device; interfaces bound to a resource must give resourceType + labels/role, v2 has no implicit default)"
    "RESOLVE_ENDPOINT_REASON_RESOURCE_AMBIGUOUS" ->
        " (hint: the selector matches several devices; narrow it with labels, or set selectionPolicy to SYSTEM_PREFERRED)"
    else -> ""
}
