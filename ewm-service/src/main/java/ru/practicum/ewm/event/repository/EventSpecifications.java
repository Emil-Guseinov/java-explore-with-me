package ru.practicum.ewm.event.repository;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import org.springframework.data.jpa.domain.Specification;

import ru.practicum.ewm.event.model.Event;
import ru.practicum.ewm.event.model.EventState;
import ru.practicum.ewm.request.model.ParticipationRequest;
import ru.practicum.ewm.request.model.RequestStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class EventSpecifications {
    private EventSpecifications() {
    }

    public static Specification<Event> publicSearch(String text, List<Long> categories, Boolean paid,
            LocalDateTime start, LocalDateTime end, boolean onlyAvailable, LocalDateTime now) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("state"), EventState.PUBLISHED));
            if (text != null && !text.isBlank()) {
                String pattern = "%" + escapeLike(text.toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("annotation")), pattern, '\\'),
                        cb.like(cb.lower(root.get("description")), pattern, '\\')));
            }
            if (categories != null && !categories.isEmpty()) {
                predicates.add(root.get("category").get("id").in(categories));
            }
            if (paid != null) {
                predicates.add(cb.equal(root.get("paid"), paid));
            }
            if (start == null && end == null) {
                predicates.add(cb.greaterThan(root.get("eventDate"), now));
            } else {
                if (start != null) {
                    predicates.add(cb.greaterThanOrEqualTo(root.get("eventDate"), start));
                }
                if (end != null) {
                    predicates.add(cb.lessThanOrEqualTo(root.get("eventDate"), end));
                }
            }
            if (onlyAvailable) {
                Subquery<Long> confirmed = query.subquery(Long.class);
                Root<ParticipationRequest> request = confirmed.from(ParticipationRequest.class);
                confirmed.select(cb.count(request));
                confirmed.where(cb.equal(request.get("event"), root),
                        cb.equal(request.get("status"), RequestStatus.CONFIRMED));
                predicates.add(cb.or(cb.equal(root.get("participantLimit"), 0),
                        cb.greaterThan(root.<Integer>get("participantLimit").as(Long.class), confirmed)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    public static Specification<Event> adminSearch(List<Long> users, List<EventState> states,
            List<Long> categories, LocalDateTime start, LocalDateTime end) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (users != null && !users.isEmpty()) {
                predicates.add(root.get("initiator").get("id").in(users));
            }
            if (states != null && !states.isEmpty()) {
                predicates.add(root.get("state").in(states));
            }
            if (categories != null && !categories.isEmpty()) {
                predicates.add(root.get("category").get("id").in(categories));
            }
            if (start != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("eventDate"), start));
            }
            if (end != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("eventDate"), end));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    public static Specification<Event> ownedBy(long userId) {
        return (root, query, cb) -> cb.equal(root.get("initiator").get("id"), userId);
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
