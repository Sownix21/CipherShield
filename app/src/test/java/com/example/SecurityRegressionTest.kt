package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.charset.CharacterCodingException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SecurityRegressionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun clearingOrStartingANewOperationRejectsOldResults() {
        val gate = SensitiveOperationGate()
        val stopped = gate.begin()
        gate.invalidate()
        assertFalse(gate.isCurrent(stopped))
        val previous = gate.begin()
        val current = gate.begin()
        assertFalse(gate.isCurrent(previous))
        assertTrue(gate.isCurrent(current))
    }

    @Test fun legacyCsV2IndependentFixtureDecryptsExactly() {
        // Generated independently using Node crypto (PBKDF2-SHA512 + AES-GCM).
        val fixture = "Q1N2MgIBAAGGoAABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhsc/QIwF4a0TGc+hcfW6zg0+H8QF+Uw+cutYGcVS33fahzm51I="
        assertEquals("Legacy\nسلام 🌿", CipherShieldEngine.unshield(fixture, "Legacy-Fixture-Key!42".toCharArray()))
    }

    @Test fun nonzeroBase64PaddingBitsAreRejected() {
        val password = "Padding-Test-Fixture!42".toCharArray()
        val payload = CipherShieldEngine.shield("a", password)
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val lastSymbol = payload.indexOf('=') - 1
        assertTrue(lastSymbol >= 0)
        val alias = payload.toCharArray().apply { this[lastSymbol] = alphabet[alphabet.indexOf(this[lastSymbol]) or 1] }.concatToString()
        assertNull(CipherShieldEngine.detectVersion(alias))
        assertThrows(IllegalArgumentException::class.java) { CipherShieldEngine.unshield(alias, password) }
    }

    @Test fun malformedSurrogatesAreRejectedRatherThanChanged() {
        assertThrows(CharacterCodingException::class.java) {
            CipherShieldEngine.shield("\uD800", "Unicode-Fixture!42".toCharArray())
        }
    }

    @Test fun byteLimitIsEnforcedForMultibytePlaintext() {
        assertThrows(IllegalArgumentException::class.java) {
            CipherShieldEngine.shield("€".repeat(CipherShieldEngine.MAX_PLAINTEXT_BYTES / 3 + 1), "Size-Fixture!42".toCharArray())
        }
    }

    @Test fun fileImportPreservesWhitespaceAndUnicodeAndRejectsMalformedBytes() {
        val file = File.createTempFile("ciphershield-import", ".txt", context.cacheDir)
        try {
            val expected = "  سلام 🌿\r\n[Interface]\nPrivateKey = abc+/=  \n"
            file.writeBytes(expected.toByteArray(Charsets.UTF_8))
            assertEquals(expected, readBoundedUtf8Text(context, Uri.fromFile(file)))
            assertThrows(IllegalArgumentException::class.java) { readBoundedUtf8Text(context, Uri.fromFile(file), 4) }
            file.writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
            assertThrows(CharacterCodingException::class.java) { readBoundedUtf8Text(context, Uri.fromFile(file)) }
        } finally { file.delete() }
    }

    @Test fun galleryQrBoundsProbeDoesNotDiscardValidImages() {
        val expected = "CipherShield gallery regression ✓"
        val matrix = MultiFormatWriter().encode(expected, BarcodeFormat.QR_CODE, 512, 512,
            mapOf(com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8"))
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(512 * 512) { index -> if (matrix[index % 512, index / 512]) Color.BLACK else Color.WHITE }
        bitmap.setPixels(pixels, 0, 512, 0, 0, 512, 512)
        val file = File.createTempFile("ciphershield-qr", ".png", context.cacheDir)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertEquals(expected, decodeUriToQr(context, Uri.fromFile(file)))
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }
}
