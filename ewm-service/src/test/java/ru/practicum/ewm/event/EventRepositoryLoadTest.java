package ru.practicum.ewm.event;

import jakarta.persistence.EntityManager;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Sort;

import ru.practicum.ewm.category.model.Category;
import ru.practicum.ewm.category.repository.CategoryRepository;
import ru.practicum.ewm.common.pagination.OffsetPageRequest;
import ru.practicum.ewm.compilation.model.Compilation;
import ru.practicum.ewm.compilation.repository.CompilationRepository;
import ru.practicum.ewm.event.model.Event;
import ru.practicum.ewm.event.model.EventState;
import ru.practicum.ewm.event.model.Location;
import ru.practicum.ewm.event.repository.EventRepository;
import ru.practicum.ewm.event.repository.EventSpecifications;
import ru.practicum.ewm.request.model.ParticipationRequest;
import ru.practicum.ewm.request.model.RequestStatus;
import ru.practicum.ewm.request.repository.ParticipationRequestRepository;
import ru.practicum.ewm.user.model.User;
import ru.practicum.ewm.user.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
class EventRepositoryLoadTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 8, 12, 0);
    @Autowired
    private EventRepository events;
    @Autowired
    private CompilationRepository compilations;
    @Autowired
    private CategoryRepository categories;
    @Autowired
    private UserRepository users;
    @Autowired
    private ParticipationRequestRepository requests;
    @Autowired
    private EntityManager entityManager;
    private Statistics statistics;
    private int sequence;

    @BeforeEach
    void setUp() {
        statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void candidatesUseFilteredKeysetBatchesWithoutLoadingEntitiesOrCountingTotal() {
        Event first = event(NOW.plusDays(1), EventState.PUBLISHED);
        event(NOW.plusDays(1), EventState.PENDING);
        event(NOW.minusDays(1), EventState.PUBLISHED);
        Event full = event(NOW.plusDays(1), EventState.PUBLISHED);
        full.setParticipantLimit(1);
        ParticipationRequest request = new ParticipationRequest();
        request.setEvent(full);
        request.setRequester(first.getInitiator());
        request.setCreated(NOW);
        request.setStatus(RequestStatus.CONFIRMED);
        requests.save(request);
        Event second = event(NOW.plusDays(1), EventState.PUBLISHED);
        Event third = event(NOW.plusDays(1), EventState.PUBLISHED);
        resetPersistenceContext();

        var filter = EventSpecifications.publicSearch(null, null, null, null, null, true, NOW);
        List<Long> firstBatch = events.findCandidateIds(filter, 0, 2);
        List<Long> secondBatch = events.findCandidateIds(filter, firstBatch.getLast(), 2);
        List<Long> emptyBatch = events.findCandidateIds(filter, secondBatch.getLast(), 2);

        assertThat(firstBatch).containsExactly(first.getId(), second.getId());
        assertThat(secondBatch).containsExactly(third.getId());
        assertThat(emptyBatch).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
        assertThat(statistics.getEntityLoadCount()).isZero();
    }

    @Test
    void publicationDatesAreReadWithoutLoadingEntitiesOrUnpublishedEvents() {
        Event first = event(NOW.plusDays(1), EventState.PUBLISHED);
        first.setPublishedOn(NOW.minusHours(3));
        Event second = event(NOW.plusDays(2), EventState.PUBLISHED);
        second.setPublishedOn(NOW.minusHours(1));
        Event pending = event(NOW.plusDays(1), EventState.PENDING);
        resetPersistenceContext();

        var publications = events.findByIdInAndPublishedOnIsNotNull(
                List.of(first.getId(), second.getId(), pending.getId()));

        assertThat(publications).hasSize(2);
        assertThat(publications).extracting(item -> item.getPublishedOn())
                .containsExactlyInAnyOrder(NOW.minusHours(3), NOW.minusHours(1));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics.getEntityLoadCount()).isZero();
    }

    @Test
    void compilationIdPagesDoNotLoadEntitiesOrCountTotal() {
        Compilation first = new Compilation();
        first.setTitle("First compilation");
        first.setPinned(false);
        compilations.save(first);
        Compilation second = new Compilation();
        second.setTitle("Second compilation");
        second.setPinned(true);
        compilations.save(second);
        resetPersistenceContext();

        var all = compilations.findAllBy(new OffsetPageRequest(1, 1, Sort.by("id")));
        var pinned = compilations.findAllByPinned(true, new OffsetPageRequest(0, 1, Sort.by("id")));

        assertThat(all).hasSize(1);
        assertThat(all.getFirst().getId()).isEqualTo(second.getId());
        assertThat(pinned).hasSize(1);
        assertThat(pinned.getFirst().getId()).isEqualTo(second.getId());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        assertThat(statistics.getEntityLoadCount()).isZero();
    }

    @Test
    void pageLoadsOnlySelectedEventsAndTheirRelationsInOneQuery() {
        event(NOW.plusDays(1), EventState.PUBLISHED);
        Event first = event(NOW.plusDays(2), EventState.PUBLISHED);
        event(NOW.plusHours(1), EventState.PENDING);
        Event second = event(NOW.plusDays(3), EventState.PUBLISHED);
        event(NOW.plusDays(4), EventState.PUBLISHED);
        resetPersistenceContext();

        List<Event> page = events.findPage(
                EventSpecifications.publicSearch(null, null, null, null, null, false, NOW),
                new OffsetPageRequest(1, 2, Sort.by("eventDate", "id")));
        entityManager.clear();

        assertThat(page).extracting(Event::getId).containsExactly(first.getId(), second.getId());
        assertThat(page).allSatisfy(event -> {
            assertThat(event.getCategory().getName()).startsWith("Category ");
            assertThat(event.getInitiator().getName()).startsWith("Owner ");
        });
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics.getEntityStatistics(Event.class.getName()).getLoadCount()).isEqualTo(2);
        assertThat(statistics.getEntityLoadCount()).isEqualTo(6);
    }

    @Test
    void offsetBeyondLastEventDoesNotLoadEntitiesOrCountTotal() {
        Event event = event(NOW.plusDays(1), EventState.PUBLISHED);
        Long ownerId = event.getInitiator().getId();
        resetPersistenceContext();

        assertThat(events.findPage(EventSpecifications.ownedBy(ownerId),
                new OffsetPageRequest(100, 10, Sort.by("id")))).isEmpty();

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics.getEntityLoadCount()).isZero();
    }

    @Test
    void existenceAndMaximumQueriesReturnScalarIdsWithoutHydratingEvents() {
        Event first = event(NOW.plusDays(1), EventState.PENDING);
        Event second = event(NOW.plusDays(1), EventState.PUBLISHED);
        resetPersistenceContext();

        assertThat(events.findExistingIds(List.of(first.getId(), second.getId() + 1)))
                .containsExactly(first.getId());
        assertThat(events.findMaximumId()).contains(second.getId());

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        assertThat(statistics.getEntityLoadCount()).isZero();
    }

    private void resetPersistenceContext() {
        entityManager.flush();
        entityManager.clear();
        statistics.clear();
    }

    private Event event(LocalDateTime date, EventState state) {
        int number = ++sequence;
        User owner = new User();
        owner.setName("Owner " + number);
        owner.setEmail("owner" + number + "@example.org");
        users.save(owner);
        Category category = new Category();
        category.setName("Category " + number);
        categories.save(category);
        Event event = new Event();
        event.setTitle("Walk " + number);
        event.setAnnotation("An interesting forest walk");
        event.setDescription("An interesting forest walk with a guide");
        event.setEventDate(date);
        event.setCreatedOn(NOW.minusDays(1));
        event.setState(state);
        event.setCategory(category);
        event.setInitiator(owner);
        event.setPaid(false);
        event.setRequestModeration(true);
        event.setParticipantLimit(0);
        Location location = new Location();
        location.setLat(55.0);
        location.setLon(37.0);
        event.setLocation(location);
        return events.save(event);
    }
}
