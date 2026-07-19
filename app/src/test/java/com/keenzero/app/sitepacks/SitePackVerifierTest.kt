package com.keenzero.app.sitepacks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class SitePackVerifierTest {
    @Test
    fun acceptsValidSignatureAndRejectsChangedPayload() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val payload = "strict-pack-payload".toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(payload)
            sign()
        }
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
        val encodedSignature = Base64.getEncoder().encodeToString(signature)

        assertTrue(SitePackVerifier.verify(publicKey, payload, encodedSignature))
        assertFalse(SitePackVerifier.verify(publicKey, "changed".toByteArray(), encodedSignature))
    }
}
