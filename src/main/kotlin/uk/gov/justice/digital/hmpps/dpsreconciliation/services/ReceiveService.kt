package uk.gov.justice.digital.hmpps.dpsreconciliation.services

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.convertValue
import com.microsoft.applicationinsights.TelemetryClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.dpsreconciliation.config.trackEvent
import uk.gov.justice.digital.hmpps.dpsreconciliation.model.MatchType
import uk.gov.justice.digital.hmpps.dpsreconciliation.model.MatchingEventPair
import uk.gov.justice.digital.hmpps.dpsreconciliation.model.Movement
import uk.gov.justice.digital.hmpps.dpsreconciliation.repository.MatchingEventPairRepository
import java.time.LocalDateTime
import java.time.OffsetDateTime

@Service
@Transactional
class ReceiveService(
  val telemetryClient: TelemetryClient,
  val repository: MatchingEventPairRepository,
  val objectMapper: ObjectMapper,
) {
  private companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  /* Can get all event logging info using:
  AppEvents
  | where AppRoleName== 'hmpps-domain-event-logger'
    and Name in ('prisoner-offender-search.prisoner.received')
  | union (AppEvents
  | where AppRoleName== 'hmpps-prisoner-events'
    and Name in ('EXTERNAL_MOVEMENT_RECORD-INSERTED')
    )
  | order by TimeGenerated asc
   */

  fun externalMovementHandler(message: ExternalPrisonerMovementMessage) {
    log.debug("externalMovementHandler {}", message)

    val previousMovement: Movement? = null // TODO findPreviousMovement(message)

    when (message.movementType) {
      "ADM" -> {
        if (message.directionCode == "IN" && message.movementReasonCode != "INT") {
          // Check for a corresponding event
          val existing = repository.findByNomsNumberAndMatchTypeAndDomainTimeAfterAndMatched(
            message.offenderIdDisplay!!,
            MatchType.RECEIVED,
            message.movementDateTime!!.minusHours(2),
            // The domain event will normally arrive after the nomis event, so I'm expecting this to draw a blank
            false,
          )
          if (existing.size == 1) {
            with(existing[0]) {
              offenderReason = message.movementReasonCode
              offenderTime = message.movementDateTime
              offenderBookingId = message.bookingId
              matched = true
            }
          } else {
            repository.save(
              MatchingEventPair(
                matchType = MatchType.RECEIVED,
                nomsNumber = message.offenderIdDisplay,
                offenderBookingId = message.bookingId,
                offenderReason = message.movementReasonCode,
                offenderTime = message.movementDateTime,
                previousOffenderReason = previousMovement?.movementReasonCode,
                previousOffenderTime = previousMovement?.movementDateTime,
                previousOffenderDirection = previousMovement?.directionCode,
                // previousOffenderBookingId = previousMovement?.bookingId, TODO: may not be required
                // previousOffenderStatus = previous booking status
              ),
            )
          }
        }
      }

      "REL" -> {
        val existing = repository.findByNomsNumberAndMatchTypeAndDomainTimeAfterAndMatched(
          message.offenderIdDisplay!!,
          MatchType.RELEASED,
          message.movementDateTime!!.minusHours(2),
          false,
        )
        if (existing.size == 1) {
          with(existing[0]) {
            offenderReason = message.movementReasonCode
            offenderTime = message.movementDateTime
            offenderBookingId = message.bookingId
            matched = true
          }
        } else {
          repository.save(
            MatchingEventPair(
              matchType = MatchType.RELEASED,
              nomsNumber = message.offenderIdDisplay,
              offenderBookingId = message.bookingId,
              offenderReason = message.movementReasonCode,
              offenderTime = message.movementDateTime,
              previousOffenderReason = previousMovement?.movementReasonCode,
              previousOffenderTime = previousMovement?.movementDateTime,
              previousOffenderDirection = previousMovement?.directionCode,
              // previousOffenderBookingId = previousMovement?.bookingId, TODO: may not be required
              // previousOffenderStatus = previous booking status
            ),
          )
        }
      }
    }
    telemetryClient.trackEvent("offender-event-received", objectMapper.convertValue<Map<String, String>>(message))
  }

//  private fun findPreviousMovement(message: ExternalPrisonerMovementMessage): Movement? {
//    val movements = prisonApi.getMovementsForOffender(message.offenderIdDisplay!!)
//      .onEach { it.movementDateTime = it.movementDate?.atTime(it.movementTime) }
//      .sortedBy { it.movementDateTime }
//
//    return movements.indexOfFirst { it.movementDateTime == message.movementDateTime }
//      .takeIf { it > 0 }
//      ?.let { movements[it - 1] }
//  }

  fun matches(
    domainReceiveReason: PrisonerReceiveReason,
    domainReceivedTime: LocalDateTime,
    offenderReasonCode: String,
    offenderReceivedTime: LocalDateTime,
  ): Boolean {
    when (domainReceiveReason) {
      PrisonerReceiveReason.NEW_ADMISSION ->
        /*
           private fun Prisoner.isNewAdmission(previousPrisonerSnapshot: Prisoner?) = this.lastMovementTypeCode == "ADM" &&
          this.status == "ACTIVE IN" &&
          this.bookingId != previousPrisonerSnapshot?.bookingId
         OR
          private fun Prisoner.isNewAdmissionDueToMoveBooking(previousPrisonerSnapshot: Prisoner?) =
          previousPrisonerSnapshot?.bookingId == null && this.status == "ACTIVE IN"
         */
        null

      PrisonerReceiveReason.READMISSION ->
        /*
           private fun Prisoner.isReadmission(previousPrisonerSnapshot: Prisoner?) = this.lastMovementTypeCode == "ADM" &&
            this.bookingId == previousPrisonerSnapshot?.bookingId &&
            this.status == "ACTIVE IN" &&
            previousPrisonerSnapshot?.status == "INACTIVE OUT"
         */
        null

      PrisonerReceiveReason.READMISSION_SWITCH_BOOKING ->
        /*
          private fun Prisoner.isReadmissionSwitchBooking(previousPrisonerSnapshot: Prisoner?) = this.lastMovementTypeCode == "ADM" &&
            previousPrisonerSnapshot?.bookingId != null &&
            this.bookingId != previousPrisonerSnapshot.bookingId &&
            this.bookingId.isBookingBefore(previousPrisonerSnapshot.bookingId) &&
            this.status == "ACTIVE IN" &&
            previousPrisonerSnapshot.status == "INACTIVE OUT"
         */
        null

      PrisonerReceiveReason.POST_MERGE_ADMISSION ->
        /*
          isNewAdmission(previousPrisonerSnapshot) && isAdmissionAssociatedWithAMerge
       AND
            private fun isAdmissionAssociatedWithAMerge(offenderBooking: OffenderBooking): Boolean = offenderBooking.identifiersForActiveOffender("MERGED")

  // check the merge is after the admission movement - or if there is no movement then check the merge happened in the last 90 minutes
  ?.any { it.whenCreated > maxOf(offenderBooking.lastMovementTime ?: LocalDateTime.MIN, LocalDateTime.now().minusMinutes(90)) }
  ?: false
         */
        null

      else -> null
    }
    return false
  }

  fun prisonerDomainReceiveHandler(message: PrisonerReceiveDomainEvent) {
    log.debug("prisonerDomainReceiveHandler {}", message)

    /*
    Possible reasons:
      NEW_ADMISSION - admission on new charges
      READMISSION - re-admission on an existing booking
      READMISSION_SWITCH_BOOKING - re-admission on an existing previous booking - typically after a new booking is created by mistake
      TRANSFERRED - transfer from another prison - inoutstatus = TRN
      RETURN_FROM_COURT - returned back to prison from court
      TEMPORARY_ABSENCE_RETURN - returned after a temporary absence
      POST_MERGE_ADMISSION - admission following an offender merge

Tests from prisoner-search PrisonerMovementsEventService:
-----

private fun Prisoner.isRelease(previousPrisonerSnapshot: Prisoner?) = this.lastMovementTypeCode == "REL" &&
  this.status == "INACTIVE OUT" &&
  previousPrisonerSnapshot?.active == true

private fun Prisoner.isReleaseToHospital(previousPrisonerSnapshot: Prisoner?) = this.lastMovementTypeCode == "REL" &&
  this.lastMovementReasonCode == "HP" &&
  this.status == "INACTIVE OUT" &&
  previousPrisonerSnapshot?.active == true

private fun Prisoner.isSomeOtherMovementIn(previousPrisonerSnapshot: Prisoner?) = this.inOutStatus == "IN" &&
  this.status != previousPrisonerSnapshot?.status

private fun Prisoner.isSomeOtherMovementOut(previousPrisonerSnapshot: Prisoner?) = this.inOutStatus == "OUT" &&
  this.status != previousPrisonerSnapshot?.status

private fun String?.isBookingBefore(previousSnapshotBookingId: String?): Boolean = (this?.toLong() ?: Long.MAX_VALUE) < (previousSnapshotBookingId?.toLong() ?: 0)

     */
    val messageDateTimeLocal = message.occurredAt.toLocalDateTime()
    when (message.additionalInformation.reason) {
      PrisonerReceiveReason.NEW_ADMISSION,
      PrisonerReceiveReason.READMISSION,
      PrisonerReceiveReason.READMISSION_SWITCH_BOOKING,
      PrisonerReceiveReason.POST_MERGE_ADMISSION,
      -> {
        val existing = repository.findByNomsNumberAndMatchTypeAndOffenderTimeAfterAndMatched(
          message.additionalInformation.nomsNumber,
          MatchType.RECEIVED,
          messageDateTimeLocal.minusHours(2),
          false,
        )
        if (existing.size == 1) {
          with(existing[0]) {
            domainReason = message.additionalInformation.reason.name
            domainTime = messageDateTimeLocal
            matched = true
          }
        } else {
          repository.save(
            MatchingEventPair(
              matchType = MatchType.RECEIVED,
              nomsNumber = message.additionalInformation.nomsNumber,
              domainReason = message.additionalInformation.reason.name,
              domainTime = messageDateTimeLocal,
            ),
          )
        }
        telemetryClient.trackEvent(
          "domain-event-received",
          mapOf(
            "type" to MatchType.RELEASED.name,
            "occurredAt" to messageDateTimeLocal.toString(),
            "nomsNumber" to message.additionalInformation.nomsNumber,
            "reason" to message.additionalInformation.reason.name,
          ) + (message.additionalInformation.prisonId?.let { mapOf("prisonId" to it) } ?: emptyMap()),
        )
      }

      PrisonerReceiveReason.TRANSFERRED,
      PrisonerReceiveReason.RETURN_FROM_COURT,
      PrisonerReceiveReason.TEMPORARY_ABSENCE_RETURN,
      -> null
    }
  }

  fun prisonerDomainReleaseHandler(message: PrisonerReleaseDomainEvent) {
    log.debug("prisonerDomainReleaseHandler {}", message)
    val messageDateTimeLocal = message.occurredAt.toLocalDateTime()

    when (message.additionalInformation.reason) {
      PrisonerReleaseReason.RELEASED,
      PrisonerReleaseReason.RELEASED_TO_HOSPITAL,
      -> {
        val existing = repository.findByNomsNumberAndMatchTypeAndOffenderTimeAfterAndMatched(
          message.additionalInformation.nomsNumber,
          MatchType.RELEASED,
          messageDateTimeLocal.minusHours(2),
          false,
        )
        if (existing.size == 1) {
          with(existing[0]) {
            domainReason = message.additionalInformation.reason.name
            domainTime = messageDateTimeLocal
            matched = true
          }
        } else {
          repository.save(
            MatchingEventPair(
              matchType = MatchType.RELEASED,
              nomsNumber = message.additionalInformation.nomsNumber,
              domainReason = message.additionalInformation.reason.name,
              domainTime = messageDateTimeLocal,
            ),
          )
        }
        telemetryClient.trackEvent(
          "domain-event-received",
          mapOf(
            "type" to MatchType.RELEASED.name,
            "occurredAt" to messageDateTimeLocal.toString(),
            "nomsNumber" to message.additionalInformation.nomsNumber,
            "reason" to message.additionalInformation.reason.name,
          ) + (message.additionalInformation.prisonId?.let { mapOf("prisonId" to it) } ?: emptyMap()),
        )
      }

      PrisonerReleaseReason.TRANSFERRED,
      PrisonerReleaseReason.TEMPORARY_ABSENCE_RELEASE,
      PrisonerReleaseReason.SENT_TO_COURT,
      -> null
    }
  }
}

data class ExternalPrisonerMovementMessage(
  val eventType: String?,
  val eventDatetime: LocalDateTime? = null,
  val bookingId: Long? = null,
  val offenderIdDisplay: String? = null,
  val nomisEventType: String? = null,
  val movementSeq: Long?,
  val movementDateTime: LocalDateTime?,
  val movementType: String?,
  val movementReasonCode: String?,
  val directionCode: String?,
  val escortCode: String?,
  val fromAgencyLocationId: String?,
  val toAgencyLocationId: String?,
)

data class PrisonerReceiveDomainEvent(
  val occurredAt: OffsetDateTime,
  val additionalInformation: ReceivePrisonerAdditionalInformationEvent,
)

data class ReceivePrisonerAdditionalInformationEvent(
  val nomsNumber: String,
  val reason: PrisonerReceiveReason,
  val prisonId: String? = null,
)

data class PrisonerReleaseDomainEvent(
  val occurredAt: OffsetDateTime,
  val additionalInformation: ReleasePrisonerAdditionalInformationEvent,
)

data class ReleasePrisonerAdditionalInformationEvent(
  val nomsNumber: String,
  val reason: PrisonerReleaseReason,
  val prisonId: String? = null,
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

enum class PrisonerReleaseReason(val description: String) {
  TEMPORARY_ABSENCE_RELEASE("released on temporary absence"),
  RELEASED_TO_HOSPITAL("released to a secure hospital"),
  RELEASED("released from prison"),
  SENT_TO_COURT("sent to court"),
  TRANSFERRED("transfer to another prison"),
}
