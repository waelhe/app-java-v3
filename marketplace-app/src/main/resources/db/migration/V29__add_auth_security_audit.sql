-- Phase 2: Security Hardening
-- OWASP Authentication Cheat Sheet: Account Lockout + Audit Logs

-- Add lockout columns to auth_users
ALTER TABLE auth_users ADD COLUMN IF NOT EXISTS failed_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE auth_users ADD COLUMN IF NOT EXISTS locked_until TIMESTAMPTZ DEFAULT NULL;

-- Audit log table (OWASP recommendation: log all auth events)
CREATE TABLE IF NOT EXISTS auth_audit_log (
    id              uuid NOT NULL PRIMARY KEY,
    username        varchar(320) NOT NULL,
    event_type      varchar(50) NOT NULL CHECK (event_type IN (
        'LOGIN_SUCCESS', 'LOGIN_FAILURE', 'REGISTRATION', 'EMAIL_VERIFIED',
        'PASSWORD_CHANGED', 'PASSWORD_RESET_REQUESTED', 'PASSWORD_RESET_COMPLETED',
        'ACCOUNT_LOCKED', 'ACCOUNT_UNLOCKED', 'ACCOUNT_DISABLED', 'ACCOUNT_ENABLED', 'ROLE_CHANGED', 'MFA_ENABLED', 'MFA_DISABLED'
    )),
    ip_address      varchar(45),
    user_agent      varchar(500),
    details         text,
    is_deleted      boolean NOT NULL DEFAULT false,
    version         bigint NOT NULL DEFAULT 0,
    created_by      varchar(200),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_by      varchar(200),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_auth_audit_username ON auth_audit_log (username);
CREATE INDEX IF NOT EXISTS idx_auth_audit_event_type ON auth_audit_log (event_type);
CREATE INDEX IF NOT EXISTS idx_auth_audit_created_at ON auth_audit_log (created_at);
