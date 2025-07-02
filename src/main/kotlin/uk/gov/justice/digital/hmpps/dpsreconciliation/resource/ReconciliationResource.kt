package uk.gov.justice.digital.hmpps.dpsreconciliation.resource

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.dpsreconciliation.services.ReceiveService
import uk.gov.justice.hmpps.kotlin.common.ErrorResponse
import java.time.LocalDateTime

@RestController
@PreAuthorize("hasRole('DPS_RECONCILIATION__RW')")
class ReconciliationResource(
  private val receiveService: ReceiveService,
) {
  @Operation(
    summary = "Housekeeping job",
    description = """
      - Matches up any pairs of db rows which were missed;
      - Checks the database for any remaining events that were not matched;
      - purges old db rows
      Checks for event records created between the times given. Requires DPS_RECONCILIATION__RW""",
    responses = [
      ApiResponse(
        responseCode = "401",
        description = "Unauthorized to access this endpoint",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
      ApiResponse(
        responseCode = "403",
        description = "Access forbidden for this endpoint",
        content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))],
      ),
    ],
  )
  @GetMapping("/reconciliation/housekeeping")
  suspend fun detect(
    @Schema(description = "Earliest timestamp to check, default 4 hours ago", example = "2025-06-22T12:00:00", required = true)
    @RequestParam(value = "from", required = false)
    from: LocalDateTime?,
    @Schema(description = "Latest timestamp to check, default 2 hours ago", example = "2025-06-22T14:00:00", required = true)
    @RequestParam(value = "to", required = false)
    to: LocalDateTime?,
  ): String {
    val startCreatedDate = from ?: LocalDateTime.now().minusHours(4)
    val endCreatedDate = to ?: LocalDateTime.now().minusHours(2)

    receiveService.purgeOldMatchedRecords()
    receiveService.batchMatch(startCreatedDate, endCreatedDate)
    return receiveService.detect(startCreatedDate, endCreatedDate)
  }
}
