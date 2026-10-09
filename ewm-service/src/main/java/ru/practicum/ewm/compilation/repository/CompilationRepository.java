package ru.practicum.ewm.compilation.repository;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ru.practicum.ewm.compilation.model.Compilation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CompilationRepository extends JpaRepository<Compilation, Long> {
    List<CompilationId> findAllBy(Pageable pageable);

    List<CompilationId> findAllByPinned(boolean pinned, Pageable pageable);

    @Query("select e.id from Compilation c join c.events e where c.id = :id order by e.id")
    List<Long> findEventIds(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Compilation> findLockedById(Long id);

    @Override
    @EntityGraph(attributePaths = {"events", "events.category", "events.initiator"})
    Optional<Compilation> findById(Long id);

    @EntityGraph(attributePaths = {"events", "events.category", "events.initiator"})
    List<Compilation> findDistinctByIdIn(Collection<Long> ids);
}
