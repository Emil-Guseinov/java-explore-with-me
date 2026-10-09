package ru.practicum.ewm.comment.mapper;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import ru.practicum.ewm.comment.dto.CommentDto;
import ru.practicum.ewm.comment.model.Comment;
import ru.practicum.ewm.user.mapper.UserMapper;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CommentMapper {
    public static CommentDto toDto(Comment comment) {
        return new CommentDto(comment.getId(), comment.getText(), comment.getEvent().getId(),
                UserMapper.toShortDto(comment.getAuthor()), comment.getCreated(), comment.getUpdated());
    }
}
