package com.harshil.movieticketbooking.common.config;

import com.harshil.movieticketbooking.notification.config.NotificationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The bounded executor that notification delivery runs on.
 * <p>
 * <b>Bounded on purpose.</b> An unbounded queue would let a burst of bookings
 * accumulate work faster than it drains until the heap gives out. With a fixed
 * capacity the pool refuses work instead - and refusing is safe here, because
 * the notification row is already committed with status PENDING before
 * dispatch is ever attempted. A rejected task loses nothing recoverable: the
 * row is still there, still PENDING, which is exactly what a durable outbox
 * poller would claim.
 * <p>
 * <b>Why neither stock rejection policy is right.</b> {@code CallerRunsPolicy}
 * would run delivery on the caller's thread - which, for an after-commit
 * listener, is the HTTP request thread. That is precisely the thing the brief
 * forbids. {@code AbortPolicy} throws {@code TaskRejectedException}, and
 * because the throw happens while submitting from inside an after-commit
 * callback, it would surface to the client as a 500 on a booking that
 * <em>actually succeeded and was committed</em>. So the handler logs and
 * discards: the request stays correct, and the undelivered row is still on
 * disk to be picked up.
 */
@Slf4j
@EnableAsync
@Configuration(proxyBeanMethods = false)
public class AsyncConfig {

    public static final String NOTIFICATION_EXECUTOR = "notificationExecutor";

    @Bean(name = NOTIFICATION_EXECUTOR)
    public ThreadPoolTaskExecutor notificationExecutor(NotificationProperties properties) {
        NotificationProperties.Executor config = properties.executor();

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(config.corePoolSize());
        executor.setMaxPoolSize(config.maxPoolSize());
        executor.setQueueCapacity(config.queueCapacity());
        executor.setThreadNamePrefix(config.threadNamePrefix());
        executor.setRejectedExecutionHandler((task, pool) -> log.error(
                "Notification executor saturated (queueCapacity={}); dispatch dropped. "
                        + "The notification row remains PENDING and is not lost.",
                config.queueCapacity()));

        // Drain in-flight deliveries on shutdown rather than dropping them.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds((int) config.awaitTermination().toSeconds());

        executor.initialize();

        log.info("Notification executor ready: core={} max={} queueCapacity={} prefix={}",
                config.corePoolSize(), config.maxPoolSize(), config.queueCapacity(), config.threadNamePrefix());
        return executor;
    }
}
