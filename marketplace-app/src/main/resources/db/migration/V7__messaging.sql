-- Conversations and messages tables
create table if not exists conversations (
    id              uuid primary key,
    booking_id      uuid references bookings(id),
    participant_a   uuid not null references users(id),
    participant_b   uuid not null references users(id),
    is_deleted      boolean not null default false,
    version         bigint not null default 0,
    created_by      varchar(200),
    created_at      timestamptz not null default now(),
    updated_by      varchar(200),
    updated_at      timestamptz not null default now()
);

create table if not exists messages (
    id              uuid primary key,
    conversation_id uuid not null references conversations(id),
    sender_id       uuid not null references users(id),
    content         text not null,
    read            boolean not null default false,
    is_deleted      boolean not null default false,
    version         bigint not null default 0,
    created_by      varchar(200),
    created_at      timestamptz not null default now(),
    updated_by      varchar(200),
    updated_at      timestamptz not null default now()
);

create index idx_conversations_participants on conversations (participant_a, participant_b) where is_deleted = false;
create index idx_conversations_booking on conversations (booking_id) where is_deleted = false and booking_id is not null;
create index idx_messages_conversation on messages (conversation_id, created_at) where is_deleted = false;
create index idx_messages_sender on messages (sender_id) where is_deleted = false;
create index idx_messages_unread on messages (conversation_id) where is_deleted = false and read = false;