package com.nervus.sdk.ipc.connection

import io.github.nervusos.ipc.v1.*

internal data class HandshakeResult(
    val protocolMajor: Int,
    val protocolMinor: Int,
    val limits: ConnectionLimits,
    val packageId: String,
    val componentId: String
)

internal class HelloHandshake(
    private val frameReader: FrameReader,
    private val frameWriter: FrameWriter
) {
    fun negotiate(
        minMajor: Int,
        maxMajor: Int,
        maxMinor: Int,
        sdkName: String,
        sdkVersion: String,
        componentId: String = "",
        timeoutMs: Long = 5000
    ): HandshakeResult {
        val hello = Hello.newBuilder()
            .setMinProtocolMajor(minMajor)
            .setMaxProtocolMajor(maxMajor)
            .setMaxProtocolMinor(maxMinor)
            .setSdkName(sdkName)
            .setSdkVersion(sdkVersion)
            .setDeclaredComponentId(componentId)
            .build()

        frameWriter.writeFrame(Envelope.newBuilder().setHello(hello).build())

        val response = frameReader.readFrame(timeoutMs)

        if (response.bodyCase != Envelope.BodyCase.HELLO_ACK) {
            throw ProtocolViolationException(
                "expected HelloAck, got ${response.bodyCase}"
            )
        }

        val ack = response.helloAck
        return when (ack.outcomeCase) {
            HelloAck.OutcomeCase.SUCCESS -> {
                val success = ack.success
                val chosenMajor = success.protocolMajor
                val chosenMinor = success.protocolMinor

                if (chosenMajor < minMajor || chosenMajor > maxMajor) {
                    throw ProtocolViolationException(
                        "server chose incompatible protocol major $chosenMajor, " +
                        "client supports [$minMajor, $maxMajor]"
                    )
                }

                HandshakeResult(
                    protocolMajor = chosenMajor,
                    protocolMinor = chosenMinor,
                    limits = success.limits,
                    packageId = success.packageId,
                    componentId = success.componentId
                )
            }
            HelloAck.OutcomeCase.FAILURE -> {
                val code = ack.failure.code
                throw HandshakeRejectedException(code, ack.failure.publicMessage)
            }
            HelloAck.OutcomeCase.OUTCOME_NOT_SET -> {
                throw ProtocolViolationException("HelloAck with no outcome")
            }
        }
    }
}

internal class HandshakeRejectedException(
    val statusCode: StatusCode,
    val detail: String
) : Exception("handshake rejected: $statusCode $detail")
