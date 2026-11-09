package io.spark.k8s.submit.api.servlet

import io.spark.k8s.submit.api.{HttpStatus, MediaType}
import io.spark.k8s.submit.metrics.SparkSubmitMetrics

import jakarta.servlet.http.{HttpServlet, HttpServletRequest, HttpServletResponse}
import scala.jdk.CollectionConverters._

/**
 * Servlet that exposes Prometheus-compatible metrics.
 */
class MetricsServlet(metrics: SparkSubmitMetrics) extends HttpServlet {

  override def doGet(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
    resp.setContentType(MediaType.PrometheusText)
    resp.setStatus(HttpStatus.Ok)

    val registry = metrics.getRegistry

    // Simple metrics output in Prometheus format
    val output = new StringBuilder()
    registry.getMeters.asScala.foreach { meter =>
      val id = meter.getId
      val measurements = meter.measure().asScala

      measurements.foreach { measurement =>
        output.append(s"${id.getName} ${measurement.getValue}\n")
      }
    }

    resp.getWriter.write(output.toString())
  }
}
