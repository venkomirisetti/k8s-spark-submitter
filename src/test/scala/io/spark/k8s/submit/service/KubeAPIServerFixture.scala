package io.spark.k8s.submit.service

import io.fabric8.kubernetes.api.model.{ConfigMap, NamespaceBuilder, Service}
import io.fabric8.kubernetes.client.{Config, KubernetesClient, KubernetesClientBuilder}
import io.fabric8.kubeapitest.KubeAPIServer
import org.scalatest.{BeforeAndAfterAll, Suite}

import java.util.UUID
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Try}

/**
 * Test fixture providing real Kubernetes API server for integration tests.
 * Manages K8s server lifecycle, namespaces, client access, template loading, and argument building.
 * Note: Pods remain in "Pending" state (no kubelet in test environment).
 */
trait KubeAPIServerFixture extends BeforeAndAfterAll {
  this: Suite =>

  private val CustomNamespaceName = "custom-namespace"

  private var kubeApiServer: KubeAPIServer = _
  private var testClient: KubernetesClient = _
  private var testNamespace: String = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    try {
      startKubeAPIServer()
      createTestClient()
      createTestNamespaces()
    } catch {
      case e: Exception =>
        cleanupResources()
        throw new RuntimeException(s"Failed to initialize test environment: ${e.getMessage}", e)
    }
    }

  override def afterAll(): Unit = {
    cleanupResources()
    super.afterAll()
    }

  private def startKubeAPIServer(): Unit = {
    kubeApiServer = new KubeAPIServer()
    kubeApiServer.start()
    }

  private def createTestClient(): Unit = {
    val kubeConfigYaml = kubeApiServer.getKubeConfigYaml
    val config = Config.fromKubeconfig(kubeConfigYaml)
    testClient = new KubernetesClientBuilder().withConfig(config).build()
    }

  /**
   * Creates isolated test namespaces:
   * - test-<uuid>: Unique namespace for this test suite
   * - custom-namespace: For namespace isolation/override tests
   */
  private def createTestNamespaces(): Unit = {
    // Create unique namespace for test isolation
    testNamespace = s"test-${UUID.randomUUID().toString.take(8)}"
    createNamespace(testNamespace)

    // Create custom namespace for namespace-specific tests
    createNamespace(CustomNamespaceName)
    }

  private def createNamespace(name: String): Unit = {
    testClient.namespaces().resource(
      new NamespaceBuilder()
        .withNewMetadata()
        .withName(name)
        .endMetadata()
        .build()
    ).create()
    }

  /**
   * Cleans up all resources (namespaces, client, server) with error recovery.
   * Collects all cleanup errors but doesn't fail - logs warnings instead.
   * This ensures cleanup continues even if individual steps fail.
   */
  private def cleanupResources(): Unit = {
    val errors = scala.collection.mutable.ArrayBuffer[String]()

    // Clean up namespaces
    safeCleanup("delete test namespace") {
      if (testClient != null && testNamespace != null) {
        testClient.namespaces().withName(testNamespace).delete()
      }
    }.failed.foreach(e => errors += e.getMessage)

    safeCleanup("delete custom namespace") {
      if (testClient != null) {
        testClient.namespaces().withName(CustomNamespaceName).delete()
      }
    }.failed.foreach(e => errors += e.getMessage)

    // Close client
    safeCleanup("close Kubernetes client") {
      if (testClient != null) {
        testClient.close()
        testClient = null
      }
    }.failed.foreach(e => errors += e.getMessage)

    // Stop server
    safeCleanup("stop KubeAPIServer") {
      if (kubeApiServer != null) {
        kubeApiServer.stop()
        kubeApiServer = null
      }
    }.failed.foreach(e => errors += e.getMessage)

    // Log warnings if any cleanup failed
    if (errors.nonEmpty) {
      System.err.println("Warnings during cleanup:")
      errors.foreach(err => System.err.println(s"  - $err"))
    }
    }

  private def safeCleanup(operation: String)(block: => Unit): Try[Unit] = {
    Try(block).recoverWith {
      case e: Exception => Failure(new Exception(s"Failed to $operation: ${e.getMessage}", e))
    }
    }

    /** Returns the KubernetesClient connected to the test API server. */
  protected def getKubeClient: KubernetesClient = testClient

  /** Returns the unique test namespace for this test suite. */
  protected def getTestNamespace: String = testNamespace

    /**
   * Loads a pod template JSON file from test resources.
   *
   * Template files are stored in src/test/resources/pod-templates/ directory.
   * This provides cleaner test code and easier template maintenance compared to inline JSON strings.
   *
   * @param filename Template filename (e.g., "driver-template.json")
   * @return Template JSON as string, or throws exception if file not found
   */
  def loadPodTemplate(filename: String): String = {
    val resourcePath = s"/pod-templates/$filename"
    val stream = getClass.getResourceAsStream(resourcePath)

    if (stream == null) {
      throw new IllegalArgumentException(s"Template resource not found: $resourcePath")
    }

    try {
      scala.io.Source.fromInputStream(stream).mkString
    } finally {
      stream.close()
    }
    }

    /** Spark submit argument names */
  private object SparkArgs {
    val Master = "--master"
    val Class = "--class"
    val Name = "--name"
    val Conf = "--conf"
    }

  /** Spark configuration keys */
  private object SparkConf {
    val Namespace = "spark.kubernetes.namespace"
    val Image = "spark.kubernetes.container.image"
    val ServiceAccount = "spark.kubernetes.authenticate.driver.serviceAccountName"
    val ClientMaster = "spark.kubernetes.client.master"
    }

  /** Default values for Spark configuration */
  private object SparkDefaults {
    val K8sScheme = "k8s://"
    val ServiceAccount = "default"
    }

  /**
   * Builds standard spark-submit argument list for testing.
   *
   * Creates a complete argument list with:
   * - Master URL (k8s://<masterUrl>)
   * - Main class and application name
   * - Required K8s configuration (namespace, image, service account)
   * - Optional additional Spark configuration
   * - JAR file path
   *
   * Example output:
   * --master k8s://https://localhost:6443
   * --class org.example.Main
   * --name my-app
   * --conf spark.kubernetes.namespace=test-abc123
   * --conf spark.kubernetes.container.image=spark:3.5.5
   * --conf spark.kubernetes.authenticate.driver.serviceAccountName=default
   * --conf spark.kubernetes.client.master=https://localhost:6443
   * local:///app.jar
   *
   * @param masterUrl Kubernetes API server URL
   * @param mainClass Spark application main class
   * @param appName Application name (shown in Spark UI)
   * @param namespace K8s namespace for pod creation
   * @param image Container image (e.g., "spark:3.5.5")
   * @param jar JAR file path (e.g., "local:///app.jar")
   * @param additionalConf Optional additional Spark configuration key-value pairs
   * @return Java List of spark-submit arguments
   */
  def buildSparkSubmitArgs(
      masterUrl: String,
      mainClass: String,
      appName: String,
      namespace: String,
      image: String,
      jar: String,
      additionalConf: Map[String, String] = Map.empty
  ): java.util.List[String] = {
    import java.util.{Arrays => JavaArrays}

    // Build base arguments
    val baseArgs = Seq(
      SparkArgs.Master, s"${SparkDefaults.K8sScheme}$masterUrl",
      SparkArgs.Class, mainClass,
      SparkArgs.Name, appName,
      SparkArgs.Conf, s"${SparkConf.Namespace}=$namespace",
      SparkArgs.Conf, s"${SparkConf.Image}=$image",
      SparkArgs.Conf, s"${SparkConf.ServiceAccount}=${SparkDefaults.ServiceAccount}",
      SparkArgs.Conf, s"${SparkConf.ClientMaster}=$masterUrl"
    )

    // Add optional configuration
    val confArgs = additionalConf.flatMap { case (key, value) =>
      Seq(SparkArgs.Conf, s"$key=$value")
    }

    // Convert to Java List
    JavaArrays.asList((baseArgs ++ confArgs :+ jar): _*)
    }

  private val ResourceKindPod = "Pod"

  /**
   * Verifies that a Pod exists in the specified namespace.
   */
  def verifyPodExists(podName: String, namespace: String): Boolean = {
    Try {
      Option(testClient.pods().inNamespace(namespace).withName(podName).get()).isDefined
    }.getOrElse(false)
  }

  /**
   * Finds a ConfigMap by Spark application ID.
   * Searches all ConfigMaps in the namespace for one containing the app ID in its name.
   */
  def findConfigMapByAppId(appId: String, namespace: String): Option[ConfigMap] = {
    Try {
      val configMaps = testClient.configMaps().inNamespace(namespace).list().getItems
      configMaps.asScala.find(_.getMetadata.getName.contains(appId))
    }.getOrElse(None)
  }

  /**
   * Finds a Service by driver pod name.
   * Searches for service that starts with driverPodName and ends with "driver-svc".
   * Pattern: {driverPodName}-*driver-svc (e.g., "my-driver" -> "my-driver-svc")
   */
  def findServiceByDriverPodName(driverPodName: String, namespace: String): Option[Service] = {
    Try {
      val services = testClient.services().inNamespace(namespace).list().getItems
      services.asScala.find { svc =>
        val name = svc.getMetadata.getName
        name.startsWith(driverPodName) && name.endsWith("driver-svc")
      }
    }.getOrElse(None)
  }

  /**
   * Verifies all K8s resources for a Spark application.
   * Validates Pod, ConfigMap, and Service (optional) with correct owner references.
   */
  def verifySparkResources(
      driverPodName: String,
      appId: String,
      namespace: String
  ): Boolean = {
    if (!verifyPodExists(driverPodName, namespace)) {
      System.err.println(s"Driver pod not found: $driverPodName")
      return false
    }

    val configMapValid = findConfigMapByAppId(appId, namespace).exists { configMap =>
      val ownerRefs = configMap.getMetadata.getOwnerReferences
      Option(ownerRefs).exists { refs =>
        !refs.isEmpty && {
          val ownerRef = refs.get(0)
          ownerRef.getKind == ResourceKindPod && ownerRef.getName == driverPodName
        }
      }
    }

    if (!configMapValid) {
      System.err.println(s"ConfigMap validation failed for appId: $appId")
      return false
    }

    findServiceByDriverPodName(driverPodName, namespace) match {
      case Some(service) =>
        val ownerRefs = service.getMetadata.getOwnerReferences
        val serviceValid = Option(ownerRefs).exists { refs =>
          !refs.isEmpty && {
            val ownerRef = refs.get(0)
            ownerRef.getKind == ResourceKindPod && ownerRef.getName == driverPodName
          }
        }
        if (!serviceValid) {
          System.err.println(s"Service exists but owner reference validation failed for driver: $driverPodName")
          return false
        }
      case None =>
        System.err.println(s"Service not found for driver: $driverPodName (may be expected in test environments)")
    }

    true
  }

  /**
   * Verifies that pod container has SPARK_CONF_DIR environment variable.
   * Our code explicitly adds this in K8sSparkClient.buildDriverPod().
   */
  def verifyPodHasSparkConfDirEnv(podName: String, namespace: String): Boolean = {
    Try {
      val pod = testClient.pods().inNamespace(namespace).withName(podName).get()
      if (pod == null || pod.getSpec.getContainers.isEmpty) return false

      val container = pod.getSpec.getContainers.get(0)
      container.getEnv.asScala.exists { env =>
        env.getName == "SPARK_CONF_DIR" && env.getValue == "/opt/spark/conf"
      }
    }.getOrElse(false)
  }

  /**
   * Verifies that pod container has ConfigMap volume mount.
   * Our code explicitly adds this in K8sSparkClient.buildDriverPod().
   */
  def verifyPodHasConfigMapVolumeMount(podName: String, namespace: String): Boolean = {
    Try {
      val pod = testClient.pods().inNamespace(namespace).withName(podName).get()
      if (pod == null || pod.getSpec.getContainers.isEmpty) return false

      val container = pod.getSpec.getContainers.get(0)
      container.getVolumeMounts.asScala.exists { mount =>
        mount.getName == "spark-conf-volume-driver" && mount.getMountPath == "/opt/spark/conf"
      }
    }.getOrElse(false)
  }

  /**
   * Verifies that pod has ConfigMap volume.
   * Our code explicitly adds this in K8sSparkClient.buildDriverPod().
   */
  def verifyPodHasConfigMapVolume(podName: String, namespace: String): Boolean = {
    Try {
      val pod = testClient.pods().inNamespace(namespace).withName(podName).get()
      if (pod == null) return false

      pod.getSpec.getVolumes.asScala.exists { volume =>
        volume.getName == "spark-conf-volume-driver" &&
        volume.getConfigMap != null
      }
    }.getOrElse(false)
  }

  /**
   * Verifies that ConfigMap has the namespace key that our code explicitly adds.
   * Our code adds this in K8sSparkClient.buildConfigMap(): + (K8sNamespaceKey -> conf.namespace)
   */
  def verifyConfigMapHasNamespaceKey(appId: String, expectedNamespace: String, namespace: String): Boolean = {
    findConfigMapByAppId(appId, namespace).exists { cm =>
      val data = cm.getData
      data != null &&
      data.containsKey("spark.kubernetes.namespace") &&
      data.get("spark.kubernetes.namespace") == expectedNamespace
    }
  }
}
