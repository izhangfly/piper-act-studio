package com.nervus.sdk.ipc

import io.github.nervusos.ipc.v1.StatusCode

/**
 * A method call that nervud or the Provider refused. [code] is the only thing to branch on
 * (FAILED_PRECONDITION: not now, fix the state first; UNAVAILABLE: retry later; INVALID_ARGUMENT:
 * fix the request). The message is for logs and people.
 *
 * It is a RuntimeException so code written before it existed keeps catching it.
 */
class NervusCallException(val code: StatusCode, message: String) : RuntimeException(message)
