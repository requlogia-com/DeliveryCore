package com.deliverycore.service;

import com.deliverycore.model.DeliveryDefinition;
import com.deliverycore.model.ScheduleConfig;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Resolves event windows and schedules their start and end callbacks. */
public class SchedulerServiceImpl implements SchedulerService {
    private static final Logger LOGGER = Logger.getLogger(SchedulerServiceImpl.class.getName());
    private static final Pattern RECURRING = Pattern.compile(
        "^every\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday|day)\\s+(\\d{1,2}):(\\d{2})$",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern RELATIVE = Pattern.compile("([1-9][0-9]{0,8})([smhdw])", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter ABSOLUTE = DateTimeFormatter
        .ofPattern("dd/MM/uuuu HH:mm[:ss]", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT);

    private final ScheduledExecutorService executor;
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new HashMap<>();
    private final Map<String, ScheduledEventInfo> scheduledEventInfos = new HashMap<>();
    private volatile Consumer<String> eventStartCallback;
    private volatile Consumer<String> eventEndCallback;

    public SchedulerServiceImpl(ScheduledExecutorService executor) {
        this.executor = executor;
    }

    public SchedulerServiceImpl() {
        this(null);
    }

    public void setEventStartCallback(Consumer<String> callback) {
        this.eventStartCallback = callback;
    }

    public void setEventEndCallback(Consumer<String> callback) {
        this.eventEndCallback = callback;
    }

    @Override
    public synchronized void scheduleEvent(DeliveryDefinition delivery) {
        if (executor == null) {
            LOGGER.warning("No executor configured, cannot schedule events");
            return;
        }
        cancelScheduledEvent(delivery.name());
        scheduleWindow(delivery, ZonedDateTime.now(delivery.timezone()));
    }

    private void scheduleWindow(DeliveryDefinition delivery, ZonedDateTime reference) {
        if (executor.isShutdown()) {
            return;
        }
        findWindow(delivery.schedule(), reference).ifPresent(window -> {
            String name = delivery.name();
            var info = new ScheduledEventInfo(name, window.start(), window.end(), delivery.timezone());
            scheduledEventInfos.put(name, info);
            scheduledTasks.put(name, executor.schedule(
                () -> startWindow(delivery, info), delayMillis(window.start()), TimeUnit.MILLISECONDS));
        });
    }

    private synchronized void startWindow(DeliveryDefinition delivery, ScheduledEventInfo info) {
        String name = delivery.name();
        if (scheduledEventInfos.get(name) != info || executor.isShutdown()) {
            return;
        }
        if (info.scheduledEnd().isAfter(ZonedDateTime.now(info.timezone()))) {
            invoke(eventStartCallback, name);
        }
        if (scheduledEventInfos.get(name) == info && !executor.isShutdown()) {
            scheduledTasks.put(name, executor.schedule(
                () -> endWindow(delivery, info), delayMillis(info.scheduledEnd()), TimeUnit.MILLISECONDS));
        }
    }

    private synchronized void endWindow(DeliveryDefinition delivery, ScheduledEventInfo info) {
        String name = delivery.name();
        if (scheduledEventInfos.get(name) != info || executor.isShutdown()) {
            return;
        }
        invoke(eventEndCallback, name);
        if (scheduledEventInfos.get(name) == info) {
            scheduledTasks.remove(name);
            scheduledEventInfos.remove(name);
            ZonedDateTime now = ZonedDateTime.now(info.timezone());
            scheduleWindow(delivery, now.isBefore(info.scheduledEnd()) ? info.scheduledEnd() : now);
        }
    }

    private static long delayMillis(ZonedDateTime time) {
        Duration remaining = Duration.between(ZonedDateTime.now(time.getZone()), time);
        return remaining.isNegative() || remaining.isZero() ? 0 : remaining.toMillis() + 1;
    }

    private static void invoke(Consumer<String> callback, String name) {
        if (callback == null) {
            return;
        }
        try {
            callback.accept(name);
        } catch (RuntimeException e) {
            LOGGER.warning("Schedule callback failed for " + name + ": " + e.getMessage());
        }
    }

    @Override
    public synchronized void cancelScheduledEvent(String deliveryName) {
        ScheduledFuture<?> task = scheduledTasks.remove(deliveryName);
        if (task != null) {
            task.cancel(false);
        }
        scheduledEventInfos.remove(deliveryName);
    }

    @Override
    public Optional<ZonedDateTime> parseScheduleExpression(String expression, ZoneId timezone) {
        if (expression == null || timezone == null) {
            return Optional.empty();
        }
        return resolveTime(expression, ZonedDateTime.now(timezone));
    }

    @Override
    public Optional<ZonedDateTime> getNextOccurrence(String expression, ZoneId timezone) {
        return parseScheduleExpression(expression, timezone);
    }

    @Override
    public synchronized Optional<ZonedDateTime> getEventEndTime(
            DeliveryDefinition delivery, ZonedDateTime actualStart, boolean manual) {
        ScheduledEventInfo planned = scheduledEventInfos.get(delivery.name());
        if (!manual && planned != null && !planned.scheduledStart().isAfter(actualStart)
                && planned.scheduledEnd().isAfter(actualStart)) {
            return Optional.of(planned.scheduledEnd());
        }
        return resolveTime(delivery.schedule().end(), actualStart).filter(end -> end.isAfter(actualStart));
    }

    @Override
    public synchronized void resumeActiveEvents() {
        // Stored event windows are started by their scheduled task, including windows currently in progress.
    }

    @Override
    public boolean isValidExpression(String expression) {
        return parseScheduleExpression(expression, ZoneOffset.UTC).isPresent();
    }

    public synchronized Map<String, ScheduledEventInfo> getScheduledEventInfos() {
        return Map.copyOf(scheduledEventInfos);
    }

    Optional<Window> findWindow(ScheduleConfig config, ZonedDateTime reference) {
        Optional<ZonedDateTime> anchor = absoluteTime(config.start(), reference.getZone());
        if (anchor.isPresent()) {
            return findAnchoredWindow(config, anchor.get(), reference);
        }

        Optional<RecurringTime> recurring = recurringTime(config.start());
        if (recurring.isEmpty() || !config.repeat().isBlank()) {
            return Optional.empty();
        }
        ZonedDateTime priorStart = recurring.get().previousOrSame(reference);
        Optional<Window> prior = windowFrom(priorStart, config.end());
        if (prior.isPresent() && prior.get().end().isAfter(reference)) {
            return prior;
        }
        return windowFrom(recurring.get().next(reference), config.end());
    }

    private Optional<Window> findAnchoredWindow(ScheduleConfig config, ZonedDateTime anchor, ZonedDateTime reference) {
        Optional<Window> initial = windowFrom(anchor, config.end());
        if (initial.isEmpty()) {
            return Optional.empty();
        }
        Duration length = Duration.between(anchor, initial.get().end());
        Optional<Duration> period = duration(config.repeat().isBlank() ? config.end() : config.repeat());
        if ((!config.repeat().isBlank() && period.isEmpty())
                || (period.isPresent() && period.get().compareTo(length) < 0)) {
            return Optional.empty();
        }
        if (period.isEmpty() || reference.isBefore(anchor)) {
            return initial.filter(window -> window.end().isAfter(reference));
        }

        Duration interval = period.get();
        long cycles = Duration.between(anchor, reference).dividedBy(interval);
        ZonedDateTime start = anchor.plus(interval.multipliedBy(cycles));
        if (!start.plus(length).isAfter(reference)) {
            start = start.plus(interval);
        }
        return Optional.of(new Window(start, start.plus(length)));
    }

    private static Optional<Window> windowFrom(ZonedDateTime start, String endExpression) {
        return resolveTime(endExpression, start)
            .filter(end -> end.isAfter(start))
            .map(end -> new Window(start, end));
    }

    private static Optional<ZonedDateTime> resolveTime(String expression, ZonedDateTime reference) {
        return duration(expression).map(reference::plus)
            .or(() -> absoluteTime(expression, reference.getZone()))
            .or(() -> recurringTime(expression).map(recurring -> recurring.next(reference)));
    }

    private static Optional<ZonedDateTime> absoluteTime(String expression, ZoneId zone) {
        String input = expression.trim();
        try {
            if (input.indexOf('/') >= 0) {
                return Optional.of(LocalDateTime.parse(input, ABSOLUTE).atZone(zone));
            }
            return Optional.of(LocalDateTime.parse(input.replace(' ', 'T')).atZone(zone));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static Optional<Duration> duration(String expression) {
        var matcher = RELATIVE.matcher(expression.trim().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        long amount = Long.parseLong(matcher.group(1));
        return Optional.of(switch (matcher.group(2)) {
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default -> Duration.ofDays(amount * 7);
        });
    }

    private static Optional<RecurringTime> recurringTime(String expression) {
        var matcher = RECURRING.matcher(expression.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int hour = Integer.parseInt(matcher.group(2));
        int minute = Integer.parseInt(matcher.group(3));
        if (hour > 23 || minute > 59) {
            return Optional.empty();
        }
        String day = matcher.group(1).toUpperCase(Locale.ROOT);
        return Optional.of(new RecurringTime("DAY".equals(day) ? null : DayOfWeek.valueOf(day),
            LocalTime.of(hour, minute)));
    }

    private record RecurringTime(DayOfWeek day, LocalTime time) {
        ZonedDateTime next(ZonedDateTime reference) {
            LocalDate date = reference.toLocalDate();
            if (day != null) {
                date = date.with(TemporalAdjusters.nextOrSame(day));
            }
            ZonedDateTime occurrence = date.atTime(time).atZone(reference.getZone());
            return occurrence.isAfter(reference) ? occurrence
                : date.plusDays(day == null ? 1 : 7).atTime(time).atZone(reference.getZone());
        }

        ZonedDateTime previousOrSame(ZonedDateTime reference) {
            LocalDate date = reference.toLocalDate();
            if (day != null) {
                date = date.with(TemporalAdjusters.previousOrSame(day));
            }
            ZonedDateTime occurrence = date.atTime(time).atZone(reference.getZone());
            return !occurrence.isAfter(reference) ? occurrence
                : date.minusDays(day == null ? 1 : 7).atTime(time).atZone(reference.getZone());
        }
    }

    record Window(ZonedDateTime start, ZonedDateTime end) {}

    public record ScheduledEventInfo(
        String deliveryName,
        ZonedDateTime scheduledStart,
        ZonedDateTime scheduledEnd,
        ZoneId timezone
    ) {}
}
