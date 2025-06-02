@file:Suppress("PropertyName")

package uk.gov.justice.digital.hmpps.dpsreconciliation.listeners

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming

@JsonNaming(value = PropertyNamingStrategies.UpperCamelCaseStrategy::class)
@JsonInclude(NON_NULL)
data class SQSMessage(
  val Type: String,
  val Message: String,
  val MessageId: String? = null,
  val MessageAttributes: MessageAttributes? = null,
)

data class MessageAttributes(val eventType: EventType)
data class EventType(val Value: String, val Type: String)
