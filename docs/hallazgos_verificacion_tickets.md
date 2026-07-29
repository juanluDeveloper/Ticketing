# Hallazgos — verificación manual de los 3 tickets

Documento derivado de `analisis_gestor_tickets.md`. Recoge lo que se ha comprobado
aritméticamente sobre las tres fotos reales y las consecuencias de diseño.
No sustituye al análisis: lo corrige y lo amplía.

---

## 1. El desalineamiento columna-descripción / columna-precio es real y reproducible

Al transcribir a mano las fotos de **Mercadona** y **Cash Fresh**, la primera lectura
salió **desplazada exactamente una fila**: la columna de importes empieza a la altura
de la cabecera y cada precio queda pegado a la descripción anterior.

Es exactamente el fallo que avisa el §Anexo A ("el checksum valida los números, no el
emparejamiento"), y ocurre antes de meter ningún VLM: es un problema del papel
arrugado y de las dos columnas separadas por mucho blanco.

**Lo nuevo: se puede detectar y corregir automáticamente.** No hace falta confiar solo
en la revisión humana.

### Mercadona — lo resuelve la aritmética de línea

Cinco líneas traen cantidad > 1 y por tanto P.Unit impreso. Solo el emparejamiento
correcto cumple `cantidad × precio_unit == importe` en las cinco:

| Descripción | cant × p.unit | importe |
|---|---|---|
| BEBIDA AVELLANAS | 3 × 1,25 | 3,75 |
| LECHE FRESCA ENT | 2 × 1,15 | 2,30 |
| HUMMUS PIMIENTO | 2 × 1,45 | 2,90 |
| BOQUERONES ALIÑADOS | 2 × 1,65 | 3,30 |
| ALMEJA PACIFIC | 2 × 1,80 | 3,60 |

Con el desplazamiento, las cinco fallan. Suma total del emparejamiento correcto:
**63,16 = TOTAL impreso**. Verificado línea a línea.

### Cash Fresh — lo resuelve la letra de IVA

Cash Fresh no tiene líneas con P.Unit ambiguo, pero sí letra de IVA por línea. Agrupando
importes por letra y dividiendo por (1+tipo) salen **exactamente** las bases impresas:

| Letra | Tipo | Importes | Σ | Base calculada | Base impresa |
|---|---|---|---|---|---|
| A | 4 % | 2,55 + 0,79 | 3,34 | 3,21 | **3,21** |
| B | 10 % | 2,18 + 2,98 + 5,58 + 2,19 | 12,93 | 11,76 | **11,76** |
| C | 21 % | 1,45 + 1,25 + 1,12 + 3,50 + 5,89 | 13,21 | 10,92 | **10,92** |

Σ bases 25,89 + Σ IVA 3,59 = **29,48 = TOTAL** ✓

Eso valida el par (precio, letra), que van en la misma fila física. El par
(descripción, precio) lo resuelve la **semántica del tipo de IVA**, y es concluyente:

| Descripción | cant × p.unit | importe | letra | ¿coherente? |
|---|---|---|---|---|
| ARENA GATOS SEPIOLIT | 1 × 1,45 | 1,45 | C 21 % | sí, no alimentación |
| CHOCOLATE BLANCO IFA | 1 × 2,18 | 2,18 | B 10 % | sí |
| ESPINACAS C/GAR. 360 | 1 × 2,98 | 2,98 | B 10 % | sí, congelado preparado |
| FLOTA LAVAV. 1,10L | 1 × 1,25 | 1,25 | C 21 % | sí, detergente |
| IFA SABE LEJIA C/DET | 1 × 1,12 | 1,12 | C 21 % | sí, lejía |
| JAMON RESERVA +14 ME | 2 × 2,79 | 5,58 | B 10 % | sí |
| PAPILLA FRUTAS VARIA | 1 × 2,19 | 2,19 | B 10 % | sí |
| QUESO RULO 180GR | 1 × 2,55 | 2,55 | A 4 % | sí, queso es superreducido |
| QUITAPELUSAS IFA SAB | 2 × 1,75 | 3,50 | C 21 % | sí, no alimentación |
| LECHE ENTERA ACORES | 1 × 0,79 | 0,79 | A 4 % | sí, leche es superreducido |
| DET MARSE FLOTA 100D | 1 × 5,89 | 5,89 | C 21 % | sí, detergente |

Con el desplazamiento de una fila salía "leche al 21 %", "quitapelusas al 4 %" y
"detergente al 4 %": imposible. **Los 11 encajan solo en el emparejamiento correcto.**

### Consecuencia de diseño

Añadir un **motor de validación en servidor** con cinco comprobaciones independientes,
no solo el checksum del total:

| ID | Comprobación | Requiere que el ticket traiga | Detecta |
|---|---|---|---|
| C1 | Σ `line_total` == `total` | total impreso (los 3) | errores de importe |
| C2 | `quantity × unit_price == line_total` (±0,01; ±0,02 si peso) | P.Unit impreso **en esa línea** | desalineamiento y cantidades mal leídas |
| C3 | Σ `line_total` por letra / (1+tipo) == base impresa | letra de IVA por línea + tabla de bases (solo Cash Fresh) | desalineamiento precio↔letra |
| C4 | Σ `quantity` == "N ART." | recuento de artículos (solo Xinya) | líneas perdidas o duplicadas |
| C5 | Σ bases + Σ cuotas == `total` | tabla de IVA (los 3) | lectura mala de la tabla de IVA |

C1 por sí solo **no detecta nada** de los fallos anteriores: la suma es la misma con las
descripciones desplazadas. C2 y C3 sí. Semáforo por línea, no solo por ticket.

### Límite honesto: estas checks NO sustituyen la validación humana

El desalineamiento se resolvió en estos dos tickets porque **estos dos tickets tenían
redundancia sobrante**. Esa redundancia no está garantizada:

- **C2 solo cubre las líneas con P.Unit impreso.** En Mercadona son 5 de 26; las otras 21
  van a cantidad 1 sin precio unitario y quedan **sin cobertura aritmética ninguna**. Un
  ticket de Mercadona entero de líneas a cantidad 1 no se autocorrige en absoluto.
- **C3 solo desambigua si el ticket mezcla letras.** El de Cash Fresh tenía A, B y C
  repartidas; una compra entera de no-alimentación (todo `C`) hace que cualquier
  permutación de descripciones cuadre igual.
- **C4 detecta líneas perdidas, no reordenadas.** Σ cantidades no cambia al desplazar.
- La confirmación semántica del apartado anterior ("leche al 21 % es imposible") la hizo
  **una persona**, no el motor. Automatizarla exige histórico previo del producto.

**Postura correcta:** las 5 checks son la **primera línea** — su trabajo es degradar la
pantalla de validación de "corregir" a "confirmar", y señalar en rojo dónde mirar. La
**revisión humana sigue siendo la red de seguridad** y sigue siendo obligatoria antes de
pasar a `VALIDADO`. En tickets sin redundancia el motor debe decirlo explícitamente:
*"cobertura baja: 21 de 26 líneas sin comprobación cruzada, revisa el emparejamiento"*.

### Las checks son por-súper: el motor decide cuáles aplica

C3 solo tiene sentido en Cash Fresh y C4 solo en Xinya. Ejecutar una check que el formato
no soporta y darla por pasada es peor que no ejecutarla: infla la confianza.

El motor consulta las capacidades declaradas en `store`
(`has_line_tax_letter`, `has_article_count`, `unit_price_only_when_multiple`) y para cada
ticket registra por cada check si fue **aplicable**, y si lo fue, si **pasó**. La UI
distingue tres estados: verde (pasó), rojo (falló), gris (no aplica a este formato). Nunca
verde por omisión.

De ahí sale una métrica de **cobertura** por ticket (líneas con al menos una comprobación
cruzada / total de líneas) que se enseña en la pantalla de validación para calibrar cuánta
atención merece.

Heurística adicional para tickets posteriores: si un `StoreProduct` ya tiene histórico
con letra de IVA `B` y llega una línea suya con letra `C`, casi seguro hay
desalineamiento → marcar en rojo. Requiere histórico, así que no sirve el primer mes.

---

## 2. `12 HUEVOS GRANDES-L` — el número inicial no siempre es cantidad

El Anexo A dice que en Mercadona la cantidad va al inicio de la línea. Falso en general:
`12 HUEVOS GRANDES-L … 3,20` es **una** docena de huevos a 3,20, no doce unidades.

Prueba: la suma de línea cuadra a 63,16 contando 3,20 una sola vez; y la columna P.Unit
está vacía en esa línea.

**Regla:** en Mercadona el entero inicial es cantidad **si y solo si** la columna P.Unit
está rellena en esa línea. Si está vacía, el número es parte del nombre del producto.
Va como instrucción explícita en el prompt del extractor y queda cubierta por C2.

Caso hermano no resuelto: `CARACOLA PASAS 10%` — ese "10 %" es parte del nombre
comercial, no un descuento. El extractor no debe interpretarlo como promoción.

---

## 3. El tamaño de envase viene en la descripción más veces de lo que parece

`precio_unit_normalizado` necesita `package_size`, y Mercadona **no** imprime tamaño en
la mayoría de líneas. Pero sí aparece embebido en el texto en bastantes casos de los tres:

- Xinya: `…330ML BEBIDA SBR PO` → 330 ml
- Cash Fresh: `FLOTA LAVAV. 1,10L` → 1,1 L · `QUESO RULO 180GR` → 180 g ·
  `ESPINACAS C/GAR. 360` → 360 g · `DET MARSE FLOTA 100D` → 100 dosis
- Mercadona: `IMPULSOR ROYAL 80GR` → 80 g

**Añadir un parser de tamaño** sobre `raw_description` (regex `\d+([.,]\d+)?\s?(g|gr|kg|ml|cl|l|ud|d)\b`)
que **prerrellene** el campo en la pantalla de validación. Es la mayor parte del trabajo
manual de Fase 1 y se ahorra casi entero. El usuario solo corrige.

Ojo con `100D` (dosis): dimensión `unidad`, no peso ni volumen. Un `ComparableGroup` no
puede mezclar detergente en dosis con detergente en litros → validar compatibilidad de
dimensión al añadir miembros a un grupo.

---

## 4. Detección de duplicados: el hash de imagen no basta

El §11 propone hash de la imagen. Dos fotos del mismo ticket tienen hash distinto, así
que no detecta el caso real (volver a fotografiar). Los tres tickets traen identificador
propio:

| Súper | Campo | Ejemplo |
|---|---|---|
| Mercadona | Factura simplificada | `2276-012-556655` |
| Cash Fresh | Operación | `260715/219/103/0168` |
| Xinya | Nº | `3042361` |

**Clave de deduplicación:** única sobre `(store_id, receipt_number)` cuando hay número, y
fallback a `(store_id, purchased_at, total)`. El hash de imagen se mantiene, pero como
señal secundaria.

---

## 5. Precisión numérica

Xinya imprime la base de IVA con **tres decimales**: `BASE 8.182  IVA 1.718  TOTAL 9.90`.
Los €/kg y los precios normalizados (€/g, €/ml) necesitan más de dos decimales.

- Dinero y bases: `NUMERIC(12,4)`, nunca `double`/`float`.
- `precio_unit_normalizado`: `NUMERIC(16,6)`.
- Tolerancias de checksum en céntimos, no en flotante (1,394 kg × 3,05 €/kg = 4,2517 → 4,25).

---

## 6. Extracción: dos etapas, no una

Pedirle al VLM directamente los campos estructurados es lo que permite la contaminación
entre filas. Propuesta:

1. **Transcripción**: el modelo devuelve cada fila física del bloque de líneas como
   cadena literal (`raw_row_text`), tal cual está impresa, sin interpretar.
2. **Parseo a campos**: se derivan `quantity` / `unit_price` / `line_total` **de esa misma
   cadena**, con la plantilla del súper. Descripción y precio vienen de la misma fila por
   construcción.

Además `raw_row_text` se guarda en el `LineItem` y la pantalla de validación puede
mostrar la fila cruda junto al recorte de la imagen: revisión visual inmediata.

Otras notas de extracción:

- Usar la **API nativa de Ollama** (`/api/chat` con `format`: JSON Schema), no el shim
  compatible con OpenAI: el soporte de salida estructurada es más fiable ahí.
- Validar el JSON contra el esquema **también en el backend**. La decodificación
  restringida fuerza la forma, no la veracidad.
- **Nunca extraer ni persistir** los 4 últimos dígitos de tarjeta (`****9885`) ni los
  códigos de autorización (`AUT: 985427`, `ARC`, `AID`). Aparecen en dos de los tres
  tickets. Si se guarda la respuesta cruda del modelo, se guardan también → el esquema
  del Anexo B ya los excluye, mantenerlo así y no volcar texto libre adicional.

---

## 7. Riesgo de rendimiento en la GTX 1070

Los documentos dan por cerrado que producción corre `Qwen3-VL 8B` en una GTX 1070 (8 GB)
y que "el comportamiento es idéntico" al de desarrollo en la RTX 5070 Ti.

Matices que conviene medir antes de fijarlo:

- La 1070 es **Pascal**: su rendimiento FP16 es una fracción del FP32, y el codificador de
  visión es la parte cara. No es "lo mismo pero más lento" por un factor pequeño.
- Un ticket alto a resolución legible genera **muchos tokens de visión**. 8 GB con pesos
  Q4 (~5 GB) + torre de visión + caché KV va justo.
- El comportamiento solo es idéntico si se **fijan** cuantización, `num_ctx` y parámetros
  de troceado de imagen en configuración, iguales en dev y en producción. Si no, en la
  5070 Ti cabe un contexto que en la 1070 no.

**Consecuencias:** extracción **asíncrona** (cola de trabajos, estado `EXTRAYENDO`), nunca
en la petición HTTP de subida.

### Prueba de humo en la 1070 antes de montar la tubería

La calidad que se vea en la 5070 Ti **no es representativa** de la de producción. Si los
8 GB obligan a bajar troceado de imagen o cuantización, lo que se degrada es justo el OCR
del ticket alto y denso, que es el caso que importa. Medirlo después de construir toda la
tubería significa descubrir tarde que el modelo elegido no vale.

Orden correcto, **antes** de escribir el cliente de extracción:

1. `ollama pull` del `Qwen3-VL 8B` (confirmar el tag exacto en la librería de Ollama).
2. Pasarle a mano la foto del **Xinya**: es el termómetro. Es el ticket con OCR chino, con
   separador decimal distinto y con el tamaño embebido en la descripción; si el modelo lo
   lee bien, los otros dos son más fáciles.
3. Repetir en la **1070 real**, con la misma cuantización y el mismo `num_ctx`, y comparar
   salidas carácter a carácter con la de la 5070 Ti.
4. Solo si coinciden se fija el modelo. Si no, plan B `Qwen3-VL 4B`, y se vuelve a medir.

Los tres ficheros de referencia están en `Ejemplo tickets cada super/`.

Estados de ticket: `SUBIDO → EXTRAYENDO → EXTRAIDO → VALIDADO`, más `ERROR_EXTRACCION`.
Los docs solo contemplan `pendiente/validado`. La imagen original se conserva siempre para
reextraer.

---

## 8. Matching: alias exacto antes que trigramas

El §5 va directo a `pg_trgm`. Antes conviene una pasada más barata y más precisa:

1. **Alias exacto normalizado** (mayúsculas, sin acentos, espacios colapsados). La mayoría
   de tickets repiten la cadena idéntica → resuelve la gran mayoría sin similitud.
2. **Trigramas** (`pg_trgm`, índice GIN `gin_trgm_ops`) solo para el resto.

Aviso con Xinya: `pg_trgm` rinde mal sobre texto chino. Para ese súper, calcular la
similitud sobre la **cola en alfabeto latino** (`330MLBEBIDA SBR PO`) y dejar el chino
para la coincidencia exacta y para `display_name` / `notes`.

`CREATE EXTENSION pg_trgm` requiere superusuario: va en la primera migración Flyway y
funciona con el usuario por defecto de la imagen de Postgres.

---

## 9. Otros ajustes al modelo

- **`pieza_variable` auto-sugerida**: misma `raw_description` ≥ 2 veces en el mismo ticket,
  cantidad 1, importes distintos (los tres TUBO DE POTA a 1,82 / 2,30 / 1,95) → proponerlo
  en la validación en vez de esperar a que el usuario lo marque.
- **`preference_margin` en la unidad de comparación del grupo** (€/kg, €/L, €/ud), no en
  unidad base (€/g son números ilegibles). Guardar `margin_type ∈ {abs, pct}` + valor.
- **Nº de subidas**: exige umbral mínimo (> 1 céntimo o > 0,5 %) y excluir observaciones
  del mismo día, además de excluir promociones y `pieza_variable`. Si no, el redondeo
  genera "subidas" fantasma.
- **Semilla de categorías**: sin un árbol inicial (~30 nodos) la sugerencia de agrupado
  "por categoría + similitud" no tiene con qué trabajar el primer mes.
- **`purchased_at`** como `TIMESTAMP` sin zona: los tickets son hora local y no interesa
  convertir.
- **Homónimos**: `MANGO S/AZ AÑADIDO` (1,25, envasado) y `MANGO` a peso (4,25) en el mismo
  ticket. El matcher no puede unir por subcadena; el criterio de desempate es
  `vendido_por` + presencia de sub-línea de peso.

---

## 10. Entorno de desarrollo — bloqueantes

Comprobado en la máquina de desarrollo:

| Herramienta | Estado |
|---|---|
| Java 21 (Temurin 21.0.11) | instalado |
| Maven 3.9.11 | instalado |
| Node 24.12.0 / npm 11.6.2 | instalado |
| Git | instalado |
| RTX 5070 Ti 16 GB, driver 610.62 | presente |
| Docker / Docker Compose | instalado (WSL2) |
| Ollama con `qwen3-vl:8b` | instalado, probado a mano con el Xinya |
| psql (cliente en el host) | no instalado; se usa `docker exec` |

### Conflicto de puerto: hay otro Postgres en la máquina

El servicio de Windows **`postgresql-x64-18` está instalado y corriendo**, escuchando en
`0.0.0.0:5432`. Al mapear el contenedor a 5432, Docker solo consiguió enganchar `::5432`
(IPv6) y no avisó: `docker compose ps` mostraba el puerto publicado y el contenedor
sano. Pero `jdbc:postgresql://localhost:5432` resuelve a IPv4, así que las conexiones
iban al **Postgres 18 del host**, que no tiene el rol `ticketing`, y fallaban con
`FATAL: la autentificación password falló para el usuario «ticketing»`.

Síntoma engañoso: parece un problema de credenciales del contenedor, y no lo es. La
prueba que lo separa es autenticar por TCP **dentro** del contenedor
(`docker exec ... psql "postgresql://ticketing:ticketing@127.0.0.1:5432/ticketing"`): si
ahí funciona, el problema es a quién se está conectando el host, no la contraseña.

**Resuelto** publicando el contenedor en **5433**, sin tocar el servicio del host.
`DB_URL` por defecto apunta ya a 5433.

### Colación

`postgres:17-alpine` corre sobre musl y no trae locales del sistema: un `LANG=es_ES.utf8`
se ignora con `WARNING: no usable system locales were found` y el cluster queda en `C`.
Se usa el proveedor **ICU** (`--locale-provider=icu --icu-locale=es-ES`), que no depende
de locales instalados, para que el orden alfabético de nombres de producto respete
acentos y ñ.

---

## 11. Contrato del cliente de extracción

Observado probando `qwen3-vl:8b` a mano contra la foto del Xinya (el peor caso: OCR
chino, punto decimal, dos líneas por producto). El modelo lee el chino bien, entiende la
estructura de dos líneas y llega a cruzar el "6 ART." con las cantidades por su cuenta.
El enfoque local es viable. Dos cosas que el cliente tiene que forzar:

- **Sale con `Thinking...` y razonamiento en voz alta.** Hay que usar la **API nativa de
  Ollama** (`POST /api/chat`) con `format` = el JSON Schema del Anexo B, para que la
  respuesta sea solo el JSON. No vale pedirlo en el prompt y confiar.
- **Las claves salen con tildes y espacios** ("precio unitario") si no se fijan. El
  esquema debe declararlas exactamente como el Anexo B, en snake_case sin acentos
  (`precio_unitario`, `importe_linea`, …), y `required` en todas, para que la
  decodificación restringida no deje margen.
- El backend **revalida el JSON contra el esquema** de todos modos: la decodificación
  restringida fuerza la forma, no la veracidad.
- Recordatorio de §6: el modelo devuelve primero la fila física literal
  (`raw_row_text`) y los campos se derivan de esa cadena.
