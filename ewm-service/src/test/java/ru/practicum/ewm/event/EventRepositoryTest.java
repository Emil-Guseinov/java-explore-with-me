package ru.practicum.ewm.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Sort;
import ru.practicum.ewm.category.Category;
import ru.practicum.ewm.category.CategoryRepository;
import ru.practicum.ewm.common.OffsetPageRequest;
import ru.practicum.ewm.request.ParticipationRequest;
import ru.practicum.ewm.request.ParticipationRequestRepository;
import ru.practicum.ewm.request.RequestStatus;
import ru.practicum.ewm.user.User;
import ru.practicum.ewm.user.UserRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {"spring.sql.init.mode=never", "spring.jpa.hibernate.ddl-auto=create-drop"})
class EventRepositoryTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 7, 12, 0);
    @Autowired
    private EventRepository events;
    @Autowired
    private UserRepository users;
    @Autowired
    private CategoryRepository categories;
    @Autowired
    private ParticipationRequestRepository requests;
    private User owner;
    private User visitor;
    private Category category;

    @BeforeEach
    void setUp() {
        owner = user("owner@example.org");
        visitor = user("visitor@example.org");
        category = new Category();
        category.setName("Walks");
        category = categories.save(category);
    }

    @Test
    void publicDefaultSearchReturnsOnlyFuturePublishedEvents() {
        Event future = event("Future", NOW.plusHours(1), EventState.PUBLISHED, 0);
        event("Past", NOW.minusHours(1), EventState.PUBLISHED, 0);
        event("Pending", NOW.plusHours(1), EventState.PENDING, 0);
        event("Now", NOW, EventState.PUBLISHED, 0);
        assertThat(events.findAll(EventSpecifications.publicSearch(null, null, null, null, null, false, NOW)))
                .extracting(Event::getId).containsExactly(future.getId());
    }

    @Test
    void aSingleExplicitUpperBoundAllowsPastEvents() {
        Event past = event("Past", NOW.minusHours(1), EventState.PUBLISHED, 0);
        event("Future", NOW.plusHours(1), EventState.PUBLISHED, 0);
        assertThat(events.findAll(EventSpecifications.publicSearch(null, null, null, null, NOW, false, NOW)))
                .extracting(Event::getId).containsExactly(past.getId());
    }

    @Test
    void caseInsensitiveSearchMatchesDescriptionAndTreatsPercentLiterally() {
        Event exact = event("Exact", NOW.plusHours(1), EventState.PUBLISHED, 0);
        exact.setDescription("Learn at 100% intensity");
        Event wildcard = event("Wildcard", NOW.plusHours(1), EventState.PUBLISHED, 0);
        wildcard.setDescription("Learn at 1000 intensity");
        assertThat(events.findAll(EventSpecifications.publicSearch("100% INTENSITY", null, null,
                null, null, false, NOW))).extracting(Event::getId).containsExactly(exact.getId());
    }

    @Test
    void availableSearchCountsOnlyConfirmedAndTreatsZeroAsUnlimited() {
        Event full = event("Full", NOW.plusHours(1), EventState.PUBLISHED, 1);
        Event pending = event("Pending request", NOW.plusHours(1), EventState.PUBLISHED, 1);
        Event unlimited = event("Unlimited", NOW.plusHours(1), EventState.PUBLISHED, 0);
        request(full, RequestStatus.CONFIRMED);
        request(pending, RequestStatus.PENDING);
        request(unlimited, RequestStatus.CONFIRMED);
        assertThat(events.findAll(EventSpecifications.publicSearch(null, null, null, null, null, true, NOW)))
                .extracting(Event::getId).containsExactlyInAnyOrder(pending.getId(), unlimited.getId());
    }

    @Test
    void availableFilterRunsBeforeOffsetPagination() {
        Event full = event("Full", NOW.plusHours(1), EventState.PUBLISHED, 1);
        Event first = event("First available", NOW.plusHours(2), EventState.PUBLISHED, 2);
        Event second = event("Second available", NOW.plusHours(3), EventState.PUBLISHED, 2);
        request(full, RequestStatus.CONFIRMED);
        var page = events.findAll(EventSpecifications.publicSearch(null, null, null, null, null, true, NOW),
                new OffsetPageRequest(1, 1, Sort.by("eventDate", "id")));
        assertThat(page.getContent()).extracting(Event::getId).containsExactly(second.getId());
        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void nonPageAlignedOffsetSkipsExactlyRequestedRows() {
        event("First", NOW.plusHours(1), EventState.PUBLISHED, 0);
        Event second = event("Second", NOW.plusHours(2), EventState.PUBLISHED, 0);
        Event third = event("Third", NOW.plusHours(3), EventState.PUBLISHED, 0);
        event("Fourth", NOW.plusHours(4), EventState.PUBLISHED, 0);
        var page = events.findAll(EventSpecifications.ownedBy(owner.getId()),
                new OffsetPageRequest(1, 2, Sort.by("eventDate", "id")));
        assertThat(page.getContent()).extracting(Event::getId).containsExactly(second.getId(), third.getId());
    }

    @Test
    void adminFiltersCombineOwnerStateCategoryAndDates() {
        Event matching = event("Matching", NOW.plusHours(2), EventState.PENDING, 0);
        event("Published", NOW.plusHours(2), EventState.PUBLISHED, 0);
        event("Late", NOW.plusHours(5), EventState.PENDING, 0);
        assertThat(events.findAll(EventSpecifications.adminSearch(List.of(owner.getId()), List.of(EventState.PENDING),
                List.of(category.getId()), NOW.plusHours(1), NOW.plusHours(3))))
                .extracting(Event::getId).containsExactly(matching.getId());
    }

    private User user(String email) {
        User user = new User();
        user.setName("Test user");
        user.setEmail(email);
        return users.save(user);
    }

    private Event event(String title, LocalDateTime date, EventState state, int limit) {
        Event event = new Event();
        event.setTitle(title);
        event.setAnnotation("An interesting forest walk");
        event.setDescription("An interesting forest walk with a guide");
        event.setEventDate(date);
        event.setCreatedOn(NOW.minusDays(1));
        event.setState(state);
        event.setCategory(category);
        event.setInitiator(owner);
        event.setPaid(false);
        event.setRequestModeration(true);
        event.setParticipantLimit(limit);
        Location location = new Location();
        location.setLat(55.0);
        location.setLon(37.0);
        event.setLocation(location);
        return events.save(event);
    }

    private void request(Event event, RequestStatus status) {
        ParticipationRequest request = new ParticipationRequest();
        request.setEvent(event);
        request.setRequester(visitor);
        request.setCreated(NOW);
        request.setStatus(status);
        requests.save(request);
    }
}
