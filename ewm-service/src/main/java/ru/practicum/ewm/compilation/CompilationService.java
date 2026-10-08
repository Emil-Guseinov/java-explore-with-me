package ru.practicum.ewm.compilation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.practicum.ewm.compilation.dto.CompilationDto;
import ru.practicum.ewm.compilation.dto.NewCompilationDto;
import ru.practicum.ewm.compilation.dto.UpdateCompilationRequest;
import ru.practicum.ewm.event.dto.EventShortDto;
import ru.practicum.ewm.stats.EventStatisticsService;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CompilationService {
    private final CompilationStorageService storage;
    private final EventStatisticsService statistics;

    public CompilationDto create(NewCompilationDto input) {
        Set<Long> ids = storage.prepareCreate(input.getEvents());
        Map<Long, Long> views = statistics.views(ids);
        return enrich(storage.create(input), views);
    }

    public CompilationDto update(long id, UpdateCompilationRequest input) {
        Set<Long> ids = storage.prepareUpdate(id, input.getEvents());
        Map<Long, Long> views = statistics.views(ids);
        return enrich(storage.update(id, input, ids), views);
    }

    public void delete(long id) {
        storage.delete(id);
    }

    public CompilationDto get(long id) {
        return enrich(List.of(storage.get(id))).getFirst();
    }

    public List<CompilationDto> list(Boolean pinned, int from, int size) {
        return enrich(storage.list(pinned, from, size));
    }

    private List<CompilationDto> enrich(List<CompilationDto> compilations) {
        List<Long> ids = compilations.stream().flatMap(compilation -> compilation.getEvents().stream())
                .map(EventShortDto::getId).distinct().toList();
        Map<Long, Long> views = statistics.views(ids);
        compilations.forEach(compilation -> enrich(compilation, views));
        return compilations;
    }

    private CompilationDto enrich(CompilationDto compilation, Map<Long, Long> views) {
        compilation.getEvents().forEach(event -> event.setViews(views.getOrDefault(event.getId(), 0L)));
        return compilation;
    }
}
