package com.taskflow.task;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record UpdateTaskRequest(
        @NotBlank @Size(max = 120) String title,
        @Size(max = 2000)
        @Schema(description = "Omitting this field or sending null clears the description")
        String description,
        @NotNull TaskStatus status,
        @NotNull TaskPriority priority,
        @Schema(description = "Omitting this field or sending null clears the due date")
        LocalDate dueDate) {
}
