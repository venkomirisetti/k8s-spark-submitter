package io.spark.k8s.submit.api.servlet

import io.spark.k8s.submit.SparkSubmitException
import io.spark.k8s.submit.api.{ErrorCode, HttpStatus}
import io.spark.k8s.submit.model.{SparkSubmitRequest, SparkSubmitResponse}
import io.spark.k8s.submit.service.SparkSubmitter
import org.mockito.ArgumentMatchers.{any, anyBoolean}
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SparkSubmitServletTest extends AnyFlatSpec with Matchers with ServletTestSupport {

  private val validJson = """{"spark_submit_args":["--master","local","--class","Main","app.jar"]}"""
  private val successResponse = SparkSubmitResponse("sub-id", "app", "ok", "t", "id", "pod", "uid", "ns")

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

  it should "return 400 for invalid spark-submit args" in {
    val submitter = mockSubmitterThrowing(
      SparkSubmitException.of(ErrorCode.InvalidSparkSubmitArgs, "bad args"))
    val (req, resp, _) = mockPostRequest(validJson)

    new SparkSubmitServlet(submitter).doPost(req, resp)

    verify(resp).setStatus(HttpStatus.BadRequest)
  }

  it should "return 503 for transient errors" in {
    val submitter = mockSubmitterThrowing(
      SparkSubmitException.of(ErrorCode.SubmitterOverloaded, "rate limited"))
    val (req, resp, _) = mockPostRequest(validJson)

    new SparkSubmitServlet(submitter).doPost(req, resp)

    verify(resp).setStatus(HttpStatus.ServiceUnavailable)
  }

  it should "return 409 for driver pod already exists" in {
    val submitter = mockSubmitterThrowing(
      SparkSubmitException.of(ErrorCode.DriverPodAlreadyExists, "pod exists"))
    val (req, resp, _) = mockPostRequest(validJson)

    new SparkSubmitServlet(submitter).doPost(req, resp)

    verify(resp).setStatus(HttpStatus.Conflict)
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

  it should "return 500 for internal server errors" in {
    val submitter = mockSubmitterThrowing(
      SparkSubmitException.of(ErrorCode.InternalError, "internal error"))
    val (req, resp, _) = mockPostRequest(validJson)

    new SparkSubmitServlet(submitter).doPost(req, resp)

    verify(resp).setStatus(HttpStatus.InternalServerError)
  }

  it should "return 422 for submission failed" in {
    val submitter = mockSubmitterThrowing(
      SparkSubmitException.of(ErrorCode.SubmissionFailed, "exceeded quota"))
    val (req, resp, _) = mockPostRequest(validJson)

    new SparkSubmitServlet(submitter).doPost(req, resp)

    verify(resp).setStatus(HttpStatus.UnprocessableEntity)
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
