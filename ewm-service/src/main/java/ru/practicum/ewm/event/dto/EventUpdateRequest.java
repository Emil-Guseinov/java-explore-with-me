package ru.practicum.ewm.event.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class EventUpdateRequest {
    @Size(min = 20, max = 2000)
    @Pattern(regexp = "(?s).*\\S.*")
    private String annotation;
    @Positive
    private Long category;
    @Size(min = 20, max = 7000)
    @Pattern(regexp = "(?s).*\\S.*")
    private String description;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Future
    private LocalDateTime eventDate;
    @Valid
    private LocationDto location;
    private Boolean paid;
    @PositiveOrZero
    private Integer participantLimit;
    private Boolean requestModeration;
    @Size(min = 3, max = 120)
    @Pattern(regexp = "(?s).*\\S.*")
    private String title;
}
