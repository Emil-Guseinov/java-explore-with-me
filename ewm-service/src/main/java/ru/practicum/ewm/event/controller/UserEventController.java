package ru.practicum.ewm.event.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import ru.practicum.ewm.event.dto.EventFullDto;
import ru.practicum.ewm.event.dto.EventShortDto;
import ru.practicum.ewm.event.dto.NewEventDto;
import ru.practicum.ewm.event.dto.UpdateEventUserRequest;
import ru.practicum.ewm.event.service.EventService;

import java.util.List;

@RestController
@RequestMapping("/users/{userId}/events")
@RequiredArgsConstructor
@Validated
public class UserEventController {
    private final EventService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventFullDto create(@PathVariable @Positive long userId, @RequestBody @Valid NewEventDto dto) {
        return service.create(userId, dto);
    }

    @GetMapping
    public List<EventShortDto> list(@PathVariable @Positive long userId,
            @RequestParam(defaultValue = "0") @PositiveOrZero int from,
            @RequestParam(defaultValue = "10") @Positive int size) {
        return service.ownEvents(userId, from, size);
    }

    @GetMapping("/{eventId}")
    public EventFullDto get(@PathVariable @Positive long userId, @PathVariable @Positive long eventId) {
        return service.ownEvent(userId, eventId);
    }

    @PatchMapping("/{eventId}")
    public EventFullDto update(@PathVariable @Positive long userId, @PathVariable @Positive long eventId,
            @RequestBody @Valid UpdateEventUserRequest dto) {
        return service.updateByUser(userId, eventId, dto);
    }
}
