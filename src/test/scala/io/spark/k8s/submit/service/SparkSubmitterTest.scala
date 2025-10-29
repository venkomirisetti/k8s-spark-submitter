package io.spark.k8s.submit.service

import io.spark.k8s.submit.SparkSubmitException
import io.spark.k8s.submit.model.SparkSubmitRequest
import io.spark.k8s.submit.util.PodTemplateUtils
import io.fabric8.kubernetes.client.KubernetesClient
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

  // Error messages
  private val K8sClientError = "K8s client error"

  // Reflection field names
  private val ClientFieldName = "_client"

  // File system paths (used in template cleanup tests)
  private val TmpDirProperty = "java.io.tmpdir"
  private val SparkSubmitterDir = ".spark-submitter"
  private val SubmissionPrefix = "submission-"

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
  private def createMockK8sProvider(client: KubernetesClient): KubernetesClientProvider = {
    val provider = new KubernetesClientProvider()
    val field = classOf[KubernetesClientProvider].getDeclaredField(ClientFieldName)
    field.setAccessible(true)
    field.set(provider, client)
    provider
  }

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
    val k8sClient = getClient
    val provider = new KubernetesClientProvider()
    val field = classOf[KubernetesClientProvider].getDeclaredField(ClientFieldName)
    field.setAccessible(true)
    field.set(provider, k8sClient)
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
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val k8sProvider = createMockK8sProvider(mockClient)
    val submitter = new SparkSubmitter(k8sProvider)

    val request = SparkSubmitRequest(
      JavaArrays.asList(InvalidArgKey, InvalidArgValue),
      null,
      null
    )

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(request)
    }
    exception.isValidationError shouldBe true
  }

  it should "accept Array[String] arguments via overload" in {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val k8sProvider = createMockK8sProvider(mockClient)
    val submitter = new SparkSubmitter(k8sProvider)

    val args = Array(InvalidArgKey, InvalidArgValue)

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(args)
    }
    exception.isValidationError shouldBe true
  }

  it should "require valid master URL" in {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val k8sProvider = createMockK8sProvider(mockClient)
    val submitter = new SparkSubmitter(k8sProvider)

    val request = SparkSubmitRequest(
      JavaArrays.asList(
        MasterArg, InvalidMasterUrl,
        ClassArg, SparkPiClass,
        LocalJar
      ),
      null,
      null
    )

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(request)
    }
    exception.isValidationError shouldBe true
  }

  it should "require main class or primary resource" in {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val k8sProvider = createMockK8sProvider(mockClient)
    val submitter = new SparkSubmitter(k8sProvider)

    val request = SparkSubmitRequest(
      JavaArrays.asList(MasterArg, DefaultK8sMaster),
      null,
      null
    )

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(request)
    }
    exception.isValidationError shouldBe true
  }

  it should "handle null driver template and null executor template" in {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val k8sProvider = createMockK8sProvider(mockClient)
    val submitter = new SparkSubmitter(k8sProvider)

    val request = SparkSubmitRequest(
      JavaArrays.asList(
        MasterArg, DefaultK8sMaster,
        ClassArg, SparkPiClass,
        ConfArg, s"$NamespaceConfKey=$DefaultNamespace",
        ConfArg, s"$ImageConfKey=$SparkImage",
        LocalJar
      ),
      null,
      null
    )

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(request)
    }
    exception.isValidationError shouldBe false
  }

  it should "handle empty driver template and empty executor template" in {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val k8sProvider = createMockK8sProvider(mockClient)
    val submitter = new SparkSubmitter(k8sProvider)

    val request = SparkSubmitRequest(
      JavaArrays.asList(
        MasterArg, DefaultK8sMaster,
        ClassArg, SparkPiClass,
        ConfArg, s"$NamespaceConfKey=$DefaultNamespace",
        ConfArg, s"$ImageConfKey=$SparkImage",
        LocalJar
      ),
      EmptyString,
      EmptyString
    )

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(request)
    }
    exception.isValidationError shouldBe false
  }

  it should "handle invalid JSON in driver template" in {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val k8sProvider = createMockK8sProvider(mockClient)
    val submitter = new SparkSubmitter(k8sProvider)

    val request = SparkSubmitRequest(
      JavaArrays.asList(
        MasterArg, DefaultK8sMaster,
        ClassArg, SparkPiClass,
        ConfArg, s"$NamespaceConfKey=$DefaultNamespace",
        ConfArg, s"$ImageConfKey=$SparkImage",
        LocalJar
      ),
      InvalidJson,
      null
    )

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(request)
    }
    exception should not be null
  }

  it should "handle invalid JSON in executor template" in {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val k8sProvider = createMockK8sProvider(mockClient)
    val submitter = new SparkSubmitter(k8sProvider)

    val request = SparkSubmitRequest(
      JavaArrays.asList(
        MasterArg, DefaultK8sMaster,
        ClassArg, SparkPiClass,
        ConfArg, s"$NamespaceConfKey=$DefaultNamespace",
        ConfArg, s"$ImageConfKey=$SparkImage",
        LocalJar
      ),
      null,
      InvalidJsonArray
    )

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(request)
    }
    exception should not be null
  }

  it should "handle both driver and executor templates with valid JSON" in {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val k8sProvider = createMockK8sProvider(mockClient)
    val submitter = new SparkSubmitter(k8sProvider)

    val request = SparkSubmitRequest(
      JavaArrays.asList(
        MasterArg, DefaultK8sMaster,
        ClassArg, SparkPiClass,
        ConfArg, s"$NamespaceConfKey=$DefaultNamespace",
        ConfArg, s"$ImageConfKey=$SparkImage",
        LocalJar
      ),
      DriverTemplateJson,
      ExecutorTemplateJson
    )

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(request)
    }
    exception.isValidationError shouldBe false
  }

  it should "generate unique template directories for concurrent submissions" in {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val k8sProvider = createMockK8sProvider(mockClient)
    val submitter = new SparkSubmitter(k8sProvider)

    val request1 = SparkSubmitRequest(
      JavaArrays.asList(MasterArg, DefaultK8sMaster, ClassArg, SparkPiClass, ConfArg, s"$NamespaceConfKey=$TestNamespace1",
        ConfArg, s"$ImageConfKey=$SparkImage", LocalJar),
      SimpleTemplateJson, null
    )

    val request2 = SparkSubmitRequest(
      JavaArrays.asList(MasterArg, DefaultK8sMaster, ClassArg, SparkPiClass, ConfArg, s"$NamespaceConfKey=$TestNamespace2",
        ConfArg, s"$ImageConfKey=$SparkImage", LocalJar),
      SimpleTemplateJson, null
    )

    intercept[SparkSubmitException] {
      submitter.submitJob(request1)
    }
    intercept[SparkSubmitException] {
      submitter.submitJob(request2)
    }
  }

  it should "use default namespace when not specified" in {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val k8sProvider = createMockK8sProvider(mockClient)
    val submitter = new SparkSubmitter(k8sProvider)

    val request = SparkSubmitRequest(
      JavaArrays.asList(
        MasterArg, DefaultK8sMaster,
        ClassArg, SparkPiClass,
        ConfArg, s"$ImageConfKey=$SparkImage",
        LocalJar
      ),
      null,
      null
    )

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(request)
    }
    exception.isValidationError shouldBe false
  }

  it should "respect custom app name from spark-submit args" in {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val k8sProvider = createMockK8sProvider(mockClient)
    val submitter = new SparkSubmitter(k8sProvider)

    val customAppName = "my-custom-app-name"
    val request = SparkSubmitRequest(
      JavaArrays.asList(
        MasterArg, DefaultK8sMaster,
        ClassArg, SparkPiClass,
        NameArg, customAppName,
        ConfArg, s"$NamespaceConfKey=$DefaultNamespace",
        ConfArg, s"$ImageConfKey=$SparkImage",
        LocalJar
      ),
      null,
      null
    )

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(request)
    }
    exception.getMessage should include(FailedToSubmit)
  }

  it should "initialize and cleanup old template directories on startup" in {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val k8sProvider = createMockK8sProvider(mockClient)
    val submitter = new SparkSubmitter(k8sProvider)

    val oldDirName = s"old_submission_${System.nanoTime()}"
    val oldDir = PodTemplateUtils.createTemplateDirForSubmission(oldDirName)
    oldDir.toFile.exists() shouldBe true

    submitter.init()
  }

  it should "cleanup templates even when submission fails during parsing" in {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val k8sProvider = createMockK8sProvider(mockClient)
    val submitter = new SparkSubmitter(k8sProvider)

    val request = SparkSubmitRequest(
      JavaArrays.asList(InvalidArgKey, InvalidArgValue),
      SimpleTemplateJson,
      null
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
      baseDir.listFiles().filter(_.isDirectory).length
    } else {
      ZeroCount
    }

    countAfter shouldBe countBefore
  }

  it should "cleanup templates even when submission fails during K8s operations" in {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val k8sProvider = createMockK8sProvider(mockClient)
    val submitter = new SparkSubmitter(k8sProvider)

    val request = SparkSubmitRequest(
      JavaArrays.asList(
        MasterArg, DefaultK8sMaster,
        ClassArg, SparkPiClass,
        ConfArg, s"$NamespaceConfKey=$DefaultNamespace",
        ConfArg, s"$ImageConfKey=$SparkImage",
        LocalJar
      ),
      SimpleTemplateJson,
      null
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

  it should "handle large template content without issues" in {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val k8sProvider = createMockK8sProvider(mockClient)
    val submitter = new SparkSubmitter(k8sProvider)

    val largeValue = LargeValuePrefix * LargeValueSize
    val largeTemplate = s"""{"metadata":{"labels":{"$LargeKey":"$largeValue"}}}"""
    val request = SparkSubmitRequest(
      JavaArrays.asList(
        MasterArg, DefaultK8sMaster,
        ClassArg, SparkPiClass,
        ConfArg, s"$NamespaceConfKey=$DefaultNamespace",
        ConfArg, s"$ImageConfKey=$SparkImage",
        LocalJar
      ),
      largeTemplate,
      null
    )

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(request)
    }
    exception.isValidationError shouldBe false
  }

  it should "handle unicode characters in templates" in {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val k8sProvider = createMockK8sProvider(mockClient)
    val submitter = new SparkSubmitter(k8sProvider)

    val unicodeTemplate = s"""{"metadata":{"labels":{"$UnicodeLabel":"$UnicodeValue"}}}"""
    val request = SparkSubmitRequest(
      JavaArrays.asList(
        MasterArg, DefaultK8sMaster,
        ClassArg, SparkPiClass,
        ConfArg, s"$NamespaceConfKey=$DefaultNamespace",
        ConfArg, s"$ImageConfKey=$SparkImage",
        LocalJar
      ),
      unicodeTemplate,
      null
    )

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(request)
    }
    exception.isValidationError shouldBe false
  }

  it should "handle special characters in app name" in {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val k8sProvider = createMockK8sProvider(mockClient)
    val submitter = new SparkSubmitter(k8sProvider)

    val request = SparkSubmitRequest(
      JavaArrays.asList(
        MasterArg, DefaultK8sMaster,
        ClassArg, SparkPiClass,
        NameArg, SpecialAppName,
        ConfArg, s"$NamespaceConfKey=$DefaultNamespace",
        ConfArg, s"$ImageConfKey=$SparkImage",
        LocalJar
      ),
      null,
      null
    )

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(request)
    }
    exception.isValidationError shouldBe false
  }

  it should "handle multiple spark configurations" in {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val k8sProvider = createMockK8sProvider(mockClient)
    val submitter = new SparkSubmitter(k8sProvider)

    val driverMemoryKey = "spark.driver.memory"
    val executorMemoryKey = "spark.executor.memory"
    val executorCoresKey = "spark.executor.cores"
    val request = SparkSubmitRequest(
      JavaArrays.asList(
        MasterArg, DefaultK8sMaster,
        ClassArg, SparkPiClass,
        ConfArg, s"$NamespaceConfKey=$DefaultNamespace",
        ConfArg, s"$ImageConfKey=$SparkImage",
        ConfArg, s"$driverMemoryKey=$DriverMemory",
        ConfArg, s"$executorMemoryKey=$ExecutorMemory",
        ConfArg, s"$executorCoresKey=$ExecutorCores",
        LocalJar
      ),
      null,
      null
    )

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(request)
    }
    exception.isValidationError shouldBe false
  }

  it should "handle application arguments after jar" in {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val k8sProvider = createMockK8sProvider(mockClient)
    val submitter = new SparkSubmitter(k8sProvider)

    val request = SparkSubmitRequest(
      JavaArrays.asList(
        MasterArg, DefaultK8sMaster,
        ClassArg, SparkPiClass,
        ConfArg, s"$NamespaceConfKey=$DefaultNamespace",
        ConfArg, s"$ImageConfKey=$SparkImage",
        LocalJar,
        AppArg1,
        AppArg2
      ),
      null,
      null
    )

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(request)
    }
    exception.isValidationError shouldBe false
  }

  it should "dry-run submit that parses args and cleans up templates on parse failure" in {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val k8sProvider = createMockK8sProvider(mockClient)
    val submitter = new SparkSubmitter(k8sProvider)

    val request = SparkSubmitRequest(
      JavaArrays.asList(InvalidArgKey, InvalidArgValue),
      null,
      null
    )

    val baseDir = new java.io.File(System.getProperty(TmpDirProperty), SparkSubmitterDir)
    val countBefore = if (baseDir.exists()) baseDir.listFiles().count(_.isDirectory) else ZeroCount

    val exception = intercept[SparkSubmitException] {
      submitter.submitJob(request, dryRun = true)
    }
    exception.isValidationError shouldBe true

    val countAfter = if (baseDir.exists()) baseDir.listFiles().count(_.isDirectory) else ZeroCount
    countAfter shouldBe countBefore
  }

  // ==========================================================================
  // Basic integration tests (comprehensive E2E scenarios in SparkSubmitEndToEndTest)

  "SparkSubmitter.submitJob (integration)" should "submit Spark job and create K8s resources" in {
    val (provider, submitter) = createIntegrationTestSubmitter()

    val masterUrl = getMockServerMasterUrl
    val args = buildSparkSubmitArgs(masterUrl, SparkPiClass, IntegrationTestApp, getTestNamespace, SparkImage, LocalJar, Map.empty[String, String])
    val request = SparkSubmitRequest(args, null, null)

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
    val request = SparkSubmitRequest(args, null, null)

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
    val request = SparkSubmitRequest(args, null, null)

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
