package ru.practicum.ewm.event;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.query.QueryUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventRepositoryCustomImpl implements EventRepositoryCustom {
    private final EntityManager entityManager;

    @Override
    public List<Long> findCandidateIds(Specification<Event> specification, long afterId, int size) {
        if (size < 1) {
            throw new IllegalArgumentException("Batch size must be positive");
        }
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = builder.createQuery(Long.class);
        Root<Event> root = query.from(Event.class);
        Predicate filter = specification == null ? null : specification.toPredicate(root, query, builder);
        Predicate after = builder.greaterThan(root.get("id"), afterId);
        query.select(root.get("id"));
        query.where(filter == null ? after : builder.and(filter, after));
        query.orderBy(builder.asc(root.get("id")));
        return entityManager.createQuery(query).setMaxResults(size).getResultList();
    }

    @Override
    public List<Event> findPage(Specification<Event> specification, Pageable pageable) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Event> query = builder.createQuery(Event.class);
        Root<Event> root = query.from(Event.class);
        root.fetch("category", JoinType.INNER);
        root.fetch("initiator", JoinType.INNER);
        query.select(root);
        Predicate filter = specification == null ? null : specification.toPredicate(root, query, builder);
        if (filter != null) {
            query.where(filter);
        }
        query.orderBy(QueryUtils.toOrders(pageable.getSort(), root, builder));
        return entityManager.createQuery(query)
                .setFirstResult(Math.toIntExact(pageable.getOffset()))
                .setMaxResults(pageable.getPageSize())
                .getResultList();
    }
}
