package uk.gov.justice.digital.hmpps.dpsreconciliation.listeners

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.groups.Tuple
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.validDomainMessage
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.validOffenderMessage
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

  @Test
  fun `will process a prisoner received event`() = runTest {
    val prisonerNumber = "A7089FD"

    awsSqsReconciliationClient.sendMessage(
      SendMessageRequest.builder().queueUrl(reconciliationUrl)
        .messageBody(validDomainMessage(prisonerNumber, "prisoner-offender-search.prisoner.received")).build(),
    )

    await untilAsserted {
      verify(telemetryClient).trackEvent(
        eq("domain-event-received"),
        anyMap(),
        isNull(),
      )
    }

    verify(receiveServiceSpyBean).prisonerDomainHandler(
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

    val actual = repository.findByNomsNumberAndDomainReceivedTimeAfterAndMatched(
      prisonerNumber,
      LocalDateTime.parse("2025-05-01T10:00:00"),
      false,
    )
    assertThat(
      actual,
    ).extracting("matchType", "nomsNumber", "domainReceiveReason", "domainReceivedTime", "matched")
      .containsExactly(
        Tuple(
          MatchType.RECEIVED,
          prisonerNumber,
          PrisonerReceiveReason.NEW_ADMISSION,
          LocalDateTime.parse("2025-05-13T15:38:48"),
          false,
        ),
      )
  }

  @Test
  fun `will match a previous external movement event`() = runTest {
    val prisonerNumber = "A7089FD"

    awsSqsReconciliationClient.sendMessage(
      SendMessageRequest.builder().queueUrl(reconciliationUrl)
        .messageBody(validOffenderMessage(prisonerNumber, 101, "EXTERNAL_MOVEMENT_RECORD-INSERTED")).build(),
    )

    await untilAsserted {
      verify(telemetryClient).trackEvent(eq("offender-event-received"), anyMap(), isNull())
    }
    reset(telemetryClient)

    awsSqsReconciliationClient.sendMessage(
      SendMessageRequest.builder().queueUrl(reconciliationUrl)
        .messageBody(validDomainMessage(prisonerNumber, "prisoner-offender-search.prisoner.received")).build(),
    )

    await untilAsserted {
      verify(telemetryClient).trackEvent(eq("domain-event-received"), anyMap(), isNull())
    }

    assertThat(
      repository.findByNomsNumberAndDomainReceivedTimeAfterAndMatched(
        prisonerNumber,
        LocalDateTime.parse("2025-05-01T10:00:00"),
        true,
      ),
    ).extracting(
      "matchType",
      "nomsNumber",
      "domainReceiveReason",
      "domainReceivedTime",
      "offenderBookingId",
      "offenderReasonCode",
      "offenderReceivedTime",
      "matched",
    )
      .containsExactly(
        Tuple(
          MatchType.RECEIVED,
          prisonerNumber,
          PrisonerReceiveReason.NEW_ADMISSION,
          LocalDateTime.parse("2025-05-13T15:38:48"),
          101L,
          "REASON",
          LocalDateTime.parse("2025-05-13T15:38:30"),
          true,
        ),
      )
  }
}
