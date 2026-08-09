-- El precio declarado pasa de dato suelto a serie.
--
-- V5 lo guardó como tres columnas en store_product, y eso solo permite saber a
-- cuánto está hoy. Pero el precio del mostrador también cambia: si el 15 de
-- agosto la pota iba a 15 €/kg y el 14 de octubre la encuentras a 16, eso es
-- exactamente lo que se quiere ver, y con una sola casilla se perdía al
-- sobrescribirla.
--
-- Cada declaración es una fila con su fecha. La vigente es la última, y el resto
-- son el historial de lo que fuiste viendo en el cartel. Sigue sin mezclarse con
-- price_observation: aquello sale de tickets y esto de mirar una etiqueta.

CREATE TABLE declared_price
(
    id               BIGSERIAL PRIMARY KEY,
    store_product_id BIGINT         NOT NULL REFERENCES store_product (id) ON DELETE CASCADE,
    -- Ya en unidad canónica: kg, L o ud. La conversión desde lo que se teclee
    -- la hace el servidor, para que el ranking no tenga que adivinar.
    unit_price       NUMERIC(12, 4) NOT NULL
        CONSTRAINT ck_declared_price_positive CHECK (unit_price > 0),
    unit             VARCHAR(10)    NOT NULL
        CONSTRAINT ck_declared_price_unit CHECK (unit IN ('kg', 'L', 'ud')),
    -- El día en que se leyó el cartel, que puede no ser hoy: se permite
    -- registrar a posteriori el precio de una visita anterior.
    declared_at      DATE           NOT NULL,
    note             VARCHAR(200),
    created_at       TIMESTAMP      NOT NULL DEFAULT now(),
    -- Dos lecturas del mismo día son la misma lectura: la segunda corrige a la
    -- primera en vez de duplicarla.
    CONSTRAINT uq_declared_price_day UNIQUE (store_product_id, declared_at)
);

CREATE INDEX idx_declared_price_product ON declared_price (store_product_id, declared_at DESC);

-- Lo que hubiera declarado con V5 se conserva como la primera lectura.
INSERT INTO declared_price (store_product_id, unit_price, unit, declared_at)
SELECT id, declared_unit_price, declared_unit, declared_at::date
FROM store_product
WHERE declared_unit_price IS NOT NULL;

ALTER TABLE store_product
    DROP CONSTRAINT ck_product_declared_complete,
    DROP CONSTRAINT ck_product_declared_positive,
    DROP COLUMN declared_unit_price,
    DROP COLUMN declared_unit,
    DROP COLUMN declared_at;
