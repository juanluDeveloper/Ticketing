package com.juanluidos.ticketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Resultado de una comprobación sobre un ticket.
 *
 * <p>{@link #applicable} y {@link #passed} son campos distintos a propósito. Si
 * el formato del súper no imprime lo que la comprobación necesita, queda
 * {@code applicable = false} y {@code passed = null}: en la UI eso es gris "no
 * aplica", no verde. Colapsar los dos estados haría que un Mercadona pareciera
 * validado por C3 cuando C3 no ha podido ni ejecutarse.
 */
@Entity
@Table(name = "ticket_check_result")
@Getter
@Setter
public class TicketCheckResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @Enumerated(EnumType.STRING)
    @Column(name = "check_code", nullable = false, length = 10)
    private CheckCode checkCode;

    @Column(nullable = false)
    private boolean applicable;

    /** Null cuando no es aplicable. Nunca true por omisión. */
    private Boolean passed;

    /** Cuántas líneas ha podido cubrir. C2 solo cubre las que traen P.Unit impreso. */
    @Column(name = "lines_covered")
    private Integer linesCovered;

    @Column(length = 500)
    private String detail;

    @Column(name = "evaluated_at", nullable = false)
    private LocalDateTime evaluatedAt = LocalDateTime.now();
}
