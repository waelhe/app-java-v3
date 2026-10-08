package test.config;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The JVM-wide shared GreenMail for EVERY app-module integration context —
 * the deterministic completion of the deterministic EmailService
 * registration (62ab0091 moved EmailService to its official
 * auto-configuration home, so the bean now exists in every test context;
 * before that, the old {@code @Service @ConditionalOnBean} ordering made it
 * appear only in SOME contexts — the non-determinism the Boot reference
 * itself warns about — and whole classes of notification-flow integration
 * tests passed only because their email leg was silently skipped).
 *
 * <p><b>The measured consequence of determinism (e8a705ac's red run):</b>
 * with EmailService present, every notification listener's email leg
 * actually sends against {@code localhost:3025} (the test profile's own
 * binding) — and with no listener there, 107 sends failed with connection
 * refused, each failure rolling back its listener's REQUIRES_NEW unit and
 * taking the in-app notification row with it ("LEAD_RECEIVED never
 * landed" — one transaction away). This server gives every such test a
 * real, working mail channel.
 *
 * <p><b>Why one JVM-wide static instance and not a bean per context:</b> the
 * Spring test context cache keeps many contexts alive side by side
 * ({@code spring.test.context.cache.maxSize=12}); per-context servers would
 * collide on the single port 3025 the test profile binds. The shared
 * instance starts once (class-init of the holder, triggered only when the
 * bean method first runs) and is never stopped — the JVM's own exit cleans
 * it up. Tests that need a clean slate call
 * {@link GreenMail#purgeEmailFromAllMailboxes()} at setup (the established
 * idiom); per-test isolation rides the suite's unique recipient addresses.
 *
 * <p><b>The deliberate exception:</b> a test that needs the channel DOWN
 * (the mailSend breaker's outage simulation) does not fight this server —
 * it points its own sender at a dead port via a per-test
 * {@code spring.mail.port} override, exactly as
 * {@code MailChannelIsolationIntegrationTest} documents.</p>
 */
@AutoConfiguration
public class GreenMailTestAutoConfiguration {

    /** The holder defers the server start to the first bean creation. */
    private static final class SharedServerHolder {
        static final GreenMail INSTANCE = start();

        private static GreenMail start() {
            GreenMail greenMail = new GreenMail(ServerSetupTest.SMTP);
            greenMail.start();
            return greenMail;
        }
    }

    @Bean(destroyMethod = "")
    GreenMail greenMailTestServer() {
        return SharedServerHolder.INSTANCE;
    }
}
