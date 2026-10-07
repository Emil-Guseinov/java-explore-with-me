package ru.practicum.stats.server;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.stats.dto.EndpointHit;
import ru.practicum.stats.dto.ViewStats;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StatsService {
    private final HitRepository repository;

    @Transactional
    public void hit(EndpointHit dto) {
        Hit hit = new Hit();
        hit.setApp(dto.getApp());
        hit.setUri(dto.getUri());
        hit.setIp(dto.getIp());
        hit.setTimestamp(dto.getTimestamp());
        repository.save(hit);
    }

    public List<ViewStats> getStats(LocalDateTime start, LocalDateTime end, List<String> uris, boolean unique) {
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("Начало диапазона не может быть позже конца");
        }
        if (uris == null || uris.isEmpty()) {
            return unique ? repository.findUniqueStats(start, end) : repository.findStats(start, end);
        }
        return unique ? repository.findUniqueStatsByUris(start, end, uris)
                : repository.findStatsByUris(start, end, uris);
    }
}
