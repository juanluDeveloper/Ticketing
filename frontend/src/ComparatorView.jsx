import React, { useEffect, useMemo, useState } from 'react'
import { api } from './api.js'

const DIMENSIONS = [
  ['WEIGHT', 'peso (€/kg)'],
  ['VOLUME', 'volumen (€/L)'],
  ['UNIT', 'unidades (€/ud)'],
]

export default function ComparatorView() {
  const [groups, setGroups] = useState(null)
  const [openId, setOpenId] = useState(null)
  const [creating, setCreating] = useState(false)
  const [query, setQuery] = useState('')
  const [error, setError] = useState(null)

  const reload = () => api.groups().then(setGroups).catch((e) => setError(e.message))

  useEffect(() => {
    reload()
  }, [])

  const shown = useMemo(() => {
    if (!groups) return []
    const needle = query.trim().toLowerCase()
    return needle ? groups.filter((g) => g.name.toLowerCase().includes(needle)) : groups
  }, [groups, query])

  if (openId) {
    return (
      <GroupDetail
        id={openId}
        onClose={() => {
          setOpenId(null)
          reload()
        }}
      />
    )
  }

  return (
    <section>
      <div className="pagehead">
        <h2>Comparador</h2>
        <p className="muted">
          Un grupo junta el mismo artículo en varios súper. Se compara por precio normalizado, así
          que la unidad la fija la dimensión y todos sus miembros tienen que compartirla.
        </p>
      </div>

      {error && <p className="error">{error}</p>}
      {!groups && <p className="muted">Cargando…</p>}

      {groups && groups.length === 0 && !creating && (
        <div className="empty">
          <h3>Aún no hay grupos</h3>
          <p className="muted">
            Un grupo enfrenta el mismo artículo en varios súper en €/kg, €/L o €/ud. Necesita al
            menos dos productos con tamaño de envase conocido: sin tamaño no hay precio
            normalizado que comparar.
          </p>
        </div>
      )}

      {groups && groups.length > 0 && (
        <>
          {groups.length > 6 && (
            <div className="filters">
              <input
                className="search"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Buscar grupo"
                aria-label="Buscar grupo"
              />
              <span className="muted small">
                {shown.length === groups.length
                  ? `${groups.length} grupos`
                  : `${shown.length} de ${groups.length}`}
              </span>
            </div>
          )}

          <div className="groups">
            {shown.map((g) => (
              <GroupCard key={g.id} group={g} onOpen={() => setOpenId(g.id)} />
            ))}
          </div>
        </>
      )}

      {creating ? (
        <CreateGroupForm
          onCancel={() => setCreating(false)}
          onCreated={() => {
            setCreating(false)
            reload()
          }}
        />
      ) : (
        <div className="actions">
          <button onClick={() => setCreating(true)}>Crear grupo</button>
        </div>
      )}
    </section>
  )
}

/**
 * La conclusión del grupo va en grande y a la derecha: es a lo que se viene.
 * Antes iba comprimida en una celda de texto con el mismo peso que el recuento
 * de miembros.
 */
function GroupCard({ group: g, onOpen }) {
  const pending = g.memberCount - g.comparableCount

  return (
    <button type="button" className="groupcard" onClick={onOpen}>
      <span className="groupcard-main">
        <span className="groupcard-name">{g.name}</span>
        <span className="muted small">
          {g.memberCount} {g.memberCount === 1 ? 'miembro' : 'miembros'} · se compara en €/
          {g.comparisonUnit}
          {g.hasPreference && ' · con preferencia'}
        </span>
        {pending > 0 && (
          <span className="warn small">
            {pending} {pending === 1 ? 'miembro se queda' : 'miembros se quedan'} fuera de la
            comparación
          </span>
        )}
      </span>

      <span className="groupcard-price">
        {g.cheapestPrice != null ? (
          <>
            <span className="value">
              {fmt(g.cheapestPrice)}
              <span className="unit"> €/{g.comparisonUnit}</span>
            </span>
            <span className="muted small">más barato en {g.cheapestStore}</span>
          </>
        ) : (
          <>
            <span className="value faint">—</span>
            <span className="warn small">ningún miembro tiene precio comparable</span>
          </>
        )}
      </span>
    </button>
  )
}

function CreateGroupForm({ onCancel, onCreated }) {
  const [name, setName] = useState('')
  const [dimension, setDimension] = useState('VOLUME')
  const [categoryId, setCategoryId] = useState('')
  const [categories, setCategories] = useState([])
  const [error, setError] = useState(null)

  useEffect(() => {
    api.categories().then(setCategories).catch(() => setCategories([]))
  }, [])

  async function submit() {
    try {
      await api.createGroup({
        name,
        dimension,
        categoryId: categoryId === '' ? null : Number(categoryId),
      })
      onCreated()
    } catch (e) {
      setError(e.message)
    }
  }

  return (
    <div className="newproduct">
      <label>
        Nombre
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Leche entera 1 L" />
      </label>
      <label>
        Se compara por
        <select value={dimension} onChange={(e) => setDimension(e.target.value)}>
          {DIMENSIONS.map(([v, l]) => (
            <option key={v} value={v}>
              {l}
            </option>
          ))}
        </select>
      </label>
      <label>
        Categoría
        <select value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
          <option value="">— sin categoría —</option>
          {categories.map((c) => (
            <option key={c.id} value={c.id}>
              {c.parentId ? '· ' : ''}
              {c.name}
            </option>
          ))}
        </select>
      </label>
      {error && <p className="error">{error}</p>}
      <div className="pickeractions">
        <button onClick={submit} disabled={!name.trim()}>
          Crear
        </button>
        <button className="link" onClick={onCancel}>
          Cancelar
        </button>
      </div>
    </div>
  )
}

function GroupDetail({ id, onClose }) {
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [adding, setAdding] = useState(false)
  const [editingPreference, setEditingPreference] = useState(false)
  const [confirmingRemoval, setConfirmingRemoval] = useState(null)

  const load = () => api.groupComparison(id).then(setData).catch((e) => setError(e.message))

  useEffect(() => {
    load()
  }, [id])

  if (error) return <p className="error">{error}</p>
  if (!data) return <p className="muted">Cargando…</p>

  const { group, ranking, notComparable, verdict, dataWarning } = data
  const preferred = ranking.find((r) => r.preferred) ?? notComparable.find((r) => r.preferred)
  const cheapest = ranking.length > 0 ? Number(ranking[0].normalizedUnitPrice) : null

  return (
    <section>
      <button className="link" onClick={onClose}>
        ← Volver a grupos
      </button>

      <div className="pagehead">
        <h2>{group.name}</h2>
        <p className="muted">
          Se compara en €/{group.comparisonUnit}
          {group.categoryName && ` · ${group.categoryName}`} · {group.memberCount} miembros
        </p>
      </div>

      <Verdict verdict={verdict} unit={group.comparisonUnit} />

      {/* El aviso es sobre la calidad del dato, no sobre el precio: precios
          viejos o un solo súper con datos no invalidan el número, pero cambian
          cuánto te puedes fiar de él. */}
      {dataWarning && <p className="warn strong">{dataWarning}</p>}

      <h3>Ranking</h3>
      {ranking.length === 0 ? (
        <p className="muted">Ningún miembro tiene precio comparable todavía.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Súper</th>
              <th>Producto</th>
              <th className="right">Precio</th>
              {/* La columna que faltaba: sin ella hay que restar de cabeza para
                  saber si la diferencia compensa el viaje. */}
              <th className="right">Diferencia</th>
              <th className="right">Con prima</th>
              <th>Fecha del precio</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {ranking.map((r, i) => (
              <tr key={r.storeProductId} className={r.preferred ? 'preferred' : ''}>
                <td>
                  {i === 0 && <span className="badge validated">más barato</span>} {r.storeName}
                </td>
                <td>
                  {r.productName}{' '}
                  {r.preferred && <span className="badge extracted">preferido</span>}
                </td>
                <td className="right">
                  {fmt(r.normalizedUnitPrice)} €/{r.unit}{' '}
                  {/* Un precio tecleado y uno medido en un ticket no merecen la
                      misma confianza, y la diferencia tiene que verse en la
                      propia fila, no en una nota al pie. */}
                  {r.declared && <span className="badge extracted">declarado</span>}
                </td>
                <td className="right">
                  <Difference price={r.normalizedUnitPrice} cheapest={cheapest} unit={r.unit} />
                </td>
                <td className="right muted">
                  {r.adjustedPrice != null ? `${fmt(r.adjustedPrice)} €/${r.unit}` : '—'}
                </td>
                <td className="muted">
                  {fmtDate(r.observedAt)} · {ageLabel(r.ageDays)}
                  {r.declared && <span className="small"> (leído en el mostrador)</span>}
                </td>
                <td>
                  <RemoveButton
                    label={`${r.storeName} · ${r.productName}`}
                    confirming={confirmingRemoval === r.storeProductId}
                    onAsk={() => setConfirmingRemoval(r.storeProductId)}
                    onCancel={() => setConfirmingRemoval(null)}
                    onConfirm={() => remove(r.storeProductId)}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {/* Nunca se mezclan con el ranking ni se omiten: "no lo he comprado ahí" y
          "ahí es más caro" son cosas distintas. */}
      {notComparable.length > 0 && (
        <>
          <h3>Fuera de la comparación</h3>
          <ul className="outside">
            {notComparable.map((r) => (
              <li key={r.storeProductId}>
                <strong>{r.storeName}</strong> · {r.productName}
                {r.preferred && <span className="badge extracted"> preferido</span>}
                <div className="muted small">{r.notComparableReason}</div>
                <RemoveButton
                  label={`${r.storeName} · ${r.productName}`}
                  text="quitar del grupo"
                  confirming={confirmingRemoval === r.storeProductId}
                  onAsk={() => setConfirmingRemoval(r.storeProductId)}
                  onCancel={() => setConfirmingRemoval(null)}
                  onConfirm={() => remove(r.storeProductId)}
                />
              </li>
            ))}
          </ul>
        </>
      )}

      <div className="actions">
        <button onClick={() => setAdding((v) => !v)}>
          {adding ? 'Cerrar sugerencias' : 'Añadir producto'}
        </button>
        <button onClick={() => setEditingPreference((v) => !v)}>
          {verdict.preferenceApplied || preferred ? 'Cambiar preferencia' : 'Marcar preferido'}
        </button>
        {(verdict.preferenceApplied || preferred) && (
          <button className="link danger" onClick={clearPreference}>
            Quitar preferencia
          </button>
        )}
      </div>

      {adding && <Suggestions groupId={id} onAdded={setData} />}
      {editingPreference && (
        <PreferenceForm
          groupId={id}
          members={[...ranking, ...notComparable]}
          unit={group.comparisonUnit}
          onSaved={(d) => {
            setData(d)
            setEditingPreference(false)
          }}
        />
      )}
    </section>
  )

  async function remove(storeProductId) {
    setConfirmingRemoval(null)
    setData(await api.removeGroupMember(id, storeProductId))
  }

  async function clearPreference() {
    setData(await api.clearPreference(id))
  }
}

/**
 * Cuánto más caro que el más barato, en euros y en tanto por ciento. El
 * porcentaje solo no basta —un 20 % sobre 0,80 €/L es calderilla— y los euros
 * solos tampoco dicen si la diferencia es grande para ese producto.
 */
function Difference({ price, cheapest, unit }) {
  if (cheapest == null || price == null) return <span className="muted">—</span>
  const diff = Number(price) - cheapest
  if (diff <= 0) return <span className="muted">—</span>
  const pct = cheapest ? (diff / cheapest) * 100 : null
  return (
    <span className="change up">
      +{fmt(diff)} €/{unit}
      {pct != null && <span className="muted small"> · +{fmtPct(pct)} %</span>}
    </span>
  )
}

/**
 * Confirmación en la propia fila. Quitar un miembro tira un dato que costó
 * reunir, y un enlace suelto se pulsa sin querer; un diálogo del navegador, en
 * cambio, saca al usuario de la tabla que está mirando.
 */
function RemoveButton({ label, text = 'quitar', confirming, onAsk, onCancel, onConfirm }) {
  if (!confirming) {
    return (
      <button className="link" onClick={onAsk} title={`Quitar ${label} del grupo`}>
        {text}
      </button>
    )
  }
  return (
    <span className="confirminline">
      <span className="small">¿Seguro?</span>
      <button className="link danger" onClick={onConfirm}>
        quitar
      </button>
      <button className="link" onClick={onCancel}>
        cancelar
      </button>
    </span>
  )
}

/**
 * Siempre las dos cosas a la vez. Enseñar solo la recomendación haría invisible
 * la prima, y entonces dejaría de ser una decisión y pasaría a ser un sesgo.
 */
function Verdict({ verdict, unit }) {
  if (!verdict.cheapest) return <p className="warn strong">{verdict.explanation}</p>

  return (
    <div className="verdict">
      <div>
        <dt>Más barato</dt>
        <dd>
          {verdict.cheapest.storeName} · {fmt(verdict.cheapest.normalizedUnitPrice)} €/{unit}
        </dd>
      </div>
      <div>
        <dt>Tu elección</dt>
        <dd>
          {verdict.chosen.storeName} · {fmt(verdict.chosen.normalizedUnitPrice)} €/{unit}
        </dd>
      </div>
      <div>
        <dt>Te cuesta</dt>
        <dd>
          {verdict.preferenceCost != null && verdict.preferenceCost > 0
            ? `${fmt(verdict.preferenceCost)} €/${unit}${
                verdict.preferenceCostPct ? ` · ${fmtPct(verdict.preferenceCostPct)} %` : ''
              }`
            : '—'}
        </dd>
      </div>
      <p className="explanation">{verdict.explanation}</p>
    </div>
  )
}

function Suggestions({ groupId, onAdded }) {
  const [items, setItems] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    api.groupSuggestions(groupId).then(setItems).catch((e) => setError(e.message))
  }, [groupId])

  if (error) return <p className="error">{error}</p>
  if (!items) return <p className="muted">Buscando…</p>

  return (
    <div className="picker">
      <h3>Sugerencias</h3>
      {items.length === 0 && (
        <p className="muted">
          Nada parecido en otros súper. Se sugiere por categoría y parecido de nombre, y solo
          productos de la misma dimensión que el grupo.
        </p>
      )}
      <ul>
        {items.map((s) => (
          <li key={s.storeProductId}>
            <button
              className="link"
              onClick={async () => {
                try {
                  onAdded(await api.addGroupMember(groupId, s.storeProductId))
                } catch (e) {
                  setError(e.message)
                }
              }}
            >
              <strong>{s.storeName}</strong> · {s.name}
            </button>
            <span className="muted small">
              {s.packageSize ? ` · ${s.packageSize} ${s.packageUnit}` : ' · sin tamaño'}
              {s.sameCategory && ' · misma categoría'}
              {` · parecido ${Math.round(s.similarity * 100)} %`}
              {s.currentGroupId && ' · ya está en otro grupo'}
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}

function PreferenceForm({ groupId, members, unit, onSaved }) {
  const [storeProductId, setStoreProductId] = useState(members[0]?.storeProductId ?? '')
  const [marginType, setMarginType] = useState('ABS')
  const [marginValue, setMarginValue] = useState('0')
  const [note, setNote] = useState('')
  const [error, setError] = useState(null)

  return (
    <div className="newproduct">
      <label>
        Preferido
        <select value={storeProductId} onChange={(e) => setStoreProductId(Number(e.target.value))}>
          {members.map((m) => (
            <option key={m.storeProductId} value={m.storeProductId}>
              {m.storeName} · {m.productName}
            </option>
          ))}
        </select>
      </label>
      <label>
        Prima
        <input value={marginValue} onChange={(e) => setMarginValue(e.target.value)} />
      </label>
      <label>
        Tipo
        <select value={marginType} onChange={(e) => setMarginType(e.target.value)}>
          <option value="ABS">€/{unit}</option>
          <option value="PCT">%</option>
        </select>
      </label>
      <label>
        Nota
        <input value={note} onChange={(e) => setNote(e.target.value)} placeholder="me gusta más" />
      </label>
      <p className="muted small">
        Cuánto de más estás dispuesto a pagar por el preferido. Si la diferencia con el más barato
        cabe dentro de la prima, gana el preferido; si no, gana el barato. En los dos casos verás
        las dos opciones y lo que cuesta la diferencia.
      </p>
      {error && <p className="error">{error}</p>}
      <div className="pickeractions">
        <button
          onClick={async () => {
            try {
              onSaved(
                await api.setPreference(groupId, {
                  storeProductId,
                  marginType,
                  marginValue: marginValue === '' ? 0 : Number(marginValue.replace(',', '.')),
                  note: note || null,
                }),
              )
            } catch (e) {
              setError(e.message)
            }
          }}
        >
          Guardar preferencia
        </button>
      </div>
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

/** Un decimal en los porcentajes: dos son precisión que el dato no tiene. */
function fmtPct(value) {
  return value == null ? '—' : Number(value).toFixed(1).replace('.', ',')
}

/** Las fechas se leen en español, no en ISO. */
function fmtDate(iso) {
  const [y, m, d] = String(iso ?? '').split('-')
  return d ? `${d}/${m}/${y}` : (iso ?? '—')
}
