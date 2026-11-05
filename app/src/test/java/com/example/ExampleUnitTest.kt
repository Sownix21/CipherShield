package com.example

import android.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.Security

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {

    @Test
    fun testShieldAndUnshieldRoundTrip_CSv3() {
        val plaintext = "vless://96b1b9d4-8d4e-4f11-92b0-8c9e5e31e5f0@secure-server.com:443?encryption=none&security=tls#CipherShieldServer"
        val password = "SuperSecurePassword123!".toCharArray()

        // Encrypt (Shield)
        val payload = CipherShieldEngine.shield(plaintext, password)
        assertNotNull(payload)
        assertTrue(payload.startsWith("Q1N2M")) // Base64 encoding of "CSv3" prefix

        // Detected Version
        val detectedVersion = CipherShieldEngine.detectVersion(payload)
        assertEquals("CSv3", detectedVersion)

        // Decrypt (Unshield)
        val decrypted = CipherShieldEngine.unshield(payload, password)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun testUnshieldWithWrongPassword_Fails() {
        val plaintext = "sensitive vless connection"
        val password = "correct_password".toCharArray()
        val wrongPassword = "wrong_password".toCharArray()

        val payload = CipherShieldEngine.shield(plaintext, password)

        try {
            CipherShieldEngine.unshield(payload, wrongPassword)
            fail("Should have thrown security/integrity exception for incorrect password")
        } catch (e: Exception) {
            // Expected exception
            assertTrue(e is SecurityException || e is IllegalArgumentException)
        }
    }

    @Test
    fun testArbitraryUtf8TextFormatsRoundTripExactly() {
        val plaintext = """
            [Interface]
            PrivateKey = vpn-secret+/=
            Address = 10.0.0.2/32

            {"sms":"سلام 👋\nLine two","enabled":true}
            vless://user@example.com:443?security=tls#東京
            spaces preserved:   end   
            symbols: \u0000 © € 🔐
        """.trimIndent()
        val password = "Long-Random-Test-Key-2026!".toCharArray()

        val payload = CipherShieldEngine.shield(plaintext, password)

        assertEquals(plaintext, CipherShieldEngine.unshield(payload, password))
    }

    @Test
    fun testFreshSaltAndIvProduceDifferentCiphertext() {
        val password = "Long-Random-Test-Key-2026!".toCharArray()
        val first = CipherShieldEngine.shield("same text", password)
        val second = CipherShieldEngine.shield("same text", password)

        assertNotEquals(first, second)
        assertEquals("same text", CipherShieldEngine.unshield(first, password))
        assertEquals("same text", CipherShieldEngine.unshield(second, password))
    }

    @Test
    fun testTamperingFailsAuthentication() {
        val password = "Long-Random-Test-Key-2026!".toCharArray()
        val decoded = Base64.decode(CipherShieldEngine.shield("critical config", password), Base64.DEFAULT)
        decoded[45] = (decoded[45].toInt() xor 1).toByte()
        val tampered = Base64.encodeToString(decoded, Base64.NO_WRAP)

        assertThrows(SecurityException::class.java) {
            CipherShieldEngine.unshield(tampered, password)
        }
    }

    @Test
    fun testAttackerControlledWorkFactorIsRejectedBeforeKdf() {
        val password = "Long-Random-Test-Key-2026!".toCharArray()
        val decoded = Base64.decode(CipherShieldEngine.shield("text", password), Base64.DEFAULT)
        decoded[6] = 0x7F
        decoded[7] = 0xFF.toByte()
        decoded[8] = 0xFF.toByte()
        decoded[9] = 0xFF.toByte()
        val malicious = Base64.encodeToString(decoded, Base64.NO_WRAP)

        assertNull(CipherShieldEngine.detectVersion(malicious))
        assertThrows(IllegalArgumentException::class.java) {
            CipherShieldEngine.unshield(malicious, password)
        }
    }

    @Test
    fun testUnsupportedMetadataAndOversizeInputAreRejected() {
        val password = "Long-Random-Test-Key-2026!".toCharArray()
        val decoded = Base64.decode(CipherShieldEngine.shield("text", password), Base64.DEFAULT)
        decoded[5] = 0x7F
        val unsupported = Base64.encodeToString(decoded, Base64.NO_WRAP)
        assertNull(CipherShieldEngine.detectVersion(unsupported))
        assertThrows(IllegalArgumentException::class.java) {
            CipherShieldEngine.unshield(unsupported, password)
        }

        val oversized = "x".repeat(CipherShieldEngine.MAX_PLAINTEXT_BYTES + 1)
        assertThrows(IllegalArgumentException::class.java) {
            CipherShieldEngine.shield(oversized, password)
        }

        val oversizedPassword = CharArray(CipherShieldEngine.MAX_PASSWORD_CHARS + 1) { 'x' }
        assertThrows(IllegalArgumentException::class.java) {
            CipherShieldEngine.shield("text", oversizedPassword)
        }
    }

    @Test
    fun testBase64ParserAcceptsWrappingButRejectsAmbiguousInput() {
        val password = "Long-Random-Test-Key-2026!".toCharArray()
        val payload = CipherShieldEngine.shield("wrapped payload", password)
        val wrapped = payload.chunked(40).joinToString("\n")

        assertEquals("CSv3", CipherShieldEngine.detectVersion(wrapped))
        assertEquals("wrapped payload", CipherShieldEngine.unshield(wrapped, password))
        assertNull(CipherShieldEngine.detectVersion("$payload!"))
        assertThrows(IllegalArgumentException::class.java) {
            CipherShieldEngine.unshield("$payload!", password)
        }
    }

    @Test
    fun testKeyStrengthEvaluator_Heuristics() {
        // Weak passwords
        assertEquals(KeyStrengthEvaluator.StrengthTier.WEAK, KeyStrengthEvaluator.evaluate("password".toCharArray()))
        assertEquals(KeyStrengthEvaluator.StrengthTier.WEAK, KeyStrengthEvaluator.evaluate("123456".toCharArray()))
        assertEquals(KeyStrengthEvaluator.StrengthTier.WEAK, KeyStrengthEvaluator.evaluate("aaaa".toCharArray()))
        assertEquals(KeyStrengthEvaluator.StrengthTier.WEAK, KeyStrengthEvaluator.evaluate("PasswordPassword1!".toCharArray()))
        assertEquals(KeyStrengthEvaluator.StrengthTier.WEAK, KeyStrengthEvaluator.evaluate("abc12345-Strong!".toCharArray()))
        assertEquals(KeyStrengthEvaluator.StrengthTier.WEAK, KeyStrengthEvaluator.evaluate("Ab1!Ab1!Ab1!Ab1!".toCharArray()))
        
        // Medium
        assertEquals(KeyStrengthEvaluator.StrengthTier.MEDIUM, KeyStrengthEvaluator.evaluate("NormalPass1".toCharArray()))
        
        // Excellent / Strong
        assertEquals(KeyStrengthEvaluator.StrengthTier.EXCELLENT, KeyStrengthEvaluator.evaluate("SuperHardKey!2026@XYZ_".toCharArray()))
    }

    @Test
    fun testGeneratedKeysAlwaysMeetExcellentThreshold() {
        repeat(100) {
            val key = generateHighEntropyKey()
            assertEquals(24, key.length)
            assertEquals(KeyStrengthEvaluator.StrengthTier.EXCELLENT, KeyStrengthEvaluator.evaluate(key.toCharArray()))
        }
    }
}
