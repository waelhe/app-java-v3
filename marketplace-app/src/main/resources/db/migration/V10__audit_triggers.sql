-- Audit triggers: auto-update updated_at on row modification
create or replace function set_updated_at()
returns trigger as $$
begin
    new.updated_at = now();
    return new;
end;
$$ language plpgsql;

-- Apply to all tables
create trigger trg_users_updated before update on users
    for each row execute function set_updated_at();
create trigger trg_provider_listings_updated before update on provider_listings
    for each row execute function set_updated_at();
create trigger trg_bookings_updated before update on bookings
    for each row execute function set_updated_at();
create trigger trg_pricing_rules_updated before update on pricing_rules
    for each row execute function set_updated_at();
create trigger trg_payment_intents_updated before update on payment_intents
    for each row execute function set_updated_at();
create trigger trg_payments_updated before update on payments
    for each row execute function set_updated_at();
create trigger trg_reviews_updated before update on reviews
    for each row execute function set_updated_at();
create trigger trg_conversations_updated before update on conversations
    for each row execute function set_updated_at();
create trigger trg_messages_updated before update on messages
    for each row execute function set_updated_at();