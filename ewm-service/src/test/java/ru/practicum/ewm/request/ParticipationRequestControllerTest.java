package ru.practicum.ewm.request;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ru.practicum.ewm.common.exception.ErrorHandler;
import ru.practicum.ewm.request.controller.ParticipationRequestController;
import ru.practicum.ewm.request.dto.ParticipationRequestDto;
import ru.practicum.ewm.request.model.RequestStatus;
import ru.practicum.ewm.request.service.ParticipationRequestService;

import java.time.Clock;
import java.time.LocalDateTime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ParticipationRequestControllerTest {
    private ParticipationRequestService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ParticipationRequestService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ParticipationRequestController(service))
                .setControllerAdvice(new ErrorHandler(Clock.systemUTC()))
                .build();
    }

    @Test
    void creationReturns201AndContractFields() throws Exception {
        when(service.create(2L, 3L)).thenReturn(new ParticipationRequestDto(
                LocalDateTime.of(2030, 1, 1, 12, 0), 3L, 7L, 2L, RequestStatus.PENDING));

        mvc.perform(post("/users/2/requests").param("eventId", "3"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.requester").value(2))
                .andExpect(jsonPath("$.event").value(3))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.created").value("2030-01-01 12:00:00"));
    }

    @Test
    void creationRequiresEventId() throws Exception {
        mvc.perform(post("/users/2/requests"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"requestIds\":[],\"status\":\"CONFIRMED\"}",
            "{\"requestIds\":[1],\"status\":\"CANCELED\"}",
            "{\"requestIds\":[1],\"status\":\"PENDING\"}",
            "{\"requestIds\":[-1],\"status\":\"CONFIRMED\"}",
            "{\"requestIds\":[null],\"status\":\"REJECTED\"}"
    })
    void malformedModerationRequestIsRejectedBeforeService(String body) throws Exception {
        mvc.perform(patch("/users/2/events/3/requests")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }
}
