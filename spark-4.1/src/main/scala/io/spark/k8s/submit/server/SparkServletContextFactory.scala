package io.spark.k8s.submit.server

import jakarta.servlet.{DispatcherType, Filter}
import jakarta.servlet.http.HttpServlet
import org.sparkproject.jetty.server.Handler
import org.sparkproject.jetty.servlet.{FilterHolder, ServletContextHandler, ServletHolder}

import java.util.EnumSet

/**
 * Servlet context factory for Jetty 11 (Spark 4.1).
 * Stopping waits for in-flight requests by default.
 */
object SparkServletContextFactory extends ServletContextFactory {

  override def create(
      contextPath: String,
      servlets: Seq[(String, HttpServlet)],
      filters: Seq[Filter]): Handler = {
    val context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS)
    context.setContextPath(contextPath)
    addFilters(context, filters)
    addServlets(context, servlets)
    context
  }

  /** Applies each filter to every request, in order. */
  private def addFilters(context: ServletContextHandler, filters: Seq[Filter]): Unit =
    filters.foreach { filter =>
      context.addFilter(new FilterHolder(filter), "/*", EnumSet.of(DispatcherType.REQUEST))
    }

  /** Maps each servlet to its path spec. */
  private def addServlets(context: ServletContextHandler, servlets: Seq[(String, HttpServlet)]): Unit =
    servlets.foreach { case (pathSpec, servlet) =>
      context.addServlet(new ServletHolder(servlet), pathSpec)
    }
}
