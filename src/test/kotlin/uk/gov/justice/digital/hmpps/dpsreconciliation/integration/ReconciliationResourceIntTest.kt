package uk.gov.justice.digital.hmpps.dpsreconciliation.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.check
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.verify
import uk.gov.justice.digital.hmpps.dpsreconciliation.model.MatchType
import uk.gov.justice.digital.hmpps.dpsreconciliation.model.MatchingEventPair
import java.time.LocalDateTime
import kotlin.jvm.optionals.getOrNull

class ReconciliationResourceIntTest : IntegrationTestBase() {
  @Test
  fun `access forbidden when no authority`() {
    webTestClient.get().uri("/reconciliation/housekeeping")
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `access forbidden when no role`() {
    webTestClient.get().uri("/reconciliation/housekeeping")
      .headers(setAuthorisation(roles = listOf()))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `access forbidden with wrong role`() {
    webTestClient.get().uri("/reconciliation/housekeeping")
      .headers(setAuthorisation(roles = listOf("ROLE_BANANAS")))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `No non-matches found`() {
    assertThat(
      webTestClient.get()
        .uri("/reconciliation/housekeeping")
        .headers(setAuthorisation(roles = listOf("ROLE_DPS_RECONCILIATION__RW")))
        .exchange()
        .expectStatus().isOk
        .expectBody(String::class.java)
        .returnResult()
        .responseBody,
    ).isEqualTo("Found 0 non-matching events")
  }

  @Test
  fun `2 non-matches found`() {
    repository.save(
      MatchingEventPair(
        matchType = MatchType.RECEIVED,
        nomsNumber = "A1234AA",
        domainReason = "test1",
        createdDate = LocalDateTime.parse("2025-06-22T12:01:00"),
      ),
    )
    repository.save(
      MatchingEventPair(
        matchType = MatchType.RECEIVED,
        nomsNumber = "A1234AB",
        domainReason = "test2",
        createdDate = LocalDateTime.parse("2025-06-22T12:59:00"),
      ),
    )
    assertThat(
      webTestClient.get()
        .uri("/reconciliation/housekeeping?from=2025-06-22T12:00:00&to=2025-06-22T13:00:00")
        .headers(setAuthorisation(roles = listOf("ROLE_DPS_RECONCILIATION__RW")))
        .exchange()
        .expectStatus().isOk
        .expectBody(String::class.java)
        .returnResult()
        .responseBody,
    ).isEqualTo("Found 2 non-matching events")

    verify(telemetryClient).trackEvent(
      eq("non-match-event"),
      check {
        assertThat(it["count"]).isEqualTo("2")
        assertThat(it["A1234AA"]).contains("matched=false")
        assertThat(it["A1234AA"]).contains("domainReason=test1")
        assertThat(it["A1234AA"]).contains("nomsNumber=A1234AA")
        assertThat(it["A1234AB"]).contains("matched=false")
        assertThat(it["A1234AB"]).contains("domainReason=test2")
        assertThat(it["A1234AB"]).contains("nomsNumber=A1234AB")
      },
      isNull(),
    )
  }

  @Test
  fun `non-matches found with default time range`() {
    repository.save(
      MatchingEventPair(
        matchType = MatchType.RECEIVED,
        nomsNumber = "A1234AA",
        domainReason = "test1",
        createdDate = LocalDateTime.now().minusHours(3),
      ),
    )
    assertThat(
      webTestClient.get()
        .uri("/reconciliation/housekeeping")
        .headers(setAuthorisation(roles = listOf("ROLE_DPS_RECONCILIATION__RW")))
        .exchange()
        .expectStatus().isOk
        .expectBody(String::class.java)
        .returnResult()
        .responseBody,
    ).isEqualTo("Found 1 non-matching events")
  }

  @Test
  fun `a matchable pair found and matched`() {
    val other1 = repository.save(
      MatchingEventPair(
        matchType = MatchType.RELEASED,
        nomsNumber = "OTHER1",
        domainReason = "OTHER_REASON",
        domainTime = LocalDateTime.parse("2025-06-22T12:00:00"),
        createdDate = LocalDateTime.parse("2025-06-22T12:01:00"),
      ),
    )
    val other2 = repository.save(
      MatchingEventPair(
        matchType = MatchType.RELEASED,
        nomsNumber = "OTHER2",
        previousOffenderReason = "HP",
        domainTime = LocalDateTime.parse("2025-06-22T12:00:00"),
        createdDate = LocalDateTime.parse("2025-06-22T12:01:00"),
      ),
    )
    val domainEvent = repository.save(
      MatchingEventPair(
        matchType = MatchType.RELEASED,
        nomsNumber = "A1234AA",
        domainReason = "REMOVED_FROM_HOSPITAL",
        domainTime = LocalDateTime.parse("2025-06-22T12:00:00"),
        createdDate = LocalDateTime.parse("2025-06-22T12:01:00"),
      ),
    )
    val offenderEvent = repository.save(
      MatchingEventPair(
        matchType = MatchType.RELEASED,
        nomsNumber = "A1234AA",
        previousOffenderReason = "HP",
        createdDate = LocalDateTime.parse("2025-06-22T12:59:00"),
      ),
    )

    webTestClient.get()
      .uri("/reconciliation/housekeeping?from=2025-06-22T12:00:00&to=2025-06-22T13:00:00")
      .headers(setAuthorisation(roles = listOf("ROLE_DPS_RECONCILIATION__RW")))
      .exchange()
      .expectStatus().isOk

    assertThat(repository.findById(other1.id).get().matched).isFalse()
    assertThat(repository.findById(other2.id).get().matched).isFalse()

    with(repository.findById(offenderEvent.id).get()) {
      assertThat(this.matched).isTrue()
      assertThat(domainReason).isEqualTo(domainEvent.domainReason)
      assertThat(domainTime).isEqualTo(domainEvent.domainTime)
    }

    assertThat(repository.findById(domainEvent.id).getOrNull()).isNull()
  }

  @Test
  fun `an orphan RP release is found and matched`() {
    val domainEvent = repository.save(
      MatchingEventPair(
        matchType = MatchType.RELEASED,
        nomsNumber = "A1234AA",
        domainReason = "REMOVED_FROM_HOSPITAL",
        domainTime = LocalDateTime.parse("2025-06-22T12:00:00"),
        createdDate = LocalDateTime.parse("2025-06-22T12:01:00"),
      ),
    )

    webTestClient.get()
      .uri("/reconciliation/housekeeping?from=2025-06-22T12:00:00&to=2025-06-22T13:00:00")
      .headers(setAuthorisation(roles = listOf("ROLE_DPS_RECONCILIATION__RW")))
      .exchange()
      .expectStatus().isOk

    with(repository.findById(domainEvent.id).get()) {
      assertThat(this.matched).isTrue()
      assertThat(offenderReason).isEqualTo("RP-EVENT")
    }
  }

  @Test
  fun `Old matched rows are purged`() {
    val old = repository.save(
      MatchingEventPair(
        matchType = MatchType.RELEASED,
        nomsNumber = "TO-GO",
        createdDate = LocalDateTime.parse("2025-04-22T12:00:00"),
        matched = true,
      ),
    )

    webTestClient.get()
      .uri("/reconciliation/housekeeping")
      .headers(setAuthorisation(roles = listOf("ROLE_DPS_RECONCILIATION__RW")))
      .exchange()
      .expectStatus().isOk

    assertThat(repository.findById(old.id).getOrNull()).isNull()
  }
}
