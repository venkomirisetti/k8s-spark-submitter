package io.spark.k8s.submit

import io.spark.k8s.submit.api.{ApiPaths, ManagementPaths}
import io.spark.k8s.submit.api.servlet.{LivenessServlet, MetricsServlet, ReadinessServlet, SparkSubmitServlet}
import io.spark.k8s.submit.server.SparkServletContextFactory
import io.spark.k8s.submit.metrics.{SparkSubmitMetrics, SparkSubmitMetricsFilter}
import io.spark.k8s.submit.service.{KubernetesClientProvider, SparkSubmitter}
import io.spark.k8s.submit.tls.{CertReloadFilter, CertReloadWatcher, TlsContextLoader}
import org.slf4j.LoggerFactory
import org.sparkproject.jetty.server.{AbstractConnectionFactory, Handler, HttpConnectionFactory, Server, ServerConnector}
import org.sparkproject.jetty.util.thread.QueuedThreadPool

/**
 * Entry point for the Spark Submit REST API.
 *
 * Runs two servers:
 *  - API server (default port 8080): serves /api/v1/spark-submit over HTTP, or HTTPS when TLS is enabled
 *  - Probe server (default port 8081): serves /livez, /readyz, /healthz and /metrics over HTTP
 *
 * TLS (API server only, configured via environment variables):
 *  - mTLS when a CA cert is provided
 *  - Optional cert reload, checked on incoming requests
 *
 * The API server starts before the probe server and stops before it.
 * /readyz returns 503 whenever the API server is not running.
 */
object SparkSubmitServer {

  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val metrics = new SparkSubmitMetrics()
    val submitter = new SparkSubmitter(new KubernetesClientProvider())

    val apiServer = createApiServer(metrics, submitter)
    val probeServer = createProbeServer(metrics, apiServer)
    registerShutdownHook(apiServer, probeServer)

    try {
      apiServer.start()
      probeServer.start()
      printBanner()
      apiServer.join()
    } catch {
      case e: Exception =>
        log.error("Failed to start server", e)
        System.exit(1)
    }
  }

  private def printBanner(): Unit = {
    val scheme = if (ServerConfig.Tls.enabled) "https" else "http"
    val security = if (ServerConfig.Tls.caCertPath.isDefined) "mTLS .............. enabled"
      else if (ServerConfig.Tls.enabled) "TLS ............... enabled"
      else "TLS ............... disabled"
    val reload = if (ServerConfig.Tls.certReloadEnabled) "enabled" else "disabled"

    val tlsDetails = if (ServerConfig.Tls.enabled)
      s"""#  | certPath .......... ${ServerConfig.Tls.certPath}
          #  | keyPath ........... ${ServerConfig.Tls.keyPath}
          #  | caCertPath ........ ${ServerConfig.Tls.caCertPath.getOrElse("(not set)")}
          #  | certReload ........ $reload
          #  | checkInterval ..... ${ServerConfig.Tls.certCheckIntervalMs}ms
          #  | hashVerify ........ ${ServerConfig.Tls.certVerifyWithHash}""".stripMargin('#')
    else
      "  | (no TLS configuration)"

    log.info(s"""
          #  ============================================================
          #  ::  Spark Submitter on Kubernetes  ::  Started
          #  ============================================================
          #  | apiPort ........... ${ServerConfig.Server.port} ($scheme)
          #  | probePort ......... ${ServerConfig.Server.probePort} (http)
          #  | $security
          #  ------------------------------------------------------------
          #$tlsDetails
          #  ============================================================
          #""".stripMargin('#'))
  }

  private def registerShutdownHook(apiServer: Server, probeServer: Server): Unit =
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      log.info("Shutting down SparkSubmitServer...")
      stopServer("API server", apiServer)
      stopServer("Probe server", probeServer)
    }))

  private def stopServer(name: String, server: Server): Unit =
    try {
      server.stop(); log.info(s"$name stopped")
    }
    catch {
      case e: Exception => log.error(s"Error stopping $name", e)
    }

  private def createApiServer(metrics: SparkSubmitMetrics, submitter: SparkSubmitter): Server = {
    val server = new Server(createApiThreadPool())

    val (apiConnector, certReloadWatcher) = createApiConnector(server)
    server.addConnector(apiConnector)

    server.setHandler(createApiContext(metrics, submitter, certReloadWatcher))
    server.setStopTimeout(ServerConfig.Server.shutdownTimeoutMs)
    server
  }

  private def createProbeServer(metrics: SparkSubmitMetrics, apiServer: Server): Server = {
    val server = new Server(createProbeThreadPool())
    server.addConnector(createProbeConnector(server))
    server.setHandler(createProbeContext(metrics, apiServer))
    server
  }

  private def createApiThreadPool(): QueuedThreadPool = {
    val pool = new QueuedThreadPool(
      ServerConfig.Server.maxThreads,
      ServerConfig.Server.minThreads,
      ServerConfig.Server.threadIdleTimeoutMs.toInt
    )
    pool.setName("spark-submitter-api")
    pool
  }

  /** Jetty's default pool; the SERVER_*_THREADS settings apply to the API server only. */
  private def createProbeThreadPool(): QueuedThreadPool = {
    val pool = new QueuedThreadPool()
    pool.setName("spark-submitter-probes")
    pool
  }

  private def createApiConnector(server: Server): (ServerConnector, Option[CertReloadWatcher]) = {
    if (ServerConfig.Tls.enabled) {
      val certPath = ServerConfig.Tls.certPath
      val keyPath = ServerConfig.Tls.keyPath
      val caCertPath = ServerConfig.Tls.caCertPath

      val sslContextFactory = TlsContextLoader.createSslContextFactory(certPath, keyPath, caCertPath)

      val httpConnectionFactory = new HttpConnectionFactory()
      val connectionFactories = AbstractConnectionFactory.getFactories(sslContextFactory, httpConnectionFactory)

      val connector = new ServerConnector(server, connectionFactories: _*)
      connector.setHost(ServerConfig.Server.address)
      connector.setPort(ServerConfig.Server.port)
      connector.setAcceptQueueSize(ServerConfig.Server.acceptQueueSize)
      connector.setIdleTimeout(ServerConfig.Server.connectionIdleTimeoutMs)
      connector.setName("api-tls")

      val watcher = if (ServerConfig.Tls.certReloadEnabled) {
        Some(new CertReloadWatcher(sslContextFactory, certPath, keyPath, caCertPath,
          ServerConfig.Tls.certCheckIntervalMs, ServerConfig.Tls.certVerifyWithHash))
      } else None

      (connector, watcher)
    } else {
      (createPlainConnector(server, ServerConfig.Server.port, "api"), None)
    }
  }

  private def createProbeConnector(server: Server): ServerConnector =
    createPlainConnector(server, ServerConfig.Server.probePort, "probes")

  private def createPlainConnector(server: Server, port: Int, name: String): ServerConnector = {
    val connector = new ServerConnector(server)
    connector.setHost(ServerConfig.Server.address)
    connector.setPort(port)
    connector.setAcceptQueueSize(ServerConfig.Server.acceptQueueSize)
    connector.setIdleTimeout(ServerConfig.Server.connectionIdleTimeoutMs)
    connector.setName(name)
    connector
  }

  private def createApiContext(
                                metrics: SparkSubmitMetrics,
                                submitter: SparkSubmitter,
                                certReloadWatcher: Option[CertReloadWatcher]): Handler = {
    // Cert reload check filter — first in chain, sub-nanosecond when debounced
    val filters = certReloadWatcher.map(new CertReloadFilter(_)).toSeq :+ new SparkSubmitMetricsFilter(metrics)

    SparkServletContextFactory.create(
      ApiPaths.Base,
      servlets = Seq(ApiPaths.SparkSubmit -> new SparkSubmitServlet(submitter)),
      filters = filters)
  }

  private def createProbeContext(metrics: SparkSubmitMetrics, apiServer: Server): Handler =
    SparkServletContextFactory.create(
      ManagementPaths.Base,
      servlets = Seq(
        ManagementPaths.Metrics -> new MetricsServlet(metrics),
        ManagementPaths.Liveness -> new LivenessServlet,
        ManagementPaths.Health -> new LivenessServlet,
        ManagementPaths.Readiness -> new ReadinessServlet(() => apiServer.isStarted)),
      filters = Nil)
}
