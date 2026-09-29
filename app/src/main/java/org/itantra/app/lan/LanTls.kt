package org.itantra.app.lan

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.*
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.*
import javax.security.auth.x500.X500Principal

/** Per-install certificate identity. No export of the private key, external CA or cloud. */
class LanTls(private val alias: String = "itantra-lan-identity-v2") {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    init {
        if (!keyStore.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    // Conscrypt hashes TLS transcripts before invoking the Keystore raw ECDSA signer.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(X500Principal("CN=iTantra local identity"))
                    .setCertificateSerialNumber(BigInteger(120, SecureRandom()).add(BigInteger.ONE))
                    .setCertificateNotBefore(Date(0)).setCertificateNotAfter(Date(4102444800000L)).build())
                generateKeyPair()
            }
        }
    }
    val certificate: X509Certificate get() = keyStore.getCertificate(alias) as X509Certificate
    val id: String get() = LanRules.fingerprint(certificate.encoded)
    fun context(expectedServer: String? = null): SSLContext {
        val keys = object : X509KeyManager {
            override fun getPrivateKey(name: String?) = keyStore.getKey(alias, null) as PrivateKey
            override fun getCertificateChain(name: String?) = arrayOf(certificate)
            override fun getClientAliases(type: String?, issuers: Array<out Principal>?) = if (type == "EC") arrayOf(alias) else null
            override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = if (types?.contains("EC") == true) alias else null
            override fun getServerAliases(type: String?, issuers: Array<out Principal>?) = if (type == "EC") arrayOf(alias) else null
            override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?) = if (type == "EC") alias else null
        }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            private fun verify(chain: Array<out X509Certificate>?) {
                if (chain == null || chain.size != 1) throw CertificateException("Expected one self-signed local identity")
                val certificate = chain[0]
                certificate.checkValidity()
                certificate.verify(certificate.publicKey)
                val key = certificate.publicKey as? ECPublicKey ?: throw CertificateException("Expected EC identity")
                if (key.params.curve.field.fieldSize != 256) throw CertificateException("Unsupported identity strength")
            }
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                verify(chain) // Identity is bound to certificate; admission still requires password/host consent.
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                verify(chain)
                if (expectedServer != null && LanRules.fingerprint(chain!![0].encoded) != expectedServer)
                    throw CertificateException("Host identity changed. Do not enter the group password.")
                // First use is PROVISIONAL: only WELCOME is read. UI code confirmation must precede JOIN.
            }
        }
        return SSLContext.getInstance("TLS").apply { init(arrayOf(keys), arrayOf(trust), SecureRandom()) }
    }
    companion object {
        fun configure(socket: SSLSocket) {
            socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            socket.enabledCipherSuites = socket.supportedCipherSuites.filter {
                it.startsWith("TLS_AES_") || it == "TLS_CHACHA20_POLY1305_SHA256" ||
                    (it.contains("ECDHE_ECDSA") && (it.contains("GCM") || it.contains("CHACHA20")))
            }.toTypedArray()
            socket.soTimeout = 20000; socket.tcpNoDelay = true; socket.keepAlive = true
        }
        fun peerId(socket: SSLSocket) = LanRules.fingerprint(socket.session.peerCertificates.first().encoded)
    }
}
