package io.spark.k8s.submit.api.servlet

import io.spark.k8s.submit._
import io.spark.k8s.submit.api._
import io.spark.k8s.submit.model.{SparkSubmitRequest, SparkSubmitResponse}
import io.spark.k8s.submit.service.SparkSubmitter
import org.slf4j.{Logger, LoggerFactory}

import java.io.IOException
import jakarta.servlet.http.{HttpServlet, HttpServletRequest, HttpServletResponse}
import scala.util.{Failure, Success, Try}

/**
 * Servlet handling Spark job submission API.
 *
 * Endpoints:
 * - POST /spark-submit              → Submit Spark job (creates K8s resources)
 * - POST /spark-submit?dryRun=true  → Validate only (no K8s resources created)
 */
class SparkSubmitServlet(sparkSubmitter: SparkSubmitter) extends HttpServlet with JsonSupport with ErrorSupport {

  protected val log: Logger = LoggerFactory.getLogger(getClass)

  override def doPost(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
    Try {
      val contentType = req.getContentType
      if (contentType == null || !contentType.startsWith(MediaType.ApplicationJson)) {
        throw new IllegalArgumentException(s"Content-Type must be ${MediaType.ApplicationJson}")
      }

      val dryRun = Option(req.getParameter("dryRun")).exists(_.toLowerCase == "true")
      val request = parseJsonStream(req.getInputStream, classOf[SparkSubmitRequest]).get

      if (dryRun) log.debug(s"${LogPrefix.Request} [DRY-RUN] $request")
      else log.debug(s"${LogPrefix.Request} $request")

      val response = sparkSubmitter.submitJob(request, dryRun)

      if (dryRun) log.info(s"${LogPrefix.Success} dryrun validation passed appName=${response.appName}")
      else logSubmissionSuccess(response)

      sendJson(resp, if (dryRun) HttpStatus.Ok else HttpStatus.Created, response)
    } match {
      case Success(_) => // Already handled
      case Failure(ex: SparkSubmitException) => handleSparkSubmitException(resp, ex)
      case Failure(ex: IOException) =>
        logSubmissionFailure(ErrorCode.BadRequest, Messages.MalformedRequest, ex.getMessage)
        sendError(resp, HttpStatus.BadRequest, ErrorCode.BadRequest, Messages.MalformedRequest)
      case Failure(ex: IllegalArgumentException) =>
        logSubmissionFailure(ErrorCode.UnsupportedMediaType, Messages.ContentTypeMustBeJson, ex.getMessage)
        sendError(resp, HttpStatus.UnsupportedMediaType, ErrorCode.UnsupportedMediaType, Messages.ContentTypeMustBeJson)
      case Failure(ex) =>
        logSubmissionFailure(ErrorCode.InternalError, Messages.UnexpectedError, ex.getMessage)
        log.error(s"${LogPrefix.Error} Unexpected error", ex)
        sendError(resp, HttpStatus.InternalServerError, ErrorCode.InternalError, Messages.UnexpectedError)
    }
  }

  override def doGet(req: HttpServletRequest, resp: HttpServletResponse): Unit = {
    resp.sendError(HttpStatus.MethodNotAllowed, "Only POST is supported")
  }

  private def handleSparkSubmitException(resp: HttpServletResponse, ex: SparkSubmitException): Unit = {
    if (ex.isValidationError) {
      val details = ex.getDetails
      logSubmissionFailure(ErrorCode.BadRequest, ex.getMessage, details)
      sendError(resp, HttpStatus.BadRequest, ErrorCode.BadRequest, ex.getMessage, details)
    } else if (ex.isTransient) {
      logSubmissionFailure(ErrorCode.ServiceUnavailable, ex.getMessage, ex.getDetails)
      sendError(resp, HttpStatus.ServiceUnavailable, ErrorCode.ServiceUnavailable, ex.getMessage, ex.getDetails)
    } else {
      logSubmissionFailure(ErrorCode.SubmissionFailed, ex.getMessage, ex.getDetails)
      sendError(resp, HttpStatus.UnprocessableEntity, ErrorCode.SubmissionFailed, ex.getMessage, ex.getDetails)
    }
  }

  private def logSubmissionSuccess(response: SparkSubmitResponse): Unit = {
    log.info(s"${LogPrefix.Success} appName=${response.appName} sparkAppId=${response.sparkAppId} " +
      s"driverPodName=${response.driverPodName} driverPodUid=${response.driverPodUid} namespace=${response.namespace}")
  }
}
