package ru.practicum.ewm.event.repository;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import ru.practicum.ewm.event.model.Event;
import ru.practicum.ewm.event.model.EventState;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long>, JpaSpecificationExecutor<Event>,
        EventRepositoryCustom {
    @Override
    @EntityGraph(attributePaths = {"category", "initiator"})
    Page<Event> findAll(Specification<Event> specification, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"category", "initiator"})
    List<Event> findAll(Specification<Event> specification);

    @Override
    @EntityGraph(attributePaths = {"category", "initiator"})
    Optional<Event> findById(Long id);

    @Override
    @EntityGraph(attributePaths = {"category", "initiator"})
    List<Event> findAllById(Iterable<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Event e where e.id = :id")
    Optional<Event> findByIdForUpdate(@Param("id") Long id);

    @Query("select e.id from Event e where e.id in :ids")
    List<Long> findExistingIds(@Param("ids") Collection<Long> ids);

    @Query("select max(e.id) from Event e")
    Optional<Long> findMaximumId();

    List<EventPublication> findByIdInAndPublishedOnIsNotNull(Collection<Long> ids);

    boolean existsByCategoryId(Long categoryId);

    boolean existsByIdAndState(Long id, EventState state);
}
