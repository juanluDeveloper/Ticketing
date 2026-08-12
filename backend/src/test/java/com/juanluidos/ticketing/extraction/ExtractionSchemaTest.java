package com.juanluidos.ticketing.extraction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El esquema del Anexo B es lo que impide que cada ticket salga con una forma
 * distinta. Estas pruebas son las que fallarían si alguien lo relaja.
 */
class ExtractionSchemaTest {

    private ObjectMapper mapper;
    private ExtractionSchemaProvider schemas;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        schemas = new ExtractionSchemaProvider(mapper);
    }

    @Test
    void acceptsAWellFormedExtraction() throws Exception {
        assertThat(schemas.validate(mapper.readTree(mercadonaJson()))).isEmpty();
    }

    /**
     * El motivo de fijar el esquema: sin él, el modelo devuelve las claves con
     * tildes y espacios ("precio unitario") y cada extracción sale distinta.
     */
    @Test
    void rejectsAccentedOrSpacedKeys() throws Exception {
        String json = mercadonaJson().replace("\"unit_price\":", "\"precio unitario\":");

        assertThat(schemas.validate(mapper.readTree(json))).isNotEmpty();
    }

    @Test
    void rejectsAMissingRequiredField() throws Exception {
        String json = mercadonaJson().replace("\"raw_row_text\": \"1 MANGO\",", "");

        assertThat(schemas.validate(mapper.readTree(json))).isNotEmpty();
    }

    /** additionalProperties: false — el modelo no puede colar campos de su cosecha. */
    @Test
    void rejectsAnUnexpectedField() throws Exception {
        String json = mercadonaJson().replace("\"currency\": \"EUR\",",
                "\"currency\": \"EUR\", \"tarjeta\": \"****9885\",");

        assertThat(schemas.validate(mapper.readTree(json))).isNotEmpty();
    }

    @Test
    void rejectsAnUnknownSoldByValue() throws Exception {
        String json = mercadonaJson().replace("\"sold_by\": \"weight\"", "\"sold_by\": \"a granel\"");

        assertThat(schemas.validate(mapper.readTree(json))).isNotEmpty();
    }

    /** El JSON lleva punto decimal aunque el ticket imprima coma. */
    @Test
    void rejectsNumbersSentAsStrings() throws Exception {
        String json = mercadonaJson().replace("\"line_total\": 4.25", "\"line_total\": \"4,25\"");

        assertThat(schemas.validate(mapper.readTree(json))).isNotEmpty();
    }

    @Test
    void mapsSnakeCaseKeysOntoTheRecord() throws Exception {
        ExtractedTicket ticket = mapper.readValue(mercadonaJson(), ExtractedTicket.class);

        assertThat(ticket.store().name()).isEqualTo("Mercadona");
        assertThat(ticket.store().nif()).isEqualTo("A-46103834");
        assertThat(ticket.receiptNumber()).isEqualTo("2276-012-556655");
        assertThat(ticket.decimalSeparator()).isEqualTo(",");
        assertThat(ticket.articleCount()).isNull();
        assertThat(ticket.totals().total()).isEqualByComparingTo(new BigDecimal("63.16"));
        assertThat(ticket.totals().generalDiscounts()).isEmpty();
        assertThat(ticket.totals().amountPaid()).isEqualByComparingTo(new BigDecimal("63.16"));
        assertThat(ticket.totals().taxBreakdown()).hasSize(1);

        var mango = ticket.lineItems().getFirst();
        assertThat(mango.rawRowText()).isEqualTo("1 MANGO");
        assertThat(mango.rawDescription()).isEqualTo("MANGO");
        assertThat(mango.soldBy()).isEqualTo("weight");
        assertThat(mango.weight().value()).isEqualByComparingTo(new BigDecimal("1.394"));
        assertThat(mango.weight().unit()).isEqualTo("kg");
        assertThat(mango.unitPrice()).isEqualByComparingTo(new BigDecimal("3.05"));
        assertThat(mango.lineTotal()).isEqualByComparingTo(new BigDecimal("4.25"));
        assertThat(mango.isPromo()).isFalse();
        assertThat(mango.taxLetter()).isNull();

        var pota = ticket.lineItems().get(1);
        assertThat(pota.soldBy()).isEqualTo("piece_variable");
        assertThat(pota.weight()).isNull();
    }

    @Test
    void mapsAGeneralCustomerVoucherSeparatelyFromProductLines() throws Exception {
        String json = mercadonaJson()
                .replace("\"total\": 63.16,", "\"total\": 67.12,")
                .replace("\"general_discounts\": [],",
                        "\"general_discounts\": [{ \"description\": \"VALES CLIENTES\", \"amount\": 3.52 }],")
                .replace("\"amount_paid\": 63.16,", "\"amount_paid\": 63.60,");

        assertThat(schemas.validate(mapper.readTree(json))).isEmpty();
        ExtractedTicket ticket = mapper.readValue(json, ExtractedTicket.class);

        assertThat(ticket.totals().total()).isEqualByComparingTo("67.12");
        assertThat(ticket.totals().generalDiscounts()).singleElement().satisfies(discount -> {
            assertThat(discount.description()).isEqualTo("VALES CLIENTES");
            assertThat(discount.amount()).isEqualByComparingTo("3.52");
        });
        assertThat(ticket.totals().amountPaid()).isEqualByComparingTo("63.60");
    }

    @Test
    void rejectsANegativeNormalizedGeneralDiscount() throws Exception {
        String json = mercadonaJson().replace("\"general_discounts\": [],",
                "\"general_discounts\": [{ \"description\": \"VALES CLIENTES\", \"amount\": -3.52 }],");

        assertThat(schemas.validate(mapper.readTree(json))).isNotEmpty();
    }

    /** Línea del Mercadona real: MANGO a peso 1,394 kg x 3,05 €/kg = 4,25. */
    private String mercadonaJson() {
        return """
                {
                  "store": {
                    "name": "Mercadona",
                    "nif": "A-46103834",
                    "address": "Avda. Nuestra Sra. de la Soledad, 62, 41320 Cantillana"
                  },
                  "purchased_at": "2026-06-24T21:51:00",
                  "receipt_number": "2276-012-556655",
                  "currency": "EUR",
                  "decimal_separator": ",",
                  "article_count": null,
                  "line_items": [
                    {
                      "raw_row_text": "1 MANGO",
                      "raw_description": "MANGO",
                      "quantity": 1,
                      "sold_by": "weight",
                      "weight": { "value": 1.394, "unit": "kg" },
                      "unit_price": 3.05,
                      "unit_price_unit": "kg",
                      "line_total": 4.25,
                      "tax_letter": null,
                      "is_promo": false,
                      "promo_note": null
                    },
                    {
                      "raw_row_text": "1 TUBO DE POTA                    2,30",
                      "raw_description": "TUBO DE POTA",
                      "quantity": 1,
                      "sold_by": "piece_variable",
                      "weight": null,
                      "unit_price": 2.30,
                      "unit_price_unit": "ud",
                      "line_total": 2.30,
                      "tax_letter": null,
                      "is_promo": false,
                      "promo_note": null
                    }
                  ],
                  "totals": {
                    "total": 63.16,
                    "general_discounts": [],
                    "amount_paid": 63.16,
                    "tax_breakdown": [
                      { "rate": 0.04, "base": 13.38, "tax": 0.54 }
                    ]
                  }
                }
                """;
    }
}
