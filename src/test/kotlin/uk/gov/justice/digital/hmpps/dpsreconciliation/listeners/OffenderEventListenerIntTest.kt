package uk.gov.justice.digital.hmpps.dpsreconciliation.listeners

import kotlinx.coroutines.test.runTest
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.dpsreconciliation.services.ExternalPrisonerMovementMessage
import uk.gov.justice.digital.hmpps.dpsreconciliation.services.ReceiveService

class OffenderEventListenerIntTest : IntegrationTestBase() {

  @MockitoSpyBean
  lateinit var receiveServiceSpyBean: ReceiveService

  @Test
  fun `will process an offender event message`() = runTest {
    val bookingId = 12345L

    awsSqsReconciliationClient.sendMessage(
      SendMessageRequest.builder().queueUrl(reconciliationUrl)
        .messageBody(validOffenderMessage(bookingId, "EXTERNAL_MOVEMENT_RECORD-INSERTED")).build(),
    )

    await untilAsserted {
      verify(telemetryClient).trackEvent(
        eq("offender-event-received"),
        eq(emptyMap()),
        isNull(),
      )
    }
    verify(receiveServiceSpyBean, times(1)).movementReceived(
      eq(
        ExternalPrisonerMovementMessage(bookingId = bookingId),
      ),
    )
  }
}

private fun validOffenderMessage(bookingId: Long, eventType: String) = validMessage(
  eventType = eventType,
  message = """{\"eventType\":\"$eventType\",\"eventDatetime\":\"2020-03-25T11:24:32.935401\",\"bookingId\":\"$bookingId\",\"nomisEventType\":\"S1_RESULT\"}""",
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
