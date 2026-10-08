package ru.practicum.ewm.request.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import ru.practicum.ewm.request.RequestStatus;

import java.time.LocalDateTime;

public record ParticipationRequestDto(
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime created,
        Long event,
        Long id,
        Long requester,
        RequestStatus status
) {
}
