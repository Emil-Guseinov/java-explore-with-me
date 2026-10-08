package ru.practicum.ewm.stats.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import ru.practicum.ewm.event.repository.EventPublication;
import ru.practicum.ewm.event.repository.EventRepository;
import ru.practicum.stats.client.StatsClient;
import ru.practicum.stats.dto.EndpointHit;
import ru.practicum.stats.dto.ViewStats;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class EventStatisticsService {
    private static final int BATCH_SIZE = 100;
    private final StatsClient client;
    private final EventRepository events;
    private final Clock clock;
    private final String appName;

    public EventStatisticsService(StatsClient client, EventRepository events, Clock clock,
            @Value("${spring.application.name}") String appName) {
        this.client = client;
        this.events = events;
        this.clock = clock;
        this.appName = appName;
    }

    public void hit(String uri, String ip) {
        client.hit(new EndpointHit(null, appName, uri, ip, LocalDateTime.now(clock)));
    }

    public Map<Long, Long> views(Collection<Long> eventIds) {
        return views(eventIds, LocalDateTime.now(clock));
    }

    public Map<Long, Long> views(Collection<Long> eventIds, LocalDateTime end) {
        if (eventIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = eventIds.stream().distinct().toList();
        Map<Long, Long> views = new HashMap<>();
        for (int offset = 0; offset < ids.size(); offset += BATCH_SIZE) {
            List<Long> batch = ids.subList(offset, Math.min(offset + BATCH_SIZE, ids.size()));
            List<EventPublication> publications = events.findByIdInAndPublishedOnIsNotNull(batch).stream()
                    .filter(event -> !event.getPublishedOn().isAfter(end)).toList();
            if (publications.isEmpty()) {
                continue;
            }
            LocalDateTime start = publications.stream().map(EventPublication::getPublishedOn)
                    .min(LocalDateTime::compareTo).orElseThrow();
            Map<String, Long> idsByUri = new HashMap<>();
            publications.forEach(event -> idsByUri.put("/events/" + event.getId(), event.getId()));
            List<String> uris = publications.stream().map(event -> "/events/" + event.getId()).toList();
            for (ViewStats stat : client.getStats(start, end, uris, true)) {
                Long eventId = idsByUri.get(stat.getUri());
                if (appName.equals(stat.getApp()) && eventId != null) {
                    views.put(eventId, stat.getHits());
                }
            }
        }
        return views;
    }
}
