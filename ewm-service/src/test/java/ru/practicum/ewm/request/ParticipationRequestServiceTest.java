package ru.practicum.ewm.request;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.ewm.category.Category;
import ru.practicum.ewm.category.CategoryRepository;
import ru.practicum.ewm.common.ConflictException;
import ru.practicum.ewm.common.NotFoundException;
import ru.practicum.ewm.event.Event;
import ru.practicum.ewm.event.EventRepository;
import ru.practicum.ewm.event.EventState;
import ru.practicum.ewm.event.Location;
import ru.practicum.ewm.request.dto.EventRequestStatusUpdateRequest;
import ru.practicum.ewm.request.dto.EventRequestStatusUpdateResult;
import ru.practicum.ewm.request.dto.ParticipationRequestDto;
import ru.practicum.ewm.request.dto.RequestStatusAction;
import ru.practicum.ewm.user.User;
import ru.practicum.ewm.user.UserRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=jdbc:h2:mem:request-tests;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ParticipationRequestService.class, ParticipationRequestServiceTest.TimeConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ParticipationRequestServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 1, 12, 0);

    @Autowired
    private ParticipationRequestService service;
    @Autowired
    private ParticipationRequestRepository requests;
    @Autowired
    private EventRepository events;
    @Autowired
    private UserRepository users;
    @Autowired
    private CategoryRepository categories;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private SqlStatements statements;

    private User owner;
    private User firstUser;
    private User secondUser;
    private User thirdUser;
    private Category category;

    @BeforeEach
    void setUp() {
        requests.deleteAllInBatch();
        events.deleteAllInBatch();
        categories.deleteAllInBatch();
        users.deleteAllInBatch();
        owner = user("owner");
        firstUser = user("first");
        secondUser = user("second");
        thirdUser = user("third");
        category = new Category();
        category.setName("Meetings");
        categories.save(category);
    }

    @Test
    void moderatedLimitedEventCreatesPendingRequest() {
        Event event = event(2, true, EventState.PUBLISHED);

        ParticipationRequestDto result = service.create(firstUser.getId(), event.getId());

        assertThat(result.status()).isEqualTo(RequestStatus.PENDING);
        assertThat(result.created()).isEqualTo(NOW);
        assertThat(result.requester()).isEqualTo(firstUser.getId());
        assertThat(result.event()).isEqualTo(event.getId());
        assertThat(service.getUserRequests(firstUser.getId())).containsExactly(result);
        assertThat(service.getEventRequests(owner.getId(), event.getId())).containsExactly(result);
    }

    @Test
    void unlimitedEventAutomaticallyConfirmsRequests() {
        Event event = event(0, true, EventState.PUBLISHED);

        ParticipationRequestDto result = service.create(firstUser.getId(), event.getId());

        assertThat(result.status()).isEqualTo(RequestStatus.CONFIRMED);
    }

    @Test
    void disabledModerationAutomaticallyConfirmsRequests() {
        Event event = event(2, false, EventState.PUBLISHED);

        ParticipationRequestDto result = service.create(firstUser.getId(), event.getId());

        assertThat(result.status()).isEqualTo(RequestStatus.CONFIRMED);
    }

    @Test
    void unpublishedEventDoesNotAcceptRequests() {
        Event event = event(0, false, EventState.PENDING);

        assertThatThrownBy(() -> service.create(firstUser.getId(), event.getId()))
                .isInstanceOf(ConflictException.class);
        assertThat(requests.count()).isZero();
    }

    @Test
    void ownerCannotParticipateInOwnEvent() {
        Event event = event(0, false, EventState.PUBLISHED);

        assertThatThrownBy(() -> service.create(owner.getId(), event.getId()))
                .isInstanceOf(ConflictException.class);
        assertThat(requests.count()).isZero();
    }

    @Test
    void canceledRequestStillPreventsDuplicateParticipationRequest() {
        Event event = event(0, false, EventState.PUBLISHED);
        ParticipationRequestDto original = service.create(firstUser.getId(), event.getId());
        service.cancel(firstUser.getId(), original.id());

        assertThatThrownBy(() -> service.create(firstUser.getId(), event.getId()))
                .isInstanceOf(ConflictException.class);
        assertThat(requests.count()).isEqualTo(1);
        assertStatus(original.id(), RequestStatus.CANCELED);
    }

    @Test
    void reachingLimitRejectsUnselectedPendingRequests() {
        Event event = event(1, true, EventState.PUBLISHED);
        ParticipationRequestDto first = service.create(firstUser.getId(), event.getId());
        ParticipationRequestDto second = service.create(secondUser.getId(), event.getId());
        ParticipationRequestDto third = service.create(thirdUser.getId(), event.getId());

        EventRequestStatusUpdateResult result = confirm(event, List.of(first.id()));

        assertThat(result.confirmedRequests()).extracting(ParticipationRequestDto::id).containsExactly(first.id());
        assertThat(result.rejectedRequests()).extracting(ParticipationRequestDto::id)
                .containsExactly(second.id(), third.id());
        assertStatus(first.id(), RequestStatus.CONFIRMED);
        assertStatus(second.id(), RequestStatus.REJECTED);
        assertStatus(third.id(), RequestStatus.REJECTED);
        assertThatThrownBy(() -> service.create(user("late").getId(), event.getId()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void reachingLimitRejectsManyPendingRequestsWithOneBulkUpdate() {
        Event event = event(2, true, EventState.PUBLISHED);
        ParticipationRequestDto previousConfirmed = service.create(firstUser.getId(), event.getId());
        confirm(event, List.of(previousConfirmed.id()));
        ParticipationRequestDto canceled = service.create(secondUser.getId(), event.getId());
        service.cancel(secondUser.getId(), canceled.id());
        ParticipationRequestDto previousRejected = service.create(thirdUser.getId(), event.getId());
        service.updateStatus(owner.getId(), event.getId(), new EventRequestStatusUpdateRequest(
                List.of(previousRejected.id()), RequestStatusAction.REJECTED));
        ParticipationRequestDto winner = service.create(user("winner").getId(), event.getId());
        List<ParticipationRequestDto> expectedRejected = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            ParticipationRequestDto pending = service.create(user("pending" + index).getId(), event.getId());
            expectedRejected.add(new ParticipationRequestDto(pending.created(), pending.event(), pending.id(),
                    pending.requester(), RequestStatus.REJECTED));
        }
        Event otherEvent = event(2, true, EventState.PUBLISHED);
        ParticipationRequestDto otherPending = service.create(firstUser.getId(), otherEvent.getId());
        statements.clear();

        EventRequestStatusUpdateResult result = confirm(event, List.of(winner.id()));

        assertThat(statements.requestUpdates()).hasSize(2);
        assertThat(statements.requestUpdates())
                .filteredOn(sql -> sql.substring(sql.indexOf(" where ")).contains("event_id"))
                .singleElement().satisfies(sql ->
                        assertThat(sql.substring(sql.indexOf(" where "))).contains("status"));
        assertThat(result.confirmedRequests()).containsExactly(new ParticipationRequestDto(
                winner.created(), winner.event(), winner.id(), winner.requester(), RequestStatus.CONFIRMED));
        assertThat(result.rejectedRequests()).containsExactlyElementsOf(expectedRejected);
        expectedRejected.forEach(request -> assertStatus(request.id(), RequestStatus.REJECTED));
        assertStatus(previousConfirmed.id(), RequestStatus.CONFIRMED);
        assertStatus(canceled.id(), RequestStatus.CANCELED);
        assertStatus(previousRejected.id(), RequestStatus.REJECTED);
        assertStatus(otherPending.id(), RequestStatus.PENDING);
    }

    @Test
    void automaticallyFillingLastPlaceRejectsPendingRequestsWithoutLoadingThem() {
        Event event = event(1, true, EventState.PUBLISHED);
        ParticipationRequestDto pending = service.create(firstUser.getId(), event.getId());
        service.create(secondUser.getId(), event.getId());
        event.setRequestModeration(false);
        events.save(event);
        statements.clear();

        ParticipationRequestDto confirmed = service.create(thirdUser.getId(), event.getId());

        assertThat(confirmed.status()).isEqualTo(RequestStatus.CONFIRMED);
        assertThat(statements.requestUpdates()).hasSize(1);
        assertThat(statements.statements).noneMatch(sql -> sql.startsWith("select ")
                && sql.contains("participation_requests") && sql.contains("created"));
        assertStatus(pending.id(), RequestStatus.REJECTED);
        assertThat(requests.countByEventIdAndStatus(event.getId(), RequestStatus.REJECTED)).isEqualTo(2);
        assertThat(requests.countByEventIdAndStatus(event.getId(), RequestStatus.CONFIRMED)).isEqualTo(1);
    }

    @Test
    void bulkRejectionDoesNotLeaveStalePendingEntitiesInPersistenceContext() {
        Event event = event(1, true, EventState.PUBLISHED);
        ParticipationRequestDto first = service.create(firstUser.getId(), event.getId());
        ParticipationRequestDto second = service.create(secondUser.getId(), event.getId());
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            ParticipationRequest cachedPending = requests.findById(second.id()).orElseThrow();
            assertThat(cachedPending.getStatus()).isEqualTo(RequestStatus.PENDING);

            confirm(event, List.of(first.id()));

            assertStatus(first.id(), RequestStatus.CONFIRMED);
            assertStatus(second.id(), RequestStatus.REJECTED);
        });
        assertStatus(second.id(), RequestStatus.REJECTED);
    }

    @Test
    void batchHonorsInputOrderWhenOnlyOnePlaceRemains() {
        Event event = event(1, true, EventState.PUBLISHED);
        ParticipationRequestDto first = service.create(firstUser.getId(), event.getId());
        ParticipationRequestDto second = service.create(secondUser.getId(), event.getId());

        EventRequestStatusUpdateResult result = confirm(event, List.of(second.id(), first.id()));

        assertThat(result.confirmedRequests()).extracting(ParticipationRequestDto::id).containsExactly(second.id());
        assertThat(result.rejectedRequests()).extracting(ParticipationRequestDto::id).containsExactly(first.id());
        assertThat(requests.countByEventIdAndStatus(event.getId(), RequestStatus.CONFIRMED)).isEqualTo(1);
    }

    @Test
    void invalidStatusInBatchDoesNotPartiallyConfirmOtherRequests() {
        Event event = event(3, true, EventState.PUBLISHED);
        ParticipationRequestDto first = service.create(firstUser.getId(), event.getId());
        ParticipationRequestDto second = service.create(secondUser.getId(), event.getId());
        service.cancel(secondUser.getId(), second.id());

        assertThatThrownBy(() -> confirm(event, List.of(first.id(), second.id())))
                .isInstanceOf(ConflictException.class);

        assertStatus(first.id(), RequestStatus.PENDING);
        assertStatus(second.id(), RequestStatus.CANCELED);
        assertThat(requests.countByEventIdAndStatus(event.getId(), RequestStatus.CONFIRMED)).isZero();
    }

    @Test
    void failedTransactionRollsBackBothConfirmationAndAutomaticRejections() {
        Event event = event(1, true, EventState.PUBLISHED);
        ParticipationRequestDto first = service.create(firstUser.getId(), event.getId());
        ParticipationRequestDto second = service.create(secondUser.getId(), event.getId());
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            confirm(event, List.of(first.id()));
            requests.flush();
            throw new IllegalStateException("Abort after database writes");
        })).isInstanceOf(IllegalStateException.class);

        assertStatus(first.id(), RequestStatus.PENDING);
        assertStatus(second.id(), RequestStatus.PENDING);
        assertThat(requests.countByEventIdAndStatus(event.getId(), RequestStatus.CONFIRMED)).isZero();
    }

    @Test
    void requestFromAnotherEventDoesNotPartiallyChangeBatch() {
        Event firstEvent = event(3, true, EventState.PUBLISHED);
        Event secondEvent = event(3, true, EventState.PUBLISHED);
        ParticipationRequestDto first = service.create(firstUser.getId(), firstEvent.getId());
        ParticipationRequestDto second = service.create(secondUser.getId(), secondEvent.getId());

        assertThatThrownBy(() -> confirm(firstEvent, List.of(first.id(), second.id())))
                .isInstanceOf(NotFoundException.class);

        assertStatus(first.id(), RequestStatus.PENDING);
        assertStatus(second.id(), RequestStatus.PENDING);
    }

    @Test
    void anotherUserCannotModerateOrReadEventRequests() {
        Event event = event(2, true, EventState.PUBLISHED);
        ParticipationRequestDto request = service.create(firstUser.getId(), event.getId());
        EventRequestStatusUpdateRequest update = new EventRequestStatusUpdateRequest(
                List.of(request.id()), RequestStatusAction.CONFIRMED);

        assertThatThrownBy(() -> service.getEventRequests(secondUser.getId(), event.getId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.updateStatus(secondUser.getId(), event.getId(), update))
                .isInstanceOf(NotFoundException.class);
        assertStatus(request.id(), RequestStatus.PENDING);
    }

    @Test
    void onlyRequesterCanCancelAndCancellationFreesPlace() {
        Event event = event(1, false, EventState.PUBLISHED);
        ParticipationRequestDto request = service.create(firstUser.getId(), event.getId());

        assertThatThrownBy(() -> service.cancel(secondUser.getId(), request.id()))
                .isInstanceOf(NotFoundException.class);
        assertStatus(request.id(), RequestStatus.CONFIRMED);

        assertThat(service.cancel(firstUser.getId(), request.id()).status()).isEqualTo(RequestStatus.CANCELED);
        assertThat(service.create(secondUser.getId(), event.getId()).status()).isEqualTo(RequestStatus.CONFIRMED);
        assertThat(requests.countByEventIdAndStatus(event.getId(), RequestStatus.CONFIRMED)).isEqualTo(1);
    }

    @Test
    void rejectedBatchLeavesOtherPendingRequestsUntouched() {
        Event event = event(2, true, EventState.PUBLISHED);
        ParticipationRequestDto first = service.create(firstUser.getId(), event.getId());
        ParticipationRequestDto second = service.create(secondUser.getId(), event.getId());

        EventRequestStatusUpdateResult result = service.updateStatus(owner.getId(), event.getId(),
                new EventRequestStatusUpdateRequest(List.of(first.id()), RequestStatusAction.REJECTED));

        assertThat(result.confirmedRequests()).isEmpty();
        assertThat(result.rejectedRequests()).extracting(ParticipationRequestDto::id).containsExactly(first.id());
        assertStatus(first.id(), RequestStatus.REJECTED);
        assertStatus(second.id(), RequestStatus.PENDING);
    }

    @Test
    void groupedCountsOnlyIncludeRequestedStatus() {
        Event event = event(3, true, EventState.PUBLISHED);
        ParticipationRequestDto first = service.create(firstUser.getId(), event.getId());
        service.create(secondUser.getId(), event.getId());
        confirm(event, List.of(first.id()));

        List<RequestCount> result = requests.counts(List.of(event.getId()), RequestStatus.CONFIRMED);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getEventId()).isEqualTo(event.getId());
        assertThat(result.getFirst().getTotal()).isEqualTo(1);
    }

    @Test
    void concurrentAutomaticConfirmationCannotOverbookLastPlace() throws Exception {
        Event event = event(1, false, EventState.PUBLISHED);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(() ->
                    createConcurrently(firstUser.getId(), event.getId(), ready, start));
            Future<Boolean> second = executor.submit(() ->
                    createConcurrently(secondUser.getId(), event.getId(), ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(requests.countByEventIdAndStatus(event.getId(), RequestStatus.CONFIRMED)).isEqualTo(1);
        assertThat(requests.count()).isEqualTo(1);
    }

    private boolean createConcurrently(Long userId, Long eventId, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent request was not released");
        }
        try {
            service.create(userId, eventId);
            return true;
        } catch (ConflictException exception) {
            return false;
        }
    }

    private EventRequestStatusUpdateResult confirm(Event event, List<Long> requestIds) {
        return service.updateStatus(owner.getId(), event.getId(),
                new EventRequestStatusUpdateRequest(requestIds, RequestStatusAction.CONFIRMED));
    }

    private void assertStatus(Long id, RequestStatus expected) {
        assertThat(requests.findById(id).orElseThrow().getStatus()).isEqualTo(expected);
    }

    private User user(String name) {
        User user = new User();
        user.setName(name);
        user.setEmail(name + "@example.org");
        return users.save(user);
    }

    private Event event(int limit, boolean moderation, EventState state) {
        Event event = new Event();
        event.setTitle("Evening in the park");
        event.setAnnotation("A short walk and friendly conversation");
        event.setDescription("A walk through the park with other participants");
        event.setCategory(category);
        event.setInitiator(owner);
        event.setCreatedOn(NOW);
        event.setEventDate(NOW.plusDays(2));
        event.setState(state);
        event.setPaid(false);
        event.setParticipantLimit(limit);
        event.setRequestModeration(moderation);
        Location location = new Location();
        location.setLat(55.75);
        location.setLon(37.62);
        event.setLocation(location);
        return events.save(event);
    }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2030-01-01T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        SqlStatements sqlStatements() {
            return new SqlStatements();
        }

        @Bean
        HibernatePropertiesCustomizer statementInspector(SqlStatements statements) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", statements);
        }
    }

    static class SqlStatements implements StatementInspector {
        private final List<String> statements = new CopyOnWriteArrayList<>();

        @Override
        public String inspect(String sql) {
            statements.add(sql);
            return sql;
        }

        void clear() {
            statements.clear();
        }

        List<String> requestUpdates() {
            return statements.stream().filter(sql -> sql.startsWith("update participation_requests ")).toList();
        }
    }
}
