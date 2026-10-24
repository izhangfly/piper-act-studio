package com.nervus.sdk.ipc.connection

/**
 * The wire protocol versions this SDK can speak.
 *
 * [Deliberately not configurable]. It used to be a default on the NervusClient / NervusServiceHost constructors,
 * changeable by any caller. The protocol version isn't the app's choice: the SDK speaks whatever version
 * the compiled-in Envelope classes describe, and changing this number won't make it understand one more field,
 * it only means misreading the other side after a successful handshake.
 *
 * [The lower bound is 2 too, not 1]. With min = 1 a v1 nervud would handshake successfully,
 * then quietly part ways with us on meaning: v1 allows an empty selector on ResolveEndpoint and implicitly
 * takes {nervus.resource.motion.base, main}, and v2 removed that default. The same code would get different devices on
 * the two kernels, with neither side reporting an error. Better not to connect at all.
 *
 * V2 is not compatible with V1; there is no compatibility layer.
 */
internal const val PROTOCOL_MAJOR_MIN = 2
internal const val PROTOCOL_MAJOR_MAX = 2
internal const val PROTOCOL_MINOR_MAX = 0
