package com.marketplace.identity;

import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID>, RevisionRepository<User, UUID, Integer> {

    Optional<User> findBySubject(String subject);

    Optional<User> findByEmail(String email);

    boolean existsBySubject(String subject);

    /**
     * I7 §9 (rotation row — resolved option 1, the keyring): existence check
     * across every candidate tombstone subject the keyring derives for one
     * raw subject (active + retained keys) — one statement for the
     * re-registration probe instead of one round trip per key. Derived query
     * ({@code Exists<Property>In}) per the official Spring Data JPA contract.
     */
    boolean existsBySubjectIn(Collection<String> subjects);
}