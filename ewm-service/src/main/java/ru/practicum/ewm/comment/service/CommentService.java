package ru.practicum.ewm.comment.service;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.practicum.ewm.comment.dto.CommentDto;
import ru.practicum.ewm.comment.dto.CommentRequest;
import ru.practicum.ewm.comment.mapper.CommentMapper;
import ru.practicum.ewm.comment.model.Comment;
import ru.practicum.ewm.comment.repository.CommentRepository;
import ru.practicum.ewm.common.exception.ConflictException;
import ru.practicum.ewm.common.exception.NotFoundException;
import ru.practicum.ewm.common.pagination.OffsetPageRequest;
import ru.practicum.ewm.event.model.Event;
import ru.practicum.ewm.event.model.EventState;
import ru.practicum.ewm.event.repository.EventRepository;
import ru.practicum.ewm.user.model.User;
import ru.practicum.ewm.user.repository.UserRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommentService {
    private final CommentRepository comments;
    private final EventRepository events;
    private final UserRepository users;
    private final Clock clock;

    @Transactional
    public CommentDto create(long userId, long eventId, CommentRequest request) {
        User author = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        Event event = events.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Event not found: " + eventId));
        if (event.getState() != EventState.PUBLISHED) {
            throw new ConflictException("Only published events accept comments");
        }
        Comment comment = new Comment();
        comment.setText(request.text().trim());
        comment.setAuthor(author);
        comment.setEvent(event);
        comment.setCreated(LocalDateTime.now(clock));
        return CommentMapper.toDto(comments.save(comment));
    }

    @Transactional
    public CommentDto update(long userId, long commentId, CommentRequest request) {
        Comment comment = ownedComment(userId, commentId);
        comment.setText(request.text().trim());
        comment.setUpdated(LocalDateTime.now(clock));
        return CommentMapper.toDto(comment);
    }

    @Transactional
    public void deleteByAuthor(long userId, long commentId) {
        comments.delete(ownedComment(userId, commentId));
    }

    @Transactional
    public void deleteByAdmin(long commentId) {
        comments.delete(lockedComment(commentId));
    }

    public List<CommentDto> list(long eventId, int from, int size) {
        OffsetPageRequest page = new OffsetPageRequest(from, size, Sort.unsorted());
        if (!events.existsByIdAndState(eventId, EventState.PUBLISHED)) {
            throw new NotFoundException("Published event not found: " + eventId);
        }
        return comments.findByEventIdOrderByCreatedDescIdDesc(eventId, page).stream()
                .map(CommentMapper::toDto).toList();
    }

    private Comment ownedComment(long userId, long commentId) {
        Comment comment = lockedComment(commentId);
        if (!comment.getAuthor().getId().equals(userId)) {
            throw new NotFoundException("Comment not found for user: " + userId);
        }
        return comment;
    }

    private Comment lockedComment(long commentId) {
        return comments.findByIdForUpdate(commentId)
                .orElseThrow(() -> new NotFoundException("Comment not found: " + commentId));
    }
}
