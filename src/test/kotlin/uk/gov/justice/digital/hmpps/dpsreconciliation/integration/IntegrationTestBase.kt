package uk.gov.justice.digital.hmpps.dpsreconciliation.integration

import com.microsoft.applicationinsights.TelemetryClient
import org.awaitility.Awaitility
import org.awaitility.kotlin.await
import org.awaitility.kotlin.matches
import org.awaitility.kotlin.untilCallTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.reset
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.http.HttpHeaders
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.reactive.server.WebTestClient
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.PurgeQueueRequest
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.LocalStackContainer.setLocalStackProperties
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.wiremock.HmppsAuthApiExtension
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.wiremock.HmppsAuthApiExtension.Companion.hmppsAuth
import uk.gov.justice.digital.hmpps.dpsreconciliation.repository.MatchingEventPairRepository
import uk.gov.justice.hmpps.sqs.HmppsQueue
import uk.gov.justice.hmpps.sqs.HmppsQueueService
import uk.gov.justice.hmpps.sqs.countAllMessagesOnQueue
import uk.gov.justice.hmpps.test.kotlin.auth.JwtAuthorisationHelper
import java.util.concurrent.TimeUnit

@ExtendWith(HmppsAuthApiExtension::class)
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("test")
abstract class IntegrationTestBase {

  @Autowired
  protected lateinit var repository: MatchingEventPairRepository

  @Autowired
  protected lateinit var webTestClient: WebTestClient

  @Autowired
  private lateinit var hmppsQueueService: HmppsQueueService

  @Autowired
  protected lateinit var jwtAuthHelper: JwtAuthorisationHelper

  internal val reconciliationQueue by lazy { hmppsQueueService.findByQueueId("reconciliation") as HmppsQueue }
  internal val reconciliationUrl by lazy { reconciliationQueue.queueUrl }
  internal val reconciliationDlqUrl by lazy { reconciliationQueue.dlqUrl as String }
  internal val awsSqsReconciliationClient by lazy { reconciliationQueue.sqsClient }
  internal val awsSqsReconciliationDlqClient by lazy { reconciliationQueue.sqsDlqClient as SqsAsyncClient }

  @MockitoSpyBean
  protected lateinit var telemetryClient: TelemetryClient

  @BeforeEach
  fun setUp() {
    reconciliationQueue.purge()
    Awaitility.setDefaultPollDelay(1, TimeUnit.MILLISECONDS)
    reset(telemetryClient)
    Awaitility.setDefaultPollInterval(50, TimeUnit.MILLISECONDS)
    repository.deleteAll()
    reconciliationQueue.wait()
  }

  internal fun setAuthorisation(
    username: String? = "AUTH_ADM",
    roles: List<String> = listOf(),
    scopes: List<String> = listOf("read"),
  ): (HttpHeaders) -> Unit = jwtAuthHelper.setAuthorisationHeader(username = username, scope = scopes, roles = roles)

  protected fun stubPingWithResponse(status: Int) {
    hmppsAuth.stubHealthPing(status)
  }

  companion object {
    private val localStackContainer = LocalStackContainer.instance

    @JvmStatic
    @DynamicPropertySource
    fun testcontainers(registry: DynamicPropertyRegistry) {
      localStackContainer?.also { setLocalStackProperties(it, registry) }
    }

    private val pgContainer = PostgresContainer.instance

    @JvmStatic
    @DynamicPropertySource
    fun properties(registry: DynamicPropertyRegistry) {
      println("URL IS ${pgContainer?.getJdbcUrl()}")
      pgContainer?.run {
        registry.add("spring.flyway.url", pgContainer::getJdbcUrl)
        registry.add("spring.flyway.user", pgContainer::getUsername)
        registry.add("spring.flyway.password", pgContainer::getPassword)
        registry.add("spring.datasource.url", pgContainer::getJdbcUrl)
        registry.add("spring.datasource.username", pgContainer::getUsername)
        registry.add("spring.datasource.password", pgContainer::getPassword)
      }
    }
  }
}

internal fun SqsAsyncClient.sendMessage(queueOffenderEventsUrl: String, message: String) = sendMessage(
  SendMessageRequest.builder().queueUrl(queueOffenderEventsUrl).messageBody(message).build(),
).get()

internal fun HmppsQueue.sendMessage(message: String) = this.sqsClient.sendMessage(this.queueUrl, message = message)
internal fun HmppsQueue.countMessagesOnQueue(message: String) = this.sqsClient.countAllMessagesOnQueue(this.queueUrl)

internal fun SqsAsyncClient.waitForMessageCountOnQueue(queueUrl: String, messageCount: Int) = await untilCallTo {
  countAllMessagesOnQueue(queueUrl).get()
} matches { it == messageCount }

internal fun String.purgeQueueRequest() = PurgeQueueRequest.builder().queueUrl(this).build()
private fun SqsAsyncClient.purgeQueue(queueUrl: String?) = purgeQueue(queueUrl?.purgeQueueRequest())

private fun HmppsQueue.purge() {
  sqsClient.purgeQueue(queueUrl).get()
  sqsDlqClient?.run { purgeQueue(dlqUrl).get() }
}

private fun HmppsQueue.wait() {
  await untilCallTo { sqsClient.countAllMessagesOnQueue(queueUrl).get() } matches { it == 0 }
  sqsDlqClient?.run {
    await untilCallTo { this.countAllMessagesOnQueue(dlqUrl!!).get() } matches { it == 0 }
  }
}

private fun HmppsQueue.purgeAndWait() {
  this.purge()
  this.wait()
}

internal fun validOffenderAdmissionMessage(offenderNo: String, bookingId: Long, eventType: String = "EXTERNAL_MOVEMENT-CHANGED") = validMessage(
  eventType = eventType,
  message = """{\"eventType\":\"$eventType\",\"eventDatetime\":\"2025-05-13T15:38:47\",\"bookingId\":$bookingId,\"offenderIdDisplay\":\"$offenderNo\", \"nomisEventType\":\"EXTERNAL_MOVEMENT-CHANGED\",\"movementSeq\":1, \"movementDateTime\":\"2025-05-13T15:38:30\", \"movementType\":\"ADM\", \"movementReasonCode\": \"REASON\", \"directionCode\":\"IN\", \"fromAgencyLocationId\":\"LDM023\",\"toAgencyLocationId\":\"CFI\"}""",
)

internal fun validOffenderReleaseMessage(offenderNo: String, bookingId: Long, eventType: String = "EXTERNAL_MOVEMENT-CHANGED") = validMessage(
  eventType = eventType,
  message = """{\"eventType\":\"$eventType\",\"eventDatetime\":\"2025-05-13T15:38:47\",\"bookingId\":$bookingId,\"offenderIdDisplay\":\"$offenderNo\", \"nomisEventType\":\"EXTERNAL_MOVEMENT-CHANGED\",\"movementSeq\":1, \"movementDateTime\":\"2025-05-13T15:38:30.0Z\", \"movementType\":\"REL\", \"movementReasonCode\": \"RELEASED\", \"directionCode\":\"OUT\", \"fromAgencyLocationId\":\"CFI\",\"toAgencyLocationId\":\"OUT\"}""",
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

internal fun validDomainReceiveMessage(prisonerNumber: String, eventType: String = "prisoner-offender-search.prisoner.received", reason: String = "NEW_ADMISSION") =
  """
    {
      "Type": "Notification",
      "MessageId": "20e13002-d1be-56e7-be8c-66cdd7e23341",
      "Message": "{\"eventType\":\"$eventType\", \"description\": \"some desc\",\"occurredAt\":\"2025-05-13T15:38:48.0Z\", \"additionalInformation\": {\"nomsNumber\":\"$prisonerNumber\", \"reason\":\"$reason\",\"prisonId\":\"CFI\"}}",
      "MessageAttributes": {
        "eventType": {
          "Type": "String",
          "Value": "$eventType"
        }
      }
    }
  """.trimIndent()

internal fun validDomainReleaseMessage(prisonerNumber: String, eventType: String = "prisoner-offender-search.prisoner.released", reason: String = "RELEASED") =
  """
    {
      "Type": "Notification",
      "MessageId": "20e13002-d1be-56e7-be8c-66cdd7e23341",
      "Message": "{\"eventType\":\"$eventType\", \"description\": \"some desc\", \"occurredAt\":\"2025-05-13T15:38:48.0Z\", \"additionalInformation\": {\"nomsNumber\":\"$prisonerNumber\", \"reason\":\"$reason\",\"prisonId\":\"CFI\"}}",
      "MessageAttributes": {
        "eventType": {
          "Type": "String",
          "Value": "$eventType"
        }
      }
    }
  """.trimIndent()
