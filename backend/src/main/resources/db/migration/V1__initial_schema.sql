-- Esquema inicial del gestor de tickets.
-- Identificadores en inglés (coherentes con las entidades JPA); la UI va en español.
--
-- Convenciones de precisión, ver docs/hallazgos_verificacion_tickets.md §5:
--   dinero e importes  -> NUMERIC(12,4)  (Xinya imprime bases de IVA con 3 decimales)
--   precio normalizado -> NUMERIC(16,6)  (€/g y €/ml son números muy pequeños)
-- Nunca coma flotante.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------------------
-- Usuarios
-- ---------------------------------------------------------------------------

CREATE TABLE app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(100) NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Supermercados y capacidades de su formato de ticket
--
-- Las banderas has_* declaran qué redundancia trae el formato. El motor de
-- validación las consulta para decidir qué comprobaciones puede aplicar; una
-- check que el formato no soporta se registra como "no aplicable", nunca como
-- pasada (docs/hallazgos_verificacion_tickets.md §1).
-- ---------------------------------------------------------------------------

CREATE TABLE store (
    id                            BIGSERIAL PRIMARY KEY,
    code                          VARCHAR(30)  NOT NULL UNIQUE,
    name                          VARCHAR(100) NOT NULL,
    tax_id                        VARCHAR(20),
    -- VARCHAR y no CHAR en todas las columnas de texto corto: Hibernate mapea
    -- String a varchar, y con ddl-auto=validate un CHAR(n) hace fallar el
    -- arranque por discrepancia de tipo.
    decimal_separator             VARCHAR(1)   NOT NULL DEFAULT ',',
    -- C3: ¿imprime letra de IVA por línea + tabla de bases por letra?
    has_line_tax_letter           BOOLEAN      NOT NULL DEFAULT FALSE,
    -- C4: ¿imprime recuento total de artículos ("6 ART.")?
    has_article_count             BOOLEAN      NOT NULL DEFAULT FALSE,
    -- C5: ¿imprime desglose de IVA (bases y cuotas)?
    has_tax_breakdown             BOOLEAN      NOT NULL DEFAULT TRUE,
    -- C2: si es TRUE, el P.Unit solo aparece cuando la cantidad es > 1, así que
    --     las líneas de cantidad 1 quedan sin comprobación aritmética. También
    --     implica la regla del "12 HUEVOS": el entero inicial de la descripción
    --     es cantidad solo si hay P.Unit en esa línea (§2).
    unit_price_only_when_multiple BOOLEAN      NOT NULL DEFAULT FALSE,
    -- ¿puede traer sub-línea de peso "1,394 kg  3,05 €/kg"?
    has_weight_subline            BOOLEAN      NOT NULL DEFAULT FALSE,
    date_format                   VARCHAR(20),
    notes                         TEXT
);

-- Tipos de IVA asociados a la letra impresa por línea (solo Cash Fresh de momento).
CREATE TABLE store_tax_letter (
    id       BIGSERIAL PRIMARY KEY,
    store_id BIGINT      NOT NULL REFERENCES store (id) ON DELETE CASCADE,
    letter   VARCHAR(1)  NOT NULL,
    rate     NUMERIC(5, 4) NOT NULL,
    CONSTRAINT uq_store_tax_letter UNIQUE (store_id, letter)
);

-- ---------------------------------------------------------------------------
-- Taxonomía y grupos comparables
-- ---------------------------------------------------------------------------

CREATE TABLE category (
    id        BIGSERIAL PRIMARY KEY,
    parent_id BIGINT REFERENCES category (id),
    code      VARCHAR(60)  NOT NULL UNIQUE,
    name      VARCHAR(100) NOT NULL
);

CREATE INDEX idx_category_parent ON category (parent_id);

CREATE TABLE comparable_group (
    id                   BIGSERIAL PRIMARY KEY,
    name                 VARCHAR(150) NOT NULL,
    category_id          BIGINT REFERENCES category (id),
    -- Un grupo no puede mezclar dimensiones: detergente en dosis y detergente
    -- en litros no son comparables (§3).
    comparison_dimension VARCHAR(10)  NOT NULL
        CONSTRAINT ck_group_dimension CHECK (comparison_dimension IN ('WEIGHT', 'VOLUME', 'UNIT')),
    -- Unidad canónica de la dimensión. No es texto libre: la serie de precios,
    -- el ranking y la prima de preferencia tienen que hablar todos la misma, o
    -- el comparador acaba con una conversión implícita de factor 1000.
    comparison_unit      VARCHAR(10)  NOT NULL,
    notes                TEXT,
    created_at           TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT ck_group_unit_matches_dimension CHECK (
        (comparison_dimension = 'WEIGHT' AND comparison_unit = 'kg') OR
        (comparison_dimension = 'VOLUME' AND comparison_unit = 'L') OR
        (comparison_dimension = 'UNIT' AND comparison_unit = 'ud')
        )
);

-- ---------------------------------------------------------------------------
-- Producto por-súper (acumula histórico) y sus alias
-- ---------------------------------------------------------------------------

CREATE TABLE store_product (
    id                  BIGSERIAL PRIMARY KEY,
    store_id            BIGINT       NOT NULL REFERENCES store (id),
    canonical_name      VARCHAR(200) NOT NULL,
    -- Nombre limpio escrito por el usuario. Imprescindible para Xinya (chino).
    display_name        VARCHAR(200),
    notes               TEXT,
    brand               VARCHAR(100),
    package_size        NUMERIC(12, 4),
    package_unit        VARCHAR(10),
    dimension           VARCHAR(10)
        CONSTRAINT ck_product_dimension CHECK (dimension IN ('WEIGHT', 'VOLUME', 'UNIT')),
    -- PACKAGE = envase | WEIGHT = a peso | VARIABLE_PIECE = pieza de peso variable
    sold_by             VARCHAR(20)  NOT NULL DEFAULT 'PACKAGE'
        CONSTRAINT ck_product_sold_by CHECK (sold_by IN ('PACKAGE', 'WEIGHT', 'VARIABLE_PIECE')),
    category_id         BIGINT REFERENCES category (id),
    comparable_group_id BIGINT REFERENCES comparable_group (id),
    -- Letra de IVA habitual observada. Heurística de desalineamiento: si llega
    -- una línea de este producto con otra letra, marcar en rojo (§1).
    usual_tax_letter    VARCHAR(1),
    created_at          TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_store_product_name UNIQUE (store_id, canonical_name)
);

CREATE INDEX idx_store_product_group ON store_product (comparable_group_id);
CREATE INDEX idx_store_product_category ON store_product (category_id);

CREATE TABLE product_alias (
    id               BIGSERIAL PRIMARY KEY,
    store_product_id BIGINT       NOT NULL REFERENCES store_product (id) ON DELETE CASCADE,
    -- Desnormalizado para poder filtrar por súper sin join en el matcher.
    store_id         BIGINT       NOT NULL REFERENCES store (id),
    raw_text         VARCHAR(300) NOT NULL,
    -- Mayúsculas, sin acentos, espacios colapsados. Primera pasada del matcher.
    normalized_text  VARCHAR(300) NOT NULL,
    -- Para Xinya: cola en alfabeto latino, porque pg_trgm rinde mal en chino (§8).
    latin_text       VARCHAR(300),
    times_seen       INTEGER      NOT NULL DEFAULT 1,
    first_seen_at    TIMESTAMP    NOT NULL DEFAULT now(),
    last_seen_at     TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_product_alias UNIQUE (store_product_id, normalized_text)
);

-- Pasada 1 del matcher: coincidencia exacta del alias normalizado en ese súper.
CREATE INDEX idx_product_alias_exact ON product_alias (store_id, normalized_text);
-- Pasada 2: similitud por trigramas, solo para lo que no casa exacto.
CREATE INDEX idx_product_alias_trgm ON product_alias USING GIN (normalized_text gin_trgm_ops);
CREATE INDEX idx_product_alias_latin_trgm ON product_alias USING GIN (latin_text gin_trgm_ops);

-- ---------------------------------------------------------------------------
-- Tickets
-- ---------------------------------------------------------------------------

CREATE TABLE ticket (
    id                    BIGSERIAL PRIMARY KEY,
    user_id               BIGINT      NOT NULL REFERENCES app_user (id),
    store_id              BIGINT REFERENCES store (id),
    ticket_type           VARCHAR(20) NOT NULL DEFAULT 'SUPERMARKET'
        CONSTRAINT ck_ticket_type CHECK (ticket_type IN ('SUPERMARKET', 'RESTAURANT')),
    -- La extracción es asíncrona: la subida responde en UPLOADED y un worker
    -- mueve el estado (§7). Nunca extraer dentro de la petición HTTP.
    status                VARCHAR(25) NOT NULL DEFAULT 'UPLOADED'
        CONSTRAINT ck_ticket_status CHECK (status IN
            ('UPLOADED', 'EXTRACTING', 'EXTRACTED', 'VALIDATED', 'EXTRACTION_ERROR')),
    -- Hora local del súper, sin zona (§9).
    purchased_at          TIMESTAMP,
    receipt_number        VARCHAR(60),
    total                 NUMERIC(12, 4),
    article_count         INTEGER,
    currency              VARCHAR(3)  NOT NULL DEFAULT 'EUR',
    image_path            VARCHAR(500) NOT NULL,
    image_sha256          VARCHAR(64) NOT NULL,
    raw_extraction        JSONB,
    extraction_model      VARCHAR(100),
    extraction_started_at TIMESTAMP,
    extraction_finished_at TIMESTAMP,
    extraction_error      TEXT,
    -- Cobertura de validación cruzada: líneas con al menos una check aplicable
    -- sobre el total de líneas. Se enseña al usuario para calibrar cuánta
    -- atención merece la revisión manual (§1).
    coverage_ratio        NUMERIC(5, 4),
    created_at            TIMESTAMP   NOT NULL DEFAULT now(),
    validated_at          TIMESTAMP,
    validated_by          BIGINT REFERENCES app_user (id)
);

-- Deduplicación primaria: el número de ticket (§4). Parcial porque no todos lo traen.
CREATE UNIQUE INDEX uq_ticket_receipt_number
    ON ticket (store_id, receipt_number)
    WHERE receipt_number IS NOT NULL;

-- Deduplicación de respaldo cuando no hay número legible.
CREATE INDEX idx_ticket_dedupe_fallback ON ticket (store_id, purchased_at, total);
-- Señal secundaria: la misma foto exacta subida dos veces.
CREATE INDEX idx_ticket_image_sha ON ticket (image_sha256);
CREATE INDEX idx_ticket_status ON ticket (status);

-- Desglose de IVA impreso. Alimenta C3 (por letra) y C5 (bases + cuotas == total).
CREATE TABLE ticket_tax_summary (
    id          BIGSERIAL PRIMARY KEY,
    ticket_id   BIGINT        NOT NULL REFERENCES ticket (id) ON DELETE CASCADE,
    tax_letter  VARCHAR(1),
    rate        NUMERIC(5, 4) NOT NULL,
    base_amount NUMERIC(12, 4) NOT NULL,
    tax_amount  NUMERIC(12, 4) NOT NULL
);

CREATE INDEX idx_tax_summary_ticket ON ticket_tax_summary (ticket_id);

-- ---------------------------------------------------------------------------
-- Líneas del ticket (verdad inmutable del recibo)
-- ---------------------------------------------------------------------------

CREATE TABLE line_item (
    id                      BIGSERIAL PRIMARY KEY,
    ticket_id               BIGINT       NOT NULL REFERENCES ticket (id) ON DELETE CASCADE,
    line_no                 INTEGER      NOT NULL,
    -- Etapa 1 de la extracción: la fila física transcrita literal, sin
    -- interpretar. Los campos de abajo se derivan de ESTA cadena, así que
    -- descripción e importe vienen de la misma fila por construcción (§6).
    -- También se enseña en la pantalla de validación junto al recorte de imagen.
    raw_row_text            VARCHAR(400),
    raw_description         VARCHAR(300) NOT NULL,
    quantity                NUMERIC(12, 4) NOT NULL DEFAULT 1,
    sold_by                 VARCHAR(20)
        CONSTRAINT ck_line_sold_by CHECK (sold_by IN ('PACKAGE', 'WEIGHT', 'VARIABLE_PIECE')),
    weight_value            NUMERIC(12, 4),
    weight_unit             VARCHAR(10),
    printed_unit_price      NUMERIC(12, 4),
    printed_unit_price_unit VARCHAR(10),
    line_total              NUMERIC(12, 4) NOT NULL,
    tax_letter              VARCHAR(1),
    is_promo                BOOLEAN      NOT NULL DEFAULT FALSE,
    promo_note              VARCHAR(200),
    store_product_id        BIGINT REFERENCES store_product (id),
    match_confidence        NUMERIC(5, 4),
    match_method            VARCHAR(20)
        CONSTRAINT ck_line_match_method CHECK (match_method IN
            ('EXACT_ALIAS', 'TRIGRAM', 'MANUAL', 'NEW_PRODUCT')),
    CONSTRAINT uq_line_item_no UNIQUE (ticket_id, line_no)
);

CREATE INDEX idx_line_item_ticket ON line_item (ticket_id);
CREATE INDEX idx_line_item_product ON line_item (store_product_id);
CREATE INDEX idx_line_item_desc_trgm ON line_item USING GIN (raw_description gin_trgm_ops);

-- ---------------------------------------------------------------------------
-- Motor de validación
--
-- Dos tablas a propósito: check_result dice si cada comprobación pudo correr y
-- con qué resultado (verde / rojo / gris "no aplica"); issue dice qué línea
-- concreta falla. Sin la distinción applicable/passed, un formato que no
-- soporta una check se leería como verde e inflaría la confianza.
-- ---------------------------------------------------------------------------

CREATE TABLE ticket_check_result (
    id           BIGSERIAL PRIMARY KEY,
    ticket_id    BIGINT      NOT NULL REFERENCES ticket (id) ON DELETE CASCADE,
    check_code   VARCHAR(10) NOT NULL,
    applicable   BOOLEAN     NOT NULL,
    -- NULL cuando applicable = FALSE. Nunca TRUE por omisión.
    passed       BOOLEAN,
    -- Nº de líneas que esta check ha podido cubrir (C2 solo cubre las que
    -- traen P.Unit impreso).
    lines_covered INTEGER,
    detail       VARCHAR(500),
    evaluated_at TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT uq_check_result UNIQUE (ticket_id, check_code),
    CONSTRAINT ck_check_passed_only_if_applicable
        CHECK (applicable OR passed IS NULL)
);

CREATE TABLE validation_issue (
    id           BIGSERIAL PRIMARY KEY,
    ticket_id    BIGINT      NOT NULL REFERENCES ticket (id) ON DELETE CASCADE,
    line_item_id BIGINT REFERENCES line_item (id) ON DELETE CASCADE,
    check_code   VARCHAR(10) NOT NULL,
    severity     VARCHAR(10) NOT NULL
        CONSTRAINT ck_issue_severity CHECK (severity IN ('ERROR', 'WARN', 'INFO')),
    status       VARCHAR(15) NOT NULL DEFAULT 'OPEN'
        CONSTRAINT ck_issue_status CHECK (status IN ('OPEN', 'RESOLVED', 'ACCEPTED')),
    message      VARCHAR(500) NOT NULL,
    expected     NUMERIC(14, 4),
    actual       NUMERIC(14, 4),
    created_at   TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE INDEX idx_issue_ticket ON validation_issue (ticket_id);
CREATE INDEX idx_issue_line ON validation_issue (line_item_id);

-- ---------------------------------------------------------------------------
-- Serie de precios
-- ---------------------------------------------------------------------------

CREATE TABLE price_observation (
    id                    BIGSERIAL PRIMARY KEY,
    store_product_id      BIGINT       NOT NULL REFERENCES store_product (id) ON DELETE CASCADE,
    line_item_id          BIGINT REFERENCES line_item (id) ON DELETE SET NULL,
    observed_at           TIMESTAMP    NOT NULL,
    quantity              NUMERIC(12, 4) NOT NULL,
    line_total            NUMERIC(12, 4) NOT NULL,
    price_per_piece       NUMERIC(12, 4),
    -- € por unidad canónica de la dimensión: €/kg, €/L o €/ud. Misma unidad que
    -- la prima de preferencia, a propósito.
    -- NULL si no normalizable: pieza de peso variable, o falta el tamaño de
    -- envase todavía sin rellenar (§6 del análisis).
    normalized_unit_price NUMERIC(16, 6),
    normalized_unit       VARCHAR(10)
        CONSTRAINT ck_observation_unit CHECK (normalized_unit IN ('kg', 'L', 'ud')),
    is_promo              BOOLEAN      NOT NULL DEFAULT FALSE,
    -- FALSE para VARIABLE_PIECE y para promociones: la variación no es subida real.
    counts_for_increase   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_price_obs_product_date ON price_observation (store_product_id, observed_at);

-- ---------------------------------------------------------------------------
-- Preferencias por usuario (mecanismo A: prima explícita)
-- ---------------------------------------------------------------------------

CREATE TABLE user_product_preference (
    id                         BIGSERIAL PRIMARY KEY,
    user_id                    BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    comparable_group_id        BIGINT      NOT NULL REFERENCES comparable_group (id) ON DELETE CASCADE,
    preferred_store_product_id BIGINT      NOT NULL REFERENCES store_product (id) ON DELETE CASCADE,
    -- ABS = € por unidad de comparación del grupo (€/kg, €/L, €/ud). PCT = %.
    margin_type                VARCHAR(5)  NOT NULL DEFAULT 'ABS'
        CONSTRAINT ck_margin_type CHECK (margin_type IN ('ABS', 'PCT')),
    margin_value               NUMERIC(12, 4) NOT NULL DEFAULT 0,
    note                       VARCHAR(300),
    created_at                 TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_group_preference UNIQUE (user_id, comparable_group_id)
);
