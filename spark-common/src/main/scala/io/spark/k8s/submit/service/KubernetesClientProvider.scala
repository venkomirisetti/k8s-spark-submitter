package io.spark.k8s.submit.service

import io.fabric8.kubernetes.client.{ConfigBuilder, KubernetesClient, KubernetesClientBuilder}
import io.spark.k8s.submit.ServerConfig
import org.slf4j.{Logger, LoggerFactory}

import scala.util.Try

/** Provides singleton Kubernetes client with connection pooling. */
class KubernetesClientProvider(clientFactory: () => KubernetesClient = KubernetesClientProvider.defaultFactory) {

  private val log: Logger = LoggerFactory.getLogger(getClass)

  @volatile private var _client: KubernetesClient = clientFactory()

  log.info(
    "K8s client initialized: connectionTimeout={}ms requestTimeout={}ms maxConcurrentRequests={} maxPerHost={}",
    ServerConfig.K8sClient.connectionTimeoutMs: Integer,
    ServerConfig.K8sClient.requestTimeoutMs: Integer,
    ServerConfig.K8sClient.maxConcurrentRequests: Integer,
    ServerConfig.K8sClient.maxConcurrentRequestsPerHost: Integer
  )

  def client: KubernetesClient = {
    if (_client == null) throw new IllegalStateException("Kubernetes client not initialized")
    _client
  }

  def close(): Unit = {
    Try {
      Option(_client).foreach { c =>
        log.info("Closing Kubernetes client")
        c.close()
        _client = null
      }
    }.recover {
      case e: Exception =>
        log.error("Error closing Kubernetes client", e)
    }
  }
}

object KubernetesClientProvider {
  private val defaultFactory: () => KubernetesClient = () =>
    new KubernetesClientBuilder()
      .withConfig(new ConfigBuilder()
        .withConnectionTimeout(ServerConfig.K8sClient.connectionTimeoutMs)
        .withRequestTimeout(ServerConfig.K8sClient.requestTimeoutMs)
        .withMaxConcurrentRequests(ServerConfig.K8sClient.maxConcurrentRequests)
        .withMaxConcurrentRequestsPerHost(ServerConfig.K8sClient.maxConcurrentRequestsPerHost)
        .withRequestRetryBackoffLimit(ServerConfig.K8sClient.retryBackoffLimit)
        .withRequestRetryBackoffInterval(ServerConfig.K8sClient.retryBackoffIntervalMs)
        .build())
      .build()
}
