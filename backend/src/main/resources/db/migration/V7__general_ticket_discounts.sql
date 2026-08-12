-- Descuentos que se aplican al conjunto de la compra después de sumar los
-- artículos. No son promociones de producto: repartirlos entre las líneas
-- falsearía el histórico de precios.

ALTER TABLE ticket
    ADD COLUMN amount_paid NUMERIC(12, 4);

-- Los tickets anteriores no tenían descuentos generales: el total de compra
-- era también el importe pagado.
UPDATE ticket
SET amount_paid = total
WHERE total IS NOT NULL;

CREATE TABLE ticket_general_discount (
    id          BIGSERIAL PRIMARY KEY,
    ticket_id   BIGINT        NOT NULL REFERENCES ticket (id) ON DELETE CASCADE,
    position    INTEGER       NOT NULL,
    description VARCHAR(200)  NOT NULL,
    -- Se normaliza como magnitud positiva aunque el papel lo imprima con signo
    -- menos. La ecuación es: total compra - descuentos = total pagado.
    amount      NUMERIC(12, 4) NOT NULL,
    CONSTRAINT uq_ticket_general_discount_position UNIQUE (ticket_id, position),
    CONSTRAINT ck_ticket_general_discount_positive CHECK (amount > 0)
);

CREATE INDEX idx_ticket_general_discount_ticket
    ON ticket_general_discount (ticket_id);
