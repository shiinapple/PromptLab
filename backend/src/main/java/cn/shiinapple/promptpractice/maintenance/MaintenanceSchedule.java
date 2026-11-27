package cn.shiinapple.promptpractice.maintenance;

import cn.shiinapple.promptpractice.auth.Sessions;
import cn.shiinapple.promptpractice.practice.PracticeError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableScheduling
class MaintenanceSchedule {
    private static final Logger LOG = LoggerFactory.getLogger(MaintenanceSchedule.class);
    private final RunMaintenance runs;
    private final Sessions sessions;

    MaintenanceSchedule(RunMaintenance runs, Sessions sessions) {
        this.runs = runs;
        this.sessions = sessions;
    }

    @Scheduled(initialDelayString = "${prompt-practice.maintenance.interval:60s}",
            fixedDelayString = "${prompt-practice.maintenance.interval:60s}")
    void maintain() {
        try {
            runs.sweep();
        } catch (PracticeError error) {
            if (!"TEMPORARILY_UNAVAILABLE".equals(error.getMessage())) throw error;
            LOG.warn("Run maintenance will retry after SQLite is busy");
        }
    }

    @Scheduled(initialDelayString = "${prompt-practice.maintenance.session-recheck:5s}",
            fixedDelayString = "${prompt-practice.maintenance.session-recheck:5s}")
    void revokeSessions() {
        sessions.revokeInactive();
    }
}
