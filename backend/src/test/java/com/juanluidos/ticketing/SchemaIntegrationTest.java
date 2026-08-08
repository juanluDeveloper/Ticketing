package com.juanluidos.ticketing;

import com.juanluidos.ticketing.domain.Store;
import com.juanluidos.ticketing.repository.AppUserRepository;
import com.juanluidos.ticketing.repository.CategoryRepository;
import com.juanluidos.ticketing.repository.StoreRepository;
import com.juanluidos.ticketing.repository.StoreTaxLetterRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprueba contra un Postgres real que las migraciones Flyway aplican y que el
 * mapeo JPA cuadra con el esquema resultante ({@code ddl-auto: validate} falla
 * el arranque si no).
 *
 * <p>Necesita la base levantada: {@code docker compose up -d}.
 */
@SpringBootTest
class SchemaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private StoreRepository stores;

    @Autowired
    private StoreTaxLetterRepository taxLetters;

    @Autowired
    private CategoryRepository categories;

    @Test
    void migrationsApplyAndMappingValidates() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(applied).isEqualTo(5);
    }

    @Test
    void pgTrgmIsAvailable() {
        Double similarity = jdbc.queryForObject(
                "SELECT similarity('TUBO DE POTA', 'TUBO POTA')", Double.class);
        assertThat(similarity).isNotNull().isGreaterThan(0.0);
    }

    @Test
    void seedsTheSingleUser() {
        assertThat(users.findByUsername("juanluidos")).isPresent();
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void seedsTheThreeStoresWithTheirFormatCapabilities() {
        assertThat(stores.count()).isEqualTo(3);

        Store mercadona = stores.findByCode("MERCADONA").orElseThrow();
        assertThat(mercadona.getDecimalSeparator()).isEqualTo(",");
        assertThat(mercadona.isUnitPriceOnlyWhenMultiple()).isTrue();
        assertThat(mercadona.isHasWeightSubline()).isTrue();
        assertThat(mercadona.isHasLineTaxLetter()).isFalse();
        assertThat(mercadona.isHasArticleCount()).isFalse();

        Store cashFresh = stores.findByCode("CASH_FRESH").orElseThrow();
        assertThat(cashFresh.isHasLineTaxLetter()).isTrue();
        assertThat(cashFresh.isHasArticleCount()).isFalse();

        Store xinya = stores.findByCode("XINYA").orElseThrow();
        assertThat(xinya.getDecimalSeparator()).isEqualTo(".");
        assertThat(xinya.isHasArticleCount()).isTrue();
        assertThat(xinya.isHasLineTaxLetter()).isFalse();
    }

    /** Auto-detección del súper por el NIF/CIF de la cabecera. */
    @Test
    void findsStoresByTaxId() {
        assertThat(stores.findByTaxId("A-46103834")).isPresent();
        assertThat(stores.findByTaxId("B41544503")).isPresent();
        assertThat(stores.findByTaxId("B-90379843")).isPresent();
    }

    /** Los tipos de la letra de IVA de Cash Fresh: A=4%, B=10%, C=21%. */
    @Test
    void seedsCashFreshTaxLetters() {
        Store cashFresh = stores.findByCode("CASH_FRESH").orElseThrow();
        var letters = taxLetters.findByStoreId(cashFresh.getId());

        assertThat(letters).hasSize(3);
        assertThat(letters).anySatisfy(l -> {
            assertThat(l.getLetter()).isEqualTo("A");
            assertThat(l.getRate()).isEqualByComparingTo(new BigDecimal("0.0400"));
        });
        assertThat(letters).anySatisfy(l -> {
            assertThat(l.getLetter()).isEqualTo("B");
            assertThat(l.getRate()).isEqualByComparingTo(new BigDecimal("0.1000"));
        });
        assertThat(letters).anySatisfy(l -> {
            assertThat(l.getLetter()).isEqualTo("C");
            assertThat(l.getRate()).isEqualByComparingTo(new BigDecimal("0.2100"));
        });

        Store mercadona = stores.findByCode("MERCADONA").orElseThrow();
        assertThat(taxLetters.findByStoreId(mercadona.getId())).isEmpty();
    }

    @Test
    void seedsTheCategoryTree() {
        assertThat(categories.findByParentIsNullOrderByName()).hasSize(10);
        assertThat(categories.count()).isGreaterThan(40);

        var frescos = categories.findByCode("FRESCOS").orElseThrow();
        assertThat(categories.findByParentIdOrderByName(frescos.getId())).isNotEmpty();
    }

    /** Un grupo comparable no puede declarar una unidad ajena a su dimensión. */
    @Test
    void rejectsComparableGroupWithMismatchedUnit() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO comparable_group (name, comparison_dimension, comparison_unit)
                        VALUES ('grupo invalido', 'WEIGHT', 'L')
                        """))
                .hasMessageContaining("ck_group_unit_matches_dimension");
    }

    /** Un resultado de check no aplicable no puede guardarse como pasado. */
    @Test
    void rejectsCheckResultPassedWhenNotApplicable() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO ticket_check_result (ticket_id, check_code, applicable, passed)
                        VALUES (1, 'C3', FALSE, TRUE)
                        """))
                .hasMessageContaining("ck_check_passed_only_if_applicable");
    }
}
