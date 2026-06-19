-- V31: Expand auth_audit_log.event_type CHECK constraint to include 6 new event types
-- added in P2.17 (AuthEventType enum expansion).
--
-- Without this migration, the following production code paths fail with
-- PSQLException: "new row for relation auth_audit_log violates check constraint":
--   - MFA_FAILURE: TwoStepLoginService.recordMfaFailure, MfaService.verifyTotp (TOTP replay)
--   - RECOVERY_CODE_USED: TwoStepLoginService.verifyRecoveryCode
--   - SESSION_REVOKED: SessionController.revokeAllSessions
--   - LOGOUT, TOKEN_REVOKED, OAUTH2_LOGIN_SUCCESS: declared in enum, used in audit logging
--
-- Reference: PostgreSQL Docs — CHECK constraints:
-- "A check constraint consists of the key word CHECK followed by an expression in parentheses."
-- https://www.postgresql.org/docs/current/ddl-constraints.html

ALTER TABLE auth_audit_log DROP CONSTRAINT IF EXISTS auth_audit_log_event_type_check;

ALTER TABLE auth_audit_log ADD CONSTRAINT auth_audit_log_event_type_check
    CHECK (event_type IN (
        'LOGIN_SUCCESS', 'LOGIN_FAILURE', 'REGISTRATION', 'EMAIL_VERIFIED',
        'PASSWORD_CHANGED', 'PASSWORD_RESET_REQUESTED', 'PASSWORD_RESET_COMPLETED',
        'ACCOUNT_LOCKED', 'ACCOUNT_UNLOCKED', 'ACCOUNT_DISABLED', 'ACCOUNT_ENABLED',
        'ROLE_CHANGED', 'MFA_ENABLED', 'MFA_DISABLED',
        'MFA_FAILURE', 'RECOVERY_CODE_USED', 'LOGOUT', 'SESSION_REVOKED',
        'TOKEN_REVOKED', 'OAUTH2_LOGIN_SUCCESS'
    ));
