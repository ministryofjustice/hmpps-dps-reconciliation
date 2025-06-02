package uk.gov.justice.digital.hmpps.dpsreconciliation.model

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import org.hibernate.Hibernate
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Persistable
import uk.gov.justice.digital.hmpps.dpsreconciliation.services.PrisonerReceiveReason
import java.time.LocalDateTime

@Entity
data class MatchingEventPair(
  @Id
  // @SequenceGenerator(name = "matching_event_pair_seq", sequenceName = "matching_event_pair_seq", allocationSize = 1)
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  val id: Long = 0,

  @Enumerated(EnumType.STRING)
  val matchType: MatchType,

  val nomsNumber: String,

  @Enumerated(EnumType.STRING)
  var domainReceiveReason: PrisonerReceiveReason? = null,
  var domainReceivedTime: LocalDateTime? = null,

  var offenderBookingId: Long? = null,
  var offenderReasonCode: String? = null,
  var offenderReceivedTime: LocalDateTime? = null,
  var previousOffenderReasonCode: String? = null,
  var previousOffenderReceivedTime: LocalDateTime? = null,
  var previousOffenderDirection: String? = null,
//  var previousOffenderStatus: String? = null,
//  var previousOffenderBookingId: Long? = null,

  val createdDate: LocalDateTime = LocalDateTime.now(),
  var matched: Boolean = false,

  @Transient
  @Value("false")
  @JsonIgnore
  val new: Boolean = true,
) : Persistable<Long> {

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
    other as MatchingEventPair
    return id == other.id
  }

  override fun hashCode(): Int = id.hashCode()

  override fun getId(): Long = id
  override fun isNew(): Boolean = new
}

enum class MatchType {
  RECEIVED,
  RELEASED,
}
