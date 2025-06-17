package uk.gov.justice.digital.hmpps.dpsreconciliation.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import uk.gov.justice.hmpps.kotlin.auth.dsl.ResourceServerConfigurationCustomizer

@Configuration
class ResourceServerConfiguration {
  @Bean
  fun resourceServerCustomizer() = ResourceServerConfigurationCustomizer {
    unauthorizedRequestPaths {
      addPaths = setOf(
        // These endpoints are secured in the ingress rather than the app so that they can be called from within the namespace without requiring authentication
        "/queue-admin/retry-all-dlqs",
      )
    }
  }
}
