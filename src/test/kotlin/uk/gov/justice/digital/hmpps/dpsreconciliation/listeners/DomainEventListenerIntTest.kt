package uk.gov.justice.digital.hmpps.dpsreconciliation.listeners

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.groups.Tuple
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.kotlin.check
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.reset
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.validDomainRPRemovedMessage
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.validDomainReceiveMessage
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.validDomainReleaseMessage
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.validMessage
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.validOffenderAdmissionMessage
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.validOffenderReleaseMessage
import uk.gov.justice.digital.hmpps.dpsreconciliation.integration.wiremock.PrisonApiExtension.Companion.prisonApi
import uk.gov.justice.digital.hmpps.dpsreconciliation.model.MatchType
import uk.gov.justice.digital.hmpps.dpsreconciliation.services.PrisonerReceiveDomainEvent
import uk.gov.justice.digital.hmpps.dpsreconciliation.services.PrisonerReceiveReason
import uk.gov.justice.digital.hmpps.dpsreconciliation.services.ReceivePrisonerAdditionalInformationEvent
import uk.gov.justice.digital.hmpps.dpsreconciliation.services.ReceiveService
import java.time.LocalDateTime
import java.time.OffsetDateTime

class DomainEventListenerIntTest : IntegrationTestBase() {

  @MockitoSpyBean
  lateinit var receiveServiceSpyBean: ReceiveService

  @Nested
  inner class Received {
    @Test
    fun `will process a prisoner received event`() {
      val prisonerNumber = "A7089FD"

      sendMessage(validDomainReceiveMessage(prisonerNumber))

      await untilAsserted {
        verify(telemetryClient).trackEvent(
          eq("domain-event"),
          anyMap(),
          isNull(),
        )
      }

      verify(receiveServiceSpyBean).prisonerDomainReceiveHandler(
        eq(
          PrisonerReceiveDomainEvent(
            additionalInformation = ReceivePrisonerAdditionalInformationEvent(
              nomsNumber = prisonerNumber,
              reason = PrisonerReceiveReason.NEW_ADMISSION,
              prisonId = "CFI",
            ),
            occurredAt = OffsetDateTime.parse("2025-05-13T15:38:48.0Z"),
          ),
        ),
      )

      val actual = repository.findByNomsNumberAndMatchTypeAndDomainTimeAfterAndMatched(
        prisonerNumber,
        MatchType.RECEIVED,
        LocalDateTime.parse("2025-05-01T10:00:00"),
        false,
      )
      assertThat(
        actual,
      ).extracting("matchType", "nomsNumber", "domainReason", "domainTime", "matched")
        .containsExactly(
          Tuple(
            MatchType.RECEIVED,
            prisonerNumber,
            "NEW_ADMISSION",
            expectedDomainTime,
            false,
          ),
        )
    }

    @Test
    fun `will match a previous external movement event`() {
      val prisonerNumber = "A7089FD"

      prisonApi.stubGetMovementsForBooking(
        101,
        """
          [
       {
      "sequence": 1,
      "movementType": "ADM",
      "directionCode": "IN",
      "movementDateTime": "2025-05-26T12:13:14",
      "movementReasonCode": "LC",
      "createdDateTime":  "2025-05-26T12:13:15"
       }]
        """.trimMargin(),
      )

      sendMessage(validOffenderAdmissionMessage(prisonerNumber, 101))

      await untilAsserted {
        verify(telemetryClient).trackEvent(eq("offender-event"), anyMap(), isNull())
      }
      reset(telemetryClient)

      sendMessage(validDomainReceiveMessage(prisonerNumber))

      await untilAsserted {
        verify(telemetryClient).trackEvent(eq("domain-event"), anyMap(), isNull())
      }

      assertThat(
        repository.findByNomsNumberAndMatchTypeAndDomainTimeAfterAndMatched(
          prisonerNumber,
          MatchType.RECEIVED,
          LocalDateTime.parse("2025-05-01T10:00:00"),
          true,
        ),
      ).extracting(
        "matchType",
        "nomsNumber",
        "domainReason",
        "domainTime",
        "offenderBookingId",
        "offenderReason",
        "offenderTime",
        "matched",
        "comment",
      )
        .containsExactly(
          Tuple(
            MatchType.RECEIVED,
            prisonerNumber,
            "NEW_ADMISSION",
            expectedDomainTime,
            101L,
            "REASON",
            LocalDateTime.parse("2025-05-13T15:38:30"),
            true,
            "matchOutcome = matched",
          ),
        )
    }

    @Test
    fun `will match when there are multiple external movement events`() {
      val prisonerNumber = "A7089FD"

      prisonApi.stubGetMovementsForBooking(
        101,
        """
          [
       {
      "sequence": 1,
      "movementType": "ADM",
      "directionCode": "IN",
      "movementDateTime": "2025-05-26T12:13:14",
      "movementReasonCode": "LC",
      "createdDateTime":  "2025-05-26T12:13:15"
       }]
        """.trimMargin(),
      )

      sendMessage(validOffenderAdmissionMessage(prisonerNumber, 101))
      sendMessage(validOffenderAdmissionMessage(prisonerNumber, 101))

      await untilAsserted {
        verify(telemetryClient, times(2)).trackEvent(eq("offender-event"), anyMap(), isNull())
      }
      reset(telemetryClient)

      sendMessage(validDomainReceiveMessage(prisonerNumber))

      await untilAsserted {
        verify(telemetryClient).trackEvent(
          eq("domain-event"),
          check {
            assertThat(it["matchOutcome"]).isEqualTo("multiple: 2")
          },
          isNull(),
        )
      }

      assertThat(
        repository.findByNomsNumberAndMatchTypeAndDomainTimeAfterAndMatched(
          prisonerNumber,
          MatchType.RECEIVED,
          LocalDateTime.parse("2025-05-01T10:00:00"),
          true,
        ),
      ).extracting(
        "matchType",
        "nomsNumber",
        "domainReason",
        "domainTime",
        "offenderBookingId",
        "offenderReason",
        "offenderTime",
        "matched",
        "comment",
      )
        .containsExactly(
          Tuple(
            MatchType.RECEIVED,
            prisonerNumber,
            "NEW_ADMISSION",
            expectedDomainTime,
            101L,
            "REASON",
            LocalDateTime.parse("2025-05-13T15:38:30"),
            true,
            "matchOutcome = multiple: 2",
          ),
        )
    }
  }

  @Nested
  inner class Released {
    @Test
    fun `will match a previous external movement event`() {
      val prisonerNumber = "A7089FD"

      prisonApi.stubGetMovementsForBooking(
        101,
        """
          [
       {
      "sequence": 1,
      "movementType": "ADM",
      "directionCode": "IN",
      "movementDateTime": "2025-05-26T12:13:14",
      "movementReasonCode": "LC",
      "createdDateTime":  "2025-05-26T12:13:15"
       }]
        """.trimMargin(),
      )

      sendMessage(validOffenderReleaseMessage(prisonerNumber, 101))

      await untilAsserted {
        verify(telemetryClient).trackEvent(eq("offender-event"), anyMap(), isNull())
      }
      reset(telemetryClient)

      sendMessage(validDomainReleaseMessage(prisonerNumber))

      await untilAsserted {
        verify(telemetryClient).trackEvent(
          eq("domain-event"),
          check {
            assertThat(it["type"]).isEqualTo("RELEASED")
          },
          isNull(),
        )
      }

      assertThat(
        repository.findByNomsNumberAndMatchTypeAndDomainTimeAfterAndMatched(
          prisonerNumber,
          MatchType.RELEASED,
          LocalDateTime.parse("2025-05-01T10:00:00"),
          true,
        ),
      ).extracting(
        "matchType",
        "nomsNumber",
        "domainReason",
        "domainTime",
        "offenderBookingId",
        "offenderReason",
        "offenderTime",
        "matched",
        "comment",
      )
        .containsExactly(
          Tuple(
            MatchType.RELEASED,
            prisonerNumber,
            "RELEASED",
            expectedDomainTime,
            101L,
            "RELEASED",
            LocalDateTime.parse("2025-05-13T15:38:30"),
            true,
            "matchOutcome = matched",
          ),
        )
    }

    @Test
    fun `will match when there are multiple external movement events`() {
      val prisonerNumber = "A7089FD"

      prisonApi.stubGetMovementsForBooking(
        101,
        """
          [
       {
      "sequence": 1,
      "movementType": "ADM",
      "directionCode": "IN",
      "movementDateTime": "2025-05-26T12:13:14",
      "movementReasonCode": "LC",
      "createdDateTime":  "2025-05-26T12:13:15"
       }]
        """.trimMargin(),
      )

      sendMessage(validOffenderReleaseMessage(prisonerNumber, 101))
      sendMessage(validOffenderReleaseMessage(prisonerNumber, 101))

      await untilAsserted {
        verify(telemetryClient, times(2)).trackEvent(eq("offender-event"), anyMap(), isNull())
      }
      reset(telemetryClient)

      sendMessage(validDomainReleaseMessage(prisonerNumber))

      await untilAsserted {
        verify(telemetryClient).trackEvent(
          eq("domain-event"),
          check {
            assertThat(it["type"]).isEqualTo("RELEASED")
            assertThat(it["matchOutcome"]).isEqualTo("multiple: 2")
          },
          isNull(),
        )
      }

      assertThat(
        repository.findByNomsNumberAndMatchTypeAndDomainTimeAfterAndMatched(
          prisonerNumber,
          MatchType.RELEASED,
          LocalDateTime.parse("2025-05-01T10:00:00"),
          true,
        ),
      ).extracting(
        "matchType",
        "nomsNumber",
        "domainReason",
        "domainTime",
        "offenderBookingId",
        "offenderReason",
        "offenderTime",
        "matched",
        "comment",
      )
        .containsExactly(
          Tuple(
            MatchType.RELEASED,
            prisonerNumber,
            "RELEASED",
            expectedDomainTime,
            101L,
            "RELEASED",
            LocalDateTime.parse("2025-05-13T15:38:30"),
            true,
            "matchOutcome = multiple: 2",
          ),
        )
    }
  }

  @Nested
  inner class RPRemoved {
    @Test
    fun `will match a previous release external movement event`() {
      val prisonerNumber = "A7089FD"

      prisonApi.stubGetMovementsForBooking(
        101,
        """
      [{
        "sequence": 1,
        "movementType": "REL",
        "directionCode": "OUT",
        "movementDateTime": "2025-05-26T12:13:14",
        "movementReasonCode": "HP",
        "createdDateTime":  "2025-05-26T12:13:15"
       },
       {
        "sequence": 2,
        "movementType": "REL",
        "directionCode": "OUT",
        "movementDateTime": "2025-05-26T12:13:14",
        "movementReasonCode": "CP",
        "createdDateTime":  "2025-05-26T12:13:16"
       }]
        """.trimMargin(),
      )

      sendMessage(validOffenderReleaseMessage(prisonerNumber, 101, 2))

      await untilAsserted {
        verify(telemetryClient).trackEvent(eq("offender-event"), anyMap(), isNull())
      }
      reset(telemetryClient)

      sendMessage(validDomainRPRemovedMessage(prisonerNumber))

      await untilAsserted {
        verify(telemetryClient).trackEvent(
          eq("restricted-patient-event"),
          check {
            assertThat(it["type"]).isEqualTo("RELEASED")
          },
          isNull(),
        )
      }

      assertThat(
        repository.findByNomsNumberAndMatchTypeAndDomainTimeAfterAndMatched(
          prisonerNumber,
          MatchType.RELEASED,
          LocalDateTime.parse("2025-05-01T10:00:00"),
          true,
        ),
      ).extracting(
        "matchType",
        "nomsNumber",
        "domainReason",
        "domainTime",
        "offenderBookingId",
        "offenderReason",
        "offenderTime",
        "matched",
        "comment",
      )
        .containsExactly(
          Tuple(
            MatchType.RELEASED,
            prisonerNumber,
            "REMOVED_FROM_HOSPITAL",
            expectedDomainTime,
            101L,
            "RELEASED",
            LocalDateTime.parse("2025-05-13T15:38:30"),
            true,
            "matchOutcome = matched",
          ),
        )
    }
  }

  @Nested
  inner class Scenarios {
    @Test
    fun `RP released from hospital to prison then released`() {
      val bookingId = 2818493L
      val prisonerNumber = "A5178EX"

      prisonApi.stubGetMovementsForBooking(
        bookingId,
        """
        [
         {
          "sequence": 1,
          "movementType": "ADM",
          "directionCode": "IN",
          "movementDateTime": "2025-05-26T12:13:14",
          "movementReasonCode": "O",
          "createdDateTime":  "2025-05-26T12:13:15"
         },
         {
          "sequence": 2,
          "movementType": "REL",
          "directionCode": "OUT",
          "movementDateTime": "2025-05-26T12:13:14",
          "movementReasonCode": "HP",
          "createdDateTime":  "2025-05-26T12:13:15"
         },
         {
          "sequence": 3,
          "movementType": "ADM",
          "directionCode": "IN",
          "movementDateTime": "2025-05-26T12:13:14",
          "movementReasonCode": "R",
          "createdDateTime":  "2025-05-26T12:13:15"
         },
         {
          "sequence": 4,
          "movementType": "REL",
          "directionCode": "OUT",
          "movementDateTime": "2025-05-26T12:13:14",
          "movementReasonCode": "DEC",
          "createdDateTime":  "2025-05-26T12:13:15"
         }
       ]
        """,
        /*
	2818493		20/07/2006 00:00:00	1	ADM	O	IN	FNI	    FNI	    N	P_PHILLIPS	18-JAN-2023 08:35:32.538650704	18-JAN-2023 08:35:32.634425000	MERGE	New Booking
	2818493		20/07/2006 00:00:00	2	REL	HP	OUT	FNI	    KSWRTH	N	P_PHILLIPS	18-JAN-2023 08:35:32.647803983	12-FEB-2026 15:47:10.455883000	OIDADMIS	Psychiatric Hospital Discharge to Kneesworth House
	2818493		12/02/2026 15:41:26	3	ADM	R	IN	KSWRTH	FNI	    N	JQL62M	    12-FEB-2026 15:47:10.529401000	12-FEB-2026 15:52:12.688469000	OIDRELEA	Deceased whilst at outside institution
	2818493		12/02/2026 15:48:12	4	REL	DEC	OUT	FNI	    OUT	    Y	JQL62M	    12-FEB-2026 15:52:12.704371000		                            OIDRELEA	Mr Lees died on the 12/10/23 at Kneesworth Hospital but remained as an active prisoner on HMP Full Sutton's caseload - this entry now rectifies that error.
         */
      )
      sendMessage(
        validMessage(
          "EXTERNAL_MOVEMENT-CHANGED",
          """{\"eventType\":\"EXTERNAL_MOVEMENT-CHANGED\",\"eventDatetime\":\"2026-02-12T15:47:10\",\"bookingId\":$bookingId,\"offenderIdDisplay\":\"$prisonerNumber\",\"nomisEventType\":\"EXTERNAL_MOVEMENT-CHANGED\",\"movementSeq\":3,\"movementDateTime\":\"2026-02-12T15:41:26\",\"movementType\":\"ADM\",\"movementReasonCode\":\"R\",\"directionCode\":\"IN\",\"fromAgencyLocationId\":\"KSWRTH\",\"toAgencyLocationId\":\"FNI\",\"recordInserted\":true,\"recordDeleted\":false,\"auditModuleName\":\"OIDADMIS\"}""",
        ),
      )

      sendMessage(
        validMessage(
          "restricted-patients.patient.removed",
          """{\"eventType\":\"restricted-patients.patient.removed\",\"additionalInformation\":{\"prisonerNumber\":\"$prisonerNumber\"},\"version\":1,\"occurredAt\":\"2026-02-12T15:47:10.701524944Z\",\"publishedAt\":\"2026-02-12T15:47:10.701524944Z\",\"description\":\"Prisoner no longer a restricted patient\",\"personReference\":{\"identifiers\":[{\"type\":\"NOMS\",\"value\":\"A5178EX\"}]}}""",
        ),
      )

      sendMessage(
        validMessage(
          "prisoner-offender-search.prisoner.received",
          """{\"additionalInformation\":{\"nomsNumber\":\"$prisonerNumber\",\"reason\":\"READMISSION\",\"prisonId\":\"FNI\"},\"occurredAt\":\"2026-02-12T15:47:10.840867033Z\",\"eventType\":\"prisoner-offender-search.prisoner.received\",\"version\":1,\"description\":\"A prisoner has been readmitted into the prison from the hospital\",\"detailUrl\":\"https://prisoner-search.prison.service.justice.gov.uk/prisoner/A5178EX\",\"personReference\":{\"identifiers\":[{\"type\":\"NOMS\",\"value\":\"A5178EX\"}]}}""",
        ),
      )

      Thread.sleep(1000) // In real life there would be a significant time gap here

      sendMessage(
        validMessage(
          "EXTERNAL_MOVEMENT-CHANGED",
          """{\"eventType\":\"EXTERNAL_MOVEMENT-CHANGED\",\"eventDatetime\":\"2026-02-12T15:52:12\",\"bookingId\":$bookingId,\"offenderIdDisplay\":\"$prisonerNumber\",\"nomisEventType\":\"EXTERNAL_MOVEMENT-CHANGED\",\"movementSeq\":4,\"movementDateTime\":\"2026-02-12T15:48:12\",\"movementType\":\"REL\",\"movementReasonCode\":\"DEC\",\"directionCode\":\"OUT\",\"fromAgencyLocationId\":\"FNI\",\"toAgencyLocationId\":\"OUT\",\"recordInserted\":true,\"recordDeleted\":false,\"auditModuleName\":\"OIDRELEA\"}""",
        ),
      )

      sendMessage(
        validMessage(
          "prisoner-offender-search.prisoner.released",
          """{\"additionalInformation\":{\"nomsNumber\":\"$prisonerNumber\",\"reason\":\"RELEASED\",\"prisonId\":\"FNI\"},\"occurredAt\":\"2026-02-12T15:52:12.968609308Z\",\"eventType\":\"prisoner-offender-search.prisoner.released\",\"version\":1,\"description\":\"A prisoner has been released from a prison with reason: died in hospital\",\"detailUrl\":\"https://prisoner-search.prison.service.justice.gov.uk/prisoner/A5178EX\",\"personReference\":{\"identifiers\":[{\"type\":\"NOMS\",\"value\":\"A5178EX\"}]}}""",
        ),
      )

      await untilAsserted {
        verify(telemetryClient).trackEvent( // wait for the last AppEvent
          eq("domain-event"),
          check { assertThat(it["type"]).isEqualTo(MatchType.RELEASED.name) },
          isNull(),
        )
      }

      assertThat(repository.findAll())
        .extracting(
          "matchType",
          "nomsNumber",
          "domainReason",
          "offenderBookingId",
          "offenderReason",
          "matched",
        )
        .containsExactlyInAnyOrder(
          Tuple(MatchType.RECEIVED, prisonerNumber, "READMISSION", bookingId, "R", true),
          Tuple(MatchType.RELEASED, prisonerNumber, "REMOVED_FROM_HOSPITAL", null, null, false), // orphan
          Tuple(MatchType.RELEASED, prisonerNumber, "RELEASED", bookingId, "DEC", true),
        )
    }
  }
}
