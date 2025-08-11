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
import java.time.LocalDateTime

@Entity
data class MatchingEventPair(
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  val id: Long = 0,

  @Enumerated(EnumType.STRING)
  val matchType: MatchType,

  val nomsNumber: String,

  var domainReason: String? = null,
  var domainTime: LocalDateTime? = null,

  var offenderBookingId: Long? = null,
  var offenderReason: String? = null,
  var offenderTime: LocalDateTime? = null,
  var previousOffenderReason: String? = null,
  var previousOffenderTime: LocalDateTime? = null,
  var previousOffenderDirection: String? = null,
//  var previousOffenderStatus: String? = null,
//  var previousOffenderBookingId: Long? = null,

  val createdDate: LocalDateTime = LocalDateTime.now(),
  var matched: Boolean = false,
  var comment: String? = null,

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

  override fun toString(): String = "MatchingEventPair(" +
    "id=$id, " +
    "nomsNumber=$nomsNumber, " +
    "matchType=$matchType, " +
    "createdDate=$createdDate, " +
    (if (domainTime == null) "" else "domainTime=$domainTime, ") +
    (if (domainReason == null) "" else "domainReason=$domainReason, ") +
    (if (offenderTime == null) "" else "offenderTime=$offenderTime, ") +
    (if (offenderReason == null) "" else "offenderReason=$offenderReason, ") +
    (if (offenderBookingId == null) "" else "offenderBookingId=$offenderBookingId, ") +
    (if (previousOffenderDirection == null) "" else "previousOffenderDirection=$previousOffenderDirection, ") +
    (if (previousOffenderTime == null) "" else "previousOffenderTime=$previousOffenderTime, ") +
    (if (previousOffenderReason == null) "" else "previousOffenderReason=$previousOffenderReason, ") +
    "matched=$matched" +
    ")"
}

enum class MatchType {
  RECEIVED,
  RELEASED,
}
