package org.itantra.app.relay

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.itantra.app.core.RelayPacket
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Team possession authenticates membership, not a person's real identity. Private signing
 * and wrapping keys stay in AndroidKeyStore; the team secret is never placed in diagnostics. */
internal class RelayIdentity(context: Context, namespace: String = "relay") {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val file = AtomicFile(File(context.noBackupFilesDir, "$namespace-team"))
    private val sequence = AtomicFile(File(context.noBackupFilesDir, "$namespace-sequence"))
    private val signingAlias = "itantra-$namespace-signing-v1"
    private val wrappingAlias = "itantra-$namespace-team-wrap-v1"
    private fun signing() {
        if (!store.containsAlias(signingAlias)) KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(signingAlias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKeyPair()
    }
    val publicKey: ByteArray get() { signing(); return store.getCertificate(signingAlias).publicKey.encoded }
    fun sign(bytes: ByteArray): ByteArray {
        signing()
        return Signature.getInstance("SHA256withECDSA").run { initSign(store.getKey(signingAlias, null) as java.security.PrivateKey); update(bytes); sign() }
    }
    @Synchronized fun messageId(): String {
        val previous = if (sequence.baseFile.exists()) sequence.openRead().bufferedReader().use { it.readText().toLong() } else 0L
        val next = Math.addExact(previous, 1L)
        val output = sequence.startWrite()
        try { output.write(next.toString().toByteArray()); sequence.finishWrite(output) }
        catch (error: Exception) { sequence.failWrite(output); throw error }
        return next.toString(16).padStart(16, '0') + RelayPacket.newCode().take(16)
    }
    private fun wrapping(): SecretKey {
        if (!store.containsAlias(wrappingAlias)) KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(wrappingAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
        return store.getKey(wrappingAlias, null) as SecretKey
    }
    fun read(): ByteArray? {
        if (!file.baseFile.exists()) return null
        val bytes = file.openRead().use { it.readBytes() }
        require(bytes.size == 60)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, wrapping(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            doFinal(bytes, 12, bytes.size - 12)
        }
    }
    fun save(code: String): ByteArray {
        val key = RelayPacket.teamKey(code)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapping()) }
        val bytes = cipher.iv + cipher.doFinal(key)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (error: Exception) { file.failWrite(output); throw error }
        return key
    }
}
