# hmpps-dps-reconciliation

[![repo standards badge](https://img.shields.io/badge/endpoint.svg?&style=flat&logo=github&url=https%3A%2F%2Foperations-engineering-reports.cloud-platform.service.justice.gov.uk%2Fapi%2Fv1%2Fcompliant_public_repositories%2Fhmpps-dps-reconciliation)](https://operations-engineering-reports.cloud-platform.service.justice.gov.uk/public-report/hmpps-dps-reconciliation "Link to report")
[![Docker Repository on ghcr](https://img.shields.io/badge/ghcr.io-repository-2496ED.svg?logo=docker)](https://ghcr.io/ministryofjustice/hmpps-dps-reconciliation)
[![API docs](https://img.shields.io/badge/API_docs_-view-85EA2D.svg?logo=swagger)](https://hmpps-dps-reconciliation-dev.hmpps.service.justice.gov.uk/webjars/swagger-ui/index.html?configUrl=/v3/api-docs)

# Reconciliation of prison events with domain events

This service listens for admission and release domain events, and their corresponding external movement prison events and 
attempts to match them up in the matching_event_pair database table. A slack alert occurs when
a mismatch is detected, i,e, there is an event which cannot be paired up.

## standard admission events

- EXTERNAL_MOVEMENT-CHANGED ADM where no previous movement or it was a REL
- prisoner-offender-search.prisoner.received

## standard released events

- EXTERNAL_MOVEMENT-CHANGED REL
- prisoner-offender-search.prisoner.released

## Unusual scenarios

A merge occurs:

- BOOKING_NUMBER-CHANGED, type MERGE
- If the surviving noms no is OUT but the deleted one was IN, then there is a prisoner-offender-search.prisoner.received type POST_MERGE_ADMISSION 

Prisoner released from hospital:

- EXTERNAL_MOVEMENT-CHANGED REL where previous movement was a REL-HP (release to the hospital)
- restricted-patients.patient.removed

Prisoner returns from hospital to prison; note the REL is **optional**:

- restricted-patients.patient.removed
- ( *possible* EXTERNAL_MOVEMENT-CHANGED REL where previous event was a REL-HP )
- EXTERNAL_MOVEMENT-CHANGED ADM
- prisoner-offender-search.prisoner.received READMISSION

## Mismatch scenarios (encountered so far)

1. There is a bug in Nomis where an offender is readmitted on an old booking, and
the seq of this booking is not set to '1'. prisoner-search wrongly gets stale data from the other booking and fails
to detect the movement and does not raise an event. This is not a race condition, it can persist for minutes e.g. A5024DJ @ 28 nov 2025 08:24
1. A prisoner can be released twice, i.e. have duplicate REL movements, with just one release domain event e.g. A5660AN @ Nov 25 10:30
1. A rapid (within minutes) double merge may be related to only one domain event (e.g. an ADM).
1. A booking.moved event can cause a domain release + receive but no external movement records. The receive event should be an READMISSION_SWITCH_BOOKING but currently (Nov 2025) due to SDIT-3065, may not be.
1. Very rarely hmpps-dps-reconciliation can receive an event more than once, though everything was fine, e.g. A4995EV at 2025-11-17 when received ADM event twice

It is also possible for a user to:
1. set a released or active-out prisoner to IN without creating a movement. This does not cause a mismatch but may result in a prisoner-search EVENTS_UNKNOWN_MOVEMENT app-insights event.
1. change the movement reason when admitting a prisoner as part of a transfer via court or TAP.
This reason is pre-populated as TRNCRT or TRNTAP in P-Nomis but the user can change it to something else, and if they do then again an EVENTS_UNKNOWN_MOVEMENT will occur.
1. add a movement with a slightly earlier movement datetime than the previous movement, possibly after a merge which has also caused movements to be out of sequence in the sense that the sequence numbers are 
not totally in the same order as the movement datetimes. This fools the reconciliation into e.g. ignoring an ADM as the previous by date is not a REL (though by sequence it is).
1. Receive an offender on multiple new bookings at the same time, e.g. A3243AT at 14-NOV-2025 23:46

# Cronjob

Every 2 hours a housekeeping job runs which:
- Matches up any pairs of db rows which were missed;
- Checks the database for any remaining events that were not matched;
- purges old db rows.

Unmatched rows can occur when
- rows are inserted simultaneously for the 2 events so each does not see the other already in the db;
- in the restricted-patients scenario : there may be a EXTERNAL_MOVEMENT-CHANGED REL where previous event was a REL-HP,
with the corresponding restricted-patients.patient.removed, or possibly just the latter.

Once these are matched up or fixed, an alert is produced on any remaining unmatched which are presumed
to be due to a bug or other unforeseen scenario.

