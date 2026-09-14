package com.marketplace.messaging;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L34 (realestate systems plan §5 — lead capture): the entity's own gates.
 * The type-gate philosophy (SearchCriteria/GeoLocation): an invalid lead
 * never becomes a row — blank/overlong contact fields and malformed phone
 * numbers fail at construction; the status machine is one-way.
 */
class ListingLeadTest {

    private static final UUID LISTING = UUID.randomUUID();
    private static final UUID PROVIDER = UUID.randomUUID();

    @Test
    void createPassesValidFieldsAndTrims() {
        ListingLead lead = ListingLead.create(LISTING, PROVIDER, null, null,
                "  Sami Ahmad  ", "+963991234567", "  Still available?  ");
        assertThat(lead.getContactName()).isEqualTo("Sami Ahmad");
        assertThat(lead.getContactPhone()).isEqualTo("+963991234567");
        assertThat(lead.getMessage()).isEqualTo("Still available?");
        assertThat(lead.getStatus()).isEqualTo(LeadStatus.NEW);
        assertThat(lead.getSenderUserId()).isNull();
        assertThat(lead.getSenderIpHash()).isNull();
    }

    @Test
    void createRejectsBlankFields() {
        assertThatThrownBy(() -> ListingLead.create(LISTING, PROVIDER, null, null,
                "   ", "+963991234567", "hello"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ListingLead.create(LISTING, PROVIDER, null, null,
                "Sami", "", "hello"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ListingLead.create(LISTING, PROVIDER, null, null,
                "Sami", "+963991234567", " "))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void createRejectsOverlongFields() {
        assertThatThrownBy(() -> ListingLead.create(LISTING, PROVIDER, null, null,
                "x".repeat(121), "+963991234567", "hello"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("contactName");
        assertThatThrownBy(() -> ListingLead.create(LISTING, PROVIDER, null, null,
                "Sami", "x".repeat(33), "hello"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ListingLead.create(LISTING, PROVIDER, null, null,
                "Sami", "+963991234567", "x".repeat(2001)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("message");
    }

    @Test
    void createRejectsMalformedPhone() {
        assertThatThrownBy(() -> ListingLead.create(LISTING, PROVIDER, null, null,
                "Sami", "not-a-phone", "hello"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("contactPhone");
        assertThatThrownBy(() -> ListingLead.create(LISTING, PROVIDER, null, null,
                "Sami", "12345", "hello"))  // below the 7-digit floor
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void createRejectsMalformedIpHash() {
        assertThatThrownBy(() -> ListingLead.create(LISTING, PROVIDER, null, "tooshort",
                "Sami", "+963991234567", "hello"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("senderIpHash");
    }

    @Test
    void statusMachineIsOneWay() {
        ListingLead lead = ListingLead.create(LISTING, PROVIDER, null, null,
                "Sami", "+963991234567", "hello");

        lead.transitionTo(LeadStatus.READ);
        assertThat(lead.getStatus()).isEqualTo(LeadStatus.READ);

        // READ cannot return to NEW.
        assertThatThrownBy(() -> lead.transitionTo(LeadStatus.NEW))
                .isInstanceOf(ConflictException.class);

        // READ → ARCHIVED is legal, ARCHIVED is terminal.
        lead.transitionTo(LeadStatus.ARCHIVED);
        assertThat(lead.getStatus()).isEqualTo(LeadStatus.ARCHIVED);
        assertThatThrownBy(() -> lead.transitionTo(LeadStatus.READ))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void newToArchivedDirectlyIsLegal() {
        ListingLead lead = ListingLead.create(LISTING, PROVIDER, null, null,
                "Sami", "+963991234567", "hello");
        lead.transitionTo(LeadStatus.ARCHIVED);
        assertThat(lead.getStatus()).isEqualTo(LeadStatus.ARCHIVED);
    }

    @Test
    void hashIpIsSha256HexOrAbsent() {
        assertThat(LeadsService.hashIp("203.0.113.9")).hasSize(64).matches("[0-9a-f]+");
        assertThat(LeadsService.hashIp("203.0.113.9"))
                .isEqualTo(LeadsService.hashIp("203.0.113.9"));  // deterministic
        assertThat(LeadsService.hashIp("203.0.113.10"))
                .isNotEqualTo(LeadsService.hashIp("203.0.113.9"));  // discriminating
        assertThat(LeadsService.hashIp(null)).isNull();
        assertThat(LeadsService.hashIp("  ")).isNull();
    }
}
