package io.spark.k8s.submit

import io.spark.k8s.submit.api.ApiPaths
import io.spark.k8s.submit.api.servlet.{HealthServlet, MetricsServlet, SparkSubmitServlet}
import io.spark.k8s.submit.metrics.{SparkSubmitMetrics, SparkSubmitMetricsFilter}
import io.spark.k8s.submit.service.{KubernetesClientProvider, SparkSubmitter}
import io.spark.k8s.submit.tls.{CertReloadFilter, CertReloadWatcher, TlsContextLoader}
import jakarta.servlet.DispatcherType
import org.slf4j.LoggerFactory
import org.sparkproject.jetty.server.handler.HandlerList
import org.sparkproject.jetty.server.{AbstractConnectionFactory, HttpConnectionFactory, Server, ServerConnector}
import org.sparkproject.jetty.servlet.{FilterHolder, ServletContextHandler, ServletHolder}
import org.sparkproject.jetty.util.thread.QueuedThreadPool

import java.util

/**
 * HTTP/HTTPS server entry point for the Spark Submit REST API.
 *
 * Dual-port architecture:
 *  - API port (default 8080): /spark-submit — HTTP or HTTPS depending on TLS config
 *  - Probe port (default 8081): /health, /metrics — always plain HTTP
 *
 * TLS modes (configured via environment variables):
 *  - TLS disabled: both ports plain HTTP
 *  - TLS enabled: API port serves HTTPS; mTLS when CA cert is provided
 *  - Cert reload: optionally checks for cert file changes on incoming requests
 */
object SparkSubmitServer {

  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val server = createServer()
    registerShutdownHook(server)

    try {
      server.start()
      printBanner()
      server.join()
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
      s"""  | certPath .......... ${ServerConfig.Tls.certPath}
         |  | keyPath ........... ${ServerConfig.Tls.keyPath}
         |  | caCertPath ........ ${ServerConfig.Tls.caCertPath.getOrElse("(not set)")}
         |  | certReload ........ $reload
         |  | checkInterval ..... ${ServerConfig.Tls.certCheckIntervalMs}ms
         |  | hashVerify ........ ${ServerConfig.Tls.certVerifyWithHash}""".stripMargin
    else
      "  | (no TLS configuration)"

    log.info(
      s"""
         |  ============================================================
         |  ::  Spark Submitter on Kubernetes  ::  Started
         |  ============================================================
         |  | apiPort ........... ${ServerConfig.Server.port} ($scheme)
         |  | probePort ......... ${ServerConfig.Server.probePort} (http)
         |  | $security
         |  ------------------------------------------------------------
         |$tlsDetails
         |  ============================================================
         |""".stripMargin)
  }

  private def registerShutdownHook(server: Server): Unit =
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      log.info("Shutting down SparkSubmitServer...")
      try {
        server.stop(); log.info("Server stopped")
      }
      catch {
        case e: Exception => log.error("Error during shutdown", e)
      }
    }))

  private def createServer(): Server = {
    val server = new Server(createThreadPool())

    val metrics = new SparkSubmitMetrics()
    val submitter = new SparkSubmitter(new KubernetesClientProvider())

    val (apiConnector, certReloadWatcher) = createApiConnector(server)
    server.addConnector(apiConnector)
    server.addConnector(createProbeConnector(server))

    val handlers = new HandlerList()
    handlers.addHandler(createApiContext(metrics, submitter, certReloadWatcher))
    handlers.addHandler(createProbeContext(metrics))
    server.setHandler(handlers)
    server.setStopTimeout(ServerConfig.Server.shutdownTimeoutMs)
    server
  }

  private def createThreadPool(): QueuedThreadPool = {
    val pool = new QueuedThreadPool(
      ServerConfig.Server.maxThreads,
      ServerConfig.Server.minThreads,
      ServerConfig.Server.threadIdleTimeoutMs.toInt
    )
    pool.setName("jetty-spark-submitter")
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

      val watcher = Option.when(ServerConfig.Tls.certReloadEnabled){
        new CertReloadWatcher(sslContextFactory, certPath, keyPath, caCertPath,
          ServerConfig.Tls.certCheckIntervalMs, ServerConfig.Tls.certVerifyWithHash)
      }

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
                                certReloadWatcher: Option[CertReloadWatcher]): ServletContextHandler = {
    val context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS)
    context.setContextPath(ApiPaths.Base)
    context.setVirtualHosts(Array("@api", "@api-tls"))

    // Cert reload check filter — first in chain, sub-nanosecond when debounced
    certReloadWatcher.foreach { watcher =>
      context.addFilter(new FilterHolder(new CertReloadFilter(watcher)), "/*", util.EnumSet.of(DispatcherType.REQUEST))
    }

    context.addFilter(new FilterHolder(new SparkSubmitMetricsFilter(metrics)), "/*", util.EnumSet.of(DispatcherType.REQUEST))

    context.addServlet(new ServletHolder(new SparkSubmitServlet(submitter)), ApiPaths.SparkSubmit)
    context
  }

  private def createProbeContext(metrics: SparkSubmitMetrics): ServletContextHandler = {
    val context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS)
    context.setContextPath(ApiPaths.Base)
    context.setVirtualHosts(Array("@probes"))

    context.addServlet(new ServletHolder(new MetricsServlet(metrics)), ApiPaths.Metrics)
    context.addServlet(new ServletHolder(new HealthServlet), ApiPaths.Health)
    context
  }
}
