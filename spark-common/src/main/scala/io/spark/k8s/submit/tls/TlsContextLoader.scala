package io.spark.k8s.submit.tls

import org.sparkproject.jetty.util.ssl.SslContextFactory
import org.slf4j.LoggerFactory

import java.io.ByteArrayInputStream
import java.nio.file.{Files, Paths}
import java.security.{KeyFactory, KeyStore}
import java.security.cert.{CertificateFactory, X509Certificate}
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import scala.jdk.CollectionConverters._

/**
 * Builds Jetty SslContextFactory from PEM certificate and key files.
 * Supports server TLS and optional mTLS (client certificate verification).
 * All files are read via symlink-resolved paths for K8s volume mount compatibility.
 */
object TlsContextLoader {

  private val log = LoggerFactory.getLogger(getClass)

  private val SupportedKeyAlgorithms = Seq("RSA", "EC", "Ed25519")

  /**
   * Creates a configured SslContextFactory ready for use with a Jetty ServerConnector.
   *
   * @param certPath   path to PEM certificate chain file
   * @param keyPath    path to PEM private key file (PKCS#8)
   * @param caCertPath optional CA cert path — enables mTLS when provided
   * @return configured SslContextFactory.Server
   */
  def createSslContextFactory(certPath: String, keyPath: String, caCertPath: Option[String]): SslContextFactory.Server = {
    validateFilesExist(certPath, keyPath, caCertPath)

    val factory = new SslContextFactory.Server()

    val keyStore = buildKeyStore(certPath, keyPath)
    factory.setKeyStore(keyStore)
    factory.setKeyStorePassword("")

    caCertPath.foreach { caPath =>
      val trustStore = buildTrustStore(caPath)
      factory.setTrustStore(trustStore)
      factory.setNeedClientAuth(true)
      log.info("mTLS enabled — clients must present a certificate signed by the configured CA")
    }

    factory
  }

  /** Builds an in-memory PKCS12 keystore from PEM cert chain + private key. */
  private[tls] def buildKeyStore(certPath: String, keyPath: String): KeyStore = {
    val certs = loadCertificateChain(certPath)
    val privateKey = loadPrivateKey(keyPath)

    if (certs.isEmpty)
      throw new IllegalArgumentException(s"No certificates found in $certPath")

    val leaf = certs.head
    log.debug(s"Loaded server certificate: subject=${leaf.getSubjectX500Principal}, " +
      s"issuer=${leaf.getIssuerX500Principal}, expires=${leaf.getNotAfter}")

    val ks = KeyStore.getInstance("PKCS12")
    ks.load(null, null)
    ks.setKeyEntry("server", privateKey, Array.emptyCharArray, certs.toArray)
    ks
  }

  /** Builds an in-memory PKCS12 truststore from a PEM CA certificate bundle. */
  private[tls] def buildTrustStore(caCertPath: String): KeyStore = {
    val caCerts = loadCertificateChain(caCertPath)
    if (caCerts.isEmpty)
      throw new IllegalArgumentException(s"No CA certificates found in $caCertPath")

    val ts = KeyStore.getInstance("PKCS12")
    ts.load(null, null)
    caCerts.zipWithIndex.foreach { case (cert, idx) =>
      ts.setCertificateEntry(s"ca-$idx", cert)
      log.debug(s"Loaded CA certificate [$idx]: subject=${cert.getSubjectX500Principal}, expires=${cert.getNotAfter}")
    }
    ts
  }

  /** Parses all X.509 certificates from a PEM file (supports cert chains). */
  private[tls] def loadCertificateChain(path: String): Seq[X509Certificate] = {
    val pemBytes = Files.readAllBytes(Paths.get(path).toRealPath())
    val cf = CertificateFactory.getInstance("X.509")
    cf.generateCertificates(new ByteArrayInputStream(pemBytes))
      .asScala.map(_.asInstanceOf[X509Certificate]).toSeq
  }

  /** Parses a PKCS#8 PEM private key, trying RSA, EC, and Ed25519 algorithms. */
  private[tls] def loadPrivateKey(path: String): java.security.PrivateKey = {
    val pem = new String(Files.readAllBytes(Paths.get(path).toRealPath()))

    if (!pem.contains("BEGIN"))
      throw new IllegalArgumentException(s"$path does not appear to be a PEM file (missing BEGIN header)")

    val base64 = pem.linesIterator
      .filterNot(line => line.startsWith("-----"))
      .mkString

    val keyBytes = try {
      Base64.getDecoder.decode(base64)
    } catch {
      case e: IllegalArgumentException =>
        throw new IllegalArgumentException(s"$path contains invalid Base64 encoding: ${e.getMessage}")
    }

    val spec = new PKCS8EncodedKeySpec(keyBytes)

    SupportedKeyAlgorithms.view
      .flatMap(algo => tryParseKey(algo, spec))
      .headOption
      .getOrElse(throw new IllegalArgumentException(
        s"Unable to parse private key from $path — must be PKCS#8 format (tried: ${SupportedKeyAlgorithms.mkString(", ")})"))
  }

  private def tryParseKey(algorithm: String, spec: PKCS8EncodedKeySpec): Option[java.security.PrivateKey] = {
    try Some(KeyFactory.getInstance(algorithm).generatePrivate(spec))
    catch { case _: Exception => None }
  }

  private def validateFilesExist(certPath: String, keyPath: String, caCertPath: Option[String]): Unit = {
    requireFileExists(certPath, "TLS_CERT_PATH")
    requireFileExists(keyPath, "TLS_KEY_PATH")
    caCertPath.foreach(p => requireFileExists(p, "TLS_CA_CERT_PATH"))
  }

  private def requireFileExists(path: String, envVar: String): Unit = {
    val resolved = Paths.get(path).toRealPath()
    if (!Files.isRegularFile(resolved))
      throw new IllegalArgumentException(s"$envVar: file not found at $path (resolved: $resolved)")
  }
}
