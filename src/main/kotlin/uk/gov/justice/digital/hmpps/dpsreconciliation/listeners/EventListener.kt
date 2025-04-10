package uk.gov.justice.digital.hmpps.dpsreconciliation.listeners

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.awspring.cloud.sqs.annotation.SqsListener
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture

@Service
class EventListener(
  val objectMapper: ObjectMapper,
  // telemetryClient: TelemetryClient,
) {

  private companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  @SqsListener("reconciliation", factory = "hmppsQueueContainerFactoryProxy")
  fun onMessage(message: String): CompletableFuture<Void?> {
    log.debug("Received offender event message {}", message)
    val sqsMessage: SQSMessage = objectMapper.readValue(message)
    return asCompletableFuture {
      when (sqsMessage.Type) {
        "Notification" -> {
          val eventType = sqsMessage.MessageAttributes!!.eventType.Value
          when (eventType) {
            "EXTERNAL_MOVEMENT_RECORD-INSERTED" -> TODO()

            "prisoner-offender-search.prisoner.received" -> TODO()
            "prisoner-offender-search.prisoner.released" -> TODO() //  service.doCheckEtc(sqsMessage.Message.fromJson())
            else -> log.info("Received a message I wasn't expecting {}", eventType)
          }
        }

        else -> log.info("Received a message I didnt recognise: {}", sqsMessage)
      }
    }
  }

  private inline fun <reified T> String.fromJson(): T = objectMapper.readValue(this)
}
