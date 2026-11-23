package com.deliverycore.service;

import com.deliverycore.model.ScheduleConfig;
import com.deliverycore.model.DeliveryDefinition;
import com.deliverycore.model.RewardConfig;
import com.deliverycore.model.SelectionConfig;
import com.deliverycore.model.WebhookConfig;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerWindowTest {
    private final ZoneId zone = ZoneId.of("Europe/Istanbul");
    private final SchedulerServiceImpl scheduler = new SchedulerServiceImpl();

    @Test
    void findsAnActiveDateWindowAndOmitsItAfterExpiry() {
        var config = new ScheduleConfig("23/11/2026 18:00:00", "2026-11-23T20:00:00");
        var current = ZonedDateTime.of(2026, 11, 23, 19, 0, 0, 0, zone);

        var window = scheduler.findWindow(config, current).orElseThrow();
        assertThat(window.start()).isEqualTo(current.minusHours(1));
        assertThat(window.end()).isEqualTo(current.plusHours(1));
        assertThat(scheduler.findWindow(config, current.plusHours(1))).isEmpty();
    }

    @Test
    void advancesAOneHourLoopFromItsConfiguredStart() {
        var config = new ScheduleConfig("2026-11-23T18:45:00", "1h");
        var current = ZonedDateTime.of(2026, 11, 23, 21, 5, 0, 0, zone);

        var window = scheduler.findWindow(config, current).orElseThrow();
        assertThat(window.start()).isEqualTo(current.minusMinutes(20));
        assertThat(window.end()).isEqualTo(current.plusMinutes(40));
    }

    @Test
    void preservesDailyWindowsAcrossMidnight() {
        var config = new ScheduleConfig("every day 23:00", "every day 01:00");
        var current = ZonedDateTime.of(2026, 11, 23, 0, 30, 0, 0, zone);

        var window = scheduler.findWindow(config, current).orElseThrow();
        assertThat(window.start()).isEqualTo(current.minusMinutes(90));
        assertThat(window.end()).isEqualTo(current.plusMinutes(30));
    }

    @Test
    void choosesTheNextWindowInAGapBetweenDateBasedRepeats() {
        var config = new ScheduleConfig("2026-11-23T18:00:00", "1h", "3h");
        var current = ZonedDateTime.of(2026, 11, 23, 20, 0, 0, 0, zone);

        var window = scheduler.findWindow(config, current).orElseThrow();
        assertThat(window.start()).isEqualTo(current.plusHours(1));
        assertThat(window.end()).isEqualTo(current.plusHours(2));
    }

    @Test
    void runsConsecutiveDurationWindowsUntilCancelled() throws InterruptedException {
        var executor = Executors.newSingleThreadScheduledExecutor();
        try {
            var runningScheduler = new SchedulerServiceImpl(executor);
            var anchor = ZonedDateTime.now(zone).toLocalDateTime();
            var schedule = new ScheduleConfig(anchor.toString(), "1s");
            var delivery = new DeliveryDefinition("farm", true, false, SelectionConfig.fixed("farm"),
                SelectionConfig.fixed("WHEAT"), zone, schedule, 1, RewardConfig.inventory("DIAMOND", 1),
                WebhookConfig.disabled());
            var starts = new AtomicInteger();
            var ends = new AtomicInteger();
            var secondStart = new CountDownLatch(1);
            runningScheduler.setEventEndCallback(name -> ends.incrementAndGet());
            runningScheduler.setEventStartCallback(name -> {
                if (starts.incrementAndGet() == 2) {
                    runningScheduler.cancelScheduledEvent(name);
                    secondStart.countDown();
                }
            });

            runningScheduler.scheduleEvent(delivery);

            assertThat(secondStart.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(ends).hasValue(1);
            assertThat(runningScheduler.getScheduledEventInfos()).isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }
}
