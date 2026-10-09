package ru.practicum.ewm.event.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import lombok.Data;

import org.springframework.format.annotation.DateTimeFormat;

import ru.practicum.ewm.event.model.EventSort;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class PublicEventSearch {
    @Size(min = 1, max = 7000)
    private String text;
    private List<Long> categories;
    private Boolean paid;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime rangeStart;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime rangeEnd;
    private boolean onlyAvailable = false;
    private EventSort sort = EventSort.EVENT_DATE;
    @PositiveOrZero
    private int from = 0;
    @Positive
    private int size = 10;
}
