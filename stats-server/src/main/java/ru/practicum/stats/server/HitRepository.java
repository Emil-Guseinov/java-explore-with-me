package ru.practicum.stats.server;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.practicum.stats.dto.ViewStats;

import java.time.LocalDateTime;
import java.util.List;

public interface HitRepository extends JpaRepository<Hit, Long> {
    @Query("""
            select new ru.practicum.stats.dto.ViewStats(h.app, h.uri, count(h.id))
            from Hit h where h.timestamp between :start and :end
            group by h.app, h.uri order by count(h.id) desc, h.app, h.uri
            """)
    List<ViewStats> findStats(LocalDateTime start, LocalDateTime end);

    @Query("""
            select new ru.practicum.stats.dto.ViewStats(h.app, h.uri, count(distinct h.ip))
            from Hit h where h.timestamp between :start and :end
            group by h.app, h.uri order by count(distinct h.ip) desc, h.app, h.uri
            """)
    List<ViewStats> findUniqueStats(LocalDateTime start, LocalDateTime end);

    @Query("""
            select new ru.practicum.stats.dto.ViewStats(h.app, h.uri, count(h.id))
            from Hit h where h.timestamp between :start and :end and h.uri in :uris
            group by h.app, h.uri order by count(h.id) desc, h.app, h.uri
            """)
    List<ViewStats> findStatsByUris(LocalDateTime start, LocalDateTime end, List<String> uris);

    @Query("""
            select new ru.practicum.stats.dto.ViewStats(h.app, h.uri, count(distinct h.ip))
            from Hit h where h.timestamp between :start and :end and h.uri in :uris
            group by h.app, h.uri order by count(distinct h.ip) desc, h.app, h.uri
            """)
    List<ViewStats> findUniqueStatsByUris(LocalDateTime start, LocalDateTime end, List<String> uris);
}
