package uk.gov.justice.digital.hmpps.dpsreconciliation.model

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class Movement(
  val offenderNo: @NotBlank String? = null,
  val createDateTime: @NotNull LocalDateTime? = null,
  val fromAgency: String? = null,
  val fromAgencyDescription: String? = null,
  val toAgency: String? = null,
  val toAgencyDescription: String? = null,
  val fromCity: String? = null,
  val toCity: String? = null,
  val movementType: @NotBlank String? = null,
  val movementTypeDescription: String? = null,
  val directionCode: @NotBlank String? = null,
  val movementDate: LocalDate? = null,
  val movementTime: LocalTime? = null,
  var movementDateTime: LocalDateTime? = null,
  val movementReason: String? = null,
  val movementReasonCode: String? = null,
  val commentText: String? = null,
)
