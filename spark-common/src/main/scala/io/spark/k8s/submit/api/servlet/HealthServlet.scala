package io.spark.k8s.submit.api.servlet

import io.spark.k8s.submit.api.{HttpStatus, MediaType}

import jakarta.servlet.http.{HttpServlet, HttpServletRequest, HttpServletResponse}

/**
 * Health check endpoint for Kubernetes liveness/readiness probes.
 * Always returns 200 OK if the service is running.
 */
class HealthServlet extends HttpServlet {
  override def doGet(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
    resp.setContentType(MediaType.TextPlain)
    resp.setStatus(HttpStatus.Ok)
    resp.getWriter.write("OK")
  }
}
