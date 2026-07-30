import React, { useEffect, useState } from 'react'
import { api } from './api.js'

export default function HistoryView() {
  const [products, setProducts] = useState([])
  const [openId, setOpenId] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    api.products().then(setProducts).catch((e) => setError(e.message))
  }, [])

  if (error) return <p className="error">{error}</p>
  if (openId) return <ProductHistory id={openId} onClose={() => setOpenId(null)} />

  return (
    <section className="card">
      <h2>Productos</h2>
      {products.length === 0 && (
        <p className="muted">
          Aún no hay productos. Salen de validar tickets: cada línea confirmada crea o reutiliza uno.
        </p>
      )}
      <table>
        <thead>
          <tr>
            <th>Producto</th>
            <th>Súper</th>
            <th>Compras</th>
            <th>Último precio</th>
            <th>Antigüedad</th>
            <th>Subidas</th>
          </tr>
        </thead>
        <tbody>
          {products.map((p) => (
            <tr key={p.id}>
              <td>
                <button className="link" onClick={() => setOpenId(p.id)}>
                  {p.name}
                </button>
              </td>
              <td className="muted">{p.storeCode}</td>
              <td>{p.purchaseCount}</td>
              <td>
                {p.comparable && p.lastNormalizedUnitPrice != null ? (
                  `${fmt(p.lastNormalizedUnitPrice)} €/${p.normalizedUnit}`
                ) : (
                  <span className="muted">no comparable</span>
                )}
              </td>
              <td className="muted">{ageLabel(p.daysSinceLast)}</td>
              <td>{p.comparable ? p.increaseCount : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}

function ProductHistory({ id, onClose }) {
  const [history, setHistory] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    api.productHistory(id).then(setHistory).catch((e) => setError(e.message))
  }, [id])

  if (error) return <p className="error">{error}</p>
  if (!history) return <p className="muted">Cargando…</p>

  const { product, points, metrics, notComparableReason } = history

  return (
    <section className="card">
      <button className="link" onClick={onClose}>
        ← Volver a productos
      </button>
      <h2>{product.displayName || product.canonicalName}</h2>
      <p className="muted">
        {product.storeName} · {product.canonicalName}
        {product.packageSize && ` · ${product.packageSize} ${product.packageUnit}`}
      </p>
      {product.notes && <p className="notes">{product.notes}</p>}

      {/* Decir por qué no hay serie vale más que dejar el hueco: casi siempre es
          que falta teclear el tamaño del envase una vez. */}
      {notComparableReason && <p className="warn strong">{notComparableReason}</p>}

      <dl className="metrics">
        <Metric label="Compras" value={metrics.purchaseCount} />
        <Metric
          label="Último precio"
          value={
            metrics.lastNormalizedUnitPrice != null
              ? `${fmt(metrics.lastNormalizedUnitPrice)} €/${metrics.normalizedUnit}`
              : '—'
          }
          hint={ageLabel(metrics.daysSinceLast)}
        />
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
            metrics.excludedPoints ? `, ${metrics.excludedPoints} excluidos` : ''
          }`}
        />
        <Metric label="Bajadas" value={metrics.decreaseCount} />
        <Metric
          label="Volatilidad"
          value={metrics.volatility != null ? `${(metrics.volatility * 100).toFixed(1)} %` : '—'}
        />
        <Metric label="Gasto total" value={`${fmt(metrics.totalSpent)} €`} />
      </dl>

      <PriceChart points={points} unit={metrics.normalizedUnit} />

      <table>
        <thead>
          <tr>
            <th>Fecha</th>
            <th>€/pieza</th>
            <th>Normalizado</th>
            <th>Cuenta</th>
            <th>Ticket</th>
          </tr>
        </thead>
        <tbody>
          {[...points].reverse().map((p, i) => (
            <tr key={i}>
              <td>{p.date}</td>
              <td>{fmt(p.pricePerPiece)}</td>
              <td>
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

/** Gráfica en SVG plano: no merece una dependencia para una serie de una línea. */
function PriceChart({ points, unit }) {
  const usable = points.filter((p) => p.normalizedUnitPrice != null)
  if (usable.length < 2) return null

  const W = 640
  const H = 200
  const PAD = 34

  const values = usable.map((p) => Number(p.normalizedUnitPrice))
  const min = Math.min(...values)
  const max = Math.max(...values)
  const span = max - min || max || 1

  const x = (i) => PAD + (i * (W - 2 * PAD)) / (usable.length - 1)
  const y = (v) => H - PAD - ((v - min) / span) * (H - 2 * PAD)

  const path = usable.map((p, i) => `${i === 0 ? 'M' : 'L'} ${x(i)} ${y(values[i])}`).join(' ')

  return (
    <svg className="chart" viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Evolución del precio">
      <line x1={PAD} y1={H - PAD} x2={W - PAD} y2={H - PAD} stroke="#ddd" />
      <line x1={PAD} y1={PAD} x2={PAD} y2={H - PAD} stroke="#ddd" />
      <text x={4} y={PAD + 4} className="axis">
        {max.toFixed(2)}
      </text>
      <text x={4} y={H - PAD} className="axis">
        {min.toFixed(2)}
      </text>
      <text x={W - PAD} y={14} className="axis" textAnchor="end">
        €/{unit}
      </text>
      <path d={path} fill="none" stroke="#1f6f4a" strokeWidth="2" />
      {usable.map((p, i) => (
        <circle
          key={i}
          cx={x(i)}
          cy={y(values[i])}
          r="3.5"
          // Las ofertas se pintan huecas: están en la serie de lo pagado, pero
          // no en la de precio de estantería.
          fill={p.promo ? '#fff' : '#1f6f4a'}
          stroke="#1f6f4a"
        >
          <title>{`${p.date}: ${values[i].toFixed(4)} €/${unit}${p.promo ? ' (oferta)' : ''}`}</title>
        </circle>
      ))}
    </svg>
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

function ageLabel(days) {
  if (days == null) return '—'
  if (days === 0) return 'hoy'
  if (days === 1) return 'ayer'
  if (days < 30) return `hace ${days} días`
  const months = Math.round(days / 30)
  return `hace ${months} ${months === 1 ? 'mes' : 'meses'}`
}

function fmt(value) {
  return value == null ? '—' : Number(value).toFixed(2).replace('.', ',')
}
