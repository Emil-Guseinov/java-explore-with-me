package ru.practicum.ewm.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class HttpErrorTest {
    @Autowired
    private MockMvc mvc;

    @Test
    void unknownRouteIsNotFound() throws Exception {
        mvc.perform(get("/unknown-route")).andExpect(status().isNotFound());
    }

    @Test
    void unsupportedMethodKeeps405() throws Exception {
        mvc.perform(post("/events")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void unsupportedMediaTypeKeeps415() throws Exception {
        mvc.perform(post("/admin/categories").contentType(MediaType.TEXT_PLAIN).content("Category"))
                .andExpect(status().isUnsupportedMediaType());
    }
}
