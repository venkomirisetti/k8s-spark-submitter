package org.apache.spark.deploy.k8s.submit

import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.client.dsl._
import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientException}
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

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
    when(client.pods().inNamespace(TestNamespace).resource(pod).dryRun(false).create()).thenAnswer(_ => {
      callOrder += CreatePodCall
      createdPod
    })

    val podUid = K8sSparkClient.createResources(client, TestNamespace, pod, pre, post)
    podUid shouldBe Some("test-pod-uid-123")

    // Verify call order: pre-resources → pod → pre-patch → post-resources
    callOrder shouldBe Seq(ServerSideApplyCall, CreatePodCall, ServerSideApplyCall, ServerSideApplyCall)
    verify(resourceListOps.inNamespace(TestNamespace).forceConflicts(), times(3)).serverSideApply()
  }

  it should "cleanup pre-resources when pod creation fails" in {
    val (client, pod, post, resourceListOps, _) = setupMocks()
    val secretName = "pre-secret"
    val errorMessage = "Pod creation failed"
    val pre = List(createSecret(secretName))

    when(client.pods().inNamespace(TestNamespace).resource(pod).dryRun(false).create())
      .thenThrow(new KubernetesClientException(errorMessage))

    val exception = intercept[KubernetesClientException] {
      K8sSparkClient.createResources(client, TestNamespace, pod, pre, post)
    }

    exception.getMessage shouldBe errorMessage
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

    val exception = intercept[KubernetesClientException] {
      K8sSparkClient.createResources(client, TestNamespace, pod, pre, post)
    }

    exception.getMessage shouldBe errorMessage
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

    val exception = intercept[KubernetesClientException] {
      K8sSparkClient.createResources(client, TestNamespace, pod, List.empty, post)
    }

    exception.getMessage shouldBe originalError
  }

  it should "create pod with dryRun when dryRun=true and skip owner-ref patches" in {
    val (client, pod, post, resourceListOps, podResource) = setupMocks()
    val pre: List[HasMetadata] = List.empty

    val createdPod = createPodWithUid(pod.getMetadata.getName, "dryrun-uid")
    when(podResource.create()).thenReturn(createdPod)

    val podUid = K8sSparkClient.createResources(client, TestNamespace, pod, pre, post, isDryRun = true)
    podUid shouldBe Some("dryrun-uid")

    verify(podResource, atLeastOnce()).dryRun(true)
  }

  it should "apply pre-resources with dryRun and skip owner-ref patch when dryRun=true" in {
    val (client, pod, post, resourceListOps, podResource) = setupMocks()
    val pre = List(createSecret("pre-secret"))

    val createdPod = createPodWithUid(pod.getMetadata.getName, "dryrun-uid")
    when(podResource.create()).thenReturn(createdPod)

    K8sSparkClient.createResources(client, TestNamespace, pod, pre, post, isDryRun = true)

    // Pre-resources applied with dryRun, but no owner-ref patch (skipped in dryRun mode)
    verify(resourceListOps, atLeastOnce()).dryRun(true)
    verify(podResource, atLeastOnce()).dryRun(true)
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
    when(podResource.dryRun(any[Boolean])).thenReturn(podResource)
    when(podResource.create()).thenReturn(pod)
    when(podResource.delete()).thenReturn(java.util.Collections.emptyList[StatusDetails]())

    // Mock resource list operations
    val resourceListOps = Mockito.mock(classOf[NamespaceListVisitFromServerGetDeleteRecreateWaitApplicable[HasMetadata]])

    when(client.resourceList(any[java.util.List[HasMetadata]])).thenReturn(resourceListOps)
    when(resourceListOps.inNamespace(any())).thenReturn(resourceListOps)
    when(resourceListOps.dryRun(any[Boolean])).thenReturn(resourceListOps)
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
