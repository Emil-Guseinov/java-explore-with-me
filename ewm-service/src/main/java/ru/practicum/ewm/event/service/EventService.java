package ru.practicum.ewm.event.service;

import lombok.RequiredArgsConstructor;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import ru.practicum.ewm.common.exception.BadRequestException;
import ru.practicum.ewm.event.dto.*;
import ru.practicum.ewm.event.model.Event;
import ru.practicum.ewm.event.model.EventSort;
import ru.practicum.ewm.event.repository.EventSpecifications;
import ru.practicum.ewm.stats.service.EventStatisticsService;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EventService {
    private final EventStorageService storage;
    private final EventViewsRanking ranking;
    private final EventStatisticsService statistics;
    private final Clock clock;

    public EventFullDto create(long userId, NewEventDto dto) {
        return storage.create(userId, dto);
    }

    public List<EventShortDto> ownEvents(long userId, int from, int size) {
        return enrichShort(storage.ownEvents(userId, from, size));
    }

    public EventFullDto ownEvent(long userId, long eventId) {
        return enrichFull(List.of(storage.ownEvent(userId, eventId))).getFirst();
    }

    public EventFullDto updateByUser(long userId, long eventId, UpdateEventUserRequest dto) {
        storage.prepareUserUpdate(userId, eventId);
        Map<Long, Long> views = statistics.views(List.of(eventId));
        EventFullDto result = storage.updateByUser(userId, eventId, dto);
        result.setViews(views.getOrDefault(eventId, 0L));
        return result;
    }

    public EventFullDto updateByAdmin(long eventId, UpdateEventAdminRequest dto) {
        storage.prepareAdminUpdate(eventId);
        Map<Long, Long> views = statistics.views(List.of(eventId));
        EventFullDto result = storage.updateByAdmin(eventId, dto);
        result.setViews(views.getOrDefault(eventId, 0L));
        return result;
    }

    public List<EventFullDto> adminEvents(AdminEventSearch search) {
        return enrichFull(storage.adminEvents(search));
    }

    public List<EventShortDto> publicEvents(PublicEventSearch search, String uri, String ip) {
        LocalDateTime start = search.getRangeStart();
        LocalDateTime end = search.getRangeEnd();
        if (start != null && end != null && start.isAfter(end)) {
            throw new BadRequestException("rangeStart must not be after rangeEnd");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Specification<Event> filter = EventSpecifications.publicSearch(search.getText(), search.getCategories(),
                search.getPaid(), start, end, search.isOnlyAvailable(), now);
        List<EventShortDto> result = search.getSort() == EventSort.VIEWS
                ? ranking.page(filter, search.getFrom(), search.getSize(), now)
                : enrichShort(storage.publicPage(filter, search.getFrom(), search.getSize()));
        statistics.hit(uri, ip);
        return result;
    }

    public EventFullDto publicEvent(long id, String uri, String ip) {
        EventFullDto result = storage.publicEvent(id);
        statistics.hit(uri, ip);
        return enrichFull(List.of(result)).getFirst();
    }

    private List<EventShortDto> enrichShort(List<EventShortDto> result) {
        if (!result.isEmpty()) {
            Map<Long, Long> views = statistics.views(result.stream().map(EventShortDto::getId).toList());
            result.forEach(event -> event.setViews(views.getOrDefault(event.getId(), 0L)));
        }
        return result;
    }

    private List<EventFullDto> enrichFull(List<EventFullDto> result) {
        if (!result.isEmpty()) {
            Map<Long, Long> views = statistics.views(result.stream().map(EventFullDto::getId).toList());
            result.forEach(event -> event.setViews(views.getOrDefault(event.getId(), 0L)));
        }
        return result;
    }
}
