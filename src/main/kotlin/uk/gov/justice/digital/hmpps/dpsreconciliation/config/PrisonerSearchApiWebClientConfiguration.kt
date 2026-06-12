package uk.gov.justice.digital.hmpps.dpsreconciliation.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
import org.springframework.web.reactive.function.client.WebClient
import uk.gov.justice.hmpps.kotlin.auth.authorisedWebClient
import uk.gov.justice.hmpps.kotlin.auth.healthWebClient
import java.time.Duration

@Configuration
class PrisonerSearchApiWebClientConfiguration(
  @Value("\${prisoner-search-api.url}") val baseUri: String,
  @Value("\${api.health-timeout:2s}") val healthTimeout: Duration,
  @Value("\${api.timeout:20s}") val timeout: Duration,
) {
  @Bean
  fun prisonerSearchApiHealthWebClient(builder: WebClient.Builder): WebClient = builder
    .healthWebClient(baseUri, healthTimeout)

  @Bean
  fun prisonerSearchApiWebClient(
    authorizedClientManager: OAuth2AuthorizedClientManager,
    builder: WebClient.Builder,
  ): WebClient = builder.authorisedWebClient(
    authorizedClientManager,
    registrationId = "prisoner-search-api",
    url = baseUri,
    timeout,
  )
}
