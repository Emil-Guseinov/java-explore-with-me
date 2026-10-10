package ru.practicum.ewm.comment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import ru.practicum.ewm.category.model.Category;
import ru.practicum.ewm.category.repository.CategoryRepository;
import ru.practicum.ewm.comment.dto.CommentRequest;
import ru.practicum.ewm.comment.model.Comment;
import ru.practicum.ewm.comment.repository.CommentRepository;
import ru.practicum.ewm.event.model.Event;
import ru.practicum.ewm.event.model.EventState;
import ru.practicum.ewm.event.model.Location;
import ru.practicum.ewm.event.repository.EventRepository;
import ru.practicum.ewm.user.model.User;
import ru.practicum.ewm.user.repository.UserRepository;
import ru.practicum.stats.client.StatsClient;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CommentApiTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private CommentRepository comments;
    @Autowired
    private EventRepository events;
    @Autowired
    private UserRepository users;
    @Autowired
    private CategoryRepository categories;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @MockBean
    private StatsClient statsClient;

    private User author;
    private User organizer;
    private Event event;

    @BeforeEach
    void setUp() {
        author = user("Автор", "author@example.org");
        organizer = user("Организатор", "organizer@example.org");
        Category category = new Category();
        category.setName("Концерты");
        categories.save(category);
        event = event(category, EventState.PUBLISHED);
    }

    @Test
    void trimsTextOnCreationAndUpdate() throws Exception {
        String response = mvc.perform(post("/users/{userId}/events/{eventId}/comments",
                        author.getId(), event.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(" \tПервый  комментарий\n ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value("Первый  комментарий"))
                .andReturn().getResponse().getContentAsString();

        long id = mapper.readTree(response).get("id").asLong();
        entityManager.flush();
        entityManager.clear();
        assertThat(comments.findById(id).orElseThrow().getText())
                .isEqualTo("Первый  комментарий");

        mvc.perform(patch("/users/{userId}/comments/{commentId}", author.getId(), id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(" \nОбновлённый  комментарий\t ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Обновлённый  комментарий"));

        entityManager.flush();
        entityManager.clear();
        assertThat(comments.findById(id).orElseThrow().getText())
                .isEqualTo("Обновлённый  комментарий");
    }

    @Test
    void createsCommentWithServerOwnedFields() throws Exception {
        String body = "{\"text\":\"Где находится вход?\",\"id\":999999,\"event\":999999,"
                + "\"author\":{\"id\":999999},\"created\":\"2000-01-01 00:00:00\","
                + "\"updated\":\"2000-01-01 00:00:00\"}";
        String response = mvc.perform(post("/users/{userId}/events/{eventId}/comments", author.getId(), event.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value("Где находится вход?"))
                .andExpect(jsonPath("$.event").value(event.getId()))
                .andExpect(jsonPath("$.author.id").value(author.getId()))
                .andExpect(jsonPath("$.author.name").value(author.getName()))
                .andExpect(jsonPath("$.author.email").doesNotExist())
                .andExpect(jsonPath("$.updated").isEmpty())
                .andReturn().getResponse().getContentAsString();
        JsonNode result = mapper.readTree(response);
        assertThat(result.get("id").asLong()).isNotEqualTo(999999);
        assertThat(result.get("created").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")
                .doesNotStartWith("2000-");
        assertThat(comments.count()).isEqualTo(1);
        verifyNoInteractions(statsClient);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2000})
    void acceptsTextAtBothLengthBoundaries(int length) throws Exception {
        String text = "я".repeat(length);
        mvc.perform(post("/users/{userId}/events/{eventId}/comments", author.getId(), event.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body(text)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{\"text\":null}", "{\"text\":\"\"}",
            "{\"text\":\"  \\n\\t \"}", "{\"text\":{}}", "{"})
    void rejectsInvalidBodyForCreationAndUpdate(String body) throws Exception {
        Comment comment = comment("Исходный текст", author, event, LocalDateTime.now());
        mvc.perform(post("/users/{userId}/events/{eventId}/comments", author.getId(), event.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/users/{userId}/comments/{commentId}", author.getId(), comment.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(comments.count()).isEqualTo(1);
        assertThat(comments.findById(comment.getId()).orElseThrow().getText()).isEqualTo("Исходный текст");
    }

    @Test
    void rejectsTextOverLimitForCreationAndUpdate() throws Exception {
        Comment comment = comment("Текст", author, event, LocalDateTime.now());
        String body = body("a".repeat(2001));
        mvc.perform(post("/users/{userId}/events/{eventId}/comments", author.getId(), event.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/users/{userId}/comments/{commentId}", author.getId(), comment.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        assertThat(comments.count()).isEqualTo(1);
        assertThat(comment.getText()).isEqualTo("Текст");
    }

    @ParameterizedTest
    @EnumSource(value = EventState.class, names = {"PENDING", "CANCELED"})
    void unpublishedEventRejectsCreationAndPublicListing(EventState state) throws Exception {
        event.setState(state);
        events.flush();
        mvc.perform(post("/users/{userId}/events/{eventId}/comments", author.getId(), event.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body("Текст")))
                .andExpect(status().isConflict());
        mvc.perform(get("/events/{eventId}/comments", event.getId())).andExpect(status().isNotFound());
        assertThat(comments.count()).isZero();
    }

    @Test
    void creationRequiresExistingUserAndEvent() throws Exception {
        mvc.perform(post("/users/{userId}/events/{eventId}/comments", Long.MAX_VALUE, event.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body("Текст")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/users/{userId}/events/{eventId}/comments", author.getId(), Long.MAX_VALUE)
                        .contentType(MediaType.APPLICATION_JSON).content(body("Текст")))
                .andExpect(status().isNotFound());
        assertThat(comments.count()).isZero();
    }

    @Test
    void organizerCanCommentOnOwnPublishedEvent() throws Exception {
        mvc.perform(post("/users/{userId}/events/{eventId}/comments", organizer.getId(), event.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body("Вход справа")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.author.id").value(organizer.getId()));
    }

    @Test
    void authorUpdatesOnlyTextAndPreservesCreationTime() throws Exception {
        LocalDateTime created = LocalDateTime.of(2026, 1, 1, 12, 0);
        Comment comment = comment("Старый текст", author, event, created);
        String body = "{\"text\":\"Новый текст\",\"author\":{\"id\":" + organizer.getId()
                + "},\"event\":999999,\"created\":\"2000-01-01 00:00:00\"}";
        mvc.perform(patch("/users/{userId}/comments/{commentId}", author.getId(), comment.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Новый текст"))
                .andExpect(jsonPath("$.author.id").value(author.getId()))
                .andExpect(jsonPath("$.event").value(event.getId()))
                .andExpect(jsonPath("$.created").value("2026-01-01 12:00:00"))
                .andExpect(jsonPath("$.updated").isString());
        entityManager.flush();
        entityManager.clear();
        Comment stored = comments.findById(comment.getId()).orElseThrow();
        assertThat(stored.getText()).isEqualTo("Новый текст");
        assertThat(stored.getCreated()).isEqualTo(created);
        assertThat(stored.getUpdated()).isAfter(created);
        verifyNoInteractions(statsClient);
    }

    @Test
    void otherUserCannotUpdateOrDeleteComment() throws Exception {
        Comment comment = comment("Исходный текст", author, event, LocalDateTime.now());
        mvc.perform(patch("/users/{userId}/comments/{commentId}", organizer.getId(), comment.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body("Подмена")))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/users/{userId}/comments/{commentId}", organizer.getId(), comment.getId()))
                .andExpect(status().isNotFound());
        assertThat(comments.findById(comment.getId()).orElseThrow().getText()).isEqualTo("Исходный текст");
    }

    @Test
    void unknownUserCannotUpdateOrDeleteComment() throws Exception {
        Comment comment = comment("Текст", author, event, LocalDateTime.now());
        mvc.perform(patch("/users/{userId}/comments/{commentId}", Long.MAX_VALUE, comment.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body("Подмена")))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/users/{userId}/comments/{commentId}", Long.MAX_VALUE, comment.getId()))
                .andExpect(status().isNotFound());
        assertThat(comments.existsById(comment.getId())).isTrue();
    }

    @Test
    void authorAndAdminDeleteOnlySelectedComments() throws Exception {
        Comment first = comment("Первый", author, event, LocalDateTime.now());
        Comment second = comment("Второй", author, event, LocalDateTime.now());
        mvc.perform(delete("/users/{userId}/comments/{commentId}", author.getId(), first.getId()))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(comments.existsById(first.getId())).isFalse();
        assertThat(comments.existsById(second.getId())).isTrue();
        mvc.perform(delete("/admin/comments/{commentId}", second.getId()))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(comments.count()).isZero();
        mvc.perform(delete("/admin/comments/{commentId}", second.getId())).andExpect(status().isNotFound());
        assertThat(events.existsById(event.getId())).isTrue();
        assertThat(users.existsById(author.getId())).isTrue();
    }

    @Test
    void missingCommentReturnsNotFoundForAllModifications() throws Exception {
        mvc.perform(patch("/users/{userId}/comments/{commentId}", author.getId(), Long.MAX_VALUE)
                        .contentType(MediaType.APPLICATION_JSON).content(body("Текст")))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/users/{userId}/comments/{commentId}", author.getId(), Long.MAX_VALUE))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/admin/comments/{commentId}", Long.MAX_VALUE)).andExpect(status().isNotFound());
    }

    @Test
    void emptyListAndOffsetBeyondEndReturnEmptyArrays() throws Exception {
        mvc.perform(get("/events/{eventId}/comments", event.getId()))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        comment("Текст", author, event, LocalDateTime.now());
        mvc.perform(get("/events/{eventId}/comments", event.getId()).param("from", "2147483647"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/events/{eventId}/comments", Long.MAX_VALUE)).andExpect(status().isNotFound());
    }

    @Test
    void listsOnlySelectedEventWithStableOrderExactOffsetAndBoundedQueries() throws Exception {
        LocalDateTime created = LocalDateTime.of(2026, 1, 1, 12, 0);
        comment("Старый", author, event, created.minusDays(1));
        Comment second = comment("Второй", author, event, created);
        Comment third = comment("Третий", organizer, event, created);
        comment("Новый", author, event, created.plusDays(1));
        Event other = event(event.getCategory(), EventState.PUBLISHED);
        comment("Другое событие", author, other, created.plusDays(2));
        entityManager.flush();
        entityManager.clear();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        mvc.perform(get("/events/{eventId}/comments", event.getId()).param("from", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(third.getId()))
                .andExpect(jsonPath("$[0].author.name").value(organizer.getName()))
                .andExpect(jsonPath("$[1].id").value(second.getId()));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        assertThat(statistics.getEntityLoadCount()).isEqualTo(4);
        verifyNoInteractions(statsClient);
    }

    @ParameterizedTest
    @ValueSource(strings = {"from=-1", "size=0", "size=-1", "from=oops", "size=oops", "size=2147483648"})
    void invalidPaginationReturnsBadRequest(String query) throws Exception {
        String[] parameter = query.split("=");
        mvc.perform(get("/events/{eventId}/comments", event.getId()).param(parameter[0], parameter[1]))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "oops"})
    void invalidPathIdsReturnBadRequest(String id) throws Exception {
        mvc.perform(get("/events/{eventId}/comments", id)).andExpect(status().isBadRequest());
        mvc.perform(post("/users/{userId}/events/{eventId}/comments", id, event.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(body("Текст")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/users/{userId}/events/{eventId}/comments", author.getId(), id)
                        .contentType(MediaType.APPLICATION_JSON).content(body("Текст")))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/users/{userId}/comments/{commentId}", author.getId(), id)
                        .contentType(MediaType.APPLICATION_JSON).content(body("Текст")))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/users/{userId}/comments/{commentId}", author.getId(), id))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/admin/comments/{commentId}", id)).andExpect(status().isBadRequest());
    }

    @Test
    void deletingAuthorRemovesCommentsButPreservesEvent() throws Exception {
        comment("Текст", author, event, LocalDateTime.now());
        mvc.perform(delete("/admin/users/{userId}", author.getId())).andExpect(status().isNoContent());
        entityManager.flush();
        entityManager.clear();
        assertThat(comments.count()).isZero();
        assertThat(events.existsById(event.getId())).isTrue();
    }

    @Test
    void deletingOrganizerRemovesEventCommentsButPreservesTheirAuthors() throws Exception {
        comment("Текст", author, event, LocalDateTime.now());
        mvc.perform(delete("/admin/users/{userId}", organizer.getId())).andExpect(status().isNoContent());
        entityManager.flush();
        entityManager.clear();
        assertThat(comments.count()).isZero();
        assertThat(events.existsById(event.getId())).isFalse();
        assertThat(users.existsById(author.getId())).isTrue();
    }

    private String body(String text) throws Exception {
        return mapper.writeValueAsString(new CommentRequest(text));
    }

    private User user(String name, String email) {
        User user = new User();
        user.setName(name);
        user.setEmail(email);
        return users.saveAndFlush(user);
    }

    private Event event(Category category, EventState state) {
        Event value = new Event();
        value.setTitle("Концерт");
        value.setAnnotation("Вечерний концерт в городском парке");
        value.setDescription("Музыкальная программа на открытой сцене городского парка");
        value.setInitiator(organizer);
        value.setCategory(category);
        value.setState(state);
        value.setCreatedOn(LocalDateTime.now().minusDays(1));
        value.setPublishedOn(state == EventState.PUBLISHED ? LocalDateTime.now().minusHours(1) : null);
        value.setEventDate(LocalDateTime.now().plusDays(1));
        Location location = new Location();
        location.setLat(55.75);
        location.setLon(37.62);
        value.setLocation(location);
        value.setPaid(false);
        value.setParticipantLimit(0);
        value.setRequestModeration(true);
        return events.saveAndFlush(value);
    }

    private Comment comment(String text, User user, Event target, LocalDateTime created) {
        Comment comment = new Comment();
        comment.setText(text);
        comment.setAuthor(user);
        comment.setEvent(target);
        comment.setCreated(created);
        return comments.saveAndFlush(comment);
    }
}
