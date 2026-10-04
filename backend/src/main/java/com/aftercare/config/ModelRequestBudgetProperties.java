package com.aftercare.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.model-request-budget")
public class ModelRequestBudgetProperties {
    private boolean enabled = false;
    private int maxAttempts = 50;
    private ModelRequestBudgetMode mode = ModelRequestBudgetMode.LIFETIME;
    private String dailyTimeZone = "Asia/Shanghai";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        if (maxAttempts < 1 || maxAttempts > 50) {
            throw new IllegalArgumentException("app.model-request-budget.max-attempts must be between 1 and 50");
        }
        this.maxAttempts = maxAttempts;
    }

    public ModelRequestBudgetMode getMode() {
        return mode;
    }

    public void setMode(ModelRequestBudgetMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("app.model-request-budget.mode is required");
        }
        this.mode = mode;
    }

    public String getDailyTimeZone() {
        return dailyTimeZone;
    }

    public void setDailyTimeZone(String dailyTimeZone) {
        java.time.ZoneId.of(dailyTimeZone);
        this.dailyTimeZone = dailyTimeZone;
    }
}
