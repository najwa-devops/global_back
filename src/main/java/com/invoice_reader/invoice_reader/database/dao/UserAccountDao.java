package com.invoice_reader.invoice_reader.database.dao;

import com.invoice_reader.invoice_reader.database.entity.auth.UserAccount;
import com.invoice_reader.invoice_reader.database.entity.auth.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserAccountDao extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByUsername(String username);
    boolean existsByUsername(String username);
    List<UserAccount> findByRole(UserRole role);
}
