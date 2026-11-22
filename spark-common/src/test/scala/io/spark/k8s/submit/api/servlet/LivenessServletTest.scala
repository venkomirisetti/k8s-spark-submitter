package io.spark.k8s.submit.api.servlet

import io.spark.k8s.submit.api.{HttpStatus, MediaType}
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LivenessServletTest extends AnyFlatSpec with Matchers with ServletTestSupport {

  "LivenessServlet" should "return 200 OK" in {
    val (req, resp, writer) = mockGetRequest()
    new LivenessServlet().doGet(req, resp)

    verify(resp).setStatus(HttpStatus.Ok)
    verify(resp).setContentType(MediaType.TextPlain)
    verify(writer).write("OK")
  }
}
