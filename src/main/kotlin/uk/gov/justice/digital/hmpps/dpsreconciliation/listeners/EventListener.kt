package uk.gov.justice.digital.hmpps.dpsreconciliation.listeners

import io.awspring.cloud.sqs.annotation.SqsListener
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import uk.gov.justice.digital.hmpps.dpsreconciliation.services.ReceiveService

@Service
class EventListener(
  val objectMapper: ObjectMapper,
  val receiveService: ReceiveService,
) {
  private companion object {
    val log: Logger = LoggerFactory.getLogger(this::class.java)
  }

  @SqsListener("reconciliation", factory = "hmppsQueueContainerFactoryProxy")
  fun onMessage(message: String) {
    log.debug("Received event message {}", message)
    val sqsMessage: SQSMessage = objectMapper.readValue(message)
    when (sqsMessage.Type) {
      "Notification" -> {
        val eventType = sqsMessage.MessageAttributes!!.eventType.Value
        when (eventType) {
          "EXTERNAL_MOVEMENT_RECORD-INSERTED" -> null
          // Ignore - Not all movements (only is_hmps_booking(), i.e. active booking or TRN). In particular ADMs missing as they are initially OUT

          "EXTERNAL_MOVEMENT-CHANGED" -> receiveService.externalMovementHandler(sqsMessage.Message.fromJson())
          // Always fires but fiddly to tell insert from update

          "BOOKING_NUMBER-CHANGED" -> receiveService.offenderMergeHandler(sqsMessage.Message.fromJson())
          // matches with a receive event

          "prisoner-offender-search.prisoner.received" -> receiveService.prisonerDomainReceiveHandler(sqsMessage.Message.fromJson())
          "prisoner-offender-search.prisoner.released" -> receiveService.prisonerDomainReleaseHandler(sqsMessage.Message.fromJson())

          "restricted-patients.patient.removed" -> receiveService.restrictedPatientRemovedHandler(sqsMessage.Message.fromJson())
          // matches with an REL external movement

          else -> log.info("Received a message I wasn't expecting {}", eventType)
        }
      }

      else -> log.info("Received a message I didnt recognise: {}", sqsMessage)
    }
  }

  private inline fun <reified T> String.fromJson(): T = objectMapper.readValue(this)
}
