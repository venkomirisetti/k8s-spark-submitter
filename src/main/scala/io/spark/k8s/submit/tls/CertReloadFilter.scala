package io.spark.k8s.submit.tls

import jakarta.servlet._

/**
 * Servlet filter that triggers certificate reload check on each request.
 * The actual filesystem check is debounced inside CertReloadWatcher —
 * this filter adds sub-nanosecond overhead (single nanoTime comparison).
 */
class CertReloadFilter(watcher: CertReloadWatcher) extends Filter {

  override def init(filterConfig: FilterConfig): Unit = {}

  override def doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain): Unit = {
    watcher.checkReloadIfNeeded()
    chain.doFilter(request, response)
  }

  override def destroy(): Unit = {}
}
