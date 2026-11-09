package io.spark.k8s.submit.service

import io.fabric8.kubernetes.client.KubernetesClient
import org.mockito.Mockito
import org.mockito.Mockito.{times, verify}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class KubernetesClientProviderTest extends AnyFlatSpec with Matchers {

  "KubernetesClientProvider" should "return the client from the factory" in {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val provider = new KubernetesClientProvider(() => mockClient)

    provider.client shouldBe mockClient
  }

  it should "close the underlying client" in {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val provider = new KubernetesClientProvider(() => mockClient)

    provider.close()

    verify(mockClient, times(1)).close()
  }

  it should "throw IllegalStateException after close" in {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val provider = new KubernetesClientProvider(() => mockClient)

    provider.close()

    intercept[IllegalStateException] {
      provider.client
    }
  }

  it should "handle double close gracefully" in {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    val provider = new KubernetesClientProvider(() => mockClient)

    provider.close()
    noException should be thrownBy provider.close()
    verify(mockClient, times(1)).close()
  }

  it should "handle close failure gracefully" in {
    val mockClient = Mockito.mock(classOf[KubernetesClient])
    Mockito.doThrow(new RuntimeException("Close failed")).when(mockClient).close()
    val provider = new KubernetesClientProvider(() => mockClient)

    noException should be thrownBy provider.close()
  }
}
