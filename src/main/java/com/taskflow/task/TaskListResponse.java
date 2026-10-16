package com.taskflow.task;

import java.util.List;

public record TaskListResponse(
        List<TaskResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
