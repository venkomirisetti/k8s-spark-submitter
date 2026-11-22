package io.spark.k8s.submit.server

import javax.servlet.{DispatcherType, Filter}
import javax.servlet.http.HttpServlet
import org.sparkproject.jetty.server.Handler
import org.sparkproject.jetty.server.handler.StatisticsHandler
import org.sparkproject.jetty.servlet.{FilterHolder, ServletContextHandler, ServletHolder}

import java.util.EnumSet

/**
 * Servlet context factory for Jetty 9 (Spark 3.5).
 * Wraps the context in a StatisticsHandler so stopping waits for in-flight requests.
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
    withRequestTracking(context)
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

  /** Tracks in-flight requests; Jetty 9 only waits for them on stop through a StatisticsHandler. */
  private def withRequestTracking(handler: Handler): Handler = {
    val statistics = new StatisticsHandler()
    statistics.setHandler(handler)
    statistics
  }
}
