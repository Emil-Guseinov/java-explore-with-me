package ru.practicum.ewm.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.ewm.category.Category;
import ru.practicum.ewm.category.CategoryRepository;
import ru.practicum.ewm.user.dto.NewUserRequest;
import ru.practicum.ewm.user.dto.UserShortDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class UserApiTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void registersUserAndReturnsGeneratedId() throws Exception {
        NewUserRequest request = new NewUserRequest("Иван", "ivan@example.org");

        mockMvc.perform(post("/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Иван"))
                .andExpect(jsonPath("$.email").value("ivan@example.org"));

        assertThat(userRepository.findAll()).singleElement()
                .satisfies(user -> assertThat(user.getEmail()).isEqualTo("ivan@example.org"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "{\"name\":\" \" ,\"email\":\"ivan@example.org\"}",
        "{\"name\":\"А\",\"email\":\"ivan@example.org\"}",
        "{\"name\":\"Иван\",\"email\":\"not-an-email\"}",
        "{\"name\":\"Иван\",\"email\":\"\"}"
    })
    void rejectsInvalidUser(String body) throws Exception {
        mockMvc.perform(post("/admin/users")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        assertThat(userRepository.count()).isZero();
    }

    @Test
    void rejectsNameOverLimit() throws Exception {
        NewUserRequest request = new NewUserRequest("a".repeat(251), "ivan@example.org");

        mockMvc.perform(post("/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateEmailReturnsConflict() throws Exception {
        saveUser("Первый", "same@example.org");

        mockMvc.perform(post("/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new NewUserRequest("Второй", "same@example.org"))))
                .andExpect(status().isConflict());
    }

    @Test
    void listsUsersWithExactOffsetWithoutCountQuery() throws Exception {
        saveUser("Первый", "one@example.org");
        User second = saveUser("Второй", "two@example.org");
        User third = saveUser("Третий", "three@example.org");
        saveUser("Четвёртый", "four@example.org");
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        mockMvc.perform(get("/admin/users").param("from", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(second.getId()))
                .andExpect(jsonPath("$[1].id").value(third.getId()));

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void filtersUsersByIdsBeforePaginationWithoutCountQuery() throws Exception {
        User first = saveUser("Первый", "one@example.org");
        saveUser("Второй", "two@example.org");
        User third = saveUser("Третий", "three@example.org");
        User fourth = saveUser("Четвёртый", "four@example.org");
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        mockMvc.perform(get("/admin/users")
                        .param("ids", first.getId().toString(), third.getId().toString(), fourth.getId().toString())
                        .param("from", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(third.getId()))
                .andExpect(jsonPath("$[1].id").value(fourth.getId()));

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void nonexistentUserFilterReturnsEmptyArray() throws Exception {
        mockMvc.perform(get("/admin/users").param("ids", "987654321"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/admin/users?from=-1", "/admin/users?size=0", "/admin/users?size=oops"})
    void rejectsInvalidPagination(String url) throws Exception {
        mockMvc.perform(get(url)).andExpect(status().isBadRequest());
    }

    @Test
    void deletesUserAndReturnsNoBody() throws Exception {
        User user = saveUser("Иван", "ivan@example.org");

        mockMvc.perform(delete("/admin/users/{userId}", user.getId()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(userRepository.existsById(user.getId())).isFalse();
    }

    @Test
    void deletingMissingUserReturnsNotFound() throws Exception {
        mockMvc.perform(delete("/admin/users/{userId}", 987654321))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingUserRemovesOwnedEventsAndRelatedRequests() throws Exception {
        User owner = saveUser("Иван", "ivan@example.org");
        User other = saveUser("Мария", "maria@example.org");
        Category category = new Category();
        category.setName("Концерты");
        categoryRepository.saveAndFlush(category);
        long ownedEvent = saveEvent(category.getId(), owner.getId());
        long otherEvent = saveEvent(category.getId(), other.getId());
        jdbcTemplate.update("""
                INSERT INTO participation_requests (event_id, requester_id, created, status)
                VALUES (?, ?, CURRENT_TIMESTAMP, 'PENDING'), (?, ?, CURRENT_TIMESTAMP, 'PENDING')
                """, ownedEvent, other.getId(), otherEvent, owner.getId());
        jdbcTemplate.update("INSERT INTO compilations (title) VALUES ('Выбор редакции')");
        Long compilationId = jdbcTemplate.queryForObject("SELECT id FROM compilations WHERE title = ?",
                Long.class, "Выбор редакции");
        jdbcTemplate.update("INSERT INTO compilation_events (compilation_id, event_id) VALUES (?, ?)",
                compilationId, ownedEvent);

        mockMvc.perform(delete("/admin/users/{userId}", owner.getId()))
                .andExpect(status().isNoContent());

        assertThat(userRepository.existsById(owner.getId())).isFalse();
        assertThat(userRepository.existsById(other.getId())).isTrue();
        assertThat(categoryRepository.existsById(category.getId())).isTrue();
        assertThat(jdbcTemplate.queryForList("SELECT id FROM events", Long.class)).containsExactly(otherEvent);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM participation_requests", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM compilation_events", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM compilations", Long.class)).isEqualTo(1L);
    }

    @Test
    void shortUserRepresentationOmitsEmail() throws Exception {
        User user = saveUser("Иван", "ivan@example.org");

        UserShortDto dto = UserMapper.toShortDto(user);

        assertThat(dto.getId()).isEqualTo(user.getId());
        assertThat(dto.getName()).isEqualTo(user.getName());
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(dto)).has("email")).isFalse();
    }

    private User saveUser(String name, String email) {
        User user = new User();
        user.setName(name);
        user.setEmail(email);
        return userRepository.saveAndFlush(user);
    }

    private long saveEvent(long categoryId, long initiatorId) {
        jdbcTemplate.update("""
                INSERT INTO events (annotation, description, title, category_id, initiator_id,
                                    event_date, created_on, state, lat, lon)
                VALUES ('Описание события', 'Подробности события', 'Концерт', ?, ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'PENDING', 0, 0)
                """, categoryId, initiatorId);
        return jdbcTemplate.queryForObject("SELECT id FROM events WHERE initiator_id = ?", Long.class, initiatorId);
    }
}
