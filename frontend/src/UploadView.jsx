import React, { useEffect, useState } from 'react'
import { api } from './api.js'

export default function UploadView({ onUploaded }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [notice, setNotice] = useState(null)
  const [stores, setStores] = useState([])
  const [storeCode, setStoreCode] = useState('')

  useEffect(() => {
    api.stores().then(setStores).catch(() => setStores([]))
  }, [])

  async function pick(event) {
    const file = event.target.files?.[0]
    if (!file) return
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const result = await api.upload(file, storeCode || undefined)
      if (result.sameImageAs?.length) {
        setNotice(`Ojo: esta misma foto ya se subió en el ticket #${result.sameImageAs.join(', #')}.`)
      }
      onUploaded(result.ticketId)
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
      event.target.value = ''
    }
  }

  return (
    <section className="card upload">
      <h2>Subir ticket</h2>
      <p className="muted">
        La extracción va por detrás y puede tardar minutos: en cuanto se sube, el ticket queda en
        cola y se puede seguir a lo otro.
      </p>
      {/*
        Decir el súper a mano no es opcional por capricho: el CIF que el modelo
        necesita para adivinarlo está en letra diminuta al pie del ticket, y en
        el de Cash Fresh ni siquiera llega a leerlo. Con el súper equivocado se
        le aplican reglas de layout ajenas y hasta las comprobaciones mienten:
        un Cash Fresh tomado por Xinya activaba C4, que no aplica, y daba verde
        contra un recuento de artículos inventado.
      */}
      <label className="storepick">
        Súper
        <select value={storeCode} onChange={(e) => setStoreCode(e.target.value)} disabled={busy}>
          <option value="">detectar por el ticket</option>
          {stores.map((s) => (
            <option key={s.id} value={s.code}>
              {s.name}
            </option>
          ))}
        </select>
      </label>

      {/*
        SIN el atributo capture, a propósito. Con capture="environment" el móvil
        abre la cámara de golpe y NO deja elegir de la galería, que es justo lo
        que hace falta para subir una foto hecha antes. Con accept="image/*" a
        secas, el sistema ofrece las dos: cámara y galería.
      */}
      <label className="filebutton">
        {busy ? 'Subiendo…' : 'Hacer foto o elegir imagen'}
        <input type="file" accept="image/*" onChange={pick} disabled={busy} />
      </label>
      {notice && <p className="warn">{notice}</p>}
      {error && <p className="error">{error}</p>}
    </section>
  )
}
