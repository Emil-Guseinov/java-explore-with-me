package ru.practicum.ewm.compilation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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

import ru.practicum.ewm.category.model.Category;
import ru.practicum.ewm.category.repository.CategoryRepository;
import ru.practicum.ewm.compilation.dto.UpdateCompilationRequest;
import ru.practicum.ewm.compilation.model.Compilation;
import ru.practicum.ewm.compilation.repository.CompilationRepository;
import ru.practicum.ewm.compilation.service.CompilationStorageService;
import ru.practicum.ewm.event.model.Event;
import ru.practicum.ewm.event.model.EventState;
import ru.practicum.ewm.event.model.Location;
import ru.practicum.ewm.event.repository.EventRepository;
import ru.practicum.ewm.user.model.User;
import ru.practicum.ewm.user.repository.UserRepository;
import ru.practicum.stats.client.StatsClient;
import ru.practicum.stats.dto.ViewStats;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CompilationApiTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CompilationRepository compilationRepository;

    @Autowired
    private CompilationStorageService compilationStorage;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private StatsClient statsClient;

    private User initiator;
    private Category category;

    @BeforeEach
    void setUp() {
        clearDatabase();
        when(statsClient.getStats(any(), any(), anyList(), eq(true))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return List.of();
        });
        initiator = new User();
        initiator.setName("Иван");
        initiator.setEmail("ivan@example.org");
        userRepository.saveAndFlush(initiator);
        category = new Category();
        category.setName("Концерты");
        categoryRepository.saveAndFlush(category);
    }

    @AfterEach
    void tearDown() {
        clearDatabase();
    }

    @Test
    void createsCompilationWithEventsAndReturnsItPublicly() throws Exception {
        Event first = saveEvent("Первый концерт");
        Event second = saveEvent("Второй концерт");

        String response = mockMvc.perform(post("/admin/compilations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Музыкальные выходные\",\"pinned\":true,\"events\":[%d,%d]}".formatted(first.getId(), second.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pinned").value(true))
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[0].views").value(0))
                .andExpect(jsonPath("$.events[0].confirmedRequests").value(0))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(response).get("id").asLong();

        mockMvc.perform(get("/compilations/{compId}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Музыкальные выходные"))
                .andExpect(jsonPath("$.events[0].id").value(first.getId()))
                .andExpect(jsonPath("$.events[1].id").value(second.getId()));
    }

    @Test
    void createsEmptyCompilationWithDefaultPinnedValue() throws Exception {
        mockMvc.perform(post("/admin/compilations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Будущие события\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pinned").value(false))
                .andExpect(jsonPath("$.events").isEmpty());

        verifyNoInteractions(statsClient);
    }

    @Test
    void nullFieldsInPatchPreserveExistingValues() throws Exception {
        Event event = saveEvent("Концерт");
        Compilation compilation = saveCompilation("Выходные", true, event);

        mockMvc.perform(patch("/admin/compilations/{compId}", compilation.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":null,\"pinned\":null,\"events\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Выходные"))
                .andExpect(jsonPath("$.pinned").value(true))
                .andExpect(jsonPath("$.events[0].id").value(event.getId()));
    }

    @Test
    void emptyPatchPreservesCompilation() throws Exception {
        Compilation compilation = saveCompilation("Выходные", true);

        mockMvc.perform(patch("/admin/compilations/{compId}", compilation.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Выходные"))
                .andExpect(jsonPath("$.pinned").value(true))
                .andExpect(jsonPath("$.events").isEmpty());
    }

    @Test
    void falseAndEmptyEventsInPatchAreApplied() throws Exception {
        Event event = saveEvent("Концерт");
        Compilation compilation = saveCompilation("Выходные", true, event);

        mockMvc.perform(patch("/admin/compilations/{compId}", compilation.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Новая подборка\",\"pinned\":false,\"events\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Новая подборка"))
                .andExpect(jsonPath("$.pinned").value(false))
                .andExpect(jsonPath("$.events").isEmpty());

        mockMvc.perform(get("/compilations/{compId}", compilation.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pinned").value(false))
                .andExpect(jsonPath("$.events").isEmpty());
        assertThat(eventRepository.existsById(event.getId())).isTrue();
        verifyNoInteractions(statsClient);
    }

    @Test
    void missingEventRollsBackAllPatchChanges() throws Exception {
        Event event = saveEvent("Концерт");
        Compilation compilation = saveCompilation("Старое название", true, event);

        mockMvc.perform(patch("/admin/compilations/{compId}", compilation.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Новое название\",\"pinned\":false,\"events\":[%d,987654321]}".formatted(event.getId())))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/compilations/{compId}", compilation.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Старое название"))
                .andExpect(jsonPath("$.pinned").value(true))
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.events[0].id").value(event.getId()));
    }

    @Test
    void missingEventPreventsCreation() throws Exception {
        mockMvc.perform(post("/admin/compilations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Выходные\",\"events\":[987654321]}"))
                .andExpect(status().isNotFound());

        assertThat(compilationRepository.count()).isZero();
        verifyNoInteractions(statsClient);
    }

    @Test
    void paginatesCompilationsBeforeFetchingCompleteEventCollections() throws Exception {
        Event excluded = saveEvent("За пределами страницы");
        Event first = saveEvent("Первый концерт");
        Event second = saveEvent("Второй концерт");
        Event third = saveEvent("Третий концерт");
        saveCompilation("Не попадёт на страницу", false, excluded);
        Compilation selected = saveCompilation("Два события", true, first, second);
        Compilation next = saveCompilation("Следующая подборка", false, third);

        mockMvc.perform(get("/compilations").param("from", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(selected.getId()))
                .andExpect(jsonPath("$[0].events.length()").value(2))
                .andExpect(jsonPath("$[1].id").value(next.getId()))
                .andExpect(jsonPath("$[1].events.length()").value(1));

        Set<String> expectedUris = Set.of("/events/" + first.getId(), "/events/" + second.getId(),
                "/events/" + third.getId());
        verify(statsClient).getStats(any(), any(),
                argThat(uris -> uris.size() == 3 && new HashSet<>(uris).equals(expectedUris)), eq(true));
        verifyNoMoreInteractions(statsClient);
    }

    @Test
    void enrichesSharedEventsOnceForWholeCompilationPage() throws Exception {
        Event shared = saveEvent("Общий концерт");
        Event extra = saveEvent("Другой концерт");
        saveCompilation("Подборка один", false, shared);
        saveCompilation("Подборка два", false, shared, extra);
        saveCompilation("Подборка три", false, shared);

        mockMvc.perform(get("/compilations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[1].events.length()").value(2));

        Set<String> expectedUris = Set.of("/events/" + shared.getId(), "/events/" + extra.getId());
        verify(statsClient).getStats(any(), any(),
                argThat(uris -> uris.size() == 2 && new HashSet<>(uris).equals(expectedUris)), eq(true));
        verifyNoMoreInteractions(statsClient);
    }

    @Test
    void appliesPinnedFilterBeforePagination() throws Exception {
        saveCompilation("Не закреплена", false);
        saveCompilation("Первая закреплённая", true);
        Compilation selected = saveCompilation("Вторая закреплённая", true);

        mockMvc.perform(get("/compilations").param("pinned", "true").param("from", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(selected.getId()));

        verifyNoInteractions(statsClient);
    }

    @Test
    void emptyPageDoesNotRequestStatistics() throws Exception {
        mockMvc.perform(get("/compilations"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verifyNoInteractions(statsClient);
    }

    @Test
    void deletingCompilationKeepsEventsAndOtherCompilations() throws Exception {
        Event event = saveEvent("Концерт");
        Compilation removed = saveCompilation("Удаляемая", false, event);
        Compilation retained = saveCompilation("Оставшаяся", false, event);

        mockMvc.perform(delete("/admin/compilations/{compId}", removed.getId()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(compilationRepository.existsById(removed.getId())).isFalse();
        assertThat(eventRepository.existsById(event.getId())).isTrue();
        mockMvc.perform(get("/compilations/{compId}", retained.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].id").value(event.getId()));
    }

    @Test
    void duplicateTitleReturnsConflictWithoutExtraCompilation() throws Exception {
        saveCompilation("Выходные", true);

        mockMvc.perform(post("/admin/compilations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Выходные\"}"))
                .andExpect(status().isConflict());

        assertThat(compilationRepository.count()).isEqualTo(1L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"title\":\" \"}", "{\"title\":\"Выходные\",\"events\":[0]}"})
    void invalidCreateReturnsBadRequest(String body) throws Exception {
        mockMvc.perform(post("/admin/compilations")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        assertThat(compilationRepository.count()).isZero();
    }

    @Test
    void rejectsOverlongTitle() throws Exception {
        JsonNode body = objectMapper.createObjectNode().put("title", "a".repeat(51));

        mockMvc.perform(post("/admin/compilations")
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/compilations?from=-1", "/compilations?size=0", "/compilations?pinned=unknown"})
    void invalidPaginationReturnsBadRequest(String url) throws Exception {
        mockMvc.perform(get(url)).andExpect(status().isBadRequest());
    }

    @Test
    void missingCompilationReturnsNotFoundForReadUpdateAndDelete() throws Exception {
        mockMvc.perform(get("/compilations/987654321")).andExpect(status().isNotFound());
        mockMvc.perform(patch("/admin/compilations/987654321")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/admin/compilations/987654321")).andExpect(status().isNotFound());
    }

    @Test
    void creationReadsStatisticsBeforeSavingAndKeepsReturnedViews() throws Exception {
        Event event = saveEvent("Концерт");
        when(statsClient.getStats(any(), any(), anyList(), eq(true))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(compilationRepository.count()).isZero();
            return List.of(new ViewStats("ewm-main-service", "/events/" + event.getId(), 24L));
        });

        mockMvc.perform(post("/admin/compilations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Новая подборка\",\"events\":[%d]}".formatted(event.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.events[0].views").value(24));

        assertThat(compilationRepository.count()).isEqualTo(1);
        verify(statsClient).getStats(any(), any(), anyList(), eq(true));
    }

    @Test
    void unavailableStatisticsPreventsCompilationCreation() throws Exception {
        Event event = saveEvent("Концерт");
        statisticsUnavailable();

        mockMvc.perform(post("/admin/compilations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Новая подборка\",\"events\":[%d]}".formatted(event.getId())))
                .andExpect(status().isServiceUnavailable());

        assertThat(compilationRepository.count()).isZero();
    }

    @Test
    void unavailableStatisticsPreventsAnyPatchMutation() throws Exception {
        Event original = saveEvent("Первый концерт");
        Event replacement = saveEvent("Другой концерт");
        Compilation compilation = saveCompilation("Исходная подборка", true, original);
        statisticsUnavailable();

        mockMvc.perform(patch("/admin/compilations/{compId}", compilation.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Новое название\",\"pinned\":false,\"events\":[%d]}"
                                .formatted(replacement.getId())))
                .andExpect(status().isServiceUnavailable());

        var unchanged = compilationStorage.get(compilation.getId());
        assertThat(unchanged.getTitle()).isEqualTo("Исходная подборка");
        assertThat(unchanged.getPinned()).isTrue();
        assertThat(unchanged.getEvents()).extracting(event -> event.getId()).containsExactly(original.getId());
    }

    @Test
    void membershipChangedDuringStatisticsReadRejectsPatchWithoutOverwritingConcurrentChange() throws Exception {
        Event original = saveEvent("Первый концерт");
        Event replacement = saveEvent("Другой концерт");
        Compilation compilation = saveCompilation("Исходная подборка", true, original);
        when(statsClient.getStats(any(), any(), anyList(), eq(true))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            UpdateCompilationRequest concurrentUpdate = new UpdateCompilationRequest();
            concurrentUpdate.setEvents(Set.of(replacement.getId()));
            compilationStorage.update(compilation.getId(), concurrentUpdate, Set.of(replacement.getId()));
            return List.of(new ViewStats("ewm-main-service", "/events/" + original.getId(), 24L));
        });

        mockMvc.perform(patch("/admin/compilations/{compId}", compilation.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Новое название\",\"pinned\":false}"))
                .andExpect(status().isConflict());

        var unchanged = compilationStorage.get(compilation.getId());
        assertThat(unchanged.getTitle()).isEqualTo("Исходная подборка");
        assertThat(unchanged.getPinned()).isTrue();
        assertThat(unchanged.getEvents()).extracting(event -> event.getId()).containsExactly(replacement.getId());
    }

    private void statisticsUnavailable() {
        when(statsClient.getStats(any(), any(), anyList(), eq(true))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            throw new ResourceAccessException("Statistics unavailable");
        });
    }

    private Compilation saveCompilation(String title, boolean pinned, Event... events) {
        Compilation compilation = new Compilation();
        compilation.setTitle(title);
        compilation.setPinned(pinned);
        compilation.setEvents(new LinkedHashSet<>(List.of(events)));
        return compilationRepository.saveAndFlush(compilation);
    }

    private Event saveEvent(String title) {
        Event event = new Event();
        event.setTitle(title);
        event.setAnnotation("Краткое описание концерта для посетителей");
        event.setDescription("Подробное описание концерта для посетителей");
        event.setCategory(category);
        event.setInitiator(initiator);
        event.setCreatedOn(LocalDateTime.now());
        event.setEventDate(LocalDateTime.now().plusDays(3));
        event.setState(EventState.PUBLISHED);
        event.setPublishedOn(LocalDateTime.now());
        event.setPaid(false);
        event.setParticipantLimit(0);
        event.setRequestModeration(false);
        Location location = new Location();
        location.setLat(55.75);
        location.setLon(37.62);
        event.setLocation(location);
        return eventRepository.saveAndFlush(event);
    }

    private void clearDatabase() {
        jdbcTemplate.update("DELETE FROM compilations");
        jdbcTemplate.update("DELETE FROM users");
        jdbcTemplate.update("DELETE FROM categories");
    }
}
