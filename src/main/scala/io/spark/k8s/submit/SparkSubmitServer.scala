package io.spark.k8s.submit

import io.spark.k8s.submit.api.ApiPaths
import io.spark.k8s.submit.api.servlet.{HealthServlet, MetricsServlet, SparkSubmitServlet}
import io.spark.k8s.submit.metrics.{SparkSubmitMetrics, SparkSubmitMetricsFilter}
import io.spark.k8s.submit.service.{KubernetesClientProvider, SparkSubmitter}
import org.sparkproject.jetty.server.{Server, ServerConnector}
import org.sparkproject.jetty.servlet.{FilterHolder, ServletContextHandler, ServletHolder}
import org.sparkproject.jetty.util.thread.QueuedThreadPool
import org.slf4j.LoggerFactory

import jakarta.servlet.DispatcherType

/**
 * HTTP server entry point for the Spark Submit REST API.
 *
 * Endpoints:
 * - POST /spark-submit              — Submit a Spark driver pod to Kubernetes
 * - POST /spark-submit?dryRun=true  — Validate submission without creating K8s resources
 * - GET  /health                    — Liveness/readiness probe
 * - GET  /metrics                   — Prometheus metrics
 *
 * Fire-and-forget: returns as soon as the driver pod is created in K8s.
 * The caller is responsible for tracking job progress via the Spark UI or K8s API.
 */
object SparkSubmitServer {

  private val log = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val server = createServer()
    registerShutdownHook(server)

    try {
      server.start()
      log.info(s"SparkSubmitServer started on port ${ServerConfig.Server.port}")
      server.join()
    } catch {
      case e: Exception =>
        log.error("Failed to start server", e)
        System.exit(1)
    }
  }

  private def registerShutdownHook(server: Server): Unit =
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      log.info("Shutting down SparkSubmitServer...")
      try { server.stop(); log.info("Server stopped") }
      catch { case e: Exception => log.error("Error during shutdown", e) }
    }))

  /** Assembles server with thread pool, connector, servlet context and shutdown timeout. */
  private def createServer(): Server = {
    val server = new Server(createThreadPool())
    server.addConnector(createConnector(server))
    server.setHandler(createContext())
    server.setStopTimeout(ServerConfig.Server.shutdownTimeoutMs)
    server
  }

  /** Bounded thread pool for handling HTTP requests. */
  private def createThreadPool(): QueuedThreadPool = {
    val pool = new QueuedThreadPool(
      ServerConfig.Server.maxThreads,
      ServerConfig.Server.minThreads,
      ServerConfig.Server.threadIdleTimeoutMs.toInt
    )
    pool.setName("jetty-spark-submitter")
    pool
  }

  /** HTTP connector bound to the configured address and port. */
  private def createConnector(server: Server): ServerConnector = {
    val connector = new ServerConnector(server)
    connector.setHost(ServerConfig.Server.address)
    connector.setPort(ServerConfig.Server.port)
    connector.setAcceptQueueSize(ServerConfig.Server.acceptQueueSize)
    connector.setIdleTimeout(ServerConfig.Server.connectionIdleTimeoutMs)
    connector
  }

  /** Wires metrics filter and all servlet endpoints into a single context. */
  private def createContext(): ServletContextHandler = {
    val metrics = new SparkSubmitMetrics()
    val submitter = new SparkSubmitter(new KubernetesClientProvider())

    // Servlet context at base path, sessions disabled (stateless REST API)
    val context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS)
    context.setContextPath(ApiPaths.Base)

    // Metrics filter intercepts all requests for latency and count tracking
    context.addFilter(new FilterHolder(new SparkSubmitMetricsFilter(metrics)), "/*",
      java.util.EnumSet.of(DispatcherType.REQUEST))

    // Register endpoints: spark-submit, metrics, health
    context.addServlet(new ServletHolder(new SparkSubmitServlet(submitter)), ApiPaths.SparkSubmit)
    context.addServlet(new ServletHolder(new MetricsServlet(metrics)), ApiPaths.Metrics)
    context.addServlet(new ServletHolder(new HealthServlet), ApiPaths.Health)
    context
  }
}
