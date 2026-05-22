package io.spark.k8s.submit.api.servlet

import io.spark.k8s.submit.api.MediaType
import org.mockito.Mockito._

import jakarta.servlet.http.{HttpServletRequest, HttpServletResponse}
import jakarta.servlet.{ReadListener, ServletInputStream, ServletOutputStream}
import java.io.{ByteArrayInputStream, PrintWriter}

trait ServletTestSupport {

  def mockGetRequest(): (HttpServletRequest, HttpServletResponse, PrintWriter) = {
    val req = mock(classOf[HttpServletRequest])
    val resp = mock(classOf[HttpServletResponse])
    val writer = mock(classOf[PrintWriter])
    when(resp.getWriter).thenReturn(writer)
    (req, resp, writer)
  }

  def mockPostRequest(body: String, contentType: String = MediaType.ApplicationJson): (HttpServletRequest, HttpServletResponse, ServletOutputStream) = {
    val req = mock(classOf[HttpServletRequest])
    val resp = mock(classOf[HttpServletResponse])
    val outputStream = mock(classOf[ServletOutputStream])

    when(req.getContentType).thenReturn(contentType)
    when(req.getInputStream).thenReturn(new MockServletInputStream(body.getBytes("UTF-8")))
    when(resp.getOutputStream).thenReturn(outputStream)

    (req, resp, outputStream)
  }
}

class MockServletInputStream(data: Array[Byte]) extends ServletInputStream {
  private val stream = new ByteArrayInputStream(data)

  override def read(): Int = stream.read()
  override def isFinished: Boolean = stream.available() == 0
  override def isReady: Boolean = true
  override def setReadListener(listener: ReadListener): Unit = ()
}
