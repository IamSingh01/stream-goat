package com.lagradost.cloudstream3.linux

import java.security.KeyStore
import java.security.cert.CertPathValidator
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Live TV only (Settings → Live TV → Play channels with an expired certificate): accepts a server whose certificate
 * is genuine but out of date. The chain must still lead to a trusted authority and match the address (OkHttp checks
 * the name as usual), so a forged or wrong certificate is refused as before. Never used for logins or the app's own
 * connections.
 */
object ExpiredCertTrust : X509TrustManager {
    private val system: X509TrustManager by lazy {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
            .trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        try {
            system.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            if (!validWhenItWasCurrent(chain)) throw e
        }
    }

    /** The chain checks out on the day before its earliest expiry: it's genuine, only out of date. */
    internal fun validWhenItWasCurrent(chain: Array<out X509Certificate>, anchors: Array<X509Certificate> = system.acceptedIssuers): Boolean = runCatching {
        if (chain.isEmpty()) return false
        val now = Date()
        // Only an expired chain qualifies (not one that isn't valid yet, or broken for any other reason).
        if (chain.none { now.after(it.notAfter) }) return false
        val at = Date(chain.minOf { it.notAfter.time } - 60_000)
        if (chain.any { at.before(it.notBefore) }) return false
        val path = CertificateFactory.getInstance("X.509").generateCertPath(chain.toList())
        val params = PKIXParameters(anchors.map { TrustAnchor(it, null) }.toSet()).apply { isRevocationEnabled = false; date = at }
        CertPathValidator.getInstance("PKIX").validate(path, params)
        true
    }.getOrDefault(false)

    val socketFactory by lazy { SSLContext.getInstance("TLS").apply { init(null, arrayOf(this@ExpiredCertTrust), null) }.socketFactory }

    /** [base] that also accepts expired-but-genuine certificates. */
    fun client(base: okhttp3.OkHttpClient): okhttp3.OkHttpClient = base.newBuilder().sslSocketFactory(socketFactory, this).build()
}
