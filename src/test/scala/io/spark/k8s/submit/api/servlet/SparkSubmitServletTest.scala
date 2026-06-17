package io.spark.k8s.submit.api.servlet

import io.spark.k8s.submit.SparkSubmitException
import io.spark.k8s.submit.api.HttpStatus
import io.spark.k8s.submit.model.{SparkSubmitRequest, SparkSubmitResponse}
import io.spark.k8s.submit.service.SparkSubmitter
import org.mockito.ArgumentMatchers.{any, anyBoolean}
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SparkSubmitServletTest extends AnyFlatSpec with Matchers with ServletTestSupport {

  private val validJson = """{"spark_submit_args":["--master","local","--class","Main","app.jar"]}"""
  private val successResponse = SparkSubmitResponse("app", "ok", "t", "id", "pod", "uid", "ns")

  "SparkSubmitServlet" should "return 201 on successful submission" in {
    val submitter = mockSubmitter(successResponse)
    val (req, resp, _) = mockPostRequest(validJson)

    new SparkSubmitServlet(submitter).doPost(req, resp)

    verify(resp).setStatus(HttpStatus.Created)
  }

  it should "return 200 on successful dry-run" in {
    val submitter = mockSubmitter(successResponse)
    val (req, resp, _) = mockPostRequest(validJson)
    when(req.getParameter("dryRun")).thenReturn("true")

    new SparkSubmitServlet(submitter).doPost(req, resp)

    verify(resp).setStatus(HttpStatus.Ok)
  }

  it should "return 400 for validation errors" in {
    val submitter = mockSubmitterThrowing(SparkSubmitException.validation("a", "b"))
    val (req, resp, _) = mockPostRequest(validJson)

    new SparkSubmitServlet(submitter).doPost(req, resp)

    verify(resp).setStatus(HttpStatus.BadRequest)
  }

  it should "return 422 for submission errors" in {
    val submitter = mockSubmitterThrowing(SparkSubmitException.submission("a", "b"))
    val (req, resp, _) = mockPostRequest(validJson)

    new SparkSubmitServlet(submitter).doPost(req, resp)

    verify(resp).setStatus(HttpStatus.UnprocessableEntity)
  }

  it should "return 503 for transient errors" in {
    val submitter = mockSubmitterThrowing(SparkSubmitException.retryable("a", "b"))
    val (req, resp, _) = mockPostRequest(validJson)

    new SparkSubmitServlet(submitter).doPost(req, resp)

    verify(resp).setStatus(HttpStatus.ServiceUnavailable)
  }

  it should "return 415 for wrong Content-Type" in {
    val submitter = mockSubmitter(successResponse)
    val (req, resp, _) = mockPostRequest("{}", contentType = "text/plain")

    new SparkSubmitServlet(submitter).doPost(req, resp)

    verify(resp).setStatus(HttpStatus.UnsupportedMediaType)
  }

  it should "return 415 for null Content-Type" in {
    val submitter = mockSubmitter(successResponse)
    val (req, resp, _) = mockPostRequest("{}", contentType = null)

    new SparkSubmitServlet(submitter).doPost(req, resp)

    verify(resp).setStatus(HttpStatus.UnsupportedMediaType)
  }

  it should "return 400 for malformed JSON body" in {
    val submitter = mockSubmitter(successResponse)
    val (req, resp, _) = mockPostRequest("not json")

    new SparkSubmitServlet(submitter).doPost(req, resp)

    verify(resp).setStatus(HttpStatus.BadRequest)
  }

  it should "reject GET requests with 405 and usage message" in {
    val submitter = mockSubmitter(successResponse)
    val (req, resp, _) = mockGetRequest()

    new SparkSubmitServlet(submitter).doGet(req, resp)

    verify(resp).setStatus(HttpStatus.MethodNotAllowed)
  }

  private def mockSubmitter(response: SparkSubmitResponse): SparkSubmitter = {
    val submitter = mock(classOf[SparkSubmitter])
    when(submitter.submitJob(any[SparkSubmitRequest], anyBoolean())).thenReturn(response)
    submitter
  }

  private def mockSubmitterThrowing(exception: SparkSubmitException): SparkSubmitter = {
    val submitter = mock(classOf[SparkSubmitter])
    when(submitter.submitJob(any[SparkSubmitRequest], anyBoolean())).thenThrow(exception)
    submitter
  }
}
