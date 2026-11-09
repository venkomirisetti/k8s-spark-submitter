package io.spark.k8s.submit.tls

import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

class TlsContextLoaderTest extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private var tempDir: Path = _
  private var certFile: Path = _
  private var keyFile: Path = _
  private var caCertFile: Path = _

  override def beforeAll(): Unit = {
    tempDir = Files.createTempDirectory("tls-test")

    val (cf, kf, _) = TestCertGenerator.writeCertAndKey(tempDir, "tls.crt", "tls.key")
    certFile = cf
    keyFile = kf

    val caKeyPair = TestCertGenerator.generateKeyPair()
    val caCert = TestCertGenerator.generateSelfSignedCert(caKeyPair, "CN=TestCA")
    caCertFile = tempDir.resolve("ca.crt")
    Files.write(caCertFile, TestCertGenerator.encodeCertPem(caCert).getBytes)
  }

  override def afterAll(): Unit = {
    Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder())
      .forEach(Files.deleteIfExists(_))
  }

  test("loadCertificateChain parses PEM certificate") {
    val certs = TlsContextLoader.loadCertificateChain(certFile.toString)
    certs should have size 1
    certs.head.getSubjectX500Principal.getName should include("localhost")
  }

  test("loadPrivateKey parses PEM RSA private key") {
    val key = TlsContextLoader.loadPrivateKey(keyFile.toString)
    key should not be null
    key.getAlgorithm shouldBe "RSA"
  }

  test("buildKeyStore creates a valid keystore from PEM files") {
    val ks = TlsContextLoader.buildKeyStore(certFile.toString, keyFile.toString)
    ks.containsAlias("server") shouldBe true
    ks.isKeyEntry("server") shouldBe true
  }

  test("buildTrustStore creates a valid truststore from CA PEM") {
    val ts = TlsContextLoader.buildTrustStore(caCertFile.toString)
    ts.containsAlias("ca-0") shouldBe true
    ts.isCertificateEntry("ca-0") shouldBe true
  }

  test("createSslContextFactory creates factory for server TLS") {
    val factory = TlsContextLoader.createSslContextFactory(
      certFile.toString, keyFile.toString, None)
    factory should not be null
    factory.getNeedClientAuth shouldBe false
  }

  test("createSslContextFactory creates factory with mTLS when CA cert provided") {
    val factory = TlsContextLoader.createSslContextFactory(
      certFile.toString, keyFile.toString, Some(caCertFile.toString))
    factory should not be null
    factory.getNeedClientAuth shouldBe true
  }

  test("createSslContextFactory fails for missing cert file") {
    val ex = intercept[Exception] {
      TlsContextLoader.createSslContextFactory("/nonexistent/cert.pem", keyFile.toString, None)
    }
    ex.getMessage should include("cert.pem")
  }

  test("createSslContextFactory fails for missing key file") {
    val ex = intercept[Exception] {
      TlsContextLoader.createSslContextFactory(certFile.toString, "/nonexistent/key.pem", None)
    }
    ex.getMessage should include("key.pem")
  }

  test("createSslContextFactory fails for missing CA cert file") {
    val ex = intercept[Exception] {
      TlsContextLoader.createSslContextFactory(
        certFile.toString, keyFile.toString, Some("/nonexistent/ca.pem"))
    }
    ex.getMessage should include("ca.pem")
  }
}
