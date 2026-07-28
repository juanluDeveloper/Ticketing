# Análisis técnico — Gestor y comparador de tickets

## 1. Alcance

v1 = herramienta personal (tú, con acceso de Chloe en el server de casa). Foco: supermercados (Mercadona, Cash Fresh, Xinya). Restaurantes = esqueleto que reutiliza la ingesta. Sin scraping ni precios en vivo: toda la información sale de los tickets que subes.

## 2. Los tres problemas de verdad

Antes del modelo, conviene tener claro dónde está la dificultad, porque el resto son decisiones de CRUD:

1. **Ingesta / OCR.** Convertir una foto de ticket en líneas estructuradas (descripción, cantidad, precio). Tres formatos distintos y encima cambian con el tiempo.
2. **Resolución de entidades (matching).** Decidir que "VINAGRE JEREZ" de hoy es el mismo artículo que "VINAGRE J. RES." de hace un mes, y además que "eso" es comparable con el vinagre del Cash. Aquí está el 80% del valor y del riesgo.
3. **Comparación justa.** Normalizar a precio por unidad para que 500 ml a 1€ y 1 L a 1,80€ se comparen de verdad, distinguiendo ofertas del precio "real".

Todo lo demás (histórico, gráficas, recomendación, lista de la compra) se construye limpio encima de esos tres.

## 3. Ingesta: de la foto a líneas estructuradas

Tres caminos:

- **OCR clásico** (Tesseract local, o Google Vision / AWS Textract en nube): te da texto/bloques y luego escribes TÚ un parser por supermercado con regex/heurísticas. Frágil: cada formato es distinto y si el súper cambia el ticket, se rompe.
- **Textract AnalyzeExpense**: modo específico para tickets, saca líneas y totales bastante bien, pero sigue siendo rígido y de pago.
- **VLM local** (vía Ollama, para runtime a coste cero y privacidad total; los tickets no salen de casa): le mandas la imagen y le pides un JSON con esquema fijo, usando **salida estructurada** (JSON schema, soportado por Ollama). Se come los tres formatos sin parser a medida, aguanta layouts sucios y de paso sugiere categoría/unidad. Un VLM de 4-8B acierta la mayoría, pero un peldaño por debajo de un cloud de frontera en OCR difícil → hay que validar (ya lo teníamos). Necesita GPU; sin urgencia, incluso lento vale.

**Recomendación:** **Qwen-VL vía Ollama** como extractor. La línea Qwen es referencia en OCR multilingüe y documentos, y su OCR de chino (Alibaba) es de primer nivel — clave para el Xinya.

**Modelo objetivo: `Qwen3-VL 8B`** (alternativa `Qwen2.5-VL 7B`). Producción llevará una **GTX 1070 (8 GB)** — se cambia la gráfica del servidor **antes** de desplegar, así el modelo queda fijo y no hay que ir alternando. **Desarrollo** en el PC personal (**RTX 5070 Ti, 16 GB**): se corre el **mismo modelo** que producción para que la calidad de extracción sea representativa; el 5070 Ti solo lo ejecuta más rápido (iteración ágil), el comportamiento es idéntico. El modelo sigue siendo config (`OLLAMA_MODEL`). Ollama expone una API compatible con OpenAI en `localhost:11434`, así que el backend cambia mínimamente. Guardamos SIEMPRE la imagen original para re-extraer.

**Clave: pantalla de validación humana.** Tras la extracción, ves las líneas en una tabla editable, corriges/confirmas y ENTONCES se guarda. Con tu volumen (unos pocos tickets a la semana) esto es trivial y te sube la calidad del dato a casi perfecta. Además las correcciones alimentan los alias de matching (ver abajo).

## 4. Modelo de datos (el núcleo)

La idea central: separar lo que pone el ticket, el artículo por-súper que acumula histórico, y el grupo comparable entre súper. Cuatro niveles:

```
Ticket  (una foto de recibo)
  └── LineItem  (línea cruda tal cual: "VINAGRE JEREZ  0,89")
        └── StoreProduct  (artículo lógico en UN súper: "Vinagre Jerez Mercadona 250ml")
              └── ComparableGroup  (lo que compara el comparador: "Vinagre de Jerez ~250ml")
                    └── Category  (jerarquía: vinagres → condimentos → …)
```

Por qué dos niveles de producto:

- El **histórico** y "cuántas veces ha subido" es por `StoreProduct` (artículo exacto en un súper).
- "Cuál sale más barato" y las preferencias son **entre súper** → `ComparableGroup`.
- Tu respuesta **D** (auto-sugerir + validar) aplica al matching `LineItem → StoreProduct`.
- Tu respuesta **B** (por categoría/comparable) aplica al agrupado `StoreProduct → ComparableGroup`.

Entidades y campos clave:

- **User**: usuario, hash de contraseña, nombre. De arranque se siembra **uno solo**: `juanluidos` (contraseña de dev, cambiable; se guarda **hasheada** con BCrypt, nunca en claro). El esquema ya es multiusuario para meter a Chloe cuando quieras.
- **Ticket**: user_id, súper, fecha/hora, total, ref_imagen, payload_OCR_crudo, estado (pendiente/validado).
- **LineItem** (verdad inmutable del recibo): descripción_cruda, cantidad, unidad_impresa, importe_línea, precio_unit_impreso (si viene), flag_oferta, texto_crudo.
- **StoreProduct**: súper, nombre_canónico, **display_name** (nombre limpio que pones tú; clave para los productos de Xinya en chino), **notes** (texto libre; p.ej. "西柚 = bebida de pomelo con gas"), marca?, tamaño_envase + unidad, dimensión (peso/volumen/unidad), **vendido_por (envase | peso | pieza_variable)**, category_id, comparable_group_id.
- **ProductAlias**: las distintas cadenas crudas con que ha aparecido un StoreProduct (los tickets abrevian distinto). Mejora el matching con el tiempo.
- **ComparableGroup**: nombre, category_id, unidad_comparación.
- **UserProductPreference** (preferencia por-usuario dentro de un grupo): user_id, comparable_group_id, preferred_store_product_id, `preference_margin` (€/unidad base o %), nota. Así tu "prefiero el vinagre del Mercadona" no pisa las preferencias de Chloe.
- **PriceObservation** (serie de precios, derivada de LineItems): store_product_id, fecha, precio_unit_normalizado (€/unidad base, `null` si no normalizable), es_oferta, line_item_id origen.

Métricas derivadas por `StoreProduct`: nº de compras, primera_vez, último_precio, min/max, nº_de_subidas (cambios al alza en la serie sin ofertas), volatilidad. Por `ComparableGroup`: súper más barato actual, diferencia.

## 5. Matching (resolución de entidades)

`LineItem → StoreProduct`: el súper ya acota. Comparas descripción_cruda contra los nombres/alias de los StoreProduct de ese súper con **similitud de trigramas** (`pg_trgm` de Postgres — encaja de lujo), más cercanía de tamaño/unidad. Por umbral: match alto → sugerido y confirmas; sin match → "crear nuevo" o "fusionar con…". Cada confirmación añade un alias, así que mejora solo. Con volumen personal, incluso un matcher mediocre + confirmación da un dato excelente.

`StoreProduct → ComparableGroup`: asistido/manual ("este vinagre del Mercadona y este del Cash son lo mismo a efectos de comparar"), con sugerencia por categoría + similitud de nombre entre súper. Aquí vive tu opción B.

## 6. Normalización a precio unitario

Sistema de unidades con dimensión ∈ {peso, volumen, unidad} y bases g / ml / ud. Tres casos según `vendido_por`:

- **Envasado**: cantidad = nº de envases, cada uno con tamaño (500 ml) → base = cantidad × 500 ml.
- **A peso** (fruta fresca, embutido al corte): la línea trae peso + €/kg (Mercadona: 1,394 kg a 3,05 €/kg) → base directa.
- **Pieza de peso variable sin €/kg** (los tubos de pota: piezas sueltas que pesan distinto y el ticket **solo imprime el precio final**, ni peso ni €/kg). Aquí **no se puede normalizar** a €/kg desde el ticket. Se guarda el €/pieza tal cual, `precio_unit_normalizado = null`, y estos productos se **excluyen del conteo de subidas** (la variación de precio entre piezas es peso, no subida real). En la validación puedes, opcionalmente, teclear el peso a mano si te interesa comparar; si no, se quedan como "no comparable por unidad".

`precio_unit_normalizado = importe_línea / (cantidad × tamaño_en_base)`. Cuando el ticket ya imprime €/kg o €/L, lo capturamos como verificación cruzada.

## 7. Detección de ofertas

Sin scraping, solo sabemos que hubo oferta por lo que vemos en el ticket. Dos señales:

- **Marcadores explícitos** en el recibo (líneas de "DTO", "AHORRO", 2ª unidad, etc.) → flag `es_oferta`.
- **Anomalía estadística**: un precio que baja y luego vuelve, o que cae >X% respecto a la línea base reciente → sospecha de oferta.

Conceptualmente dos series: precio "base/estantería" (sin ofertas, para la inflación real y el nº de subidas) y "lo que pagué de verdad". Es una limitación honesta del enfoque solo-tickets, pero para uso personal es suficiente.

## 8. Comparador y recomendación (con preferencia)

Base: dentro de un ComparableGroup, ordenas StoreProducts por precio_unit_normalizado (último precio conocido sin oferta, mostrando la fecha/antigüedad).

La ponderación de tu preferencia (el vinagre del Mercadona aunque sea +10c) — te propongo tres mecanismos y **es decisión tuya**:

- **A — Prima explícita ("pago hasta X más").** Por StoreProduct dentro del grupo, un margen opcional en €/unidad o en %. La recomendación resta ese margen antes de comparar: el preferido gana si está dentro de su prima respecto al más barato real. Muy transparente: "prefiero el vinagre del Mercadona hasta 0,15 €/L más caro".
- **B — Peso/score.** score = precio × (1 − afinidad); eliges el mínimo. Menos interpretable, cuesta razonar el impacto en €.
- **C — Etiqueta (preferido / neutro / evitar) + banda.** El preferido gana empates o diferencias dentro de un % configurable.

Recomiendo **A**, porque es justo como lo describiste ("aunque sea 10 céntimos más caro") y siempre ves lo que te "cuesta" la preferencia (se muestra el más barato objetivo Y el ajustado por preferencia).

**Decidido: mecanismo A.** En el modelo la preferencia vive en `UserProductPreference` (por-usuario, ver §4): dentro de un `ComparableGroup`, cada usuario marca su `StoreProduct` preferido y un `preference_margin` en €/unidad base (o %). El comparador ordena por precio normalizado, resta ese margen al preferido del usuario antes de comparar, y muestra ambas cosas: el más barato objetivo y tu elección ajustada, con la diferencia que te cuesta.

## 9. La "inteligencia" (lista de la compra) — fase posterior

Entrada: una lista de ComparableGroups con cantidades. Datos: último precio normalizado por (grupo, súper) + primas de preferencia.

- **Un solo súper**: por cada súper, suma el mejor precio disponible de cada ítem; el que no lo tenga → penalización/excluir; eliges el súper de menor total.
- **Hasta 2 súper**: con 3 súper, fuerza bruta de subconjuntos de tamaño ≤2, asignas cada ítem a su súper más barato dentro del subconjunto, y eliges subconjunto+asignación de coste mínimo (con un pequeño "coste de parada extra" para que no te mande a dos sitios por ahorrar 8 céntimos). El cómputo es trivial a esta escala.

**Aviso importante**: los precios son "de la última vez que compré", no en vivo (por diseño). Hay que enseñar la antigüedad ("precio de hace 3 semanas") y la disponibilidad solo se conoce si has comprado ahí. Por eso es fase 3: necesita datos acumulados, justo como decías.

## 10. Módulo restaurante (esqueleto)

Comparte el backbone: foto → extracción → validación → guardar. Cambia el dominio: sin matching ni comparación entre sitios. Entidad `RestaurantTicket` (o `Ticket` con un discriminador de tipo): restaurante, fecha, total, propina, método de pago, líneas (platos) opcionales, categoría (comida/cena…), notas. Objetivo: registrar y categorizar gasto ("cuánto voy en restaurantes al mes", "dónde repito"). Lo dejamos como vertical fino; la ingesta se diseña genérica (parámetro tipo_ticket) para que ambos la usen sin acoplar esquemas.

## 11. Stack y despliegue

Igual que el de videojuegos: **React** (frontend) + **Spring Boot** (backend) + **PostgreSQL**.

- Frontend como **PWA** para usar la cámara del móvil y "añadir a inicio". La captura es `<input capture>` / getUserMedia → subida. Sin offline (subes luego), así que simple.
- Backend REST: endpoints de subida, extracción (llama a **Ollama en local**, sin API externa ni claves), CRUD de productos/grupos/observaciones, comparador y optimizador de lista.
- Postgres con `pg_trgm` para el matching; posible vista materializada para la serie de precios y métricas.
- **Almacenamiento de imágenes** de los tickets (filesystem del server u objeto): imprescindible para re-extraer y auditar. Guarda un hash para detectar subidas duplicadas.
- Hosting en tu server de casa, acceso en LAN para Chloe; acceso remoto = reverse proxy/túnel, pero eso es ops de más adelante.
- **GPU**: producción en el server con **GTX 1070 (8 GB)** corriendo `Qwen3-VL 8B` vía Ollama (la gráfica se cambia antes de desplegar). Desarrollo en el PC con **RTX 5070 Ti (16 GB)**, mismo modelo, solo que más rápido. Ollama y Postgres corren en el mismo host.
- Auth mínima. Puedes empezar single-user y añadir usuarios cuando quieras distinguir compras/preferencias tuyas vs de Chloe.

## 12. Roadmap por fases

- **Fase 0 — Fundamento**: foto → extracción LLM → pantalla de validación → guardar Ticket + LineItems. StoreProduct + matching básico (pg_trgm) con confirmación. Histórico de precios por StoreProduct. *(Ya registras y ves evolución.)*
- **Fase 1 — Comparador**: ComparableGroups, normalización a precio unitario, comparador entre súper, preferencias (mecanismo elegido).
- **Fase 2 — Ofertas y métricas**: detección de promos (marcadores + anomalía), serie base vs pagado, nº de subidas, gráficas.
- **Fase 3 — Inteligencia**: optimizador de lista / reparto entre súper con avisos de frescura.
- **Paralelo**: esqueleto de restaurante.

## Anexo A — Formatos reales de los tres tickets

Los tres tickets confirman que cada súper tiene un layout distinto: un parser por-súper con regex sería un infierno de mantener, lo que refuerza ir con **LLM-visión**. Matriz de formato:

| Aspecto | Mercadona | Cash Fresh | Xinya |
|---|---|---|---|
| Líneas por ítem | 1 (2 si va a peso) | 1 | 2 (descripción + números) |
| Posición cantidad | al inicio ("3 BEBIDA…") | en medio ("1x 1,45") | en la 2ª línea |
| P. unit | solo si cantidad > 1 | siempre ("Nx P.UNIT") | siempre |
| Separador decimal | coma (1,65) | coma (1,45) | **punto (1.65)** |
| Producto a peso | sub-línea "1,394 kg  3,05 €/kg" | no aparece | no aparece |
| IVA por línea | no (solo tabla agregada) | **sí, letra A/B/C** | no (solo 21%) |
| Fecha | dd/mm/aaaa | dd/mm/aaaa | dd.mm.aaaa |
| Identificación | NIF A-46103834 | NIF B41544503 | CIF B-90379843 |

Hallazgos que importan para el diseño:

- **Checksum del total.** En los tres, Σ importes de línea (+ ítems a peso) = TOTAL impreso exacto (29,48 / 63,16 / 9,90). Regla de oro: si tras extraer no cuadra con el TOTAL, marca el ticket para revisión. Es tu control de calidad automático y sale gratis.
- **Ojo: el checksum valida los números, no el emparejamiento.** En tickets densos y arrugados (el de Cash Fresh es el caso), la alineación vertical entre la columna de descripción (izquierda) y la de precios (derecha) puede bailar. El checksum sigue cuadrando aunque un precio se enganche a la descripción equivocada, porque la suma es la misma. Por eso la pantalla de validación es doblemente necesaria: garantiza el emparejamiento nombre↔precio, no solo los totales.
- **Posición de la cantidad cambia** entre súper (inicio / "Nx" en medio / 2ª línea). El extractor debe devolver siempre `{cantidad, precio_unit, importe}` normalizado, sin depender del layout.
- **Separador decimal.** Xinya usa punto; los otros, coma. Config a nivel de súper (`decimal_separator`) o detección automática por la cabecera.
- **Producto a peso** (Mercadona: MANGO 1,394 kg a 3,05 €/kg = 4,25). Hay que asociar la sub-línea al ítem de arriba. Nos regala el €/kg para la verificación cruzada de la normalización. → `vendido_por = peso`.
- **Descripción muy truncada en Cash Fresh** (ancho fijo: "DET MARSE FLOTA 100D", "FLOTA LAVAV. 1,10L", "IFA SABE LEJIA C/DET"): refuerza la necesidad de alias. Mercadona abrevia menos; Xinya trae nombre largo bilingüe (chino + cola en español, "…330MLBEBIDA SBR PO").
- **Homónimos que NO son el mismo producto.** En el Mercadona conviven "MANGO S/AZ AÑADIDO" (envasado, 1,25) y "MANGO" fresco a peso (4,25). El matcher no puede fusionar por subcadena de nombre.
- **Misma descripción, distinto precio, mismo ticket → resuelto.** Los tres "TUBO DE POTA" a 1,82 / 2,30 / 1,95 son la **misma** pieza (tubo de pota) de peso variable; varían porque cada pieza pesa distinto y el ticket **no imprime €/kg ni peso**. Se modela como `vendido_por = pieza_variable`: mismo `StoreProduct`, tres `PriceObservation`, sin normalizar a €/kg y fuera del conteo de subidas (ver §6).
- **La letra de IVA de Cash Fresh** (A=4%, B=10%, C=21%) es una pista débil de categoría (A básicos, B alimentación, C no-alimentación/general). Solo la tenemos ahí; en Mercadona y Xinya el IVA es agregado, así que la categoría se infiere del nombre.
- **Auto-detección de súper.** El NIF/CIF y la dirección de la cabecera permiten etiquetar `Ticket.store` (e incluso la sucursal) automáticamente.

## Anexo B — Esquema de extracción (JSON)

La llamada de visión recibe una pista del súper (o `store_template_id` para que sepa el layout) y devuelve SIEMPRE este esquema normalizado, sea cual sea el formato. El backend recomputa el checksum (Σ `line_total` vs `total`) y, si no cuadra o la confianza es baja, la validación lo marca en rojo.

```json
{
  "store": {
    "name": "Mercadona",
    "nif": "A-46103834",
    "address": "Avda. Nuestra Sra. de la Soledad, 62, 41320 Cantillana"
  },
  "purchased_at": "2026-06-24T21:51:00",
  "receipt_number": "2276-012-556655",
  "currency": "EUR",
  "decimal_separator": ",",
  "line_items": [
    {
      "raw_description": "MANGO",
      "quantity": 1,
      "sold_by": "weight",           // "unit" | "weight" | "piece_variable"
      "weight": { "value": 1.394, "unit": "kg" },
      "unit_price": 3.05,            // €/kg si weight, €/ud si unit
      "unit_price_unit": "kg",
      "line_total": 4.25,
      "tax_letter": null,            // "A"|"B"|"C" solo Cash Fresh
      "is_promo": false,
      "promo_note": null
    },
    {
      "raw_description": "TUBO DE POTA",
      "quantity": 1,
      "sold_by": "piece_variable",   // pieza suelta, sin €/kg ni peso → no normalizable
      "weight": null,
      "unit_price": 2.30,
      "unit_price_unit": "ud",
      "line_total": 2.30,
      "tax_letter": null,
      "is_promo": false,
      "promo_note": null
    },
    {
      "raw_description": "BEBIDA AVELLANAS",
      "quantity": 3,
      "sold_by": "unit",
      "weight": null,
      "unit_price": 1.25,
      "unit_price_unit": "ud",
      "line_total": 3.75,
      "tax_letter": null,
      "is_promo": false,
      "promo_note": null
    }
  ],
  "totals": {
    "total": 63.16,
    "tax_breakdown": [
      { "rate": 0.04, "base": 13.38, "tax": 0.54 },
      { "rate": 0.10, "base": 39.40, "tax": 3.94 },
      { "rate": 0.21, "base": 4.88,  "tax": 1.02 }
    ]
  },
  "checksum_ok": true                 // Σ line_total == total
}
```

Nota: el JSON es la salida cruda de la extracción; el mapeo a `StoreProduct` / `ComparableGroup` (matching) ocurre después, en la validación. `raw_description` se guarda tal cual y alimenta la tabla de alias.

## Anexo C — Enriquecimiento opcional con catálogo (solo Mercadona)

Idea que surge de que la tienda online de Mercadona tiene ficha canónica por producto (nombre completo, tamaño, categoría, €/kg o €/ud, imagen), con ID estable en la URL (p.ej. mango deshidratado = producto 34209, mango pieza = 3050). Se podría, **opcionalmente y en fase futura**, casar una línea del ticket con el catálogo para autocompletar `display_name`, tamaño, categoría e incluso la imagen — subiría mucho la calidad de matching y presentación sin teclear nada.

Matices honestos: es **solo Mercadona** (Cash Fresh es marca IFA, sin catálogo público claro; Xinya no tiene); y roza el scraping que ya descartaste para precios de oferta. La diferencia es que esto es canonicalización puntual, no seguimiento de precios en vivo. Truco extra para la pota: si el catálogo diera un €/kg de referencia, se podría *despejar* el peso de la pieza (peso ≈ precio_pagado / €kg_catálogo), pero es frágil (ofertas y cambios lo rompen). Lo dejo anotado como opción, **fuera del núcleo**.

## 13. Decisiones (cerradas)

1. **Preferencia** → **A**, prima `preference_margin`, y vive en `UserProductPreference` (por-usuario).
2. **OCR** → **LLM-visión** como extractor principal.
3. **Validación** → pantalla "revisa y confirma líneas" tras cada escaneo.
4. **Multiusuario** → **sí desde el día 1** (user_id en tickets y preferencias), pero se siembra **un único usuario**: `juanluidos` (contraseña de dev, hasheada con BCrypt).
5. **Pieza de peso variable** (pota) → `vendido_por = pieza_variable`: mismo producto, varias observaciones, sin normalizar a €/kg y fuera del conteo de subidas.
6. **Descripciones chinas de Xinya** → se guarda el crudo + `display_name` y `notes` editables por ti en el detalle del ticket/producto (la traducción la pones una vez y se reutiliza).

**Siguiente paso**: el análisis está cerrado. Toca el **prompt de generación** de la app. Propongo hacerlo para **Fase 0 + Fase 1 juntas** (registro con foto + histórico + comparador con preferencias), que comparten modelo y es lo que te da valor pronto; ofertas e inteligencia (Fases 2–3) quedan para iteraciones. Si te cuadra el alcance, lo escribo ya.
