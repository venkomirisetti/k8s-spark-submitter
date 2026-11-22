package io.spark.k8s.submit.api.servlet

import io.spark.k8s.submit.api.{HttpStatus, MediaType}

import jakarta.servlet.http.{HttpServlet, HttpServletRequest, HttpServletResponse}

/**
 * Health check endpoint for Kubernetes readiness probes (/readyz).
 * Returns 200 OK when the API server is accepting requests, 503 Service Unavailable otherwise.
 */
class ReadinessServlet(apiReady: () => Boolean) extends HttpServlet {
  override def doGet(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
    val (status, body) =
      if (apiReady()) (HttpStatus.Ok, "OK")
      else (HttpStatus.ServiceUnavailable, "API server is not accepting requests")
    resp.setContentType(MediaType.TextPlain)
    resp.setStatus(status)
    resp.getWriter.write(body)
  }
}
