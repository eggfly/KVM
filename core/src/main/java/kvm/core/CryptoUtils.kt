package kvm.core

import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoUtils {
    fun decryptFile() {
        try {
            val sks = SecretKeySpec(Constants.keyBytes, "AES")
            val iv = IvParameterSpec(Constants.ivBytes)
            val cipher: Cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, sks, iv)

            // read file to byte[]
            val `is`: InputStream = FileInputStream("test.enc")
            val baos = ByteArrayOutputStream()
            var b: Int
            while (`is`.read().also { b = it } != -1) {
                baos.write(b)
            }
            val fileBytes: ByteArray = baos.toByteArray()
            val decrypted: ByteArray = cipher.doFinal(fileBytes)
            println(String(decrypted))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}