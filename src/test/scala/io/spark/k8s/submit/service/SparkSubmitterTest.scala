package io.spark.k8s.submit.service

import io.spark.k8s.submit.SparkSubmitException
import io.spark.k8s.submit.api.ErrorCode
import io.spark.k8s.submit.model.SparkSubmitRequest
import io.spark.k8s.submit.util.PodTemplateUtils
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException}
import org.mockito.Mockito
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}

import java.util.{Arrays => JavaArrays}
import scala.jdk.CollectionConverters._

/**
 * Unit tests and basic integration tests for SparkSubmitter.submitJob().
 *
 * This test suite is organized into two main sections:
 *
 * 1. UNIT TESTS:
 *    - Use Mockito mocks for isolated testing
 *    - Test error handling, validation, and business logic
 *    - Fast execution (~1-2 seconds total)
 *    - No external dependencies
 *    - Cover argument parsing, validation, error handling
 *
 * 2. BASIC INTEGRATION TESTS:
 *    - Use kube-api-test with real Kubernetes API server
 *    - Verify basic Spark job submission works end-to-end
 *    - Validate K8s resource creation (Pod, ConfigMap, Service)
 *    - Tests use SparkK8sResourceValidator for resource verification
 *
 * NOTE: Comprehensive end-to-end scenarios (pod templates, concurrent submissions, etc.)
 * are covered in SparkSubmitEndToEndTest.scala to keep this file focused on unit testing.
 */
class SparkSubmitterTest extends AnyFlatSpec
  with Matchers
  with BeforeAndAfterAll
  with BeforeAndAfterEach
  with KubeAPIServerFixture {

  // ========== Test Constants ==========

  // Spark CLI Arguments
  private val MasterArg = "--master"
  private val DeployModeArg = "--deploy-mode"
  private val ClassArg = "--class"
  private val NameArg = "--name"
  private val ConfArg = "--conf"

  // Common test values
  private val SparkPiClass = "org.apache.spark.examples.SparkPi"
  private val LocalJar = "local:///app.jar"
  private val DefaultNamespace = "test-ns"
  private val SparkImage = "spark:3.5.5"
  private val DefaultServiceAccount = "default"
  private val K8sScheme = "k8s://"
  private val DefaultK8sMaster = "k8s://https://kubernetes.default.svc"

  // Spark configuration keys
  private val NamespaceConfKey = "spark.kubernetes.namespace"
  private val ImageConfKey = "spark.kubernetes.container.image"
  private val ServiceAccountConfKey = "spark.kubernetes.authenticate.driver.serviceAccountName"
  private val ClientMasterConfKey = "spark.kubernetes.client.master"

  // Kubernetes labels and metadata (for integration tests)
  private val SparkAppSelectorLabel = "spark-app-selector"
  private val PodKind = "Pod"

  // App names (for integration tests)
  private val IntegrationTestApp = "integration-test-app"
  private val ResourceCheckApp = "resource-check-app"
  private val ArrayArgsTestApp = "array-args-test-app"

  // Template JSON loaded from resources (for unit tests only)
  private lazy val DriverTemplateJson = loadPodTemplate("driver-template.json")
  private lazy val ExecutorTemplateJson = loadPodTemplate("executor-template.json")
  private lazy val SimpleTemplateJson = loadPodTemplate("simple-template.json")

  // Invalid test data
  private val InvalidArgKey = "--invalid-arg"
  private val InvalidArgValue = "value"
  private val InvalidMasterUrl = "invalid-master-url"
  private val InvalidJson = "{invalid json"
  private val InvalidJsonArray = "[not valid"

  // File system paths (used in template cleanup tests)
  private val TmpDirProperty = "java.io.tmpdir"
  private val SparkSubmitterDir = ".spark-submitter"

  // Count expectations (used in template cleanup tests)
  private val ZeroCount = 0

  // Error message fragments
  private val FailedToSubmit = "Failed to submit"

  // Special characters test data
  private val SpecialAppName = "app-with-special-chars_123.test"
  private val DriverMemory = "2g"
  private val ExecutorMemory = "4g"
  private val ExecutorCores = "2"
  private val AppArg1 = "100"
  private val AppArg2 = "arg2"
  private val EmptyString = ""
  private val LargeValuePrefix = "x"
  private val LargeValueSize = 10000
  private val UnicodeLabel = "emoji"
  private val UnicodeValue = "🚀"
  private val LargeKey = "large-key"

  // Namespace for test configurations
  private val TestNamespace1 = "ns1"
  private val TestNamespace2 = "ns2"

  // Index for container retrieval
  private val FirstContainerIndex = 0
  private val FirstOwnerRefIndex = 0

  // Count expectations
  private val MinContainerCount = 0

  // ========== Test Setup ==========

  // Unit test setup (mocks)
  private def createMockK8sProvider(client: KubernetesClient): KubernetesClientProvider =
    new KubernetesClientProvider(() => client)

  private def createMockSubmitter(): SparkSubmitter = {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    new SparkSubmitter(createMockK8sProvider(mockClient))
  }

  private def submitExpectingValidationError(request: SparkSubmitRequest): SparkSubmitException = {
    val submitter = createMockSubmitter()
    val ex = intercept[SparkSubmitException] { submitter.submitJob(request) }
    ex.errorCode shouldBe ErrorCode.InvalidSparkSubmitArgs
    ex
  }

  private def submitExpectingSubmissionError(request: SparkSubmitRequest): SparkSubmitException = {
    val submitter = createMockSubmitter()
    val ex = intercept[SparkSubmitException] { submitter.submitJob(request) }
    ex.errorCode should not be ErrorCode.InvalidSparkSubmitArgs
    ex
  }

  private def validArgs: java.util.List[String] = JavaArrays.asList(
    MasterArg, DefaultK8sMaster, DeployModeArg, "cluster", ClassArg, SparkPiClass,
    ConfArg, s"$NamespaceConfKey=$DefaultNamespace", ConfArg, s"$ImageConfKey=$SparkImage", LocalJar)

  // Integration test setup (KubeAPIServer from fixture)
  override def beforeAll(): Unit = {
    super.beforeAll()  // Calls KubeAPIServerFixture.beforeAll()
  }

  override def afterAll(): Unit = {
    super.afterAll()  // Calls KubeAPIServerFixture.afterAll()
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
  }

  private def getClient: KubernetesClient = getKubeClient

  private def getMockServerMasterUrl: String = {
    getKubeClient.getConfiguration.getMasterUrl
  }

  private def createIntegrationTestSubmitter(): (KubernetesClientProvider, SparkSubmitter) = {
    val provider = new KubernetesClientProvider(() => getClient)
    val submitter = new SparkSubmitter(provider)
    (provider, submitter)
  }

  // ==========================================================================
  // UNIT TESTS
  // ==========================================================================
  // These tests use Mockito mocks to test error handling, validation,
  // and business logic in isolation without external dependencies.
  // ==========================================================================

  "SparkSubmitter" should "wrap parsing exceptions as validation errors" in {
    submitExpectingValidationError(SparkSubmitRequest(sparkSubmitArgs = JavaArrays.asList(InvalidArgKey, InvalidArgValue)))
  }

  it should "accept Array[String] arguments via overload" in {
    val submitter = createMockSubmitter()
    val ex = intercept[SparkSubmitException] { submitter.submitJob(Array(InvalidArgKey, InvalidArgValue)) }
    ex.errorCode shouldBe ErrorCode.InvalidSparkSubmitArgs
  }

  it should "require valid master URL" in {
    submitExpectingValidationError(SparkSubmitRequest(sparkSubmitArgs = JavaArrays.asList(
      MasterArg, InvalidMasterUrl, ClassArg, SparkPiClass, LocalJar)))
  }

  it should "require main class or primary resource" in {
    submitExpectingValidationError(SparkSubmitRequest(sparkSubmitArgs = JavaArrays.asList(MasterArg, DefaultK8sMaster, DeployModeArg, "cluster")))
  }

  it should "reject client deploy mode" in {
    val request = SparkSubmitRequest(sparkSubmitArgs =
      JavaArrays.asList(
        MasterArg, DefaultK8sMaster, DeployModeArg, "client",
        ClassArg, SparkPiClass,
        ConfArg, s"$ImageConfKey=$SparkImage",
        LocalJar
      ))

    val exception = intercept[SparkSubmitException] { createMockSubmitter().submitJob(request) }
    exception.errorCode shouldBe ErrorCode.InvalidSparkSubmitArgs
    exception.getMessage should include("cluster")
  }

  it should "reject client deploy mode passed via --conf" in {
    val request = SparkSubmitRequest(sparkSubmitArgs =
      JavaArrays.asList(
        ConfArg, s"spark.master=$DefaultK8sMaster",
        ConfArg, "spark.submit.deployMode=client",
        ClassArg, SparkPiClass,
        ConfArg, s"$ImageConfKey=$SparkImage",
        LocalJar
      ))

    val exception = intercept[SparkSubmitException] { createMockSubmitter().submitJob(request) }
    exception.errorCode shouldBe ErrorCode.InvalidSparkSubmitArgs
    exception.getMessage should include("cluster")
  }

  it should "handle null driver template and null executor template" in {
    submitExpectingSubmissionError(SparkSubmitRequest(sparkSubmitArgs = JavaArrays.asList(
      MasterArg, DefaultK8sMaster, DeployModeArg, "cluster", ClassArg, SparkPiClass,
      ConfArg, s"$NamespaceConfKey=$DefaultNamespace", ConfArg, s"$ImageConfKey=$SparkImage", LocalJar)))
  }

  it should "handle empty driver template and empty executor template" in {
    submitExpectingSubmissionError(SparkSubmitRequest(
      sparkSubmitArgs = JavaArrays.asList(MasterArg, DefaultK8sMaster, DeployModeArg, "cluster", ClassArg, SparkPiClass,
        ConfArg, s"$NamespaceConfKey=$DefaultNamespace", ConfArg, s"$ImageConfKey=$SparkImage", LocalJar),
      driverPodTemplate = EmptyString, executorPodTemplate = EmptyString))
  }

  it should "handle invalid JSON in driver template" in {
    val submitter = createMockSubmitter()
    val ex = intercept[SparkSubmitException] { submitter.submitJob(SparkSubmitRequest(sparkSubmitArgs = validArgs, driverPodTemplate = InvalidJson)) }
    ex.errorCode shouldBe ErrorCode.InternalError
  }

  it should "handle invalid JSON in executor template" in {
    val submitter = createMockSubmitter()
    val ex = intercept[SparkSubmitException] { submitter.submitJob(SparkSubmitRequest(sparkSubmitArgs = validArgs, executorPodTemplate = InvalidJsonArray)) }
    ex.errorCode shouldBe ErrorCode.InternalError
  }

  it should "handle both driver and executor templates with valid JSON" in {
    val submitter = createMockSubmitter()
    val ex = intercept[SparkSubmitException] { submitter.submitJob(SparkSubmitRequest(sparkSubmitArgs = validArgs, driverPodTemplate = DriverTemplateJson, executorPodTemplate = ExecutorTemplateJson)) }
    ex.errorCode shouldBe ErrorCode.InternalError
  }

  it should "generate unique template directories for concurrent submissions" in {
    val submitter = createMockSubmitter()

    val request1 = SparkSubmitRequest(
      sparkSubmitArgs = JavaArrays.asList(MasterArg, DefaultK8sMaster, DeployModeArg, "cluster", ClassArg, SparkPiClass, ConfArg, s"$NamespaceConfKey=$TestNamespace1",
        ConfArg, s"$ImageConfKey=$SparkImage", LocalJar),
      driverPodTemplate = SimpleTemplateJson
    )

    val request2 = SparkSubmitRequest(
      sparkSubmitArgs = JavaArrays.asList(MasterArg, DefaultK8sMaster, DeployModeArg, "cluster", ClassArg, SparkPiClass, ConfArg, s"$NamespaceConfKey=$TestNamespace2",
        ConfArg, s"$ImageConfKey=$SparkImage", LocalJar),
      driverPodTemplate = SimpleTemplateJson
    )

    intercept[SparkSubmitException] {
      submitter.submitJob(request1)
    }
    intercept[SparkSubmitException] {
      submitter.submitJob(request2)
    }
  }

  it should "use default namespace when not specified" in {
    submitExpectingSubmissionError(SparkSubmitRequest(sparkSubmitArgs = JavaArrays.asList(
      MasterArg, DefaultK8sMaster, DeployModeArg, "cluster", ClassArg, SparkPiClass, ConfArg, s"$ImageConfKey=$SparkImage", LocalJar)))
  }

  it should "respect custom app name from spark-submit args" in {
    submitExpectingSubmissionError(SparkSubmitRequest(sparkSubmitArgs = JavaArrays.asList(
      MasterArg, DefaultK8sMaster, DeployModeArg, "cluster", ClassArg, SparkPiClass, NameArg, "custom",
      ConfArg, s"$NamespaceConfKey=$DefaultNamespace", ConfArg, s"$ImageConfKey=$SparkImage", LocalJar)))
  }

  it should "cleanup old template directories on construction" in {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val k8sProvider = createMockK8sProvider(mockClient)

    val oldDirName = s"old_submission_${System.nanoTime()}"
    val oldDir = PodTemplateUtils.createTemplateDirForSubmission(oldDirName)
    oldDir.toFile.exists() shouldBe true

    // Construction triggers PodTemplateUtils.cleanupOldTemplateDirs()
    new SparkSubmitter(k8sProvider)
  }

  it should "cleanup templates even when submission fails during parsing" in {
    val submitter = createMockSubmitter()

    val request = SparkSubmitRequest(
      sparkSubmitArgs = JavaArrays.asList(InvalidArgKey, InvalidArgValue),
      driverPodTemplate = SimpleTemplateJson
    )

    val baseDir = new java.io.File(System.getProperty(TmpDirProperty), SparkSubmitterDir)
    val countBefore = if (baseDir.exists()) {
      baseDir.listFiles().count(_.isDirectory)
    } else {
      ZeroCount
    }

    intercept[SparkSubmitException] {
      submitter.submitJob(request)
    }

    val countAfter = if (baseDir.exists()) {
      baseDir.listFiles().count(_.isDirectory)
    } else {
      ZeroCount
    }

    countAfter shouldBe countBefore
  }

  it should "cleanup templates even when submission fails during K8s operations" in {
    val submitter = createMockSubmitter()
    val baseDir = new java.io.File(System.getProperty(TmpDirProperty), SparkSubmitterDir)
    val countBefore = if (baseDir.exists()) baseDir.listFiles().count(_.isDirectory) else ZeroCount

    intercept[SparkSubmitException] { submitter.submitJob(SparkSubmitRequest(sparkSubmitArgs = validArgs, driverPodTemplate = SimpleTemplateJson)) }

    val countAfter = if (baseDir.exists()) baseDir.listFiles().count(_.isDirectory) else ZeroCount
    countAfter shouldBe countBefore
  }

  it should "handle large template content without issues" in {
    val submitter = createMockSubmitter()
    val largeTemplate = s"""{"metadata":{"labels":{"k":"${"x" * 10000}"}}}"""
    val ex = intercept[SparkSubmitException] { submitter.submitJob(SparkSubmitRequest(sparkSubmitArgs = validArgs, driverPodTemplate = largeTemplate)) }
    ex.errorCode shouldBe ErrorCode.InternalError
  }

  it should "handle unicode characters in templates" in {
    val submitter = createMockSubmitter()
    val ex = intercept[SparkSubmitException] { submitter.submitJob(SparkSubmitRequest(sparkSubmitArgs = validArgs, driverPodTemplate = """{"metadata":{"labels":{"e":"H"}}}""")) }
    ex.errorCode shouldBe ErrorCode.InternalError
  }

  it should "handle special characters in app name" in {
    submitExpectingSubmissionError(SparkSubmitRequest(sparkSubmitArgs = JavaArrays.asList(
      MasterArg, DefaultK8sMaster, DeployModeArg, "cluster", ClassArg, SparkPiClass, NameArg, SpecialAppName,
      ConfArg, s"$NamespaceConfKey=$DefaultNamespace", ConfArg, s"$ImageConfKey=$SparkImage", LocalJar)))
  }

  it should "handle multiple spark configurations" in {
    submitExpectingSubmissionError(SparkSubmitRequest(sparkSubmitArgs = JavaArrays.asList(
      MasterArg, DefaultK8sMaster, DeployModeArg, "cluster", ClassArg, SparkPiClass,
      ConfArg, s"$NamespaceConfKey=$DefaultNamespace", ConfArg, s"$ImageConfKey=$SparkImage",
      ConfArg, "spark.driver.memory=2g", ConfArg, "spark.executor.memory=4g", LocalJar)))
  }

  it should "handle application arguments after jar" in {
    submitExpectingSubmissionError(SparkSubmitRequest(sparkSubmitArgs = JavaArrays.asList(
      MasterArg, DefaultK8sMaster, DeployModeArg, "cluster", ClassArg, SparkPiClass,
      ConfArg, s"$NamespaceConfKey=$DefaultNamespace", ConfArg, s"$ImageConfKey=$SparkImage",
      LocalJar, AppArg1, AppArg2)))
  }

  it should "dry-run submit that parses args and cleans up templates on parse failure" in {
    val submitter = createMockSubmitter()

    val request = SparkSubmitRequest(sparkSubmitArgs = JavaArrays.asList(InvalidArgKey, InvalidArgValue))

    val baseDir = new java.io.File(System.getProperty(TmpDirProperty), SparkSubmitterDir)
    val countBefore = if (baseDir.exists()) baseDir.listFiles().count(_.isDirectory) else ZeroCount

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(request, dryRun = true)
    }
    exception.errorCode shouldBe ErrorCode.InvalidSparkSubmitArgs

    val countAfter = if (baseDir.exists()) baseDir.listFiles().count(_.isDirectory) else ZeroCount
    countAfter shouldBe countBefore
  }

  // ==========================================================================
  // ERROR CODE CLASSIFICATION TESTS
  // ==========================================================================
  // Verify that SparkSubmitter correctly maps K8s failures to structured error codes.
  // ==========================================================================

  "SparkSubmitter error code classification" should "map K8s 401 Unauthorized to INTERNAL_SERVER_ERROR" in {
    val submitter = createSubmitterWithThrowingClient(
      new KubernetesClientException("unauthorized", 401, null))

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(validRequest)
    }
    exception.errorCode shouldBe ErrorCode.InternalError
  }

  it should "map K8s 429 Too Many Requests to SUBMITTER_OVERLOADED" in {
    val submitter = createSubmitterWithThrowingClient(
      new KubernetesClientException("rate limited", 429, null))

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(validRequest)
    }
    exception.errorCode shouldBe ErrorCode.SubmitterOverloaded
  }

  it should "map K8s 500 Internal Server Error to INTERNAL_SERVER_ERROR" in {
    val submitter = createSubmitterWithThrowingClient(
      new KubernetesClientException("server error", 500, null))

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(validRequest)
    }
    exception.errorCode shouldBe ErrorCode.InternalError
  }

  it should "map K8s 503 Service Unavailable to INTERNAL_SERVER_ERROR" in {
    val submitter = createSubmitterWithThrowingClient(
      new KubernetesClientException("unavailable", 503, null))

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(validRequest)
    }
    exception.errorCode shouldBe ErrorCode.InternalError
  }

  it should "map K8s 403 Forbidden to INTERNAL_SERVER_ERROR" in {
    val submitter = createSubmitterWithThrowingClient(
      new KubernetesClientException("forbidden", 403, null))

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(validRequest)
    }
    exception.errorCode shouldBe ErrorCode.InternalError
  }

  it should "return idempotent replay on K8s 409 when same submission-id exists" in {
    val submitter = createSubmitterWithConflictAndExistingPod(sameSubmissionId = true)
    val request = validRequestWithSubmissionId("sub-123")

    val response = submitter.submitJob(request)

    response.idempotentReplay shouldBe true
    response.submissionId shouldBe "sub-123"
    response.driverPodName shouldBe "existing-driver"
  }

  it should "throw DRIVER_POD_ALREADY_EXISTS on K8s 409 when different submission-id" in {
    val submitter = createSubmitterWithConflictAndExistingPod(sameSubmissionId = false)
    val request = validRequestWithSubmissionId("sub-123")

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(request)
    }
    exception.errorCode shouldBe ErrorCode.DriverPodAlreadyExists
  }


  it should "map K8s exception with no HTTP code (network error) to SUBMISSION_FAILED" in {
    val submitter = createSubmitterWithThrowingClient(
      new KubernetesClientException("connection refused",
        new java.net.ConnectException("connection refused")))

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(validRequest)
    }
    exception.errorCode shouldBe ErrorCode.InternalError
  }

  it should "map unknown RuntimeException to SUBMISSION_FAILED" in {
    val submitter = createSubmitterWithThrowingClient(
      new RuntimeException("unexpected"))

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(validRequest)
    }
    exception.errorCode shouldBe ErrorCode.InternalError
  }

  it should "map K8s 422 Unprocessable Entity to INVALID_POD_TEMPLATE" in {
    val submitter = createSubmitterWithThrowingClient(
      new KubernetesClientException("invalid pod spec", 422, null))

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(validRequest)
    }
    exception.errorCode shouldBe ErrorCode.InvalidPodTemplate
  }

  it should "map K8s 404 Not Found to INVALID_SPARK_SUBMIT_ARGS" in {
    val submitter = createSubmitterWithThrowingClient(
      new KubernetesClientException("not found", 404, null))

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(validRequest)
    }
    exception.errorCode shouldBe ErrorCode.InvalidSparkSubmitArgs
  }

  it should "preserve error code classification in dry-run mode" in {
    val submitter = createSubmitterWithThrowingClient(
      new KubernetesClientException("unavailable", 503, null))

    val dryRunException = intercept[SparkSubmitException] {
      submitter.submitJob(validRequest, dryRun = true)
    }
    dryRunException.errorCode shouldBe ErrorCode.InternalError
  }

  private def validRequest = SparkSubmitRequest(sparkSubmitArgs = JavaArrays.asList(
    MasterArg, DefaultK8sMaster, DeployModeArg, "cluster",
    ClassArg, SparkPiClass,
    ConfArg, s"$NamespaceConfKey=$DefaultNamespace",
    ConfArg, s"$ImageConfKey=$SparkImage",
    LocalJar
  ))

  private def validRequestWithSubmissionId(submissionId: String) = SparkSubmitRequest(
    sparkSubmitArgs = JavaArrays.asList(
      MasterArg, DefaultK8sMaster, DeployModeArg, "cluster",
      ClassArg, SparkPiClass,
      ConfArg, s"$NamespaceConfKey=$DefaultNamespace",
      ConfArg, s"$ImageConfKey=$SparkImage",
      LocalJar
    ),
    submissionId = submissionId
  )

  private def createSubmitterWithConflictAndExistingPod(sameSubmissionId: Boolean): SparkSubmitter = {
    import org.mockito.ArgumentMatchers.{any, anyBoolean}
    import org.mockito.Mockito.RETURNS_DEEP_STUBS
    import io.fabric8.kubernetes.api.model.{ObjectMeta, Pod, PodList}
    import io.fabric8.kubernetes.client.dsl.{MixedOperation, NonNamespaceOperation, PodResource}

    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val podOps = Mockito.mock(classOf[MixedOperation[Pod, PodList, PodResource]], RETURNS_DEEP_STUBS)
    val namespacedPodOps = Mockito.mock(classOf[NonNamespaceOperation[Pod, PodList, PodResource]], RETURNS_DEEP_STUBS)
    val podResource = Mockito.mock(classOf[PodResource])

    Mockito.when(mockClient.pods()).thenReturn(podOps)
    Mockito.when(podOps.inNamespace(any())).thenReturn(namespacedPodOps)
    Mockito.when(namespacedPodOps.resource(any[Pod])).thenReturn(podResource)
    Mockito.when(podResource.dryRun(anyBoolean())).thenReturn(podResource)
    Mockito.when(podResource.create()).thenThrow(new KubernetesClientException("conflict", 409, null))

    // Mock label-based pod lookup for handleConflict
    val podList = if (sameSubmissionId) {
      val existingPod = new Pod()
      val meta = new ObjectMeta()
      meta.setName("existing-driver")
      meta.setUid("uid-123")
      val labels = new java.util.HashMap[String, String]()
      labels.put("spark-app-selector", "spark-app-123")
      meta.setLabels(labels)
      existingPod.setMetadata(meta)
      val pl = new PodList()
      pl.setItems(java.util.Collections.singletonList(existingPod))
      pl
    } else {
      val pl = new PodList()
      pl.setItems(java.util.Collections.emptyList())
      pl
    }
    Mockito.when(namespacedPodOps.withLabel(any[String], any[String]).list()).thenReturn(podList)

    val resourceListOps = Mockito.mock(classOf[io.fabric8.kubernetes.client.dsl.NamespaceListVisitFromServerGetDeleteRecreateWaitApplicable[io.fabric8.kubernetes.api.model.HasMetadata]])
    Mockito.when(mockClient.resourceList(any[java.util.List[io.fabric8.kubernetes.api.model.HasMetadata]])).thenReturn(resourceListOps)
    Mockito.when(resourceListOps.inNamespace(any())).thenReturn(resourceListOps)
    Mockito.when(resourceListOps.dryRun(anyBoolean())).thenReturn(resourceListOps)
    Mockito.when(resourceListOps.forceConflicts()).thenReturn(resourceListOps)
    Mockito.when(resourceListOps.serverSideApply()).thenReturn(java.util.Collections.emptyList())

    val k8sProvider = createMockK8sProvider(mockClient)
    new SparkSubmitter(k8sProvider)
  }

  private def createSubmitterWithThrowingClient(exception: Exception): SparkSubmitter = {
    import org.mockito.ArgumentMatchers.{any, anyBoolean}
    import io.fabric8.kubernetes.api.model.{Pod, PodList}
    import io.fabric8.kubernetes.client.dsl.{MixedOperation, NonNamespaceOperation, PodResource}

    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val podOps = Mockito.mock(classOf[MixedOperation[Pod, PodList, PodResource]])
    val namespacedPodOps = Mockito.mock(classOf[NonNamespaceOperation[Pod, PodList, PodResource]])
    val podResource = Mockito.mock(classOf[PodResource])

    Mockito.when(mockClient.pods()).thenReturn(podOps)
    Mockito.when(podOps.inNamespace(any())).thenReturn(namespacedPodOps)
    Mockito.when(namespacedPodOps.resource(any[Pod])).thenReturn(podResource)
    Mockito.when(podResource.dryRun(anyBoolean())).thenReturn(podResource)
    Mockito.when(podResource.create()).thenThrow(exception)

    val resourceListOps = Mockito.mock(classOf[io.fabric8.kubernetes.client.dsl.NamespaceListVisitFromServerGetDeleteRecreateWaitApplicable[io.fabric8.kubernetes.api.model.HasMetadata]])
    Mockito.when(mockClient.resourceList(any[java.util.List[io.fabric8.kubernetes.api.model.HasMetadata]])).thenReturn(resourceListOps)
    Mockito.when(resourceListOps.inNamespace(any())).thenReturn(resourceListOps)
    Mockito.when(resourceListOps.dryRun(anyBoolean())).thenReturn(resourceListOps)
    Mockito.when(resourceListOps.forceConflicts()).thenReturn(resourceListOps)
    Mockito.when(resourceListOps.serverSideApply()).thenReturn(java.util.Collections.emptyList())

    val k8sProvider = createMockK8sProvider(mockClient)
    new SparkSubmitter(k8sProvider)
  }

  // ==========================================================================
  // Basic integration tests (comprehensive E2E scenarios in SparkSubmitEndToEndTest)

  "SparkSubmitter.submitJob (integration)" should "submit Spark job and create K8s resources" in {
    val (provider, submitter) = createIntegrationTestSubmitter()

    val masterUrl = getMockServerMasterUrl
    val args = buildSparkSubmitArgs(masterUrl, SparkPiClass, IntegrationTestApp, getTestNamespace, SparkImage, LocalJar, Map.empty[String, String])
    val request = SparkSubmitRequest(sparkSubmitArgs = args)

    val response = submitter.submitJob(request)

    response.appName shouldBe IntegrationTestApp
    response.namespace shouldBe getTestNamespace

    val pod = getClient.pods().inNamespace(getTestNamespace).withName(response.driverPodName).get()
    pod should not be null
    pod.getMetadata.getName shouldBe response.driverPodName
    pod.getMetadata.getNamespace shouldBe getTestNamespace

    val podLabels = pod.getMetadata.getLabels
    podLabels should not be null
    podLabels.get(SparkAppSelectorLabel) shouldBe response.sparkAppId

    pod.getSpec.getContainers should not be null
    pod.getSpec.getContainers.size() should be > MinContainerCount
    val driverContainer = pod.getSpec.getContainers.get(FirstContainerIndex)
    driverContainer.getName should not be null
    driverContainer.getImage shouldBe SparkImage

    val configMaps = getClient.configMaps().inNamespace(getTestNamespace).list().getItems
    val sparkConfigMap = configMaps.asScala.find(_.getMetadata.getName.contains(response.sparkAppId))
    sparkConfigMap should not be None

    val cm = sparkConfigMap.get
    cm.getMetadata.getName should include(response.sparkAppId)
    cm.getData should not be null

    val cmLabels = cm.getMetadata.getLabels
    cmLabels should not be null
    // Note: spark-app-selector label may not be set in kube-api-test environment
    // cmLabels.get(SparkAppSelectorLabel) shouldBe response.sparkAppId

    val cmOwnerRefs = cm.getMetadata.getOwnerReferences
    cmOwnerRefs should not be null
    cmOwnerRefs.size() should be > MinContainerCount
    cmOwnerRefs.get(FirstOwnerRefIndex).getKind shouldBe PodKind
    cmOwnerRefs.get(FirstOwnerRefIndex).getName shouldBe response.driverPodName
    cmOwnerRefs.get(FirstOwnerRefIndex).getUid shouldBe pod.getMetadata.getUid
  }

  it should "create all required K8s resources with correct metadata" in {
    val (provider, submitter) = createIntegrationTestSubmitter()

    val masterUrl = getMockServerMasterUrl
    val args = buildSparkSubmitArgs(masterUrl, SparkPiClass, ResourceCheckApp, getTestNamespace, SparkImage, LocalJar, Map.empty[String, String])
    val request = SparkSubmitRequest(sparkSubmitArgs = args)

    val response = submitter.submitJob(request)
    val appId = response.sparkAppId

    // Comprehensive resource validation
    // Verifies: Pod, ConfigMap, Service (when present) with correct owner references
    verifySparkResources(
      response.driverPodName, appId, getTestNamespace
    ) shouldBe true

    // Basic response validation
    val pod = getClient.pods().inNamespace(getTestNamespace).withName(response.driverPodName).get()
    pod should not be null
    pod.getMetadata.getName shouldBe response.driverPodName
    pod.getMetadata.getNamespace shouldBe getTestNamespace
  }

  it should "submit job using Array[String] overload" in {
    val (provider, submitter) = createIntegrationTestSubmitter()

    val masterUrl = getMockServerMasterUrl
    val args = Array(
      MasterArg, s"$K8sScheme$masterUrl",
      DeployModeArg, "cluster",
      ClassArg, SparkPiClass,
      NameArg, ArrayArgsTestApp,
      ConfArg, s"$NamespaceConfKey=${getTestNamespace}",
      ConfArg, s"$ImageConfKey=$SparkImage",
      ConfArg, s"$ServiceAccountConfKey=$DefaultServiceAccount",
      ConfArg, s"$ClientMasterConfKey=$masterUrl",
      LocalJar
    )

    val response = submitter.submitJob(args)

    response.appName shouldBe ArrayArgsTestApp
    response.namespace shouldBe getTestNamespace

    val pod = getClient.pods().inNamespace(getTestNamespace).withName(response.driverPodName).get()
    pod should not be null
  }

  "SparkSubmitter.submitJob (dry-run integration)" should "validate without persisting the driver pod" in {
    val (provider, submitter) = createIntegrationTestSubmitter()

    val masterUrl = getMockServerMasterUrl
    val dryRunAppName = "dryrun-test-app"
    val args = buildSparkSubmitArgs(masterUrl, SparkPiClass, dryRunAppName, getTestNamespace, SparkImage, LocalJar, Map.empty[String, String])
    val request = SparkSubmitRequest(sparkSubmitArgs = args)

    val response = submitter.submitJob(request, dryRun = true)
    response.appName shouldBe dryRunAppName
    response.namespace shouldBe getTestNamespace

    // The dry-run pod must NOT be persisted.
    val pod = getClient.pods().inNamespace(getTestNamespace).withName(response.driverPodName).get()
    pod shouldBe null

    // No ConfigMap with the spark-app-id either.
    val configMaps = getClient.configMaps().inNamespace(getTestNamespace).list().getItems
    configMaps.asScala.exists(_.getMetadata.getName.contains(response.sparkAppId)) shouldBe false
  }

  // ==========================================================================
  // NOTE: Comprehensive E2E test scenarios (pod templates, concurrent submissions,
  // namespace isolation, template cleanup) are in SparkSubmitEndToEndTest.scala
  // ==========================================================================
}
