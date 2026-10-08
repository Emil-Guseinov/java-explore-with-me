package ru.practicum.ewm.event;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

public interface EventRepositoryCustom {
    List<Long> findCandidateIds(Specification<Event> specification, long afterId, int size);

    List<Event> findPage(Specification<Event> specification, Pageable pageable);
}
