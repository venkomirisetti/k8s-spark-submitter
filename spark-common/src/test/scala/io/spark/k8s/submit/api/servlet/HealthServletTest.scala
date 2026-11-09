package io.spark.k8s.submit.api.servlet

import io.spark.k8s.submit.api.{HttpStatus, MediaType}
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HealthServletTest extends AnyFlatSpec with Matchers with ServletTestSupport {

  "HealthServlet" should "return 200 OK" in {
    val (req, resp, writer) = mockGetRequest()
    new HealthServlet().doGet(req, resp)

    verify(resp).setStatus(HttpStatus.Ok)
    verify(resp).setContentType(MediaType.TextPlain)
    verify(writer).write("OK")
  }
}
