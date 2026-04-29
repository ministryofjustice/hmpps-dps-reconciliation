package uk.gov.justice.digital.hmpps.dpsreconciliation.listeners

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.groups.Tuple
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.verify
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.validDomainBookingMovedMessage
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.validOffenderBookingMovedMessage
import uk.gov.justice.digital.hmpps.dpsreconciliation.model.MatchType
import uk.gov.justice.digital.hmpps.dpsreconciliation.repository.BOOKING_MOVED_EVENT
import java.time.LocalDateTime

class BookingMovedIntTest : IntegrationTestBase() {

  @Test
  fun `will match up booking moved events, domain first`() {
    val movedFromNomsNumber = "A7089FD"
    val movedToNomsNumber = "A7089FE"

    sendMessage(validDomainBookingMovedMessage(movedFromNomsNumber, movedToNomsNumber))

    await untilAsserted {
      verify(telemetryClient).trackEvent(
        eq("booking-moved-domain-event"),
        anyMap(),
        isNull(),
      )
    }

    sendMessage(validOffenderBookingMovedMessage(movedFromNomsNumber, movedToNomsNumber))

    await untilAsserted {
      verify(telemetryClient).trackEvent(
        eq("booking-moved-offender-event"),
        anyMap(),
        isNull(),
      )
    }

    val actual = repository.findByNomsNumberAndMatchTypeAndDomainTimeAfterAndMatched(
      movedToNomsNumber,
      MatchType.RECEIVED,
      LocalDateTime.parse("2025-05-01T10:00:00"),
      true,
    )
    assertThat(
      actual,
    ).extracting(
      "matchType",
      "nomsNumber",
      "relatedNomsNumber",
      "offenderReason",
      "offenderBookingId",
      "domainReason",
      "domainTime",
      "matched",
    )
      .containsExactly(
        Tuple(
          MatchType.RECEIVED,
          movedToNomsNumber,
          movedFromNomsNumber,
          BOOKING_MOVED_EVENT,
          3048348L,
          BOOKING_MOVED_EVENT,
          expectedDomainTime,
          true,
        ),
      )
  }

  @Test
  fun `will match up booking moved events, offender first`() {
    val movedFromNomsNumber = "A7089FD"
    val movedToNomsNumber = "A7089FE"

    sendMessage(validOffenderBookingMovedMessage(movedFromNomsNumber, movedToNomsNumber))

    await untilAsserted {
      verify(telemetryClient).trackEvent(
        eq("booking-moved-offender-event"),
        anyMap(),
        isNull(),
      )
    }

    sendMessage(validDomainBookingMovedMessage(movedFromNomsNumber, movedToNomsNumber))

    await untilAsserted {
      verify(telemetryClient).trackEvent(
        eq("booking-moved-domain-event"),
        anyMap(),
        isNull(),
      )
    }

    val actual = repository.findByNomsNumberAndMatchTypeAndDomainTimeAfterAndMatched(
      movedToNomsNumber,
      MatchType.RECEIVED,
      LocalDateTime.parse("2025-05-01T10:00:00"),
      true,
    )
    assertThat(
      actual,
    ).extracting(
      "matchType",
      "nomsNumber",
      "relatedNomsNumber",
      "offenderReason",
      "offenderBookingId",
      "domainReason",
      "domainTime",
      "matched",
    )
      .containsExactly(
        Tuple(
          MatchType.RECEIVED,
          movedToNomsNumber,
          movedFromNomsNumber,
          BOOKING_MOVED_EVENT,
          3048348L,
          BOOKING_MOVED_EVENT,
          expectedDomainTime,
          true,
        ),
      )
  }
}
