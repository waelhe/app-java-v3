package com.marketplace.payments;

import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.observation.annotation.Observed;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Architecture gate for the N2 fix: the authorization split between the
 * command surface and the domain settlement. The pre-fix shape had
 * {@code confirmIntent} (carrying {@code @PreAuthorize("hasRole('ADMIN')")}
 * + {@code @Retry} + {@code @Observed}) on {@code PaymentsService}, while
 * the verified-webhook dispatch reached it through <em>self-invocation</em>
 * — documented Spring Framework AOP behavior (Reference › AOP › Proxying
 * Mechanisms): a target-method call bypasses the proxy, so on the webhook
 * path the role annotation read as a boundary that was silently skipped,
 * and the observation/retry never fired. This test pins the corrected
 * shape so the lie cannot return:
 * <ul>
 *   <li>the domain settlement methods carry observability + resilience
 *       (proxy semantics) and NO role check — authorization belongs to
 *       the calling surface, not the domain transition;</li>
 *   <li>the admin command shell carries the ADMIN role check and nothing
 *       else;</li>
 *   <li>{@code failIntent} no longer exists on {@code PaymentsService} —
 *       its only caller was the self-invocation, so its {@code @Observed}
 *       was dead code (never recorded once).</li>
 * </ul>
 */
class SettlementAuthorizationSplitTest {

    @Test
    void domainSettlementCarriesObservabilityAndResilienceButNoRoleCheck() throws Exception {
        Method confirm = PaymentIntentSettlementService.class
                .getMethod("confirm", UUID.class, String.class);
        Method fail = PaymentIntentSettlementService.class
                .getMethod("fail", UUID.class);

        for (Method m : new Method[]{confirm, fail}) {
            assertNotNull(m.getAnnotation(Observed.class),
                    m.getName() + " must stay @Observed — the webhook path needs the observation");
            assertNull(m.getAnnotation(PreAuthorize.class),
                    m.getName() + " must NOT carry a role check — the webhook reaches it with"
                            + " provider-signature authorization, and a role annotation here"
                            + " would either break that path or lie about it again");
        }
        assertNotNull(confirm.getAnnotation(Retry.class),
                "confirm must keep @Retry(paymentProcessing) — PSP settlement is the"
                        + " transient-failure-prone call the retry was configured for");
    }

    @Test
    void adminCommandShellCarriesTheRoleCheckAndOnlyThat() throws Exception {
        Method command = PaymentsService.class
                .getMethod("confirmIntent", UUID.class, String.class);

        PreAuthorize role = command.getAnnotation(PreAuthorize.class);
        assertNotNull(role, "the admin command must keep its role check");
        assertEquals("hasRole('ADMIN')", role.value());
        assertNull(command.getAnnotation(Observed.class),
                "observation moved to the domain settlement (single point covering both paths)");
        assertNull(command.getAnnotation(Retry.class),
                "retry moved to the domain settlement (single point covering both paths)");
    }

    @Test
    void deadFailIntentSurfaceIsGoneFromPaymentsService() {
        assertThrows(NoSuchMethodException.class, () ->
                        PaymentsService.class.getMethod("failIntent", UUID.class),
                "failIntent had no caller besides the self-invocation — resurrecting it as an"
                        + " unexposed public method would recreate an annotated-but-dead surface");
    }
}
