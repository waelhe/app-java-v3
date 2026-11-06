package com.marketplace.identity;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.Objects;
import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696 — the
 * D-ADDR-001 adopted decision): the user-account address row — the single
 * identity store's own address book behind the Saleor Users-domain address
 * lifecycle ({@code accountAddress*}/{@code address*} mutations and the
 * {@code address} read root).
 *
 * <p>The country is the Saleor {@code CountryCode} enum's own two-letter
 * ISO 3166-1 alpha-2 vocabulary — validated at the GraphQL boundary by the
 * enum itself, stored as the raw code (no country registry table invented).
 * The default shipping/billing flags are the account's one-per-type
 * pointers; the owning use case serializes the flip so at most one live
 * row per type carries each flag.</p>
 */
@Entity
@Table(name = "user_addresses")
@Audited
public class AccountAddress extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "first_name", length = 200)
    private String firstName;

    @Column(name = "last_name", length = 200)
    private String lastName;

    @Column(name = "company_name", length = 200)
    private String companyName;

    @Column(name = "street_address_1", length = 500)
    private String streetAddress1;

    @Column(name = "street_address_2", length = 500)
    private String streetAddress2;

    @Column(name = "city", length = 200)
    private String city;

    @Column(name = "city_area", length = 200)
    private String cityArea;

    @Column(name = "postal_code", length = 40)
    private String postalCode;

    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @Column(name = "country_area", length = 200)
    private String countryArea;

    @Column(name = "phone", length = 60)
    private String phone;

    @Column(name = "is_default_shipping", nullable = false)
    private boolean defaultShipping;

    @Column(name = "is_default_billing", nullable = false)
    private boolean defaultBilling;

    protected AccountAddress() {
    }

    private AccountAddress(UUID id, UUID userId) {
        this.id = id;
        this.userId = userId;
    }

    /**
     * The creation factory — a fresh row for the owning account with the
     * caller-supplied field set applied verbatim (the use case validates
     * the pair before this runs).
     */
    public static AccountAddress create(UUID userId) {
        return new AccountAddress(UUID.randomUUID(), userId);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getCompanyName() {
        return companyName;
    }

    public String getStreetAddress1() {
        return streetAddress1;
    }

    public String getStreetAddress2() {
        return streetAddress2;
    }

    public String getCity() {
        return city;
    }

    public String getCityArea() {
        return cityArea;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getCountry() {
        return country;
    }

    public String getCountryArea() {
        return countryArea;
    }

    public String getPhone() {
        return phone;
    }

    public boolean isDefaultShipping() {
        return defaultShipping;
    }

    public boolean isDefaultBilling() {
        return defaultBilling;
    }

    /**
     * The address's own field set — null fields mean "no information"
     * (kept), matching {@code User.updateProfile}'s own semantics; the
     * use case validates the pair before this runs. Returns whether any
     * column actually changed.
     */
    boolean applyFields(String firstName, String lastName, String companyName,
                        String streetAddress1, String streetAddress2,
                        String city, String cityArea, String postalCode,
                        String country, String countryArea, String phone) {
        boolean changed = false;
        if (firstName != null && !Objects.equals(this.firstName, firstName.trim())) {
            this.firstName = firstName.trim();
            changed = true;
        }
        if (lastName != null && !Objects.equals(this.lastName, lastName.trim())) {
            this.lastName = lastName.trim();
            changed = true;
        }
        if (companyName != null && !Objects.equals(this.companyName, companyName.trim())) {
            this.companyName = companyName.trim();
            changed = true;
        }
        if (streetAddress1 != null && !Objects.equals(this.streetAddress1, streetAddress1.trim())) {
            this.streetAddress1 = streetAddress1.trim();
            changed = true;
        }
        if (streetAddress2 != null && !Objects.equals(this.streetAddress2, streetAddress2.trim())) {
            this.streetAddress2 = streetAddress2.trim();
            changed = true;
        }
        if (city != null && !Objects.equals(this.city, city.trim())) {
            this.city = city.trim();
            changed = true;
        }
        if (cityArea != null && !Objects.equals(this.cityArea, cityArea.trim())) {
            this.cityArea = cityArea.trim();
            changed = true;
        }
        if (postalCode != null && !Objects.equals(this.postalCode, postalCode.trim())) {
            this.postalCode = postalCode.trim();
            changed = true;
        }
        if (country != null && !Objects.equals(this.country, country)) {
            this.country = country;
            changed = true;
        }
        if (countryArea != null && !Objects.equals(this.countryArea, countryArea.trim())) {
            this.countryArea = countryArea.trim();
            changed = true;
        }
        if (phone != null && !Objects.equals(this.phone, phone.trim())) {
            this.phone = phone.trim();
            changed = true;
        }
        return changed;
    }

    void markDefaultShipping(boolean value) {
        this.defaultShipping = value;
    }

    void markDefaultBilling(boolean value) {
        this.defaultBilling = value;
    }
}
