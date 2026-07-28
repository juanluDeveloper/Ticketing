package com.juanluidos.ticketing.repository;

import com.juanluidos.ticketing.domain.IssueSeverity;
import com.juanluidos.ticketing.domain.IssueStatus;
import com.juanluidos.ticketing.domain.ValidationIssue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ValidationIssueRepository extends JpaRepository<ValidationIssue, Long> {

    List<ValidationIssue> findByTicketId(Long ticketId);

    /** Un ticket con estos abiertos no puede pasar a VALIDATED. */
    List<ValidationIssue> findByTicketIdAndSeverityAndStatus(
            Long ticketId, IssueSeverity severity, IssueStatus status);

    void deleteByTicketId(Long ticketId);
}
