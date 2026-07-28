package com.juanluidos.ticketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Hallazgo concreto de validación. Con línea asociada si el fallo es de una línea. */
@Entity
@Table(name = "validation_issue")
@Getter
@Setter
public class ValidationIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "line_item_id")
    private LineItem lineItem;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_code", nullable = false, length = 10)
    private CheckCode checkCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private IssueSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private IssueStatus status = IssueStatus.OPEN;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(precision = 14, scale = 4)
    private BigDecimal expected;

    @Column(precision = 14, scale = 4)
    private BigDecimal actual;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
