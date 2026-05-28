package org.apache.spark.deploy.k8s.submit

import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.client.KubernetesClient
import io.spark.k8s.submit.{LogPrefix, SparkConstants}
import org.apache.spark.deploy.K8sSparkSubmitArgs
import org.apache.spark.deploy.k8s.{Config, _}
import org.slf4j.{Logger, LoggerFactory}

import scala.jdk.CollectionConverters._
import scala.util.Try

/** Result of Spark driver pod submission. */
case class SubmissionResult(sparkAppId: String, driverPodName: String, driverPodUid: String, namespace: String)

/**
 * Submits Spark driver pods to Kubernetes using Spark's internal APIs.
 *
 * Package: org.apache.spark.deploy.k8s.submit - required to access package-private classes:
 *   - KubernetesDriverBuilder: builds driver pod spec via feature steps
 *   - KubernetesClientUtils: builds ConfigMaps for Spark configuration
 *   - JavaMainAppResource: represents application JAR
 *
 * Why not use Spark's Client directly?
 * Spark's Client uses a singleton for ConfigMap naming, causing collisions when
 * submitting multiple jobs from the same JVM. We fix this by generating unique
 * ConfigMap names per submission via KubernetesClientUtils.configMapName().
 *
 * Architecture:
 * 1. buildFromFeatures() builds pod spec but does NOT build ConfigMap (same as Spark's Client)
 * 2. We build ConfigMap separately with unique name per submission
 * 3. We mount ConfigMap as volume in driver pod
 * 4. Some methods are marked private[submit] for unit testability
 */
object K8sSparkClient {

  private val log: Logger = LoggerFactory.getLogger(getClass)

  // Kubernetes config keys (exposed for service layer)
  val K8sNamespaceKey: String = Config.KUBERNETES_NAMESPACE.key
  val K8sNamespaceDefault: String = Config.KUBERNETES_NAMESPACE.defaultValueString
  val K8sDriverTemplateKey: String = Config.KUBERNETES_DRIVER_PODTEMPLATE_FILE.key
  val K8sExecutorTemplateKey: String = Config.KUBERNETES_EXECUTOR_PODTEMPLATE_FILE.key

  /**
   * Submits driver pod. Generates app ID if not set, then builds and creates driver pod.
   * Throws raw exceptions (KubernetesClientException, IOException, etc.) — error translation
   * to API contract is handled by the service layer (SparkSubmitter).
   */
  def submit(args: K8sSparkSubmitArgs, client: KubernetesClient, dryRun: Boolean = false): SubmissionResult = {
    val conf = args.sparkConf

    // Generate Kubernetes app ID if not already set (matches native spark-submit behavior: "spark-{UUID}")
    conf.setIfMissing(SparkConstants.AppId, KubernetesConf.getKubernetesAppId())
    val appId = conf.get(SparkConstants.AppId)

    // Step 1: Build KubernetesDriverConf from SparkConf
    val driverConf = new KubernetesDriverConf(
      conf, appId,
      args.mainAppResource,
      Option(args.mainClass).getOrElse(""),
      args.appArgs,
      args.proxyUser
    )

    // Step 2: Build driver spec using Spark's feature steps (pod, service, secrets, volumes, etc.)
    val spec = new KubernetesDriverBuilder().buildFromFeatures(driverConf, client)

    // Step 3: Build ConfigMap with spark.properties (unique name per submission)
    val configMap = buildConfigMap(driverConf, spec)

    // Step 4: Build driver pod with ConfigMap volume mount
    val pod = buildDriverPod(spec, configMap)

    // Step 5: Collect pre-resources (secrets, SAs) and post-resources (service, ConfigMap)
    val pre = spec.driverPreKubernetesResources.toList
    val post = spec.driverKubernetesResources.toList :+ configMap
    logResourceSummary(pre, post)

    // Step 6: Create all resources in Kubernetes and extract auto-generated pod UID from metadata
    val driverPodUid = createResources(client, driverConf.namespace, pod, pre, post, dryRun)
    SubmissionResult(appId, pod.getMetadata.getName, driverPodUid.orNull, driverConf.namespace)
  }

  /**
   * Builds ConfigMap with Spark configuration files.
   * Note: buildFromFeatures() does NOT build ConfigMap, so we build it separately here.
   * Uses unique name per submission to avoid collisions in multi-job JVM scenarios.
   */
  private def buildConfigMap(conf: KubernetesDriverConf, spec: KubernetesDriverSpec): ConfigMap = {
    val name = KubernetesClientUtils.configMapName(s"${conf.appId}${SparkConstants.DriverPodSuffix}")
    val sparkConfFiles = KubernetesClientUtils.buildSparkConfDirFilesMap(name, conf.sparkConf, spec.systemProperties)
    val configData = sparkConfFiles + (K8sNamespaceKey -> conf.namespace)
    KubernetesClientUtils.buildConfigMap(name, configData, Map.empty)
  }

  private def buildDriverPod(spec: KubernetesDriverSpec, configMap: ConfigMap): Pod = {
    val configMapName = configMap.getMetadata.getName
    val configData = Option(configMap.getData).fold(Map.empty[String, String])(_.asScala.toMap)

    // Add SPARK_CONF_DIR env and mount ConfigMap as volume
    val container = new ContainerBuilder(spec.pod.container)
      .addNewEnv().withName(Constants.ENV_SPARK_CONF_DIR).withValue(Constants.SPARK_CONF_DIR_INTERNAL).endEnv()
      .addNewVolumeMount().withName(Constants.SPARK_CONF_VOLUME_DRIVER).withMountPath(Constants.SPARK_CONF_DIR_INTERNAL).endVolumeMount()
      .build()

    // Build volume items from config data keys
    val volumeItems = buildVolumeItems(configData)

    new PodBuilder(spec.pod.pod).editSpec()
      .addToContainers(container)
      .addNewVolume().withName(Constants.SPARK_CONF_VOLUME_DRIVER)
      .withNewConfigMap()
      .withItems(volumeItems)
      .withName(configMapName)
      .endConfigMap()
      .endVolume()
      .endSpec().build()
  }

  /**
   * Builds volume items from ConfigMap data keys.
   * Extracted to separate method for testability.
   */
  private def buildVolumeItems(configData: Map[String, String]): java.util.List[KeyToPath] = {
    configData.keys.map(k => new KeyToPathBuilder().withKey(k).withPath(k).build()).toList.asJava
  }

  /**
   * Logs resource summary for debugging.
   * Extracted to separate method for testability.
   */
  private def logResourceSummary(pre: List[HasMetadata], post: List[HasMetadata]): Unit = {
    val preFormatted = formatResources(pre)
    val postFormatted = formatResources(post)
    log.debug(s"${LogPrefix.Resources} pre=$preFormatted, post=$postFormatted")
  }

  /**
   * Formats resources for logging (e.g., "Pod/my-pod, ConfigMap/my-cm").
   * Marked private[submit] for unit testability.
   */
  private[submit] def formatResources(resources: List[HasMetadata]): String =
    if (resources.isEmpty) "[]"
    else resources.map(r => s"${r.getKind}/${r.getMetadata.getName}").mkString("[", ", ", "]")

  /**
   * Applies resources with owner reference to Kubernetes.
   * Adds owner reference to resources and applies them using server-side apply.
   */
  private def addOwnerReference(client: KubernetesClient, ns: String, owner: Pod,
                                resources: List[HasMetadata], isDryRun: Boolean): Unit = {
    if (resources.nonEmpty) {
      KubernetesUtils.addOwnerReference(owner, resources)
      client.resourceList(resources.asJava).inNamespace(ns).dryRun(isDryRun).forceConflicts().serverSideApply()
    }
  }

  /**
   * Creates resources in Kubernetes in the correct order:
   * 1. Pre-resources (secrets, service accounts)
   * 2. Driver pod
   * 3. Patch pre-resources with owner reference (skipped for dry-run)
   * 4. Post-resources (service, ConfigMap) with owner reference (skipped for dry-run)
   *
   * When dryRun=true, uses Kubernetes server-side validation (?dryRun=All):
   * - Validates RBAC, schema, admission webhooks, quotas, conflicts
   * - Nothing persisted to etcd
   * - Skips owner reference patching (no real pod UID)
   *
   * Returns the UID of the created driver pod.
   * Marked private[submit] for unit testability.
   */
  private[submit] def createResources(client: KubernetesClient, ns: String, pod: Pod,
                                      pre: List[HasMetadata], post: List[HasMetadata],
                                      isDryRun: Boolean = false): Option[String] = {
    var driverPod: Option[Pod] = None
    try {
      // Step 1: Create pre-resources (secrets, service accounts)
      if (pre.nonEmpty) {
        client.resourceList(pre.asJava).inNamespace(ns).dryRun(isDryRun).forceConflicts().serverSideApply()
      }

      // Step 2: Create driver pod
      driverPod = Some(client.pods().inNamespace(ns).resource(pod).dryRun(isDryRun).create())

      // Step 3: Add owner reference to pre-resources (for garbage collection)
      addOwnerReference(client, ns, driverPod.get, pre, isDryRun)

      // Step 4: Create post-resources (service, ConfigMap) with owner reference
      addOwnerReference(client, ns, driverPod.get, post, isDryRun)

      // Log success and return pod UID (extracted from Kubernetes metadata)
      val podUid = driverPod.map(_.getMetadata.getUid)
      val podName = driverPod.map(_.getMetadata.getName)
      if (isDryRun) {
        log.debug(s"${LogPrefix.DryRun} Validation passed: pod=$podName, podUid=$podUid")
      } else {
        val appId = driverPod.map(_.getMetadata.getLabels.get(SparkConstants.SparkAppSelectorLabel))
        log.debug(s"${LogPrefix.Success} Driver created: appId=$appId, pod=$podName, podUid=$podUid")
      }
      podUid

    } catch {
      case e: Exception =>
        log.error(s"${LogPrefix.Error} ${e.getMessage}")

        // Attempt cleanup (skip for dry-run — nothing persisted to etcd)
        if (!isDryRun) {
          driverPod.foreach(p => safeCleanup("driver pod", client.pods().inNamespace(ns).resource(p).delete()))
          if (pre.nonEmpty) safeCleanup("pre-resources", client.resourceList(pre.asJava).inNamespace(ns).delete())
        }

        // Rethrow raw exception — service layer (SparkSubmitter) translates to SparkSubmitException
        throw e
    }
  }

  /** Best-effort cleanup: log warning on failure, never throws. */
  private def safeCleanup(name: String, action: => Any): Unit =
    Try(action).failed.foreach(err => log.warn(s"Failed to cleanup $name: ${err.getMessage}"))
}
