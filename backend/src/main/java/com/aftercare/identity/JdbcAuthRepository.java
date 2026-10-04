package com.aftercare.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import com.aftercare.identity.AuthRepository.RegisteredAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuthRepository implements AuthRepository {
    private static final String FIND_ACCOUNT = """
            SELECT t.id AS tenant_id, t.code AS merchant_code, t.status AS tenant_status,
                   u.id AS actor_id, u.username, u.password_hash, u.role, u.status AS user_status
              FROM tenants t
              JOIN users u ON u.tenant_id = t.id
             WHERE t.code = ? AND u.username = ?
            """;

    private final JdbcTemplate jdbc;

    public JdbcAuthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<RegisteredAccount> findAccount(String merchantCode, String username) {
        return jdbc.query(FIND_ACCOUNT, rs -> rs.next() ? Optional.of(toAccount(rs)) : Optional.empty(),
                merchantCode, username);
    }

    private RegisteredAccount toAccount(ResultSet rs) throws SQLException {
        return new RegisteredAccount(
                rs.getObject("tenant_id", java.util.UUID.class),
                rs.getString("merchant_code"),
                rs.getObject("actor_id", java.util.UUID.class),
                rs.getString("username"),
                rs.getString("password_hash"),
                Role.valueOf(rs.getString("role")),
                rs.getString("user_status"),
                rs.getString("tenant_status"));
    }
}
