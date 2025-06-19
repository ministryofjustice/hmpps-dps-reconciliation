package uk.gov.justice.digital.hmpps.dpsreconciliation.listeners

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.groups.Tuple
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.validDomainReceiveMessage
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.validDomainReleaseMessage
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.validOffenderAdmissionMessage
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.validOffenderMergeMessage
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.validOffenderReleaseMessage
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.wiremock.PrisonApiExtension.Companion.prisonApi
import uk.gov.justice.digital.hmpps.dpsreconciliation.model.MatchType
import uk.gov.justice.digital.hmpps.dpsreconciliation.services.ExternalPrisonerMovementMessage
import uk.gov.justice.digital.hmpps.dpsreconciliation.services.ReceiveService
import java.time.LocalDateTime

class OffenderEventListenerIntTest : IntegrationTestBase() {

  @MockitoSpyBean
  lateinit var receiveServiceSpyBean: ReceiveService

  @Nested
  inner class Received {
    @Test
    fun `will process an offender event message`() {
      val bookingId = 12345L
      val offenderNo = "A1234AA"

      prisonApi.stubGetMovementsForBooking(
        12345L,
        """
  [ 
    {
      "sequence": 1,
      "fromAgency": "xxxxxx",
      "toAgency": "xxxxxx",
      "movementType": "ADM",
      "directionCode": "IN",
      "movementDateTime": "2025-05-26T12:13:14",
      "movementReasonCode": "LC",
      "createdDateTime":  "2025-05-26T12:13:15"
    },
    {
      "sequence": 2,
      "fromAgency": "xxxxxx",
      "toAgency": "xxxxxx",
      "movementType": "REL",
      "directionCode": "OUT",
      "movementDateTime": "2025-05-30T15:13:15",
      "movementReasonCode": "LC",
      "createdDateTime":  "2025-05-26T12:13:15"
    }
  ]
        """.trimIndent(),
      )

      awsSqsReconciliationClient.sendMessage(
        SendMessageRequest.builder().queueUrl(reconciliationUrl)
          .messageBody(validOffenderAdmissionMessage(offenderNo, bookingId)).build(),
      )

      await untilAsserted {
        verify(telemetryClient).trackEvent(
          eq("offender-event"),
          anyMap(),
          isNull(),
        )
      }
      verify(receiveServiceSpyBean).externalMovementHandler(
        eq(
          ExternalPrisonerMovementMessage(
            bookingId = bookingId,
            eventType = "EXTERNAL_MOVEMENT-CHANGED",
            eventDatetime = LocalDateTime.parse("2025-05-13T15:38:47"),
            offenderIdDisplay = offenderNo,
            nomisEventType = "EXTERNAL_MOVEMENT-CHANGED",
            movementSeq = 1,
            movementDateTime = LocalDateTime.parse("2025-05-13T15:38:30"),
            movementType = "ADM",
            movementReasonCode = "REASON",
            directionCode = "IN",
            escortCode = null,
            fromAgencyLocationId = "LDM023",
            toAgencyLocationId = "CFI",
          ),
        ),
      )

      assertThat(
        repository.findByNomsNumberAndMatchTypeAndOffenderTimeAfterAndMatched(
          offenderNo,
          MatchType.RECEIVED,
          LocalDateTime.parse("2025-05-01T10:00:00"),
          false,
        ),
      ).extracting(
        "matchType",
        "nomsNumber",
        "offenderBookingId",
        "offenderReason",
        "offenderTime",
        "matched",
      )
        .containsExactly(
          Tuple(
            MatchType.RECEIVED,
            offenderNo,
            bookingId,
            "REASON",
            LocalDateTime.parse("2025-05-13T15:38:30"),
            false,
          ),
        )
    }

    @Test
    fun `will not process an ADM event where there is a previous movement`() {
      val bookingId = 12345L
      val offenderNo = "A1234AA"

      prisonApi.stubGetMovementsForBooking(
        12345L,
        """
  [ 
   {
      "sequence": 0,
      "movementType": "TRN",
      "directionCode": "OUT",
      "movementDateTime": "2025-05-26T12:13:13",
      "movementReasonCode": "LC",
      "createdDateTime":  "2025-05-26T12:13:15"
    },
    {
      "sequence": 1,
      "movementType": "ADM",
      "directionCode": "IN",
      "movementDateTime": "2025-05-26T12:13:14",
      "movementReasonCode": "LC",
      "createdDateTime":  "2025-05-26T12:13:15"
    }
  ]
        """.trimIndent(),
      )

      awsSqsReconciliationClient.sendMessage(
        SendMessageRequest.builder().queueUrl(reconciliationUrl)
          .messageBody(validOffenderAdmissionMessage(offenderNo, bookingId)).build(),
      )

      await untilAsserted {
        verify(telemetryClient).trackEvent(
          eq("offender-event"),
          anyMap(),
          isNull(),
        )
      }

      assertThat(
        repository.findByNomsNumberAndMatchTypeAndOffenderTimeAfterAndMatched(
          offenderNo,
          MatchType.RECEIVED,
          LocalDateTime.parse("2025-05-01T10:00:00"),
          false,
        ),
      ).isEmpty()
    }

    @Test
    fun `will match a previous domain receive event message`() {
      val bookingId = 12345L
      val offenderNo = "A1234AA"

      prisonApi.stubGetMovementsForBooking(
        12345L,
        """
  [ 
    {
      "sequence": 1,
      "fromAgency": "xxxxxx",
      "toAgency": "xxxxxx",
      "movementType": "ADM",
      "directionCode": "IN",
      "movementDateTime": "2025-05-26T12:13:14",
      "movementReasonCode": "LC",
      "createdDateTime":  "2025-05-26T12:13:15"
    }
  ]
        """.trimIndent(),
      )

      awsSqsReconciliationClient.sendMessage(
        SendMessageRequest.builder().queueUrl(reconciliationUrl)
          .messageBody(validDomainReceiveMessage(offenderNo)).build(),
      )
      await untilAsserted {
        verify(telemetryClient).trackEvent(eq("domain-event"), anyMap(), isNull())
      }
      reset(telemetryClient)

      awsSqsReconciliationClient.sendMessage(
        SendMessageRequest.builder().queueUrl(reconciliationUrl)
          .messageBody(validOffenderAdmissionMessage(offenderNo, bookingId)).build(),
      )
      await untilAsserted {
        verify(telemetryClient).trackEvent(eq("offender-event"), anyMap(), isNull())
      }

      assertThat(
        repository.findByNomsNumberAndMatchTypeAndOffenderTimeAfterAndMatched(
          offenderNo,
          MatchType.RECEIVED,
          LocalDateTime.parse("2025-05-01T10:00:00"),
          true,
        ),
      ).extracting(
        "matchType",
        "nomsNumber",
        "domainReason",
        "domainTime",
        "offenderBookingId",
        "offenderReason",
        "offenderTime",
        "matched",
      )
        .containsExactly(
          Tuple(
            MatchType.RECEIVED,
            offenderNo,
            "NEW_ADMISSION",
            LocalDateTime.parse("2025-05-13T15:38:48"),
            bookingId,
            "REASON",
            LocalDateTime.parse("2025-05-13T15:38:30"),
            true,
          ),
        )
    }
  }

  @Nested
  inner class Released {

    @Test
    fun `will process an offender event message`() {
      val bookingId = 12345L
      val offenderNo = "A1234AA"

      prisonApi.stubGetMovementsForBooking(
        12345L,
        """
  [ 
    {
      "sequence": 1,
      "fromAgency": "xxxxxx",
      "toAgency": "xxxxxx",
      "movementType": "REL",
      "directionCode": "OUT",
      "movementDateTime": "2025-05-26T12:13:14",
      "movementReasonCode": "LC",
      "createdDateTime":  "2025-05-26T12:13:15"
    },
    {
      "sequence": 2,
      "fromAgency": "xxxxxx",
      "toAgency": "xxxxxx",
      "movementType": "ADM",
      "directionCode": "IN",
      "movementDateTime": "2025-05-30T15:13:15",
      "movementReasonCode": "LC",
      "createdDateTime":  "2025-05-26T12:13:15"
    }
  ]
        """.trimIndent(),
      )

      awsSqsReconciliationClient.sendMessage(
        SendMessageRequest.builder().queueUrl(reconciliationUrl)
          .messageBody(validOffenderReleaseMessage(offenderNo, bookingId)).build(),
      )

      await untilAsserted {
        verify(telemetryClient).trackEvent(
          eq("offender-event"),
          anyMap(),
          isNull(),
        )
      }
      verify(receiveServiceSpyBean).externalMovementHandler(
        eq(
          ExternalPrisonerMovementMessage(
            bookingId = bookingId,
            eventType = "EXTERNAL_MOVEMENT-CHANGED",
            eventDatetime = LocalDateTime.parse("2025-05-13T15:38:47"),
            offenderIdDisplay = offenderNo,
            nomisEventType = "EXTERNAL_MOVEMENT-CHANGED",
            movementSeq = 1,
            movementDateTime = LocalDateTime.parse("2025-05-13T15:38:30"),
            movementType = "REL",
            movementReasonCode = "RELEASED",
            directionCode = "OUT",
            escortCode = null,
            fromAgencyLocationId = "CFI",
            toAgencyLocationId = "OUT",
          ),
        ),
      )

      assertThat(
        repository.findByNomsNumberAndMatchTypeAndOffenderTimeAfterAndMatched(
          offenderNo,
          MatchType.RELEASED,
          LocalDateTime.parse("2025-05-01T10:00:00"),
          false,
        ),
      ).extracting(
        "matchType",
        "nomsNumber",
        "offenderBookingId",
        "offenderReason",
        "offenderTime",
        "matched",
      )
        .containsExactly(
          Tuple(
            MatchType.RELEASED,
            offenderNo,
            bookingId,
            "RELEASED",
            LocalDateTime.parse("2025-05-13T15:38:30"),
            false,
          ),
        )
    }

    @Test
    fun `will match a previous domain release event message`() {
      val bookingId = 12345L
      val offenderNo = "A1234AA"

      prisonApi.stubGetMovementsForBooking(
        12345L,
        """
  [ 
    {
      "sequence": 1,
      "fromAgency": "xxxxxx",
      "toAgency": "xxxxxx",
      "movementType": "REL",
      "directionCode": "OUT",
      "movementDateTime": "2025-05-26T12:13:14",
      "movementReasonCode": "LC",
      "createdDateTime":  "2025-05-26T12:13:15"
    }
  ]
        """.trimIndent(),
      )

      awsSqsReconciliationClient.sendMessage(
        SendMessageRequest.builder().queueUrl(reconciliationUrl)
          .messageBody(validDomainReleaseMessage(offenderNo)).build(),
      )
      await untilAsserted {
        verify(telemetryClient).trackEvent(eq("domain-event"), anyMap(), isNull())
      }
      reset(telemetryClient)

      awsSqsReconciliationClient.sendMessage(
        SendMessageRequest.builder().queueUrl(reconciliationUrl)
          .messageBody(validOffenderReleaseMessage(offenderNo, bookingId)).build(),
      )
      await untilAsserted {
        verify(telemetryClient).trackEvent(eq("offender-event"), anyMap(), isNull())
      }

      assertThat(
        repository.findByNomsNumberAndMatchTypeAndOffenderTimeAfterAndMatched(
          offenderNo,
          MatchType.RELEASED,
          LocalDateTime.parse("2025-05-01T10:00:00"),
          true,
        ),
      ).extracting(
        "matchType",
        "nomsNumber",
        "domainReason",
        "domainTime",
        "offenderBookingId",
        "offenderReason",
        "offenderTime",
        "matched",
      )
        .containsExactly(
          Tuple(
            MatchType.RELEASED,
            offenderNo,
            "RELEASED",
            LocalDateTime.parse("2025-05-13T15:38:48"),
            bookingId,
            "RELEASED",
            LocalDateTime.parse("2025-05-13T15:38:30"),
            true,
          ),
        )
    }
  }

  @Nested
  inner class Merge {

    @Test
    fun `will process a merge event message`() {
      val bookingId = 12345L
      val offenderNo = "A1234AA"

      awsSqsReconciliationClient.sendMessage(
        SendMessageRequest.builder().queueUrl(reconciliationUrl)
          .messageBody(validOffenderMergeMessage(offenderNo, bookingId)).build(),
      )

      await untilAsserted {
        verify(telemetryClient).trackEvent(
          eq("merge-event"),
          anyMap(),
          isNull(),
        )
      }

      assertThat(
        repository.findByNomsNumberAndMatchTypeAndOffenderTimeAfterAndMatched(
          offenderNo,
          MatchType.RECEIVED,
          LocalDateTime.parse("2025-05-01T10:00:00"),
          false,
        ),
      ).extracting(
        "matchType",
        "nomsNumber",
        "offenderBookingId",
        "offenderReason",
        "offenderTime",
        "matched",
      )
        .containsExactly(
          Tuple(
            MatchType.RECEIVED,
            offenderNo,
            bookingId,
            "MERGE-EVENT",
            LocalDateTime.parse("2025-05-13T15:38:56"),
            false,
          ),
        )
    }

    @Test
    fun `will match a previous domain POST_MERGE_ADMISSION event message`() {
      val bookingId = 12345L
      val offenderNo = "A1234AA"

      awsSqsReconciliationClient.sendMessage(
        SendMessageRequest.builder().queueUrl(reconciliationUrl)
          .messageBody(validDomainReceiveMessage(offenderNo, "POST_MERGE_ADMISSION")).build(),
      )
      await untilAsserted {
        verify(telemetryClient).trackEvent(eq("domain-event"), anyMap(), isNull())
      }
      reset(telemetryClient)

      awsSqsReconciliationClient.sendMessage(
        SendMessageRequest.builder().queueUrl(reconciliationUrl)
          .messageBody(validOffenderMergeMessage(offenderNo, bookingId)).build(),
      )
      await untilAsserted {
        verify(telemetryClient).trackEvent(eq("merge-event"), anyMap(), isNull())
      }

      assertThat(
        repository.findByNomsNumberAndMatchTypeAndOffenderTimeAfterAndMatched(
          offenderNo,
          MatchType.RECEIVED,
          LocalDateTime.parse("2025-05-01T10:00:00"),
          true,
        ),
      ).extracting(
        "matchType",
        "nomsNumber",
        "domainReason",
        "domainTime",
        "offenderBookingId",
        "offenderReason",
        "offenderTime",
        "matched",
      )
        .containsExactly(
          Tuple(
            MatchType.RECEIVED,
            offenderNo,
            "POST_MERGE_ADMISSION",
            LocalDateTime.parse("2025-05-13T15:38:48"),
            bookingId,
            "MERGE-EVENT",
            LocalDateTime.parse("2025-05-13T15:38:56"),
            true,
          ),
        )
    }
  }
}
