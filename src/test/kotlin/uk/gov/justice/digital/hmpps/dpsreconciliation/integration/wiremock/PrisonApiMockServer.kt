package uk.gov.justice.digital.hmpps.dpsreconciliation.integration.wiremock

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

class PrisonApiExtension :
  BeforeAllCallback,
  AfterAllCallback,
  BeforeEachCallback {
  companion object {
    @JvmField
    val prisonApi = PrisonApiMockServer()
  }

  override fun beforeAll(context: ExtensionContext) {
    prisonApi.start()
  }

  override fun beforeEach(context: ExtensionContext) {
    prisonApi.resetRequests()
  }

  override fun afterAll(context: ExtensionContext) {
    prisonApi.stop()
  }
}

class PrisonApiMockServer : WireMockServer(WIREMOCK_PORT) {
  companion object {
    private const val WIREMOCK_PORT = 8091
  }

  fun stubHealthPing(status: Int) {
    stubFor(
      get("/health/ping").willReturn(
        aResponse()
          .withHeader("Content-Type", "application/json")
          .withBody(if (status == 200) """{"status":"UP"}""" else """{"status":"DOWN"}""")
          .withStatus(status),
      ),
    )
  }

  fun stubGetMovementsForOffender(offenderNo: String) {
    stubFor(
      get("/api/movements/offender/$offenderNo").willReturn(
        okJson(
          """
          [ 
          {
      "offenderNo": "$offenderNo",
      "createDateTime": "2025-05-26T14:15:16",
      "fromAgency": "xxxxxx",
      "fromAgencyDescription": "xxxxxx",
      "toAgency": "xxxxxx",
      "toAgencyDescription": "xxxxxx",
      "fromCity": "xxxxxx",
      "toCity": "xxxxxx",
      "movementType": "ADM",
      "movementTypeDescription": "xxxxxx",
      "directionCode": "IN",
      "movementDate": "2025-05-26",
      "movementTime": "12:13:14",
      "movementReason": "xxxxxx",
      "movementReasonCode": "xxxxxx",
      "commentText": "xxxxxx"
          }
          ]
          """.trimIndent(),
        ),
      ),
    )
  }

  fun stubGetMovementsForBooking(bookingId: Long, response: String) {
    stubFor(get("/api/movements/booking/$bookingId").willReturn(okJson(response)))
  }
}
