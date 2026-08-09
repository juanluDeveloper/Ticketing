package com.juanluidos.ticketing.comparison;

import com.juanluidos.ticketing.domain.*;
import com.juanluidos.ticketing.repository.ComparableGroupRepository;
import com.juanluidos.ticketing.repository.DeclaredPriceRepository;
import com.juanluidos.ticketing.repository.PriceObservationRepository;
import com.juanluidos.ticketing.repository.StoreProductRepository;
import com.juanluidos.ticketing.repository.UserProductPreferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * El comparador tiene que enseñar SIEMPRE las dos cosas: el más barato objetivo
 * y la elección ajustada por preferencia, con lo que cuesta la diferencia. Si
 * solo mostrara la recomendación, la prima se volvería invisible y dejaría de
 * ser una decisión consciente.
 */
class ComparisonServiceTest {

    private static final Long GROUP_ID = 7L;
    private static final Long USER_ID = 1L;

    private StoreProductRepository products;
    private PriceObservationRepository observations;
    private DeclaredPriceRepository declaredPrices;
    private UserProductPreferenceRepository preferences;
    private ComparisonService service;

    private ComparableGroup group;
    private final Map<Long, List<PriceObservation>> series = new HashMap<>();
    private final List<StoreProduct> members = new ArrayList<>();
    private long nextId = 100;

    @BeforeEach
    void setUp() {
        ComparableGroupRepository groups = mock(ComparableGroupRepository.class);
        products = mock(StoreProductRepository.class);
        observations = mock(PriceObservationRepository.class);
        declaredPrices = mock(DeclaredPriceRepository.class);
        preferences = mock(UserProductPreferenceRepository.class);
        service = new ComparisonService(groups, products, observations, declaredPrices, preferences);

        group = new ComparableGroup();
        group.setId(GROUP_ID);
        group.setName("Leche entera");
        group.setComparisonDimension(Dimension.VOLUME);
        group.setComparisonUnit("L");

        when(groups.findById(GROUP_ID)).thenReturn(Optional.of(group));
        when(products.findByComparableGroupId(GROUP_ID)).thenReturn(members);
        when(observations.findByStoreProductIdOrderByObservedAtAsc(any()))
                .thenAnswer(i -> series.getOrDefault(i.getArgument(0), List.of()));
        when(preferences.findByUserIdAndComparableGroupId(any(), any())).thenReturn(Optional.empty());
    }

    // ------------------------------------------------------------------

    @Test
    void ranksByNormalizedPriceCheapestFirst() {
        product("MERCADONA", "Mercadona", "LECHE FRESCA", price("1.15", 5));
        product("CASH_FRESH", "Cash Fresh", "LECHE ENTERA ACORES", price("0.79", 5));
        product("XINYA", "Xinya", "LECHE", price("1.40", 5));

        GroupComparison c = compare();

        assertThat(c.ranking()).extracting(GroupComparison.Entry::storeCode)
                .containsExactly("CASH_FRESH", "MERCADONA", "XINYA");
        assertThat(c.verdict().cheapest().storeName()).isEqualTo("Cash Fresh");
    }

    /** La antigüedad viaja con cada precio: esto no es un precio en vivo. */
    @Test
    void reportsTheAgeOfEveryPrice() {
        product("MERCADONA", "Mercadona", "LECHE FRESCA", price("1.15", 3));

        assertThat(compare().ranking().getFirst().ageDays()).isEqualTo(3);
    }

    /** El comparador responde a "dónde sale más barato", no a "qué pagué en oferta". */
    @Test
    void ignoresPromotionsAndUsesTheLastShelfPrice() {
        StoreProduct mercadona = product("MERCADONA", "Mercadona", "LECHE FRESCA",
                price("1.15", 40), promoPrice("0.79", 5));
        product("CASH_FRESH", "Cash Fresh", "LECHE ACORES", price("0.99", 5));

        GroupComparison c = compare();

        assertThat(c.ranking()).extracting(GroupComparison.Entry::normalizedUnitPrice)
                .containsExactly(new BigDecimal("0.99"), new BigDecimal("1.15"));
        assertThat(c.ranking()).extracting(GroupComparison.Entry::storeProductId)
                .contains(mercadona.getId());
    }

    // ------------------------------------------------------------------
    // Precio declarado a mano: el tubo de pota y sus excepciones
    // ------------------------------------------------------------------

    /**
     * Hay productos cuyo ticket no permite calcular el precio por unidad nunca:
     * el tubo de pota sale con nombre e importe, sin peso y sin €/kg. Sin el
     * precio declarado quedan fuera del comparador para siempre.
     */
    @Test
    void aDeclaredPriceLetsAnUnmeasurableProductEnterTheRanking() {
        product("MERCADONA", "Mercadona", "LECHE FRESCA", price("1.15", 5));
        declared(variablePiece("XINYA", "Xinya", "TUBO DE POTA", unnormalizedPrice("1.82", 6)),
                "0.99", "L", 10);

        GroupComparison c = compare();

        assertThat(c.notComparable()).isEmpty();
        assertThat(c.ranking()).extracting(GroupComparison.Entry::storeCode)
                .containsExactly("XINYA", "MERCADONA");
        assertThat(c.ranking().getFirst().declared()).isTrue();
        assertThat(c.ranking().getLast().declared()).isFalse();
    }

    /** El precio declarado envejece como cualquier otro: lleva su fecha. */
    @Test
    void aDeclaredPriceCarriesTheDayItWasRead() {
        declared(variablePiece("XINYA", "Xinya", "TUBO DE POTA"), "15.00", "kg", 12);

        assertThat(compare().ranking().getFirst().ageDays()).isEqualTo(12);
    }

    /** Un precio medido en un ticket gana siempre al tecleado, aunque sea más viejo. */
    @Test
    void aMeasuredPriceBeatsTheDeclaredOne() {
        declared(product("MERCADONA", "Mercadona", "LECHE FRESCA", price("1.15", 200)),
                "0.50", "L", 0);

        GroupComparison c = compare();

        assertThat(c.ranking().getFirst().normalizedUnitPrice()).isEqualByComparingTo("1.15");
        assertThat(c.ranking().getFirst().declared()).isFalse();
    }

    /** Y se dice en el aviso de calidad, que para eso está. */
    @Test
    void theDataWarningSaysWhenAPriceWasTypedByHand() {
        product("MERCADONA", "Mercadona", "LECHE FRESCA", price("1.15", 5));
        declared(variablePiece("XINYA", "Xinya", "TUBO DE POTA"), "0.99", "L", 3);

        assertThat(compare().dataWarning()).contains("no sale de un ticket");
    }

    /** Sin precio declarado, la pieza variable sigue fuera y con su motivo. */
    @Test
    void withoutADeclaredPriceTheVariablePieceStaysOut() {
        product("MERCADONA", "Mercadona", "LECHE FRESCA", price("1.15", 5));
        variablePiece("XINYA", "Xinya", "TUBO DE POTA", unnormalizedPrice("1.82", 6));

        GroupComparison c = compare();

        assertThat(c.ranking()).hasSize(1);
        assertThat(c.notComparable()).hasSize(1);
        assertThat(c.notComparable().getFirst().notComparableReason())
                .contains("decláralo a mano");
    }

    // ------------------------------------------------------------------
    // Lo que NO se puede comparar se aparta y se explica
    // ------------------------------------------------------------------

    @Test
    void separatesProductsNeverBoughtInThatStore() {
        product("MERCADONA", "Mercadona", "LECHE FRESCA", price("1.15", 5));
        product("XINYA", "Xinya", "LECHE", /* sin compras */ new PriceObservation[0]);

        GroupComparison c = compare();

        assertThat(c.ranking()).hasSize(1);
        assertThat(c.notComparable()).hasSize(1);
        assertThat(c.notComparable().getFirst().notComparableReason())
                .contains("Nunca lo he comprado en Xinya");
    }

    @Test
    void explainsAMissingPackageSize() {
        StoreProduct p = product("XINYA", "Xinya", "BEBIDA", unnormalizedPrice("1.65", 5));
        p.setPackageSize(null);
        p.setPackageUnit(null);

        assertThat(compare().notComparable().getFirst().notComparableReason())
                .contains("Falta el tamaño del envase");
    }

    @Test
    void explainsAVariablePiece() {
        StoreProduct p = product("MERCADONA", "Mercadona", "TUBO DE POTA",
                unnormalizedPrice("2.30", 5));
        p.setSoldBy(SoldBy.VARIABLE_PIECE);

        assertThat(compare().notComparable().getFirst().notComparableReason())
                .contains("peso variable");
    }

    @Test
    void explainsWhenEveryPurchaseWasOnPromotion() {
        product("MERCADONA", "Mercadona", "LECHE FRESCA", promoPrice("0.79", 5));

        assertThat(compare().notComparable().getFirst().notComparableReason())
                .contains("de oferta");
    }

    // ------------------------------------------------------------------
    // Preferencia (mecanismo A)
    // ------------------------------------------------------------------

    /** La prima cubre la diferencia: gana el preferido, y se dice lo que cuesta. */
    @Test
    void preferenceWinsWhenTheGapFitsInTheMargin() {
        StoreProduct mercadona = product("MERCADONA", "Mercadona", "LECHE FRESCA", price("1.15", 5));
        product("CASH_FRESH", "Cash Fresh", "LECHE ACORES", price("1.05", 5));
        preference(mercadona, MarginType.ABS, "0.15");

        GroupComparison c = compare();

        assertThat(c.verdict().cheapest().storeName()).isEqualTo("Cash Fresh");
        assertThat(c.verdict().chosen().storeName()).isEqualTo("Mercadona");
        assertThat(c.verdict().preferenceWins()).isTrue();
        // Las dos cosas SIEMPRE: el más barato y lo que cuesta no cogerlo.
        assertThat(c.verdict().preferenceCost()).isEqualByComparingTo("0.10");
        assertThat(c.verdict().explanation()).contains("Cash Fresh").contains("Mercadona");
    }

    /** La diferencia supera la prima: gana el barato, pero el coste sigue a la vista. */
    @Test
    void preferenceLosesWhenTheGapExceedsTheMargin() {
        StoreProduct mercadona = product("MERCADONA", "Mercadona", "LECHE FRESCA", price("1.45", 5));
        product("CASH_FRESH", "Cash Fresh", "LECHE ACORES", price("1.05", 5));
        preference(mercadona, MarginType.ABS, "0.15");

        GroupComparison c = compare();

        assertThat(c.verdict().preferenceWins()).isFalse();
        assertThat(c.verdict().chosen().storeName()).isEqualTo("Cash Fresh");
        assertThat(c.verdict().preferenceCost()).isEqualByComparingTo("0.40");
        assertThat(c.verdict().explanation()).contains("por encima de la prima");
    }

    /** El límite exacto cuenta como que entra: "pago hasta X más". */
    @Test
    void aGapExactlyEqualToTheMarginStillWins() {
        StoreProduct mercadona = product("MERCADONA", "Mercadona", "LECHE FRESCA", price("1.20", 5));
        product("CASH_FRESH", "Cash Fresh", "LECHE ACORES", price("1.05", 5));
        preference(mercadona, MarginType.ABS, "0.15");

        assertThat(compare().verdict().preferenceWins()).isTrue();
    }

    @Test
    void supportsAPercentageMargin() {
        StoreProduct mercadona = product("MERCADONA", "Mercadona", "LECHE FRESCA", price("1.10", 5));
        product("CASH_FRESH", "Cash Fresh", "LECHE ACORES", price("1.00", 5));
        // 10 % de 1,10 son 0,11, que cubre la diferencia de 0,10.
        preference(mercadona, MarginType.PCT, "10");

        assertThat(compare().verdict().preferenceWins()).isTrue();
    }

    @Test
    void saysTheMarginIsUnnecessaryWhenThePreferredIsAlreadyCheapest() {
        StoreProduct mercadona = product("MERCADONA", "Mercadona", "LECHE FRESCA", price("0.95", 5));
        product("CASH_FRESH", "Cash Fresh", "LECHE ACORES", price("1.05", 5));
        preference(mercadona, MarginType.ABS, "0.15");

        GroupComparison c = compare();

        assertThat(c.verdict().preferenceWins()).isTrue();
        assertThat(c.verdict().preferenceCost()).isEqualByComparingTo("0");
        assertThat(c.verdict().explanation()).contains("La prima no hace falta");
    }

    /** Una preferencia que no se puede aplicar se dice, no se ignora en silencio. */
    @Test
    void saysWhenThePreferredProductHasNoComparablePrice() {
        StoreProduct xinya = product("XINYA", "Xinya", "LECHE", new PriceObservation[0]);
        product("CASH_FRESH", "Cash Fresh", "LECHE ACORES", price("1.05", 5));
        preference(xinya, MarginType.ABS, "0.15");

        GroupComparison c = compare();

        assertThat(c.verdict().preferenceApplied()).isFalse();
        assertThat(c.verdict().explanation()).contains("no tiene precio comparable");
    }

    // ------------------------------------------------------------------
    // Avisos sobre la calidad del dato
    // ------------------------------------------------------------------

    @Test
    void warnsThatOneStoreAloneIsNotAComparison() {
        product("MERCADONA", "Mercadona", "LECHE FRESCA", price("1.15", 5));

        assertThat(compare().dataWarning()).contains("un súper");
    }

    /** Comparar un precio de esta semana con otro de hace medio año dice poco. */
    @Test
    void warnsWhenComparedPricesHaveVeryDifferentAges() {
        product("MERCADONA", "Mercadona", "LECHE FRESCA", price("1.15", 5));
        product("CASH_FRESH", "Cash Fresh", "LECHE ACORES", price("1.05", 200));

        String warning = compare().dataWarning();
        assertThat(warning).contains("fechas muy distintas");
        assertThat(warning).contains("200 días");
    }

    @Test
    void warnsAboutMembersLeftOutOfTheComparison() {
        product("MERCADONA", "Mercadona", "LECHE FRESCA", price("1.15", 5));
        product("CASH_FRESH", "Cash Fresh", "LECHE ACORES", price("1.05", 5));
        product("XINYA", "Xinya", "LECHE", new PriceObservation[0]);

        assertThat(compare().dataWarning()).contains("se queda fuera de la comparación");
    }

    @Test
    void saysSoWhenNothingIsComparableYet() {
        product("XINYA", "Xinya", "LECHE", new PriceObservation[0]);

        assertThat(compare().verdict().explanation()).contains("ningún precio comparable");
        assertThat(compare().verdict().cheapest()).isNull();
    }

    // ------------------------------------------------------------------

    private GroupComparison compare() {
        return service.compare(GROUP_ID, USER_ID);
    }

    private StoreProduct product(String storeCode, String storeName, String name,
                                 PriceObservation... prices) {
        Store store = new Store();
        store.setCode(storeCode);
        store.setName(storeName);

        StoreProduct product = new StoreProduct();
        product.setId(nextId++);
        product.setStore(store);
        product.setCanonicalName(name);
        product.setPackageSize(BigDecimal.ONE);
        product.setPackageUnit("L");
        product.setDimension(Dimension.VOLUME);
        product.setSoldBy(SoldBy.PACKAGE);

        members.add(product);
        series.put(product.getId(), List.of(prices));
        when(products.findById(product.getId())).thenReturn(Optional.of(product));
        return product;
    }

    /**
     * Pieza de peso variable comprada ahí, pero sin precio normalizable: el
     * tubo de pota, que el ticket imprime con nombre e importe y nada más.
     */
    private StoreProduct variablePiece(String storeCode, String storeName, String name,
                                       PriceObservation... prices) {
        StoreProduct product = product(storeCode, storeName, name, prices);
        product.setSoldBy(SoldBy.VARIABLE_PIECE);
        product.setPackageSize(null);
        product.setPackageUnit(null);
        return product;
    }

    private void declared(StoreProduct product, String price, String unit, int daysAgo) {
        DeclaredPrice entry = new DeclaredPrice();
        entry.setStoreProduct(product);
        entry.setUnitPrice(new BigDecimal(price));
        entry.setUnit(unit);
        entry.setDeclaredAt(LocalDate.now().minusDays(daysAgo));
        when(declaredPrices.findFirstByStoreProductIdOrderByDeclaredAtDesc(product.getId()))
                .thenReturn(Optional.of(entry));
    }

    private void preference(StoreProduct preferred, MarginType type, String value) {
        UserProductPreference p = new UserProductPreference();
        p.setComparableGroup(group);
        p.setPreferredStoreProduct(preferred);
        p.setMarginType(type);
        p.setMarginValue(new BigDecimal(value));
        when(preferences.findByUserIdAndComparableGroupId(USER_ID, GROUP_ID))
                .thenReturn(Optional.of(p));
    }

    private PriceObservation price(String normalized, int daysAgo) {
        return observation(normalized, daysAgo, false, true);
    }

    private PriceObservation promoPrice(String normalized, int daysAgo) {
        return observation(normalized, daysAgo, true, true);
    }

    private PriceObservation unnormalizedPrice(String perPiece, int daysAgo) {
        return observation(perPiece, daysAgo, false, false);
    }

    private PriceObservation observation(String value, int daysAgo, boolean promo, boolean normalized) {
        PriceObservation o = new PriceObservation();
        o.setObservedAt(LocalDateTime.now().minusDays(daysAgo));
        o.setQuantity(BigDecimal.ONE);
        o.setLineTotal(new BigDecimal(value));
        o.setPricePerPiece(new BigDecimal(value));
        if (normalized) {
            o.setNormalizedUnitPrice(new BigDecimal(value));
            o.setNormalizedUnit("L");
        }
        o.setPromo(promo);
        o.setCountsForIncrease(normalized && !promo);
        return o;
    }
}
