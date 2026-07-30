const AUTH_KEY = 'ticketing.auth'

let auth = localStorage.getItem(AUTH_KEY)

export function setCredentials(username, password) {
  auth = btoa(`${username}:${password}`)
  localStorage.setItem(AUTH_KEY, auth)
}

export function clearCredentials() {
  auth = null
  localStorage.removeItem(AUTH_KEY)
}

export function hasCredentials() {
  return Boolean(auth)
}

async function request(path, { method = 'GET', body, raw = false } = {}) {
  const headers = { Authorization: `Basic ${auth}` }
  if (body && !(body instanceof FormData)) headers['Content-Type'] = 'application/json'

  const response = await fetch(`/api${path}`, {
    method,
    headers,
    body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined,
  })

  if (response.status === 401) {
    clearCredentials()
    throw new Error('Credenciales incorrectas')
  }
  if (!response.ok) {
    // El backend manda el motivo en el cuerpo cuando rechaza una validación
    // ("quedan 2 comprobaciones en rojo"), y ese texto es útil tal cual.
    const text = await response.text()
    throw new Error(text || `Error ${response.status}`)
  }
  if (raw) return response.blob()
  return response.status === 204 ? null : response.json()
}

export const api = {
  listTickets: () => request('/tickets'),
  getTicket: (id) => request(`/tickets/${id}`),
  upload: (file, storeCode) => {
    const form = new FormData()
    form.append('file', file)
    const query = storeCode ? `?storeCode=${encodeURIComponent(storeCode)}` : ''
    return request(`/tickets${query}`, { method: 'POST', body: form })
  },
  reextract: (id) => request(`/tickets/${id}/reextract`, { method: 'POST' }),
  validate: (id, payload) => request(`/tickets/${id}/validate`, { method: 'POST', body: payload }),
  // La imagen va autenticada, así que no se puede poner la URL en un <img> a
  // secas: se descarga y se envuelve en un object URL.
  image: (id) => request(`/tickets/${id}/image`, { raw: true }),
  stores: () => request('/stores'),
  categories: () => request('/categories'),
  searchProducts: (storeId, q) =>
    request(`/stores/${storeId}/products?q=${encodeURIComponent(q || '')}`),
}
