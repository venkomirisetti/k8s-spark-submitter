package io.spark.k8s.submit.api.servlet

import io.spark.k8s.submit.api.{HttpStatus, MediaType}
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ReadinessServletTest extends AnyFlatSpec with Matchers with ServletTestSupport {

  "ReadinessServlet" should "return 200 OK when the API is ready" in {
    val (req, resp, writer) = mockGetRequest()
    new ReadinessServlet(() => true).doGet(req, resp)

    verify(resp).setStatus(HttpStatus.Ok)
    verify(resp).setContentType(MediaType.TextPlain)
    verify(writer).write("OK")
  }

  it should "return 503 Service Unavailable when the API is not ready" in {
    val (req, resp, writer) = mockGetRequest()
    new ReadinessServlet(() => false).doGet(req, resp)

    verify(resp).setStatus(HttpStatus.ServiceUnavailable)
    verify(resp).setContentType(MediaType.TextPlain)
    verify(writer).write("API server is not accepting requests")
  }

  it should "check readiness on every request" in {
    var ready = true
    val servlet = new ReadinessServlet(() => ready)

    val (req1, resp1, _) = mockGetRequest()
    servlet.doGet(req1, resp1)
    verify(resp1).setStatus(HttpStatus.Ok)

    ready = false
    val (req2, resp2, _) = mockGetRequest()
    servlet.doGet(req2, resp2)
    verify(resp2).setStatus(HttpStatus.ServiceUnavailable)
  }
}
