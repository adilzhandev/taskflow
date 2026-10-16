package com.taskflow.task;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<Task, Long> {
    Optional<Task> findByIdAndOwnerId(long id, long ownerId);

    @Query("""
            select task from Task task
            where task.owner.id = :ownerId
              and (:status is null or task.status = :status)
              and (:priority is null or task.priority = :priority)
            """)
    Page<Task> findAllForOwner(@Param("ownerId") long ownerId,
            @Param("status") TaskStatus status,
            @Param("priority") TaskPriority priority,
            Pageable pageable);
}
