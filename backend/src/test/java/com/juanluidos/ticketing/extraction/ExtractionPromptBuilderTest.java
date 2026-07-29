package com.juanluidos.ticketing.extraction;

import com.juanluidos.ticketing.domain.Store;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El prompt sale de las capacidades declaradas del súper, no de una plantilla
 * escrita a mano. Estas pruebas fijan que cada bandera produzca su regla, para
 * que sembrar un cuarto supermercado baste.
 */
class ExtractionPromptBuilderTest {

    private final ExtractionPromptBuilder builder = new ExtractionPromptBuilder();

    @Test
    void alwaysDemandsTheLiteralRowFirst() {
        String prompt = builder.build(mercadona());

        assertThat(prompt).contains("raw_row_text");
        assertThat(prompt).contains("misma fila física");
        assertThat(prompt).contains("quantity * unit_price = line_total");
    }

    /** Nunca se piden datos de pago: no están en el esquema y no deben guardarse. */
    @Test
    void forbidsExtractingCardData() {
        assertThat(builder.build(mercadona()))
                .contains("NO extraigas números de tarjeta");
    }

    /** La regla del "12 HUEVOS" solo aplica donde el P.Unit es condicional. */
    @Test
    void explainsTheLeadingNumberTrapOnlyWhereItApplies() {
        assertThat(builder.build(mercadona())).contains("12 HUEVOS GRANDES-L");
        assertThat(builder.build(cashFresh())).doesNotContain("12 HUEVOS GRANDES-L");
    }

    @Test
    void asksForTheWeightSublineOnlyWhereItExists() {
        assertThat(builder.build(mercadona())).contains("sub-línea");
        assertThat(builder.build(xinya())).doesNotContain("sub-línea");
    }

    @Test
    void asksForTheTaxLetterOnlyWhereItIsPrinted() {
        assertThat(builder.build(cashFresh())).contains("letra de IVA (A, B o C)");
        assertThat(builder.build(mercadona())).contains("tax_letter va a null");
    }

    @Test
    void asksForTheArticleCountOnlyWhereItIsPrinted() {
        assertThat(builder.build(xinya())).contains("article_count");
        assertThat(builder.build(mercadona())).contains("article_count va a null");
    }

    @Test
    void declaresThePrintedDecimalSeparatorAndKeepsTheJsonOnDots() {
        assertThat(builder.build(xinya())).contains("\".\"").contains("en el JSON, punto igualmente");
        assertThat(builder.build(mercadona())).contains("\",\"");
    }

    private Store mercadona() {
        Store s = base("Mercadona", "A-46103834", ",");
        s.setUnitPriceOnlyWhenMultiple(true);
        s.setHasWeightSubline(true);
        return s;
    }

    private Store cashFresh() {
        Store s = base("Cash Fresh", "B41544503", ",");
        s.setHasLineTaxLetter(true);
        return s;
    }

    private Store xinya() {
        Store s = base("Xinya", "B-90379843", ".");
        s.setHasArticleCount(true);
        return s;
    }

    private Store base(String name, String taxId, String decimalSeparator) {
        Store s = new Store();
        s.setName(name);
        s.setTaxId(taxId);
        s.setDecimalSeparator(decimalSeparator);
        return s;
    }
}
