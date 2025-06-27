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

class ReconciliationResourceIntTest : IntegrationTestBase() {
  @Test
  fun `access forbidden when no authority`() {
    webTestClient.get().uri("/reconciliation/detect")
      .exchange()
      .expectStatus().isUnauthorized
  }

  @Test
  fun `access forbidden when no role`() {
    webTestClient.get().uri("/reconciliation/detect")
      .headers(setAuthorisation(roles = listOf()))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `access forbidden with wrong role`() {
    webTestClient.get().uri("/reconciliation/detect")
      .headers(setAuthorisation(roles = listOf("ROLE_BANANAS")))
      .exchange()
      .expectStatus().isForbidden
  }

  @Test
  fun `No non-matches found`() {
    assertThat(
      webTestClient.get()
        .uri("/reconciliation/detect")
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
        .uri("/reconciliation/detect?from=2025-06-22T12:00:00&to=2025-06-22T13:00:00")
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
        .uri("/reconciliation/detect")
        .headers(setAuthorisation(roles = listOf("ROLE_DPS_RECONCILIATION__RW")))
        .exchange()
        .expectStatus().isOk
        .expectBody(String::class.java)
        .returnResult()
        .responseBody,
    ).isEqualTo("Found 1 non-matching events")
  }
}
