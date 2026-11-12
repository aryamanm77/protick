package com.yourorg.qbank.core.port;

import com.yourorg.qbank.core.model.UserSessionToken;

import java.time.Instant;
import java.util.Optional;

public interface UserSessionRepository {
    void save(UserSessionToken session);
    Optional<UserSessionToken> findByTokenHash(String tokenHash);
    void deleteByTokenHash(String tokenHash);
    void deleteByUserId(long userId);
    void deleteExpired(Instant now);
}
