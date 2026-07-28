package com.juanluidos.ticketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Una fila del desglose de IVA impreso. Alimenta C3 (bases por letra) y C5
 * (bases + cuotas contra el total).
 */
@Entity
@Table(name = "ticket_tax_summary")
@Getter
@Setter
public class TicketTaxSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    /** Solo Cash Fresh etiqueta las filas con letra; en los otros va a null. */
    @Column(name = "tax_letter", length = 1)
    private String taxLetter;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal rate;

    /** Xinya la imprime con tres decimales (8.182), de ahí la escala 4. */
    @Column(name = "base_amount", nullable = false, precision = 12, scale = 4)
    private BigDecimal baseAmount;

    @Column(name = "tax_amount", nullable = false, precision = 12, scale = 4)
    private BigDecimal taxAmount;
}
