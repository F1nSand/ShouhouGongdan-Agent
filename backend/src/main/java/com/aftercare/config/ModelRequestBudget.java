package com.aftercare.config;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.aftercare.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

public class ModelRequestBudget {
    private final JdbcTemplate jdbcTemplate;
    private final int maxAttempts;
    private final ModelRequestBudgetMode mode;
    private final Clock clock;
    private final ZoneId dailyTimeZone;

    public ModelRequestBudget(JdbcTemplate jdbcTemplate, int maxAttempts) {
        this(jdbcTemplate, maxAttempts, ModelRequestBudgetMode.LIFETIME, Clock.systemUTC(), ZoneId.of("UTC"));
    }

    public ModelRequestBudget(JdbcTemplate jdbcTemplate, int maxAttempts, ModelRequestBudgetMode mode,
            Clock clock, ZoneId dailyTimeZone) {
        if (maxAttempts < 1 || maxAttempts > 50) {
            throw new IllegalArgumentException("Model request budget must be between 1 and 50 attempts");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.maxAttempts = maxAttempts;
        this.mode = java.util.Objects.requireNonNull(mode);
        this.clock = java.util.Objects.requireNonNull(clock);
        this.dailyTimeZone = java.util.Objects.requireNonNull(dailyTimeZone);
    }

    public long reserveAttempt() {
        if (mode == ModelRequestBudgetMode.DAILY) {
            return reserveDailyAttempt();
        }
        Long reservedAttempt = jdbcTemplate.query("""
                UPDATE model_request_budget
                SET attempts = attempts + 1, updated_at = CURRENT_TIMESTAMP
                WHERE singleton_id = 1 AND attempts < ?
                RETURNING attempts
                """, resultSet -> resultSet.next() ? resultSet.getLong(1) : null, maxAttempts);
        if (reservedAttempt == null) {
            throw ModelRequestBudgetExceededException.lifetime(maxAttempts);
        }
        return reservedAttempt;
    }

    private long reserveDailyAttempt() {
        LocalDate budgetDate = LocalDate.now(clock.withZone(dailyTimeZone));
        Long reservedAttempt = jdbcTemplate.query("""
                INSERT INTO model_request_budget_daily (budget_date, attempts)
                VALUES (?, 1)
                ON CONFLICT (budget_date) DO UPDATE
                    SET attempts = model_request_budget_daily.attempts + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE model_request_budget_daily.attempts < ?
                RETURNING attempts
                """, resultSet -> resultSet.next() ? resultSet.getLong(1) : null, budgetDate, maxAttempts);
        if (reservedAttempt == null) {
            Instant resetAt = budgetDate.plusDays(1).atStartOfDay(dailyTimeZone).toInstant();
            throw ModelRequestBudgetExceededException.daily(maxAttempts, resetAt, dailyTimeZone);
        }
        return reservedAttempt;
    }

    public static final class ModelRequestBudgetExceededException extends ApiException {
        private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        private final Instant resetAt;

        private ModelRequestBudgetExceededException(String code, String message, Instant resetAt) {
            super(code, message, HttpStatus.TOO_MANY_REQUESTS);
            this.resetAt = resetAt;
        }

        static ModelRequestBudgetExceededException lifetime(int maxAttempts) {
            return new ModelRequestBudgetExceededException("MODEL_LIFETIME_BUDGET_EXHAUSTED",
                    "Cumulative model request budget exhausted at " + maxAttempts + " attempts", null);
        }

        static ModelRequestBudgetExceededException daily(int maxAttempts, Instant resetAt, ZoneId zone) {
            String resetText = DISPLAY_TIME.withZone(zone).format(resetAt);
            return new ModelRequestBudgetExceededException("MODEL_DAILY_BUDGET_EXHAUSTED",
                    "Daily model request budget exhausted at " + maxAttempts
                            + " attempts; reset at " + resetText + " " + zone, resetAt);
        }

        public Instant resetAt() {
            return resetAt;
        }
    }
}
