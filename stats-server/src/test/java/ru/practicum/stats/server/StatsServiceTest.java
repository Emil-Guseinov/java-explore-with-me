package ru.practicum.stats.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.stats.dto.EndpointHit;
import ru.practicum.stats.dto.ViewStats;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StatsServiceTest {
    private static final LocalDateTime START = LocalDateTime.of(2025, 1, 1, 10, 0);
    private static final LocalDateTime END = START.plusHours(1);

    @Autowired
    private StatsService service;

    @Autowired
    private HitRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void savesHitWithGeneratedId() {
        service.hit(new EndpointHit(777L, "main", "/events/1", "127.0.0.1", START));

        assertThat(repository.findAll()).singleElement().satisfies(hit -> {
            assertThat(hit.getId()).isPositive().isNotEqualTo(777L);
            assertThat(hit.getApp()).isEqualTo("main");
            assertThat(hit.getUri()).isEqualTo("/events/1");
            assertThat(hit.getIp()).isEqualTo("127.0.0.1");
            assertThat(hit.getTimestamp()).isEqualTo(START);
        });
    }

    @Test
    void groupsByBothAppAndUriAndSortsByHits() {
        addSampleHits();

        assertThat(service.getStats(START, END, null, false)).containsExactly(
                new ViewStats("app-a", "/events/1", 3L),
                new ViewStats("app-b", "/events/1", 2L),
                new ViewStats("app-a", "/events/2", 1L));
    }

    @Test
    void countsUniqueIpsInsideEachAppAndUri() {
        addSampleHits();

        assertThat(service.getStats(START, END, null, true)).containsExactly(
                new ViewStats("app-a", "/events/1", 2L),
                new ViewStats("app-a", "/events/2", 1L),
                new ViewStats("app-b", "/events/1", 1L));
    }

    @Test
    void appliesUriFilterToTotalCounts() {
        addSampleHits();

        assertThat(service.getStats(START, END, List.of("/events/1"), false)).containsExactly(
                new ViewStats("app-a", "/events/1", 3L),
                new ViewStats("app-b", "/events/1", 2L));
    }

    @Test
    void appliesUriFilterToUniqueCounts() {
        addSampleHits();

        assertThat(service.getStats(START, END, List.of("/events/1"), true)).containsExactly(
                new ViewStats("app-a", "/events/1", 2L),
                new ViewStats("app-b", "/events/1", 1L));
    }

    @Test
    void includesRangeBoundariesAndExcludesHitsOutsideRange() {
        addHit("main", "/events/1", "127.0.0.1", START.minusSeconds(1));
        addHit("main", "/events/1", "127.0.0.2", START);
        addHit("main", "/events/1", "127.0.0.3", END);
        addHit("main", "/events/1", "127.0.0.4", END.plusSeconds(1));

        assertThat(service.getStats(START, END, null, false))
                .containsExactly(new ViewStats("main", "/events/1", 2L));
        assertThat(service.getStats(START, START, null, false))
                .containsExactly(new ViewStats("main", "/events/1", 1L));
    }

    @Test
    void emptyUriListMeansAllUris() {
        addSampleHits();

        assertThat(service.getStats(START, END, List.of(), false))
                .isEqualTo(service.getStats(START, END, null, false));
        assertThat(service.getStats(START, END, List.of(), true))
                .isEqualTo(service.getStats(START, END, null, true));
    }

    @Test
    void returnsEmptyListWhenNoHitsMatch() {
        addSampleHits();

        assertThat(service.getStats(START, END, List.of("/events/404"), false)).isEmpty();
        assertThat(service.getStats(END.plusSeconds(1), END.plusHours(1), null, true)).isEmpty();
    }

    @Test
    void rejectsReversedRange() {
        assertThatThrownBy(() -> service.getStats(END, START, null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void addSampleHits() {
        addHit("app-a", "/events/1", "127.0.0.1", START);
        addHit("app-a", "/events/1", "127.0.0.1", START.plusSeconds(1));
        addHit("app-a", "/events/1", "127.0.0.2", START.plusSeconds(2));
        addHit("app-a", "/events/2", "127.0.0.1", START);
        addHit("app-b", "/events/1", "127.0.0.1", START);
        addHit("app-b", "/events/1", "127.0.0.1", START.plusSeconds(1));
    }

    private void addHit(String app, String uri, String ip, LocalDateTime time) {
        service.hit(new EndpointHit(null, app, uri, ip, time));
    }
}
