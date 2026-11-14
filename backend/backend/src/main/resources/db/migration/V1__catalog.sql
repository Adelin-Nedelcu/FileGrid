CREATE TABLE stored_file (
    id                   uuid          PRIMARY KEY,
    category             varchar(32)   NOT NULL,
    storage_type         varchar(16)   NOT NULL,
    storage_key          varchar(512)  NOT NULL,
    status               varchar(16)   NOT NULL,
    original_name        varchar(512)  NOT NULL,
    content_type         varchar(255),
    size_bytes           bigint,
    sha256               varchar(64),
    encrypted            boolean       NOT NULL DEFAULT false,
    encryption_iv        bytea,
    idempotency_key      varchar(255),
    source_id            varchar(128),
    region               varchar(64),
    description          varchar(1000),
    error_message        varchar(1000),
    created_at           timestamptz   NOT NULL,
    stored_at            timestamptz,
    last_verified_at     timestamptz,
    last_verification_ok boolean,
    version              bigint        NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_stored_file_idempotency ON stored_file (category, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX ix_stored_file_category_created ON stored_file (category, created_at DESC);
CREATE INDEX ix_stored_file_status_created ON stored_file (status, created_at DESC);
CREATE INDEX ix_stored_file_region ON stored_file (region) WHERE region IS NOT NULL;

CREATE TABLE file_access_log (
    id          bigserial     PRIMARY KEY,
    file_id     uuid          NOT NULL REFERENCES stored_file (id),
    category    varchar(32)   NOT NULL,
    action      varchar(16)   NOT NULL,
    actor       varchar(128)  NOT NULL,
    client_ip   varchar(64),
    request_id  varchar(64),
    success     boolean       NOT NULL,
    detail      varchar(1000),
    occurred_at timestamptz   NOT NULL
);

CREATE INDEX ix_file_access_log_file ON file_access_log (file_id, occurred_at DESC);
