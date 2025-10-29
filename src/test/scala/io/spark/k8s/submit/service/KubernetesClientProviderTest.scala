package io.spark.k8s.submit.service

import io.fabric8.kubernetes.client.KubernetesClient
import org.mockito.Mockito
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Unit tests for KubernetesClientProvider using Mockito.
 * Integration tests are in SparkSubmitterTest.
 */
class KubernetesClientProviderTest extends AnyFlatSpec with Matchers {

  "KubernetesClientProvider" should "manage kubernetes client lifecycle" in {
    val provider = new KubernetesClientProvider()
    val mockClient = Mockito.mock(classOf[KubernetesClient])

    val clientField = classOf[KubernetesClientProvider].getDeclaredField("_client")
    clientField.setAccessible(true)
    clientField.set(provider, mockClient)

    provider.client should not be null
    provider.client shouldBe mockClient

    provider.cleanup()
    Mockito.verify(mockClient, Mockito.times(1)).close()
  }

  it should "handle cleanup when client is null" in {
    val provider = new KubernetesClientProvider()
    noException should be thrownBy provider.cleanup()
  }

  it should "throw exception when accessing client before initialization" in {
    val provider = new KubernetesClientProvider()

    intercept[IllegalStateException] {
      provider.client
    }
  }

  it should "handle cleanup failure gracefully" in {
    val provider = new KubernetesClientProvider()
    val mockClient = Mockito.mock(classOf[KubernetesClient])

    // Make close() throw exception
    Mockito.when(mockClient.close()).thenThrow(new RuntimeException("Close failed"))

    val clientField = classOf[KubernetesClientProvider].getDeclaredField("_client")
    clientField.setAccessible(true)
    clientField.set(provider, mockClient)

    // Should not throw - error is logged
    noException should be thrownBy provider.cleanup()
    Mockito.verify(mockClient, Mockito.times(1)).close()
  }
}
