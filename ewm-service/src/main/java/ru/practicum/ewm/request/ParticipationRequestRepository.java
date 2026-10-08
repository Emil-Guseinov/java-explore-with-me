package ru.practicum.ewm.request;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.practicum.ewm.request.dto.ParticipationRequestDto;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ParticipationRequestRepository extends JpaRepository<ParticipationRequest, Long> {
    boolean existsByEventIdAndRequesterId(Long eventId, Long requesterId);

    long countByEventIdAndStatus(Long eventId, RequestStatus status);

    List<ParticipationRequest> findByRequesterIdOrderByIdAsc(Long requesterId);

    List<ParticipationRequest> findByEventIdOrderByIdAsc(Long eventId);

    List<ParticipationRequest> findByEventIdAndIdInOrderByIdAsc(Long eventId, Collection<Long> requestIds);

    @Query("select new ru.practicum.ewm.request.dto.ParticipationRequestDto("
            + "r.created, r.event.id, r.id, r.requester.id, r.status) from ParticipationRequest r "
            + "where r.event.id = :eventId and r.status = :status order by r.id")
    List<ParticipationRequestDto> findDtosByEventIdAndStatus(@Param("eventId") Long eventId,
                                                          @Param("status") RequestStatus status);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ParticipationRequest r set r.status = :newStatus "
            + "where r.event.id = :eventId and r.status = :currentStatus")
    int updateStatusByEventIdAndStatus(@Param("eventId") Long eventId,
                                     @Param("currentStatus") RequestStatus currentStatus,
                                     @Param("newStatus") RequestStatus newStatus);

    Optional<ParticipationRequest> findByIdAndRequesterId(Long id, Long requesterId);

    @Query("select r.event.id from ParticipationRequest r where r.id = :id and r.requester.id = :requesterId")
    Optional<Long> findEventId(@Param("id") Long id, @Param("requesterId") Long requesterId);

    @Query("select r.event.id as eventId, count(r.id) as total from ParticipationRequest r "
            + "where r.event.id in :ids and r.status = :status group by r.event.id")
    List<RequestCount> counts(@Param("ids") Collection<Long> ids, @Param("status") RequestStatus status);
}
