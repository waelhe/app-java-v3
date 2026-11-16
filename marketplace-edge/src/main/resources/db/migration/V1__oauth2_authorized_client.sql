CREATE TABLE oauth2_authorized_client (
  client_registration_id varchar(100) NOT NULL,
  principal_name varchar(200) NOT NULL,
  access_token_type varchar(100) NOT NULL,
  access_token_value bytea NOT NULL,
  -- timestamptz (CodeRabbit round-1 root adoption, citing the PostgreSQL
  -- datatype-datetime reference): the official JdbcOAuth2AuthorizedClientService
  -- schema ships a plain `timestamp`, and a JVM that writes the row in one
  -- default zone stores its WALL CLOCK — a JVM reading in another zone then
  -- shifts every token instant by the zone difference, so a token's expiry
  -- moves and refresh timing breaks. timestamptz stores the instant itself
  -- (normalized to UTC internally), so the official service's
  -- setTimestamp/getTimestamp round trip is exact under ANY writer/reader
  -- zone pair. The bytea adaptation above is the same PostgreSQL-specific
  -- translation of the official DDL (blob -> bytea).
  access_token_issued_at timestamptz NOT NULL,
  access_token_expires_at timestamptz NOT NULL,
  access_token_scopes varchar(1000) DEFAULT NULL,
  refresh_token_value bytea DEFAULT NULL,
  refresh_token_issued_at timestamptz DEFAULT NULL,
  created_at timestamptz DEFAULT CURRENT_TIMESTAMP NOT NULL,
  PRIMARY KEY (client_registration_id, principal_name)
);
