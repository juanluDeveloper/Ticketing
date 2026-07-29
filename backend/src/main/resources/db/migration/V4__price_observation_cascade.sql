-- price_observation.line_item_id pasa de ON DELETE SET NULL a ON DELETE CASCADE.
--
-- V1 lo dejó en SET NULL, que deja huérfanos silenciosos: al borrar un ticket
-- desaparecen sus líneas, pero las observaciones de precio sobreviven con
-- line_item_id a null. Son dato derivado de la línea, así que sin ella no tienen
-- procedencia: no se puede auditar de qué recibo salió ese precio, no se puede
-- reconstruir al reextraer, y el conteo de subidas sigue contando precios de un
-- ticket que ya no existe. Justo el caso de subir un ticket equivocado y
-- borrarlo: el precio malo se quedaba dentro para siempre.
--
-- La columna sigue siendo NULL-able: deja la puerta abierta a observaciones
-- introducidas a mano en el futuro, que nunca han tenido línea de origen.

ALTER TABLE price_observation
    DROP CONSTRAINT price_observation_line_item_id_fkey;

ALTER TABLE price_observation
    ADD CONSTRAINT price_observation_line_item_id_fkey
        FOREIGN KEY (line_item_id) REFERENCES line_item (id) ON DELETE CASCADE;
