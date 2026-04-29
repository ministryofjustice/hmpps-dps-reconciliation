package uk.gov.justice.digital.hmpps.dpsreconciliation.services

import com.microsoft.applicationinsights.TelemetryClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import tools.jackson.databind.json.JsonMapper
import uk.gov.justice.digital.hmpps.dpsreconciliation.model.MatchType
import uk.gov.justice.digital.hmpps.dpsreconciliation.model.MatchingEventPair
import uk.gov.justice.digital.hmpps.dpsreconciliation.repository.BOOKING_MOVED_EVENT
import uk.gov.justice.digital.hmpps.dpsreconciliation.repository.MatchingEventPairRepository
import java.time.LocalDateTime

private const val NOMS_NUMBER = "A1234AA"
private const val RELATED_PRISONER = "B2345BB"

class ReceiveServiceTest {
  val prisonApi: PrisonApi = mock()
  val telemetryClient: TelemetryClient = mock()
  val repository: MatchingEventPairRepository = mock()
  val jsonMapper: JsonMapper = mock()

  val service: ReceiveService = ReceiveService(prisonApi, telemetryClient, repository, jsonMapper)

  @Nested
  inner class MatchUpBookingMovedReceivesAndReleases {
    @Test
    fun `match up the correct booking moved events`() {
      val notDomainOnly = MatchingEventPair(
        matchType = MatchType.RECEIVED,
        nomsNumber = NOMS_NUMBER,
        domainReason = PrisonerReceiveReason.READMISSION_SWITCH_BOOKING.name,
        domainTime = LocalDateTime.parse("2026-04-01T12:00:00"),
        createdDate = LocalDateTime.parse("2026-04-01T12:00:00"),
        offenderTime = LocalDateTime.parse("2026-04-01T12:00:00"),
        matched = false,
      )
      val alreadyMatched = MatchingEventPair(
        matchType = MatchType.RECEIVED,
        nomsNumber = NOMS_NUMBER,
        domainReason = PrisonerReceiveReason.READMISSION_SWITCH_BOOKING.name,
        domainTime = LocalDateTime.parse("2026-04-01T12:00:00"),
        createdDate = LocalDateTime.parse("2026-04-01T12:00:00"),
        matched = true,
      )
      val otherPrisoner = MatchingEventPair(
        matchType = MatchType.RECEIVED,
        nomsNumber = "SOME-OTHER",
        domainReason = PrisonerReceiveReason.READMISSION_SWITCH_BOOKING.name,
        domainTime = LocalDateTime.parse("2026-04-01T12:00:00"),
        createdDate = LocalDateTime.parse("2026-04-01T12:00:00"),
        matched = false,
      )
      val receivedEvent = MatchingEventPair(
        matchType = MatchType.RECEIVED,
        nomsNumber = NOMS_NUMBER,
        domainReason = PrisonerReceiveReason.READMISSION_SWITCH_BOOKING.name,
        domainTime = LocalDateTime.parse("2026-04-01T12:00:00"),
        createdDate = LocalDateTime.parse("2026-04-01T12:00:00"),
        matched = false,
      )
      val releasedEvent = MatchingEventPair(
        matchType = MatchType.RELEASED,
        nomsNumber = RELATED_PRISONER,
        domainReason = PrisonerReleaseReason.RELEASED.name,
        domainTime = LocalDateTime.parse("2026-04-01T12:00:00"),
        createdDate = LocalDateTime.parse("2026-04-01T12:00:00"),
        matched = false,
      )

      service.matchUpBookingMovedReceivesAndReleases(
        nonMatches = listOf(
          notDomainOnly,
          alreadyMatched,
          otherPrisoner,
          receivedEvent,
          releasedEvent,
        ),
        bookingMoveds = listOf(
          MatchingEventPair(
            matchType = MatchType.RECEIVED,
            nomsNumber = NOMS_NUMBER,
            relatedNomsNumber = RELATED_PRISONER,
            domainReason = BOOKING_MOVED_EVENT,
            domainTime = LocalDateTime.parse("2026-04-01T12:00:00"),
            offenderBookingId = 1234567L,
            offenderReason = BOOKING_MOVED_EVENT,
            offenderTime = LocalDateTime.parse("2026-04-01T12:00:00"),
//          previousOffenderReason = xxxx,
//          previousOffenderTime = xxxx,
//          previousOffenderDirection = xxxx,
            createdDate = LocalDateTime.parse("2026-04-01T12:00:00"),
            matched = true,
          ),
          MatchingEventPair(
            matchType = MatchType.RECEIVED,
            nomsNumber = "OTHER1",
            relatedNomsNumber = "OTHER2",
            domainReason = BOOKING_MOVED_EVENT,
            domainTime = LocalDateTime.parse("2026-04-01T12:00:00"),
            offenderBookingId = 1234567L,
            offenderReason = BOOKING_MOVED_EVENT,
            offenderTime = LocalDateTime.parse("2026-04-01T12:00:00"),
            createdDate = LocalDateTime.parse("2026-04-01T12:00:00"),
            matched = true,
          ),
        ),
      )

      assertThat(receivedEvent.matched).isTrue()
      assertThat(receivedEvent.offenderReason).isEqualTo(BOOKING_MOVED_EVENT)
      assertThat(releasedEvent.matched).isTrue()
      assertThat(releasedEvent.offenderReason).isEqualTo(BOOKING_MOVED_EVENT)
      assertThat(notDomainOnly.offenderReason).isNotEqualTo(BOOKING_MOVED_EVENT)
      assertThat(alreadyMatched.offenderReason).isNotEqualTo(BOOKING_MOVED_EVENT)
      assertThat(otherPrisoner.offenderReason).isNotEqualTo(BOOKING_MOVED_EVENT)
    }

    @Test
    fun `too many receive events`() {
      val receivedEvent = MatchingEventPair(
        matchType = MatchType.RECEIVED,
        nomsNumber = NOMS_NUMBER,
        domainReason = PrisonerReceiveReason.READMISSION_SWITCH_BOOKING.name,
        domainTime = LocalDateTime.parse("2026-04-01T12:00:00"),
        createdDate = LocalDateTime.parse("2026-04-01T12:00:00"),
        matched = false,
      )

      service.matchUpBookingMovedReceivesAndReleases(
        nonMatches = listOf(
          receivedEvent,
          receivedEvent,
        ),
        bookingMoveds = listOf(
          MatchingEventPair(
            matchType = MatchType.RECEIVED,
            nomsNumber = NOMS_NUMBER,
            relatedNomsNumber = RELATED_PRISONER,
            domainReason = BOOKING_MOVED_EVENT,
            domainTime = LocalDateTime.parse("2026-04-01T12:00:00"),
            offenderBookingId = 1234567L,
            offenderReason = BOOKING_MOVED_EVENT,
            offenderTime = LocalDateTime.parse("2026-04-01T12:00:00"),
            createdDate = LocalDateTime.parse("2026-04-01T12:00:00"),
            matched = true,
          ),
        ),
      )

      assertThat(receivedEvent.matched).isFalse()
      assertThat(receivedEvent.offenderReason).isNotEqualTo(BOOKING_MOVED_EVENT)
    }

    @Test
    fun `too many release events`() {
      val releasedEvent = MatchingEventPair(
        matchType = MatchType.RELEASED,
        nomsNumber = RELATED_PRISONER,
        domainReason = PrisonerReleaseReason.RELEASED.name,
        domainTime = LocalDateTime.parse("2026-04-01T12:00:00"),
        createdDate = LocalDateTime.parse("2026-04-01T12:00:00"),
        matched = false,
      )

      service.matchUpBookingMovedReceivesAndReleases(
        nonMatches = listOf(
          releasedEvent,
          releasedEvent,
        ),
        bookingMoveds = listOf(
          MatchingEventPair(
            matchType = MatchType.RECEIVED,
            nomsNumber = NOMS_NUMBER,
            relatedNomsNumber = RELATED_PRISONER,
            domainReason = BOOKING_MOVED_EVENT,
            domainTime = LocalDateTime.parse("2026-04-01T12:00:00"),
            offenderBookingId = 1234567L,
            offenderReason = BOOKING_MOVED_EVENT,
            offenderTime = LocalDateTime.parse("2026-04-01T12:00:00"),
            createdDate = LocalDateTime.parse("2026-04-01T12:00:00"),
            matched = true,
          ),
        ),
      )

      assertThat(releasedEvent.matched).isFalse()
      assertThat(releasedEvent.offenderReason).isNotEqualTo(BOOKING_MOVED_EVENT)
    }
  }
}
