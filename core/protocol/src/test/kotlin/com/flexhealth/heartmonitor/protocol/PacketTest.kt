package com.flexhealth.heartmonitor.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketTest {
    private val base = HeartRatePacket(
        sessionId = "session-abc123", sequence = 125, device = "SM-R950",
        bpm = 92, timestampMs = 1_794_150_000_000, sensorStatus = SensorStatus.ACTIVE,
        quality = ReadingQuality.HIGH, source = "exercise",
    )

    @Test fun roundTrip() {
        val decoded = PacketCodec.decode(PacketCodec.encode(base))
        assertEquals(base, decoded)
    }

    @Test fun nullBpmIsOmittedAndDecodesAsNull() {
        val status = base.copy(bpm = null, sensorStatus = SensorStatus.OFF_BODY)
        val json = PacketCodec.encode(status).decodeToString()
        assertTrue("bpm no debe serializarse cuando es null", !json.contains("\"bpm\""))
        val decoded = PacketCodec.decode(json.encodeToByteArray())!!
        assertNull(decoded.bpm)
        assertEquals(SensorStatus.OFF_BODY, decoded.sensorStatus)
    }

    @Test fun forwardCompatibleUnknownFieldsAndEnums() {
        val json = """{"protocolVersion":1,"sessionId":"session-abc123","sequence":1,"device":"SM-R950",
            "metric":"heart_rate","bpm":70,"timestampMs":1794150000000,"sensorStatus":"some_future_state",
            "quality":"ultra","newField":{"x":1}}"""
        val p = PacketCodec.decode(json.encodeToByteArray())
        assertNotNull(p)
        assertEquals(SensorStatus.UNKNOWN, p!!.sensorStatus)
        assertEquals(ReadingQuality.UNKNOWN, p.quality)
    }

    @Test fun malformedInputReturnsNull() {
        assertNull(PacketCodec.decode("not json".encodeToByteArray()))
        assertNull(PacketCodec.decode("{}".encodeToByteArray()))
        assertNull(PacketCodec.decode(ByteArray(0)))
        assertNull(PacketCodec.decode(ByteArray(ProtocolConstants.LAN_MAX_FRAME_BYTES + 1) { 'a'.code.toByte() }))
        assertNull(PacketCodec.decode("""{"sessionId":"session-abc123"}""".encodeToByteArray()))
    }

    @Test fun validation() {
        assertEquals(ValidationResult.Valid, PacketValidator.validate(base))
        assertEquals(ValidationResult.Valid, PacketValidator.validate(base.copy(bpm = null)))
        fun reason(p: HeartRatePacket) = (PacketValidator.validate(p) as ValidationResult.Invalid).reason
        assertEquals(ValidationResult.Reason.UNSUPPORTED_VERSION, reason(base.copy(protocolVersion = 99)))
        assertEquals(ValidationResult.Reason.UNSUPPORTED_VERSION, reason(base.copy(protocolVersion = 0)))
        assertEquals(ValidationResult.Reason.BAD_SESSION_ID, reason(base.copy(sessionId = "x")))
        assertEquals(ValidationResult.Reason.BAD_SESSION_ID, reason(base.copy(sessionId = "bad id with spaces")))
        assertEquals(ValidationResult.Reason.BAD_SEQUENCE, reason(base.copy(sequence = -1)))
        assertEquals(ValidationResult.Reason.BAD_DEVICE, reason(base.copy(device = "")))
        assertEquals(ValidationResult.Reason.UNKNOWN_METRIC, reason(base.copy(metric = "spo2")))
        assertEquals(ValidationResult.Reason.BPM_OUT_OF_RANGE, reason(base.copy(bpm = 0)))
        assertEquals(ValidationResult.Reason.BPM_OUT_OF_RANGE, reason(base.copy(bpm = 400)))
        assertEquals(ValidationResult.Reason.BAD_TIMESTAMP, reason(base.copy(timestampMs = 1000)))
    }

    @Test fun lanMessagePolymorphicRoundTrip() {
        val msgs = listOf<LanMessage>(
            LanMessage.Hello(1, "pid", "bm9uY2U=", 123),
            LanMessage.Secure(5, "AAAA"),
            LanMessage.PairResult(false, reason = "no"),
            LanMessage.Error(LanErrorCodes.AUTH_FAILED),
        )
        for (m in msgs) assertEquals(m, LanMessage.decode(LanMessage.encode(m)))
        assertTrue(LanMessage.encode(msgs[0]).contains("\"type\":\"hello\""))
        assertNull(LanMessage.decode("""{"type":"does_not_exist"}"""))
        assertNull(LanMessage.decode("garbage"))
    }

    @Test fun secureBodyRoundTrip() {
        val body = SecureBody.Sample(base)
        assertEquals(body, SecureBody.decode(SecureBody.encode(body)))
        assertNull(SecureBody.decode("{".encodeToByteArray()))
    }
}
