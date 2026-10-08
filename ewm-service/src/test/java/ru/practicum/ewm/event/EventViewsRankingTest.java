package ru.practicum.ewm.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;
import ru.practicum.ewm.category.Category;
import ru.practicum.ewm.category.CategoryRepository;
import ru.practicum.ewm.event.dto.EventShortDto;
import ru.practicum.ewm.request.ParticipationRequestRepository;
import ru.practicum.ewm.request.RequestCount;
import ru.practicum.ewm.request.RequestStatus;
import ru.practicum.ewm.stats.EventStatisticsService;
import ru.practicum.ewm.user.User;
import ru.practicum.ewm.user.UserRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventViewsRankingTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime CUTOFF = LocalDateTime.now(CLOCK);
    private static final Specification<Event> FILTER = EventSpecifications.publicSearch(
            null, null, null, null, null, false, CUTOFF);
    @Mock
    private EventRepository events;
    @Mock
    private UserRepository users;
    @Mock
    private CategoryRepository categories;
    @Mock
    private ParticipationRequestRepository requests;
    @Mock
    private EventStatisticsService statistics;
    private EventViewsRanking ranking;

    @BeforeEach
    void setUp() {
        EventStorageService storage = new EventStorageService(events, users, categories, requests, CLOCK);
        ranking = new EventViewsRanking(events, storage, statistics);
    }

    @Test
    void ranksAcrossThreeBatchesBeforeOffsetAndLoadsOnlySelectedDetailsAndCounts() {
        stubCandidates(211);
        Map<Long, Long> views = Map.of(208L, 1000L, 205L, 300L, 110L, 200L,
                5L, 100L, 101L, 100L, 203L, 100L);
        when(statistics.views(anyCollection(), eq(CUTOFF))).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return views.entrySet().stream().filter(entry -> ids.contains(entry.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        });
        when(events.findAllById(List.of(110L, 5L, 101L)))
                .thenReturn(List.of(event(101), event(5), event(110)));
        when(requests.counts(List.of(101L, 5L, 110L), RequestStatus.CONFIRMED))
                .thenReturn(List.of(count(101, 9), count(110, 3)));

        List<EventShortDto> result = ranking.page(FILTER, 2, 3, CUTOFF);

        assertThat(result).extracting(EventShortDto::getId).containsExactly(110L, 5L, 101L);
        assertThat(result).extracting(EventShortDto::getViews).containsExactly(200L, 100L, 100L);
        assertThat(result).extracting(EventShortDto::getConfirmedRequests).containsExactly(3L, 0L, 9L);
        verify(events).findCandidateIds(any(), eq(0L), eq(100));
        verify(events).findCandidateIds(any(), eq(100L), eq(100));
        verify(events).findCandidateIds(any(), eq(200L), eq(100));
        verify(statistics).views(ids(1, 100), CUTOFF);
        verify(statistics).views(ids(101, 200), CUTOFF);
        verify(statistics).views(ids(201, 211), CUTOFF);
        verifyNoMoreInteractions(statistics);
        verify(events).findAllById(List.of(110L, 5L, 101L));
        verify(requests).counts(List.of(101L, 5L, 110L), RequestStatus.CONFIRMED);
        verifyNoMoreInteractions(requests);
        verifyNoInteractions(users, categories);
    }

    @Test
    void missingViewsAreZeroAndTiesUseAscendingIdAcrossBatches() {
        stubCandidates(209);
        when(statistics.views(anyCollection(), eq(CUTOFF))).thenReturn(Map.of());
        when(events.findAllById(List.of(102L, 103L, 104L)))
                .thenReturn(List.of(event(104), event(102), event(103)));

        List<EventShortDto> result = ranking.page(FILTER, 101, 3, CUTOFF);

        assertThat(result).extracting(EventShortDto::getId).containsExactly(102L, 103L, 104L);
        assertThat(result).extracting(EventShortDto::getViews).containsExactly(0L, 0L, 0L);
        verify(statistics, times(3)).views(anyCollection(), eq(CUTOFF));
        verify(requests).counts(List.of(104L, 102L, 103L), RequestStatus.CONFIRMED);
    }

    @Test
    void fromPlusSizeDoesNotOverflowOrAllocateQueueForTheWholeRequestedRange() {
        stubCandidates(3);
        when(statistics.views(List.of(1L, 2L, 3L), CUTOFF)).thenReturn(Map.of(3L, 5L));

        assertThat(ranking.page(FILTER, Integer.MAX_VALUE, Integer.MAX_VALUE, CUTOFF)).isEmpty();

        verify(events, never()).findAllById(any());
        verifyNoInteractions(requests);
    }

    @Test
    void emptyRepositoryDoesNotQueryStatisticsOrHydrateEvents() {
        when(events.findMaximumId()).thenReturn(Optional.empty());

        assertThat(ranking.page(FILTER, 0, 10, CUTOFF)).isEmpty();

        verify(events).findMaximumId();
        verifyNoMoreInteractions(events);
        verifyNoInteractions(statistics, requests);
    }

    @Test
    void emptyFilteredSetDoesNotQueryStatisticsOrHydrateEvents() {
        when(events.findMaximumId()).thenReturn(Optional.of(500L));
        when(events.findCandidateIds(any(), eq(0L), eq(100))).thenReturn(List.of());

        assertThat(ranking.page(FILTER, 0, 10, CUTOFF)).isEmpty();

        verify(events, never()).findAllById(any());
        verifyNoInteractions(statistics, requests);
    }

    @Test
    void failureInLaterStatisticsBatchDoesNotReturnPartiallyRankedPage() {
        stubCandidates(101);
        when(statistics.views(ids(1, 100), CUTOFF)).thenReturn(Map.of(1L, 10L));
        when(statistics.views(List.of(101L), CUTOFF)).thenThrow(new IllegalStateException("Stats unavailable"));

        assertThatThrownBy(() -> ranking.page(FILTER, 0, 1, CUTOFF))
                .isInstanceOf(IllegalStateException.class).hasMessage("Stats unavailable");

        verify(events, never()).findAllById(any());
        verifyNoInteractions(requests);
    }

    @Test
    void eventDeletedBetweenRankingAndHydrationIsOmittedWithoutLosingOrder() {
        stubCandidates(3);
        when(statistics.views(List.of(1L, 2L, 3L), CUTOFF)).thenReturn(Map.of(3L, 20L, 2L, 10L));
        when(events.findAllById(List.of(3L, 2L, 1L))).thenReturn(List.of(event(1), event(3)));

        List<EventShortDto> result = ranking.page(FILTER, 0, 3, CUTOFF);

        assertThat(result).extracting(EventShortDto::getId).containsExactly(3L, 1L);
        assertThat(result).extracting(EventShortDto::getViews).containsExactly(20L, 0L);
        verify(requests).counts(List.of(1L, 3L), RequestStatus.CONFIRMED);
    }

    private void stubCandidates(long maximumId) {
        when(events.findMaximumId()).thenReturn(Optional.of(maximumId));
        when(events.findCandidateIds(any(), anyLong(), eq(100))).thenAnswer(invocation -> {
            long afterId = invocation.getArgument(1);
            return ids(afterId + 1, Math.min(afterId + 100, maximumId));
        });
    }

    private List<Long> ids(long first, long last) {
        return LongStream.rangeClosed(first, last).boxed().toList();
    }

    private Event event(long id) {
        Category category = new Category();
        category.setId(1L);
        category.setName("Walks");
        User user = new User();
        user.setId(1L);
        user.setName("Owner");
        Event event = new Event();
        event.setId(id);
        event.setCategory(category);
        event.setInitiator(user);
        return event;
    }

    private RequestCount count(long eventId, long total) {
        return new RequestCount() {
            @Override
            public Long getEventId() {
                return eventId;
            }

            @Override
            public Long getTotal() {
                return total;
            }
        };
    }
}
