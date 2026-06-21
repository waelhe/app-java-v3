package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link VerificationTokenService}.
 */
@ExtendWith(MockitoExtension.class)
class VerificationTokenServiceTest {

    @Mock
    private VerificationTokenRepository tokenRepository;

    @InjectMocks
    private VerificationTokenService tokenService;

    @Test
    void generateToken_createsAndSavesToken() {
        UUID userId = UUID.randomUUID();
        when(tokenRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        VerificationToken token = tokenService.generateToken(userId, VerificationTokenType.EMAIL_VERIFICATION);

        assertNotNull(token);
        assertEquals(userId, token.getUserId());
        assertEquals(VerificationTokenType.EMAIL_VERIFICATION, token.getTokenType());
        assertFalse(token.isUsed());
        assertFalse(token.isExpired());
        verify(tokenRepository).save(any());
    }

    @Test
    void validateToken_returnsTokenWhenValid() {
        String tokenValue = "valid-token";
        UUID userId = UUID.randomUUID();
        VerificationToken token = VerificationToken.create(userId, tokenValue,
                VerificationTokenType.EMAIL_VERIFICATION,
                Instant.now().plus(1, ChronoUnit.HOURS));
        when(tokenRepository.findByToken(tokenValue)).thenReturn(Optional.of(token));

        VerificationToken result = tokenService.validateToken(tokenValue, VerificationTokenType.EMAIL_VERIFICATION);

        assertEquals(token, result);
    }

    @Test
    void validateToken_throwsWhenNotFound() {
        when(tokenRepository.findByToken("missing")).thenReturn(Optional.empty());

        assertThrows(BadRequestException.class,
                () -> tokenService.validateToken("missing", VerificationTokenType.EMAIL_VERIFICATION));
    }

    @Test
    void validateToken_throwsWhenWrongType() {
        String tokenValue = "token";
        VerificationToken token = VerificationToken.create(UUID.randomUUID(), tokenValue,
                VerificationTokenType.PASSWORD_RESET,
                Instant.now().plus(1, ChronoUnit.HOURS));
        when(tokenRepository.findByToken(tokenValue)).thenReturn(Optional.of(token));

        assertThrows(BadRequestException.class,
                () -> tokenService.validateToken(tokenValue, VerificationTokenType.EMAIL_VERIFICATION));
    }

    @Test
    void validateToken_throwsWhenUsed() {
        String tokenValue = "used-token";
        VerificationToken token = VerificationToken.create(UUID.randomUUID(), tokenValue,
                VerificationTokenType.EMAIL_VERIFICATION,
                Instant.now().plus(1, ChronoUnit.HOURS));
        token.markAsUsed();
        when(tokenRepository.findByToken(tokenValue)).thenReturn(Optional.of(token));

        assertThrows(ConflictException.class,
                () -> tokenService.validateToken(tokenValue, VerificationTokenType.EMAIL_VERIFICATION));
    }

    @Test
    void validateToken_throwsWhenExpired() {
        String tokenValue = "expired-token";
        VerificationToken token = VerificationToken.create(UUID.randomUUID(), tokenValue,
                VerificationTokenType.EMAIL_VERIFICATION,
                Instant.now().minus(1, ChronoUnit.HOURS));
        when(tokenRepository.findByToken(tokenValue)).thenReturn(Optional.of(token));

        assertThrows(BadRequestException.class,
                () -> tokenService.validateToken(tokenValue, VerificationTokenType.EMAIL_VERIFICATION));
    }

    @Test
    void markAsUsed_savesToken() {
        VerificationToken token = VerificationToken.create(UUID.randomUUID(), "t",
                VerificationTokenType.EMAIL_VERIFICATION,
                Instant.now().plus(1, ChronoUnit.HOURS));

        tokenService.markAsUsed(token);

        assertTrue(token.isUsed());
        verify(tokenRepository).save(token);
    }
}
