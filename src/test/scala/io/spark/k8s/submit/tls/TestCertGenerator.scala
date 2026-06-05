package io.spark.k8s.submit.tls

import java.math.BigInteger
import java.nio.file.{Files, Path}
import java.security.{KeyPair, KeyPairGenerator}
import java.security.cert.X509Certificate
import java.util.Date
import sun.security.x509._

object TestCertGenerator {

  def generateKeyPair(): KeyPair = {
    val gen = KeyPairGenerator.getInstance("RSA")
    gen.initialize(2048)
    gen.generateKeyPair()
  }

  def generateSelfSignedCert(keyPair: KeyPair, dn: String): X509Certificate = {
    val now = System.currentTimeMillis()
    val notBefore = new Date(now)
    val notAfter = new Date(now + 365L * 24 * 60 * 60 * 1000)

    val info = new X509CertInfo()
    info.set(X509CertInfo.VALIDITY, new CertificateValidity(notBefore, notAfter))
    info.set(X509CertInfo.SERIAL_NUMBER, new CertificateSerialNumber(BigInteger.valueOf(now)))
    val owner = new X500Name(dn)
    info.set(X509CertInfo.SUBJECT, owner)
    info.set(X509CertInfo.ISSUER, owner)
    info.set(X509CertInfo.KEY, new CertificateX509Key(keyPair.getPublic))
    info.set(X509CertInfo.VERSION, new CertificateVersion(CertificateVersion.V3))
    val algo = AlgorithmId.get("SHA256withRSA")
    info.set(X509CertInfo.ALGORITHM_ID, new CertificateAlgorithmId(algo))

    val cert = new X509CertImpl(info)
    cert.sign(keyPair.getPrivate, "SHA256withRSA")
    cert
  }

  def encodeCertPem(cert: X509Certificate): String = {
    val encoded = java.util.Base64.getEncoder.encodeToString(cert.getEncoded)
    s"-----BEGIN CERTIFICATE-----\n${encoded.grouped(64).mkString("\n")}\n-----END CERTIFICATE-----\n"
  }

  def encodeKeyPem(keyPair: KeyPair): String = {
    val encoded = java.util.Base64.getEncoder.encodeToString(keyPair.getPrivate.getEncoded)
    s"-----BEGIN PRIVATE KEY-----\n${encoded.grouped(64).mkString("\n")}\n-----END PRIVATE KEY-----\n"
  }

  def writeCertAndKey(dir: Path, certName: String, keyName: String): (Path, Path, KeyPair) = {
    val keyPair = generateKeyPair()
    val cert = generateSelfSignedCert(keyPair, "CN=localhost")
    val certFile = dir.resolve(certName)
    val keyFile = dir.resolve(keyName)
    Files.write(certFile, encodeCertPem(cert).getBytes)
    Files.write(keyFile, encodeKeyPem(keyPair).getBytes)
    (certFile, keyFile, keyPair)
  }
}
