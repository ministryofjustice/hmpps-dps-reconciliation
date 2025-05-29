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
import org.mockito.kotlin.verify
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.dpsreconciliation.model.MatchType
import uk.gov.justice.digital.hmpps.dpsreconciliation.services.ExternalPrisonerMovementMessage
import uk.gov.justice.digital.hmpps.dpsreconciliation.services.ReceiveService
import java.time.LocalDateTime

class OffenderEventListenerIntTest : IntegrationTestBase() {

  @MockitoSpyBean
  lateinit var receiveServiceSpyBean: ReceiveService

  @Test
  fun `will process an offender event message`() = runTest {
    val bookingId = 12345L
    val offenderNo = "A1234AA"

    awsSqsReconciliationClient.sendMessage(
      SendMessageRequest.builder().queueUrl(reconciliationUrl)
        .messageBody(validOffenderMessage(offenderNo, bookingId, "EXTERNAL_MOVEMENT_RECORD-INSERTED")).build(),
    )

    await untilAsserted {
      verify(telemetryClient).trackEvent(
        eq("offender-event-received"),
        anyMap(),
        isNull(),
      )
    }
    verify(receiveServiceSpyBean).externalMovementHandler(
      eq(
        ExternalPrisonerMovementMessage(
          bookingId = bookingId,
          eventType = "EXTERNAL_MOVEMENT_RECORD-INSERTED",
          eventDatetime = LocalDateTime.parse("2025-05-13T15:38:47"),
          offenderIdDisplay = offenderNo,
          nomisEventType = "M1_RESULT",
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
      repository.findByNomsNumberAndOffenderReceivedTimeAfterAndMatched(
        offenderNo,
        LocalDateTime.parse("2025-05-01T10:00:00"),
        false,
      ),
    ).extracting(
      "matchType",
      "nomsNumber",
      "offenderBookingId",
      "offenderReasonCode",
      "offenderReceivedTime",
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
}

private fun validOffenderMessage(offenderNo: String, bookingId: Long, eventType: String) = validMessage(
  eventType = eventType,
  message = """{\"eventType\":\"$eventType\",\"eventDatetime\":\"2025-05-13T15:38:47\",\"bookingId\":$bookingId,\"offenderIdDisplay\":\"$offenderNo\", \"nomisEventType\":\"M1_RESULT\",\"movementSeq\":1, \"movementDateTime\":\"2025-05-13T15:38:30\", \"movementType\":\"ADM\", \"movementReasonCode\": \"REASON\", \"directionCode\":\"IN\", \"fromAgencyLocationId\":\"LDM023\",\"toAgencyLocationId\":\"CFI\"}""",
)

private fun validMessage(eventType: String, message: String) =
  """
  {
    "Type": "Notification",
    "MessageId": "20e13002-d1be-56e7-be8c-66cdd7e23341",
    "Message": "$message",
    "MessageAttributes": {
      "eventType": {
        "Type": "String",
        "Value": "$eventType"
      },
      "id": {
        "Type": "String",
        "Value": "cb4645f2-d0c1-4677-806a-8036ed54bf69"
      }
    }
  }
  """.trimIndent()
