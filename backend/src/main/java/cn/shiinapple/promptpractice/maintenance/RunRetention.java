package cn.shiinapple.promptpractice.maintenance;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record RunRetention(Duration d0, Duration direct) {
    public RunRetention(
            @Value("${prompt-practice.retention.d0:24h}") Duration d0,
            @Value("${prompt-practice.retention.direct:10m}") Duration direct) {
        if (d0.toMillis() <= 0 || direct.toMillis() <= 0) {
            throw new IllegalArgumentException("Run retention must be at least one millisecond");
        }
        this.d0 = d0;
        this.direct = direct;
    }

    public long milliseconds(String mode) {
        return ("D0".equals(mode) ? d0 : direct).toMillis();
    }
}
