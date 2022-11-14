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

import better.files.File
import cats.data.{EitherNec, EitherT, NonEmptyChain}
import cats.implicits._
import models.submission.{SubmissionRequest, SubmissionResponse}
import play.api.i18n.{I18nSupport, Messages}
import play.api.libs.Files
import play.api.libs.json.Json
import play.api.mvc.{ControllerComponents, MultipartFormData}
import services.SubmissionService
import uk.gov.hmrc.internalauth.client._
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendBaseController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SubmissionController @Inject() (
                                       override val controllerComponents: ControllerComponents,
                                       submissionService: SubmissionService,
                                       submissionFormProvider: SubmissionFormProvider,
                                       auth: BackendAuthComponents
                                     )(implicit ec: ExecutionContext) extends BackendBaseController with I18nSupport {

  private val permission = Predicate.Permission(
    resource = Resource(
      resourceType = ResourceType("dms-submission"),
      resourceLocation = ResourceLocation("submit")
    ),
    action = IAAction("WRITE")
  )

  private val authorised = auth.authorizedAction(permission, Retrieval.username)

  def submit = authorised.compose(Action(parse.multipartFormData(false))).async { implicit request =>
    val result: EitherT[Future, NonEmptyChain[String], String] = (
      EitherT.fromEither[Future](getSubmissionRequest(request.body)),
      EitherT.fromEither[Future](getFile(request.body))
    ).parTupled.flatMap { case (submissionRequest, file) =>
      EitherT.liftF(submissionService.submit(submissionRequest, file, request.retrieval.value))
    }
    result.fold(
      errors        => BadRequest(Json.toJson(SubmissionResponse.Failure(errors))),
      correlationId => Accepted(Json.toJson(SubmissionResponse.Success(correlationId)))
    )
  }

  private def getSubmissionRequest(formData: MultipartFormData[Files.TemporaryFile])(implicit messages: Messages): EitherNec[String, SubmissionRequest] =
    submissionFormProvider.form.bindFromRequest(formData.dataParts).fold(
      formWithErrors => Left(NonEmptyChain.fromSeq(formWithErrors.errors.map(error => formatError(error.key, error.format))).get), // always safe
      _.rightNec[String]
    )

  private def getFile(formData: MultipartFormData[Files.TemporaryFile])(implicit messages: Messages): EitherNec[String, File] =
    formData.file("form")
      .map(file => File(file.ref))
      .toRight(NonEmptyChain.one(formatError("form", Messages("error.required"))))

  private def formatError(key: String, message: String): String = s"$key: $message"
}
