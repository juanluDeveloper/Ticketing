# Prompt para el rediseño del frontend

Pégalo entero. Está escrito para que quien lo lea no necesite ver el código.

---

## Qué es la aplicación

Un gestor personal de tickets de la compra. Fotografío el ticket del súper, un modelo de
visión lo transcribe línea a línea, yo reviso y confirmo lo transcrito, y esa confirmación
alimenta dos cosas: el **histórico de precios** de cada producto y un **comparador** que
dice en qué súper sale más barato cada artículo.

No es una app de contabilidad ni de presupuesto. No suma gastos por mes ni categoriza el
consumo. Lo único que persigue es: *¿este producto ha subido de precio?* y *¿dónde lo
compro más barato?*

La transcripción automática **se equivoca**, y equivocarse de una forma muy concreta:
desplaza una descripción respecto a su importe, y entonces la leche cuesta lo que costaba
el detergente. Por eso existe una pantalla de validación con comprobaciones aritméticas, y
por eso esa pantalla es el corazón del producto y no un trámite.

## Quién la usa y dónde

Una sola persona, la que la ha construido. Dos contextos reales:

- **Móvil, de pie en la cocina**, con el ticket recién sacado de la bolsa: hacer la foto y
  soltarla. Nada más. La revisión no se hace aquí.
- **Escritorio, sentado**, revisando la transcripción contra la foto del ticket, o mirando
  si el aceite ha vuelto a subir. Sesiones de varios minutos.

Usuario experto en lo suyo: entiende qué es una base imponible y una letra de IVA. No hay
que explicarle conceptos, hay que **no hacerle perder el hilo**.

## Vocabulario del dominio — no renombrar nada de esto

| Término | Qué es |
|---|---|
| **Ticket** | Una compra. Tiene súper, fecha impresa, total, número de recibo y N líneas |
| **Línea** | Una fila del ticket: descripción, cantidad, precio unitario, importe, letra de IVA |
| **Letra de IVA** | `A` (4 %), `B` (10 %), `C` (21 %). Solo Cash Fresh la imprime por línea |
| **Producto de súper** | El artículo concreto en un súper concreto. Cada línea confirmada se asigna a uno |
| **Observación de precio** | Lo que genera confirmar un ticket: un punto en la serie de ese producto |
| **Grupo comparable** | El mismo artículo en varios súper, para enfrentarlos en €/kg, €/L o €/ud |
| **Preferencia y prima** | "Prefiero el de Mercadona y pago hasta 0,30 €/L de más por él" |
| **Comprobación (C1…C5, H3, H4)** | Las verificaciones aritméticas sobre la transcripción |

Estados de un ticket, en orden: `en cola` → `extrayendo…` → `pendiente de revisar` →
`validado`. Y una vía muerta: `error`.

## El flujo que lo explica todo

```
Foto  →  cola  →  el modelo transcribe (minutos, GPU local)  →  PENDIENTE DE REVISAR
                                                                       │
                                        yo corrijo y asigno productos  │
                                                                       ▼
                                                                   VALIDADO
                                                                       │
                                                 ┌─────────────────────┴────────────────┐
                                                 ▼                                      ▼
                                          Histórico de precios                    Comparador
```

La aplicación **espera**. La extracción tarda de uno a varios minutos en una GTX 1070. Ese
tiempo muerto está hoy resuelto con un texto gris y un polling cada 4 s, y es una de las
cosas que peor se sienten.

## Las pantallas

Hoy son cinco vistas y una barra de pestañas: **Tickets · Histórico · Comparador**.

### 0. Login

Usuario y contraseña, auth básica. Una caja centrada. No hay registro, no hay recuperación,
no hay más usuarios. No merece protagonismo, pero es lo primero que se ve.

### 1. Tickets — subida y lista

**Subir**: un selector de súper (`detectar por el ticket` / Mercadona / Cash Fresh / Xinya)
y un botón grande de *Hacer foto o elegir imagen*. Decir el súper a mano importa: con el
súper equivocado se aplican reglas de formato ajenas y las comprobaciones mienten. Aviso
posible al subir: *"esta misma foto ya se subió en el ticket #12"*.

**Lista**: una fila por ticket con estado, súper, fecha de compra, total, nº de líneas y una
✕ para borrar. Ejemplo real de fila:

```
[pendiente de revisar]  Cash Fresh   2026-05-16 21:23   53,93 €   22 líneas
```

Borrar es lo que libera el número de recibo para volver a subir la compra con una foto
mejor; si el ticket ya está validado, el backend exige una segunda confirmación porque el
borrado se lleva por delante los precios que aportó al histórico. Hoy eso son dos
`window.confirm()` seguidos.

### 2. Validación — **la pantalla crítica**

Se abre al pinchar un ticket. Es donde se pasa el tiempo y donde hoy más se sufre. Contiene,
en este orden:

1. **Barra**: volver, nombre del súper, `2026-05-16 21:23 · 53,93 € · 22 líneas`, ocultar
   foto, borrar ticket.
2. **Semáforos**: una lista de 7 comprobaciones, cada una con código, descripción, estado y
   un detalle técnico. Tres estados, nunca dos:
   - `pasa` (verde) — `C1 · Suma de líneas contra el total impreso · pasa · suma 53.93 contra total 53.93`
   - `falla` (rojo) — `C3 · Importes por letra de IVA · falla · B=28.55 contra 25.97 FALLA`
   - `no aplica` (gris) — `C4 · Recuento de artículos · no aplica · Cash Fresh no imprime recuento`
3. **Aviso de cobertura** cuando pocas líneas son verificables: *"26 líneas y solo 6
   comprobables: las otras 20 no las mira nadie salvo tú"*. En Mercadona pasa siempre.
4. **Problemas concretos**, en rojo o ámbar, con el código de la comprobación delante.
5. **La tabla de líneas**, editable entera, junto a **la foto del ticket** en una columna
   pegajosa a la derecha. Ocho columnas: `# · Descripción · Cant. · P. unit · Importe · IVA ·
   Venta · Producto`. Bajo cada fila puede haber hasta tres subfilas: el peso (`0,26 × 31,95
   = 8,31 €`), la fila cruda transcrita (`QUESO CABRA PAYOYA  0,260x 31,95  8,31 A`) y los
   problemas de esa línea. Cada fila necesita un producto asignado, con un buscador y un
   formulario de alta.
6. **Acciones**: *Crear producto para las 22 líneas sin asignar*, *Guardar correcciones*,
   *Confirmar ticket*. Confirmar es el paso irreversible: genera la serie de precios y exige
   cero comprobaciones en rojo y cero líneas sin producto.

En un ticket de 22 líneas eso son más de 100 campos editables en pantalla a la vez, con
tres subfilas intercaladas, y hoy todo tiene el mismo peso visual. **Es el problema número
uno a resolver.**

### 3. Histórico — productos y ficha de producto

**Lista de productos**: nombre, súper, nº de compras, último precio normalizado
(`1,19 €/L`), antigüedad (`hace 3 días`), nº de subidas de precio.

**Ficha**: métricas en rejilla (compras, último precio, mínimo, máximo, subidas, bajadas,
volatilidad, gasto total), una gráfica de línea de la evolución del precio, y la tabla de
puntos con fecha, €/pieza, normalizado, si cuenta para subida y el ticket de origen. Las
ofertas se pintan huecas en la gráfica: están en lo que pagué, pero no en el precio de
estantería. Si un producto no tiene serie, se dice por qué: casi siempre falta teclear el
tamaño del envase una vez.

Esta es **la pantalla que debería invitar a navegar** y hoy es una tabla gris. Es donde
está la respuesta a "¿me están subiendo el precio del café?", que es la razón de existir de
todo lo demás.

### 4. Comparador — grupos y veredicto

**Lista de grupos**: nombre, miembros, cuántos son comparables, más barato, si hay
preferencia.

**Detalle**: el veredicto en tres cifras juntas — **más barato**, **tu elección** y **lo que
te cuesta** la diferencia — más una explicación en texto. Debajo, el ranking por precio
normalizado con badges de *más barato* y *preferido*, la fecha de cada precio y su
antigüedad. Y aparte, nunca mezclado, **fuera de la comparación**: los miembros sin dato o
sin tamaño de envase, con el motivo escrito.

Un aviso de calidad del dato cuando toca: precios viejos, o un solo súper con datos. No
invalida el número, cambia cuánto te puedes fiar de él.

## Qué falla hoy en el diseño

Sé honesto conmigo: el frontend actual es un prototipo funcional de 214 líneas de CSS.
Funciona y no engaña, pero:

- **Todo pesa lo mismo.** Cinco pantallas, todas cajas blancas con borde gris de 1 px y el
  mismo `h2` de 1,05 rem. Nada dice qué es importante.
- **La tabla de validación abruma.** 22 filas × 8 columnas de inputs, más subfilas. No hay
  forma de ver de un vistazo qué líneas están bien y cuáles piden atención.
- **Los semáforos son texto.** Siete líneas de lista con un borde de color a la izquierda.
  El detalle técnico (`B=28.55 contra 25.97`) es exactamente lo que hace falta cuando algo
  falla y ruido cuando todo va bien, y hoy se muestra siempre igual.
- **La espera está sin diseñar.** "Extrayendo con el modelo. Puede tardar unos minutos" en
  gris, y una lista que se refresca sola cada 4 s sin decirlo.
- **La lista de tickets no prioriza.** Un ticket pendiente de revisar es una tarea. Uno
  validado es archivo. Se ven igual.
- **El histórico no invita.** La parte más interesante del producto —la evolución de
  precios— está enterrada tras dos clics y presentada como una hoja de cálculo.
- **Los vacíos son un párrafo gris.** "Aún no hay grupos."
- **El móvil es el escritorio encogido.** Tres `@media` y poco más, cuando el móvil solo
  tiene que hacer una cosa: foto y a la cola.
- **Lo destructivo usa `window.confirm()`.** Borrar un ticket validado, que se lleva
  precios del histórico, merece más que un diálogo del navegador.

## Invariantes que el diseño NO puede romper

Esto no es estética, es la razón de que la app sirva para algo:

1. **Tres estados en las comprobaciones, nunca dos.** Verde pasa, rojo falla, **gris no
   aplica a este formato**. Una comprobación que no ha podido correr no es una comprobación
   superada. Jamás verde por omisión.
2. **La baja cobertura se dice, no se esconde.** Si solo 6 de 26 líneas son verificables,
   eso tiene que verse tanto como los siete verdes.
3. **El veredicto del comparador enseña siempre las dos opciones y el coste.** Enseñar solo
   la recomendación haría invisible la prima, y entonces deja de ser una decisión y pasa a
   ser un sesgo.
4. **"Fuera de la comparación" no se mezcla con el ranking ni se omite.** "No lo he comprado
   ahí" y "ahí es más caro" son cosas distintas.
5. **La foto del ticket tiene que estar a la vista mientras se corrigen las líneas.** El
   trabajo es comparar papel contra pantalla.
6. **La fila cruda transcrita tiene que ser accesible.** Es lo que permite ver que el modelo
   leyó `0,260x 31,95` donde el papel pone otra cosa.
7. **Confirmar y borrar son irreversibles.** Merecen fricción proporcional, y el motivo del
   bloqueo tiene que leerse antes de intentarlo, no después.
8. **Nada de datos inventados.** Donde no hay dato va `—`, y si hay motivo, el motivo.
9. **Números en español**: coma decimal, `53,93 €`, `1,19 €/L`, fechas `16/05/2026 21:23`.
10. **Todo el texto en español de España**, tono sobrio y directo, sin exclamaciones ni
    infantilizar. El usuario sabe lo que es una base imponible.

## Objetivos del rediseño

Por orden de importancia:

1. **Que en cada pantalla se sepa qué hacer ahora.** Sobre todo en validación: qué líneas
   piden atención, qué falta para poder confirmar.
2. **Que la tabla de validación deje de abrumar** sin perder ni un dato de los que hay hoy.
   Densidad controlada, jerarquía dentro de la fila, lo problemático destacado y lo correcto
   tranquilo.
3. **Que el histórico invite a entrar.** Es la recompensa de todo el trabajo de validar y
   hoy no lo parece. Que apetezca pinchar un producto y ver su curva.
4. **Que la espera se sienta bien.** La extracción tarda minutos: eso se comunica, no se
   disimula.
5. **Que el móvil haga una cosa bien** (foto → cola → ver el estado) en vez de todo mal.

## Restricciones técnicas

- **React 18 + Vite**, JavaScript sin TypeScript. Componentes de función y hooks.
- **Sin librería de componentes** (nada de MUI, Chakra, Ant). CSS propio o Tailwind si lo
  justificas. La gráfica es SVG a mano hoy; puede seguir siéndolo.
- El backend es Spring Boot y **no se toca**. Los nombres de los campos vienen dados.
- Auth básica en `localStorage`, la imagen del ticket se descarga autenticada y se muestra
  como object URL.
- Modo oscuro: bienvenido, no obligatorio. Si lo haces, que funcione entero.
- Accesibilidad real: foco visible, contraste AA, la tabla navegable con teclado. Se editan
  100 campos seguidos: el orden de tabulación importa de verdad.

## API disponible

```
GET    /api/tickets                       lista: id, status, storeName, purchasedAt, total, lineCount, extractionError
POST   /api/tickets?storeCode=            subida (multipart) → ticketId, sameImageAs[]
GET    /api/tickets/{id}                  detalle: summary, checks[], ticketIssues[], coverageWarning, lines[]
GET    /api/tickets/{id}/image            la foto (autenticada)
POST   /api/tickets/{id}/validate         guardar correcciones / confirmar
POST   /api/tickets/{id}/reextract        reintentar tras error
DELETE /api/tickets/{id}?force=           borrar (force para los validados)
GET    /api/stores  /api/categories
GET    /api/products  ·  /api/products/{id}/history
GET    /api/stores/{id}/products?q=       buscador para asignar producto a una línea
GET    /api/groups  ·  POST /api/groups
GET    /api/groups/{id}/comparison        group, ranking[], notComparable[], verdict, dataWarning
GET    /api/groups/{id}/suggestions
POST   /api/groups/{id}/members  ·  DELETE .../members/{storeProductId}
PUT    /api/groups/{id}/preference  ·  DELETE  (storeProductId, marginType ABS|PCT, marginValue, note)
```

Cada comprobación llega como `{ code, description, applicable, passed, detail }`. Cada línea
como `{ id, rawRowText, description, quantity, unitPrice, lineTotal, taxLetter, soldBy,
weight, storeProductId, storeProductName, issues[] }`.

## Entregable

1. **Un sistema visual**: paleta (con los tres colores de estado bien resueltos, que hoy son
   `#1f6f4a`, `#b3261e`, `#8a6100`), tipografía, escala de espaciado, radios, sombras,
   estados de foco. Justifica las decisiones en una línea cada una.
2. **Las cinco pantallas rediseñadas**, en HTML+CSS o React, con datos realistas — usa el
   ticket de Cash Fresh de 53,93 € con 22 líneas, no `lorem ipsum`.
3. **Los estados de cada pantalla**: vacío, cargando, error, y en validación el estado
   "todo en verde, listo para confirmar" frente a "tres cosas que arreglar".
4. **Móvil y escritorio** de las pantallas donde cambie algo de verdad.
5. **Los componentes sueltos** que se repiten: badge de estado, fila de comprobación, métrica,
   fila de ticket, veredicto.

## Lo que NO quiero

- Renombrar los conceptos del dominio para que suenen más amables.
- Esconder el detalle técnico de las comprobaciones. Puede plegarse, no desaparecer.
- Gráficas decorativas, medidores de progreso inventados, gamificación, rachas, ni resúmenes
  de gasto mensual: esta app no hace eso.
- Un dashboard de bienvenida con seis tarjetas de métricas que nadie ha pedido.
- Emojis en la interfaz.
- Dependencias pesadas para cosas que ya funcionan con 200 líneas de CSS.
