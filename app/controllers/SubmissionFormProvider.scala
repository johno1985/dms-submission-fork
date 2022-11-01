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

import models.submission.{SubmissionMetadata, SubmissionRequest}
import play.api.data.Form
import play.api.data.Forms._

import java.time.{LocalDateTime, ZoneOffset}
import javax.inject.{Inject, Singleton}

@Singleton
class SubmissionFormProvider @Inject() () {

  // TODO some validation to make sure callback urls are ok for us to call?
  val form: Form[SubmissionRequest] = Form(
    mapping(
      "callbackUrl" -> text,
      "metadata" -> mapping(
        "store" -> text
          .verifying("error.invalid", _.toBooleanOption.isDefined)
          .transform(_.toBoolean, (_: Boolean).toString),
        "source" -> text,
        "timeOfReceipt" -> localDateTime("yyyy-MM-dd'T'HH:mm:ss")
          .transform(_.toInstant(ZoneOffset.UTC), LocalDateTime.ofInstant(_, ZoneOffset.UTC)),
        "formId" -> text,
        "numberOfPages" -> number,
        "customerId" -> text,
        "submissionMark" -> text,
        "casKey" -> text,
        "classificationType" -> text,
        "businessArea" -> text
      )(SubmissionMetadata.apply)(SubmissionMetadata.unapply)
    )(SubmissionRequest.apply)(SubmissionRequest.unapply)
  )
}
