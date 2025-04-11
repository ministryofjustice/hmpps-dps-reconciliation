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
import uk.gov.justice.digital.hmpps.dpsreconciliation.services.PrisonerReceiveDomainEvent
import uk.gov.justice.digital.hmpps.dpsreconciliation.services.PrisonerReceiveReason
import uk.gov.justice.digital.hmpps.dpsreconciliation.services.ReceivePrisonerAdditionalInformationEvent
import uk.gov.justice.digital.hmpps.dpsreconciliation.services.ReceiveService

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
        eq(emptyMap()),
        isNull(),
      )
    }
    verify(receiveServiceSpyBean, times(1)).prisonerReceived(
      eq(
        PrisonerReceiveDomainEvent(
          additionalInformation = ReceivePrisonerAdditionalInformationEvent(
            nomsNumber = prisonerNumber,
            reason = PrisonerReceiveReason.NEW_ADMISSION,
          ),
        ),
      ),
    )
  }
}

private fun validDomainMessage(prisonerNumber: String, eventType: String) =
  """
    {
      "Type": "Notification",
      "MessageId": "20e13002-d1be-56e7-be8c-66cdd7e23341",
      "Message": "{\"eventType\":\"$eventType\", \"description\": \"some desc\", \"additionalInformation\": {\"nomsNumber\":\"$prisonerNumber\", \"reason\":\"NEW_ADMISSION\"}}",
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
