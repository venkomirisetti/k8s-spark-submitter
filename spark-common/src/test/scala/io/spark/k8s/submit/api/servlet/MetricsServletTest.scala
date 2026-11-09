package io.spark.k8s.submit.api.servlet

import io.spark.k8s.submit.api.{HttpStatus, MediaType}
import io.spark.k8s.submit.metrics.SparkSubmitMetrics
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MetricsServletTest extends AnyFlatSpec with Matchers with ServletTestSupport {

  "MetricsServlet" should "return 200 with Prometheus content type" in {
    val (req, resp, writer) = mockGetRequest()
    new MetricsServlet(new SparkSubmitMetrics()).doGet(req, resp)

    verify(resp).setStatus(HttpStatus.Ok)
    verify(resp).setContentType(MediaType.PrometheusText)
    verify(writer).write(org.mockito.ArgumentMatchers.anyString())
  }

  it should "include metrics in output after activity" in {
    val metrics = new SparkSubmitMetrics()
    metrics.recordRequestStart()
    metrics.recordRequestComplete(200)

    val (req, resp, writer) = mockGetRequest()
    new MetricsServlet(metrics).doGet(req, resp)

    val captor = org.mockito.ArgumentCaptor.forClass(classOf[String])
    verify(writer).write(captor.capture())
    captor.getValue should not be empty
  }
}
