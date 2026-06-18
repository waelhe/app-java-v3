package com.marketplace.identity;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Manages creation, validation, and consumption of verification tokens.
 * <p>Follows OWASP Authentication Cheat Sheet — tokens are:
 * <ul>
 *   <li>Cryptographically random (32 bytes via {@link SecureRandom})</li>
 *   <li>Single-use (throws if already used)</li>
 *   <li>Time-bound (throws if expired)</li>
 * </ul>
 *
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html">OWASP Forgot Password Cheat Sheet</a>
 */
@Service
public class VerificationTokenService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32; // 256-bit token

    public static final Duration EMAIL_VERIFICATION_EXPIRY = Duration.ofHours(24);
    public static final Duration PASSWORD_RESET_EXPIRY = Duration.ofMinutes(30);

    private final VerificationTokenRepository tokenRepository;

    public VerificationTokenService(VerificationTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    @Transactional
    public VerificationToken generateToken(UUID userId, VerificationTokenType type) {
        String tokenValue = generateSecureToken();
        Instant expiry = Instant.now().plus(
                type == VerificationTokenType.EMAIL_VERIFICATION
                        ? EMAIL_VERIFICATION_EXPIRY
                        : PASSWORD_RESET_EXPIRY
        );
        VerificationToken token = VerificationToken.create(userId, tokenValue, type, expiry);
        return tokenRepository.save(token);
    }

    @Transactional(readOnly = true)
    public VerificationToken validateToken(String tokenValue, VerificationTokenType expectedType) {
        VerificationToken token = tokenRepository.findByToken(tokenValue)
                .orElseThrow(() -> new BadRequestException("Invalid verification token"));

        if (token.getTokenType() != expectedType) {
            throw new BadRequestException("Invalid token type");
        }
        if (token.isUsed()) {
            throw new ConflictException("Token has already been used");
        }
        if (token.isExpired()) {
            throw new BadRequestException("Token has expired. Please request a new one.");
        }

        return token;
    }

    @Transactional
    public void markAsUsed(VerificationToken token) {
        token.markAsUsed();
        tokenRepository.save(token);
    }

    private String generateSecureToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
