package com.kumanda.tv

import android.annotation.SuppressLint
import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Telefonun TV'ye kendini tanittigi kalici istemci sertifikasi. */
@SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
class Identity(context: Context) {
    private val file = File(context.filesDir, "identity.p12")
    private val password = "kumanda".toCharArray()

    private val keyStore: KeyStore by lazy { load() ?: create() }

    val certificate: X509Certificate
        get() = keyStore.getCertificate(ALIAS) as X509Certificate

    val sslContext: SSLContext by lazy {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, password)
        // Android TV kendinden imzali sertifika kullanir; guven eslestirme koduyla saglanir.
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, arrayOf(trustAll), SecureRandom())
        }
    }

    private fun load(): KeyStore? {
        if (!file.exists()) return null
        return try {
            val ks = KeyStore.getInstance("PKCS12")
            file.inputStream().use { ks.load(it, password) }
            if (ks.containsAlias(ALIAS)) ks else null
        } catch (e: Exception) {
            null
        }
    }

    private fun create(): KeyStore {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=atvremote")
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - 24L * 3600 * 1000),
            Date(now + 20L * 365 * 24 * 3600 * 1000),
            name,
            pair.public
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(holder.encoded))
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, pair.private, password, arrayOf(cert))
        file.outputStream().use { ks.store(it, password) }
        return ks
    }

    companion object {
        private const val ALIAS = "atv"
    }
}
