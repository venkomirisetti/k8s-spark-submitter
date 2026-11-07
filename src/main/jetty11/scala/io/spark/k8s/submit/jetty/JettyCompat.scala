package io.spark.k8s.submit.jetty

import jakarta.servlet.{Filter, Servlet}
import org.sparkproject.jetty.server.Handler
import org.sparkproject.jetty.server.handler.HandlerList
import org.sparkproject.jetty.servlet.{FilterHolder, ServletContextHandler, ServletHolder}

/**
 * Jetty 11 API used by Spark 4.0 and 4.1 (shaded as org.sparkproject.jetty).
 *
 * Spark 4.2 moved to Jetty 12, which renamed the servlet package and removed HandlerList.
 * The jetty12 source directory provides the same object for those Spark versions, and the
 * Maven profile selects one at build time.
 */
object JettyCompat {
  type ContextHandler = ServletContextHandler

  def newContext(contextPath: String, virtualHosts: Seq[String]): ContextHandler = {
    val context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS)
    context.setContextPath(contextPath)
    context.setVirtualHosts(virtualHosts.toArray)
    context
  }

  def filterHolder(filter: Filter): FilterHolder = new FilterHolder(filter)

  def servletHolder(servlet: Servlet): ServletHolder = new ServletHolder(servlet)

  def handlers(contexts: ContextHandler*): Handler = {
    val list = new HandlerList()
    contexts.foreach(list.addHandler)
    list
  }
}
