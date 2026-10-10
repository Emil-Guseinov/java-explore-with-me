package ru.practicum.ewm.comment.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import ru.practicum.ewm.user.dto.UserShortDto;

import java.time.LocalDateTime;

public record CommentDto(
        Long id,
        String text,
        Long event,
        UserShortDto author,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime created,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime updated
) {
}
