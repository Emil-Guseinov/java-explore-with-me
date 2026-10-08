package ru.practicum.ewm.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;
import ru.practicum.ewm.category.Category;
import ru.practicum.ewm.category.CategoryRepository;
import ru.practicum.ewm.stats.EventStatisticsService;
import ru.practicum.ewm.user.User;
import ru.practicum.ewm.user.UserRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EventTransactionTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private EventRepository events;
    @Autowired
    private UserRepository users;
    @Autowired
    private CategoryRepository categories;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockBean
    private EventStatisticsService statistics;

    private User owner;
    private Category category;

    @BeforeEach
    void setUp() {
        clearDatabase();
        owner = new User();
        owner.setName("Организатор");
        owner.setEmail("event-transactions@example.org");
        users.saveAndFlush(owner);
        category = new Category();
        category.setName("Концерты");
        categories.saveAndFlush(category);
        when(statistics.views(anyCollection())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Collection<Long> ids = invocation.getArgument(0);
            Map<Long, Long> result = new LinkedHashMap<>();
            ids.forEach(id -> result.put(id, 17L));
            return result;
        });
        when(statistics.views(anyCollection(), any(LocalDateTime.class))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Collection<Long> ids = invocation.getArgument(0);
            Map<Long, Long> result = new LinkedHashMap<>();
            ids.forEach(id -> result.put(id, 17L));
            return result;
        });
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(statistics).hit(anyString(), anyString());
    }

    @AfterEach
    void tearDown() {
        clearDatabase();
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "user-list", "user-detail"})
    void privateReadsEnrichDetachedDtosOutsideTransactions(String endpoint) throws Exception {
        Event event = saveEvent(EventState.PUBLISHED);
        String url = switch (endpoint) {
            case "admin" -> "/admin/events";
            case "user-list" -> "/users/" + owner.getId() + "/events";
            default -> "/users/" + owner.getId() + "/events/" + event.getId();
        };
        String prefix = endpoint.equals("user-detail") ? "$" : "$[0]";

        mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(jsonPath(prefix + ".views").value(17))
                .andExpect(jsonPath(prefix + ".category.name").value(category.getName()))
                .andExpect(jsonPath(prefix + ".initiator.name").value(owner.getName()));

        verify(statistics).views(List.of(event.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"EVENT_DATE", "VIEWS"})
    void publicListsReadStatisticsAndRecordHitsOutsideTransactions(String sort) throws Exception {
        Event event = saveEvent(EventState.PUBLISHED);

        mockMvc.perform(get("/events").param("sort", sort))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(event.getId()))
                .andExpect(jsonPath("$[0].views").value(17))
                .andExpect(jsonPath("$[0].category.name").value(category.getName()))
                .andExpect(jsonPath("$[0].initiator.name").value(owner.getName()));

        verify(statistics).hit("/events", "127.0.0.1");
    }

    @Test
    void publicDetailRecordsHitBeforeReadingViewsOutsideTransaction() throws Exception {
        Event event = saveEvent(EventState.PUBLISHED);
        String uri = "/events/" + event.getId();

        mockMvc.perform(get(uri))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.views").value(17));

        var order = inOrder(statistics);
        order.verify(statistics).hit(uri, "127.0.0.1");
        order.verify(statistics).views(List.of(event.getId()));
        order.verifyNoMoreInteractions();
    }

    @Test
    void adminPatchReadsViewsBeforeMutationAndCommitsPublishedEvent() throws Exception {
        Event event = saveEvent(EventState.PENDING);
        expectStatisticsBeforeMutation(event.getId());

        mockMvc.perform(patch("/admin/events/{eventId}", event.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Изменённый концерт\",\"participantLimit\":4,"
                                + "\"stateAction\":\"PUBLISH_EVENT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.views").value(17))
                .andExpect(jsonPath("$.state").value("PUBLISHED"));

        Event changed = events.findById(event.getId()).orElseThrow();
        assertThat(changed.getTitle()).isEqualTo("Изменённый концерт");
        assertThat(changed.getParticipantLimit()).isEqualTo(4);
        assertThat(changed.getState()).isEqualTo(EventState.PUBLISHED);
        assertThat(changed.getPublishedOn()).isNotNull();
        verify(statistics).views(List.of(event.getId()));
    }

    @Test
    void userPatchReadsViewsBeforeMutationAndCommitsCanceledEvent() throws Exception {
        Event event = saveEvent(EventState.PENDING);
        expectStatisticsBeforeMutation(event.getId());

        mockMvc.perform(patch("/users/{userId}/events/{eventId}", owner.getId(), event.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Изменённый концерт\",\"paid\":true,"
                                + "\"stateAction\":\"CANCEL_REVIEW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.views").value(17))
                .andExpect(jsonPath("$.state").value("CANCELED"));

        Event changed = events.findById(event.getId()).orElseThrow();
        assertThat(changed.getTitle()).isEqualTo("Изменённый концерт");
        assertThat(changed.getPaid()).isTrue();
        assertThat(changed.getState()).isEqualTo(EventState.CANCELED);
        verify(statistics).views(List.of(event.getId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "user"})
    void statisticsOutageDoesNotChangeAnyEventFields(String actor) throws Exception {
        Event event = saveEvent(EventState.PENDING);
        when(statistics.views(anyCollection())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            throw new ResourceAccessException("Statistics unavailable");
        });
        String url = actor.equals("admin") ? "/admin/events/" + event.getId()
                : "/users/" + owner.getId() + "/events/" + event.getId();
        String action = actor.equals("admin") ? "PUBLISH_EVENT" : "CANCEL_REVIEW";

        mockMvc.perform(patch(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Изменённый концерт\",\"paid\":true,\"participantLimit\":4,"
                                + "\"stateAction\":\"" + action + "\"}"))
                .andExpect(status().isServiceUnavailable());

        Event unchanged = events.findById(event.getId()).orElseThrow();
        assertThat(unchanged.getTitle()).isEqualTo("Исходный концерт");
        assertThat(unchanged.getPaid()).isFalse();
        assertThat(unchanged.getParticipantLimit()).isZero();
        assertThat(unchanged.getState()).isEqualTo(EventState.PENDING);
        assertThat(unchanged.getPublishedOn()).isNull();
    }

    @Test
    void reversedPublicDateRangeReturnsBadRequestWithoutStatistics() throws Exception {
        mockMvc.perform(get("/events")
                        .param("rangeStart", "2030-01-02 12:00:00")
                        .param("rangeEnd", "2030-01-01 12:00:00"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(statistics);
    }

    @Test
    void unpublishedPublicDetailReturnsNotFoundWithoutRecordingHit() throws Exception {
        Event event = saveEvent(EventState.PENDING);

        mockMvc.perform(get("/events/{id}", event.getId()))
                .andExpect(status().isNotFound());

        verifyNoInteractions(statistics);
    }

    private void expectStatisticsBeforeMutation(long eventId) {
        when(statistics.views(anyCollection())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Event unchanged = events.findById(eventId).orElseThrow();
            assertThat(unchanged.getTitle()).isEqualTo("Исходный концерт");
            assertThat(unchanged.getState()).isEqualTo(EventState.PENDING);
            return Map.of(eventId, 17L);
        });
    }

    private Event saveEvent(EventState state) {
        LocalDateTime now = LocalDateTime.now();
        Event event = new Event();
        event.setTitle("Исходный концерт");
        event.setAnnotation("Краткое описание концерта для посетителей");
        event.setDescription("Подробное описание концерта для посетителей");
        event.setCategory(category);
        event.setInitiator(owner);
        event.setCreatedOn(now);
        event.setEventDate(now.plusDays(3));
        event.setState(state);
        event.setPublishedOn(state == EventState.PUBLISHED ? now : null);
        event.setPaid(false);
        event.setParticipantLimit(0);
        event.setRequestModeration(false);
        Location location = new Location();
        location.setLat(55.75);
        location.setLon(37.62);
        event.setLocation(location);
        return events.saveAndFlush(event);
    }

    private void clearDatabase() {
        jdbcTemplate.update("DELETE FROM compilations");
        jdbcTemplate.update("DELETE FROM users");
        jdbcTemplate.update("DELETE FROM categories");
    }
}
