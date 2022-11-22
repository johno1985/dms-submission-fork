/*
 * Copyright 2022 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package controllers

import models.SubmissionSummary
import models.submission.{ObjectSummary, SubmissionItem, SubmissionItemStatus}
import org.mockito.ArgumentMatchers.{any, eq => eqTo}
import org.mockito.Mockito
import org.mockito.Mockito.{never, times, verify, when}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterEach, OptionValues}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.test.{FakeRequest, Helpers}
import play.api.test.Helpers._
import repositories.SubmissionItemRepository
import uk.gov.hmrc.internalauth.client.{BackendAuthComponents, IAAction, Resource, ResourceLocation, ResourceType, Retrieval}
import uk.gov.hmrc.internalauth.client.Predicate.Permission
import uk.gov.hmrc.internalauth.client.test.{BackendAuthComponentsStub, StubBehaviour}

import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant, ZoneOffset}
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class SubmissionAdminControllerSpec
  extends AnyFreeSpec
    with Matchers
    with OptionValues
    with MockitoSugar
    with BeforeAndAfterEach
    with ScalaFutures {

  private val mockSubmissionItemRepository = mock[SubmissionItemRepository]

  override def beforeEach(): Unit = {
    Mockito.reset(
      mockSubmissionItemRepository,
      mockStubBehaviour
    )
    super.beforeEach()
  }

  private val clock = Clock.fixed(Instant.now, ZoneOffset.UTC)

  private val mockStubBehaviour = mock[StubBehaviour]
  private val stubBackendAuthComponents =
    BackendAuthComponentsStub(mockStubBehaviour)(Helpers.stubControllerComponents(), implicitly)

  private val app = GuiceApplicationBuilder()
    .overrides(
      bind[Clock].toInstance(clock),
      bind[SubmissionItemRepository].toInstance(mockSubmissionItemRepository),
      bind[BackendAuthComponents].toInstance(stubBackendAuthComponents)
    )
    .build()

  private val item = SubmissionItem(
    id = "id",
    owner = "owner",
    callbackUrl = "callbackUrl",
    status = SubmissionItemStatus.Submitted,
    objectSummary = ObjectSummary(
      location = "file",
      contentLength = 1337L,
      contentMd5 = "hash",
      lastModified = clock.instant().minus(2, ChronoUnit.DAYS)
    ),
    failureReason = None,
    created = clock.instant(),
    lastUpdated = clock.instant(),
    sdesCorrelationId = "sdesCorrelationId"
  )

  "list" - {

    "must return a list of submissions for an authorised user" in {

      val predicate = Permission(Resource(ResourceType("dms-submission"), ResourceLocation("owner")), IAAction("READ"))
      when(mockSubmissionItemRepository.list(any())).thenReturn(Future.successful(Seq(item)))
      when(mockStubBehaviour.stubAuth(eqTo(Some(predicate)), eqTo(Retrieval.EmptyRetrieval))).thenReturn(Future.successful(Retrieval.EmptyRetrieval))

      val request =
        FakeRequest(routes.SubmissionAdminController.list("owner"))
          .withHeaders("Authorization" -> "Token foo")

      val result = route(app, request).value

      status(result) mustEqual OK

      val expectedResult = List(SubmissionSummary("id", SubmissionItemStatus.Submitted, None, clock.instant()))
      contentAsJson(result) mustEqual Json.toJson(expectedResult)
    }

    "must return unauthorised for an unauthenticated user" in {

      when(mockSubmissionItemRepository.list(any())).thenReturn(Future.successful(Seq(item)))
      when(mockStubBehaviour.stubAuth(any(), eqTo(Retrieval.EmptyRetrieval))).thenReturn(Future.successful(Retrieval.EmptyRetrieval))

      val request = FakeRequest(routes.SubmissionAdminController.list("owner")) // No Authorization header

      route(app, request).value.failed.futureValue
      verify(mockSubmissionItemRepository, never()).list(any())
    }

    "must return unauthorised for an unauthorised user" in {

      when(mockSubmissionItemRepository.list(any())).thenReturn(Future.successful(Seq(item)))
      when(mockStubBehaviour.stubAuth(any(), eqTo(Retrieval.EmptyRetrieval))).thenReturn(Future.failed(new Exception("foo")))

      val request =
        FakeRequest(routes.SubmissionAdminController.list("owner"))
          .withHeaders("Authorization" -> "Token foo")

      route(app, request).value.failed.futureValue
      verify(mockSubmissionItemRepository, never()).list(any())
    }
  }

  "retry" - {

    "must update a submission item to Submitted and return Accepted when the user is authorised" in {

      val predicate = Permission(Resource(ResourceType("dms-submission"), ResourceLocation("owner")), IAAction("WRITE"))
      when(mockSubmissionItemRepository.update(eqTo("owner"), eqTo("id"), any(), any())).thenReturn(Future.successful(item))
      when(mockStubBehaviour.stubAuth(eqTo(Some(predicate)), eqTo(Retrieval.EmptyRetrieval))).thenReturn(Future.successful(Retrieval.EmptyRetrieval))

      val request =
        FakeRequest(routes.SubmissionAdminController.retry("owner", "id"))
          .withHeaders("Authorization" -> "Token foo")

      val result = route(app, request).value

      status(result) mustEqual ACCEPTED
      verify(mockSubmissionItemRepository, times(1)).update(eqTo("owner"), eqTo("id"), eqTo(SubmissionItemStatus.Submitted), eqTo(None))
    }

    "must return Not Found when an authorised user attempts to retry a submission item that cannot be found" in {

      val predicate = Permission(Resource(ResourceType("dms-submission"), ResourceLocation("owner")), IAAction("WRITE"))
      when(mockSubmissionItemRepository.update(eqTo("owner"), eqTo("id"), any(), any())).thenReturn(Future.failed(SubmissionItemRepository.NothingToUpdateException))
      when(mockStubBehaviour.stubAuth(eqTo(Some(predicate)), eqTo(Retrieval.EmptyRetrieval))).thenReturn(Future.successful(Retrieval.EmptyRetrieval))

      val request =
        FakeRequest(routes.SubmissionAdminController.retry("owner", "id"))
          .withHeaders("Authorization" -> "Token foo")

      val result = route(app, request).value

      status(result) mustEqual NOT_FOUND
    }

    "must fail for an unauthenticated user" in {

      val predicate = Permission(Resource(ResourceType("dms-submission"), ResourceLocation("owner")), IAAction("WRITE"))
      when(mockStubBehaviour.stubAuth(eqTo(Some(predicate)), eqTo(Retrieval.EmptyRetrieval))).thenReturn(Future.successful(Retrieval.EmptyRetrieval))

      val request = FakeRequest(routes.SubmissionAdminController.retry("owner", "id")) // No Authorization header

      route(app, request).value.failed.futureValue
      verify(mockSubmissionItemRepository, never()).update(any(), any(), any(), any())
    }

    "must fail when the user is not authorised" in {

      when(mockStubBehaviour.stubAuth(any(), eqTo(Retrieval.EmptyRetrieval))).thenReturn(Future.failed(new Exception("foo")))

      val request =
        FakeRequest(routes.SubmissionAdminController.retry("owner", "id"))
          .withHeaders("Authorization" -> "Token foo")

      route(app, request).value.failed.futureValue
      verify(mockSubmissionItemRepository, never()).update(any(), any(), any(), any())
    }
  }
}
