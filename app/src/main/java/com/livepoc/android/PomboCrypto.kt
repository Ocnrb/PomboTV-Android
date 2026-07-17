package com.livepoc.android

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encriptação por password — formato PARTILHADO com o Pombo (github.com/Ocnrb/
 * Pombo) e com o PomboTV WebView: PBKDF2-SHA256 com 310000 iterações →
 * AES-256-GCM; blob Pombo = salt(16)‖iv(12)‖ct. Envelope de wire do PomboTV:
 * [0xC2][blob] aplicado ao PAYLOAD PUBLICADO inteiro (container de records) —
 * 1 operação AES por mensagem, não por record.
 *
 * O KDF é caro DE PROPÓSITO (centenas de ms) → salt FIXO por sessão de emissão
 * + cache por password|salt: deriva-se 1× por password ao emitir e 1× por
 * emissor ao receber. O AES-GCM em si é acelerado por hardware (ARMv8) —
 * custo por mensagem na ordem dos µs.
 */
object PomboCrypto {
    const val MAGIC: Byte = 0xC2.toByte()
    private const val ITERS = 310_000
    private const val OVERHEAD = 1 + 16 + 12
    private val rng = SecureRandom()
    private val sessionSalt = ByteArray(16).also { rng.nextBytes(it) }
    private val keyCache = ConcurrentHashMap<String, SecretKeySpec>()

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun key(pass: String, salt: ByteArray): SecretKeySpec =
        keyCache.getOrPut(pass + "|" + hex(salt)) {
            val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            SecretKeySpec(f.generateSecret(PBEKeySpec(pass.toCharArray(), salt, ITERS, 256)).encoded, "AES")
        }

    /** Pré-deriva a chave da sessão de emissão (no arranque, fora do caminho quente). */
    fun prederive(pass: String) { key(pass, sessionSalt) }

    fun isSealed(data: ByteArray) = data.size > OVERHEAD && data[0] == MAGIC

    fun seal(data: ByteArray, pass: String): ByteArray {
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(pass, sessionSalt), GCMParameterSpec(128, iv))
        val ct = c.doFinal(data)
        val out = ByteArray(OVERHEAD + ct.size)
        out[0] = MAGIC
        System.arraycopy(sessionSalt, 0, out, 1, 16)
        System.arraycopy(iv, 0, out, 17, 12)
        System.arraycopy(ct, 0, out, 29, ct.size)
        return out
    }

    /** null = password errada (autenticação GCM falhou) ou blob inválido. */
    fun open(data: ByteArray, pass: String): ByteArray? = try {
        val salt = data.copyOfRange(1, 17)
        val iv = data.copyOfRange(17, 29)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(pass, salt), GCMParameterSpec(128, iv))
        c.doFinal(data, OVERHEAD, data.size - OVERHEAD)
    } catch (e: Exception) { null }
}
