package ru.practicum.ewm.stats;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.practicum.ewm.event.repository.EventPublication;
import ru.practicum.ewm.event.repository.EventRepository;
import ru.practicum.ewm.stats.service.EventStatisticsService;
import ru.practicum.stats.client.StatsClient;
import ru.practicum.stats.dto.EndpointHit;
import ru.practicum.stats.dto.ViewStats;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventStatisticsServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    @Mock
    private StatsClient client;
    @Mock
    private EventRepository events;
    private EventStatisticsService service;

    @BeforeEach
    void setUp() {
        service = new EventStatisticsService(client, events, CLOCK, "events-test");
    }

    @Test
    void recordsConfiguredApplicationNameAndClockTime() {
        service.hit("/events/1", "127.0.0.1");

        verify(client).hit(new EndpointHit(null, "events-test", "/events/1", "127.0.0.1", NOW));
        verifyNoInteractions(events);
    }

    @Test
    void singleEventUsesPublicationTimeAndIgnoresOtherApplicationsAndUris() {
        LocalDateTime published = NOW.minusHours(2);
        when(events.findByIdInAndPublishedOnIsNotNull(List.of(1L)))
                .thenReturn(List.of(publication(1L, published)));
        when(client.getStats(published, NOW, List.of("/events/1"), true)).thenReturn(List.of(
                new ViewStats("events-test", "/events/1", 7L),
                new ViewStats("another-service", "/events/1", 50L),
                new ViewStats("events-test", "/events/2", 30L)));

        assertThat(service.views(List.of(1L))).containsOnlyKeys(1L).containsEntry(1L, 7L);
    }

    @Test
    void listUsesEarliestPublicationAndOmitsUnpublishedEvents() {
        LocalDateTime earliest = NOW.minusDays(3);
        when(events.findByIdInAndPublishedOnIsNotNull(List.of(1L, 2L, 3L)))
                .thenReturn(List.of(publication(1L, NOW.minusHours(1)), publication(2L, earliest)));
        when(client.getStats(earliest, NOW, List.of("/events/1", "/events/2"), true))
                .thenReturn(List.of(new ViewStats("events-test", "/events/2", 9L)));

        assertThat(service.views(List.of(1L, 2L, 3L, 1L))).containsOnlyKeys(2L).containsEntry(2L, 9L);
        verify(events).findByIdInAndPublishedOnIsNotNull(List.of(1L, 2L, 3L));
    }

    @Test
    void missingPublicationDoesNotRequestStatistics() {
        when(events.findByIdInAndPublishedOnIsNotNull(List.of(1L))).thenReturn(List.of());

        assertThat(service.views(List.of(1L))).isEmpty();
        assertThat(service.views(List.of())).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void publicationAfterRankingCutoffDoesNotProduceInvertedDateRange() {
        when(events.findByIdInAndPublishedOnIsNotNull(List.of(1L)))
                .thenReturn(List.of(publication(1L, NOW.plusSeconds(1))));

        assertThat(service.views(List.of(1L), NOW)).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void metadataAndHttpRequestsStayBoundedAndUseTheSameCutoff() {
        List<Long> ids = LongStream.rangeClosed(1, 205).boxed().toList();
        when(events.findByIdInAndPublishedOnIsNotNull(anyCollection())).thenAnswer(invocation -> {
            List<Long> batch = invocation.getArgument(0);
            assertThat(batch).hasSizeLessThanOrEqualTo(100);
            return batch.stream().map(id -> publication(id, NOW.minusHours(id))).toList();
        });
        when(client.getStats(any(), eq(NOW), anyList(), eq(true))).thenAnswer(invocation -> {
            List<String> uris = invocation.getArgument(2);
            assertThat(uris).hasSizeLessThanOrEqualTo(100);
            return uris.stream().map(uri -> new ViewStats("events-test", uri, 4L)).toList();
        });

        assertThat(service.views(ids, NOW)).hasSize(205);
        verify(client).getStats(NOW.minusHours(100), NOW,
                ids.subList(0, 100).stream().map(id -> "/events/" + id).toList(), true);
        verify(client).getStats(NOW.minusHours(200), NOW,
                ids.subList(100, 200).stream().map(id -> "/events/" + id).toList(), true);
        verify(client).getStats(NOW.minusHours(205), NOW,
                ids.subList(200, 205).stream().map(id -> "/events/" + id).toList(), true);
    }

    private EventPublication publication(long id, LocalDateTime publishedOn) {
        return new EventPublication() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public LocalDateTime getPublishedOn() {
                return publishedOn;
            }
        };
    }
}
