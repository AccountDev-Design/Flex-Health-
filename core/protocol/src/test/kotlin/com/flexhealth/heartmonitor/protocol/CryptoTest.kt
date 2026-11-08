package com.flexhealth.heartmonitor.protocol

import com.flexhealth.heartmonitor.protocol.crypto.CryptoBytes
import com.flexhealth.heartmonitor.protocol.crypto.EcdhKeyPair
import com.flexhealth.heartmonitor.protocol.crypto.Hkdf
import com.flexhealth.heartmonitor.protocol.crypto.PairingInitiator
import com.flexhealth.heartmonitor.protocol.crypto.PairingResponder
import com.flexhealth.heartmonitor.protocol.crypto.SecureChannel
import com.flexhealth.heartmonitor.protocol.crypto.SecurityProtocolException
import com.flexhealth.heartmonitor.protocol.crypto.SessionKeys
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CryptoTest {

    @Test fun hkdfMatchesRfc5869TestCase1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = Hkdf.derive(ikm, salt, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            CryptoBytes.hex(okm),
        )
    }

    @Test fun ecdhAgreementIsSymmetric() {
        val a = EcdhKeyPair.generate(); val b = EcdhKeyPair.generate()
        assertArrayEquals(a.agree(b.publicEncoded), b.agree(a.publicEncoded))
    }

    @Test(expected = SecurityProtocolException::class)
    fun ecdhRejectsGarbageKey() { EcdhKeyPair.generate().agree(ByteArray(91) { 7 }) }

    @Test(expected = SecurityProtocolException::class)
    fun ecdhRejectsReflection() { val a = EcdhKeyPair.generate(); a.agree(a.publicEncoded) }

    private fun pair(): Triple<PairingInitiator, PairingResponder, Pair<String, String>> {
        val w = PairingInitiator("watch-1", "Galaxy Watch6 Classic")
        val t = PairingResponder("tablet-1", "Galaxy Tab S10 FE")
        val commit = t.onHello(w.hello())
        val reveal = t.onNonce(w.onCommit(commit))
        val wCode = w.onReveal(reveal)
        return Triple(w, t, wCode to t.verificationCode!!)
    }

    @Test fun honestPairingProducesMatchingCodesAndKeys() {
        val (w, t, codes) = pair()
        assertEquals(codes.first, codes.second)
        assertEquals(6, codes.first.length)
        t.onConfirm(w.confirm())
        val (result, tRecord) = t.accept()
        val wRecord = w.onResult(result)
        assertEquals(wRecord.pairingId, tRecord.pairingId)
        assertArrayEquals(wRecord.key(), tRecord.key())
        assertEquals("watch-1", tRecord.peerId)
        assertEquals("tablet-1", wRecord.peerId)
        assertFalse("toString no debe filtrar la clave", tRecord.toString().contains(tRecord.keyB64))
    }

    @Test fun tabletCannotAcceptBeforeWatchConfirms() {
        val (_, t, _) = pair()
        try { t.accept(); fail() } catch (e: SecurityProtocolException) { /* ok */ }
    }

    @Test fun tamperedCommitmentIsDetected() {
        val w = PairingInitiator("watch-1", "W")
        val t = PairingResponder("tablet-1", "T")
        val commit = t.onHello(w.hello())
        w.onCommit(commit.copy(commitment = CryptoBytes.b64(ByteArray(32))))
        val reveal = t.onNonce(LanMessageFactory.nonce())
        try { w.onReveal(reveal); fail() } catch (e: SecurityProtocolException) { assertTrue(e.message!!.contains("compromiso")) }
    }

    @Test fun manInTheMiddleYieldsDifferentCodes() {
        // El atacante M hace de tablet frente al reloj y de reloj frente a la tablet.
        val w = PairingInitiator("watch-1", "W"); val t = PairingResponder("tablet-1", "T")
        val mAsTablet = PairingResponder("tablet-1", "T"); val mAsWatch = PairingInitiator("watch-1", "W")
        val wCode = w.onReveal(mAsTablet.onNonce(w.onCommit(mAsTablet.onHello(w.hello()))))
        t.onNonce(mAsWatch.onCommit(t.onHello(mAsWatch.hello())).let { it })
        // Sin poder elegir nonces tras ver los compromisos, los códigos difieren (prob. 1 - 1e-6).
        assertNotEquals(wCode, t.verificationCode)
    }

    @Test fun forgedWatchConfirmationRejected() {
        val (_, t, _) = pair()
        try { t.onConfirm(com.flexhealth.heartmonitor.protocol.LanMessage.PairConfirm(CryptoBytes.b64(ByteArray(32)))); fail() }
        catch (e: SecurityProtocolException) { /* ok */ }
    }

    @Test fun rejectedPairingFailsOnWatch() {
        val (w, t, _) = pair()
        w.confirm()
        try { w.onResult(t.reject("usuario")); fail() } catch (e: SecurityProtocolException) { assertTrue(e.message!!.contains("rechaz")) }
    }

    @Test fun outOfOrderMessagesRejected() {
        val w = PairingInitiator("watch-1", "W")
        try { w.confirm(); fail() } catch (e: IllegalStateException) { /* check() */ } catch (e: SecurityProtocolException) { /* ok */ }
    }

    private fun channels(): Pair<SecureChannel, SecureChannel> {
        val key = CryptoBytes.random(32)
        val cn = CryptoBytes.random(16); val sn = CryptoBytes.random(16)
        val kc = SessionKeys.derive(key, "pid", cn, sn); val ks = SessionKeys.derive(key, "pid", cn, sn)
        assertArrayEquals(kc.clientProof(), ks.clientProof())
        assertFalse(kc.clientProof().contentEquals(kc.serverProof()))
        return SecureChannel.forClient(kc) to SecureChannel.forServer(ks)
    }

    @Test fun secureChannelRoundTripBothDirections() {
        val (c, s) = channels()
        assertEquals("hola", s.open(c.seal("hola".encodeToByteArray()))!!.decodeToString())
        assertEquals("pong", c.open(s.seal("pong".encodeToByteArray()))!!.decodeToString())
    }

    @Test fun secureChannelRejectsReplayTamperAndWrongKey() {
        val (c, s) = channels()
        val m1 = c.seal("uno".encodeToByteArray())
        assertTrue(s.open(m1) != null)
        assertNull("replay", s.open(m1))
        val m2 = c.seal("dos".encodeToByteArray())
        val bytes = CryptoBytes.unb64(m2.payload)!!; bytes[0] = (bytes[0].toInt() xor 1).toByte()
        assertNull("alterado", s.open(m2.copy(payload = CryptoBytes.b64(bytes))))
        assertNull("contador alterado", s.open(m2.copy(counter = m2.counter + 5)))
        val (_, other) = channels()
        assertNull("otra clave", other.open(c.seal("tres".encodeToByteArray())))
        assertNull("base64 inválido", s.open(m2.copy(payload = "%%%")))
    }

    @Test fun differentNoncesGiveDifferentSessionKeys() {
        val key = CryptoBytes.random(32)
        val a = SessionKeys.derive(key, "pid", CryptoBytes.random(16), CryptoBytes.random(16))
        val b = SessionKeys.derive(key, "pid", CryptoBytes.random(16), CryptoBytes.random(16))
        assertFalse(a.clientToServer.contentEquals(b.clientToServer))
    }
}

private object LanMessageFactory {
    fun nonce() = com.flexhealth.heartmonitor.protocol.LanMessage.PairNonce(CryptoBytes.b64(CryptoBytes.random(16)))
}
