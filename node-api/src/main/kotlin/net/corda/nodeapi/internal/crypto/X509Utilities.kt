package net.corda.nodeapi.internal.crypto

import net.corda.core.CordaOID
import net.corda.core.crypto.Crypto
import net.corda.core.crypto.SignatureScheme
import net.corda.core.crypto.random63BitValue
import net.corda.core.internal.CertRole
import net.corda.core.internal.reader
import net.corda.core.internal.writer
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.*
import net.corda.core.utilities.days
import net.corda.core.utilities.millis
import org.bouncycastle.asn1.*
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.bc.BcX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import org.bouncycastle.util.io.pem.PemReader
import java.io.InputStream
import java.math.BigInteger
import java.nio.file.Path
import java.security.KeyPair
import java.security.PublicKey
import java.security.cert.*
import java.security.cert.Certificate
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import javax.security.auth.x500.X500Principal

object X509Utilities {
    val DEFAULT_IDENTITY_SIGNATURE_SCHEME = Crypto.EDDSA_ED25519_SHA512
    val DEFAULT_TLS_SIGNATURE_SCHEME = Crypto.ECDSA_SECP256R1_SHA256

    // TODO This class is more of a general purpose utility class and as such these constants belong elsewhere
    // Aliases for private keys and certificates.
    const val CORDA_ROOT_CA = "cordarootca"
    const val CORDA_INTERMEDIATE_CA = "cordaintermediateca"
    const val CORDA_CLIENT_TLS = "cordaclienttls"
    const val CORDA_CLIENT_CA = "cordaclientca"

    private val DEFAULT_VALIDITY_WINDOW = Pair(0.millis, 3650.days)

    /**
     * Helper function to return the latest out of an instant and an optional date.
     */
    private fun max(first: Instant, second: Date?): Date {
        return if (second != null && second.time > first.toEpochMilli())
            second
        else
            Date(first.toEpochMilli())
    }

    /**
     * Helper function to return the earliest out of an instant and an optional date.
     */
    private fun min(first: Instant, second: Date?): Date {
        return if (second != null && second.time < first.toEpochMilli())
            second
        else
            Date(first.toEpochMilli())
    }

    /**
     * Helper method to get a notBefore and notAfter pair from current day bounded by parent certificate validity range.
     * @param before duration to roll back returned start date relative to current date.
     * @param after duration to roll forward returned end date relative to current date.
     * @param parent if provided certificate whose validity should bound the date interval returned.
     */
    fun getCertificateValidityWindow(before: Duration, after: Duration, parent: X509Certificate? = null): Pair<Date, Date> {
        val startOfDayUTC = Instant.now().truncatedTo(ChronoUnit.DAYS)
        val notBefore = max(startOfDayUTC - before, parent?.notBefore)
        val notAfter = min(startOfDayUTC + after, parent?.notAfter)
        return Pair(notBefore, notAfter)
    }

    /*
     * Create a de novo root self-signed X509 v3 CA cert.
     */
    @JvmStatic
    fun createSelfSignedCACertificate(subject: X500Principal,
                                      keyPair: KeyPair,
                                      validityWindow: Pair<Duration, Duration> = DEFAULT_VALIDITY_WINDOW): X509Certificate {
        val window = getCertificateValidityWindow(validityWindow.first, validityWindow.second)
        return createCertificate(CertificateType.ROOT_CA, subject, keyPair, subject, keyPair.public, window)
    }

    @Throws(CertPathValidatorException::class)
    fun validateCertificateChain(trustedRoot: X509Certificate, vararg certificates: Certificate) {
        require(certificates.isNotEmpty()) { "Certificate path must contain at least one certificate" }
        val params = PKIXParameters(setOf(TrustAnchor(trustedRoot, null)))
        params.isRevocationEnabled = false
        val certPath = X509CertificateFactory().generateCertPath(*certificates)
        val pathValidator = CertPathValidator.getInstance("PKIX")
        pathValidator.validate(certPath, params)
    }

    /**
     * Helper method to store a .pem/.cer format file copy of a certificate if required for import into a PC/Mac, or for inspection.
     * @param certificate certificate to save.
     * @param file Target file.
     */
    @JvmStatic
    fun saveCertificateAsPEMFile(certificate: X509Certificate, file: Path) {
        JcaPEMWriter(file.writer()).use {
            it.writeObject(certificate)
        }
    }

    /**
     * Helper method to load back a .pem/.cer format file copy of a certificate.
     * @param file Source file.
     * @return The X509Certificate that was encoded in the file.
     */
    @JvmStatic
    fun loadCertificateFromPEMFile(file: Path): X509Certificate {
        return file.reader().use {
            val pemObject = PemReader(it).readPemObject()
            X509CertificateHolder(pemObject.content).run {
                isValidOn(Date())
                toJca()
            }
        }
    }

    /**
     * Build a partial X.509 certificate ready for signing.
     *
     * @param issuer name of the issuing entity.
     * @param subject name of the certificate subject.
     * @param subjectPublicKey public key of the certificate subject.
     * @param validityWindow the time period the certificate is valid for.
     * @param nameConstraints any name constraints to impose on certificates signed by the generated certificate.
     */
    fun createPartialCertificate(certificateType: CertificateType,
                                 issuer: X500Principal,
                                 subject: X500Principal,
                                 subjectPublicKey: PublicKey,
                                 validityWindow: Pair<Date, Date>,
                                 nameConstraints: NameConstraints? = null): X509v3CertificateBuilder {
        val serial = BigInteger.valueOf(random63BitValue())
        val keyPurposes = DERSequence(ASN1EncodableVector().apply { certificateType.purposes.forEach { add(it) } })
        val subjectPublicKeyInfo = SubjectPublicKeyInfo.getInstance(ASN1Sequence.getInstance(subjectPublicKey.encoded))
        val role = certificateType.role

        val builder = JcaX509v3CertificateBuilder(issuer, serial, validityWindow.first, validityWindow.second, subject, subjectPublicKey)
                .addExtension(Extension.subjectKeyIdentifier, false, BcX509ExtensionUtils().createSubjectKeyIdentifier(subjectPublicKeyInfo))
                .addExtension(Extension.basicConstraints, certificateType.isCA, BasicConstraints(certificateType.isCA))
                .addExtension(Extension.keyUsage, false, certificateType.keyUsage)
                .addExtension(Extension.extendedKeyUsage, false, keyPurposes)

        if (role != null) {
            builder.addExtension(ASN1ObjectIdentifier(CordaOID.X509_EXTENSION_CORDA_ROLE), false, role)
        }
        if (nameConstraints != null) {
            builder.addExtension(Extension.nameConstraints, true, nameConstraints)
        }

        return builder
    }

    /**
     * Create a X509 v3 certificate using the given issuer certificate and key pair.
     *
     * @param issuerCertificate The Public certificate of the root CA above this used to sign it.
     * @param issuerKeyPair The KeyPair of the root CA above this used to sign it.
     * @param subject subject of the generated certificate.
     * @param subjectPublicKey subject's public key.
     * @param validityWindow The certificate's validity window. Default to [DEFAULT_VALIDITY_WINDOW] if not provided.
     * @return A data class is returned containing the new intermediate CA Cert and its KeyPair for signing downstream certificates.
     * Note the generated certificate tree is capped at max depth of 1 below this to be in line with commercially available certificates.
     */
    @JvmStatic
    fun createCertificate(certificateType: CertificateType,
                          issuerCertificate: X509Certificate,
                          issuerKeyPair: KeyPair,
                          subject: X500Principal,
                          subjectPublicKey: PublicKey,
                          validityWindow: Pair<Duration, Duration> = DEFAULT_VALIDITY_WINDOW,
                          nameConstraints: NameConstraints? = null): X509Certificate {
        val window = getCertificateValidityWindow(validityWindow.first, validityWindow.second, issuerCertificate)
        return createCertificate(
                certificateType,
                issuerCertificate.subjectX500Principal,
                issuerKeyPair,
                subject,
                subjectPublicKey,
                window,
                nameConstraints
        )
    }

    /**
     * Build and sign an X.509 certificate with the given signer.
     *
     * @param issuer name of the issuing entity.
     * @param issuerSigner content signer to sign the certificate with.
     * @param subject name of the certificate subject.
     * @param subjectPublicKey public key of the certificate subject.
     * @param validityWindow the time period the certificate is valid for.
     * @param nameConstraints any name constraints to impose on certificates signed by the generated certificate.
     */
    fun createCertificate(certificateType: CertificateType,
                          issuer: X500Principal,
                          issuerSigner: ContentSigner,
                          subject: X500Principal,
                          subjectPublicKey: PublicKey,
                          validityWindow: Pair<Date, Date>,
                          nameConstraints: NameConstraints? = null): X509Certificate {
        val builder = createPartialCertificate(certificateType, issuer, subject, subjectPublicKey, validityWindow, nameConstraints)
        return builder.build(issuerSigner).run {
            require(isValidOn(Date()))
            toJca()
        }
    }

    /**
     * Build and sign an X.509 certificate with CA cert private key.
     *
     * @param issuer name of the issuing entity.
     * @param issuerKeyPair the public & private key to sign the certificate with.
     * @param subject name of the certificate subject.
     * @param subjectPublicKey public key of the certificate subject.
     * @param validityWindow the time period the certificate is valid for.
     * @param nameConstraints any name constraints to impose on certificates signed by the generated certificate.
     */
    fun createCertificate(certificateType: CertificateType,
                          issuer: X500Principal,
                          issuerKeyPair: KeyPair,
                          subject: X500Principal,
                          subjectPublicKey: PublicKey,
                          validityWindow: Pair<Date, Date>,
                          nameConstraints: NameConstraints? = null): X509Certificate {
        val signatureScheme = Crypto.findSignatureScheme(issuerKeyPair.private)
        val provider = Crypto.findProvider(signatureScheme.providerName)
        val signer = ContentSignerBuilder.build(signatureScheme, issuerKeyPair.private, provider)
        val builder = createPartialCertificate(certificateType, issuer, subject, subjectPublicKey, validityWindow, nameConstraints)
        return builder.build(signer).run {
            require(isValidOn(Date()))
            require(isSignatureValid(JcaContentVerifierProviderBuilder().build(issuerKeyPair.public)))
            toJca()
        }
    }

    /**
     * Create certificate signing request using provided information.
     */
    private fun createCertificateSigningRequest(subject: X500Principal,
                                                email: String,
                                                keyPair: KeyPair,
                                                signatureScheme: SignatureScheme): PKCS10CertificationRequest {
        val signer = ContentSignerBuilder.build(signatureScheme, keyPair.private, Crypto.findProvider(signatureScheme.providerName))
        return JcaPKCS10CertificationRequestBuilder(subject, keyPair.public).addAttribute(BCStyle.E, DERUTF8String(email)).build(signer)
    }

    fun createCertificateSigningRequest(subject: X500Principal, email: String, keyPair: KeyPair): PKCS10CertificationRequest {
        return createCertificateSigningRequest(subject, email, keyPair, DEFAULT_TLS_SIGNATURE_SCHEME)
    }
}

/**
 * Convert a [X509Certificate] into Bouncycastle's [X509CertificateHolder].
 *
 * NOTE: To avoid unnecessary copying use [X509Certificate] where possible.
 */
fun X509Certificate.toBc() = X509CertificateHolder(encoded)
fun X509CertificateHolder.toJca(): X509Certificate = X509CertificateFactory().generateCertificate(encoded.inputStream())

/**
 * Wraps a [CertificateFactory] to remove boilerplate. It's unclear whether [CertificateFactory] is threadsafe so best
 * so assume this class is not.
 */
class X509CertificateFactory {
    val delegate: CertificateFactory = CertificateFactory.getInstance("X.509")

    fun generateCertificate(input: InputStream): X509Certificate {
        return delegate.generateCertificate(input) as X509Certificate
    }

    fun generateCertPath(certificates: List<Certificate>): CertPath {
        return delegate.generateCertPath(certificates)
    }

    fun generateCertPath(vararg certificates: Certificate): CertPath {
        return delegate.generateCertPath(certificates.asList())
    }
}

enum class CertificateType(val keyUsage: KeyUsage, vararg val purposes: KeyPurposeId, val isCA: Boolean, val role: CertRole?) {
    ROOT_CA(
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyCertSign or KeyUsage.cRLSign),
            KeyPurposeId.id_kp_serverAuth,
            KeyPurposeId.id_kp_clientAuth,
            KeyPurposeId.anyExtendedKeyUsage,
            isCA = true,
            role = null
    ),

    INTERMEDIATE_CA(
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyCertSign or KeyUsage.cRLSign),
            KeyPurposeId.id_kp_serverAuth,
            KeyPurposeId.id_kp_clientAuth,
            KeyPurposeId.anyExtendedKeyUsage,
            isCA = true,
            role = CertRole.INTERMEDIATE_CA
    ),

    NETWORK_MAP(
            KeyUsage(KeyUsage.digitalSignature),
            KeyPurposeId.id_kp_serverAuth,
            KeyPurposeId.id_kp_clientAuth,
            KeyPurposeId.anyExtendedKeyUsage,
            isCA = false,
            role = CertRole.NETWORK_MAP
    ),

    SERVICE_IDENTITY(
            KeyUsage(KeyUsage.digitalSignature),
            KeyPurposeId.id_kp_serverAuth,
            KeyPurposeId.id_kp_clientAuth,
            KeyPurposeId.anyExtendedKeyUsage,
            isCA = false,
            role = CertRole.SERVICE_IDENTITY
    ),

    NODE_CA(
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyCertSign or KeyUsage.cRLSign),
            KeyPurposeId.id_kp_serverAuth,
            KeyPurposeId.id_kp_clientAuth,
            KeyPurposeId.anyExtendedKeyUsage,
            isCA = true,
            role = CertRole.NODE_CA
    ),

    TLS(
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment or KeyUsage.keyAgreement),
            KeyPurposeId.id_kp_serverAuth,
            KeyPurposeId.id_kp_clientAuth,
            KeyPurposeId.anyExtendedKeyUsage,
            isCA = false,
            role = CertRole.TLS
    ),

    // TODO: Identity certs should have tight name constraints on child certificates
    LEGAL_IDENTITY(
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyCertSign),
            KeyPurposeId.id_kp_serverAuth,
            KeyPurposeId.id_kp_clientAuth,
            KeyPurposeId.anyExtendedKeyUsage,
            isCA = true,
            role = CertRole.LEGAL_IDENTITY
    ),

    CONFIDENTIAL_LEGAL_IDENTITY(
            KeyUsage(KeyUsage.digitalSignature),
            KeyPurposeId.id_kp_serverAuth,
            KeyPurposeId.id_kp_clientAuth,
            KeyPurposeId.anyExtendedKeyUsage,
            isCA = false,
            role = CertRole.CONFIDENTIAL_LEGAL_IDENTITY
    )
}

data class CertificateAndKeyPair(val certificate: X509Certificate, val keyPair: KeyPair)

fun <T : Any> SignedDataWithCert<T>.verifiedNetworkMapCert(rootCert: X509Certificate): T {
    require(CertRole.extract(sig.by) == CertRole.NETWORK_MAP) { "Incorrect cert role: ${CertRole.extract(sig.by)}" }
    X509Utilities.validateCertificateChain(rootCert, sig.by, rootCert)
    return verified()
}
