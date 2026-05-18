package com.aria.ariacast.raop

import android.util.Base64
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec

object RaopKeys {
    private const val RAOP_MODULUS_BASE64 =
        "59dE8qLieItsH1WgjrcFRKj6eUWqi+bGLOX1HL3U3GhC/j0Qg90u3sG/1CUtwC" +
        "5vOYvfDmFI6oSFXi5ELabWJmT2dKHzBJKa3k9ok+8t9ucRqMd6DZHJ2YCCLlDR" +
        "KSKv6kDqnw4UwPdpOMXziC/AMj3Z/lUVX1G7WSHCAWKf1zNS1eLvqr+boEjXuB" +
        "OitnZ/bDzPHrTOZz0Dew0uowxf/+sG+NCK3eQJVxqcaJ/vEHKIVd2M+5qL71yJ" +
        "Q+87X6oV3eaYvt3zWZYD6z5vYTcrtij2VZ9Zmni/UAaHqn9JdsBWLUEpVviYnh" +
        "imNVvYFZeCXg/IdTQ+x4IRdiXNv5hEew=="

    fun getPublicKey(): RSAPublicKey {
        val modulusBytes = Base64.decode(RAOP_MODULUS_BASE64, Base64.DEFAULT)
        val modulus = BigInteger(1, modulusBytes)
        val exponent = BigInteger.valueOf(65537)
        val spec = RSAPublicKeySpec(modulus, exponent)
        val factory = KeyFactory.getInstance("RSA")
        return factory.generatePublic(spec) as RSAPublicKey
    }
}
