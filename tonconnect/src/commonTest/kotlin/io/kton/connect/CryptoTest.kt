package io.kton.connect

import io.kton.connect.crypto.SessionCrypto
import io.kton.connect.crypto.TweetNaCl
import io.kton.connect.crypto.hexToBytes
import io.kton.connect.crypto.sha256
import io.kton.connect.crypto.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoTest {
    @Test
    fun sha256OfAbc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun sha512MatchesTweetNaCl() {
        assertEquals(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            TweetNaCl.sha512("abc".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun x25519PublicKeyMatchesRfc7748() {
        val secret = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".hexToBytes()
        assertEquals(
            "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a",
            TweetNaCl.scalarmultBase(secret).toHex(),
        )
    }

    @Test
    fun secretboxMatchesTweetNaCl() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val nonce = ByteArray(24) { (it + 1).toByte() }
        val boxed = TweetNaCl.secretbox("tonconnect-secretbox".encodeToByteArray(), nonce, key)
        assertEquals("2c8730fba19de7e2a757f21861c977145a404340788fcf3afdef24bc3a88bc7250e7245a", boxed.toHex())
        assertEquals(
            "dfbd817bab5067edf101f65ea6d5e072",
            TweetNaCl.secretbox(ByteArray(0), nonce, key).toHex(),
        )
        assertContentEquals("tonconnect-secretbox".encodeToByteArray(), TweetNaCl.secretboxOpen(boxed, nonce, key))
    }

    @Test
    fun boxMatchesTweetNaCl() {
        val alice = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".hexToBytes()
        val bob = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb".hexToBytes()
        val alicePublic = TweetNaCl.scalarmultBase(alice)
        val bobPublic = TweetNaCl.scalarmultBase(bob)
        val nonce = "69696ee955b62b73cd62bda875fc73d68219e0036b7a0b37".hexToBytes()
        val boxed = TweetNaCl.box("hello ton connect".encodeToByteArray(), nonce, bobPublic, alice)
        assertEquals("c0e674acbf454c507b2850fb60338cd158fb08361bc994c963a220c3b7791fd66e", boxed.toHex())
        assertContentEquals(
            "hello ton connect".encodeToByteArray(),
            TweetNaCl.boxOpen(boxed, nonce, alicePublic, bob),
        )
        val long = ByteArray(100) { 0x61 }
        val longBox = TweetNaCl.box(long, nonce, bobPublic, alice)
        assertEquals(
            "e181c8c2b6f43ab41787b7344ffc727951ff053b158881c76ce322cdb8761bd4" +
                "7b7a8aec3b4e3c116d685d3f34e436f744521ab2ca00fc003601b9a4d345c93a" +
                "7c6f9f6fd9c68f775bda621733fead68dbd467a779805d8616b94d5b88b0c798" +
                "13b57763e6aa9f01de40519d6b0e9765fc6b3deb",
            longBox.toHex(),
        )
        assertNull(TweetNaCl.boxOpen(boxed, nonce, alicePublic, alice))
    }

    @Test
    fun sessionRoundTripUsesNoncePrefix() {
        val alice = SessionCrypto.fromSecretKey("11".repeat(32).hexToBytes())
        val bob = SessionCrypto.fromSecretKey("22".repeat(32).hexToBytes())
        val wire = alice.encrypt("{\"method\":\"disconnect\",\"params\":[],\"id\":\"1\"}", bob.publicKey)
        assertEquals(24 + 16 + 44, wire.size)
        assertEquals(
            "{\"method\":\"disconnect\",\"params\":[],\"id\":\"1\"}",
            bob.decrypt(wire, alice.publicKey),
        )
    }

    @Test
    fun ed25519VerifyMatchesTweetNaCl() {
        val seed = ByteArray(32) { 7 }
        val publicKey = TweetNaCl.signPublicKey(seed)
        assertEquals("ea4a6c63e29c520abef5507b132ec5f9954776aebebe7b92421eea691446d22c", publicKey.toHex())
        val message = "ton-connect".encodeToByteArray()
        val signature = (
            "b78a6890722cdd91e5435f2ac2ca6ba44302154d15777e74257f626e56ef624f" +
                "51fffca6dec9e4f5da4e4700091d441f96cf2adb5688a9cd43c3085ef9bc3c01"
            ).hexToBytes()
        assertTrue(TweetNaCl.signVerify(message, signature, publicKey))
        assertContentEquals(signature, TweetNaCl.signDetached(message, seed))
    }
}
