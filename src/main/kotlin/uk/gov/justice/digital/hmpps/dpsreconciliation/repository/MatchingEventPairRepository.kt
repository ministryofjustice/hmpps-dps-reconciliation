package uk.gov.justice.digital.hmpps.dpsreconciliation.repository

import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.dpsreconciliation.model.MatchingEventPair
import java.time.LocalDateTime

@Repository
interface MatchingEventPairRepository : CrudRepository<MatchingEventPair, Long> {
  fun findByNomsNumberAndDomainReceivedTimeAfterAndMatched(nomsNumber: String, domainReceivedTime: LocalDateTime, matched: Boolean): List<MatchingEventPair>
  fun findByNomsNumberAndOffenderReceivedTimeAfterAndMatched(nomsNumber: String, offenderReceivedTime: LocalDateTime, matched: Boolean): List<MatchingEventPair>
}
