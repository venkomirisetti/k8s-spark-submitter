package io.spark.k8s.submit.metrics

import io.micrometer.core.instrument.Timer
import io.spark.k8s.submit.api.ApiPaths

import java.io.IOException
import jakarta.servlet._
import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}

/** Filter that records request count and latency for the Spark submit endpoint. */
class SparkSubmitMetricsFilter(metrics: SparkSubmitMetrics) extends Filter {

  override def init(filterConfig: FilterConfig): Unit = {
    // No initialization needed
  }

  override def doFilter(
      request: ServletRequest,
      response: ServletResponse,
      chain: FilterChain): Unit = {

    (request, response) match {
      case (httpReq: HttpServletRequest, httpResp: HttpServletResponse) =>
        if (isSubmitRequest(httpReq)) {
          val sample = Timer.start(metrics.getRegistry)
          metrics.recordRequestStart()
          try {
            chain.doFilter(request, response)
          } finally {
            sample.stop(metrics.getLatencyTimer)
            metrics.recordRequestComplete(httpResp.getStatus)
          }
        } else {
          chain.doFilter(request, response)
        }
      case _ =>
        chain.doFilter(request, response)
    }
  }

  override def destroy(): Unit = {
    // No cleanup needed
  }

  private def isSubmitRequest(request: HttpServletRequest): Boolean = {
    val path = request.getRequestURI
    val method = request.getMethod
    path != null && path.endsWith(ApiPaths.SparkSubmit) && "POST".equalsIgnoreCase(method)
  }
}
