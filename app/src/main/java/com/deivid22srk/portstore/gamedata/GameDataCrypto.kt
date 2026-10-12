package com.deivid22srk.portstore.gamedata

import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Criptografia do pacote de dados (.psenc) — senha do repositório.
 *
 * Formato (sem dependência externa, javax.crypto é parte do Android):
 *
 * ```
 * 0..6    magic "PSENC01" (7 bytes)
 * 7..22   salt   (PBKDF2-HMAC-SHA256, [RepoPackageSpec.iterations] voltas)
 * 23..38  IV     (contador inicial do AES-CTR)
 * 39..    AES-256-CTR do .tar.gz
 * ```
 *
 * A decriptação é FEITA EM STREAMING sobre a CONCATENAÇÃO das partes
 * baixadas: o contador do CTR avança naturalmente pelo fluxo, então o
 * limite de 100 MiB por arquivo do GitHub não vira matemática de offset
 * no app — basta abrir as partes em ordem.
 *
 * O GZIP multi-membro do pacote v3 valida o CRC32 de cada entrada durante
 * a extração (integridade fim-a-fim sem hash externo).
 */
object GameDataCrypto {

    /** Magic do cabeçalho .psenc (7 bytes — "PSENC01"). */
    const val MAGIC: String = "PSENC01"

    /** Tamanho do cabeçalho: 7 (magic) + 16 (salt) + 16 (IV) = 39. */
    const val HEADER_BYTES: Int = 39

    /** Deriva a chave AES-256 da senha (PBKDF2-HMAC-SHA256). */
    fun deriveKey(password: String, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    /** Deriva o verificador da senha (ativação do repositório). */
    fun passwordVerifier(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    /** Compara verificadores em tempo constante. */
    fun verifiersMatch(a: ByteArray, b: String): Boolean {
        val expected = hexToBytes(b)
        return expected.size == a.size && MessageDigest.isEqual(a, expected)
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "hex ímpar" }
        return ByteArray(clean.length / 2) { i ->
            ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
    }

    /**
     * Abre o fluxo DECRIPTADO do pacote completo: [concatParts] é a
     * concatenação das partes em ordem (o arquivo .psenc inteiro). O header
     * é descartado e o restante passa pelo AES-CTR (sem padding) — o stream
     * resultante é exatamente o .tar.gz original.
     */
    fun decryptingStream(concatParts: InputStream, key: SecretKey, iv: ByteArray, headerBytes: Int): InputStream {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
        val afterHeader = HeadSkipStream(concatParts, headerBytes.toLong())
        return CipherInputStream(afterHeader, cipher)
    }

    /** Descarta os primeiros [skip] bytes do fluxo (header do .psenc). */
    private class HeadSkipStream(input: InputStream, private val skip: Long) : FilterInputStream(input) {
        private var remaining = skip

        override fun read(): Int {
            skipHead()
            return super.read()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            skipHead()
            return super.read(b, off, len)
        }

        private fun skipHead() {
            val buf = ByteArray(8 * 1024)
            while (remaining > 0) {
                val want = minOf(buf.size.toLong(), remaining).toInt()
                val n = super.read(buf, 0, want)
                if (n < 0) return
                remaining -= n
            }
        }
    }
}
