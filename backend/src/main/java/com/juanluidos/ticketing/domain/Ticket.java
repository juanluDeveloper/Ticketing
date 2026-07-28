package com.juanluidos.ticketing.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Una foto de recibo y todo lo que se ha derivado de ella. */
@Entity
@Table(name = "ticket")
@Getter
@Setter
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    /** Nulo hasta que la extracción identifica el súper por el NIF/CIF de la cabecera. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    private Store store;

    @Enumerated(EnumType.STRING)
    @Column(name = "ticket_type", nullable = false, length = 20)
    private TicketType ticketType = TicketType.SUPERMARKET;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private TicketStatus status = TicketStatus.UPLOADED;

    /** Hora local del súper, sin zona: los recibos son hora local y no interesa convertir. */
    @Column(name = "purchased_at")
    private LocalDateTime purchasedAt;

    /** Número impreso del recibo. Clave de deduplicación primaria junto al súper. */
    @Column(name = "receipt_number", length = 60)
    private String receiptNumber;

    @Column(precision = 12, scale = 4)
    private BigDecimal total;

    /** Recuento de artículos impreso. Solo Xinya lo trae; alimenta C4. */
    @Column(name = "article_count")
    private Integer articleCount;

    @Column(nullable = false, length = 3)
    private String currency = "EUR";

    /** La imagen original se conserva siempre, para reextraer y auditar. */
    @Column(name = "image_path", nullable = false, length = 500)
    private String imagePath;

    /** Señal secundaria de duplicado: solo detecta la misma foto byte a byte. */
    @Column(name = "image_sha256", nullable = false, length = 64)
    private String imageSha256;

    /**
     * Respuesta cruda del modelo, para poder auditar y reprocesar sin volver a
     * pasar por la GPU. No debe contener datos de tarjeta ni códigos de
     * autorización: el esquema de extracción los excluye a propósito.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_extraction", columnDefinition = "jsonb")
    private String rawExtraction;

    @Column(name = "extraction_model", length = 100)
    private String extractionModel;

    @Column(name = "extraction_started_at")
    private LocalDateTime extractionStartedAt;

    @Column(name = "extraction_finished_at")
    private LocalDateTime extractionFinishedAt;

    @Column(name = "extraction_error", columnDefinition = "text")
    private String extractionError;

    /**
     * Fracción de líneas que alguna comprobación ha podido cubrir. Se enseña en
     * la pantalla de validación: un ticket con cobertura baja necesita revisión
     * más atenta, porque las checks no han podido decir casi nada.
     */
    @Column(name = "coverage_ratio", precision = 5, scale = 4)
    private BigDecimal coverageRatio;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "validated_at")
    private LocalDateTime validatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "validated_by")
    private AppUser validatedBy;
}
