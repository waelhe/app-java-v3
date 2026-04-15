create table if not exists users (
    id uuid primary key,
    subject varchar(200) not null unique,
    email varchar(320),
    display_name varchar(200),
    role varchar(30) not null,
    created_at timestamptz not null default now()
);

