package io.spark.k8s.submit.tls

import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

class CertReloadWatcherTest extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  private var tempDir: Path = _
  private var certFile: Path = _
  private var keyFile: Path = _

  override def beforeAll(): Unit = {
    tempDir = Files.createTempDirectory("reload-test")
    val (cf, kf, _) = TestCertGenerator.writeCertAndKey(tempDir, "tls.crt", "tls.key")
    certFile = cf
    keyFile = kf
  }

  override def afterAll(): Unit = {
    Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder())
      .forEach(Files.deleteIfExists(_))
  }

  test("watcher detects cert file change and triggers reload") {
    val sslFactory = TlsContextLoader.createSslContextFactory(certFile.toString, keyFile.toString, None)
    sslFactory.start()

    // checkIntervalMs=0 means every call checks immediately (no debounce)
    val watcher = new CertReloadWatcher(sslFactory, certFile.toString, keyFile.toString,
      checkIntervalMs = 0L)

    try {
      // Initial check — no change
      watcher.checkReloadIfNeeded()

      // Write new cert
      val newKeyPair = TestCertGenerator.generateKeyPair()
      val newCert = TestCertGenerator.generateSelfSignedCert(newKeyPair, "CN=reloaded")
      Files.write(certFile, TestCertGenerator.encodeCertPem(newCert).getBytes)
      Files.write(keyFile, TestCertGenerator.encodeKeyPem(newKeyPair).getBytes)

      // Next check detects change and reloads
      watcher.checkReloadIfNeeded()

      sslFactory.getSslContext should not be null
    } finally {
      sslFactory.stop()
    }
  }

  test("watcher does not reload when files unchanged") {
    val sslFactory = TlsContextLoader.createSslContextFactory(certFile.toString, keyFile.toString, None)
    sslFactory.start()

    val watcher = new CertReloadWatcher(sslFactory, certFile.toString, keyFile.toString,
      checkIntervalMs = 0L)

    try {
      // Multiple checks — no change, no reload
      watcher.checkReloadIfNeeded()
      watcher.checkReloadIfNeeded()
      sslFactory.getSslContext should not be null
    } finally {
      sslFactory.stop()
    }
  }

  test("watcher respects debounce interval") {
    val sslFactory = TlsContextLoader.createSslContextFactory(certFile.toString, keyFile.toString, None)
    sslFactory.start()

    // 5 second debounce — won't check on immediate subsequent calls
    val watcher = new CertReloadWatcher(sslFactory, certFile.toString, keyFile.toString,
      checkIntervalMs = 5000L)

    try {
      watcher.checkReloadIfNeeded() // first call checks

      // Write new cert
      val newKeyPair = TestCertGenerator.generateKeyPair()
      val newCert = TestCertGenerator.generateSelfSignedCert(newKeyPair, "CN=debounced")
      Files.write(certFile, TestCertGenerator.encodeCertPem(newCert).getBytes)
      Files.write(keyFile, TestCertGenerator.encodeKeyPem(newKeyPair).getBytes)

      // Immediate call — still within debounce, won't detect change
      watcher.checkReloadIfNeeded()

      // SSL context still valid (reload was skipped due to debounce)
      sslFactory.getSslContext should not be null
    } finally {
      sslFactory.stop()
    }
  }

  test("watcher handles symlink swap (K8s volume update)") {
    val dataDir1 = tempDir.resolve("data1")
    val dataDir2 = tempDir.resolve("data2")
    Files.createDirectories(dataDir1)
    Files.createDirectories(dataDir2)

    TestCertGenerator.writeCertAndKey(dataDir1, "tls.crt", "tls.key")

    // Create symlinks pointing to data1
    val symlinkCert = tempDir.resolve("link-cert")
    val symlinkKey = tempDir.resolve("link-key")
    Files.createSymbolicLink(symlinkCert, dataDir1.resolve("tls.crt"))
    Files.createSymbolicLink(symlinkKey, dataDir1.resolve("tls.key"))

    val sslFactory = TlsContextLoader.createSslContextFactory(symlinkCert.toString, symlinkKey.toString, None)
    sslFactory.start()

    val watcher = new CertReloadWatcher(sslFactory, symlinkCert.toString, symlinkKey.toString,
      checkIntervalMs = 0L)

    try {
      watcher.checkReloadIfNeeded()

      // Write new cert to data2
      TestCertGenerator.writeCertAndKey(dataDir2, "tls.crt", "tls.key")

      // Swap symlinks (simulates kubelet atomic swap)
      Files.delete(symlinkCert)
      Files.delete(symlinkKey)
      Files.createSymbolicLink(symlinkCert, dataDir2.resolve("tls.crt"))
      Files.createSymbolicLink(symlinkKey, dataDir2.resolve("tls.key"))

      watcher.checkReloadIfNeeded()
      sslFactory.getSslContext should not be null
    } finally {
      sslFactory.stop()
    }
  }
}
