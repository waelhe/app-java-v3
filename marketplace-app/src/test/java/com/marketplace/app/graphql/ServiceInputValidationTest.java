package com.marketplace.app.graphql;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B3 (docs/codex-review-fixes-plan.md §4): free services are allowed. The
 * price rule is "zero or positive cents" and this pins it at the GraphQL input
 * surface with the framework's own validation API (Jakarta Bean Validation,
 * resolved by Hibernate Validator via spring-boot-starter-validation) — the
 * same provider Spring for GraphQL uses to enforce {@code @Valid @Argument}
 * at runtime.
 *
 * <p>Surfaces already enforcing the same rule: V2
 * (provider_listings.price_cents {@code >= 0}), REST
 * (CreateListingRequest/UpdateListingRequest priceCents {@code @NotNull}
 * only) and BookingInfo (rejects {@code < 0} only). The former
 * {@code @Positive} on this record rejected zero — GraphQL was the only
 * surface forbidding a free listing.</p>
 *
 * <p>Scope note (pre-existing, out of B3's scope): the GraphQL schema input
 * field is {@code price: Float!} while this record's component is
 * {@code priceCents} — the spring-graphql argument binder matches constructor
 * parameter names to input keys by name, so the createService mutation has a
 * schema-to-DTO seam defect that predates PR #255 (last touched by #226 /
 * the original state-machines commit, zero mutation coverage in the repo).
 * This test therefore pins the validation contract of the record itself; the
 * seam needs a separate user decision (schema field vs DTO naming/units).</p>
 */
class ServiceInputValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        factory.close();
    }

    @Test
    void zeroPriceCentsIsValid_freeServiceAllowed() {
        Set<ConstraintViolation<ServiceInput>> violations =
                validator.validate(new ServiceInput("Free consult", "15 minutes", "general", 0L));

        assertThat(violations).isEmpty();
    }

    @Test
    void positivePriceCentsIsValid() {
        Set<ConstraintViolation<ServiceInput>> violations =
                validator.validate(new ServiceInput("Paid consult", "30 minutes", "general", 5000L));

        assertThat(violations).isEmpty();
    }

    @Test
    void negativePriceCentsIsRejected() {
        Set<ConstraintViolation<ServiceInput>> violations =
                validator.validate(new ServiceInput("Bad price", "desc", "general", -1L));

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("priceCents");
    }

    @Test
    void nullPriceCentsIsRejected() {
        Set<ConstraintViolation<ServiceInput>> violations =
                validator.validate(new ServiceInput("No price", "desc", "general", null));

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("priceCents");
    }

    /** CodeRabbit r1: the shared GraphQL Int ceiling (2,147,483,647) is explicit in the type. */
    @Test
    void priceCentsAtGraphQLIntCeilingIsValid_aboveItIsRejected() {
        assertThat(validator.validate(
                new ServiceInput("Ceiling", "desc", "general", 2_147_483_647L))).isEmpty();

        Set<ConstraintViolation<ServiceInput>> violations =
                validator.validate(new ServiceInput("Above ceiling", "desc", "general", 2_147_483_648L));

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("priceCents");
    }
}
