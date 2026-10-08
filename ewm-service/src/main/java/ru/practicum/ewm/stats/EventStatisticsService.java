package ru.practicum.ewm.stats;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.practicum.stats.client.StatsClient;
import ru.practicum.stats.dto.EndpointHit;
import ru.practicum.stats.dto.ViewStats;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EventStatisticsService {
    private static final String APP_NAME = "ewm-main-service";
    private static final LocalDateTime STATISTICS_START = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final int BATCH_SIZE = 100;
    private final StatsClient client;
    private final Clock clock;

    public void hit(String uri, String ip) {
        client.hit(new EndpointHit(null, APP_NAME, uri, ip, LocalDateTime.now(clock)));
    }

    public Map<Long, Long> views(Collection<Long> eventIds) {
        return views(eventIds, LocalDateTime.now(clock));
    }

    public Map<Long, Long> views(Collection<Long> eventIds, LocalDateTime end) {
        if (eventIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> idsByUri = new HashMap<>();
        eventIds.forEach(id -> idsByUri.put("/events/" + id, id));
        List<String> uris = new ArrayList<>(idsByUri.keySet());
        Map<Long, Long> views = new HashMap<>();
        for (int start = 0; start < uris.size(); start += BATCH_SIZE) {
            List<String> batch = uris.subList(start, Math.min(start + BATCH_SIZE, uris.size()));
            for (ViewStats stat : client.getStats(STATISTICS_START, end, batch, true)) {
                Long eventId = idsByUri.get(stat.getUri());
                if (APP_NAME.equals(stat.getApp()) && eventId != null) {
                    views.put(eventId, stat.getHits());
                }
            }
        }
        return views;
    }
}
