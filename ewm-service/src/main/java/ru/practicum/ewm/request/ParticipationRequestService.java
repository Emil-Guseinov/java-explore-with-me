package ru.practicum.ewm.request;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.ewm.common.ConflictException;
import ru.practicum.ewm.common.NotFoundException;
import ru.practicum.ewm.event.Event;
import ru.practicum.ewm.event.EventRepository;
import ru.practicum.ewm.event.EventState;
import ru.practicum.ewm.request.dto.EventRequestStatusUpdateRequest;
import ru.practicum.ewm.request.dto.EventRequestStatusUpdateResult;
import ru.practicum.ewm.request.dto.ParticipationRequestDto;
import ru.practicum.ewm.request.dto.RequestStatusAction;
import ru.practicum.ewm.user.User;
import ru.practicum.ewm.user.UserRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ParticipationRequestService {
    private final ParticipationRequestRepository requests;
    private final EventRepository events;
    private final UserRepository users;
    private final Clock clock;

    public List<ParticipationRequestDto> getUserRequests(Long userId) {
        requireUser(userId);
        return requests.findByRequesterIdOrderByIdAsc(userId).stream()
                .map(ParticipationRequestMapper::toDto)
                .toList();
    }

    public List<ParticipationRequestDto> getEventRequests(Long userId, Long eventId) {
        requireUser(userId);
        Event event = events.findById(eventId).orElseThrow(() -> eventNotFound(eventId));
        requireOwner(event, userId);
        return requests.findByEventIdOrderByIdAsc(eventId).stream()
                .map(ParticipationRequestMapper::toDto)
                .toList();
    }

    @Transactional
    public ParticipationRequestDto create(Long userId, Long eventId) {
        User requester = requireUser(userId);
        Event event = lockEvent(eventId);
        if (requests.existsByEventIdAndRequesterId(eventId, userId)) {
            throw new ConflictException("Participation request already exists");
        }
        if (event.getInitiator().getId().equals(userId)) {
            throw new ConflictException("The event initiator cannot participate in their own event");
        }
        if (event.getState() != EventState.PUBLISHED) {
            throw new ConflictException("Only published events accept participation requests");
        }
        long confirmed = requests.countByEventIdAndStatus(eventId, RequestStatus.CONFIRMED);
        if (event.getParticipantLimit() > 0 && confirmed >= event.getParticipantLimit()) {
            throw new ConflictException("The participant limit has been reached");
        }
        ParticipationRequest request = new ParticipationRequest();
        request.setEvent(event);
        request.setRequester(requester);
        request.setCreated(LocalDateTime.now(clock));
        request.setStatus(event.getParticipantLimit() == 0 || !event.getRequestModeration()
                ? RequestStatus.CONFIRMED : RequestStatus.PENDING);
        requests.save(request);
        ParticipationRequestDto result = ParticipationRequestMapper.toDto(request);
        if (request.getStatus() == RequestStatus.CONFIRMED && event.getParticipantLimit() > 0
                && confirmed + 1 == event.getParticipantLimit()) {
            requests.updateStatusByEventIdAndStatus(eventId, RequestStatus.PENDING, RequestStatus.REJECTED);
        }
        return result;
    }

    @Transactional
    public ParticipationRequestDto cancel(Long userId, Long requestId) {
        requireUser(userId);
        Long eventId = requests.findEventId(requestId, userId)
                .orElseThrow(() -> requestNotFound(requestId));
        lockEvent(eventId);
        ParticipationRequest request = requests.findByIdAndRequesterId(requestId, userId)
                .orElseThrow(() -> requestNotFound(requestId));
        request.setStatus(RequestStatus.CANCELED);
        return ParticipationRequestMapper.toDto(request);
    }

    @Transactional
    public EventRequestStatusUpdateResult updateStatus(Long userId, Long eventId,
                                                       EventRequestStatusUpdateRequest update) {
        requireUser(userId);
        Event event = lockEvent(eventId);
        requireOwner(event, userId);
        Set<Long> ids = new LinkedHashSet<>(update.requestIds());
        Map<Long, ParticipationRequest> selected = requests.findByEventIdAndIdInOrderByIdAsc(eventId, ids).stream()
                .collect(Collectors.toMap(ParticipationRequest::getId, Function.identity()));
        for (Long id : ids) {
            ParticipationRequest request = selected.get(id);
            if (request == null) {
                throw requestNotFound(id);
            }
            if (request.getStatus() != RequestStatus.PENDING) {
                throw new ConflictException("Request " + id + " must have status PENDING");
            }
        }
        List<ParticipationRequestDto> confirmedRequests = new ArrayList<>();
        List<ParticipationRequestDto> rejectedRequests = new ArrayList<>();
        if (update.status() == RequestStatusAction.REJECTED) {
            for (Long id : ids) {
                ParticipationRequest request = selected.get(id);
                request.setStatus(RequestStatus.REJECTED);
                rejectedRequests.add(ParticipationRequestMapper.toDto(request));
            }
            return new EventRequestStatusUpdateResult(confirmedRequests, rejectedRequests);
        }
        long confirmed = requests.countByEventIdAndStatus(eventId, RequestStatus.CONFIRMED);
        int limit = event.getParticipantLimit();
        if (limit > 0 && confirmed >= limit) {
            throw new ConflictException("The participant limit has been reached");
        }
        for (Long id : ids) {
            if (limit > 0 && confirmed >= limit) {
                break;
            }
            ParticipationRequest request = selected.get(id);
            request.setStatus(RequestStatus.CONFIRMED);
            confirmedRequests.add(ParticipationRequestMapper.toDto(request));
            confirmed++;
        }
        if (limit > 0 && confirmed == limit) {
            rejectedRequests.addAll(rejectPending(eventId));
        }
        return new EventRequestStatusUpdateResult(confirmedRequests, rejectedRequests);
    }

    private List<ParticipationRequestDto> rejectPending(Long eventId) {
        requests.flush();
        List<ParticipationRequestDto> rejected = requests.findDtosByEventIdAndStatus(eventId, RequestStatus.PENDING)
                .stream()
                .map(request -> new ParticipationRequestDto(request.created(), request.event(), request.id(),
                        request.requester(), RequestStatus.REJECTED))
                .toList();
        requests.updateStatusByEventIdAndStatus(eventId, RequestStatus.PENDING, RequestStatus.REJECTED);
        return rejected;
    }

    private Event lockEvent(Long eventId) {
        return events.findByIdForUpdate(eventId).orElseThrow(() -> eventNotFound(eventId));
    }

    private User requireUser(Long userId) {
        return users.findById(userId)
                .orElseThrow(() -> new NotFoundException("User with id=" + userId + " was not found"));
    }

    private void requireOwner(Event event, Long userId) {
        if (!event.getInitiator().getId().equals(userId)) {
            throw eventNotFound(event.getId());
        }
    }

    private NotFoundException eventNotFound(Long eventId) {
        return new NotFoundException("Event with id=" + eventId + " was not found or is unavailable");
    }

    private NotFoundException requestNotFound(Long requestId) {
        return new NotFoundException("Request with id=" + requestId + " was not found or is unavailable");
    }
}
