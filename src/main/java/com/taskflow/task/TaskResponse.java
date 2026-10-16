package com.taskflow.task;

import java.time.Instant;
import java.time.LocalDate;

public record TaskResponse(
        Long id,
        String title,
        String description,
        TaskStatus status,
        TaskPriority priority,
        LocalDate dueDate,
        Instant createdAt,
        Instant updatedAt) {

    static TaskResponse from(Task task) {
        return new TaskResponse(task.getId(), task.getTitle(), task.getDescription(),
                task.getStatus(), task.getPriority(), task.getDueDate(),
                task.getCreatedAt(), task.getUpdatedAt());
    }
}
