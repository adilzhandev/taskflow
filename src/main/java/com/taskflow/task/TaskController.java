package com.taskflow.task;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/tasks")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Tasks", description = "Manage the authenticated user's tasks")
public class TaskController {
    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    @ApiResponse(responseCode = "201", description = "Task created")
    @Operation(summary = "Create a task")
    public ResponseEntity<TaskResponse> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateTaskRequest request) {
        TaskResponse task = taskService.create(ownerId(jwt), request);
        return ResponseEntity.created(URI.create("/api/tasks/" + task.id())).body(task);
    }

    @GetMapping
    @Operation(summary = "List your tasks", description = "Sorted by createdAt DESC, then id DESC")
    public TaskListResponse list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) TaskPriority priority,
            @Parameter(description = "Page index, starting at 0", example = "0")
            @RequestParam(required = false) @Min(0) Integer page,
            @Parameter(description = "Page size, default 10", example = "10")
            @RequestParam(required = false) @Min(1) @Max(50) Integer size,
            HttpServletRequest request) {
        rejectBlankParameters(request);
        return taskService.list(ownerId(jwt), status, priority,
                page == null ? 0 : page, size == null ? 10 : size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get your task")
    public TaskResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return taskService.get(ownerId(jwt), id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace the editable fields of your task", description = "title, status, and "
            + "priority are required. Omitting description or dueDate, or sending null, clears them.")
    public TaskResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
            @Valid @RequestBody UpdateTaskRequest request) {
        return taskService.update(ownerId(jwt), id, request);
    }

    @DeleteMapping("/{id}")
    @ApiResponse(responseCode = "204", description = "Task deleted", content = @Content)
    @Operation(summary = "Delete your task")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        taskService.delete(ownerId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    private long ownerId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }

    private void rejectBlankParameters(HttpServletRequest request) {
        for (String name : new String[] {"status", "priority", "page", "size"}) {
            String value = request.getParameter(name);
            if (value != null && value.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Query parameter '" + name + "' must not be blank");
            }
        }
    }
}
