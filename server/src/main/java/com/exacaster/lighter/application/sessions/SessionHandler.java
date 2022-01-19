package com.exacaster.lighter.application.sessions;

import static java.util.Optional.ofNullable;
import static net.javacrumbs.shedlock.core.LockAssert.assertLocked;
import static org.slf4j.LoggerFactory.getLogger;

import com.exacaster.lighter.application.Application;
import com.exacaster.lighter.application.ApplicationState;
import com.exacaster.lighter.application.ApplicationStatusHandler;
import com.exacaster.lighter.application.sessions.processors.StatementHandler;
import com.exacaster.lighter.backend.Backend;
import com.exacaster.lighter.configuration.AppConfiguration;
import com.exacaster.lighter.configuration.AppConfiguration.SessionConfiguration;
import com.exacaster.lighter.spark.SparkApp;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.scheduling.annotation.Async;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.javacrumbs.shedlock.micronaut.SchedulerLock;
import org.slf4j.Logger;

@Singleton
public class SessionHandler {

    private static final Logger LOG = getLogger(SessionHandler.class);

    private final SessionService sessionService;
    private final Backend backend;
    private final StatementHandler statementStatusChecker;
    private final ApplicationStatusHandler statusTracker;
    private final SessionConfiguration sessionConfiguration;

    public SessionHandler(SessionService sessionService,
            Backend backend,
            StatementHandler statementStatusChecker,
            ApplicationStatusHandler statusTracker,
            AppConfiguration appConfiguration) {
        this.sessionService = sessionService;
        this.backend = backend;
        this.statementStatusChecker = statementStatusChecker;
        this.statusTracker = statusTracker;
        this.sessionConfiguration = appConfiguration.getSessionConfiguration();
    }

    public void launch(Application application, Consumer<Throwable> errorHandler) {
        var app = new SparkApp(application.getSubmitParams(), errorHandler);
        app.launch(backend.getSubmitConfiguration(application));
    }

    @EventListener
    @Async
    public void startPermanentSession(StartupEvent event) {
        restartPermanentSession();
    }

    @SchedulerLock(name = "keepPermanentSession")
    @Scheduled(fixedRate = "1m")
    public void keepPermanentSession() {
        assertLocked();
        var sessionId = sessionConfiguration.getPermanentSessionId();
        if (sessionId == null) {
            return;
        }
        var session = sessionService.fetchOne(sessionId);
        if (session.isEmpty() || session.filter(it -> it.getState().isComplete()).isPresent()) {
            restartPermanentSession();
        }
    }

    private void restartPermanentSession() {
        var sessionId = sessionConfiguration.getPermanentSessionId();
        var params = sessionConfiguration.getPermanentSessionParams();
        if (sessionId != null && params != null) {
            sessionService.deleteOne(sessionId);
            launchSession(sessionService.createSession(params, sessionId));
        }
    }

    @SchedulerLock(name = "processScheduledSessions")
    @Scheduled(fixedRate = "1m")
    public void processScheduledSessions() {
        assertLocked();
        sessionService.fetchByState(ApplicationState.NOT_STARTED, 10).forEach(this::launchSession);
    }

    private void launchSession(Application session) {
        LOG.info("Launching {}", session);
        statusTracker.processApplicationStarting(session);
        launch(session, error -> statusTracker.processApplicationError(session, error));
    }

    @SchedulerLock(name = "trackRunningSessions")
    @Scheduled(fixedRate = "2m")
    public void trackRunning() {
        assertLocked();
        var running = sessionService.fetchRunning();

        var idleAndRunning = running.stream()
                .collect(Collectors.groupingBy(statementStatusChecker::hasWaitingStatement));

        selfOrEmpty(selfOrEmpty(idleAndRunning.get(false))).forEach(statusTracker::processApplicationIdle);
        selfOrEmpty(idleAndRunning.get(true)).forEach(statusTracker::processApplicationRunning);
    }

    @SchedulerLock(name = "handleTimeoutSessions")
    @Scheduled(fixedRate = "10m")
    public void handleTimeout() {
        assertLocked();
        var timeout = sessionConfiguration.getTimeoutMinutes();
        if (timeout != null) {
            sessionService.fetchRunning()
                    .stream()
                    .filter(s -> !s.getId().equals(sessionConfiguration.getPermanentSessionId()))
                    .filter(s -> s.getCreatedAt().isBefore(LocalDateTime.now().minusMinutes(timeout)))
                    .peek(s -> LOG.info("Killing because of timeout {}, session: {}", timeout, s))
                    .forEach(sessionService::killOne);
        }

    }

    private <T> List<T> selfOrEmpty(List<T> list) {
        return ofNullable(list).orElse(List.of());
    }
}
