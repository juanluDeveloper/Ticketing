import React, { useEffect, useMemo, useState } from 'react'
import { api } from './api.js'

/**
 * El orden por defecto lo decide el backend: primero lo más comprado, que es
 * donde la serie tiene algo que contar. Los demás existen porque la pregunta
 * cambia — "¿qué me están subiendo?" no se responde con la misma lista que
 * "¿qué compro más?".
 */
const ORDERS = [
  ['compras', 'más compras'],
  ['subida', 'más ha subido'],
  ['caro', 'precio más alto'],
  ['reciente', 'comprado hace menos'],
  ['nombre', 'nombre'],
]

export default function HistoryView() {
  const [products, setProducts] = useState(null)
  const [openId, setOpenId] = useState(null)
  const [error, setError] = useState(null)
  const [query, setQuery] = useState('')
  const [store, setStore] = useState('')
  const [order, setOrder] = useState('compras')

  useEffect(() => {
    api.products().then(setProducts).catch((e) => setError(e.message))
  }, [])

  const stores = useMemo(() => {
    if (!products) return []
    return [...new Set(products.map((p) => p.storeName).filter(Boolean))].sort()
  }, [products])

  const shown = useMemo(() => {
    if (!products) return []
    const needle = query.trim().toLowerCase()
    const filtered = products.filter(
      (p) =>
        (!store || p.storeName === store) &&
        (!needle || p.name.toLowerCase().includes(needle)),
    )
    return [...filtered].sort(comparator(order))
  }, [products, query, store, order])

  if (error) return <p className="error">{error}</p>
  if (openId) return <ProductHistory id={openId} onClose={() => setOpenId(null)} />
  if (!products) return <p className="muted">Cargando…</p>

  return (
    <section>
      <div className="pagehead">
        <h2>Histórico de precios</h2>
        <p className="muted">
          Un producto por súper. La serie sale de los tickets confirmados: cada línea validada
          añade un punto.
        </p>
      </div>

      {products.length === 0 ? (
        <div className="empty">
          <h3>Aún no hay productos</h3>
          <p className="muted">
            Salen de validar tickets: al confirmar uno, cada línea crea o reutiliza un producto y
            deja un precio en su serie.
          </p>
        </div>
      ) : (
        <>
          <div className="filters">
            <input
              className="search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Buscar producto"
              aria-label="Buscar producto"
            />
            <label>
              Súper
              <select value={store} onChange={(e) => setStore(e.target.value)}>
                <option value="">todos</option>
                {stores.map((s) => (
                  <option key={s} value={s}>
                    {s}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Ordenar por
              <select value={order} onChange={(e) => setOrder(e.target.value)}>
                {ORDERS.map(([v, l]) => (
                  <option key={v} value={v}>
                    {l}
                  </option>
                ))}
              </select>
            </label>
            <span className="muted small">
              {shown.length === products.length
                ? `${products.length} productos`
                : `${shown.length} de ${products.length}`}
            </span>
          </div>

          {shown.length === 0 ? (
            <div className="empty">
              <h3>Ningún producto coincide</h3>
              <p className="muted">
                Prueba con menos texto o quita el filtro de súper. El mismo artículo en dos súper
                son dos productos distintos, cada uno con el nombre que imprime su ticket.
              </p>
            </div>
          ) : (
            <div className="prodgrid">
              {shown.map((p) => (
                <ProductCard key={p.id} product={p} onOpen={() => setOpenId(p.id)} />
              ))}
            </div>
          )}
        </>
      )}
    </section>
  )
}

function ProductCard({ product: p, onOpen }) {
  return (
    <button type="button" className="prodcard" onClick={onOpen}>
      <span className="prodcard-head">
        <span className="prodcard-name">{p.name}</span>
        <span className="muted small">{p.storeName ?? p.storeCode}</span>
      </span>

      {p.comparable && p.lastNormalizedUnitPrice != null ? (
        <>
          <span className="prodcard-price">
            {fmt(p.lastNormalizedUnitPrice)}
            <span className="unit"> €/{p.normalizedUnit}</span>
            <Change pct={p.changePct} />
          </span>
          <Sparkline series={p.series} />
          <span className="muted small">
            {p.purchaseCount} {p.purchaseCount === 1 ? 'compra' : 'compras'} · {ageLabel(p.daysSinceLast)}
            {p.increaseCount > 0 && ` · ${p.increaseCount} ${p.increaseCount === 1 ? 'subida' : 'subidas'}`}
          </span>
        </>
      ) : (
        <>
          <span className="prodcard-price faint">—</span>
          {/* El motivo aquí y no solo en la ficha: casi siempre es que falta
              teclear el tamaño del envase, y así se ve sin entrar. */}
          <span className="warn small">{p.notComparableReason ?? 'sin precio comparable'}</span>
          <span className="muted small">
            {p.purchaseCount} {p.purchaseCount === 1 ? 'compra' : 'compras'} · {ageLabel(p.daysSinceLast)}
          </span>
        </>
      )}
    </button>
  )
}

/**
 * Subir de precio es malo y baja es bueno, así que el color va al revés que en
 * una cotización: rojo cuando sube. Sin flecha ni icono — el signo ya lo dice y
 * el color lo refuerza.
 */
function Change({ pct }) {
  if (pct == null) return null
  const value = Number(pct)
  if (value === 0) return <span className="change flat"> igual</span>
  const tone = value > 0 ? 'up' : 'down'
  return (
    <span className={`change ${tone}`}>
      {' '}
      {value > 0 ? '+' : '−'}
      {fmtPct(Math.abs(value))} %
    </span>
  )
}

/** Miniserie de la tarjeta: la forma, no los valores. Los valores están dentro. */
function Sparkline({ series }) {
  if (!series || series.length < 2) {
    return <span className="sparkline-empty muted small">una sola compra, aún no hay serie</span>
  }

  const values = series.map(Number)
  const min = Math.min(...values)
  const max = Math.max(...values)
  const span = max - min || max || 1
  const x = (i) => (i * 100) / (values.length - 1)
  const y = (v) => 28 - ((v - min) / span) * 24

  const path = values.map((v, i) => `${i ? 'L' : 'M'} ${x(i).toFixed(2)} ${y(v).toFixed(2)}`).join(' ')

  return (
    <svg className="sparkline" viewBox="0 0 100 32" preserveAspectRatio="none" aria-hidden="true">
      <path d={path} fill="none" stroke="currentColor" strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
      <circle cx={x(values.length - 1)} cy={y(values[values.length - 1])} r="2" fill="currentColor" />
    </svg>
  )
}

function ProductHistory({ id, onClose }) {
  const [history, setHistory] = useState(null)
  const [error, setError] = useState(null)
  const [editing, setEditing] = useState(false)

  useEffect(() => {
    api.productHistory(id).then(setHistory).catch((e) => setError(e.message))
  }, [id])

  if (error) return <p className="error">{error}</p>
  if (!history) return <p className="muted">Cargando…</p>

  const { product, points, metrics, notComparableReason } = history
  const normalized = points.filter((p) => p.normalizedUnitPrice != null)
  const sincePrevious = variation(normalized, 2)
  const sinceFirst = variation(normalized, normalized.length)

  return (
    <section>
      <button className="link" onClick={onClose}>
        ← Volver a productos
      </button>

      <div className="pagehead">
        <h2>{product.displayName || product.canonicalName}</h2>
        <p className="muted">
          {product.storeName} · {product.canonicalName}
          {product.packageSize && ` · ${fmtPlain(product.packageSize)} ${product.packageUnit}`}
        </p>
      </div>

      {product.notes && <p className="notes">{product.notes}</p>}

      {/* El aviso lleva al sitio donde se arregla. Antes decía "ponlo una vez en
          el producto" y no había dónde ponerlo: el tamaño solo se podía teclear
          al crear el producto desde un ticket. */}
      {notComparableReason && (
        <div className="warn strong actionable">
          <span>{notComparableReason}</span>
          {!editing && (
            <button onClick={() => setEditing(true)}>Poner el tamaño del envase</button>
          )}
        </div>
      )}

      {!notComparableReason && !editing && (
        <div className="actions tight">
          <button className="link" onClick={() => setEditing(true)}>
            Editar producto
          </button>
        </div>
      )}

      {editing && (
        <ProductForm
          product={product}
          purchaseCount={metrics.purchaseCount}
          lastPricePerPiece={points.length ? points[points.length - 1].pricePerPiece : null}
          onCancel={() => setEditing(false)}
          onSaved={(updated) => {
            setHistory(updated)
            setEditing(false)
          }}
        />
      )}

      {/* Una cifra manda y el resto acompaña. Antes las ocho pesaban igual y el
          precio de hoy competía con el gasto acumulado. */}
      {metrics.lastNormalizedUnitPrice != null && (
        <div className="hero">
          <div>
            <dt>Último precio</dt>
            <dd>
              {fmt(metrics.lastNormalizedUnitPrice)}
              <span className="unit"> €/{metrics.normalizedUnit}</span>
            </dd>
            <p className="muted small">{ageLabel(metrics.daysSinceLast)}</p>
          </div>
          <div className="hero-changes">
            <p>
              <span className="muted small">Desde la compra anterior</span>
              <Change pct={sincePrevious} />
            </p>
            <p>
              <span className="muted small">Desde la primera</span>
              <Change pct={sinceFirst} />
            </p>
          </div>
        </div>
      )}

      <dl className="metrics">
        <Metric label="Compras" value={metrics.purchaseCount} />
        <Metric
          label="Mínimo"
          value={metrics.minNormalizedUnitPrice != null ? fmt(metrics.minNormalizedUnitPrice) : '—'}
        />
        <Metric
          label="Máximo"
          value={metrics.maxNormalizedUnitPrice != null ? fmt(metrics.maxNormalizedUnitPrice) : '—'}
        />
        <Metric
          label="Subidas"
          value={metrics.increaseCount}
          hint={`sobre ${metrics.comparablePoints} puntos${
            metrics.excludedPoints
              ? `, ${metrics.excludedPoints} ${metrics.excludedPoints === 1 ? 'excluido' : 'excluidos'}`
              : ''
          }`}
        />
        <Metric label="Bajadas" value={metrics.decreaseCount} />
        <Metric
          label="Volatilidad"
          value={metrics.volatility != null ? `${fmtPct(metrics.volatility * 100)} %` : '—'}
        />
        <Metric label="Gasto total" value={`${fmt(metrics.totalSpent)} €`} />
      </dl>

      <PriceChart points={points} unit={metrics.normalizedUnit} />

      <table>
        <thead>
          <tr>
            <th>Fecha</th>
            <th className="right">€/pieza</th>
            <th className="right">Normalizado</th>
            <th>Cuenta</th>
            <th>Ticket</th>
          </tr>
        </thead>
        <tbody>
          {[...points].reverse().map((p, i) => (
            <tr key={i}>
              <td>{fmtDate(p.date)}</td>
              <td className="right">{fmt(p.pricePerPiece)}</td>
              <td className="right">
                {p.normalizedUnitPrice != null
                  ? `${fmt(p.normalizedUnitPrice)} €/${p.normalizedUnit}`
                  : '—'}
              </td>
              <td className="muted">
                {p.countsForIncrease ? 'sí' : p.promo ? 'no, oferta' : 'no'}
              </td>
              <td className="muted">{p.ticketId ? `#${p.ticketId}` : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}

/**
 * El tamaño del envase es el campo que desbloquea todo lo demás: sin él no hay
 * €/L, sin €/L no hay serie ni comparación. Se teclea una vez por producto y
 * las compras ya registradas se recalculan solas en el servidor.
 */
const SOLD_BY = [
  ['PACKAGE', 'envase'],
  ['WEIGHT', 'a peso'],
  ['VARIABLE_PIECE', 'pieza variable'],
]

/** Las mismas equivalencias que UnitConverter en el servidor. */
const TO_CANONICAL = {
  kg: [1, 'kg'],
  g: [0.001, 'kg'],
  gr: [0.001, 'kg'],
  l: [1, 'L'],
  ml: [0.001, 'L'],
  cl: [0.01, 'L'],
  ud: [1, 'ud'],
  uds: [1, 'ud'],
  u: [1, 'ud'],
  pieza: [1, 'ud'],
}

/**
 * Lo que costaría la unidad con el tamaño que hay escrito, calculado sobre la
 * última compra. Es la comprobación que convierte un campo mal entendido en un
 * error visible antes de guardar.
 */
function SizePreview({ soldBy, packageSize, packageUnit, lastPricePerPiece }) {
  if (soldBy === 'VARIABLE_PIECE' || packageSize === '' || !packageUnit) return null

  const size = Number(String(packageSize).replace(',', '.'))
  const conversion = TO_CANONICAL[packageUnit.trim().toLowerCase()]

  if (!conversion) {
    return (
      <p className="unithint warn small">
        «{packageUnit}» no es una unidad que el comparador sepa convertir. Se admiten kg, g, L,
        ml, cl y ud.
      </p>
    )
  }
  if (!Number.isFinite(size) || size <= 0) return null
  if (lastPricePerPiece == null) return null

  const [factor, canonical] = conversion
  const unitPrice = Number(lastPricePerPiece) / (size * factor)

  return (
    <p className="unithint muted small">
      Con este tamaño, la última compra ({fmt(lastPricePerPiece)} € por envase) queda en{' '}
      <strong>
        {fmt(unitPrice)} €/{canonical}
      </strong>
      . Si ese número no es el que esperas, lo que está mal es el tamaño: aquí va lo que{' '}
      <em>contiene</em> el envase, no lo que cuesta.
    </p>
  )
}

function ProductForm({ product, purchaseCount, lastPricePerPiece, onCancel, onSaved }) {
  const [soldBy, setSoldBy] = useState(product.soldBy ?? 'PACKAGE')
  const [displayName, setDisplayName] = useState(product.displayName ?? '')
  const [packageSize, setPackageSize] = useState(
    product.packageSize == null ? '' : String(product.packageSize).replace('.', ','),
  )
  const [packageUnit, setPackageUnit] = useState(product.packageUnit ?? '')
  const [notes, setNotes] = useState(product.notes ?? '')
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)

  async function save() {
    setBusy(true)
    setError(null)
    try {
      onSaved(
        await api.updateProduct(product.id, {
          displayName: displayName || null,
          notes: notes || null,
          packageSize: packageSize === '' ? null : Number(packageSize.replace(',', '.')),
          packageUnit: packageUnit || null,
          soldBy,
        }),
      )
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="newproduct">
      <label>
        Nombre mostrado
        <input
          value={displayName}
          onChange={(e) => setDisplayName(e.target.value)}
          placeholder={product.canonicalName}
        />
      </label>
      <label>
        Cómo se vende
        <select value={soldBy} onChange={(e) => setSoldBy(e.target.value)}>
          {SOLD_BY.map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
      </label>
      <label>
        Tamaño del envase
        <input
          className="num"
          value={packageSize}
          onChange={(e) => setPackageSize(e.target.value)}
          placeholder="1"
          inputMode="decimal"
          disabled={soldBy === 'VARIABLE_PIECE'}
        />
      </label>
      <label>
        Unidad
        <input
          value={packageUnit}
          onChange={(e) => setPackageUnit(e.target.value)}
          placeholder="L"
          list="unidades-envase"
          disabled={soldBy === 'VARIABLE_PIECE'}
        />
        <datalist id="unidades-envase">
          {['kg', 'g', 'L', 'ml', 'cl', 'ud'].map((u) => (
            <option key={u} value={u} />
          ))}
        </datalist>
      </label>
      <label>
        Notas
        <input
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          placeholder="西柚 = pomelo"
        />
      </label>

      {/* Atajo, no valor por defecto. Hay productos donde "una unidad" es la
          verdad —una lechuga, un bote de lavavajillas— y teclearlo es fricción.
          Ponerlo solo, en cambio, haría que el comparador enfrentase precios por
          envase creyendo que son por litro. */}
      {/* La cuenta hecha delante, antes de guardar. El campo dice "tamaño" y se
          lee como "precio": alguien que escriba 15 kg pensando en 15 €/kg ve
          aquí que su tubo saldría a doce céntimos el kilo y se corrige solo. */}
      <SizePreview
        soldBy={soldBy}
        packageSize={packageSize}
        packageUnit={packageUnit}
        lastPricePerPiece={lastPricePerPiece}
      />

      {/* Una pieza de peso variable no tiene envase que teclear: lo que falta es
          el peso de cada compra, y ese va en la línea del ticket, no aquí. */}
      {soldBy === 'VARIABLE_PIECE' && (
        <p className="unithint muted small">
          Las piezas de peso variable no se normalizan desde aquí: cada compra pesa distinto. Para
          que entren en la serie, abre el ticket, pon la línea como «a peso», escribe el peso de
          esa pieza y vuelve a confirmar.
        </p>
      )}

      {packageSize === '' && soldBy !== 'VARIABLE_PIECE' && (
        <p className="unithint">
          <button
            className="link"
            onClick={() => {
              setPackageSize('1')
              setPackageUnit('ud')
            }}
          >
            Es una unidad
          </button>
          <span className="muted small">
            {' '}
            — para lo que se compra de uno en uno y no se mide. Si el producto viene en litros o
            en gramos, escribe el tamaño de verdad: comparar briks de tamaños distintos como si
            fueran «uno» da el más barato al revés.
          </span>
        </p>
      )}
      <p className="muted small">
        El envase va con número y unidad: «1» y «L» para un brik de litro, «400» y «g» para un
        bote de 400 gramos. Al guardarlo,{' '}
        {purchaseCount === 1
          ? 'la compra ya registrada se recalcula y entra en la serie'
          : `las ${purchaseCount} compras ya registradas se recalculan y entran en la serie`}
        . El nombre del ticket no se toca: es con lo que empareja el emparejador.
      </p>
      {error && <p className="error">{error}</p>}
      <div className="pickeractions">
        <button className="primary" onClick={save} disabled={busy}>
          {busy ? 'Guardando…' : 'Guardar'}
        </button>
        <button className="link" onClick={onCancel} disabled={busy}>
          Cancelar
        </button>
      </div>
    </div>
  )
}

/**
 * Gráfica en SVG plano: no merece una dependencia para una serie de una línea.
 * Anotada con el mínimo, el máximo y las fechas de los extremos, que es lo que
 * antes había que ir a buscar a la tabla.
 */
function PriceChart({ points, unit }) {
  const usable = points.filter((p) => p.normalizedUnitPrice != null)
  if (usable.length < 2) return null

  const W = 640
  const H = 220
  const PAD_L = 46
  const PAD_R = 14
  const PAD_T = 18
  const PAD_B = 30

  const values = usable.map((p) => Number(p.normalizedUnitPrice))
  const min = Math.min(...values)
  const max = Math.max(...values)
  const span = max - min || max || 1

  const x = (i) => PAD_L + (i * (W - PAD_L - PAD_R)) / (usable.length - 1)
  const y = (v) => H - PAD_B - ((v - min) / span) * (H - PAD_T - PAD_B)

  const path = usable.map((p, i) => `${i === 0 ? 'M' : 'L'} ${x(i)} ${y(values[i])}`).join(' ')
  const minIndex = values.indexOf(min)
  const maxIndex = values.indexOf(max)

  return (
    <figure className="chartbox">
      <figcaption>
        Evolución del precio normalizado
        <span className="muted small">
          {' '}
          · las ofertas se pintan huecas: están en lo que pagaste, no en el precio de estantería
        </span>
      </figcaption>
      <svg className="chart" viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Evolución del precio">
        {/* Solo dos guías, la del mínimo y la del máximo: son las dos cifras que
            se leen de una gráfica de precios. */}
        <line x1={PAD_L} y1={y(max)} x2={W - PAD_R} y2={y(max)} className="grid" />
        <line x1={PAD_L} y1={y(min)} x2={W - PAD_R} y2={y(min)} className="grid" />
        <text x={PAD_L - 6} y={y(max) + 4} className="axis" textAnchor="end">
          {fmt(max)}
        </text>
        <text x={PAD_L - 6} y={y(min) + 4} className="axis" textAnchor="end">
          {fmt(min)}
        </text>
        <text x={W - PAD_R} y={12} className="axis" textAnchor="end">
          €/{unit}
        </text>

        <path d={path} fill="none" className="line" />

        {usable.map((p, i) => (
          <circle
            key={i}
            cx={x(i)}
            cy={y(values[i])}
            r="3.5"
            className={p.promo ? 'dot promo' : 'dot'}
          >
            <title>{`${p.date}: ${values[i].toFixed(4)} €/${unit}${p.promo ? ' (oferta)' : ''}`}</title>
          </circle>
        ))}

        {/* Fechas solo en los extremos: rotular las doce las vuelve ilegibles. */}
        <text x={PAD_L} y={H - 8} className="axis">
          {shortDate(usable[0].date)}
        </text>
        <text x={W - PAD_R} y={H - 8} className="axis" textAnchor="end">
          {shortDate(usable[usable.length - 1].date)}
        </text>
        <text x={x(minIndex)} y={y(min) + 18} className="axis" textAnchor="middle">
          mín
        </text>
        <text x={x(maxIndex)} y={y(max) - 8} className="axis" textAnchor="middle">
          máx
        </text>
      </svg>
    </figure>
  )
}

function Metric({ label, value, hint }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>
        {value}
        {hint && <span className="muted small"> {hint}</span>}
      </dd>
    </div>
  )
}

/** Variación porcentual entre el último punto y el que está n posiciones atrás. */
function variation(normalized, n) {
  if (normalized.length < 2 || n < 2) return null
  const last = Number(normalized[normalized.length - 1].normalizedUnitPrice)
  const before = Number(normalized[Math.max(0, normalized.length - n)].normalizedUnitPrice)
  if (!before) return null
  return ((last - before) / before) * 100
}

/** Eje de la gráfica: año a dos cifras, que ahí el espacio es el que es. */
function shortDate(iso) {
  const [y, m, d] = String(iso).split('-')
  return d ? `${d}/${m}/${y.slice(2)}` : iso
}

/** Las fechas se leen en español, no en ISO. */
function fmtDate(iso) {
  const [y, m, d] = String(iso ?? '').split('-')
  return d ? `${d}/${m}/${y}` : (iso ?? '—')
}

function ageLabel(days) {
  if (days == null) return '—'
  if (days === 0) return 'hoy'
  if (days === 1) return 'ayer'
  if (days < 30) return `hace ${days} días`
  const months = Math.round(days / 30)
  return `hace ${months} ${months === 1 ? 'mes' : 'meses'}`
}

function comparator(order) {
  switch (order) {
    case 'subida':
      // Sin variación conocida al final: un producto de una sola compra no puede
      // encabezar la lista de "lo que más ha subido".
      return (a, b) => (b.changePct ?? -Infinity) - (a.changePct ?? -Infinity)
    case 'caro':
      return (a, b) => (b.lastNormalizedUnitPrice ?? -Infinity) - (a.lastNormalizedUnitPrice ?? -Infinity)
    case 'reciente':
      return (a, b) => (a.daysSinceLast ?? Infinity) - (b.daysSinceLast ?? Infinity)
    case 'nombre':
      return (a, b) => a.name.localeCompare(b.name, 'es')
    default:
      return (a, b) => b.purchaseCount - a.purchaseCount || a.name.localeCompare(b.name, 'es')
  }
}

function fmtPlain(value) {
  return value == null ? '—' : String(value).replace('.', ',')
}

/** Un decimal en los porcentajes: dos son precisión que el dato no tiene. */
function fmtPct(value) {
  return value == null ? '—' : Number(value).toFixed(1).replace('.', ',')
}

function fmt(value) {
  return value == null ? '—' : Number(value).toFixed(2).replace('.', ',')
}
