package ru.practicum.ewm.event.service;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.practicum.ewm.category.repository.CategoryRepository;
import ru.practicum.ewm.common.exception.BadRequestException;
import ru.practicum.ewm.common.exception.ConflictException;
import ru.practicum.ewm.common.exception.NotFoundException;
import ru.practicum.ewm.common.pagination.OffsetPageRequest;
import ru.practicum.ewm.event.dto.*;
import ru.practicum.ewm.event.mapper.EventMapper;
import ru.practicum.ewm.event.model.Event;
import ru.practicum.ewm.event.model.EventState;
import ru.practicum.ewm.event.repository.EventRepository;
import ru.practicum.ewm.event.repository.EventSpecifications;
import ru.practicum.ewm.request.model.RequestStatus;
import ru.practicum.ewm.request.repository.ParticipationRequestRepository;
import ru.practicum.ewm.user.repository.UserRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventStorageService {
    private static final Sort EVENT_ORDER = Sort.by("eventDate").ascending().and(Sort.by("id"));
    private final EventRepository events;
    private final UserRepository users;
    private final CategoryRepository categories;
    private final ParticipationRequestRepository requests;
    private final Clock clock;

    @Transactional
    public EventFullDto create(long userId, NewEventDto dto) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (dto.getEventDate().isBefore(now.plusHours(2))) {
            throw new BadRequestException("Event date must be at least two hours from now");
        }
        Event event = new Event();
        event.setInitiator(users.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId)));
        event.setCategory(categories.findById(dto.getCategory())
                .orElseThrow(() -> new NotFoundException("Category not found: " + dto.getCategory())));
        event.setAnnotation(dto.getAnnotation());
        event.setDescription(dto.getDescription());
        event.setTitle(dto.getTitle());
        event.setEventDate(dto.getEventDate());
        event.setLocation(EventMapper.toLocation(dto.getLocation()));
        event.setPaid(Boolean.TRUE.equals(dto.getPaid()));
        event.setParticipantLimit(dto.getParticipantLimit() == null ? 0 : dto.getParticipantLimit());
        event.setRequestModeration(dto.getRequestModeration() == null || dto.getRequestModeration());
        event.setCreatedOn(now);
        event.setState(EventState.PENDING);
        return EventMapper.toFullDto(events.save(event), 0, 0);
    }

    public List<EventShortDto> ownEvents(long userId, int from, int size) {
        requireUser(userId);
        return shortDtos(events.findPage(EventSpecifications.ownedBy(userId),
                new OffsetPageRequest(from, size, Sort.by("id"))));
    }

    public EventFullDto ownEvent(long userId, long eventId) {
        requireUser(userId);
        Event event = getEvent(eventId);
        requireOwner(event, userId);
        return fullDto(event);
    }

    @Transactional
    public EventFullDto updateByUser(long userId, long eventId, UpdateEventUserRequest dto) {
        requireUser(userId);
        Event event = lockEvent(eventId);
        requireOwner(event, userId);
        if (event.getState() == EventState.PUBLISHED) {
            throw new ConflictException("Only pending or canceled events can be changed");
        }
        LocalDateTime date = dto.getEventDate() == null ? event.getEventDate() : dto.getEventDate();
        if (date.isBefore(LocalDateTime.now(clock).plusHours(2))) {
            throw new ConflictException("Event date must be at least two hours from now");
        }
        applyChanges(event, dto);
        if (dto.getStateAction() == UpdateEventUserRequest.StateAction.SEND_TO_REVIEW) {
            event.setState(EventState.PENDING);
        } else if (dto.getStateAction() == UpdateEventUserRequest.StateAction.CANCEL_REVIEW) {
            event.setState(EventState.CANCELED);
        }
        return fullDto(event);
    }

    @Transactional
    public EventFullDto updateByAdmin(long eventId, UpdateEventAdminRequest dto) {
        Event event = lockEvent(eventId);
        LocalDateTime now = LocalDateTime.now(clock);
        boolean publish = dto.getStateAction() == UpdateEventAdminRequest.StateAction.PUBLISH_EVENT;
        boolean reject = dto.getStateAction() == UpdateEventAdminRequest.StateAction.REJECT_EVENT;
        if (publish && event.getState() != EventState.PENDING) {
            throw new ConflictException("Only pending events can be published");
        }
        if (reject && event.getState() == EventState.PUBLISHED) {
            throw new ConflictException("A published event cannot be rejected");
        }
        if (publish || dto.getEventDate() != null) {
            LocalDateTime date = dto.getEventDate() == null ? event.getEventDate() : dto.getEventDate();
            LocalDateTime publication = publish || event.getPublishedOn() == null ? now : event.getPublishedOn();
            if (date.isBefore(publication.plusHours(1))) {
                throw new ConflictException("Event date must be at least one hour after publication");
            }
        }
        applyChanges(event, dto);
        if (publish) {
            event.setState(EventState.PUBLISHED);
            event.setPublishedOn(now);
        } else if (reject) {
            event.setState(EventState.CANCELED);
        }
        return fullDto(event);
    }

    public List<EventFullDto> adminEvents(AdminEventSearch search) {
        validateRange(search.getRangeStart(), search.getRangeEnd());
        List<Event> found = events.findPage(EventSpecifications.adminSearch(search.getUsers(), search.getStates(),
                search.getCategories(), search.getRangeStart(), search.getRangeEnd()),
                new OffsetPageRequest(search.getFrom(), search.getSize(), EVENT_ORDER));
        return fullDtos(found);
    }

    public List<Long> candidateIds(Specification<Event> filter, long afterId, int size) {
        if (size < 1) {
            throw new BadRequestException("Batch size must be positive");
        }
        return events.findCandidateIds(filter, afterId, size);
    }

    public List<EventShortDto> publicPage(Specification<Event> filter, int from, int size) {
        return shortDtos(events.findPage(filter, new OffsetPageRequest(from, size, EVENT_ORDER)));
    }

    public List<EventShortDto> shortDtosByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return shortDtos(events.findAllById(ids));
    }

    public EventFullDto publicEvent(long id) {
        Event event = getEvent(id);
        if (event.getState() != EventState.PUBLISHED) {
            throw new NotFoundException("Published event not found: " + id);
        }
        return fullDto(event);
    }

    public void prepareUserUpdate(long userId, long eventId) {
        requireUser(userId);
        requireOwner(getEvent(eventId), userId);
    }

    public void prepareAdminUpdate(long eventId) {
        if (!events.existsById(eventId)) {
            throw new NotFoundException("Event not found: " + eventId);
        }
    }

    public List<EventShortDto> shortDtos(List<Event> source) {
        if (source.isEmpty()) {
            return List.of();
        }
        List<Long> ids = source.stream().map(Event::getId).toList();
        Map<Long, Long> confirmed = confirmedCounts(ids);
        return source.stream().map(event -> EventMapper.toShortDto(event,
                confirmed.getOrDefault(event.getId(), 0L), 0)).toList();
    }

    private List<EventFullDto> fullDtos(List<Event> source) {
        if (source.isEmpty()) {
            return List.of();
        }
        List<Long> ids = source.stream().map(Event::getId).toList();
        Map<Long, Long> confirmed = confirmedCounts(ids);
        return source.stream().map(event -> EventMapper.toFullDto(event,
                confirmed.getOrDefault(event.getId(), 0L), 0)).toList();
    }

    private EventFullDto fullDto(Event event) {
        return fullDtos(List.of(event)).getFirst();
    }

    private Map<Long, Long> confirmedCounts(Collection<Long> ids) {
        Map<Long, Long> result = new HashMap<>();
        requests.counts(ids, RequestStatus.CONFIRMED).forEach(count -> result.put(count.getEventId(), count.getTotal()));
        return result;
    }

    private void applyChanges(Event event, EventUpdateRequest dto) {
        if (dto.getAnnotation() != null) {
            event.setAnnotation(dto.getAnnotation());
        }
        if (dto.getDescription() != null) {
            event.setDescription(dto.getDescription());
        }
        if (dto.getTitle() != null) {
            event.setTitle(dto.getTitle());
        }
        if (dto.getCategory() != null) {
            event.setCategory(categories.findById(dto.getCategory())
                    .orElseThrow(() -> new NotFoundException("Category not found: " + dto.getCategory())));
        }
        if (dto.getEventDate() != null) {
            event.setEventDate(dto.getEventDate());
        }
        if (dto.getLocation() != null) {
            event.setLocation(EventMapper.toLocation(dto.getLocation()));
        }
        if (dto.getPaid() != null) {
            event.setPaid(dto.getPaid());
        }
        if (dto.getParticipantLimit() != null) {
            long confirmed = requests.countByEventIdAndStatus(event.getId(), RequestStatus.CONFIRMED);
            if (dto.getParticipantLimit() != 0 && dto.getParticipantLimit() < confirmed) {
                throw new ConflictException("Participant limit cannot be below the confirmed request count");
            }
            event.setParticipantLimit(dto.getParticipantLimit());
        }
        if (dto.getRequestModeration() != null) {
            event.setRequestModeration(dto.getRequestModeration());
        }
    }

    private Event getEvent(long id) {
        return events.findById(id).orElseThrow(() -> new NotFoundException("Event not found: " + id));
    }

    private Event lockEvent(long id) {
        return events.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Event not found: " + id));
    }

    private void requireUser(long id) {
        if (!users.existsById(id)) {
            throw new NotFoundException("User not found: " + id);
        }
    }

    private void requireOwner(Event event, long userId) {
        if (!event.getInitiator().getId().equals(userId)) {
            throw new NotFoundException("Event not found for user: " + userId);
        }
    }

    private void validateRange(LocalDateTime start, LocalDateTime end) {
        if (start != null && end != null && start.isAfter(end)) {
            throw new BadRequestException("rangeStart must not be after rangeEnd");
        }
    }
}
