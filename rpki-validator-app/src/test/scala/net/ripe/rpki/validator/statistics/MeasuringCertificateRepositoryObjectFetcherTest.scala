/**
 * The BSD License
 *
 * Copyright (c) 2010-2012 RIPE NCC
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *   - Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *   - Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *   - Neither the name of the RIPE NCC nor the names of its contributors may be
 *     used to endorse or promote products derived from this software without
 *     specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package net.ripe.rpki.validator.statistics

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.mock.MockitoSugar

import net.ripe.certification.validator.fetchers.CertificateRepositoryObjectFetcher
import java.net.URI
import net.ripe.commons.certification.validation.ValidationResult
import net.ripe.commons.certification.validation.objectvalidators.CertificateRepositoryObjectValidationContext
import net.ripe.commons.certification.util.Specification
import scala.collection.JavaConverters._

@RunWith(classOf[JUnitRunner])
class MeasuringCertificateRepositoryObjectFetcherTest extends FunSuite with ShouldMatchers with BeforeAndAfter with MockitoSugar {

  val mockFetcher = mock[CertificateRepositoryObjectFetcher]
  val mockResult = mock[ValidationResult]
  val mockContext = mock[CertificateRepositoryObjectValidationContext]

  val subject = new MeasuringCertificateRepositoryObjectFetcher(mockFetcher)
  val uri = URI.create("rsync://rpki.ripe.net/ta/ripe-ncc-ta.cer")

  after {
    subject.reset()
  }

  test("should measure prefetch per host") {
    checkForMetrics { subject.prefetch(uri, mockResult) }
  }

  test("should measure getting manifest per host") {
    checkForMetrics { subject.getManifest(uri, mockContext, mockResult) }
  }

  test("should measure getting object per host") {
    checkForMetrics { subject.getObject(uri, mockContext, mock[Specification[Array[Byte]]], mockResult) }
  }

  test("should measure getting crl per host") {
    checkForMetrics { subject.getCrl(uri, mockContext, mockResult) }
  }

  test("should have separate metrics for different hosts") {
    val apnicUri = URI.create("rsync://rpki.apnic.net/repository/APNIC.cer")

    subject.prefetch(uri, mockResult)
    subject.prefetch(apnicUri, mockResult)

    subject.metrics().values should have size(2)
    metricNames should contain(apnicUri.getHost)
    metricNames should contain(uri.getHost)
  }

  test("should reuse metric for the same host") {
    subject.getCrl(uri, mockContext, mockResult)
    subject.getCrl(uri, mockContext, mockResult)

    subject.metrics().values should have size(1)
  }

  private def checkForMetrics[B](block: => B) = {
    val metricCount = subject.metrics().size()

    block

    subject.metrics().values should have size(metricCount + 1)
    metricNames should contain(uri.getHost)
  }

  def metricNames  = {
    for (metric <- subject.metrics().keySet().asScala) yield metric.getName()
  }
}