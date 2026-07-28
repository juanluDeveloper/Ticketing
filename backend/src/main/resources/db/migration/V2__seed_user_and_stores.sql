-- Semillas: usuario único y los tres súper con las capacidades de su formato.

-- El hash real lo escribe SeedUserInitializer al arrancar, a partir de la variable
-- de entorno SEED_USER_PASSWORD (por defecto "cambiame"). Así no queda ninguna
-- contraseña en el repositorio y cambiarla es trivial: borrar el hash o poner el
-- centinela y reiniciar.
INSERT INTO app_user (username, password_hash, display_name)
VALUES ('juanluidos', 'NEEDS_INIT', 'Juanlu');

-- ---------------------------------------------------------------------------
-- Súper. Las banderas describen qué redundancia trae cada formato y por tanto
-- qué comprobaciones del motor de validación son aplicables:
--   C2 <- unit_price_only_when_multiple (cobertura parcial si TRUE)
--   C3 <- has_line_tax_letter
--   C4 <- has_article_count
--   C5 <- has_tax_breakdown
-- Ver docs/hallazgos_verificacion_tickets.md §1 y Anexo A del análisis.
-- ---------------------------------------------------------------------------

INSERT INTO store (code, name, tax_id, decimal_separator,
                   has_line_tax_letter, has_article_count, has_tax_breakdown,
                   unit_price_only_when_multiple, has_weight_subline,
                   date_format, notes)
VALUES
    ('MERCADONA', 'Mercadona', 'A-46103834', ',',
     FALSE, FALSE, TRUE,
     TRUE, TRUE,
     'dd/MM/yyyy',
     'Cantidad al inicio de la línea, pero SOLO si esa línea trae P.Unit: '
     || '"12 HUEVOS GRANDES-L" es una docena a 3,20, no doce unidades. '
     || 'Sub-línea de peso "1,394 kg 3,05 EUR/kg" asociada al ítem anterior. '
     || 'IVA solo agregado. Cobertura de C2 baja: la mayoría de líneas van a cantidad 1.'),

    ('CASH_FRESH', 'Cash Fresh', 'B41544503', ',',
     TRUE, FALSE, TRUE,
     FALSE, FALSE,
     'dd/MM/yyyy',
     'Letra de IVA por línea (A/B/C) + tabla de bases por letra: habilita C3, '
     || 'que es la unica defensa automatica contra el desalineamiento de columnas '
     || 'en este formato, y solo funciona si el ticket mezcla letras. '
     || 'Descripciones muy truncadas a ancho fijo: los alias son imprescindibles.'),

    ('XINYA', 'Xinya (亚非商场)', 'B-90379843', '.',
     FALSE, TRUE, TRUE,
     FALSE, FALSE,
     'dd.MM.yyyy',
     'Separador decimal PUNTO. Dos lineas por item: descripcion, luego '
     || 'cantidad/precio/importe. Descripcion en chino con cola en latino que suele '
     || 'llevar el tamano embebido ("330ML"). Imprime recuento de articulos: habilita C4. '
     || 'Base de IVA con 3 decimales.');

-- Tipos de IVA por letra impresa. Solo Cash Fresh los imprime por línea.
INSERT INTO store_tax_letter (store_id, letter, rate)
SELECT s.id, v.letter, v.rate
FROM store s
         CROSS JOIN (VALUES ('A', 0.0400), ('B', 0.1000), ('C', 0.2100)) AS v(letter, rate)
WHERE s.code = 'CASH_FRESH';
