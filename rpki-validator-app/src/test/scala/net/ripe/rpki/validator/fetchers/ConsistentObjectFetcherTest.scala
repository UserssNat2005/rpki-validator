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
package net.ripe.rpki.validator.fetchers

import net.ripe.rpki.validator.store.DataSources
import net.ripe.rpki.validator.store.RepositoryObjectStore
import net.ripe.rpki.commons.rsync.Rsync
import net.ripe.rpki.validator.util.UriToFileMapper
import java.io.File
import java.net.URI
import net.ripe.rpki.commons.crypto.CertificateRepositoryObject
import net.ripe.rpki.commons.validation._
import net.ripe.rpki.commons.validation.objectvalidators.CertificateRepositoryObjectValidationContext
import net.ripe.rpki.commons.validation.ValidationString._
import net.ripe.rpki.commons.util.Specification
import net.ripe.rpki.commons.util.Specifications
import net.ripe.rpki.commons.crypto.cms.manifest.ManifestCmsTest
import net.ripe.rpki.commons.crypto.crl.X509CrlTest
import net.ripe.rpki.commons.crypto.cms.roa.RoaCmsTest
import net.ripe.rpki.commons.crypto.x509cert.X509ResourceCertificateTest
import net.ripe.rpki.validator.models.StoredRepositoryObject
import org.scalatest.BeforeAndAfter
import org.scalatest.mock.MockitoSugar
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.mockito.stubbing.Answer
import org.mockito.invocation.InvocationOnMock
import java.util
import scala.Some
import scala.collection.JavaConverters._
import net.ripe.rpki.validator.support.ValidatorTestCase

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ConsistentObjectFetcherTest extends ValidatorTestCase with BeforeAndAfter with MockitoSugar {

  val store = new RepositoryObjectStore(DataSources.InMemoryDataSource)
  before {
    store.clear
  }

  val issuingCertificate = X509ResourceCertificateTest.createSelfSignedCaResourceCertificate
  val baseUri = issuingCertificate.getRepositoryUri
  val baseValidationContext = new CertificateRepositoryObjectValidationContext(mftUri, issuingCertificate)

  val crl = X509CrlTest.createCrl
  val crlFileName = "crl.crl"
  val crlUri = baseUri.resolve(crlFileName)

  val roa = RoaCmsTest.getRoaCms
  val roaFileName = "roa.roa"
  val roaUri = baseUri.resolve(roaFileName)

  val mftBuilder = ManifestCmsTest.getRootManifestBuilder
  mftBuilder.addFile(crlFileName, crl.getEncoded)
  mftBuilder.addFile(roaFileName, roa.getEncoded)

  val mft = mftBuilder.build(ManifestCmsTest.MANIFEST_KEY_PAIR.getPrivate)
  val mftFileName = "mft.mft"
  val mftUri = baseUri.resolve(mftFileName)

  test("Should store consistent set") {
    val entries = Map(
      mftUri -> mft,
      crlUri -> crl,
      roaUri -> roa)

    val rsyncFetcher = new TestRemoteObjectFetcher(entries)

    val subject = new ConsistentObjectFetcher(remoteObjectFetcher = rsyncFetcher, store = store)

    val validationResult = ValidationResult.withLocation(mftUri)

    subject.fetch(mftUri, Specifications.alwaysTrue(), validationResult)

    validationResult.hasFailures should be(false)
    store.getLatestByUrl(mftUri) should equal(Some(StoredRepositoryObject(uri = mftUri, binary = mft.getEncoded)))
    store.getLatestByUrl(crlUri) should equal(Some(StoredRepositoryObject(uri = crlUri, binary = crl.getEncoded)))
    store.getLatestByUrl(roaUri) should equal(Some(StoredRepositoryObject(uri = roaUri, binary = roa.getEncoded)))
  }

  test("Should not store mft when entry is missing") {
    val entries = Map(
      mftUri -> mft,
      roaUri -> roa)

    val rsyncFetcher = new TestRemoteObjectFetcher(entries)

    val subject = new ConsistentObjectFetcher(remoteObjectFetcher = rsyncFetcher, store = store)
    val validationResult = ValidationResult.withLocation(mftUri)

    subject.fetch(mftUri, Specifications.alwaysTrue(), validationResult)

    validationResult.getWarnings should have size 2
    validationResult.getWarnings.asScala.map(_.getKey) contains
      (List(ValidationString.VALIDATOR_REPOSITORY_INCOMPLETE, ValidationString.VALIDATOR_REPOSITORY_OBJECT_NOT_IN_CACHE))

    store.getLatestByUrl(mftUri) should equal(None)

  }

  test("Should not store when wrong hash is found") {

    val mftWrongHashBuilder = ManifestCmsTest.getRootManifestBuilder
    mftWrongHashBuilder.addFile(crlFileName, crl.getEncoded)
    mftWrongHashBuilder.addFile(roaFileName, Array[Byte](0, 2, 3)) // <-- wrong content

    val mftWrongHash = mftWrongHashBuilder.build(ManifestCmsTest.MANIFEST_KEY_PAIR.getPrivate)
    val mftWrongHashFileName = "inconsistenMft.mft"
    val mftWrongHashUri = baseUri.resolve(mftWrongHashFileName)

    val entries = Map(
      mftWrongHashUri -> mftWrongHash,
      crlUri -> crl,
      roaUri -> roa)

    val rsyncFetcher = new TestRemoteObjectFetcher(entries)

    val subject = new ConsistentObjectFetcher(remoteObjectFetcher = rsyncFetcher, store = store)
    val validationResult = ValidationResult.withLocation(mftUri)

    subject.fetch(mftWrongHashUri, Specifications.alwaysTrue(), validationResult)

    // Should see warnings
    validationResult.getWarnings should have size 2
    validationResult.getWarnings.asScala.map(_.getKey) contains
      (List(ValidationString.VALIDATOR_REPOSITORY_INCOMPLETE, ValidationString.VALIDATOR_REPOSITORY_OBJECT_NOT_IN_CACHE))

    // And since it's not in the cache, also errors
    val failures = validationResult.getFailures(new ValidationLocation(mftWrongHashUri))
    failures should have size 0
    store.getLatestByUrl(mftWrongHashUri) should equal(None)
  }

  test("Should get certificate repository objects from the store") {

    val rsyncFetcher = new TestRemoteObjectFetcher(Map.empty)
    val subject = new ConsistentObjectFetcher(remoteObjectFetcher = rsyncFetcher, store = store)
    val validationResult = ValidationResult.withLocation(mftUri)

    store.put(StoredRepositoryObject(uri = mftUri, binary = mft.getEncoded))
    store.put(StoredRepositoryObject(uri = roaUri, binary = roa.getEncoded))
    store.put(StoredRepositoryObject(uri = crlUri, binary = crl.getEncoded))

    // Should get it from store
    subject.fetch(mftUri, Specifications.alwaysTrue(), validationResult) should equal(mft)

    validationResult.setLocation(new ValidationLocation(roaUri))
    subject.fetch(roaUri, Specifications.alwaysTrue(), validationResult) should equal(roa)

    validationResult.setLocation(new ValidationLocation(crlUri))
    subject.fetch(crlUri, Specifications.alwaysTrue(), validationResult) should equal(crl)

    // But should see warnings about fetching
    validationResult.getResult(new ValidationLocation(mftUri), ValidationString.VALIDATOR_REPOSITORY_INCOMPLETE).getStatus() should be(ValidationStatus.WARNING)
    validationResult.getFailuresForCurrentLocation should have size 0
  }

  test("Should get object by hash if we can") {
    val rsyncFetcher = new TestRemoteObjectFetcher(Map.empty)
    val subject = new ConsistentObjectFetcher(remoteObjectFetcher = rsyncFetcher, store = store)


    store.put(StoredRepositoryObject(uri = mftUri, binary = mft.getEncoded))
    val nonExistentUri = URI.create("rsync://some.host/doesnotexist.roa")
    val validationResult = ValidationResult.withLocation(nonExistentUri)

    store.put(StoredRepositoryObject(uri = nonExistentUri, binary = roa.getEncoded))
    store.put(StoredRepositoryObject(uri = crlUri, binary = crl.getEncoded))

    subject.fetch(nonExistentUri, mft.getFileContentSpecification(roaFileName), validationResult) should equal(roa)
  }

  test("Should fall back to remote object from inconsistent repository if no consistent repository object is available") {
    val entries = Map(
      mftUri -> mft,
      roaUri -> roa)

    val rsyncFetcher = new TestRemoteObjectFetcher(entries)
    val subject = new ConsistentObjectFetcher(remoteObjectFetcher = rsyncFetcher, store = store)
    val validationResult = ValidationResult.withLocation(mftUri)

    subject.fetch(mftUri, Specifications.alwaysTrue(), validationResult) should equal(mft)
    validationResult.getWarnings should have size 2
    validationResult.getWarnings.asScala.map(_.getKey) contains
      (List(ValidationString.VALIDATOR_REPOSITORY_INCOMPLETE, ValidationString.VALIDATOR_REPOSITORY_OBJECT_NOT_IN_CACHE))
  }

  test("Should add a failure when object cannot be retrieved from remote repository due to an rsync failure") {
    val rsyncFetcher = mock[RsyncRpkiRepositoryObjectFetcher]
    val subject = new ConsistentObjectFetcher(remoteObjectFetcher = rsyncFetcher, store = store)
    val validationResult = ValidationResult.withLocation(mftUri)

    when(rsyncFetcher.fetch(isA(classOf[URI]), isA(classOf[Specification[Array[Byte]]]), isA(classOf[ValidationResult]))).thenAnswer(new Answer[CertificateRepositoryObject] {
      def answer(invocation: InvocationOnMock) = {
        val result = invocation.getArguments()(2).asInstanceOf[ValidationResult]
        result.error(ValidationString.VALIDATOR_RSYNC_COMMAND)
        null
      }
    })

    subject.fetch(mftUri, Specifications.alwaysTrue(), validationResult) should equal(null)

    val warnings = validationResult.getWarnings
    warnings should have size 2
    warnings.get(0).getKey should equal(ValidationString.VALIDATOR_RSYNC_COMMAND)
    warnings.get(1).getKey should equal(ValidationString.VALIDATOR_REPOSITORY_OBJECT_NOT_IN_CACHE)

    val failures: util.List[ValidationCheck] = validationResult.getFailures(new ValidationLocation(mftUri))
    failures should have size 1
    failures.get(0).getKey should equal(ValidationString.VALIDATOR_REPOSITORY_OBJECT_NOT_FOUND)
  }

}

class TestRemoteObjectFetcher(entries: Map[URI, CertificateRepositoryObject]) extends RsyncRpkiRepositoryObjectFetcher(new Rsync, new UriToFileMapper(new File(System.getProperty("java.io.tmpdir")))) {

  override def prefetch(uri: URI, result: ValidationResult) = {}

  override def fetchContent(uri: URI, specification: Specification[Array[Byte]], result: ValidationResult) = {
    result.setLocation(new ValidationLocation(uri))
    entries.get(uri) match {
      case Some(repositoryObject) =>
        if (result.rejectIfFalse(specification.isSatisfiedBy(repositoryObject.getEncoded), VALIDATOR_FILE_CONTENT, uri.toString())) {
          repositoryObject.getEncoded
        } else {
          null
        }
      case _ =>
        result.rejectIfNull(null, VALIDATOR_READ_FILE, uri.toString);
        null
    }
  }
}
