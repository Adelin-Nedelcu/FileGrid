ALTER TABLE stored_file ADD COLUMN uploaded_by varchar(128);
ALTER TABLE stored_file ADD COLUMN part_count integer;
ALTER TABLE stored_file ADD COLUMN part_size bigint;

CREATE TABLE stored_file_part (
    file_id     uuid        NOT NULL REFERENCES stored_file (id) ON DELETE CASCADE,
    part_number integer     NOT NULL,
    size_bytes  bigint      NOT NULL,
    sha256      varchar(64) NOT NULL,
    created_at  timestamptz NOT NULL,
    PRIMARY KEY (file_id, part_number)
);

CREATE INDEX ix_stored_file_pending_multipart ON stored_file (created_at)
    WHERE status = 'PENDING' AND part_count IS NOT NULL;
