package io.spark.k8s.submit.tls

import org.slf4j.LoggerFactory
import org.sparkproject.jetty.util.ssl.SslContextFactory

import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Paths}
import java.security.MessageDigest

/**
 * Request-triggered TLS certificate reload with debounce.
 *
 * Detection strategy:
 *  - File modification time as the primary gate (single stat syscall)
 *  - Optional SHA-256 content hash as fallback for K8s symlink swaps that don't update mtime
 *
 * Call [[checkReloadIfNeeded()]] on each incoming request. Returns immediately
 * unless the debounce interval has elapsed since the last filesystem check.
 * Validates new certs before reload — a malformed file won't break the running server.
 *
 * @param sslContextFactory Jetty SSL context to reload when certs change
 * @param certPath          path to PEM certificate chain file (tls.crt)
 * @param keyPath           path to PEM private key file (tls.key)
 * @param caCertPath        optional path to CA cert for mTLS client verification
 * @param checkIntervalMs   minimum time between filesystem checks (default 60 min)
 * @param verifyWithHash    if true, also detect changes via content hash when mtime is unchanged (default false)
 */
class CertReloadWatcher(
                         sslContextFactory: SslContextFactory.Server,
                         certPath: String,
                         keyPath: String,
                         caCertPath: Option[String] = None,
                         checkIntervalMs: Long = 30000L,
                         verifyWithHash: Boolean = false
                       ) {

  private val log = LoggerFactory.getLogger(getClass)

  private val watchedPaths = Seq(certPath, keyPath) ++ caCertPath.toSeq
  @volatile private var lastCheckMs = System.currentTimeMillis()
  @volatile private var lastModificationTimes = getModificationTimes()
  @volatile private var lastHashes = if (verifyWithHash) Some(computeHashes()) else None

  /** Called on each request. Returns immediately unless debounce interval has elapsed. */
  def checkReloadIfNeeded(): Unit = {
    val now = System.currentTimeMillis()
    if (now - lastCheckMs >= checkIntervalMs) {
      lastCheckMs = now
      doCheck()
    }
  }

  /** Checks file modification times (and optionally content hash) and reloads SSL context if changed. */
  private def doCheck(): Unit = {
    try {
      val currentModificationTimes = getModificationTimes()
      val modTimeChanged = currentModificationTimes != lastModificationTimes
      val shouldReload = modTimeChanged || (verifyWithHash && hasContentChanged)

      if (shouldReload) {
        log.info("Certificate file change detected, validating before reload...")
        validateBeforeReload()
        reload()
        lastModificationTimes = currentModificationTimes
        if (verifyWithHash) lastHashes = Some(computeHashes())
        logReloadSuccess()
      }
    } catch {
      case e: Exception => log.warn(s"Certificate reload failed, continuing with current cert: ${e.getMessage}")
    }
  }

  private def hasContentChanged: Boolean = !lastHashes.contains(computeHashes())

  /** Parse cert/key/CA files to ensure they're valid before triggering reload. */
  private def validateBeforeReload(): Unit = {
    TlsContextLoader.loadCertificateChain(certPath)
    TlsContextLoader.loadPrivateKey(keyPath)
    caCertPath.foreach(TlsContextLoader.loadCertificateChain)
  }

  /** Atomically swaps the SSL context — in-flight connections use old cert, new connections use new. */
  private def reload(): Unit = {
    sslContextFactory.reload(scf => {
      val keyStore = TlsContextLoader.buildKeyStore(certPath, keyPath)
      scf.setKeyStore(keyStore)
      scf.setKeyStorePassword("")

      caCertPath.foreach { caPath =>
        val trustStore = TlsContextLoader.buildTrustStore(caPath)
        scf.setTrustStore(trustStore)
      }
    })
  }

  private def logReloadSuccess(): Unit = {
    val certs = TlsContextLoader.loadCertificateChain(certPath)
    certs.headOption.foreach { leaf =>
      log.info(s"Certificate reloaded: subject=${leaf.getSubjectX500Principal}, expires=${leaf.getNotAfter}")
    }
  }

  /** Single stat syscall per file — nanosecond cost. */
  private def getModificationTimes(): Map[String, FileTime] =
    watchedPaths.map(p => (p, Files.getLastModifiedTime(Paths.get(p).toRealPath()))).toMap

  /** Full file read + SHA-256 — only called when mtime changes and verifyWithHash is enabled. */
  private def computeHashes(): Map[String, String] =
    watchedPaths.map(p => (p, computeHash(p))).toMap

  private def computeHash(path: String): String = {
    val bytes = Files.readAllBytes(Paths.get(path).toRealPath())
    MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString
  }
}
