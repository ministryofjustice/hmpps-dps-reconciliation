package uk.gov.justice.digital.hmpps.dpsreconciliation.repository

import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.dpsreconciliation.model.MatchType
import uk.gov.justice.digital.hmpps.dpsreconciliation.model.MatchingEventPair
import java.time.LocalDateTime

@Repository
interface MatchingEventPairRepository : CrudRepository<MatchingEventPair, Long> {
  fun findByNomsNumberAndMatchTypeAndDomainTimeAfterAndMatched(nomsNumber: String, matchType: MatchType, domainTime: LocalDateTime, matched: Boolean): List<MatchingEventPair>
  fun findByNomsNumberAndMatchTypeAndOffenderTimeAfterAndMatched(nomsNumber: String, matchType: MatchType, offenderTime: LocalDateTime, matched: Boolean): List<MatchingEventPair>
  fun findByNomsNumberAndMatchTypeAndCreatedDateAfterAndDomainTimeIsNullAndMatchedOrderByIdAsc(nomsNumber: String, matchType: MatchType, createdDate: LocalDateTime, matched: Boolean): List<MatchingEventPair>
  fun findByNomsNumberAndMatchTypeAndCreatedDateAfterAndOffenderTimeIsNullAndMatchedOrderByIdAsc(nomsNumber: String, matchType: MatchType, createdDate: LocalDateTime, matched: Boolean): List<MatchingEventPair>
  fun deleteByCreatedDateIsBeforeAndMatched(createdDate: LocalDateTime, matched: Boolean): Int
  fun findByCreatedDateIsBetweenAndMatched(startCreatedDate: LocalDateTime, endCreatedDate: LocalDateTime, matched: Boolean): List<MatchingEventPair>
}
