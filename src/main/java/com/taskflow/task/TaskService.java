package com.taskflow.task;

import com.taskflow.auth.User;
import com.taskflow.auth.UserRepository;
import com.taskflow.common.error.NotFoundException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TaskService {
    private final TaskRepository taskRepository;
    private final UserRepository userRepository;

    public TaskService(TaskRepository taskRepository, UserRepository userRepository) {
        this.taskRepository = taskRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public TaskResponse create(long ownerId, CreateTaskRequest request) {
        User owner = userRepository.getReferenceById(ownerId);
        TaskStatus status = request.status() == null ? TaskStatus.TODO : request.status();
        TaskPriority priority = request.priority() == null ? TaskPriority.MEDIUM : request.priority();
        Task task = new Task(owner, request.title(), request.description(), status,
                priority, request.dueDate());
        return TaskResponse.from(taskRepository.save(task));
    }

    public TaskResponse get(long ownerId, long id) {
        return TaskResponse.from(findOwnedTask(ownerId, id));
    }

    @Transactional
    public TaskResponse update(long ownerId, long id, UpdateTaskRequest request) {
        Task task = findOwnedTask(ownerId, id);
        task.replaceEditableFields(request.title(), request.description(), request.status(),
                request.priority(), request.dueDate());
        // Flush before mapping so @PreUpdate supplies the timestamp returned to the client.
        return TaskResponse.from(taskRepository.saveAndFlush(task));
    }

    @Transactional
    public void delete(long ownerId, long id) {
        Task task = findOwnedTask(ownerId, id);
        taskRepository.delete(task);
    }

    public TaskListResponse list(long ownerId, TaskStatus status, TaskPriority priority,
            int page, int size) {
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt", "id");
        Page<Task> tasks = taskRepository.findAllForOwner(ownerId, status, priority,
                PageRequest.of(page, size, sort));
        List<TaskResponse> items = new ArrayList<>();
        for (Task task : tasks) {
            items.add(TaskResponse.from(task));
        }
        return new TaskListResponse(items, tasks.getNumber(), tasks.getSize(),
                tasks.getTotalElements(), tasks.getTotalPages());
    }

    private Task findOwnedTask(long ownerId, long id) {
        return taskRepository.findByIdAndOwnerId(id, ownerId)
                .orElseThrow(() -> new NotFoundException("Task not found"));
    }
}
