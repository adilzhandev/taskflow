package com.taskflow.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.taskflow.auth.User;
import com.taskflow.auth.UserRepository;
import com.taskflow.common.error.NotFoundException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {
    private static final long OWNER_ID = 7L;
    private static final long TASK_ID = 42L;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private UserRepository userRepository;

    private TaskService taskService;
    private User owner;

    @BeforeEach
    void setUp() {
        taskService = new TaskService(taskRepository, userRepository);
        owner = new User("student@example.com", "irrelevant-test-hash");
    }

    @Test
    void createAppliesDefaultsAndUsesTheAuthenticatedOwner() {
        when(userRepository.getReferenceById(OWNER_ID)).thenReturn(owner);
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TaskResponse response = taskService.create(OWNER_ID,
                new CreateTaskRequest("Study collections", null, null, null, null));

        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).save(taskCaptor.capture());
        Task savedTask = taskCaptor.getValue();
        assertThat(savedTask.getOwner()).isSameAs(owner);
        assertThat(response.title()).isEqualTo("Study collections");
        assertThat(response.status()).isEqualTo(TaskStatus.TODO);
        assertThat(response.priority()).isEqualTo(TaskPriority.MEDIUM);
        assertThat(response.description()).isNull();
        assertThat(response.dueDate()).isNull();
        verify(userRepository).getReferenceById(OWNER_ID);
    }

    @Test
    void createPreservesExplicitFieldsAndAllowsAPastDueDate() {
        when(userRepository.getReferenceById(OWNER_ID)).thenReturn(owner);
        when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));
        LocalDate overdue = LocalDate.of(2000, 1, 1);

        TaskResponse response = taskService.create(OWNER_ID,
                new CreateTaskRequest("Finish assignment", "Review edge cases", TaskStatus.IN_PROGRESS,
                        TaskPriority.HIGH, overdue));

        assertThat(response.title()).isEqualTo("Finish assignment");
        assertThat(response.description()).isEqualTo("Review edge cases");
        assertThat(response.status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(response.priority()).isEqualTo(TaskPriority.HIGH);
        assertThat(response.dueDate()).isEqualTo(overdue);
    }

    @Test
    void getFindsTheTaskByBothIdAndOwner() {
        Task task = existingTask();
        when(taskRepository.findByIdAndOwnerId(TASK_ID, OWNER_ID)).thenReturn(Optional.of(task));

        TaskResponse response = taskService.get(OWNER_ID, TASK_ID);

        assertThat(response.id()).isEqualTo(TASK_ID);
        assertThat(response.title()).isEqualTo("Original title");
        assertThat(response.createdAt()).isEqualTo(task.getCreatedAt());
        verify(taskRepository).findByIdAndOwnerId(TASK_ID, OWNER_ID);
        verifyNoMoreInteractions(taskRepository);
    }

    @Test
    void updateReplacesEditableFieldsAndPreservesTheOwnerAndCreationTime() {
        Task task = existingTask();
        var createdAt = task.getCreatedAt();
        LocalDate dueDate = LocalDate.of(2027, 5, 20);
        when(taskRepository.findByIdAndOwnerId(TASK_ID, OWNER_ID)).thenReturn(Optional.of(task));
        when(taskRepository.saveAndFlush(task)).thenReturn(task);

        TaskResponse response = taskService.update(OWNER_ID, TASK_ID,
                new UpdateTaskRequest("Updated title", "Updated description", TaskStatus.DONE,
                        TaskPriority.LOW, dueDate));

        assertThat(response.title()).isEqualTo("Updated title");
        assertThat(response.description()).isEqualTo("Updated description");
        assertThat(response.status()).isEqualTo(TaskStatus.DONE);
        assertThat(response.priority()).isEqualTo(TaskPriority.LOW);
        assertThat(response.dueDate()).isEqualTo(dueDate);
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(task.getOwner()).isSameAs(owner);
        verify(taskRepository).saveAndFlush(task);
    }

    @Test
    void updateClearsOptionalFieldsWhenTheyAreNull() {
        Task task = existingTask();
        when(taskRepository.findByIdAndOwnerId(TASK_ID, OWNER_ID)).thenReturn(Optional.of(task));
        when(taskRepository.saveAndFlush(task)).thenReturn(task);

        TaskResponse response = taskService.update(OWNER_ID, TASK_ID,
                new UpdateTaskRequest("Updated title", null, TaskStatus.TODO, TaskPriority.MEDIUM, null));

        assertThat(response.description()).isNull();
        assertThat(response.dueDate()).isNull();
        assertThat(task.getDescription()).isNull();
        assertThat(task.getDueDate()).isNull();
    }

    @Test
    void deleteRemovesOnlyTheTaskReturnedByTheOwnerScopedQuery() {
        Task task = existingTask();
        when(taskRepository.findByIdAndOwnerId(TASK_ID, OWNER_ID)).thenReturn(Optional.of(task));

        taskService.delete(OWNER_ID, TASK_ID);

        verify(taskRepository).findByIdAndOwnerId(TASK_ID, OWNER_ID);
        verify(taskRepository).delete(task);
        verifyNoMoreInteractions(taskRepository);
    }

    @Test
    void getReturnsNotFoundWhenTheTaskIsMissingOrOwnedBySomeoneElse() {
        when(taskRepository.findByIdAndOwnerId(TASK_ID, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.get(OWNER_ID, TASK_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Task not found");

        verify(taskRepository).findByIdAndOwnerId(TASK_ID, OWNER_ID);
        verifyNoMoreInteractions(taskRepository);
    }

    @Test
    void deniedUpdateDoesNotLoadOrSaveAnUnscopedTask() {
        when(taskRepository.findByIdAndOwnerId(TASK_ID, OWNER_ID)).thenReturn(Optional.empty());
        UpdateTaskRequest request = new UpdateTaskRequest("Unauthorized change", null,
                TaskStatus.DONE, TaskPriority.HIGH, null);

        assertThatThrownBy(() -> taskService.update(OWNER_ID, TASK_ID, request))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Task not found");

        verify(taskRepository).findByIdAndOwnerId(TASK_ID, OWNER_ID);
        verify(taskRepository, never()).saveAndFlush(any(Task.class));
        verify(taskRepository, never()).findById(TASK_ID);
        verifyNoMoreInteractions(taskRepository);
        verifyNoInteractions(userRepository);
    }

    @Test
    void deniedDeleteDoesNotLoadOrDeleteAnUnscopedTask() {
        when(taskRepository.findByIdAndOwnerId(TASK_ID, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> taskService.delete(OWNER_ID, TASK_ID))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Task not found");

        verify(taskRepository).findByIdAndOwnerId(TASK_ID, OWNER_ID);
        verify(taskRepository, never()).delete(any(Task.class));
        verify(taskRepository, never()).findById(TASK_ID);
        verifyNoMoreInteractions(taskRepository);
        verifyNoInteractions(userRepository);
    }

    @ParameterizedTest
    @MethodSource("filters")
    void listUsesOwnerScopedFiltersPaginationAndStableSorting(TaskStatus status, TaskPriority priority) {
        Task task = existingTask();
        Page<Task> page = new PageImpl<>(List.of(task), PageRequest.of(1, 2), 5);
        when(taskRepository.findAllForOwner(eq(OWNER_ID), eq(status), eq(priority), any(Pageable.class)))
                .thenReturn(page);

        TaskListResponse response = taskService.list(OWNER_ID, status, priority, 1, 2);

        ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(taskRepository).findAllForOwner(eq(OWNER_ID), eq(status), eq(priority), pageCaptor.capture());
        Pageable requestedPage = pageCaptor.getValue();
        assertThat(requestedPage.getPageNumber()).isEqualTo(1);
        assertThat(requestedPage.getPageSize()).isEqualTo(2);
        assertThat(requestedPage.getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().id()).isEqualTo(TASK_ID);
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(5);
        assertThat(response.totalPages()).isEqualTo(3);
    }

    @Test
    void listReturnsAnEmptyListAndZeroCounters() {
        when(taskRepository.findAllForOwner(eq(OWNER_ID), eq(null), eq(null), any(Pageable.class)))
                .thenReturn(Page.empty(PageRequest.of(0, 10)));

        TaskListResponse response = taskService.list(OWNER_ID, null, null, 0, 10);

        assertThat(response.items()).isEmpty();
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();
    }

    private static Stream<Arguments> filters() {
        return Stream.of(
                Arguments.of(null, null),
                Arguments.of(TaskStatus.IN_PROGRESS, null),
                Arguments.of(null, TaskPriority.HIGH),
                Arguments.of(TaskStatus.DONE, TaskPriority.LOW));
    }

    private Task existingTask() {
        Task task = new Task(owner, "Original title", "Original description", TaskStatus.TODO,
                TaskPriority.MEDIUM, LocalDate.of(2026, 1, 1));
        ReflectionTestUtils.setField(task, "id", TASK_ID);
        task.setCreationTimestamps();
        return task;
    }
}
