package com.juanluidos.ticketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Lo que compara el comparador: el mismo artículo a través de varios súper.
 *
 * <p>Todos los miembros deben compartir {@link #comparisonDimension}; mezclar
 * dosis con litros da un ranking sin sentido.
 */
@Entity
@Table(name = "comparable_group")
@Getter
@Setter
public class ComparableGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(name = "comparison_dimension", nullable = false, length = 10)
    private Dimension comparisonDimension;

    /**
     * Unidad legible del ranking y del margen de preferencia: kg, L, ud.
     * No es la unidad base de la dimensión.
     */
    @Column(name = "comparison_unit", nullable = false, length = 10)
    private String comparisonUnit;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
