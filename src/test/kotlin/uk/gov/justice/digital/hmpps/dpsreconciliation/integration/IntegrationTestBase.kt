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
    Awaitility.setDefaultPollDelay(1, TimeUnit.MILLISECONDS)
    Awaitility.setDefaultPollInterval(10, TimeUnit.MILLISECONDS)
    reset(telemetryClient)
    reconciliationQueue.purgeAndWait()
    Awaitility.setDefaultPollInterval(50, TimeUnit.MILLISECONDS)
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

private fun HmppsQueue.purgeAndWait() {
  sqsClient.purgeQueue(queueUrl).get().also {
    await untilCallTo { sqsClient.countAllMessagesOnQueue(queueUrl).get() } matches { it == 0 }
  }
  sqsDlqClient?.run {
    purgeQueue(dlqUrl).get().also {
      await untilCallTo { this.countAllMessagesOnQueue(dlqUrl!!).get() } matches { it == 0 }
    }
  }
}
