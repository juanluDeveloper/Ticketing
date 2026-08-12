import React, { useEffect, useMemo, useRef, useState } from 'react'
import { api } from './api.js'

const SOLD_BY = [
  ['PACKAGE', 'envase'],
  ['WEIGHT', 'a peso'],
  ['VARIABLE_PIECE', 'pieza variable'],
]

export default function ValidationView({ ticketId, onClose, onOpenTicket }) {
  const [detail, setDetail] = useState(null)
  const [drafts, setDrafts] = useState({})
  const [error, setError] = useState(null)
  const [busy, setBusy] = useState(false)
  const [imageUrl, setImageUrl] = useState(null)
  const [showImage, setShowImage] = useState(true)

  const extracting = detail?.summary.status === 'EXTRACTING' || detail?.summary.status === 'UPLOADED'
  const referencedTicketId = detail?.summary.extractionError
    ?.match(/ticket\s+#(\d+)/i)?.[1]

  useEffect(() => {
    let cancelled = false

    async function load() {
      try {
        const data = await api.getTicket(ticketId)
        if (!cancelled) setDetail(data)
      } catch (e) {
        if (!cancelled) setError(e.message)
      }
    }

    load()
    const timer = setInterval(() => {
      // Solo se sondea mientras la GPU tiene trabajo; una vez extraído, la
      // pantalla es de edición y recargarla pisaría lo que se está escribiendo.
      setDetail((current) => {
        const status = current?.summary.status
        if (status === 'EXTRACTING' || status === 'UPLOADED') load()
        return current
      })
    }, 3000)

    return () => {
      cancelled = true
      clearInterval(timer)
    }
  }, [ticketId])

  useEffect(() => {
    let url = null
    api
      .image(ticketId)
      .then((blob) => {
        url = URL.createObjectURL(blob)
        setImageUrl(url)
      })
      .catch(() => setImageUrl(null))
    return () => url && URL.revokeObjectURL(url)
  }, [ticketId])

  function patch(lineId, field, value) {
    setDrafts((d) => ({ ...d, [lineId]: { ...d[lineId], [field]: value } }))
  }

  function valueOf(line, field) {
    const draft = drafts[line.id]
    return draft && field in draft ? draft[field] : line[field]
  }

  const unassigned = (detail?.lines ?? []).filter((l) => !l.product)

  /**
   * Crea de golpe un producto por cada línea sin asignar, con el nombre del
   * ticket y el tamaño que el backend haya deducido de la descripción.
   *
   * <p>El primer ticket de un súper trae decenas de líneas y ninguna tiene a
   * qué engancharse todavía: hacerlo una a una con el selector es inviable. El
   * trabajo es de una vez, no recurrente — cada confirmación enseña un alias al
   * matcher, así que el siguiente ticket del mismo súper ya llega emparejado.
   */
  async function createMissingProducts() {
    setBusy(true)
    setError(null)
    try {
      const updated = await api.validate(ticketId, {
        purchasedAt: detail.summary.purchasedAt,
        receiptNumber: detail.summary.receiptNumber,
        total: detail.summary.total,
        articleCount: detail.summary.articleCount,
        confirm: false,
        lines: unassigned.map((l) => ({
          lineItemId: l.id,
          product: {
            newProduct: {
              canonicalName: l.rawDescription,
              packageSize: l.sizeSuggestion?.value ?? null,
              packageUnit: l.sizeSuggestion?.unit ?? null,
              soldBy: l.soldBy,
            },
          },
        })),
      })
      setDetail(updated)
      setDrafts({})
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  async function save(confirm) {
    setBusy(true)
    setError(null)
    try {
      const payload = {
        purchasedAt: detail.summary.purchasedAt,
        receiptNumber: detail.summary.receiptNumber,
        total: detail.summary.total,
        articleCount: detail.summary.articleCount,
        confirm,
        lines: Object.entries(drafts).map(([lineItemId, changes]) => ({
          lineItemId: Number(lineItemId),
          ...changes,
        })),
      }
      const updated = await api.validate(ticketId, payload)
      setDetail(updated)
      setDrafts({})
      if (confirm && updated.summary.status === 'VALIDATED') onClose()
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  if (error && !detail) return <ErrorBox message={error} onClose={onClose} />
  if (!detail) return <p className="muted">Cargando…</p>

  return (
    <section className="validation">
      <div className="toolbar">
        <button className="link" onClick={onClose}>
          ← Volver
        </button>
        <strong>Ticket #{detail.summary.id} · {detail.summary.storeName ?? 'súper sin identificar'}</strong>
        <span className="muted">
          {detail.summary.purchasedAt?.replace('T', ' ').slice(0, 16) ?? 'sin fecha'} ·{' '}
          {detail.summary.total != null ? `${fmt(detail.summary.total)} €` : 'sin total'} ·{' '}
          {detail.summary.lineCount} líneas
        </span>
        <button className="link" onClick={() => setShowImage((v) => !v)}>
          {showImage ? 'Ocultar foto' : 'Ver foto'}
        </button>
        {/* Borrar libera el número de recibo y permite volver a subir la compra
            con una foto mejor, que es justo lo que hace falta cuando la
            extracción sale mal. */}
        <button
          className="link danger"
          disabled={extracting}
          onClick={async () => {
            if (!window.confirm('¿Borrar este ticket y todo lo extraído de él?')) return
            try {
              await api.deleteTicket(ticketId)
              onClose()
            } catch (e) {
              if (e.message.includes('histórico') &&
                  window.confirm(`${e.message}\n\n¿Borrarlo de todas formas?`)) {
                await api.deleteTicket(ticketId, true)
                onClose()
                return
              }
              setError(e.message)
            }
          }}
        >
          Borrar ticket
        </button>
      </div>

      {extracting && (
        <p className="warn">
          Extrayendo con el modelo. Puede tardar unos minutos; esta pantalla se actualiza sola.
        </p>
      )}
      {detail.summary.status === 'EXTRACTION_ERROR' && (
        <div className="error">
          <p>{detail.summary.extractionError}</p>
          {referencedTicketId && (
            <button onClick={() => onOpenTicket?.(Number(referencedTicketId))}>
              Abrir ticket #{referencedTicketId}
            </button>
          )}{' '}
          <button onClick={() => api.reextract(ticketId).then(() => setDetail(null))}>
            Reintentar extracción
          </button>
        </div>
      )}

      <Checks checks={detail.checks} />

      {detail.coverageWarning && <p className="warn strong">{detail.coverageWarning}</p>}

      {detail.ticketIssues.length > 0 && (
        <ul className="issues">
          {detail.ticketIssues.map((i) => (
            <li key={i.id} className={i.severity.toLowerCase()}>
              <code>{i.code}</code> {i.message}
            </li>
          ))}
        </ul>
      )}

      <div className={showImage ? 'split' : ''}>
        <div className="lines">
          <table>
            <thead>
              <tr>
                <th>#</th>
                <th>Descripción</th>
                <th>Cant.</th>
                <th>P. unit</th>
                <th>Importe</th>
                <th>IVA</th>
                <th>Venta</th>
                <th>Producto</th>
              </tr>
            </thead>
            <tbody>
              {detail.lines.map((line) => (
                <LineRow
                  key={line.id}
                  line={line}
                  storeId={null}
                  storeCode={detail.summary.storeCode}
                  edited={Boolean(drafts[line.id])}
                  valueOf={valueOf}
                  patch={patch}
                />
              ))}
            </tbody>
          </table>
        </div>

        {showImage && imageUrl && (
          <div className="photo">
            <img src={imageUrl} alt="Ticket" />
          </div>
        )}
      </div>

      {error && <p className="error">{error}</p>}

      <div className="actions">
        {unassigned.length > 0 && (
          <button onClick={createMissingProducts} disabled={busy}>
            Crear producto para las {unassigned.length} líneas sin asignar
          </button>
        )}
        <button onClick={() => save(false)} disabled={busy}>
          Guardar correcciones
        </button>
        <button className="primary" onClick={() => save(true)} disabled={busy}>
          Confirmar ticket
        </button>
        <span className="muted">
          Confirmar genera la serie de precios. Exige que no queden comprobaciones en rojo y que
          todas las líneas tengan producto.
        </span>
      </div>
    </section>
  )
}

/**
 * Tres estados a propósito: verde pasa, rojo falla, gris no aplica a este
 * formato. Nunca verde por omisión — una comprobación que no ha podido correr no
 * es una comprobación superada.
 */
function Checks({ checks }) {
  return (
    <ul className="checks">
      {checks.map((c) => {
        const state = !c.applicable ? 'na' : c.passed ? 'ok' : 'fail'
        const label = !c.applicable ? 'no aplica' : c.passed ? 'pasa' : 'falla'
        return (
          <li key={c.code} className={state} title={c.detail ?? ''}>
            <strong>{c.code}</strong>
            <span className="what">{c.description}</span>
            <span className="state">{label}</span>
            {c.detail && <span className="detail">{c.detail}</span>}
          </li>
        )
      })}
    </ul>
  )
}

function LineRow({ line, storeCode, edited, valueOf, patch }) {
  const [picking, setPicking] = useState(false)
  const worst = line.issues.some((i) => i.severity === 'ERROR')
    ? 'error'
    : line.issues.length > 0
      ? 'warn'
      : ''

  return (
    <>
      <tr className={`${worst} ${edited ? 'edited' : ''}`}>
        <td>{line.lineNo}</td>
        <td>
          <input
            value={valueOf(line, 'rawDescription') ?? ''}
            onChange={(e) => patch(line.id, 'rawDescription', e.target.value)}
          />
        </td>
        <td>
          <input
            className="num"
            value={valueOf(line, 'quantity') ?? ''}
            onChange={(e) => patch(line.id, 'quantity', e.target.value)}
          />
        </td>
        <td>
          <input
            className="num"
            value={valueOf(line, 'printedUnitPrice') ?? ''}
            onChange={(e) => patch(line.id, 'printedUnitPrice', e.target.value)}
          />
          {/*
            La misma columna significa cosas distintas según cómo se venda el
            producto: EUR/unidad en un envase, EUR/kg en uno a peso. Sin la
            unidad a la vista, un 31,95 en un queso de 260 g parece el precio de
            la pieza y no el del kilo.
          */}
          {line.printedUnitPriceUnit && line.printedUnitPriceUnit !== 'ud' && (
            <span className="muted small"> €/{line.printedUnitPriceUnit}</span>
          )}
        </td>
        <td>
          <input
            className="num"
            value={valueOf(line, 'lineTotal') ?? ''}
            onChange={(e) => patch(line.id, 'lineTotal', e.target.value)}
          />
        </td>
        <td>
          <input
            className="letter"
            maxLength={1}
            value={valueOf(line, 'taxLetter') ?? ''}
            onChange={(e) => patch(line.id, 'taxLetter', e.target.value.toUpperCase())}
          />
        </td>
        <td>
          <select
            value={valueOf(line, 'soldBy') ?? 'PACKAGE'}
            onChange={(e) => patch(line.id, 'soldBy', e.target.value)}
          >
            {SOLD_BY.map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </td>
        <td>
          <button className="link" onClick={() => setPicking((v) => !v)}>
            {line.product
              ? line.product.displayName || line.product.canonicalName
              : '— sin asignar —'}
          </button>
          {/* El extractor genera líneas que no existen en el papel: la sub-línea
              de peso salió como ítem propio y rompía el cuadre del total. */}
          <button
            className="link danger"
            title="Borrar esta línea"
            onClick={() => patch(line.id, 'delete', true)}
          >
            {' '}
            ✕
          </button>
          {line.matchMethod && (
            <span className="muted small">
              {' '}
              {line.matchMethod === 'EXACT_ALIAS' ? 'alias exacto' : line.matchMethod.toLowerCase()}
            </span>
          )}
        </td>
      </tr>

      {/*
        Las líneas a peso necesitan su propio hueco: el precio unitario es €/kg,
        así que sin el peso ni cuadra la aritmética ni se puede calcular el
        precio comparable. El modelo marca "a peso" y a veces se deja el peso,
        y sin esta fila no había forma de arreglarlo desde la pantalla.
      */}
      {valueOf(line, 'soldBy') === 'WEIGHT' && (
        <tr className={`weightrow ${line.weightValue == null ? 'missing' : ''}`}>
          <td />
          <td colSpan={7}>
            <label>
              Peso{' '}
              <input
                className="num"
                // Sin texto de ejemplo dentro del campo: un número en gris ahí
                // se lee como un valor puesto y no como un hueco vacío.
                placeholder=""
                value={valueOf(line, 'weightValue') ?? ''}
                onChange={(e) => patch(line.id, 'weightValue', e.target.value)}
              />
            </label>{' '}
            <label>
              Unidad{' '}
              <input
                className="letter"
                placeholder="kg"
                value={valueOf(line, 'weightUnit') ?? 'kg'}
                onChange={(e) => patch(line.id, 'weightUnit', e.target.value)}
              />
            </label>
            {line.weightValue == null ? (
              <span className="muted small">
                {' '}
                · sin peso no hay precio por kilo con el que comparar
              </span>
            ) : (
              // La cuenta que cuadra en una línea a peso es peso x precio por
              // kilo, no cantidad x precio: enseñarla evita tener que deducirla.
              <span className="muted small">
                {' '}
                · {fmtPlain(line.weightValue)} × {fmtPlain(line.printedUnitPrice)} ={' '}
                {fmtPlain(line.lineTotal)} €
              </span>
            )}
          </td>
        </tr>
      )}

      {/* La fila transcrita literal, para cotejarla con la foto sin bizquear. */}
      {line.rawRowText && (
        <tr className="rawrow">
          <td />
          <td colSpan={7}>
            <code>{line.rawRowText}</code>
          </td>
        </tr>
      )}

      {line.issues.map((i) => (
        <tr key={i.id} className={`issuerow ${i.severity.toLowerCase()}`}>
          <td />
          <td colSpan={7}>
            <code>{i.code}</code> {i.message}
          </td>
        </tr>
      ))}

      {picking && (
        <tr className="pickerrow">
          <td />
          <td colSpan={7}>
            <ProductPicker
              line={line}
              storeCode={storeCode}
              onChoose={(decision) => {
                patch(line.id, 'product', decision)
                setPicking(false)
              }}
            />
          </td>
        </tr>
      )}
    </>
  )
}

function ProductPicker({ line, storeCode, onChoose }) {
  const [stores, setStores] = useState([])
  const [query, setQuery] = useState(line.rawDescription ?? '')
  const [results, setResults] = useState([])
  const [creating, setCreating] = useState(false)

  const storeId = useMemo(
    () => stores.find((s) => s.code === storeCode)?.id ?? null,
    [stores, storeCode],
  )

  useEffect(() => {
    api.stores().then(setStores).catch(() => setStores([]))
  }, [])

  useEffect(() => {
    if (!storeId) return
    let cancelled = false
    api
      .searchProducts(storeId, query)
      .then((r) => !cancelled && setResults(r))
      .catch(() => !cancelled && setResults([]))
    return () => {
      cancelled = true
    }
  }, [storeId, query])

  if (creating) {
    return (
      <NewProductForm
        line={line}
        onCancel={() => setCreating(false)}
        onCreate={(newProduct) => onChoose({ newProduct })}
      />
    )
  }

  return (
    <div className="picker">
      <input
        placeholder="Buscar producto de este súper…"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
      />
      <ul>
        {results.slice(0, 8).map((p) => (
          <li key={p.id}>
            <button className="link" onClick={() => onChoose({ existingStoreProductId: p.id })}>
              {p.displayName || p.canonicalName}
              {p.packageSize && (
                <span className="muted">
                  {' '}
                  · {p.packageSize} {p.packageUnit}
                </span>
              )}
            </button>
          </li>
        ))}
        {results.length === 0 && <li className="muted">Ningún producto parecido.</li>}
      </ul>
      <div className="pickeractions">
        <button onClick={() => setCreating(true)}>Crear producto nuevo</button>
        <button className="link" onClick={() => onChoose({ unassign: true })}>
          Quitar asignación
        </button>
      </div>
    </div>
  )
}

function NewProductForm({ line, onCancel, onCreate }) {
  // El tamaño lo propone el backend leyéndolo de la descripción ("330ML",
  // "1,10L"): es el dato del que depende todo el precio normalizado y así no
  // hay que teclearlo.
  const suggestion = line.sizeSuggestion
  const [form, setForm] = useState({
    canonicalName: line.rawDescription ?? '',
    displayName: '',
    notes: '',
    brand: '',
    packageSize: suggestion?.value ?? '',
    packageUnit: suggestion?.unit ?? '',
    categoryId: '',
    soldBy: line.soldBy ?? 'PACKAGE',
  })
  const [categories, setCategories] = useState([])

  useEffect(() => {
    api.categories().then(setCategories).catch(() => setCategories([]))
  }, [])

  function field(name, label, extra = {}) {
    return (
      <label>
        {label}
        <input
          value={form[name]}
          onChange={(e) => setForm({ ...form, [name]: e.target.value })}
          {...extra}
        />
      </label>
    )
  }

  return (
    <div className="newproduct">
      {field('canonicalName', 'Nombre en el ticket')}
      {field('displayName', 'Nombre legible')}
      {field('brand', 'Marca')}
      {field('packageSize', 'Tamaño')}
      {field('packageUnit', 'Unidad (g, kg, ml, L, ud)')}
      <label>
        Notas
        <input value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} />
      </label>
      <label>
        Categoría
        <select
          value={form.categoryId}
          onChange={(e) => setForm({ ...form, categoryId: e.target.value })}
        >
          <option value="">— sin categoría —</option>
          {categories.map((c) => (
            <option key={c.id} value={c.id}>
              {c.parentId ? '· ' : ''}
              {c.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        Venta
        <select value={form.soldBy} onChange={(e) => setForm({ ...form, soldBy: e.target.value })}>
          {SOLD_BY.map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
      </label>
      {suggestion && (
        <p className="muted small">
          Tamaño deducido de la descripción: {suggestion.value} {suggestion.unit}. Corrígelo si no
          es eso.
        </p>
      )}
      <div className="pickeractions">
        <button
          onClick={() =>
            onCreate({
              ...form,
              packageSize: form.packageSize === '' ? null : form.packageSize,
              packageUnit: form.packageUnit === '' ? null : form.packageUnit,
              categoryId: form.categoryId === '' ? null : Number(form.categoryId),
              displayName: form.displayName || null,
              notes: form.notes || null,
              brand: form.brand || null,
            })
          }
        >
          Crear y asignar
        </button>
        <button className="link" onClick={onCancel}>
          Cancelar
        </button>
      </div>
    </div>
  )
}

function ErrorBox({ message, onClose }) {
  return (
    <div className="card">
      <p className="error">{message}</p>
      <button onClick={onClose}>Volver</button>
    </div>
  )
}

/** Sin decimales forzados: un peso de 0,26 kg no debe salir como "0,26" fijo. */
function fmtPlain(value) {
  if (value == null) return '—'
  return String(Number(value)).replace('.', ',')
}

function fmt(value) {
  return Number(value).toFixed(2).replace('.', ',')
}
