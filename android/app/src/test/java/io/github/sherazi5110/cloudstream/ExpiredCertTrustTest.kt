package io.github.sherazi5110.cloudstream

import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real certificates made with the JDK's keytool: an authority, an expired server certificate it signed, and a stranger's. */
class ExpiredCertTrustTest {
    private val dir = kotlin.io.path.createTempDirectory("certs").toFile()
    private val keytool = File(System.getProperty("java.home"), "bin/keytool").path

    private fun run(vararg args: String, input: String? = null): String {
        val p = ProcessBuilder(listOf(keytool) + args).directory(dir).redirectErrorStream(true).start()
        input?.let { p.outputStream.use { o -> o.write(it.toByteArray()) } }
        val out = p.inputStream.bufferedReader().readText(); p.waitFor(); return out
    }

    private fun ca(name: String) {
        run("-genkeypair", "-keystore", "$name.jks", "-storepass", "secret", "-alias", name, "-keyalg", "RSA", "-keysize", "2048",
            "-dname", "CN=$name", "-ext", "bc:c", "-validity", "3650", "-startdate", "-1000d")
        run("-exportcert", "-keystore", "$name.jks", "-storepass", "secret", "-alias", name, "-file", "$name.crt")
    }

    /** A server certificate signed by [issuer], valid from [start] for [days]. */
    private fun leaf(issuer: String, start: String, days: Int): X509Certificate {
        run("-genkeypair", "-keystore", "leaf.jks", "-storepass", "secret", "-alias", "leaf$start", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=tv.example")
        val csr = run("-certreq", "-keystore", "leaf.jks", "-storepass", "secret", "-alias", "leaf$start")
        File(dir, "leaf.csr").writeText(csr)
        run("-gencert", "-keystore", "$issuer.jks", "-storepass", "secret", "-alias", issuer, "-infile", "leaf.csr", "-outfile", "leaf.crt",
            "-startdate", start, "-validity", days.toString(), "-ext", "san=dns:tv.example")
        return load("leaf.crt")
    }

    private fun load(name: String) = File(dir, name).inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }

    @Test fun acceptsOnlyGenuineExpiredCertificates() {
        ca("authority"); ca("stranger")
        val authority = load("authority.crt")
        val expired = leaf("authority", "-400d", 90)
        assertTrue("genuine but expired", ExpiredCertTrust.validWhenItWasCurrent(arrayOf(expired, authority), arrayOf(authority)))
        // Signed by someone the device doesn't trust: refused, expired or not.
        val forged = leaf("stranger", "-400d", 90)
        assertFalse("untrusted issuer", ExpiredCertTrust.validWhenItWasCurrent(arrayOf(forged, load("stranger.crt")), arrayOf(authority)))
        // Not expired at all: this path never accepts it (the normal check decides).
        val current = leaf("authority", "-10d", 365)
        assertFalse("not expired", ExpiredCertTrust.validWhenItWasCurrent(arrayOf(current, authority), arrayOf(authority)))
        assertFalse("empty", ExpiredCertTrust.validWhenItWasCurrent(emptyArray(), arrayOf(authority)))
    }
}
