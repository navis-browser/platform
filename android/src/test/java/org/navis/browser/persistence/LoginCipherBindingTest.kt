/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.navis.browser.persistence

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class LoginCipherBindingTest {
    @Test
    fun bindingIsDeterministicAndOwnsBothRowIdentities() {
        val expected = LoginCipherBinding.associatedData("guid-a", "https://example.com")

        assertArrayEquals(
            expected,
            LoginCipherBinding.associatedData("guid-a", "https://example.com"),
        )
        assertFalse(
            expected.contentEquals(
                LoginCipherBinding.associatedData("guid-b", "https://example.com"),
            ),
        )
        assertFalse(
            expected.contentEquals(
                LoginCipherBinding.associatedData("guid-a", "https://other.example"),
            ),
        )
    }

    @Test
    fun lengthPrefixesPreventConcatenationAmbiguity() {
        assertFalse(
            LoginCipherBinding.associatedData("a", "bc")
                .contentEquals(LoginCipherBinding.associatedData("ab", "c")),
        )
    }

    @Test
    fun bindingHasVersionedLengthDelimitedFields() {
        val buffer = ByteBuffer.wrap(
            LoginCipherBinding.associatedData("row-guid", "https://example.com:8443"),
        )

        assertEquals("navis.login.v2", buffer.readField())
        assertEquals("row-guid", buffer.readField())
        assertEquals("https://example.com:8443", buffer.readField())
        assertEquals(0, buffer.remaining())
    }

    @Test
    fun aesGcmRejectsCiphertextMovedToAnotherLoginRow() {
        val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        val encrypt = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key)
            updateAAD(LoginCipherBinding.associatedData("guid-a", "https://example.com"))
        }
        val ciphertext = encrypt.doFinal("secret".toByteArray(StandardCharsets.UTF_8))
        val iv = encrypt.iv

        val decrypt = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            updateAAD(LoginCipherBinding.associatedData("guid-b", "https://example.com"))
        }
        assertThrows(AEADBadTagException::class.java) {
            decrypt.doFinal(ciphertext)
        }
    }

    private fun ByteBuffer.readField(): String {
        val bytes = ByteArray(int)
        get(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
}
