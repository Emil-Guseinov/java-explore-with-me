package ru.practicum.ewm.compilation.service;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.practicum.ewm.common.exception.ConflictException;
import ru.practicum.ewm.common.exception.NotFoundException;
import ru.practicum.ewm.common.pagination.OffsetPageRequest;
import ru.practicum.ewm.compilation.dto.CompilationDto;
import ru.practicum.ewm.compilation.dto.NewCompilationDto;
import ru.practicum.ewm.compilation.dto.UpdateCompilationRequest;
import ru.practicum.ewm.compilation.model.Compilation;
import ru.practicum.ewm.compilation.repository.CompilationId;
import ru.practicum.ewm.compilation.repository.CompilationRepository;
import ru.practicum.ewm.event.dto.EventShortDto;
import ru.practicum.ewm.event.model.Event;
import ru.practicum.ewm.event.repository.EventRepository;
import ru.practicum.ewm.event.service.EventStorageService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CompilationStorageService {
    private final CompilationRepository repository;
    private final EventRepository eventRepository;
    private final EventStorageService eventStorage;

    public Set<Long> prepareCreate(Set<Long> ids) {
        return requireExistingEvents(ids);
    }

    public Set<Long> prepareUpdate(long id, Set<Long> ids) {
        if (!repository.existsById(id)) {
            throw notFound(id);
        }
        return ids == null ? new LinkedHashSet<>(repository.findEventIds(id)) : requireExistingEvents(ids);
    }

    @Transactional
    public CompilationDto create(NewCompilationDto input) {
        Compilation compilation = new Compilation();
        compilation.setTitle(input.getTitle());
        compilation.setPinned(Boolean.TRUE.equals(input.getPinned()));
        compilation.setEvents(findEvents(input.getEvents()));
        repository.saveAndFlush(compilation);
        return toDtos(List.of(compilation)).getFirst();
    }

    @Transactional
    public CompilationDto update(long id, UpdateCompilationRequest input, Set<Long> expectedEventIds) {
        Compilation compilation = repository.findLockedById(id).orElseThrow(() -> notFound(id));
        if (input.getEvents() == null) {
            if (!new LinkedHashSet<>(repository.findEventIds(id)).equals(expectedEventIds)) {
                throw new ConflictException("Compilation events changed; retry the update");
            }
            compilation = repository.findDistinctByIdIn(List.of(id)).getFirst();
        } else {
            compilation.setEvents(findEvents(input.getEvents()));
        }
        if (input.getTitle() != null) {
            compilation.setTitle(input.getTitle());
        }
        if (input.getPinned() != null) {
            compilation.setPinned(input.getPinned());
        }
        repository.flush();
        return toDtos(List.of(compilation)).getFirst();
    }

    @Transactional
    public void delete(long id) {
        Compilation compilation = repository.findLockedById(id).orElseThrow(() -> notFound(id));
        repository.delete(compilation);
        repository.flush();
    }

    public CompilationDto get(long id) {
        Compilation compilation = repository.findById(id).orElseThrow(() -> notFound(id));
        return toDtos(List.of(compilation)).getFirst();
    }

    public List<CompilationDto> list(Boolean pinned, int from, int size) {
        Pageable page = new OffsetPageRequest(from, size, Sort.by("id"));
        List<CompilationId> selected = pinned == null
                ? repository.findAllBy(page) : repository.findAllByPinned(pinned, page);
        List<Long> ids = selected.stream().map(CompilationId::getId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Compilation> detailed = repository.findDistinctByIdIn(ids);
        detailed.sort(Comparator.comparing(Compilation::getId));
        return toDtos(detailed);
    }

    private Set<Long> requireExistingEvents(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        if (eventRepository.findExistingIds(ids).size() != ids.size()) {
            throw new NotFoundException("Some events in the compilation were not found");
        }
        return new LinkedHashSet<>(ids);
    }

    private Set<Event> findEvents(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new LinkedHashSet<>();
        }
        List<Event> events = eventRepository.findAllById(ids);
        if (events.size() != ids.size()) {
            throw new NotFoundException("Some events in the compilation were not found");
        }
        return new LinkedHashSet<>(events);
    }

    private List<CompilationDto> toDtos(List<Compilation> compilations) {
        Map<Long, Event> uniqueEvents = compilations.stream().flatMap(c -> c.getEvents().stream())
                .collect(Collectors.toMap(Event::getId, Function.identity(), (first, second) -> first));
        Map<Long, EventShortDto> events = eventStorage.shortDtos(new ArrayList<>(uniqueEvents.values())).stream()
                .collect(Collectors.toMap(EventShortDto::getId, Function.identity()));
        return compilations.stream().map(compilation -> new CompilationDto(compilation.getId(),
                compilation.getTitle(), compilation.getPinned(), compilation.getEvents().stream()
                .map(event -> events.get(event.getId())).sorted(Comparator.comparing(EventShortDto::getId)).toList()))
                .toList();
    }

    private NotFoundException notFound(long id) {
        return new NotFoundException("Compilation with id=" + id + " was not found");
    }
}
