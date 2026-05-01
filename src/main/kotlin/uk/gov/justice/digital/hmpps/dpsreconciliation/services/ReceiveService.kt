package uk.gov.justice.digital.hmpps.dpsreconciliation.services

import com.microsoft.applicationinsights.TelemetryClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.convertValue
import uk.gov.justice.digital.hmpps.dpsreconciliation.config.trackEvent
import uk.gov.justice.digital.hmpps.dpsreconciliation.model.MatchType
import uk.gov.justice.digital.hmpps.dpsreconciliation.model.MatchingEventPair
import uk.gov.justice.digital.hmpps.dpsreconciliation.repository.BOOKING_MOVED_EVENT
import uk.gov.justice.digital.hmpps.dpsreconciliation.repository.MERGE_EVENT
import uk.gov.justice.digital.hmpps.dpsreconciliation.repository.MatchingEventPairRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

@Service
@Transactional(isolation = Isolation.SERIALIZABLE)
class ReceiveService(
  val prisonApi: PrisonApi,
  val telemetryClient: TelemetryClient,
  val repository: MatchingEventPairRepository,
  val jsonMapper: JsonMapper,
) {
  private companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  fun externalMovementHandler(message: ExternalPrisonerMovementMessage) {
    log.debug("externalMovementHandler {}", message)

    val movements = prisonApi.getMovementsForBooking(message.bookingId!!)

    val thisMovement = movements.find { it.sequence == message.movementSeq }
      ?: run {
        log.info("Detected a deletion of {} (cannot find)", message)
        telemetryClient.trackEvent("offender-event-deletion", jsonMapper.convertValue<Map<String, String>>(message))
        return
      }

    if (thisMovement.modifiedDateTime != null && thisMovement.modifiedDateTime != thisMovement.createdDateTime) {
      // When event is an insert, the modifiedDateTime is either null or modifiedDateTime = createdDateTime
      log.info("Detected an update in {}", thisMovement)
      return
    }

    val previousMovement: BookingMovement? = findPreviousMovement(
      message,
      movements.sortedBy { it.sequence },
    )

    var matchOutcome = ""

    when (message.movementType) {
      "ADM" -> {
        if (message.directionCode == "IN" &&
          (previousMovement == null || isRelease(previousMovement))
        ) {
          // NB: movementReasonCode can be anything - it doesn't indicate whether it is a real 'first' entry to prison
          // Also movement datetime can be hours or even days ago when nomis entry is retrospective
          // Check for a corresponding event by actual event arrival. This should be within minutes unless there is a serious outage
          val existing = repository.findByNomsNumberAndMatchTypeAndCreatedDateAfterAndOffenderTimeIsNullAndMatchedOrderByIdAsc(
            message.offenderIdDisplay!!,
            MatchType.RECEIVED,
            LocalDateTime.now().minusMinutes(1),
            // The domain event will normally arrive after the nomis event
            // If the domain event did arrive first (about 400 per month) it is 'always' within a minute and mostly < 1s
            false,
          ).filter {
            it.domainReason != PrisonerReceiveReason.POST_MERGE_ADMISSION.name // POST_MERGE_ADMISSIONs only match merge events
          }
          if (existing.isNotEmpty()) {
            if (existing.size == 1) {
              matchOutcome = "matched in externalMovementHandler"
            } else {
              log.warn("externalMovementHandler() ADM: Unexpected multiple matches: {}", existing)
              matchOutcome = "multiple: ${existing.size} in externalMovementHandler"
            }
            with(existing[0]) {
              offenderReason = message.movementReasonCode
              offenderTime = message.movementDateTime
              offenderBookingId = message.bookingId
              matched = true
              comment = "matchOutcome = $matchOutcome"
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
                comment = "matchOutcome = saved in externalMovementHandler",
              ),
            )
            matchOutcome = "saved in externalMovementHandler"
          }
        }
      }

      "REL" -> {
        val existing = repository.findByNomsNumberAndMatchTypeAndCreatedDateAfterAndOffenderTimeIsNullAndMatchedOrderByIdAsc(
          message.offenderIdDisplay!!,
          MatchType.RELEASED,
          LocalDateTime.now().minusHours(2),
          false,
        )
        if (
          existing.isNotEmpty() &&
          existing.first().domainReason != PrisonerReleaseReason.REMOVED_FROM_HOSPITAL.name
          // Leave RP releases to the batch matcher: 'existing' could be an orphan
        ) {
          if (existing.size == 1) {
            matchOutcome = "matched in externalMovementHandler"
          } else {
            log.warn("externalMovementHandler() REL: Unexpected multiple matches: {}", existing)
            matchOutcome = "multiple: ${existing.size} in externalMovementHandler"
          }
          with(existing[0]) {
            offenderReason = message.movementReasonCode
            offenderTime = message.movementDateTime
            offenderBookingId = message.bookingId
            matched = true
            comment = "matchOutcome = $matchOutcome"
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
              comment = "matchOutcome = saved in externalMovementHandler",
            ),
          )
          matchOutcome = "saved in externalMovementHandler"
        }
      }
    }
    if (matchOutcome.isNotEmpty()) {
      telemetryClient.trackEvent(
        "offender-event",
        jsonMapper.convertValue<Map<String, String>>(message) + mapOf(
          "matchType" to (message.movementType ?: "Unknown"),
          "matchOutcome" to matchOutcome,
        ),
      )
    }
  }

  fun offenderMergeHandler(message: MergeMessage) {
    log.debug("offenderMergeHandler {}", message)

    if (message.type == "MERGE") {
      var matchOutcome: String
      val existing = repository.findByNomsNumberAndMatchTypeAndCreatedDateAfterAndOffenderTimeIsNullAndMatchedOrderByIdAsc(
        message.offenderIdDisplay!!,
        MatchType.RECEIVED,
        LocalDateTime.now().minusHours(2),
        // The domain event will normally arrive after the nomis event, so I'm expecting this to draw a blank
        false,
      ).filter {
        it.domainReason == PrisonerReceiveReason.POST_MERGE_ADMISSION.name // POST_MERGE_ADMISSIONs only match merge events
      }
      if (existing.isNotEmpty()) {
        if (existing.size == 1) {
          matchOutcome = "matched in offenderMergeHandler"
        } else {
          log.warn("offenderMergeHandler(): Unexpected multiple with ${existing.size} matches: {}", existing)
          matchOutcome = "multiple: ${existing.size} in offenderMergeHandler"
        }
        with(existing[0]) {
          offenderReason = MERGE_EVENT
          offenderTime = message.eventDatetime
          offenderBookingId = message.bookingId
          matched = true
          comment = "matchOutcome = $matchOutcome"
          relatedNomsNumber = message.previousOffenderIdDisplay
        }
      } else {
        repository.save(
          MatchingEventPair(
            matchType = MatchType.RECEIVED,
            nomsNumber = message.offenderIdDisplay,
            offenderBookingId = message.bookingId,
            offenderReason = MERGE_EVENT,
            offenderTime = message.eventDatetime,
            relatedNomsNumber = message.previousOffenderIdDisplay,
            comment = "saved in offenderMergeHandler",
          ),
        )
        matchOutcome = "saved in offenderMergeHandler"
      }
      telemetryClient.trackEvent(
        "merge-offender-event",
        jsonMapper.convertValue<Map<String, String>>(message) + mapOf("matchOutcome" to matchOutcome),
      )
    }
  }

  fun offenderBookingMovedHandler(message: BookingMovedMessage) {
    log.debug("offenderBookingMovedHandler {}", message)

    var matchOutcome: String
    val existing = repository.findByNomsNumberAndMatchTypeAndCreatedDateAfterAndOffenderTimeIsNullAndMatchedOrderByIdAsc(
      message.offenderIdDisplay!!,
      MatchType.RECEIVED,
      LocalDateTime.now().minusHours(2),
      // The domain event will normally arrive after the nomis event, so I'm expecting this to draw a blank
      false,
    ).filter {
      it.domainReason == BOOKING_MOVED_EVENT
    }
    if (existing.isNotEmpty()) {
      if (existing.size == 1) {
        matchOutcome = "matched in offenderBookingMovedHandler"
      } else {
        log.warn("offenderBookingMovedHandler(): Unexpected multiple with ${existing.size} matches: {}", existing)
        matchOutcome = "multiple: ${existing.size} in offenderBookingMovedHandler"
      }
      with(existing[0]) {
        offenderReason = BOOKING_MOVED_EVENT
        offenderTime = message.eventDatetime
        offenderBookingId = message.bookingId
        matched = true
        comment = "matchOutcome = $matchOutcome"
      }
    } else {
      repository.save(
        MatchingEventPair(
          matchType = MatchType.RECEIVED,
          nomsNumber = message.offenderIdDisplay,
          offenderBookingId = message.bookingId,
          offenderReason = BOOKING_MOVED_EVENT,
          offenderTime = message.eventDatetime,
          relatedNomsNumber = message.previousOffenderIdDisplay,
          comment = "saved in offenderBookingMovedHandler",
        ),
      )
      matchOutcome = "saved in offenderBookingMovedHandler"
    }
    telemetryClient.trackEvent(
      "booking-moved-offender-event",
      jsonMapper.convertValue<Map<String, String>>(message) + mapOf("matchOutcome" to matchOutcome),
    )
    log.debug("offenderBookingMovedHandler EXIT matchOutcome={}", matchOutcome)
  }

  fun purgeOldMatchedRecords() {
    val rows = repository.deleteByCreatedDateIsBeforeAndMatched(
      createdDate = LocalDateTime.now().minusMonths(6),
      matched = true,
    )
    telemetryClient.trackEvent("database-purge", mapOf("deleted-rows" to rows.toString()))
  }

  private fun findPreviousMovement(
    message: ExternalPrisonerMovementMessage,
    movements: List<BookingMovement>,
  ): BookingMovement? = movements
    .indexOfFirst { it.sequence == message.movementSeq }
    .takeIf { it > 0 }
    ?.let { movements[it - 1] }

  fun prisonerDomainReceiveHandler(message: PrisonerReceiveDomainEvent) {
    log.debug("prisonerDomainReceiveHandler {}", message)

    val messageDateTimeLocal = message.occurredAt.atZoneSameInstant(ZoneId.of("Europe/London")).toLocalDateTime()
    var matchOutcome = ""

    when (message.additionalInformation.reason) {
      PrisonerReceiveReason.NEW_ADMISSION,
      PrisonerReceiveReason.READMISSION,
      PrisonerReceiveReason.READMISSION_SWITCH_BOOKING,
      PrisonerReceiveReason.POST_MERGE_ADMISSION, // will match with a merge event, not a movement
      -> {
        val existing = repository.findByNomsNumberAndMatchTypeAndCreatedDateAfterAndDomainTimeIsNullAndMatchedOrderByIdAsc(
          message.additionalInformation.nomsNumber,
          MatchType.RECEIVED,
          LocalDateTime.now().minusHours(2),
          false,
        ).filter {
          eitherStandardAdmissionOrMerge(message, it)
        }
        if (existing.isNotEmpty()) {
          if (existing.size == 1) {
            matchOutcome = "matched in prisonerDomainReceiveHandler"
          } else {
            log.warn("prisonerDomainReceiveHandler(): Unexpected multiple with ${existing.size} matches: {}", existing)
            matchOutcome = "multiple: ${existing.size} in prisonerDomainReceiveHandler"
          }
          with(existing[0]) {
            domainReason = message.additionalInformation.reason.name
            domainTime = messageDateTimeLocal
            matched = true
            comment = "matchOutcome = $matchOutcome"
          }
        } else {
          repository.save(
            MatchingEventPair(
              matchType = MatchType.RECEIVED,
              nomsNumber = message.additionalInformation.nomsNumber,
              domainReason = message.additionalInformation.reason.name,
              domainTime = messageDateTimeLocal,
              comment = "saved in prisonerDomainReceiveHandler",
            ),
          )
          matchOutcome = "saved in prisonerDomainReceiveHandler"
        }
        telemetryClient.trackEvent(
          "domain-event",
          mapOf(
            "type" to MatchType.RECEIVED.name,
            "occurredAt" to messageDateTimeLocal.toString(),
            "nomsNumber" to message.additionalInformation.nomsNumber,
            "reason" to message.additionalInformation.reason.name,
            "matchOutcome" to matchOutcome,
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
    val messageDateTimeLocal = message.occurredAt.atZoneSameInstant(ZoneId.of("Europe/London")).toLocalDateTime()
    var matchOutcome = ""

    when (message.additionalInformation.reason) {
      PrisonerReleaseReason.RELEASED,
      PrisonerReleaseReason.RELEASED_TO_HOSPITAL,
      -> {
        val existing = repository.findByNomsNumberAndMatchTypeAndCreatedDateAfterAndDomainTimeIsNullAndMatchedOrderByIdAsc(
          message.additionalInformation.nomsNumber,
          MatchType.RELEASED,
          LocalDateTime.now().minusHours(2),
          false,
        )
        if (existing.isNotEmpty()) {
          if (existing.size == 1) {
            matchOutcome = "matched in prisonerDomainReleaseHandler"
          } else {
            log.warn("prisonerDomainReleaseHandler(): Unexpected multiple with ${existing.size} matches: {}", existing)
            matchOutcome = "multiple: ${existing.size} in prisonerDomainReleaseHandler"
          }
          with(existing[0]) {
            domainReason = message.additionalInformation.reason.name
            domainTime = messageDateTimeLocal
            matched = true
            comment = "matchOutcome = $matchOutcome"
          }
        } else {
          repository.save(
            MatchingEventPair(
              matchType = MatchType.RELEASED,
              nomsNumber = message.additionalInformation.nomsNumber,
              domainReason = message.additionalInformation.reason.name,
              domainTime = messageDateTimeLocal,
              comment = "saved in prisonerDomainReleaseHandler",
            ),
          )
          matchOutcome = "saved in prisonerDomainReleaseHandler"
        }
        telemetryClient.trackEvent(
          "domain-event",
          mapOf(
            "type" to MatchType.RELEASED.name,
            "occurredAt" to messageDateTimeLocal.toString(),
            "nomsNumber" to message.additionalInformation.nomsNumber,
            "reason" to message.additionalInformation.reason.name,
            "matchOutcome" to matchOutcome,
          ) + (message.additionalInformation.prisonId?.let { mapOf("prisonId" to it) } ?: emptyMap()),
        )
      }

      PrisonerReleaseReason.TRANSFERRED,
      PrisonerReleaseReason.TEMPORARY_ABSENCE_RELEASE,
      PrisonerReleaseReason.SENT_TO_COURT,
      PrisonerReleaseReason.REMOVED_FROM_HOSPITAL,
      -> null
    }
  }

  /*
  If a prisoner leaves a hospital there may be a corresponding OUT movement or there may not,
  e.g.
    27th 08:31  EXTERNAL_MOVEMENT-CHANGED ADM IN
    27th 08:31. restricted-patients.patient.removed, from restricted-patient-removed-cleanup (FROM hmpps-restricted-patients-api)
    prisoner-offender-search.prisoner.received

    27th 14:42  EXTERNAL_MOVEMENT-CHANGED REL
    prisoner-offender-search.prisoner.released
   */
  fun restrictedPatientRemovedHandler(message: RestrictedPatientRemovedDomainEvent) {
    log.debug("restrictedPatientRemovedHandler {}", message)
    val messageDateTimeLocal = message.occurredAt.atZoneSameInstant(ZoneId.of("Europe/London")).toLocalDateTime()
    var matchOutcome: String

    val existing = repository.findByNomsNumberAndMatchTypeAndCreatedDateAfterAndDomainTimeIsNullAndMatchedOrderByIdAsc(
      message.additionalInformation.prisonerNumber,
      MatchType.RELEASED,
      LocalDateTime.now().minusHours(2),
      false,
    ).filter {
      it.previousOffenderDirection == "OUT" &&
        it.previousOffenderReason == "HP"
    }

    if (existing.isNotEmpty()) {
      if (existing.size == 1) {
        matchOutcome = "matched in restrictedPatientRemovedHandler"
      } else {
        log.warn("restrictedPatientRemovedHandler(): Unexpected multiple with ${existing.size} matches: {}", existing)
        matchOutcome = "multiple: ${existing.size} in restrictedPatientRemovedHandler"
      }
      with(existing[0]) {
        domainReason = PrisonerReleaseReason.REMOVED_FROM_HOSPITAL.name
        domainTime = messageDateTimeLocal
        matched = true
        comment = "matchOutcome = $matchOutcome"
      }
    } else {
      repository.save(
        MatchingEventPair(
          matchType = MatchType.RELEASED,
          nomsNumber = message.additionalInformation.prisonerNumber,
          domainReason = PrisonerReleaseReason.REMOVED_FROM_HOSPITAL.name,
          domainTime = messageDateTimeLocal,
          comment = "saved in restrictedPatientRemovedHandler",
        ),
      )
      matchOutcome = "saved in restrictedPatientRemovedHandler"
    }
    telemetryClient.trackEvent(
      "restricted-patient-event",
      mapOf(
        "type" to MatchType.RELEASED.name,
        "occurredAt" to messageDateTimeLocal.toString(),
        "nomsNumber" to message.additionalInformation.prisonerNumber,
        "reason" to PrisonerReleaseReason.REMOVED_FROM_HOSPITAL.name,
        "matchOutcome" to matchOutcome,
      ),
    )
  }

  fun prisonerDomainBookingMovedHandler(message: BookingMovedDomainEvent) {
    log.debug("prisonerDomainBookingMovedHandler {}", message)

    val messageDateTimeLocal = message.occurredAt.atZoneSameInstant(ZoneId.of("Europe/London")).toLocalDateTime()
    var matchOutcome = ""

    val existing = repository.findByNomsNumberAndMatchTypeAndCreatedDateAfterAndDomainTimeIsNullAndMatchedOrderByIdAsc(
      message.additionalInformation.movedToNomsNumber,
      MatchType.RECEIVED,
      LocalDateTime.now().minusHours(2),
      false,
    ).filter { it.offenderReason == BOOKING_MOVED_EVENT }

    if (existing.isNotEmpty()) {
      if (existing.size == 1) {
        matchOutcome = "matched in prisonerDomainBookingMovedHandler"
      } else {
        log.warn("prisonerDomainBookingMovedHandler(): Unexpected multiple with ${existing.size} matches: {}", existing)
        matchOutcome = "multiple: ${existing.size} in prisonerDomainBookingMovedHandler"
      }
      with(existing[0]) {
        domainReason = BOOKING_MOVED_EVENT
        domainTime = messageDateTimeLocal
        matched = true
        comment = "matchOutcome = $matchOutcome"
      }
    } else {
      repository.save(
        MatchingEventPair(
          matchType = MatchType.RECEIVED,
          nomsNumber = message.additionalInformation.movedToNomsNumber,
          domainReason = BOOKING_MOVED_EVENT,
          domainTime = messageDateTimeLocal,
          relatedNomsNumber = message.additionalInformation.movedFromNomsNumber,
          comment = "saved in prisonerDomainBookingMovedHandler",
        ),
      )
      matchOutcome = "saved in prisonerDomainBookingMovedHandler"
    }
    telemetryClient.trackEvent(
      "booking-moved-domain-event",
      mapOf(
        "type" to MatchType.RECEIVED.name,
        "occurredAt" to messageDateTimeLocal.toString(),
        "nomsNumber" to message.additionalInformation.movedToNomsNumber,
        "reason" to "booking-moved-event",
        "matchOutcome" to matchOutcome,
      ) + mapOf("movedFromNomsNumber" to message.additionalInformation.movedFromNomsNumber),
    )
    log.debug("prisonerDomainBookingMovedHandler EXIT matchOutcome={}", matchOutcome)
  }

  fun batchMatch(startCreatedDate: LocalDateTime, endCreatedDate: LocalDateTime) {
    val nonMatches = repository.findByCreatedDateIsBetweenAndMatched(
      startCreatedDate,
      endCreatedDate,
      false,
    )
    matchUpHospitalReleases(nonMatches)
    matchUpMergeReceives(nonMatches)

    matchUpBookingMovedReceivesAndReleases(
      nonMatches,
      repository.findByCreatedDateIsBetweenAndIsBookingMoved(startCreatedDate, endCreatedDate),
    )
  }

  fun detectNonMatches(startCreatedDate: LocalDateTime, endCreatedDate: LocalDateTime): String {
    val nonMatches = repository.findByCreatedDateIsBetweenAndMatched(
      startCreatedDate,
      endCreatedDate,
      false,
    )

    telemetryClient.trackEvent(
      "non-match-event",
      mapOf(
        "count" to nonMatches.size.toString(),
      ) + nonMatches
        .take(20)
        .mapIndexed { index, it -> index + 1 to it }
        .associate { it.first.toString() to it.second.toString() },
    )
    return "Found ${nonMatches.size} non-matching events"
  }

  private fun matchUpHospitalReleases(nonMatches: List<MatchingEventPair>) {
    nonMatches.groupBy { it.nomsNumber }
      .values
      .forEach { value ->
        if (isUnmatchedRelease(value)) {
          val domainEvent = if (value[0].isDomainOnly()) value.first() else value.last()
          val offenderEvent = if (value[0].isDomainOnly()) value.last() else value.first()
          if (
            domainEvent.domainReason == PrisonerReleaseReason.REMOVED_FROM_HOSPITAL.name &&
            offenderEvent.previousOffenderReason == "HP"
          ) {
            telemetryClient.trackEvent(
              "batch-match",
              mapOf(
                "domainRow" to domainEvent.toString(),
                "offenderRow" to offenderEvent.toString(),
              ),
            )
            offenderEvent.domainReason = domainEvent.domainReason
            offenderEvent.domainTime = domainEvent.domainTime
            offenderEvent.matched = true
            offenderEvent.comment = "Matched with $domainEvent in prisonerDomainBookingMovedHandler isUnmatchedRelease"
            repository.delete(domainEvent)
          }
        } else if (isReleaseDomainOrphan(value)) {
          val domainEvent = value.first()
          if (domainEvent.domainReason == PrisonerReleaseReason.REMOVED_FROM_HOSPITAL.name) {
            telemetryClient.trackEvent(
              "batch-match-orphan",
              mapOf(
                "domainRow" to domainEvent.toString(),
              ),
            )
            domainEvent.offenderReason = "RP-EVENT"
            domainEvent.matched = true
            domainEvent.comment = "Matched as orphan in prisonerDomainBookingMovedHandler isReleaseDomainOrphan"
          }
        }
      }
  }

  /**
   * Looking for an offender merge event where there is a missing POST_MERGE_ADMISSION domain event
   */
  private fun matchUpMergeReceives(nonMatches: List<MatchingEventPair>) {
    nonMatches.groupBy { it.nomsNumber }
      .values
      .forEach { value ->
        if (isReceiveOffenderOrphan(value)) {
          val offenderEvent = value.first()
          if (offenderEvent.offenderReason == MERGE_EVENT) {
            offenderEvent.domainReason = MERGE_EVENT
            offenderEvent.matched = true
            offenderEvent.comment = "Matched as orphan in matchUpMergeReceives"
          }
        }
      }
  }

  /**
   * Looking for 1 unmatched receive and 1 release domain events where there is a booking.moved for that prisoner
   * These always seem to occur within a second or 2 of the booking.moved event
   */
  fun matchUpBookingMovedReceivesAndReleases(
    nonMatches: List<MatchingEventPair>,
    bookingMovedEvents: List<MatchingEventPair>,
  ) {
    fun LocalDateTime.isInRangeOf(rangeBase: LocalDateTime) = isAfter(rangeBase) && isBefore(rangeBase.plusSeconds(10))

    bookingMovedEvents.forEach { matchRow ->
      val receives = nonMatches.filter {
        it.nomsNumber == matchRow.nomsNumber &&
          !it.matched &&
          it.isDomainOnly() &&
          it.matchType == MatchType.RECEIVED &&
          it.createdDate.isInRangeOf(matchRow.createdDate)
      }
      val releases = nonMatches.filter {
        it.nomsNumber == matchRow.relatedNomsNumber &&
          !it.matched &&
          it.isDomainOnly() &&
          it.matchType == MatchType.RELEASED &&
          it.createdDate.isInRangeOf(matchRow.createdDate)
      }

      if (receives.size > 1 || releases.size > 1) {
        log.warn("more than 1 unmatched receive or release domain event found, abandoning matching for $matchRow")
      } else {
        if (receives.isNotEmpty()) {
          with(receives.first()) {
            matched = true
            offenderReason = BOOKING_MOVED_EVENT
            offenderBookingId = matchRow.offenderBookingId
            offenderTime = LocalDateTime.now()
            relatedNomsNumber = matchRow.relatedNomsNumber
            comment = "Matched as orphan in matchUpBookingMovedReceivesAndReleases"
          }
        }
        if (releases.isNotEmpty()) {
          with(releases.first()) {
            matched = true
            offenderReason = BOOKING_MOVED_EVENT
            offenderBookingId = matchRow.offenderBookingId
            offenderTime = LocalDateTime.now()
            relatedNomsNumber = matchRow.nomsNumber
            comment = "Matched as orphan in matchUpBookingMovedReceivesAndReleases"
          }
        }
      }
    }
  }
}

private fun eitherStandardAdmissionOrMerge(
  message: PrisonerReceiveDomainEvent,
  pair: MatchingEventPair,
): Boolean = (message.additionalInformation.reason == PrisonerReceiveReason.POST_MERGE_ADMISSION) ==
  (pair.offenderReason == MERGE_EVENT)

private fun isRelease(previousMovement: BookingMovement): Boolean = previousMovement.movementType == "REL"

private fun isUnmatchedRelease(value: List<MatchingEventPair>): Boolean = value.size == 2 &&
  value.first().matchType == MatchType.RELEASED &&
  value.last().matchType == MatchType.RELEASED

private fun isReceiveOffenderOrphan(value: List<MatchingEventPair>): Boolean = value.size == 1 &&
  value.first().matchType == MatchType.RECEIVED &&
  value.first().isOffenderOnly()

private fun isReleaseDomainOrphan(value: List<MatchingEventPair>): Boolean = value.size == 1 &&
  value.first().matchType == MatchType.RELEASED &&
  value.first().isDomainOnly()

data class ExternalPrisonerMovementMessage(
  val eventType: String?,
  val eventDatetime: LocalDateTime? = null,
  val bookingId: Long? = null,
  val offenderIdDisplay: String? = null,
  val nomisEventType: String? = null,
  val movementSeq: Int?,
  val movementDateTime: LocalDateTime?,
  val movementType: String?,
  val movementReasonCode: String?,
  val directionCode: String?,
  val escortCode: String?,
  val fromAgencyLocationId: String?,
  val toAgencyLocationId: String?,
)

data class MergeMessage(
  val eventType: String?,
  val eventDatetime: LocalDateTime? = null,
  val bookingId: Long? = null,
  val offenderIdDisplay: String? = null,
  val offenderId: Long? = null,
  val previousBookingNumber: String? = null,
  val previousOffenderIdDisplay: String? = null,
  val type: String? = null, // should be MERGE
)

data class BookingMovedMessage(
  val eventType: String?,
  val eventDatetime: LocalDateTime? = null,
  val bookingId: Long? = null,
  val offenderIdDisplay: String? = null,
  val offenderId: Long? = null,
  val previousOffenderIdDisplay: String? = null,
  val previousOffenderId: Long? = null,
  val lastAdmissionDate: LocalDate? = null,
  val bookingStartDateTime: LocalDateTime? = null,
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
  REMOVED_FROM_HOSPITAL("released from a secure hospital"),
}

data class RestrictedPatientRemovedDomainEvent(
  val occurredAt: OffsetDateTime,
  val additionalInformation: RestrictedPatientAdditionalInformationEvent,
)

data class RestrictedPatientAdditionalInformationEvent(
  val prisonerNumber: String,
)

data class BookingMovedDomainEvent(
  val eventType: String?,
  val occurredAt: OffsetDateTime,
  val additionalInformation: BookingMovedAdditionalInformationEvent,
)

data class BookingMovedAdditionalInformationEvent(
  val bookingId: Long,
  val movedFromNomsNumber: String,
  val movedToNomsNumber: String,
  val bookingStartDateTime: LocalDateTime? = null,
  val bookingEndDateTime: LocalDateTime? = null,
)
