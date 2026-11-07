package io.spark.k8s.submit.jetty

import jakarta.servlet.{Filter, Servlet}
import org.sparkproject.jetty.ee10.servlet.{FilterHolder, ServletContextHandler, ServletHolder}
import org.sparkproject.jetty.server.Handler
import org.sparkproject.jetty.server.handler.ContextHandlerCollection

import scala.jdk.CollectionConverters._

/**
 * Jetty 12 API used by Spark 4.2 and later (shaded as org.sparkproject.jetty).
 *
 * See the jetty11 source directory for Spark 4.0 and 4.1. Both provide the same object, and
 * the Maven profile selects one at build time.
 */
object JettyCompat {
  type ContextHandler = ServletContextHandler

  def newContext(contextPath: String, virtualHosts: Seq[String]): ContextHandler = {
    val context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS)
    context.setContextPath(contextPath)
    context.setVirtualHosts(virtualHosts.asJava)
    context
  }

  def filterHolder(filter: Filter): FilterHolder = new FilterHolder(filter)

  def servletHolder(servlet: Servlet): ServletHolder = new ServletHolder(servlet)

  def handlers(contexts: ContextHandler*): Handler = {
    val collection = new ContextHandlerCollection()
    contexts.foreach(c => collection.addHandler(c))
    collection
  }
}
