package com.yourorg.qbank.core.port;

import com.yourorg.qbank.core.model.UserAccount;
import com.yourorg.qbank.core.model.UserRole;

import java.util.List;
import java.util.Optional;

public interface UserAccountRepository {
    UserAccount save(UserAccount user);
    Optional<UserAccount> findById(long id);
    Optional<UserAccount> findByUsernameIgnoreCase(String username);
    List<UserAccount> findAll();
    boolean existsByRole(UserRole role);
}
