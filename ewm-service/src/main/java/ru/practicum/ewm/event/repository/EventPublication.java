package ru.practicum.ewm.event.repository;

import java.time.LocalDateTime;

public interface EventPublication {
    Long getId();

    LocalDateTime getPublishedOn();
}
