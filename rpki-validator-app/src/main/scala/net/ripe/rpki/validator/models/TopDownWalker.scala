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
package net.ripe.rpki.validator.models

import java.net.URI

import net.ripe.rpki.commons.crypto.crl.{CrlLocator, X509Crl, X509CrlValidator}
import net.ripe.rpki.commons.crypto.util.CertificateRepositoryObjectFactory
import net.ripe.rpki.commons.validation.ValidationString.CRL_REQUIRED
import net.ripe.rpki.commons.validation.objectvalidators.CertificateRepositoryObjectValidationContext
import net.ripe.rpki.commons.validation.{ValidationLocation, ValidationOptions, ValidationResult, ValidationString}
import net.ripe.rpki.validator.fetchers.Fetcher
import net.ripe.rpki.validator.models.validation.{CertificateObject, CrlObject, ManifestObject, RoaObject}
import net.ripe.rpki.validator.store.Storage
import org.apache.commons.lang.Validate

import scala.collection.JavaConverters._


class TopDownWalker(certificateContext: CertificateRepositoryObjectValidationContext, store: Storage, fetcher: Fetcher, validationOptions: ValidationOptions) {
  
  Validate.isTrue(certificateContext.getCertificate.isObjectIssuer, "certificate must be an object issuer")

  val validationResult: ValidationResult = ValidationResult.withLocation("")

  lazy val crl: Option[CrlObject] = findCrl

  def crlLocator = new CrlLocator {
    override def getCrl(uri: URI, context: CertificateRepositoryObjectValidationContext, result: ValidationResult): X509Crl = crl.get.decoded
  }

  def execute: ValidationResult = {
    Option(certificateContext.getRepositoryURI) match {
      case Some(repositoryUri) =>
        prefetch(repositoryUri)
        validationResult.setLocation(new ValidationLocation(repositoryUri))

      case None =>  //TODO do nothing, suppose this could happen if CA has no children?
    }

    if (crl.isDefined) {
      val manifest = findManifest
      val roas = findRoas
      val childrenCertificates = findChildrenCertificates

      crossCheckWithManifest(manifest, crl.get, roas, childrenCertificates)

      childrenCertificates.foreach( cert => {
        val newValidationContext = new CertificateRepositoryObjectValidationContext(new URI(""), cert.decoded) //TODO get proper URI
        new TopDownWalker(newValidationContext, store, fetcher, validationOptions).execute
      })

    } else {
      validationError(CRL_REQUIRED, "No valid CRL found with SKI=" + certificateContext.getSubjectKeyIdentifier)
    }

    validationResult
  }

  def prefetch(uri: URI) = ???

  def findCrl: Option[CrlObject] = {
    val keyIdentifier = certificateContext.getSubjectKeyIdentifier
    findMostRecentValidCrl(store.getCrls(keyIdentifier))
  }

  def findMostRecentValidCrl(crlList: Seq[CrlObject]): Option[CrlObject] = {
    crlList.sortBy(_.decoded.getNumber).reverse.find(crl => {
      val crlLocation = crl.decoded.getCrlUri.toString
      val crlValidationResult = ValidationResult.withLocation(crlLocation)
      val validator: X509CrlValidator = new X509CrlValidator(validationOptions, crlValidationResult, certificateContext.getCertificate)
      validator.validate(crlLocation, crl.decoded)
      validationResult.addAll(crlValidationResult)
      ! crlValidationResult.hasFailureForCurrentLocation
    })
  }

  def findManifest: Option[ManifestObject] = {
    val manifests = store.getManifests(certificateContext.getSubjectKeyIdentifier)
    findMostRecentValidManifest(manifests)
  }

  def findMostRecentValidManifest(manifests: Seq[ManifestObject]): Option[ManifestObject] = {
    manifests.sortBy(_.decoded.getNumber).reverse.find( manifest => {
      val manifestValidationResult: ValidationResult = ValidationResult.withLocation("")
      manifest.decoded.validate(certificateContext.getLocation.toString, certificateContext, crlLocator, validationOptions, manifestValidationResult)
      validationResult.addAll(manifestValidationResult)
      ! manifestValidationResult.hasFailures
    })
  }

  def processManifestEntries(manifest: ManifestObject, crl: CrlObject, roas: Seq[RoaObject], childrenCertificates: Seq[CertificateObject]) {

    manifest.decoded.getFiles.entrySet().asScala.map(entry => {

    })
  }

  def findRoas = {
    val location: String = certificateContext.getLocation.toString
    val roas = store.getRoas(certificateContext.getSubjectKeyIdentifier)
    roas.foreach(_.decoded.validate(location, certificateContext, crlLocator, validationOptions, validationResult))
    roas
  }

  def findChildrenCertificates = {
    val location: String = certificateContext.getLocation.toString
    val certs = store.getCertificates(certificateContext.getSubjectKeyIdentifier)
    certs.foreach(_.decoded.validate(location, certificateContext, crlLocator, validationOptions, validationResult))
    certs
  }

  def crossCheckWithManifest(manifest: Option[ManifestObject], crl: CrlObject, roas: Seq[RoaObject], childrenCertificates: Seq[CertificateObject]) {
    if (manifest.isEmpty) {
      //TODO better error code
      validationError(ValidationString.VALIDATOR_OBJECT_PROCESSING_EXCEPTION, "No manifests with SKI=" + certificateContext.getSubjectKeyIdentifier)
    } else {
      processManifestEntries(manifest.get, crl, roas, childrenCertificates)
    }
  }



  def validate(repositoryObject: StoredRepositoryObject, uri: URI): ValidationResult = {
    val result = ValidationResult.withLocation(uri)
    val certificateRepositoryObject = CertificateRepositoryObjectFactory.createCertificateRepositoryObject(repositoryObject.binaryObject.toArray[Byte], result)
    certificateRepositoryObject.validate(uri.toString, certificateContext, crlLocator, validationOptions, result)
    result
  }

  def validationError(uri: URI, key: String, param: String) = {
    validationResult.rejectForLocation(new ValidationLocation(uri), key, param)
  }

  def validationError(key: String, param: String) = {
    validationResult.error(key, param)
  }

}