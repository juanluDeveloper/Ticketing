package com.juanluidos.ticketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Reducción aplicada al total de la compra, no a un producto concreto.
 *
 * <p>El importe se guarda como magnitud positiva aunque el recibo lo imprima
 * con signo menos. Así la relación contable no tiene ambigüedad:
 * {@code total compra - descuentos = total pagado}.
 */
@Entity
@Table(name = "ticket_general_discount")
@Getter
@Setter
public class TicketGeneralDiscount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @Column(nullable = false)
    private Integer position;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal amount;
}
