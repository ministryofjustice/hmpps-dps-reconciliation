package uk.gov.justice.digital.hmpps.dpsreconciliation.services

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.ParameterizedTypeReference
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import uk.gov.justice.digital.hmpps.dpsreconciliation.model.Movement
import java.time.LocalDateTime

@Service
class PrisonApi(@Qualifier("prisonApiWebClient") private val webClient: WebClient) {
  fun getMovementsForOffender(offenderNo: String): List<Movement> = webClient
    .get()
    .uri("/api/movements/offender/{offenderNo}", offenderNo)
    .retrieve()
    .bodyToMono(object : ParameterizedTypeReference<List<Movement>>() {})
    .block()!!

  fun getMovementsForBooking(bookingId: Long): List<BookingMovement> = webClient
    .get()
    .uri("/api/movements/booking/{bookingId}", bookingId)
    .retrieve()
    .bodyToMono(object : ParameterizedTypeReference<List<BookingMovement>>() {})
    .block()!!
}

data class BookingMovement(
  val sequence: Int? = null,
  val fromAgency: String? = null,
  val toAgency: String? = null,
  val movementType: String? = null,
  val directionCode: String? = null,
  var movementDateTime: LocalDateTime? = null,
  val movementReasonCode: String? = null,
  val createdDateTime: LocalDateTime? = null,
  val modifiedDateTime: LocalDateTime? = null,
)
