/**
 * The HTTP client.
 *
 * Every call goes to the same origin and carries the session token, so the
 * browser never has to deal with CORS and the token never ends up in a URL.
 * The backend answers every failure with the same envelope:
 *
 *   { timestamp, status, code, error, message, path, details }
 *
 * — so one place turns that into a readable message.
 */

const TOKEN_KEY = 'examseating.token'
const USER_KEY = 'examseating.user'
const INSTITUTION_KEY = 'examseating.institution'

/**
 * Base URL for API requests.
 * Empty in local development (uses Vite's dev proxy to localhost:8080).
 * Configured in production (e.g. Vercel) via VITE_API_BASE_URL=https://your-backend.onrender.com
 */
const API_BASE = (import.meta.env?.VITE_API_BASE_URL || '').replace(/\/$/, '')

/** An error the backend described — its message is safe to show. */
export class ApiError extends Error {
  constructor(message, status, code, details) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.details = details || []
  }
}

export const tokenStore = {
  get: () => window.localStorage.getItem(TOKEN_KEY),
  set: (token) => window.localStorage.setItem(TOKEN_KEY, token),
  clear: () => {
    window.localStorage.removeItem(TOKEN_KEY)
    window.localStorage.removeItem(USER_KEY)
    window.localStorage.removeItem(INSTITUTION_KEY)
  },
  user: () => {
    const raw = window.localStorage.getItem(USER_KEY)
    try {
      return raw ? JSON.parse(raw) : null
    } catch {
      return null
    }
  },
  setUser: (user) => window.localStorage.setItem(USER_KEY, JSON.stringify(user)),
  institution: () => {
    const raw = window.localStorage.getItem(INSTITUTION_KEY)
    try {
      return raw ? JSON.parse(raw) : null
    } catch {
      return null
    }
  },
  setInstitution: (institution) =>
    window.localStorage.setItem(INSTITUTION_KEY, JSON.stringify(institution)),
}

/** Called when the backend says the session is gone. */
let onUnauthorized = () => {}
export function setUnauthorizedHandler(handler) {
  onUnauthorized = handler
}

async function parse(response) {
  const type = response.headers.get('content-type') || ''
  if (type.includes('application/json')) {
    try {
      return await response.json()
    } catch {
      return null
    }
  }
  return null
}

function messageOf(body, response) {
  if (body && typeof body.message === 'string' && body.message.trim()) return body.message
  if (body && Array.isArray(body.details) && body.details.length) return body.details.join('; ')
  return `${response.status} ${response.statusText || 'Request failed'}`
}

async function request(path, options = {}) {
  const headers = new Headers(options.headers || {})
  const token = tokenStore.get()
  if (token) headers.set('Authorization', `Bearer ${token}`)

  let body = options.body
  if (body && !(body instanceof FormData) && typeof body !== 'string') {
    headers.set('Content-Type', 'application/json')
    body = JSON.stringify(body)
  }

  let response
  const fullUrl = path.startsWith('http') ? path : `${API_BASE}${path}`
  try {
    response = await fetch(fullUrl, { ...options, headers, body })
  } catch (error) {
    throw new ApiError(
      'Cannot reach the server. Is the backend running on port 8080?',
      0,
      'NETWORK_ERROR',
    )
  }

  if (response.status === 204) return null

  const parsed = await parse(response)

  if (!response.ok) {
    if (response.status === 401) {
      tokenStore.clear()
      onUnauthorized()
    }
    throw new ApiError(
      messageOf(parsed, response),
      response.status,
      parsed?.code,
      parsed?.details,
    )
  }
  return parsed
}

export const api = {
  get: (path) => request(path),
  post: (path, body) => request(path, { method: 'POST', body }),
  put: (path, body) => request(path, { method: 'PUT', body }),
  patch: (path, body) => request(path, { method: 'PATCH', body }),
  delete: (path) => request(path, { method: 'DELETE' }),
  upload: (path, formData) => request(path, { method: 'POST', body: formData }),
}

/** Builds a query string, leaving out everything that is empty. */
export function query(params) {
  const search = new URLSearchParams()
  Object.entries(params || {}).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') return
    search.append(key, value)
  })
  const text = search.toString()
  return text ? `?${text}` : ''
}

/** Downloads a file from the backend and hands it to the browser. */
export async function download(path, filename) {
  const token = tokenStore.get()
  const fullUrl = path.startsWith('http') ? path : `${API_BASE}${path}`
  const response = await fetch(fullUrl, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  })
  if (!response.ok) {
    const parsed = await parse(response)
    throw new ApiError(messageOf(parsed, response), response.status, parsed?.code)
  }
  const blob = await response.blob()
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}
