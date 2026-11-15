CREATE TABLE meter_reading_stat (
    file_id        uuid             PRIMARY KEY REFERENCES stored_file (id) ON DELETE CASCADE,
    meter_id       varchar(128),
    region         varchar(64),
    consumer_type  varchar(32),
    reading_at     timestamptz      NOT NULL,
    samples        integer          NOT NULL DEFAULT 0,
    avg_kw         double precision,
    max_kw         double precision,
    min_voltage    double precision,
    outage_samples integer          NOT NULL DEFAULT 0,
    sag_samples    integer          NOT NULL DEFAULT 0,
    spike_samples  integer          NOT NULL DEFAULT 0,
    parsed         boolean          NOT NULL,
    analyzed_at    timestamptz      NOT NULL
);

CREATE INDEX ix_meter_reading_stat_time ON meter_reading_stat (reading_at);
CREATE INDEX ix_meter_reading_stat_meter ON meter_reading_stat (meter_id, reading_at);

CREATE TABLE storage_benchmark (
    id              bigserial        PRIMARY KEY,
    run_id          uuid             NOT NULL,
    category        varchar(32)      NOT NULL,
    storage_type    varchar(16)      NOT NULL,
    files           integer          NOT NULL,
    file_size_bytes bigint           NOT NULL,
    write_p50_ms    double precision,
    write_p95_ms    double precision,
    read_p50_ms     double precision,
    read_p95_ms     double precision,
    write_mb_per_s  double precision,
    read_mb_per_s   double precision,
    errors          integer          NOT NULL,
    error_detail    varchar(500),
    measured_at     timestamptz      NOT NULL
);

CREATE INDEX ix_storage_benchmark_measured ON storage_benchmark (measured_at DESC);

CREATE INDEX ix_file_access_log_category_time ON file_access_log (category, occurred_at DESC);
