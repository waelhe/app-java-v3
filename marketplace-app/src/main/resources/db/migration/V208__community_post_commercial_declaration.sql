-- ADR-0011 (plan D-15 — DSA (EU) 2022/2065 Art. 26(2), the EUR-Lex text):
-- "Providers of online platforms shall provide recipients of the service
-- with a functionality to declare whether the content they provide is or
-- contains commercial communications. When the recipient of the service
-- submits a declaration pursuant to this paragraph, the provider of online
-- platforms shall ensure that other recipients of the service can identify
-- in a clear and unambiguous manner and in real time ... that the content
-- provided by the recipient of the service is or contains commercial
-- communications."
--
-- The community post is the recipient-provided content surface: the
-- author's self-declaration rides the post itself (additive, NOT NULL with
-- a false default — every existing row is undeclared and stays honest), and
-- NeighborhoodPostView carries it on every read (the real-time marking).
-- The declaration is the AUTHOR's own statement — the moderation layer
-- never flips it; there is no write path outside the publish factory.
ALTER TABLE neighborhood_posts
    ADD COLUMN declared_commercial boolean NOT NULL DEFAULT false;

-- The Envers mirror (the V24 pattern): the declaration is part of the
-- audited state — an undeclared-then-declared edit is history, not a
-- silent overwrite.
ALTER TABLE neighborhood_posts_aud
    ADD COLUMN declared_commercial boolean;
