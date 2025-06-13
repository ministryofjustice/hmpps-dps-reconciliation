package uk.gov.justice.digital.hmpps.dpsreconciliation.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class Movement(
  val offenderNo: String? = null,
  val sequence: Int? = null,
  val createDateTime: LocalDateTime? = null,
  val fromAgency: String? = null,
  val fromAgencyDescription: String? = null,
  val toAgency: String? = null,
  val toAgencyDescription: String? = null,
  val fromCity: String? = null,
  val toCity: String? = null,
  val movementType: String? = null,
  val movementTypeDescription: String? = null,
  val directionCode: String? = null,
  val movementDate: LocalDate? = null,
  val movementTime: LocalTime? = null,
  // var movementDateTime: LocalDateTime? = null,
  val movementReason: String? = null,
  val movementReasonCode: String? = null,
  val commentText: String? = null,
)
