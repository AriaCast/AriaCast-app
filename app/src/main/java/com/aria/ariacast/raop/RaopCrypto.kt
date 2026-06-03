package com.aria.ariacast.raop

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Security
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class RaopCrypto {
    companion object {
        init {
            Security.addProvider(BouncyCastleProvider())
        }

        // Apple AirPlay RSA Public Key Modulus (2048-bit) as used in airplay2-rs
        private val APPLE_RSA_MODULUS = byteArrayOf(
            0x59.toByte(), 0xdc.toByte(), 0xd5.toByte(), 0x24.toByte(), 0x49.toByte(), 0xba.toByte(), 0x62.toByte(), 0x13.toByte(), 0x2a.toByte(), 0x45.toByte(), 0x67.toByte(), 0x68.toByte(), 0x22.toByte(), 0x5b.toByte(), 0x16.toByte(),
            0x78.toByte(), 0x1a.toByte(), 0xfb.toByte(), 0xce.toByte(), 0xb1.toByte(), 0x20.toByte(), 0x64.toByte(), 0x12.toByte(), 0xd8.toByte(), 0xf5.toByte(), 0x36.toByte(), 0x6a.toByte(), 0x58.toByte(), 0x91.toByte(), 0x6c.toByte(),
            0x2d.toByte(), 0x96.toByte(), 0x57.toByte(), 0x2e.toByte(), 0x1f.toByte(), 0x91.toByte(), 0x02.toByte(), 0x8e.toByte(), 0x60.toByte(), 0x3b.toByte(), 0xe1.toByte(), 0x09.toByte(), 0x00.toByte(), 0x1c.toByte(), 0x32.toByte(),
            0x97.toByte(), 0x01.toByte(), 0x2f.toByte(), 0xb2.toByte(), 0x2b.toByte(), 0x3e.toByte(), 0x21.toByte(), 0x07.toByte(), 0x3d.toByte(), 0x98.toByte(), 0x18.toByte(), 0xa8.toByte(), 0x6e.toByte(), 0x7e.toByte(), 0x17.toByte(),
            0x37.toByte(), 0x6e.toByte(), 0x85.toByte(), 0x36.toByte(), 0x2c.toByte(), 0x71.toByte(), 0xca.toByte(), 0xfe.toByte(), 0x07.toByte(), 0xe4.toByte(), 0x80.toByte(), 0x2a.toByte(), 0x2a.toByte(), 0x23.toByte(), 0x5f.toByte(),
            0xed.toByte(), 0x08.toByte(), 0x6a.toByte(), 0xf3.toByte(), 0x44.toByte(), 0xde.toByte(), 0x02.toByte(), 0x41.toByte(), 0x14.toByte(), 0x59.toByte(), 0x7c.toByte(), 0xda.toByte(), 0x46.toByte(), 0xeb.toByte(), 0x49.toByte(),
            0xd2.toByte(), 0xde.toByte(), 0x3a.toByte(), 0x50.toByte(), 0x20.toByte(), 0xb9.toByte(), 0x39.toByte(), 0x26.toByte(), 0x97.toByte(), 0xfe.toByte(), 0xac.toByte(), 0x7d.toByte(), 0x32.toByte(), 0xb6.toByte(), 0x2b.toByte(),
            0x28.toByte(), 0x01.toByte(), 0x0b.toByte(), 0x84.toByte(), 0x37.toByte(), 0xa0.toByte(), 0x6f.toByte(), 0xcc.toByte(), 0xa3.toByte(), 0x9e.toByte(), 0x39.toByte(), 0x40.toByte(), 0x89.toByte(), 0xce.toByte(), 0x95.toByte(),
            0xce.toByte(), 0xab.toByte(), 0x53.toByte(), 0xec.toByte(), 0x59.toByte(), 0x0f.toByte(), 0x16.toByte(), 0xba.toByte(), 0x96.toByte(), 0x6d.toByte(), 0x3e.toByte(), 0x78.toByte(), 0x76.toByte(), 0x7b.toByte(), 0x3c.toByte(),
            0x7b.toByte(), 0x09.toByte(), 0xaf.toByte(), 0x3e.toByte(), 0xf1.toByte(), 0xbf.toByte(), 0x03.toByte(), 0xa9.toByte(), 0x3e.toByte(), 0xd5.toByte(), 0xe0.toByte(), 0x31.toByte(), 0x65.toByte(), 0xb9.toByte(), 0x28.toByte(),
            0xeb.toByte(), 0xba.toByte(), 0x98.toByte(), 0x78.toByte(), 0xbb.toByte(), 0x85.toByte(), 0x12.toByte(), 0xfa.toByte(), 0x5f.toByte(), 0x6c.toByte(), 0x16.toByte(), 0xba.toByte(), 0x7a.toByte(), 0x23.toByte(), 0x6c.toByte(),
            0x40.toByte(), 0x47.toByte(), 0xf9.toByte(), 0x9a.toByte(), 0xb2.toByte(), 0x83.toByte(), 0xbb.toByte(), 0xb8.toByte(), 0xa5.toByte(), 0xb1.toByte(), 0x20.toByte(), 0x8e.toByte(), 0x0d.toByte(), 0x33.toByte(), 0x06.toByte(),
            0x0a.toByte(), 0x6c.toByte(), 0x2b.toByte(), 0x29.toByte(), 0x09.toByte(), 0xf0.toByte(), 0xc7.toByte(), 0xfb.toByte(), 0xef.toByte(), 0xb3.toByte(), 0x51.toByte(), 0xf6.toByte(), 0x34.toByte(), 0x7a.toByte(), 0xa0.toByte(),
            0xbe.toByte(), 0x8f.toByte(), 0xd5.toByte(), 0xfd.toByte(), 0x6e.toByte(), 0xae.toByte(), 0x2c.toByte(), 0xa7.toByte(), 0x20.toByte(), 0x73.toByte(), 0x8d.toByte(), 0x47.toByte(), 0xd8.toByte(), 0xee.toByte(), 0x85.toByte(),
            0xc3.toByte(), 0xb3.toByte(), 0xbc.toByte(), 0xd3.toByte(), 0xf8.toByte(), 0x7b.toByte(), 0x68.toByte(), 0x52.toByte(), 0x2c.toByte(), 0xce.toByte(), 0xfa.toByte(), 0xc9.toByte(), 0x6f.toByte(), 0x8e.toByte(), 0x0d.toByte(),
            0x6c.toByte(), 0x81.toByte(), 0x0c.toByte(), 0x7c.toByte(), 0x41.toByte(), 0x19.toByte(), 0xc2.toByte(), 0x01.toByte(), 0xac.toByte(), 0x24.toByte(), 0x82.toByte(), 0xc6.toByte(), 0x13.toByte(), 0xa5.toByte(), 0x2c.toByte(),
            0x30.toByte(), 0x38.toByte(), 0x3e.toByte(), 0x49.toByte(), 0xf5.toByte(), 0x20.toByte(), 0x24.toByte(), 0xf8.toByte(), 0xed.toByte(), 0xe1.toByte(), 0x5c.toByte(), 0xfa.toByte(), 0xe0.toByte(), 0x4b.toByte(), 0xf3.toByte(),
            0xbd.toByte()
        )

        fun encryptAesKey(aesKey: ByteArray): ByteArray {
            val spec = RSAPublicKeySpec(BigInteger(1, APPLE_RSA_MODULUS), BigInteger.valueOf(65537))
            val factory = KeyFactory.getInstance("RSA")
            val publicKey = factory.generatePublic(spec)

            // RAOP uses RSA-OAEP with SHA-1
            val cipher = Cipher.getInstance("RSA/None/OAEPWithSHA1AndMGF1Padding", Security.getProvider("BC"))
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            return cipher.doFinal(aesKey)
        }
    }

    private lateinit var secretKeySpec: SecretKeySpec
    private lateinit var ivSpec: IvParameterSpec

    fun initAes(aesKey: ByteArray, iv: ByteArray) {
        secretKeySpec = SecretKeySpec(aesKey, "AES")
        ivSpec = IvParameterSpec(iv)
    }

    /**
     * Encrypts audio data using AES-128-CBC.
     * In RAOP, the IV is reset for every packet.
     */
    fun encryptAudio(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/NoPadding", Security.getProvider("BC"))
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivSpec)
        
        return if (data.size % 16 == 0) {
            cipher.doFinal(data)
        } else {
            val paddedSize = ((data.size + 15) / 16) * 16
            val paddedData = ByteArray(paddedSize)
            System.arraycopy(data, 0, paddedData, 0, data.size)
            cipher.doFinal(paddedData)
        }
    }
}
