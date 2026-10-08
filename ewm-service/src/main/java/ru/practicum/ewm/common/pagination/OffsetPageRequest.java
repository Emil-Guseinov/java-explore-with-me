package ru.practicum.ewm.common.pagination;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import ru.practicum.ewm.common.exception.BadRequestException;

public class OffsetPageRequest implements Pageable {
    private final int offset;
    private final int size;
    private final Sort sort;

    public OffsetPageRequest(int offset, int size, Sort sort) {
        if (offset < 0 || size < 1) {
            throw new BadRequestException("from must be non-negative and size must be positive");
        }
        this.offset = offset;
        this.size = size;
        this.sort = sort;
    }

    @Override
    public int getPageNumber() {
        return offset / size;
    }

    @Override
    public int getPageSize() {
        return size;
    }

    @Override
    public long getOffset() {
        return offset;
    }

    @Override
    public Sort getSort() {
        return sort;
    }

    @Override
    public Pageable next() {
        return new OffsetPageRequest(Math.addExact(offset, size), size, sort);
    }

    @Override
    public Pageable previousOrFirst() {
        return new OffsetPageRequest(Math.max(0, offset - size), size, sort);
    }

    @Override
    public Pageable first() {
        return new OffsetPageRequest(0, size, sort);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        if (pageNumber < 0) {
            throw new IllegalArgumentException("Page number must be non-negative");
        }
        return new OffsetPageRequest(Math.multiplyExact(pageNumber, size), size, sort);
    }

    @Override
    public boolean hasPrevious() {
        return offset > 0;
    }
}
