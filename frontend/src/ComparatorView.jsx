import React, { useEffect, useState } from 'react'
import { api } from './api.js'

const DIMENSIONS = [
  ['WEIGHT', 'peso (€/kg)'],
  ['VOLUME', 'volumen (€/L)'],
  ['UNIT', 'unidades (€/ud)'],
]

export default function ComparatorView() {
  const [groups, setGroups] = useState([])
  const [openId, setOpenId] = useState(null)
  const [creating, setCreating] = useState(false)
  const [error, setError] = useState(null)

  const reload = () => api.groups().then(setGroups).catch((e) => setError(e.message))

  useEffect(() => {
    reload()
  }, [])

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
    <section className="card">
      <h2>Grupos comparables</h2>
      <p className="muted">
        Un grupo junta el mismo artículo en varios súper. Se compara por precio normalizado, así
        que la unidad la fija la dimensión y todos sus miembros tienen que compartirla.
      </p>
      {error && <p className="error">{error}</p>}

      {groups.length === 0 && !creating && (
        <p className="muted">Aún no hay grupos. Crea uno y añádele productos de cada súper.</p>
      )}

      {groups.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>Grupo</th>
              <th>Miembros</th>
              <th>Comparables</th>
              <th>Más barato</th>
              <th>Preferencia</th>
            </tr>
          </thead>
          <tbody>
            {groups.map((g) => (
              <tr key={g.id}>
                <td>
                  <button className="link" onClick={() => setOpenId(g.id)}>
                    {g.name}
                  </button>
                </td>
                <td>{g.memberCount}</td>
                <td>
                  {g.comparableCount}
                  {g.comparableCount < g.memberCount && (
                    <span className="muted small"> de {g.memberCount}</span>
                  )}
                </td>
                <td>
                  {g.cheapestStore ? (
                    `${g.cheapestStore} · ${fmt(g.cheapestPrice)} €/${g.comparisonUnit}`
                  ) : (
                    <span className="muted">sin datos</span>
                  )}
                </td>
                <td className="muted">{g.hasPreference ? 'sí' : '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
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

  const load = () => api.groupComparison(id).then(setData).catch((e) => setError(e.message))

  useEffect(() => {
    load()
  }, [id])

  if (error) return <p className="error">{error}</p>
  if (!data) return <p className="muted">Cargando…</p>

  const { group, ranking, notComparable, verdict, dataWarning } = data
  const preferred = ranking.find((r) => r.preferred) ?? notComparable.find((r) => r.preferred)

  return (
    <section className="card">
      <button className="link" onClick={onClose}>
        ← Volver a grupos
      </button>
      <h2>{group.name}</h2>
      <p className="muted">
        Se compara en €/{group.comparisonUnit}
        {group.categoryName && ` · ${group.categoryName}`} · {group.memberCount} miembros
      </p>

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
              <th>Precio</th>
              <th>Con prima</th>
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
                  {r.productName}
                  {r.preferred && <span className="badge extracted"> preferido</span>}
                </td>
                <td>
                  {fmt(r.normalizedUnitPrice)} €/{r.unit}
                </td>
                <td className="muted">
                  {r.adjustedPrice != null ? `${fmt(r.adjustedPrice)} €/${r.unit}` : '—'}
                </td>
                <td className="muted">
                  {r.observedAt} · {ageLabel(r.ageDays)}
                </td>
                <td>
                  <button className="link" onClick={() => remove(r.storeProductId)}>
                    quitar
                  </button>
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
          <ul className="issues">
            {notComparable.map((r) => (
              <li key={r.storeProductId}>
                <strong>{r.storeName}</strong> · {r.productName}
                {r.preferred && <span className="badge extracted"> preferido</span>}
                <div className="muted small">{r.notComparableReason}</div>
                <button className="link" onClick={() => remove(r.storeProductId)}>
                  quitar del grupo
                </button>
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
          <button className="link" onClick={clearPreference}>
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
    setData(await api.removeGroupMember(id, storeProductId))
  }

  async function clearPreference() {
    setData(await api.clearPreference(id))
  }
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
                verdict.preferenceCostPct ? ` · ${fmt(verdict.preferenceCostPct)} %` : ''
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
