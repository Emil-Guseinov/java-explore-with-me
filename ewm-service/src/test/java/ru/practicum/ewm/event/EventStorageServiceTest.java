package ru.practicum.ewm.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ru.practicum.ewm.category.model.Category;
import ru.practicum.ewm.category.repository.CategoryRepository;
import ru.practicum.ewm.common.exception.BadRequestException;
import ru.practicum.ewm.common.exception.ConflictException;
import ru.practicum.ewm.common.exception.NotFoundException;
import ru.practicum.ewm.event.dto.*;
import ru.practicum.ewm.event.model.Event;
import ru.practicum.ewm.event.model.EventState;
import ru.practicum.ewm.event.model.Location;
import ru.practicum.ewm.event.repository.EventRepository;
import ru.practicum.ewm.event.service.EventStorageService;
import ru.practicum.ewm.request.model.RequestStatus;
import ru.practicum.ewm.request.repository.ParticipationRequestRepository;
import ru.practicum.ewm.user.model.User;
import ru.practicum.ewm.user.repository.UserRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventStorageServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    @Mock
    private EventRepository events;
    @Mock
    private UserRepository users;
    @Mock
    private CategoryRepository categories;
    @Mock
    private ParticipationRequestRepository requests;
    private EventStorageService service;
    private Event event;

    @BeforeEach
    void setUp() {
        service = new EventStorageService(events, users, categories, requests, CLOCK);
        event = event(1L);
    }

    @Test
    void invalidBatchSizeIsRejectedBeforeRepositoryAccess() {
        assertThatThrownBy(() -> service.candidateIds(null, 0, 0))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.candidateIds(null, 0, -1))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(events);
    }

    @Test
    void createRejectsDateInsideTwoHourWindow() {
        NewEventDto dto = newEvent();
        dto.setEventDate(NOW.plusHours(2).minusSeconds(1));
        assertThatThrownBy(() -> service.create(1, dto)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(events);
    }

    @Test
    void createAcceptsExactTwoHourBoundaryAndUsesDefaults() {
        NewEventDto dto = newEvent();
        dto.setPaid(null);
        dto.setParticipantLimit(null);
        dto.setRequestModeration(null);
        when(users.findById(1L)).thenReturn(Optional.of(event.getInitiator()));
        when(categories.findById(1L)).thenReturn(Optional.of(event.getCategory()));
        when(events.save(any())).thenAnswer(invocation -> {
            Event saved = invocation.getArgument(0);
            saved.setId(4L);
            return saved;
        });
        EventFullDto result = service.create(1, dto);
        assertThat(result.getId()).isEqualTo(4L);
        assertThat(result.getState()).isEqualTo(EventState.PENDING);
        assertThat(result.getCreatedOn()).isEqualTo(NOW);
        assertThat(result.getPaid()).isFalse();
        assertThat(result.getParticipantLimit()).isZero();
        assertThat(result.getRequestModeration()).isTrue();
    }

    @Test
    void ownerCannotEditPublishedEvent() {
        event.setState(EventState.PUBLISHED);
        stubOwnedLock();
        assertThatThrownBy(() -> service.updateByUser(1, 1, new UpdateEventUserRequest()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void anotherUserCannotEditEvent() {
        when(users.existsById(2L)).thenReturn(true);
        when(events.findByIdForUpdate(1L)).thenReturn(Optional.of(event));
        assertThatThrownBy(() -> service.updateByUser(2, 1, new UpdateEventUserRequest()))
                .isInstanceOf(NotFoundException.class);
        assertThat(event.getState()).isEqualTo(EventState.PENDING);
    }

    @Test
    void ownerPatchPreservesNullsAndAppliesFalseAndZero() {
        stubOwnedLock();
        event.setPaid(true);
        event.setParticipantLimit(10);
        UpdateEventUserRequest dto = new UpdateEventUserRequest();
        dto.setPaid(false);
        dto.setParticipantLimit(0);
        dto.setRequestModeration(false);
        dto.setStateAction(UpdateEventUserRequest.StateAction.CANCEL_REVIEW);
        EventFullDto result = service.updateByUser(1, 1, dto);
        assertThat(result.getPaid()).isFalse();
        assertThat(result.getRequestModeration()).isFalse();
        assertThat(result.getParticipantLimit()).isZero();
        assertThat(result.getTitle()).isEqualTo("Forest walk");
        assertThat(result.getState()).isEqualTo(EventState.CANCELED);
    }

    @Test
    void canceledEventCanBeSentBackForReview() {
        stubOwnedLock();
        event.setState(EventState.CANCELED);
        UpdateEventUserRequest dto = new UpdateEventUserRequest();
        dto.setStateAction(UpdateEventUserRequest.StateAction.SEND_TO_REVIEW);
        assertThat(service.updateByUser(1, 1, dto).getState()).isEqualTo(EventState.PENDING);
    }

    @Test
    void ownerDateViolationIsConflict() {
        stubOwnedLock();
        UpdateEventUserRequest dto = new UpdateEventUserRequest();
        dto.setEventDate(NOW.plusHours(2).minusSeconds(1));
        assertThatThrownBy(() -> service.updateByUser(1, 1, dto)).isInstanceOf(ConflictException.class);
    }

    @Test
    void publishingAcceptsExactOneHourBoundaryAndSetsTimestamp() {
        when(events.findByIdForUpdate(1L)).thenReturn(Optional.of(event));
        event.setEventDate(NOW.plusHours(1));
        UpdateEventAdminRequest dto = publishRequest();
        EventFullDto result = service.updateByAdmin(1, dto);
        assertThat(result.getState()).isEqualTo(EventState.PUBLISHED);
        assertThat(result.getPublishedOn()).isEqualTo(NOW);
    }

    @Test
    void publishingRejectsTooEarlyEvent() {
        when(events.findByIdForUpdate(1L)).thenReturn(Optional.of(event));
        event.setEventDate(NOW.plusHours(1).minusSeconds(1));
        assertThatThrownBy(() -> service.updateByAdmin(1, publishRequest())).isInstanceOf(ConflictException.class);
        assertThat(event.getPublishedOn()).isNull();
    }

    @Test
    void canceledEventCannotBePublishedDirectly() {
        when(events.findByIdForUpdate(1L)).thenReturn(Optional.of(event));
        event.setState(EventState.CANCELED);
        assertThatThrownBy(() -> service.updateByAdmin(1, publishRequest())).isInstanceOf(ConflictException.class);
    }

    @Test
    void publishedEventCannotBeRejected() {
        when(events.findByIdForUpdate(1L)).thenReturn(Optional.of(event));
        event.setState(EventState.PUBLISHED);
        UpdateEventAdminRequest dto = new UpdateEventAdminRequest();
        dto.setStateAction(UpdateEventAdminRequest.StateAction.REJECT_EVENT);
        assertThatThrownBy(() -> service.updateByAdmin(1, dto)).isInstanceOf(ConflictException.class);
    }

    @Test
    void adminDateEditUsesOriginalPublicationTimestamp() {
        when(events.findByIdForUpdate(1L)).thenReturn(Optional.of(event));
        event.setState(EventState.PUBLISHED);
        event.setPublishedOn(NOW.minusDays(1));
        UpdateEventAdminRequest dto = new UpdateEventAdminRequest();
        dto.setEventDate(NOW.minusHours(4));
        assertThat(service.updateByAdmin(1, dto).getEventDate()).isEqualTo(NOW.minusHours(4));
        assertThat(event.getPublishedOn()).isEqualTo(NOW.minusDays(1));
    }

    @Test
    void cannotLowerLimitBelowConfirmedRequests() {
        when(events.findByIdForUpdate(1L)).thenReturn(Optional.of(event));
        when(requests.countByEventIdAndStatus(1L, RequestStatus.CONFIRMED)).thenReturn(5L);
        UpdateEventAdminRequest dto = new UpdateEventAdminRequest();
        dto.setParticipantLimit(4);
        assertThatThrownBy(() -> service.updateByAdmin(1, dto)).isInstanceOf(ConflictException.class);
        assertThat(event.getParticipantLimit()).isZero();
    }

    @Test
    void publicUnpublishedEventIsNotFound() {
        when(events.findById(1L)).thenReturn(Optional.of(event));
        assertThatThrownBy(() -> service.publicEvent(1))
                .isInstanceOf(NotFoundException.class);
    }

    private void stubOwnedLock() {
        when(users.existsById(1L)).thenReturn(true);
        when(events.findByIdForUpdate(1L)).thenReturn(Optional.of(event));
    }

    private UpdateEventAdminRequest publishRequest() {
        UpdateEventAdminRequest dto = new UpdateEventAdminRequest();
        dto.setStateAction(UpdateEventAdminRequest.StateAction.PUBLISH_EVENT);
        return dto;
    }

    private NewEventDto newEvent() {
        NewEventDto dto = new NewEventDto();
        dto.setAnnotation("An interesting forest walk");
        dto.setDescription("An interesting forest walk with a guide");
        dto.setTitle("Forest walk");
        dto.setCategory(1L);
        dto.setEventDate(NOW.plusHours(2));
        LocationDto location = new LocationDto();
        location.setLat(55.0);
        location.setLon(37.0);
        dto.setLocation(location);
        return dto;
    }

    private Event event(long id) {
        User user = new User();
        user.setId(1L);
        user.setName("Emil");
        Category category = new Category();
        category.setId(1L);
        category.setName("Walks");
        Event value = new Event();
        value.setId(id);
        value.setInitiator(user);
        value.setCategory(category);
        value.setTitle("Forest walk");
        value.setAnnotation("An interesting forest walk");
        value.setDescription("An interesting forest walk with a guide");
        value.setState(EventState.PENDING);
        value.setEventDate(NOW.plusDays(1));
        value.setCreatedOn(NOW);
        value.setLocation(new Location());
        value.setPaid(false);
        value.setParticipantLimit(0);
        value.setRequestModeration(true);
        return value;
    }
}
