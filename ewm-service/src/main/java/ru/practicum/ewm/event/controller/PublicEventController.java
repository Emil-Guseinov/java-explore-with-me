package ru.practicum.ewm.event.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import lombok.RequiredArgsConstructor;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import ru.practicum.ewm.event.dto.EventFullDto;
import ru.practicum.ewm.event.dto.EventShortDto;
import ru.practicum.ewm.event.dto.PublicEventSearch;
import ru.practicum.ewm.event.service.EventService;

import java.util.List;

@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
@Validated
public class PublicEventController {
    private final EventService service;

    @GetMapping
    public List<EventShortDto> list(@Valid @ModelAttribute PublicEventSearch search, HttpServletRequest request) {
        return service.publicEvents(search, request.getRequestURI(), request.getRemoteAddr());
    }

    @GetMapping("/{id}")
    public EventFullDto get(@PathVariable @Positive long id, HttpServletRequest request) {
        return service.publicEvent(id, request.getRequestURI(), request.getRemoteAddr());
    }
}
