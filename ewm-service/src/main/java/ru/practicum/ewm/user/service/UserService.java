package ru.practicum.ewm.user.service;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ru.practicum.ewm.common.exception.NotFoundException;
import ru.practicum.ewm.common.pagination.OffsetPageRequest;
import ru.practicum.ewm.user.dto.NewUserRequest;
import ru.practicum.ewm.user.dto.UserDto;
import ru.practicum.ewm.user.mapper.UserMapper;
import ru.practicum.ewm.user.model.User;
import ru.practicum.ewm.user.repository.UserRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {
    private final UserRepository userRepository;

    public List<UserDto> findAll(List<Long> ids, int from, int size) {
        Pageable pageable = new OffsetPageRequest(from, size, Sort.by("id"));
        List<User> users = ids == null || ids.isEmpty()
                ? userRepository.findAllBy(pageable)
                : userRepository.findAllByIdIn(ids, pageable);
        return users.stream().map(UserMapper::toDto).toList();
    }

    @Transactional
    public UserDto create(NewUserRequest request) {
        User user = userRepository.saveAndFlush(UserMapper.toEntity(request));
        return UserMapper.toDto(user);
    }

    @Transactional
    public void delete(long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User with id=" + userId + " was not found"));
        userRepository.delete(user);
        userRepository.flush();
    }
}
