package com.keenzero.app.sitepacks

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object SitePackVerifier {
    fun verify(publicKeyBase64: String, payload: ByteArray, signatureBase64: String): Boolean = try {
        val publicKey = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyBase64)),
        )
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(payload)
            verify(Base64.getDecoder().decode(signatureBase64))
        }
    } catch (_: Exception) {
        false
    }
}
