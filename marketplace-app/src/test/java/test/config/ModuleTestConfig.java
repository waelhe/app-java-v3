package test.config;

import com.marketplace.identity.AuthAuditService;
import com.marketplace.identity.AuthAuditLogRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.security.provisioning.UserDetailsManager;

import javax.sql.DataSource;

/**
 * Test configuration for @ApplicationModuleTest slice tests.
 * Provides beans that are normally created by SecurityConfig (which is not loaded
 * in module slice tests).
 */
@Configuration
public class ModuleTestConfig {

    @Bean
    UserDetailsManager userDetailsManager(DataSource dataSource) {
        JdbcUserDetailsManager manager = new JdbcUserDetailsManager(dataSource);
        manager.setUsersByUsernameQuery("select username, password, enabled from auth_users where username = ?");
        manager.setAuthoritiesByUsernameQuery("select username, authority from auth_authorities where username = ?");
        return manager;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    AuthAuditService authAuditService(AuthAuditLogRepository auditLogRepository) {
        return new AuthAuditService(auditLogRepository);
    }
}
