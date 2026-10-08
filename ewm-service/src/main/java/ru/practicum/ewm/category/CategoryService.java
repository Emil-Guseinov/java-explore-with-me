package ru.practicum.ewm.category;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.ewm.category.dto.CategoryDto;
import ru.practicum.ewm.category.dto.NewCategoryDto;
import ru.practicum.ewm.common.NotFoundException;
import ru.practicum.ewm.common.OffsetPageRequest;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryService {
    private final CategoryRepository categoryRepository;

    public List<CategoryDto> findAll(int from, int size) {
        return categoryRepository.findAllBy(new OffsetPageRequest(from, size, Sort.by("id")))
                .stream().map(CategoryMapper::toDto).toList();
    }

    public CategoryDto findById(long catId) {
        return CategoryMapper.toDto(getCategory(catId));
    }

    @Transactional
    public CategoryDto create(NewCategoryDto request) {
        Category category = categoryRepository.saveAndFlush(CategoryMapper.toEntity(request));
        return CategoryMapper.toDto(category);
    }

    @Transactional
    public CategoryDto update(long catId, CategoryDto request) {
        Category category = getCategory(catId);
        category.setName(request.getName());
        categoryRepository.flush();
        return CategoryMapper.toDto(category);
    }

    @Transactional
    public void delete(long catId) {
        Category category = getCategory(catId);
        categoryRepository.delete(category);
        categoryRepository.flush();
    }

    private Category getCategory(long catId) {
        return categoryRepository.findById(catId)
                .orElseThrow(() -> new NotFoundException("Category with id=" + catId + " was not found"));
    }
}
