package com.nervus.sdk.ipc

import io.github.nervusos.ipc.v1.ControlLeaseErrorDetail
import io.github.nervusos.ipc.v1.ControlLeaseErrorReason
import io.github.nervusos.ipc.v1.Failure
import io.github.nervusos.ipc.v1.StatusCode

/**
 * A refused AcquireControl / ReleaseControl.
 *
 * [reason] is the typed cause nervud attaches (the detail type is fixed by the control plane, so it
 * can be decoded without guessing): a resource held by a person should make an app back off, one
 * held by another AI may be worth waiting for, and SAFETY_LATCHED means nothing moves until a re-arm.
 */
class ControlLeaseException(
    val code: StatusCode,
    val reason: ControlLeaseErrorReason,
    message: String,
) : RuntimeException(message) {
    companion object {
        internal fun from(f: Failure): ControlLeaseException {
            val reason = runCatching {
                if (f.errorDetail.isEmpty) ControlLeaseErrorReason.CONTROL_LEASE_ERROR_REASON_UNSPECIFIED
                else ControlLeaseErrorDetail.parseFrom(f.errorDetail).reason
            }.getOrDefault(ControlLeaseErrorReason.CONTROL_LEASE_ERROR_REASON_UNSPECIFIED)
            val text = reason.name.removePrefix("CONTROL_LEASE_ERROR_REASON_").lowercase().replace('_', ' ')
            return ControlLeaseException(f.code, reason, "control lease refused: $text (code=${f.code})")
        }
    }
}
