package uk.gov.justice.digital.hmpps.dpsreconciliation.services

import com.microsoft.applicationinsights.TelemetryClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.dpsreconciliation.config.trackEvent

@Service
class ReceiveService(
  val telemetryClient: TelemetryClient,
) {
  private companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  suspend fun movementReceived(message: ExternalPrisonerMovementMessage) {
    log.debug("Received ExternalPrisonerMovementMessage message {}", message)
    // add to database using update or insert syntax.
    // If completes row, (soft?) delete that row.
    telemetryClient.trackEvent("offender-event-received", emptyMap())
  }

  suspend fun prisonerReceived(message: PrisonerReceiveDomainEvent) {
    log.debug("Received PrisonerReceiveDomainEvent message {}", message)
    telemetryClient.trackEvent("domain-event-received", emptyMap())
  }
}

data class ExternalPrisonerMovementMessage(val bookingId: Long)

data class PrisonerReceiveDomainEvent(
  val additionalInformation: ReceivePrisonerAdditionalInformationEvent,
)

data class ReceivePrisonerAdditionalInformationEvent(
  val nomsNumber: String,
  val reason: PrisonerReceiveReason,
)

enum class PrisonerReceiveReason(val description: String) {
  NEW_ADMISSION("admission on new charges"),
  READMISSION("re-admission on an existing booking"),
  READMISSION_SWITCH_BOOKING("re-admission but switched to old booking"),
  TRANSFERRED("transfer from another prison"),
  RETURN_FROM_COURT("returned back to prison from court"),
  TEMPORARY_ABSENCE_RETURN("returned after a temporary absence"),
  POST_MERGE_ADMISSION("admission following an offender merge"),
}
