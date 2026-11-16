package com.marketplace.messaging;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * L34 (realestate systems plan §5 — lead capture): a guest's or member's
 * contact message about one live listing, routed to the listing's
 * provider. The messaging module's second domain shape (the plan's chosen
 * home: person-to-person contact is this module's original story — the
 * lead is the same story without a booking).
 *
 * <p><b>Cross-module references (the V48/property_details discipline):</b>
 * {@code listingId} and {@code providerId} are plain UUID columns — no JPA
 * relationship and no FK across module borders; liveness and provider
 * ownership are resolved through {@code CatalogSpi} and the shared ports
 * at write time.
 *
 * <p><b>Type gate (the SearchCriteria/GeoLocation philosophy):</b> the
 * factory rejects blank and overlong contact fields before any persistence
 * — an invalid lead never becomes a row. Phone is the documented
 * E.164-tolerant shape: optional leading '+', 7-15 digits.
 */
@Entity
@Table(name = "listing_leads")
@Audited
public class ListingLead extends BaseEntity {

    /** Documented contact-field bounds (the request record mirrors them). */
    static final int NAME_MAX = 120;
    static final int PHONE_MAX = 32;
    static final int MESSAGE_MAX = 2000;
    static final String PHONE_PATTERN = "^\\+?[0-9]{7,15}$";

    @Id
    private UUID id;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    /** NULL for anonymous submissions; the user id when a valid JWT rode the request. */
    @Column(name = "sender_user_id")
    private UUID senderUserId;

    /** SHA-256 hex of the client IP (64 chars) — never the raw address; the G-R6 daily-cap key. */
    @Column(name = "sender_ip_hash", length = 64)
    private String senderIpHash;

    @Column(name = "contact_name", nullable = false, length = NAME_MAX)
    private String contactName;

    @Column(name = "contact_phone", nullable = false, length = PHONE_MAX)
    private String contactPhone;

    @Column(name = "message", nullable = false, length = MESSAGE_MAX)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private LeadStatus status = LeadStatus.NEW;

    protected ListingLead() {
    }

    private ListingLead(UUID id, UUID listingId, UUID providerId, UUID senderUserId,
                        String senderIpHash, String contactName, String contactPhone, String message) {
        this.id = id;
        this.listingId = listingId;
        this.providerId = providerId;
        this.senderUserId = senderUserId;
        this.senderIpHash = senderIpHash;
        this.contactName = contactName;
        this.contactPhone = contactPhone;
        this.message = message;
    }

    /**
     * The only construction path: every field passes the type gate first —
     * a blank name/phone/message or an overlong value is a 400 before any
     * write (D-R5: no silent truncation, no defaulting).
     */
    public static ListingLead create(UUID listingId, UUID providerId, UUID senderUserId,
                                     String senderIpHash, String contactName,
                                     String contactPhone, String message) {
        requireBounded("contactName", contactName, NAME_MAX);
        requireBounded("contactPhone", contactPhone, PHONE_MAX);
        requireBounded("message", message, MESSAGE_MAX);
        if (!contactPhone.trim().matches(PHONE_PATTERN)) {
            throw new BadRequestException(
                    "contactPhone must be 7-15 digits with an optional leading '+'");
        }
        if (senderIpHash != null && senderIpHash.length() != 64) {
            throw new BadRequestException("senderIpHash must be a 64-char SHA-256 hex digest");
        }
        return new ListingLead(UUID.randomUUID(), listingId, providerId, senderUserId,
                senderIpHash, contactName.trim(), contactPhone.trim(), message.trim());
    }

    private static void requireBounded(String field, String value, int max) {
        if (value == null || value.trim().isEmpty()) {
            throw new BadRequestException(field + " must not be blank");
        }
        if (value.trim().length() > max) {
            throw new BadRequestException(field + " must be at most " + max + " characters");
        }
    }

    /**
     * The provider's inbox move — one-way through {@link LeadStatus}; an
     * illegal transition is a conflict, not a silent rewrite.
     */
    public void transitionTo(LeadStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new ConflictException(
                    "Lead cannot move from " + status + " to " + target);
        }
        this.status = target;
    }

    @Override
    public UUID getId() { return id; }
    public UUID getListingId() { return listingId; }
    public UUID getProviderId() { return providerId; }
    public UUID getSenderUserId() { return senderUserId; }
    public String getSenderIpHash() { return senderIpHash; }
    public String getContactName() { return contactName; }
    public String getContactPhone() { return contactPhone; }
    public String getMessage() { return message; }
    public LeadStatus getStatus() { return status; }
}
