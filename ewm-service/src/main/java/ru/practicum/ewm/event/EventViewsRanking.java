package ru.practicum.ewm.event;

import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import ru.practicum.ewm.event.dto.EventShortDto;
import ru.practicum.ewm.stats.EventStatisticsService;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EventViewsRanking {
    private static final int BATCH_SIZE = 100;
    private static final Comparator<RankedEvent> BEST_FIRST = Comparator.comparingLong(RankedEvent::views)
            .reversed().thenComparingLong(RankedEvent::id);
    private final EventRepository events;
    private final EventStorageService storage;
    private final EventStatisticsService statistics;

    public List<EventShortDto> page(Specification<Event> filter, int from, int size, LocalDateTime cutoff) {
        Long maximumId = events.findMaximumId().orElse(null);
        if (maximumId == null) {
            return List.of();
        }
        Specification<Event> bounded = filter.and((root, query, builder) ->
                builder.lessThanOrEqualTo(root.get("id"), maximumId));
        long capacity = (long) from + size;
        PriorityQueue<RankedEvent> best = new PriorityQueue<>(BEST_FIRST.reversed());
        long afterId = 0;
        while (afterId < maximumId) {
            List<Long> ids = events.findCandidateIds(bounded, afterId, BATCH_SIZE);
            if (ids.isEmpty()) {
                break;
            }
            Map<Long, Long> views = statistics.views(ids, cutoff);
            for (Long id : ids) {
                RankedEvent candidate = new RankedEvent(id, views.getOrDefault(id, 0L));
                if (best.size() < capacity) {
                    best.add(candidate);
                } else if (BEST_FIRST.compare(candidate, best.element()) < 0) {
                    best.remove();
                    best.add(candidate);
                }
            }
            afterId = ids.getLast();
        }
        List<RankedEvent> page = best.stream().sorted(BEST_FIRST).skip(from).limit(size).toList();
        Map<Long, EventShortDto> details = storage.shortDtosByIds(page.stream().map(RankedEvent::id).toList())
                .stream().collect(Collectors.toMap(EventShortDto::getId, Function.identity()));
        return page.stream().filter(event -> details.containsKey(event.id())).map(event -> {
            EventShortDto dto = details.get(event.id());
            dto.setViews(event.views());
            return dto;
        }).toList();
    }

    private record RankedEvent(long id, long views) {
    }
}
