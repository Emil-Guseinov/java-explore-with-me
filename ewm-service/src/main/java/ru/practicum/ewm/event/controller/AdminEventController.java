package ru.practicum.ewm.event.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import lombok.RequiredArgsConstructor;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import ru.practicum.ewm.event.dto.AdminEventSearch;
import ru.practicum.ewm.event.dto.EventFullDto;
import ru.practicum.ewm.event.dto.UpdateEventAdminRequest;
import ru.practicum.ewm.event.service.EventService;

import java.util.List;

@RestController
@RequestMapping("/admin/events")
@RequiredArgsConstructor
@Validated
public class AdminEventController {
    private final EventService service;

    @GetMapping
    public List<EventFullDto> list(@Valid @ModelAttribute AdminEventSearch search) {
        return service.adminEvents(search);
    }

    @PatchMapping("/{eventId}")
    public EventFullDto update(@PathVariable @Positive long eventId,
            @RequestBody @Valid UpdateEventAdminRequest dto) {
        return service.updateByAdmin(eventId, dto);
    }
}
