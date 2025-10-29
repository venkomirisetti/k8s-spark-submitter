package org.apache.spark.deploy.k8s.submit

import io.spark.k8s.submit.SparkSubmitException
import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.client.dsl._
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException}
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{ConnectException, SocketTimeoutException}
import javax.net.ssl.{SSLHandshakeException, SSLPeerUnverifiedException}

/**
 * Unit tests for K8sSparkClient business logic.
 * Tests formatResources() and createResources() methods.
 * Full end-to-end submission is tested in SparkSubmitterTest.
 */
class K8sSparkClientTest extends AnyFlatSpec with Matchers {

  private val EmptyListFormat = "[]"
  private val TestNamespace = "test-ns"
  private val ServerSideApplyCall = "serverSideApply"
  private val CreatePodCall = "createPod"

  "K8sSparkClient.formatResources" should "format empty list as []" in {
    K8sSparkClient.formatResources(List.empty) shouldBe EmptyListFormat
  }

  it should "format resources as [Kind/name, ...] and preserve order" in {
    val secretKind = "Secret"
    val secretName = "secret-1"
    val configMapKind = "ConfigMap"
    val configMapName = "configmap-1"
    val serviceKind = "Service"
    val serviceName = "service-1"

    val resources = List(
      createResource(secretKind, secretName),
      createResource(configMapKind, configMapName),
      createResource(serviceKind, serviceName)
    )

    val result = K8sSparkClient.formatResources(resources)

    result shouldBe s"[$secretKind/$secretName, $configMapKind/$configMapName, $serviceKind/$serviceName]"
  }

  "K8sSparkClient.createResources" should "create resources in correct order: pre → pod → pre-patch → post" in {
    val (client, pod, post, resourceListOps, _) = setupMocks()
    val secretName = "pre-secret"
    val pre = List(createSecret(secretName))
    val callOrder = scala.collection.mutable.ArrayBuffer[String]()

    // Track call order
    when(resourceListOps.serverSideApply()).thenAnswer(_ => {
      callOrder += ServerSideApplyCall
      java.util.Collections.emptyList[HasMetadata]()
    })
    val createdPod = createPodWithUid(pod.getMetadata.getName, "test-pod-uid-123")
    when(client.pods().inNamespace(TestNamespace).resource(pod).create()).thenAnswer(_ => {
      callOrder += CreatePodCall
      createdPod
    })

    val podUid = K8sSparkClient.createResources(client, TestNamespace, pod, pre, post)
    podUid shouldBe "test-pod-uid-123"

    // Verify call order: pre-resources → pod → pre-patch → post-resources
    callOrder shouldBe Seq(ServerSideApplyCall, CreatePodCall, ServerSideApplyCall, ServerSideApplyCall)
    verify(resourceListOps.inNamespace(TestNamespace).forceConflicts(), times(3)).serverSideApply()
  }

  it should "cleanup pre-resources when pod creation fails" in {
    val (client, pod, post, resourceListOps, _) = setupMocks()
    val secretName = "pre-secret"
    val errorMessage = "Pod creation failed"
    val pre = List(createSecret(secretName))

    when(client.pods().inNamespace(TestNamespace).resource(pod).create())
      .thenThrow(new KubernetesClientException(errorMessage))

    val exception = intercept[RuntimeException] {
      K8sSparkClient.createResources(client, TestNamespace, pod, pre, post)
    }

    exception.getCause shouldBe a[KubernetesClientException]
    verify(resourceListOps.inNamespace(TestNamespace), times(1)).delete()
  }

  it should "cleanup both pod and pre-resources when post-resource creation fails" in {
    val (client, pod, post, resourceListOps, podResource) = setupMocks()
    val secretName = "pre-secret"
    val errorMessage = "Post-resource failed"
    val pre = List(createSecret(secretName))

    when(resourceListOps.serverSideApply())
      .thenReturn(java.util.Collections.emptyList[HasMetadata]()) // pre-resources
      .thenReturn(java.util.Collections.emptyList[HasMetadata]()) // pre-patch
      .thenThrow(new KubernetesClientException(errorMessage)) // post-resources

    val exception = intercept[RuntimeException] {
      K8sSparkClient.createResources(client, TestNamespace, pod, pre, post)
    }

    exception.getCause shouldBe a[KubernetesClientException]
    verify(resourceListOps.inNamespace(TestNamespace), times(1)).delete()
    verify(podResource, times(1)).delete()
  }

  it should "preserve original exception when cleanup fails" in {
    val (client, pod, post, resourceListOps, _) = setupMocks()
    val originalError = "Original failure"
    val cleanupError = "Cleanup failed"

    when(resourceListOps.serverSideApply())
      .thenThrow(new KubernetesClientException(originalError))
    when(resourceListOps.delete())
      .thenThrow(new RuntimeException(cleanupError))

    val exception = intercept[RuntimeException] {
      K8sSparkClient.createResources(client, TestNamespace, pod, List.empty, post)
    }

    // Original exception preserved, not cleanup exception
    exception.getCause shouldBe a[KubernetesClientException]
  }

  it should "create pod with dryRun when dryRun=true and skip post-resources" in {
    val (client, pod, post, resourceListOps, podResource) = setupMocks()
    val pre: List[HasMetadata] = List.empty

    val callOrder = scala.collection.mutable.ArrayBuffer[String]()
    when(podResource.dryRun(true)).thenAnswer(_ => {
      callOrder += "dryRun(true)"
      podResource
    })
    val createdPod = createPodWithUid(pod.getMetadata.getName, "dryrun-uid")
    when(podResource.create()).thenAnswer(_ => {
      callOrder += CreatePodCall
      createdPod
    })

    val podUid = K8sSparkClient.createResources(client, TestNamespace, pod, pre, post, dryRun = true)
    podUid shouldBe "dryrun-uid"

    callOrder shouldBe Seq("dryRun(true)", CreatePodCall)
    verify(resourceListOps, never()).serverSideApply()
  }

  it should "apply pre-resources with dryRun and skip owner-ref patch when dryRun=true" in {
    val (client, pod, post, resourceListOps, podResource) = setupMocks()
    val pre = List(createSecret("pre-secret"))

    when(resourceListOps.dryRun(true)).thenReturn(resourceListOps)
    when(podResource.dryRun(true)).thenReturn(podResource)

    val createdPod = createPodWithUid(pod.getMetadata.getName, "dryrun-uid")
    when(podResource.create()).thenReturn(createdPod)

    K8sSparkClient.createResources(client, TestNamespace, pod, pre, post, dryRun = true)

    // Pre-resources applied exactly once (no owner-ref patch second pass).
    verify(resourceListOps.inNamespace(TestNamespace).dryRun(true).forceConflicts(), times(1)).serverSideApply()
    verify(resourceListOps, atLeastOnce()).dryRun(true)
    verify(podResource, atLeastOnce()).dryRun(true)
  }

  // -- classifyKubernetesFailure: operator circuit-breaker contract --------------
  //
  // These tests pin the HTTP status the operator will see for each failure class.
  // Changing a mapping here changes breaker behavior in production, so be deliberate.

  "K8sSparkClient.classifyKubernetesFailure" should "return None for TLS handshake failures so they become 500" in {
    val tls = new SSLHandshakeException("cert expired")
    K8sSparkClient.classifyKubernetesFailure(tls) shouldBe None
  }

  it should "return None when TLS is nested inside a KubernetesClientException cause chain" in {
    val tls = new SSLPeerUnverifiedException("hostname mismatch")
    val wrapped = new KubernetesClientException("io error", tls)
    K8sSparkClient.classifyKubernetesFailure(wrapped) shouldBe None
  }

  it should "classify apiserver 401 as transient so the operator retries token-projection races" in {
    val e = new KubernetesClientException("unauthorized", 401, null)
    val Some(result) = K8sSparkClient.classifyKubernetesFailure(e)
    result.isTransient shouldBe true
  }

  it should "classify apiserver 403 as submission so the operator surfaces RBAC denials" in {
    val e = new KubernetesClientException("forbidden", 403, null)
    val Some(result) = K8sSparkClient.classifyKubernetesFailure(e)
    result.isTransient shouldBe false
    result.isValidationError shouldBe false
  }

  it should "classify apiserver 429 and 5xx as transient" in {
    Seq(429, 500, 502, 503, 504).foreach { code =>
      val e = new KubernetesClientException(s"code $code", code, null)
      val Some(result) = K8sSparkClient.classifyKubernetesFailure(e)
      withClue(s"code=$code: ") { result.isTransient shouldBe true }
    }
  }

  it should "classify IO / connect / socket-timeout as transient" in {
    val ioTransients: Seq[Throwable] = Seq(
      new ConnectException("connection refused"),
      new SocketTimeoutException("read timeout"),
      new java.io.IOException("broken pipe")
    )
    ioTransients.foreach { e =>
      val Some(result) = K8sSparkClient.classifyKubernetesFailure(e)
      withClue(s"${e.getClass.getSimpleName}: ") { result.isTransient shouldBe true }
    }
  }

  it should "classify unknown throwables as submission (conservative default)" in {
    val e = new IllegalStateException("unexpected")
    val Some(result) = K8sSparkClient.classifyKubernetesFailure(e)
    result.isTransient shouldBe false
    result.isValidationError shouldBe false
  }

  it should "preserve the original exception as the cause for observability" in {
    val e = new KubernetesClientException("apiserver down", 503, null)
    val Some(result) = K8sSparkClient.classifyKubernetesFailure(e)
    result.getCause shouldBe e
  }

  it should "tag dry-run failures with [selftest dry-run] so operators can distinguish probe failures from user submissions" in {
    val e = new KubernetesClientException("forbidden", 403, null)
    val Some(result) = K8sSparkClient.classifyKubernetesFailure(e, dryRun = true)
    result.getMessage should startWith("[selftest dry-run] ")
    result.getMessage should include("forbidden")
  }

  it should "not tag non-dry-run failures" in {
    val e = new KubernetesClientException("forbidden", 403, null)
    val Some(result) = K8sSparkClient.classifyKubernetesFailure(e, dryRun = false)
    result.getMessage should not include "[selftest dry-run]"
  }

  it should "preserve HTTP classification regardless of dry-run tag" in {
    // dry-run and real failures use identical breaker semantics; the tag is cosmetic.
    val e = new KubernetesClientException("apiserver down", 503, null)
    val Some(tagged) = K8sSparkClient.classifyKubernetesFailure(e, dryRun = true)
    val Some(untagged) = K8sSparkClient.classifyKubernetesFailure(e, dryRun = false)
    tagged.isTransient shouldBe untagged.isTransient
  }

  private def setupMocks(): (KubernetesClient, Pod, List[HasMetadata], NamespaceListVisitFromServerGetDeleteRecreateWaitApplicable[HasMetadata], PodResource) = {
    val podName = "test-pod"
    val configMapName = "post-configmap"

    val client = Mockito.mock(classOf[KubernetesClient])
    val pod = createPod(podName)
    val post = List(createConfigMap(configMapName))

    // Mock pod operations
    val podOps = Mockito.mock(classOf[MixedOperation[Pod, PodList, PodResource]])
    val namespacedPodOps = Mockito.mock(classOf[NonNamespaceOperation[Pod, PodList, PodResource]])
    val podResource = Mockito.mock(classOf[PodResource])

    when(client.pods()).thenReturn(podOps)
    when(podOps.inNamespace(any())).thenReturn(namespacedPodOps)
    when(namespacedPodOps.resource(any[Pod])).thenReturn(podResource)
    when(podResource.create()).thenReturn(pod)
    when(podResource.delete()).thenReturn(java.util.Collections.emptyList[StatusDetails]())

    // Mock resource list operations
    val resourceListOps = Mockito.mock(classOf[NamespaceListVisitFromServerGetDeleteRecreateWaitApplicable[HasMetadata]])

    when(client.resourceList(any[java.util.List[HasMetadata]])).thenReturn(resourceListOps)
    when(resourceListOps.inNamespace(any())).thenReturn(resourceListOps)
    when(resourceListOps.forceConflicts()).thenReturn(resourceListOps)
    when(resourceListOps.serverSideApply()).thenReturn(java.util.Collections.emptyList[HasMetadata]())
    when(resourceListOps.delete()).thenReturn(java.util.Collections.emptyList[StatusDetails]())

    (client, pod, post, resourceListOps, podResource)
  }

  private def createResource(kind: String, name: String): HasMetadata = {
    new GenericKubernetesResourceBuilder()
      .withNewMetadata()
      .withName(name)
      .endMetadata()
      .withKind(kind)
      .build()
      .asInstanceOf[HasMetadata]
  }

  private def createPod(name: String): Pod = {
    createPodWithUid(name, null)
  }

  private def createPodWithUid(name: String, uid: String): Pod = {
    val labelKey = "spark-app-selector"
    val labelValue = "test-app-id"

    val builder = new PodBuilder()
      .withNewMetadata()
      .withName(name)
      .addToLabels(labelKey, labelValue)
    if (uid != null) {
      builder.withUid(uid)
    }
    builder.endMetadata()
      .withNewSpec()
      .endSpec()
      .build()
  }

  private def createSecret(name: String): HasMetadata = {
    new SecretBuilder()
      .withNewMetadata()
      .withName(name)
      .endMetadata()
      .build()
      .asInstanceOf[HasMetadata]
  }

  private def createConfigMap(name: String): HasMetadata = {
    new ConfigMapBuilder()
      .withNewMetadata()
      .withName(name)
      .endMetadata()
      .build()
      .asInstanceOf[HasMetadata]
  }
}
