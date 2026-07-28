package com.juanluidos.ticketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Tipo de IVA asociado a la letra impresa por línea. En Cash Fresh: A=4%, B=10%, C=21%. */
@Entity
@Table(name = "store_tax_letter")
@Getter
@Setter
public class StoreTaxLetter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(nullable = false, length = 1)
    private String letter;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal rate;
}
