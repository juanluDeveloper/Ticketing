package com.juanluidos.ticketing.history;

import com.juanluidos.ticketing.domain.*;
import com.juanluidos.ticketing.repository.PriceObservationRepository;
import com.juanluidos.ticketing.repository.StoreProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * El número de subidas es la métrica que más fácil se corrompe, porque cualquier
 * ruido de la serie se lee como inflación. Estas pruebas fijan qué NO cuenta.
 */
class ProductHistoryServiceTest {

    private StoreProductRepository products;
    private PriceObservationRepository observations;
    private ProductHistoryService service;
    private StoreProduct product;

    @BeforeEach
    void setUp() {
        products = mock(StoreProductRepository.class);
        observations = mock(PriceObservationRepository.class);
        service = new ProductHistoryService(products, observations);

        Store store = new Store();
        store.setCode("MERCADONA");
        store.setName("Mercadona");

        product = new StoreProduct();
        product.setStore(store);
        product.setCanonicalName("LECHE FRESCA ENT");
        product.setPackageSize(new BigDecimal("1"));
        product.setPackageUnit("L");
        product.setSoldBy(SoldBy.PACKAGE);

        when(products.findById(any())).thenReturn(Optional.of(product));
    }

    @Test
    void countsARealPriceRise() {
        given(
                observation("2026-01-10", "1.15", true, false),
                observation("2026-02-10", "1.25", true, false),
                observation("2026-03-10", "1.35", true, false));

        assertThat(history().metrics().increaseCount()).isEqualTo(2);
        assertThat(history().metrics().decreaseCount()).isZero();
    }

    @Test
    void countsDropsSeparately() {
        given(
                observation("2026-01-10", "1.35", true, false),
                observation("2026-02-10", "1.15", true, false),
                observation("2026-03-10", "1.35", true, false));

        assertThat(history().metrics().increaseCount()).isEqualTo(1);
        assertThat(history().metrics().decreaseCount()).isEqualTo(1);
    }

    /** Medio céntimo no es una subida de precio, es redondeo del importe. */
    @Test
    void ignoresChangesBelowOneCent() {
        given(
                observation("2026-01-10", "1.1500", true, false),
                observation("2026-02-10", "1.1550", true, false));

        assertThat(history().metrics().increaseCount()).isZero();
    }

    /**
     * En €/kg los valores son grandes y un céntimo absoluto se alcanza con
     * cualquier redondeo del tamaño del envase, así que hace falta también el
     * umbral relativo.
     */
    @Test
    void ignoresChangesBelowHalfAPercentEvenIfOverOneCent() {
        given(
                observation("2026-01-10", "20.00", true, false),
                observation("2026-02-10", "20.05", true, false));

        assertThat(history().metrics().increaseCount()).isZero();
    }

    /** Las promociones no son precio de estantería. */
    @Test
    void excludesPromotions() {
        given(
                observation("2026-01-10", "1.15", true, false),
                observation("2026-02-10", "0.79", false, true),
                observation("2026-03-10", "1.15", true, false));

        assertThat(history().metrics().increaseCount()).isZero();
        assertThat(history().metrics().comparablePoints()).isEqualTo(2);
        assertThat(history().metrics().excludedPoints()).isEqualTo(1);
    }

    /**
     * Los tres tubos de pota del mismo ticket: mismo producto, tres importes
     * distintos porque cada pieza pesa distinto. Ni una subida.
     */
    @Test
    void excludesVariablePieces() {
        product.setSoldBy(SoldBy.VARIABLE_PIECE);
        product.setPackageSize(null);
        product.setPackageUnit(null);

        List<PriceObservation> pieces = new ArrayList<>();
        for (String price : List.of("1.82", "2.30", "1.95")) {
            PriceObservation o = new PriceObservation();
            o.setObservedAt(LocalDateTime.parse("2026-06-24T21:51:00"));
            o.setQuantity(BigDecimal.ONE);
            o.setLineTotal(new BigDecimal(price));
            o.setPricePerPiece(new BigDecimal(price));
            o.setNormalizedUnitPrice(null);
            o.setCountsForIncrease(false);
            pieces.add(o);
        }
        when(observations.findByStoreProductIdOrderByObservedAtAsc(any())).thenReturn(pieces);

        PriceHistory h = history();
        assertThat(h.metrics().increaseCount()).isZero();
        assertThat(h.metrics().purchaseCount()).isEqualTo(3);
        assertThat(h.metrics().comparablePoints()).isZero();
        assertThat(h.notComparableReason()).contains("peso variable");
    }

    /**
     * Dos envases del mismo producto en el mismo ticket son el mismo precio de
     * estantería: sin colapsar por día, la serie inventaría escalones.
     */
    @Test
    void collapsesSeveralObservationsFromTheSameDay() {
        given(
                observation("2026-01-10", "1.15", true, false),
                observation("2026-01-10", "1.15", true, false),
                observation("2026-02-10", "1.25", true, false));

        assertThat(history().metrics().comparablePoints()).isEqualTo(2);
        assertThat(history().metrics().increaseCount()).isEqualTo(1);
    }

    @Test
    void reportsMinMaxAndLastPrice() {
        given(
                observation("2026-01-10", "1.15", true, false),
                observation("2026-02-10", "1.45", true, false),
                observation("2026-03-10", "1.25", true, false));

        var m = history().metrics();
        assertThat(m.minNormalizedUnitPrice()).isEqualByComparingTo("1.15");
        assertThat(m.maxNormalizedUnitPrice()).isEqualByComparingTo("1.45");
        assertThat(m.lastNormalizedUnitPrice()).isEqualByComparingTo("1.25");
        assertThat(m.normalizedUnit()).isEqualTo("L");
        assertThat(m.totalSpent()).isEqualByComparingTo("3.85");
    }

    /** Sin tamaño de envase no hay precio comparable, y hay que decir por qué. */
    @Test
    void explainsAMissingPackageSize() {
        product.setPackageSize(null);
        product.setPackageUnit(null);

        PriceObservation o = new PriceObservation();
        o.setObservedAt(LocalDateTime.parse("2026-01-10T12:00:00"));
        o.setQuantity(BigDecimal.ONE);
        o.setLineTotal(new BigDecimal("2.30"));
        o.setPricePerPiece(new BigDecimal("2.30"));
        o.setCountsForIncrease(false);
        when(observations.findByStoreProductIdOrderByObservedAtAsc(any())).thenReturn(List.of(o));

        assertThat(history().notComparableReason()).contains("tamaño del envase");
    }

    @Test
    void saysSoWhenThereAreNoPurchasesYet() {
        given();
        assertThat(history().notComparableReason()).contains("ninguna compra");
        assertThat(history().points()).isEmpty();
    }

    // ------------------------------------------------------------------

    private PriceHistory history() {
        return service.of(1L);
    }

    private void given(PriceObservation... items) {
        when(observations.findByStoreProductIdOrderByObservedAtAsc(any()))
                .thenReturn(List.of(items));
    }

    private PriceObservation observation(String date, String normalizedPrice,
                                        boolean counts, boolean promo) {
        PriceObservation o = new PriceObservation();
        o.setObservedAt(LocalDateTime.parse(date + "T12:00:00"));
        o.setQuantity(BigDecimal.ONE);
        o.setLineTotal(new BigDecimal(normalizedPrice));
        o.setPricePerPiece(new BigDecimal(normalizedPrice));
        o.setNormalizedUnitPrice(new BigDecimal(normalizedPrice));
        o.setNormalizedUnit("L");
        o.setPromo(promo);
        o.setCountsForIncrease(counts);
        return o;
    }
}
