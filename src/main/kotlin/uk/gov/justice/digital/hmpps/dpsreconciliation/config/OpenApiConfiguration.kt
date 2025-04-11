package uk.gov.justice.digital.hmpps.dpsreconciliation.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfiguration(buildProperties: BuildProperties) {
  private val version: String = buildProperties.version

  @Bean
  fun customOpenAPI(): OpenAPI = OpenAPI()
    .servers(
      listOf(
        Server().url("https://dps-reconciliation-dev.hmpps.service.justice.gov.uk").description("Development"),
        Server().url("https://dps-reconciliation-preprod.hmpps.service.justice.gov.uk").description("Pre-Production"),
        Server().url("https://dps-reconciliation.hmpps.service.justice.gov.uk").description("Production"),
        Server().url("http://localhost:8080").description("Local"),
      ),
    )
    .tags(
      listOf(),
    )
    .info(
      Info().title("HMPPS Dps Reconciliation").version(version)
        .description("Reconciles prison and domain events")
        .contact(Contact().name("HMPPS Digital Studio").email("feedback@digital.justice.gov.uk")),
    )
    .components(
      Components().addSecuritySchemes(
        "bearer-jwt",
        SecurityScheme().addBearerJwtRequirement(),
      ),
    )
    .addSecurityItem(SecurityRequirement().addList("bearer-jwt", listOf("read", "write")))
}

private fun SecurityScheme.addBearerJwtRequirement(): SecurityScheme = type(SecurityScheme.Type.HTTP)
  .scheme("bearer")
  .bearerFormat("JWT")
  .`in`(SecurityScheme.In.HEADER)
  .name("Authorization")
  .description("An HMPPS Auth access token.")
