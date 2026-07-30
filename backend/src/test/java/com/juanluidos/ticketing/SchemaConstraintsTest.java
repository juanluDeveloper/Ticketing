package com.juanluidos.ticketing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cubre lo que {@code ddl-auto: validate} no mira y de lo que dependen dos
 * decisiones de diseño: la deduplicación de tickets y el borrado en cascada.
 *
 * <p>Validate comprueba que existan tablas y columnas con tipo compatible. No
 * comprueba índices ni reglas de integridad referencial, así que un índice único
 * mal escrito pasaría el arranque y solo se notaría como histórico de precios
 * duplicado meses después.
 *
 * <p>Necesita la base levantada: {@code docker compose up -d}.
 */
@SpringBootTest
@Transactional
class SchemaConstraintsTest {

    @Autowired
    private JdbcTemplate jdbc;

    private Long userId;
    private Long mercadonaId;
    private Long cashFreshId;

    /**
     * Los números de recibo se generan únicos por ejecución. Con valores fijos, el
     * test chocaba contra tickets reales ya guardados en la base de desarrollo —
     * el rollback protege lo que inserta el test, no lo que ya estaba.
     */
    private String receipt(String suffix) {
        return "TEST-" + java.util.UUID.randomUUID().toString().substring(0, 8) + "-" + suffix;
    }

    @BeforeEach
    void loadSeeds() {
        userId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE username = 'juanluidos'", Long.class);
        mercadonaId = jdbc.queryForObject(
                "SELECT id FROM store WHERE code = 'MERCADONA'", Long.class);
        cashFreshId = jdbc.queryForObject(
                "SELECT id FROM store WHERE code = 'CASH_FRESH'", Long.class);
    }

    // -----------------------------------------------------------------------
    // Deduplicación: índice único parcial sobre (store_id, receipt_number)
    // -----------------------------------------------------------------------

    /** El mismo ticket subido dos veces duplicaría todo su histórico de precios. */
    @Test
    void rejectsTheSameReceiptNumberTwiceInTheSameStore() {
        String number = receipt("dup");
        insertTicket(mercadonaId, number, "sha-a");

        assertThatThrownBy(() -> insertTicket(mercadonaId, number, "sha-b"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uq_ticket_receipt_number");
    }

    /** Dos súper distintos pueden numerar sus recibos igual sin que sea un duplicado. */
    @Test
    void allowsTheSameReceiptNumberInDifferentStores() {
        String shared = receipt("shared");
        insertTicket(mercadonaId, shared, "sha-c");

        assertThatCode(() -> insertTicket(cashFreshId, shared, "sha-d"))
                .doesNotThrowAnyException();
    }

    /**
     * El índice es parcial a propósito: si el número no se ha podido leer, el
     * ticket tiene que poder guardarse igual. Un índice único normal trataría
     * todos los nulos como el mismo valor a partir del segundo ticket sin número.
     */
    @Test
    void allowsSeveralTicketsWithoutReceiptNumber() {
        // Se mide el incremento, no el total: la base de desarrollo puede tener
        // ya tickets reales sin número y el test no debe depender de eso.
        int before = countTicketsWithoutReceiptNumber();

        insertTicket(mercadonaId, null, "sha-e");
        assertThatCode(() -> insertTicket(mercadonaId, null, "sha-f"))
                .doesNotThrowAnyException();

        assertThat(countTicketsWithoutReceiptNumber()).isEqualTo(before + 2);
    }

    private int countTicketsWithoutReceiptNumber() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ticket WHERE store_id = ? AND receipt_number IS NULL",
                Integer.class, mercadonaId);
    }

    // -----------------------------------------------------------------------
    // Borrado en cascada
    // -----------------------------------------------------------------------

    /** Borrar un ticket no puede dejar líneas, desglose de IVA ni hallazgos sueltos. */
    @Test
    void deletingATicketRemovesEverythingDerivedFromIt() {
        Long ticketId = insertTicket(mercadonaId, receipt("casc"), "sha-g");
        Long productId = insertStoreProduct("PRODUCTO CASCADA A");
        Long lineId = insertLineItem(ticketId, productId);
        insertTaxSummary(ticketId);
        insertCheckResult(ticketId);
        insertValidationIssue(ticketId, lineId);
        insertPriceObservation(productId, lineId);

        jdbc.update("DELETE FROM ticket WHERE id = ?", ticketId);

        assertThat(countBy("line_item", "ticket_id", ticketId)).isZero();
        assertThat(countBy("ticket_tax_summary", "ticket_id", ticketId)).isZero();
        assertThat(countBy("ticket_check_result", "ticket_id", ticketId)).isZero();
        assertThat(countBy("validation_issue", "ticket_id", ticketId)).isZero();

        // La serie de precios es dato derivado de las líneas: si desaparece el
        // ticket que la originó, la observación no puede sobrevivir sin
        // procedencia, o el conteo de subidas cuenta precios de un ticket que
        // ya no existe.
        //
        // Se cuenta por store_product_id y NO por line_item_id: con
        // ON DELETE SET NULL la fila sobrevive con la columna a null, así que
        // contar por line_item_id daría cero igualmente y el test no podría
        // fallar nunca.
        assertThat(countBy("price_observation", "store_product_id", productId)).isZero();
    }

    /** Borrar una línea suelta arrastra sus hallazgos y sus observaciones. */
    @Test
    void deletingALineItemRemovesItsIssuesAndObservations() {
        Long ticketId = insertTicket(mercadonaId, receipt("casc2"), "sha-h");
        Long productId = insertStoreProduct("PRODUCTO CASCADA B");
        Long lineId = insertLineItem(ticketId, productId);
        insertValidationIssue(ticketId, lineId);
        insertPriceObservation(productId, lineId);

        jdbc.update("DELETE FROM line_item WHERE id = ?", lineId);

        assertThat(countBy("validation_issue", "line_item_id", lineId)).isZero();
        assertThat(countBy("price_observation", "store_product_id", productId)).isZero();
        // El ticket sigue: se borró una línea, no el recibo.
        assertThat(countBy("ticket", "id", ticketId)).isEqualTo(1);
    }

    /** Borrar un producto arrastra sus alias; si no, el matcher sugeriría fantasmas. */
    @Test
    void deletingAStoreProductRemovesItsAliases() {
        Long productId = insertStoreProduct("PRODUCTO CASCADA C");
        jdbc.update("""
                INSERT INTO product_alias (store_product_id, store_id, raw_text, normalized_text)
                VALUES (?, ?, 'Producto Cascada C', 'PRODUCTO CASCADA C')
                """, productId, mercadonaId);

        jdbc.update("DELETE FROM store_product WHERE id = ?", productId);

        assertThat(countBy("product_alias", "store_product_id", productId)).isZero();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Long insertTicket(Long storeId, String receiptNumber, String sha) {
        return jdbc.queryForObject("""
                INSERT INTO ticket (user_id, store_id, receipt_number, total, image_path, image_sha256)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, userId, storeId, receiptNumber,
                new BigDecimal("63.16"), "/imagenes/" + sha + ".jpg", sha);
    }

    private Long insertStoreProduct(String canonicalName) {
        return jdbc.queryForObject("""
                INSERT INTO store_product (store_id, canonical_name, sold_by)
                VALUES (?, ?, 'PACKAGE')
                RETURNING id
                """, Long.class, mercadonaId, canonicalName);
    }

    private Long insertLineItem(Long ticketId, Long productId) {
        return jdbc.queryForObject("""
                INSERT INTO line_item (ticket_id, line_no, raw_description, quantity,
                                       line_total, store_product_id)
                VALUES (?, 1, 'TUBO DE POTA', 1, 2.30, ?)
                RETURNING id
                """, Long.class, ticketId, productId);
    }

    private void insertTaxSummary(Long ticketId) {
        jdbc.update("""
                INSERT INTO ticket_tax_summary (ticket_id, rate, base_amount, tax_amount)
                VALUES (?, 0.2100, 4.88, 1.02)
                """, ticketId);
    }

    private void insertCheckResult(Long ticketId) {
        jdbc.update("""
                INSERT INTO ticket_check_result (ticket_id, check_code, applicable, passed)
                VALUES (?, 'C1', TRUE, TRUE)
                """, ticketId);
    }

    private void insertValidationIssue(Long ticketId, Long lineId) {
        jdbc.update("""
                INSERT INTO validation_issue (ticket_id, line_item_id, check_code, severity, message)
                VALUES (?, ?, 'C2', 'ERROR', 'cantidad por precio no cuadra')
                """, ticketId, lineId);
    }

    private void insertPriceObservation(Long productId, Long lineId) {
        jdbc.update("""
                INSERT INTO price_observation (store_product_id, line_item_id, observed_at,
                                               quantity, line_total, price_per_piece)
                VALUES (?, ?, now(), 1, 2.30, 2.30)
                """, productId, lineId);
    }

    private Integer countBy(String table, String column, Long value) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
    }
}
