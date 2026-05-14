package com.marketplace.admin;

import com.marketplace.booking.BookingController;
import com.marketplace.catalog.CatalogController;
import com.marketplace.ledger.LedgerController;
import com.marketplace.payments.PaymentsController;
import com.marketplace.reviews.ReviewsController;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that @PreAuthorize annotations are present on protected controller
 * methods with the correct role expressions.
 */
class PreAuthorizeAnnotationTest {

    @Test
    void bookingCreate_requiresConsumer() throws Exception {
        Method m = BookingController.class.getMethod("create", BookingController.CreateBookingRequest.class, org.springframework.security.core.Authentication.class);
        PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
        assertNotNull(pa, "create() should be @PreAuthorize");
        assertEquals("hasRole('CONSUMER')", pa.value());
    }

    @Test
    void bookingConfirm_requiresProviderOrAdmin() throws Exception {
        Method m = BookingController.class.getMethod("confirm", java.util.UUID.class, org.springframework.security.core.Authentication.class);
        PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
        assertNotNull(pa);
        assertEquals("hasAnyRole('PROVIDER','ADMIN')", pa.value());
    }

    @Test
    void bookingComplete_requiresProviderOrAdmin() throws Exception {
        Method m = BookingController.class.getMethod("complete", java.util.UUID.class, org.springframework.security.core.Authentication.class);
        PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
        assertNotNull(pa);
        assertEquals("hasAnyRole('PROVIDER','ADMIN')", pa.value());
    }

    @Test
    void bookingCancel_requiresConsumerOrProvider() throws Exception {
        Method m = BookingController.class.getMethod("cancel", java.util.UUID.class, org.springframework.security.core.Authentication.class);
        PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
        assertNotNull(pa);
        assertEquals("hasAnyRole('CONSUMER','PROVIDER')", pa.value());
    }

    @Test
    void catalogCreate_requiresProvider() throws Exception {
        Method m = CatalogController.class.getMethod("create", CatalogController.CreateListingRequest.class, org.springframework.security.core.Authentication.class);
        PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
        assertNotNull(pa);
        assertEquals("hasRole('PROVIDER')", pa.value());
    }

    @Test
    void paymentsCreateIntent_requiresConsumer() throws Exception {
        Method m = PaymentsController.class.getMethod("createIntent", PaymentsController.CreateIntentRequest.class, org.springframework.security.core.Authentication.class);
        PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
        assertNotNull(pa);
        assertEquals("hasRole('CONSUMER')", pa.value());
    }

    @Test
    void paymentsRefund_requiresAdmin() throws Exception {
        Method m = PaymentsController.class.getMethod("refundPayment", java.util.UUID.class);
        PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
        assertNotNull(pa);
        assertEquals("hasRole('ADMIN')", pa.value());
    }

    @Test
    void ledgerCredit_requiresAdmin() throws Exception {
        Method m = LedgerController.class.getMethod("creditProvider", java.util.UUID.class, java.util.UUID.class, long.class);
        PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
        assertNotNull(pa);
        assertEquals("hasRole('ADMIN')", pa.value());
    }

    @Test
    void reviewsCreate_requiresConsumer() throws Exception {
        Method m = ReviewsController.class.getMethod("create", ReviewsController.CreateReviewRequest.class, org.springframework.security.core.Authentication.class);
        PreAuthorize pa = m.getAnnotation(PreAuthorize.class);
        assertNotNull(pa);
        assertEquals("hasRole('CONSUMER')", pa.value());
    }

    @Test
    void adminController_hasClassLevelAdmin() throws Exception {
        PreAuthorize pa = AdminController.class.getAnnotation(PreAuthorize.class);
        assertNotNull(pa);
        assertEquals("hasRole('ADMIN')", pa.value());
    }
}
