package io.spark.k8s.submit.server

import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServlet
import org.sparkproject.jetty.server.Handler

/**
 * Servlet context factory for the Jetty version shaded in Spark.
 * Each Spark version module implements it as `SparkServletContextFactory`.
 */
trait ServletContextFactory {

  /**
   * Creates a handler that serves the servlets under the context path, without sessions.
   * Stopping the server waits for in-flight requests.
   *
   * @param contextPath base path of the context, e.g. `/api/v1`
   * @param servlets    path spec to servlet, e.g. `/spark-submit` -> servlet
   * @param filters     filters applied to every request, in order
   */
  def create(contextPath: String, servlets: Seq[(String, HttpServlet)], filters: Seq[Filter]): Handler
}
