package ru.practicum.ewm.compilation.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;

@Getter
@Setter
public class UpdateCompilationRequest {
    @Size(min = 1, max = 50)
    @Pattern(regexp = "(?s).*\\S.*")
    private String title;
    private Boolean pinned;
    private Set<@NotNull @Positive Long> events;
}
