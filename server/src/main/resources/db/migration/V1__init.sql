-- ride-insight storage model (docs/api.md). Per-second series live in array columns, one row per activity.

create table athlete (
    id          uuid primary key default gen_random_uuid(),
    name        text not null check (char_length(name) between 1 and 100),
    gender      text check (gender in ('M', 'F')),
    birth_year  integer check (birth_year between 1900 and 2100),
    weight_kg   real check (weight_kg > 20 and weight_kg < 300),
    created_at  timestamptz not null default now()
);

create table athlete_threshold (
    athlete_id      uuid not null references athlete(id) on delete cascade,
    effective_from  date not null,
    ftp_watts       integer not null check (ftp_watts between 50 and 1000),
    source          text not null default 'manual' check (source in ('manual', 'estimate', 'import')),
    created_at      timestamptz not null default now(),
    primary key (athlete_id, effective_from)
);

create table activity (
    id              uuid primary key default gen_random_uuid(),
    athlete_id      uuid not null references athlete(id) on delete cascade,
    started_at      timestamptz not null,
    source          text not null check (source in ('fit', 'opendata')),
    source_name     text,
    sport           text,
    rec_int_secs    real,
    interval_share  real,
    sample_count    integer not null,
    duration_secs   real,
    manufacturer    integer,
    product         integer,
    crc_valid       boolean,
    has_power       boolean not null,
    power_missing   integer not null default 0,
    created_at      timestamptz not null default now(),
    unique (athlete_id, started_at)
);

create index activity_athlete_started_idx on activity (athlete_id, started_at desc);

create table activity_samples (
    activity_id  uuid primary key references activity(id) on delete cascade,
    secs         real[] not null,
    power        smallint[],
    heart_rate   smallint[],
    cadence      smallint[],
    distance_m   real[],
    altitude_m   real[]
);

create table activity_metrics (
    activity_id       uuid primary key references activity(id) on delete cascade,
    metrics_version   integer not null,
    ftp_used          integer,
    normalized_power  real,
    intensity_factor  real,
    tss               real,
    average_power     real,
    max_power         integer,
    work_kj           real,
    mmp               jsonb not null,
    computed_at       timestamptz not null default now()
);
