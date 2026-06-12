package uk.gov.justice.digital.hmpps.dpsreconciliation.services

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono

@Service
class PrisonerSearchApi(@Qualifier("prisonerSearchApiWebClient") private val webClient: WebClient) {
  fun getPrisoner(offenderNo: String): Prisoner = webClient
    .get()
    .uri("/prisoner/{offenderNo}", offenderNo)
    .retrieve()
    .bodyToMono<Prisoner>()
    .block()!!
}

/**
 * Copied from prisoner-search. Just a subset of fields available.
 */
data class Prisoner(
  var prisonerNumber: String? = null,
  var bookingId: String? = null,
  var status: String? = null,
  var lastMovementTypeCode: String? = null,
  var lastMovementReasonCode: String? = null,
  var prisonId: String? = null,
  var lastPrisonId: String? = null,
)
