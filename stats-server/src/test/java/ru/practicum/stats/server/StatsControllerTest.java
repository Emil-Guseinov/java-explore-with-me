package ru.practicum.stats.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StatsControllerTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private HitRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void acceptsHitAndUsesServerGeneratedId() throws Exception {
        mvc.perform(post("/hit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\": 777, \"app\": \"main\", \"uri\": \"/events/1\", \"ip\": \"::1\", \"timestamp\": \"2025-01-01 10:00:00\"}"))
                .andExpect(status().isCreated());

        assertThat(repository.findAll()).singleElement().satisfies(hit -> {
            assertThat(hit.getId()).isPositive().isNotEqualTo(777L);
            assertThat(hit.getIp()).isEqualTo("::1");
            assertThat(hit.getTimestamp()).hasYear(2025).hasHour(10);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"app\":\"\",\"uri\":\"/events/1\",\"ip\":\"::1\",\"timestamp\":\"2025-01-01 10:00:00\"}",
            "{\"app\":\"main\",\"uri\":\" \",\"ip\":\"::1\",\"timestamp\":\"2025-01-01 10:00:00\"}",
            "{\"app\":\"main\",\"uri\":\"/events/1\",\"ip\":null,\"timestamp\":\"2025-01-01 10:00:00\"}",
            "{\"app\":\"main\",\"uri\":\"/events/1\",\"ip\":\"::1\",\"timestamp\":null}",
            "{\"app\":\"main\",\"uri\":\"/events/1\",\"ip\":\"::1\",\"timestamp\":\"not-a-date\"}"
    })
    void rejectsInvalidHitWithoutSaving(String body) throws Exception {
        mvc.perform(post("/hit").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        assertThat(repository.count()).isZero();
    }

    @Test
    void returnsAllHitsByDefaultAndUniqueHitsWhenRequested() throws Exception {
        String body = "{\"app\": \"main\", \"uri\": \"/events/1\", \"ip\": \"::1\", \"timestamp\": \"2025-01-01 10:00:00\"}";
        mvc.perform(post("/hit").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/hit").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mvc.perform(get("/stats").param("start", "2025-01-01 10:00:00")
                        .param("end", "2025-01-01 11:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].app").value("main"))
                .andExpect(jsonPath("$[0].uri").value("/events/1"))
                .andExpect(jsonPath("$[0].hits").value(2));

        mvc.perform(get("/stats").param("start", "2025-01-01 10:00:00")
                        .param("end", "2025-01-01 11:00:00")
                        .param("uris", "/events/1", "/events/2").param("unique", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].hits").value(1));
    }

    @Test
    void rejectsReversedRange() throws Exception {
        mvc.perform(get("/stats").param("start", "2025-01-01 11:00:00")
                        .param("end", "2025-01-01 10:00:00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void requiresRangeParameters() throws Exception {
        mvc.perform(get("/stats")).andExpect(status().isBadRequest());
        mvc.perform(get("/stats").param("start", "2025-01-01 10:00:00"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMalformedDate() throws Exception {
        mvc.perform(get("/stats").param("start", "not-a-date")
                        .param("end", "2025-01-01 11:00:00"))
                .andExpect(status().isBadRequest());
    }
}
