package com.marketplace.android.feature

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Row 30 (agent findings 2026-10-25, CWE-488): the session-recovery
 * restore decision — a snapshot is restored ONLY when the authenticating
 * session's principal is confirmed to be the snapshot's owner. The
 * companion decode half lives in
 * {@code com.marketplace.android.core.session.AccessTokenSubjectTest};
 * this pins the decision matrix authenticated() composes it with.
 */
class CommunitySessionRecoveryTest {

    @Test
    fun theSamePrincipalGetsTheirOwnDraftBack() {
        assertTrue(canRestoreSessionRecoveryState(
            ownerSubject = "0e0b1e6d-6b1e-4f44-b3a3-c4f00d31d220",
            newSessionSubject = "0e0b1e6d-6b1e-4f44-b3a3-c4f00d31d220"))
    }

    @Test
    fun aDifferentAccountNeverSeesThePreviousAccountSDraftOrPhotos() {
        // The finding's exact scenario: session recovery falls back to
        // interactive login, and a DIFFERENT person completes it.
        assertFalse(canRestoreSessionRecoveryState(
            ownerSubject = "0e0b1e6d-6b1e-4f44-b3a3-c4f00d31d220",
            newSessionSubject = "7c2a9b52-2f3d-4a71-9a2f-6d8c5b9e4f01"))
    }

    @Test
    fun anUnknownSnapshotOwnerRestoresNothingFailClosed() {
        // The expired token's principal could not be read — the snapshot
        // has no confirmed owner, so NOBODY may receive it.
        assertFalse(canRestoreSessionRecoveryState(
            ownerSubject = null,
            newSessionSubject = "0e0b1e6d-6b1e-4f44-b3a3-c4f00d31d220"))
    }

    @Test
    fun anUnconfirmableNewSessionRestoresNothingFailClosed() {
        // The fresh token carries no readable sub — the new session's
        // principal cannot be confirmed as the owner.
        assertFalse(canRestoreSessionRecoveryState(
            ownerSubject = "0e0b1e6d-6b1e-4f44-b3a3-c4f00d31d220",
            newSessionSubject = null))
    }

    @Test
    fun twoUnknownPrincipalsStillRestoreNothingFailClosed() {
        assertFalse(canRestoreSessionRecoveryState(ownerSubject = null, newSessionSubject = null))
    }

    @Test
    fun theOwnerIsComparedByExactPrincipalHandle() {
        assertFalse(canRestoreSessionRecoveryState(
            ownerSubject = "member@dayf.example",
            newSessionSubject = "Member@dayf.example"))
    }
}
