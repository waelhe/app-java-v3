-- V32: Storage files table for file upload/download functionality.
-- Reference: Spring Boot How-to -- Handling Multipart File Uploads
-- https://docs.spring.io/spring-boot/how-to/spring-mvc.html

CREATE TABLE IF NOT EXISTS storage_files (
    id              uuid PRIMARY KEY,
    original_name   varchar(512) NOT NULL,
    stored_path     varchar(1024) NOT NULL,
    content_type    varchar(255),
    size_bytes      bigint NOT NULL,
    uploaded_by     uuid NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    created_by      varchar(200),
    updated_by      varchar(200),
    version         bigint NOT NULL DEFAULT 0,
    is_deleted      boolean NOT NULL DEFAULT false
);

CREATE INDEX IF NOT EXISTS idx_storage_files_uploaded_by ON storage_files (uploaded_by);
