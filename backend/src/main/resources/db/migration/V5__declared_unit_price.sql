-- Precio por unidad declarado a mano.
--
-- Para los productos cuyo ticket no da lo suficiente para calcularlo nunca: el
-- tubo de pota de Mercadona sale impreso con el nombre y el importe, sin peso y
-- sin €/kg. Con esos dos datos no hay forma de saber a cuánto va el kilo, y el
-- producto queda condenado a no aparecer jamás en el comparador.
--
-- Lo que se guarda aquí NO es una observación de precio: no sale de un ticket,
-- no entra en la serie del histórico y no cuenta subidas. Es lo que el usuario
-- leyó en el cartel del mostrador, con la fecha en que lo leyó, y solo lo usa el
-- comparador como respaldo cuando no hay precio medido. Va etiquetado como
-- declarado en el ranking, para que nunca se confunda con lo que sí sale de un
-- ticket.
--
-- La unidad se guarda ya en canónica (kg, L, ud) porque es lo que compara el
-- ranking; la conversión desde lo que se teclee (g, ml, cl) la hace el servidor
-- antes de guardar.

ALTER TABLE store_product
    ADD COLUMN declared_unit_price NUMERIC(12, 4),
    ADD COLUMN declared_unit       VARCHAR(10)
        CONSTRAINT ck_product_declared_unit CHECK (declared_unit IN ('kg', 'L', 'ud')),
    ADD COLUMN declared_at         TIMESTAMP;

-- Precio sin unidad no se puede comparar y unidad sin precio no dice nada: o
-- los tres campos, o ninguno.
ALTER TABLE store_product
    ADD CONSTRAINT ck_product_declared_complete CHECK (
        (declared_unit_price IS NULL AND declared_unit IS NULL AND declared_at IS NULL)
            OR (declared_unit_price IS NOT NULL AND declared_unit IS NOT NULL AND declared_at IS NOT NULL)
        );

ALTER TABLE store_product
    ADD CONSTRAINT ck_product_declared_positive CHECK (
        declared_unit_price IS NULL OR declared_unit_price > 0
        );
