package com.mondns.app

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit

object KeystoreManager {

    private val bc: BouncyCastleProvider by lazy {
        val provider = BouncyCastleProvider()
        try {
            Security.removeProvider(provider.name)
        } catch (_: Exception) {}
        Security.addProvider(provider)
        provider
    }

    data class GenerateResult(
        val file: File,
        val alias: String
    )

    data class LoadedKey(
        val privateKey: PrivateKey,
        val certChain: List<X509Certificate>
    )

    fun generate(
        outputFile: File,
        alias: String,
        storePassword: CharArray,
        keyPassword: CharArray = storePassword,
        commonName: String = "MonToolkit",
        organizationName: String? = null,
        organizationalUnit: String? = null,
        locality: String? = null,
        state: String? = null,
        countryCode: String? = null,
        validityYears: Int = 25,
        keySize: Int = 2048
    ): GenerateResult {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(keySize)
        }.generateKeyPair()

        val now = System.currentTimeMillis()
        val notBefore = Date(now - TimeUnit.DAYS.toMillis(1))
        val notAfter = Date(now + TimeUnit.DAYS.toMillis(365L * validityYears))

        val subjectBuilder = X500NameBuilder(BCStyle.INSTANCE).apply {
            addRDN(BCStyle.CN, commonName)
            if (!organizationName.isNullOrBlank()) addRDN(BCStyle.O, organizationName)
            if (!organizationalUnit.isNullOrBlank()) addRDN(BCStyle.OU, organizationalUnit)
            if (!locality.isNullOrBlank()) addRDN(BCStyle.L, locality)
            if (!state.isNullOrBlank()) addRDN(BCStyle.ST, state)
            if (!countryCode.isNullOrBlank()) addRDN(BCStyle.C, countryCode)
        }
        val subject: X500Name = subjectBuilder.build()
        val serial = BigInteger(160, SecureRandom())

        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )

        val signer = JcaContentSignerBuilder("SHA256WithRSA")
            .setProvider(bc)
            .build(keyPair.private)

        val cert: X509Certificate = JcaX509CertificateConverter()
            .setProvider(bc)
            .getCertificate(certBuilder.build(signer))

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry(alias, keyPair.private, keyPassword, arrayOf<Certificate>(cert))

        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { out ->
            keyStore.store(out, storePassword)
        }

        return GenerateResult(outputFile, alias)
    }

    fun loadPkcs12(
        file: File,
        storePassword: CharArray,
        alias: String? = null,
        keyPassword: CharArray = storePassword
    ): LoadedKey {
        val keyStore = KeyStore.getInstance("PKCS12")
        FileInputStream(file).use { input ->
            keyStore.load(input, storePassword)
        }

        val resolvedAlias = alias
            ?: keyStore.aliases().asSequence().firstOrNull { keyStore.isKeyEntry(it) }
            ?: throw IllegalStateException("No key entry found inside this keystore")

        val privateKey = keyStore.getKey(resolvedAlias, keyPassword) as? PrivateKey
            ?: throw IllegalStateException("Entry '$resolvedAlias' is not a private key")

        val chain: List<X509Certificate> = keyStore.getCertificateChain(resolvedAlias)
            ?.map { it as X509Certificate }
            ?: listOf(keyStore.getCertificate(resolvedAlias) as X509Certificate)

        return LoadedKey(privateKey, chain)
    }
}