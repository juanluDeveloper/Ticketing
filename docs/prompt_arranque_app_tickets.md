# Prompt de arranque — App de tickets de súper

## Contexto y rol

Eres un agente de desarrollo. Vas a construir desde cero una aplicación web personal para **registrar y comparar tickets de supermercado**. Es para uso propio (un solo usuario de arranque), pensada para correr en un servidor doméstico.

**Fuente de verdad**: lee el archivo `analisis_gestor_tickets.md` ANTES de escribir nada. Contiene el modelo de datos, las decisiones cerradas y los anexos con los formatos reales de ticket (Anexo A), el esquema JSON de extracción (Anexo B) y una opción futura de catálogo (Anexo C, **fuera de alcance ahora**). Están también adjuntas **3 fotos de tickets reales** (Mercadona, Cash Fresh, Xinya): úsalas para calibrar la extracción y para verificar.

## Alcance de esta entrega: Fase 0 + Fase 1

**Dentro:**
- Ingesta: subir foto de ticket → extracción a JSON → checksum → pantalla de validación → guardar.
- Modelo de datos completo del §4 con histórico de precios por producto.
- Matching de líneas a productos (sugerido con `pg_trgm`, confirmado por el usuario) con alias.
- Comparador entre súper con normalización a precio por unidad y preferencias por-usuario (mecanismo A).

**Fuera (iteraciones posteriores, NO ahora):** detección de ofertas (Fase 2), inteligencia de lista de la compra / reparto entre súper (Fase 3), enriquecimiento con catálogo (Anexo C). El módulo de restaurante queda como stub mínimo o fuera; céntrate en supermercado.

## Stack y estructura

Monorepo:
- `/backend` — Spring Boot (Java 21): Spring Web, Spring Data JPA, Spring Security (BCrypt), Flyway para migraciones. PostgreSQL con extensión `pg_trgm` habilitada.
- `/frontend` — React + Vite, configurada como **PWA** (instalable, captura de cámara del móvil vía `<input type="file" accept="image/*" capture>`). UI en español.
- `docker-compose.yml` — Postgres (y opcionalmente back y front), para levantar todo con un comando.
- Extracción con **VLM local vía Ollama** (sin API de pago, sin clave; runtime a coste cero y privacidad total). Config por variables de entorno: `OLLAMA_BASE_URL` (por defecto `http://localhost:11434`) y `OLLAMA_MODEL`.

## Modelo de datos

Implementa las entidades del §4 con migraciones Flyway: `User`, `Ticket`, `LineItem`, `StoreProduct` (con `display_name`, `notes`, `vendido_por ∈ {envase, peso, pieza_variable}`), `ProductAlias`, `ComparableGroup`, `UserProductPreference`, `PriceObservation`, `Category`.

Seeds:
- **Un único usuario** `juanluidos`, con contraseña de desarrollo hasheada con BCrypt (que sea trivial de cambiar). El esquema ya es multiusuario (`user_id` en tickets y preferencias) para meter a otra persona luego.
- Los **tres súper** (Mercadona, Cash Fresh, Xinya) con su config: `decimal_separator` (Xinya = punto, resto = coma) y si traen letra de IVA por línea (solo Cash Fresh).

## Ingesta y extracción

- **Endpoint de subida**: guarda la imagen original + un hash (para detectar duplicados) y crea un `Ticket` en estado `pendiente`.
- **Extracción con VLM local**: el backend manda la imagen a **Ollama** (API compatible con OpenAI en `OLLAMA_BASE_URL`), usando un modelo **Qwen-VL** (familia fuerte en OCR multilingüe y documentos; excelente en chino para el Xinya). Modelo objetivo por config `OLLAMA_MODEL`: **`Qwen3-VL 8B`** (alternativa `Qwen2.5-VL 7B`). Producción corre en una **GTX 1070 (8 GB)** (la gráfica del servidor se cambia antes de desplegar, así el modelo queda fijo); el desarrollo se hace en un PC con **RTX 5070 Ti (16 GB)** usando el **mismo modelo** para que la extracción sea representativa (dev solo va más rápido). Confirma el tag exacto en la librería de Ollama. Requiere **Ollama 0.12.7+** para Qwen3-VL. Usa **salida estructurada** (JSON schema, soportado por Ollama) para forzar EXACTAMENTE el esquema del Anexo B, de modo que el modelo no pueda devolver campos de más o de menos. Pásale como pista el súper detectado por la cabecera (NIF/CIF) para que aplique el layout correcto (Anexo A: la cantidad va en posición distinta según súper, Xinya usa punto decimal, Mercadona trae sub-línea de peso + €/kg, Cash Fresh trae letra de IVA, Xinya trae descripciones en chino). Nota: los tickets necesitan resolución de imagen decente para leerse; no reduzcas en exceso antes de mandarlos al modelo.
- Tras la respuesta: **recomputa el checksum** (Σ `line_total` == `total`), guárdalo. Normaliza el separador decimal a numérico canónico.

## Pantalla de validación (clave)

Tras extraer, el usuario ve una **tabla editable** con las líneas:
- Si el checksum no cuadra o la confianza es baja, márcalo en rojo.
- El usuario puede corregir cualquier campo (el checksum valida números, no el emparejamiento nombre↔precio, así que esta revisión es imprescindible en tickets densos como el de Cash Fresh).
- Para cada línea, **sugiere** el `StoreProduct` existente de ese súper por similitud de trigramas (`pg_trgm`) sobre nombres/alias; el usuario confirma, crea nuevo o fusiona. Cada confirmación añade un `ProductAlias`.
- Permite fijar `display_name` y `notes` del producto (imprescindible para los productos de Xinya en chino: el usuario teclea la traducción una vez y se reutiliza).
- Al confirmar el ticket → genera las `PriceObservation` normalizadas según §6. Recuerda: `pieza_variable` (p.ej. tubos de pota) **no** se normaliza a €/kg y queda **fuera del conteo de subidas**.

## Normalización (§6)

Tres reglas según `vendido_por`:
- **envase**: base = cantidad × tamaño_del_envase.
- **peso**: la línea trae peso + €/kg → base directa.
- **pieza_variable**: sin peso ni €/kg en el ticket → `precio_unit_normalizado = null`, se guarda solo el €/pieza y no cuenta para subidas.

## Comparador y preferencias (Fase 1)

- UI para **agrupar** StoreProducts de distintos súper en un `ComparableGroup` (asistido por categoría + similitud de nombre).
- **Vista de comparación**: dentro de un grupo, ranking por `precio_unit_normalizado` (último precio conocido, mostrando antigüedad). Aplica la `UserProductPreference` del usuario (mecanismo A: resta `preference_margin` al preferido antes de comparar). Muestra SIEMPRE ambas cosas: el más barato objetivo Y la elección ajustada por preferencia, con lo que cuesta la diferencia.

## Histórico

Vista por `StoreProduct`: serie de precios (gráfica), nº de compras, primer/último precio, min/max, nº de subidas (cambios al alza en la serie sin ofertas; excluye `pieza_variable`).

## Método de trabajo (importante)

1. Primero **lee** `analisis_gestor_tickets.md` y las fotos, y **resume tu plan y el modelo antes de escribir código**. Si algo del modelo es ambiguo o hay una decisión irreversible, PÁRATE y pregunta.
2. **Scaffold mínimo que arranque**: `docker-compose up` levanta Postgres, el backend compila y sirve, el frontend carga. Verifícalo antes de seguir.
3. Modelo + migraciones Flyway + seeds.
4. **Fase 0**: ingesta → extracción → validación → guardar → histórico. Verifica con una de las fotos reales que el checksum cuadra y las líneas salen bien.
5. **Fase 1**: comparador + preferencias.
6. Cambios **quirúrgicos e incrementales**; no rompas lo que ya funciona; commit por hito.
