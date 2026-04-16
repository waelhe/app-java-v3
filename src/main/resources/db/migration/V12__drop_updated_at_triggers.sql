-- Drop redundant updated_at triggers — JPA AuditingEntityListener manages updated_at via @LastModifiedDate
drop trigger if exists trg_users_updated on users;
drop trigger if exists trg_provider_listings_updated on provider_listings;
drop trigger if exists trg_bookings_updated on bookings;
drop trigger if exists trg_pricing_rules_updated on pricing_rules;
drop trigger if exists trg_payment_intents_updated on payment_intents;
drop trigger if exists trg_payments_updated on payments;
drop trigger if exists trg_reviews_updated on reviews;
drop trigger if exists trg_conversations_updated on conversations;
drop trigger if exists trg_messages_updated on messages;

-- Drop the trigger function itself (no longer needed)
drop function if exists set_updated_at();