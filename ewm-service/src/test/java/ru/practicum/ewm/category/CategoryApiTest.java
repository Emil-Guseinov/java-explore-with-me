package ru.practicum.ewm.category;

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
import ru.practicum.ewm.category.dto.NewCategoryDto;
import ru.practicum.ewm.user.User;
import ru.practicum.ewm.user.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
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
class CategoryApiTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void createsCategoryAndMakesItPubliclyAvailable() throws Exception {
        String response = mockMvc.perform(post("/admin/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Концерты\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Концерты"))
                .andReturn().getResponse().getContentAsString();
        long categoryId = objectMapper.readTree(response).get("id").asLong();

        mockMvc.perform(get("/categories/{catId}", categoryId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(categoryId))
                .andExpect(jsonPath("$.name").value("Концерты"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":null}", "{\"name\":\"\"}", "{\"name\":\"  \"}"})
    void rejectsBlankCategory(String body) throws Exception {
        mockMvc.perform(post("/admin/categories")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        assertThat(categoryRepository.count()).isZero();
    }

    @Test
    void rejectsNameOverLimit() throws Exception {
        mockMvc.perform(post("/admin/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new NewCategoryDto("a".repeat(51)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateCategoryReturnsConflict() throws Exception {
        saveCategory("Концерты");

        mockMvc.perform(post("/admin/categories")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Концерты\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void updatesOnlyCategorySelectedByPath() throws Exception {
        Category category = saveCategory("Концерты");
        Category other = saveCategory("Выставки");

        mockMvc.perform(patch("/admin/categories/{catId}", category.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":" + other.getId() + ",\"name\":\"Музыка\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(category.getId()))
                .andExpect(jsonPath("$.name").value("Музыка"));

        assertThat(categoryRepository.findById(other.getId()).orElseThrow().getName()).isEqualTo("Выставки");
    }

    @Test
    void updatingNameToItselfIsAllowed() throws Exception {
        Category category = saveCategory("Концерты");

        mockMvc.perform(patch("/admin/categories/{catId}", category.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Концерты\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Концерты"));
    }

    @Test
    void updatingNameToAnotherCategoryNameReturnsConflict() throws Exception {
        Category category = saveCategory("Концерты");
        saveCategory("Выставки");

        mockMvc.perform(patch("/admin/categories/{catId}", category.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Выставки\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void updateRequiresNonblankName() throws Exception {
        Category category = saveCategory("Концерты");

        mockMvc.perform(patch("/admin/categories/{catId}", category.getId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listsCategoriesWithExactOffsetWithoutCountQuery() throws Exception {
        saveCategory("Первая");
        Category second = saveCategory("Вторая");
        Category third = saveCategory("Третья");
        saveCategory("Четвёртая");
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        mockMvc.perform(get("/categories").param("from", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(second.getId()))
                .andExpect(jsonPath("$[1].id").value(third.getId()));

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test
    void emptyCatalogReturnsArray() throws Exception {
        mockMvc.perform(get("/categories"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/categories?from=-1", "/categories?size=0", "/categories?size=oops"})
    void rejectsInvalidPagination(String url) throws Exception {
        mockMvc.perform(get(url)).andExpect(status().isBadRequest());
    }

    @Test
    void missingCategoryReturnsNotFound() throws Exception {
        mockMvc.perform(get("/categories/{catId}", 987654321))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/admin/categories/{catId}", 987654321)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Музыка\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/admin/categories/{catId}", 987654321))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesEmptyCategory() throws Exception {
        Category category = saveCategory("Концерты");

        mockMvc.perform(delete("/admin/categories/{catId}", category.getId()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(categoryRepository.existsById(category.getId())).isFalse();
    }

    @Test
    void cannotDeleteCategoryReferencedByEvent() throws Exception {
        Category category = saveCategory("Концерты");
        User user = new User();
        user.setName("Иван");
        user.setEmail("ivan@example.org");
        userRepository.saveAndFlush(user);
        jdbcTemplate.update("""
                INSERT INTO events (annotation, description, title, category_id, initiator_id,
                                    event_date, created_on, state, lat, lon)
                VALUES ('Описание события', 'Подробности события', 'Концерт', ?, ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'PENDING', 0, 0)
                """, category.getId(), user.getId());

        mockMvc.perform(delete("/admin/categories/{catId}", category.getId()))
                .andExpect(status().isConflict());
    }

    private Category saveCategory(String name) {
        Category category = new Category();
        category.setName(name);
        return categoryRepository.saveAndFlush(category);
    }
}
