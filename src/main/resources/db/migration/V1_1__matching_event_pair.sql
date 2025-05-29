create table MATCHING_EVENT_PAIR
(
    ID                              serial primary key,
    MATCH_TYPE                      varchar(20)              not null,
    NOMS_NUMBER                     varchar(20)              not null,
    DOMAIN_RECEIVE_REASON           varchar(20),
    DOMAIN_RECEIVED_TIME            timestamp with time zone,
    OFFENDER_BOOKING_ID             bigint,
    OFFENDER_REASON_CODE            varchar(20),
    OFFENDER_RECEIVED_TIME          timestamp with time zone,

    PREVIOUS_OFFENDER_REASON_CODE   varchar(20),
    PREVIOUS_OFFENDER_RECEIVED_TIME timestamp with time zone,
    PREVIOUS_OFFENDER_DIRECTION     varchar(20),

    CREATED_DATE                    timestamp with time zone not null,
    MATCHED                         boolean default false    not null
);

create index MATCHING_EVENT_PAIR_I1 on MATCHING_EVENT_PAIR (NOMS_NUMBER, MATCHED);
