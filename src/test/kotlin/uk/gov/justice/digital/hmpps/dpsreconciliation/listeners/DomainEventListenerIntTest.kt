package uk.gov.justice.digital.hmpps.dpsreconciliation.listeners

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.groups.Tuple
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.kotlin.check
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.validDomainRPRemovedMessage
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.validDomainReceiveMessage
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.validDomainReleaseMessage
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.validOffenderAdmissionMessage
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.validOffenderReleaseMessage
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.wiremock.PrisonApiExtension.Companion.prisonApi
import uk.gov.justice.digital.hmpps.dpsreconciliation.model.MatchType
import uk.gov.justice.digital.hmpps.dpsreconciliation.services.PrisonerReceiveDomainEvent
import uk.gov.justice.digital.hmpps.dpsreconciliation.services.PrisonerReceiveReason
import uk.gov.justice.digital.hmpps.dpsreconciliation.services.ReceivePrisonerAdditionalInformationEvent
import uk.gov.justice.digital.hmpps.dpsreconciliation.services.ReceiveService
import java.time.LocalDateTime
import java.time.OffsetDateTime

class DomainEventListenerIntTest : IntegrationTestBase() {

  @MockitoSpyBean
  lateinit var receiveServiceSpyBean: ReceiveService

  @Nested
  inner class Received {
    @Test
    fun `will process a prisoner received event`() {
      val prisonerNumber = "A7089FD"

      awsSqsReconciliationClient.sendMessage(
        SendMessageRequest.builder().queueUrl(reconciliationUrl)
          .messageBody(validDomainReceiveMessage(prisonerNumber)).build(),
      )

      await untilAsserted {
        verify(telemetryClient).trackEvent(
          eq("domain-event"),
          anyMap(),
          isNull(),
        )
      }

      verify(receiveServiceSpyBean).prisonerDomainReceiveHandler(
        eq(
          PrisonerReceiveDomainEvent(
            additionalInformation = ReceivePrisonerAdditionalInformationEvent(
              nomsNumber = prisonerNumber,
              reason = PrisonerReceiveReason.NEW_ADMISSION,
              prisonId = "CFI",
            ),
            occurredAt = OffsetDateTime.parse("2025-05-13T15:38:48.0Z"),
          ),
        ),
      )

      val actual = repository.findByNomsNumberAndMatchTypeAndDomainTimeAfterAndMatched(
        prisonerNumber,
        MatchType.RECEIVED,
        LocalDateTime.parse("2025-05-01T10:00:00"),
        false,
      )
      assertThat(
        actual,
      ).extracting("matchType", "nomsNumber", "domainReason", "domainTime", "matched")
        .containsExactly(
          Tuple(
            MatchType.RECEIVED,
            prisonerNumber,
            "NEW_ADMISSION",
            LocalDateTime.parse("2025-05-13T15:38:48"),
            false,
          ),
        )
    }

    @Test
    fun `will match a previous external movement event`() {
      val prisonerNumber = "A7089FD"

      prisonApi.stubGetMovementsForBooking(
        101,
        """
          [
       {
      "sequence": 1,
      "movementType": "ADM",
      "directionCode": "IN",
      "movementDateTime": "2025-05-26T12:13:14",
      "movementReasonCode": "LC",
      "createdDateTime":  "2025-05-26T12:13:15"
       }]
        """.trimMargin(),
      )

      awsSqsReconciliationClient.sendMessage(
        SendMessageRequest.builder().queueUrl(reconciliationUrl)
          .messageBody(validOffenderAdmissionMessage(prisonerNumber, 101)).build(),
      )

      await untilAsserted {
        verify(telemetryClient).trackEvent(eq("offender-event"), anyMap(), isNull())
      }
      reset(telemetryClient)

      awsSqsReconciliationClient.sendMessage(
        SendMessageRequest.builder().queueUrl(reconciliationUrl)
          .messageBody(validDomainReceiveMessage(prisonerNumber)).build(),
      )

      await untilAsserted {
        verify(telemetryClient).trackEvent(eq("domain-event"), anyMap(), isNull())
      }

      assertThat(
        repository.findByNomsNumberAndMatchTypeAndDomainTimeAfterAndMatched(
          prisonerNumber,
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
            prisonerNumber,
            "NEW_ADMISSION",
            LocalDateTime.parse("2025-05-13T15:38:48"),
            101L,
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
    fun `will match a previous external movement event`() {
      val prisonerNumber = "A7089FD"

      prisonApi.stubGetMovementsForBooking(
        101,
        """
          [
       {
      "sequence": 1,
      "movementType": "ADM",
      "directionCode": "IN",
      "movementDateTime": "2025-05-26T12:13:14",
      "movementReasonCode": "LC",
      "createdDateTime":  "2025-05-26T12:13:15"
       }]
        """.trimMargin(),
      )

      awsSqsReconciliationClient.sendMessage(
        SendMessageRequest.builder().queueUrl(reconciliationUrl)
          .messageBody(validOffenderReleaseMessage(prisonerNumber, 101)).build(),
      )

      await untilAsserted {
        verify(telemetryClient).trackEvent(eq("offender-event"), anyMap(), isNull())
      }
      reset(telemetryClient)

      awsSqsReconciliationClient.sendMessage(
        SendMessageRequest.builder().queueUrl(reconciliationUrl)
          .messageBody(validDomainReleaseMessage(prisonerNumber)).build(),
      )

      await untilAsserted {
        verify(telemetryClient).trackEvent(
          eq("domain-event"),
          check {
            assertThat(it["type"]).isEqualTo("RELEASED")
          },
          isNull(),
        )
      }

      assertThat(
        repository.findByNomsNumberAndMatchTypeAndDomainTimeAfterAndMatched(
          prisonerNumber,
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
            prisonerNumber,
            "RELEASED",
            LocalDateTime.parse("2025-05-13T15:38:48"),
            101L,
            "RELEASED",
            LocalDateTime.parse("2025-05-13T15:38:30"),
            true,
          ),
        )
    }
  }

  @Nested
  inner class RPRemoved {
    @Test
    fun `will match a previous release external movement event`() {
      val prisonerNumber = "A7089FD"

      prisonApi.stubGetMovementsForBooking(
        101,
        """
      [{
        "sequence": 1,
        "movementType": "REL",
        "directionCode": "OUT",
        "movementDateTime": "2025-05-26T12:13:14",
        "movementReasonCode": "HP",
        "createdDateTime":  "2025-05-26T12:13:15"
       },
       {
        "sequence": 2,
        "movementType": "REL",
        "directionCode": "OUT",
        "movementDateTime": "2025-05-26T12:13:14",
        "movementReasonCode": "CP",
        "createdDateTime":  "2025-05-26T12:13:16"
       }]
        """.trimMargin(),
      )

      awsSqsReconciliationClient.sendMessage(
        SendMessageRequest.builder().queueUrl(reconciliationUrl)
          .messageBody(validOffenderReleaseMessage(prisonerNumber, 101, 2)).build(),
      )

      await untilAsserted {
        verify(telemetryClient).trackEvent(eq("offender-event"), anyMap(), isNull())
      }
      reset(telemetryClient)

      awsSqsReconciliationClient.sendMessage(
        SendMessageRequest.builder().queueUrl(reconciliationUrl)
          .messageBody(validDomainRPRemovedMessage(prisonerNumber)).build(),
      )

      await untilAsserted {
        verify(telemetryClient).trackEvent(
          eq("restricted-patient-event"),
          check {
            assertThat(it["type"]).isEqualTo("RELEASED")
          },
          isNull(),
        )
      }

      assertThat(
        repository.findByNomsNumberAndMatchTypeAndDomainTimeAfterAndMatched(
          prisonerNumber,
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
            prisonerNumber,
            "REMOVED_FROM_HOSPITAL",
            LocalDateTime.parse("2025-05-13T15:38:48"),
            101L,
            "RELEASED",
            LocalDateTime.parse("2025-05-13T15:38:30"),
            true,
          ),
        )
    }
  }
}
