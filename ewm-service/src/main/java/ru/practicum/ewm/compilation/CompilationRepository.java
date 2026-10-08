package ru.practicum.ewm.compilation;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CompilationRepository extends JpaRepository<Compilation, Long> {
    @Query("select c.id from Compilation c where :pinned is null or c.pinned = :pinned")
    List<Long> findIds(@Param("pinned") Boolean pinned, Pageable pageable);

    @Query("select e.id from Compilation c join c.events e where c.id = :id order by e.id")
    List<Long> findEventIds(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Compilation c where c.id = :id")
    Optional<Compilation> findByIdForUpdate(@Param("id") Long id);

    @Override
    @EntityGraph(attributePaths = {"events", "events.category", "events.initiator"})
    Optional<Compilation> findById(Long id);

    @EntityGraph(attributePaths = {"events", "events.category", "events.initiator"})
    @Query("select distinct c from Compilation c where c.id in :ids")
    List<Compilation> findDetailedByIds(@Param("ids") Collection<Long> ids);
}
