package com.taskflow.task;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record CreateTaskRequest(
        @NotBlank @Size(max = 120)
        @Schema(example = "Review Java collections") String title,
        @Size(max = 2000) String description,
        @Schema(defaultValue = "TODO") TaskStatus status,
        @Schema(defaultValue = "MEDIUM") TaskPriority priority,
        LocalDate dueDate) {
}
