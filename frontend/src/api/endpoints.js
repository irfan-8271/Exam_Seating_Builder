/**
 * Every backend call the frontend makes, in one place.
 *
 * Pure /api/v1/* contract — zero legacy endpoints or university-specific parameters.
 */
import { api, query } from './client'

export const authApi = {
  login: (username, password, rememberMe = false) =>
    api.post('/api/v1/auth/login', { username, password, rememberMe }),
  platformLogin: (username, password) =>
    api.post('/api/v1/auth/platform/login', { username, password }),
  register: (body) => api.post('/api/v1/auth/register', body),
  me: () => api.get('/api/v1/auth/me'),
  logout: () => api.post('/api/v1/auth/logout'),
}

export const memberApi = {
  list: () => api.get('/api/v1/users'),
  create: (body) => api.post('/api/v1/users', body),
  update: (id, body) => api.put(`/api/v1/users/${id}`, body),
  remove: (id) => api.delete(`/api/v1/users/${id}`),
}

export const institutionApi = {
  current: () => api.get('/api/v1/institutions/current'),
  update: (body) => api.patch('/api/v1/institutions/current', body),
}

export const planApi = {
  list: () => api.get('/api/v1/plans'),
  subscription: () => api.get('/api/v1/subscriptions/current'),
}

export const paymentApi = {
  config: () => api.get('/api/v1/payment-config'),
  qrPath: () => '/api/v1/payment-config/qr',
  myRequests: () => api.get('/api/v1/subscription-requests'),
  submitRequest: (fields, screenshotFile) => {
    const form = new FormData()
    Object.entries(fields || {}).forEach(([key, val]) => {
      if (val !== undefined && val !== null && val !== '') {
        form.append(key, String(val))
      }
    })
    if (screenshotFile) {
      form.append('screenshot', screenshotFile)
    }
    return api.upload('/api/v1/subscription-requests', form)
  },
  myScreenshotPath: (id) => `/api/v1/subscription-requests/${id}/screenshot`,
}

export const usageApi = {
  usage: () => api.get('/api/v1/usage'),
  generations: () => api.get('/api/v1/generations'),
}

async function collectPages(fetchPage, pageSize = 200) {
  const first = await fetchPage(0, pageSize)
  if (!first || !Array.isArray(first.content)) {
    return { content: [], totalElements: 0 }
  }
  const all = [...first.content]
  const totalPages = Number(first.totalPages || 1)
  for (let p = 1; p < totalPages; p += 1) {
    const next = await fetchPage(p, pageSize)
    if (!next?.content?.length) break
    all.push(...next.content)
  }
  return {
    ...first,
    content: all,
    totalElements: first.totalElements ?? all.length,
  }
}

export const studentApi = {
  list: (params) => api.get(`/api/v1/students${query(params)}`),
  listAll: (params = {}) =>
    collectPages((page, size) =>
      api.get(`/api/v1/students${query({ ...params, page, size })}`),
    ),
  get: (id) => api.get(`/api/v1/students/${id}`),
  create: (body) => api.post('/api/v1/students', body),
  replace: (id, body) => api.put(`/api/v1/students/${id}`, body),
  patch: (id, body) => api.patch(`/api/v1/students/${id}`, body),
  remove: (id) => api.delete(`/api/v1/students/${id}`),
  clearAll: () => api.delete('/api/v1/students'),
  identity: () => api.get('/api/v1/students/identity'),
  schema: () => api.get('/api/v1/students/schema'),
  saveIdentity: (body) => api.put('/api/v1/students/identity', body),
  clearIdentity: () => api.delete('/api/v1/students/identity'),
  preview: (file) => {
    const form = new FormData()
    form.append('file', file)
    return api.upload('/api/v1/students/imports/preview', form)
  },
  import: (file, identityAttributes, classmateAttributes, scopeAttribute) => {
    const form = new FormData()
    form.append('file', file)
    if (identityAttributes && identityAttributes.length) {
      form.append('identityAttributes', identityAttributes.join(','))
    }
    if (classmateAttributes && classmateAttributes.length) {
      form.append('classmateAttributes', classmateAttributes.join(','))
    }
    if (scopeAttribute) {
      form.append('scopeAttribute', scopeAttribute)
    }
    return api.upload('/api/v1/students/imports', form)
  },
}

export const cohortApi = {
  overview: () => api.get('/api/v1/students/cohorts'),
  members: (attributes, values) => {
    const params = new URLSearchParams()
    ;(attributes || []).forEach((a) => params.append('attributes', a))
    ;(values || []).forEach((v) => params.append('values', v))
    const qs = params.toString()
    return api.get(`/api/v1/students/cohorts/members${qs ? `?${qs}` : ''}`)
  },
  preview: (body) => api.post('/api/v1/students/cohorts/preview', body),
  configure: (body) => api.post('/api/v1/students/cohorts/configure', body),
  removeRule: (body) => api.delete('/api/v1/students/cohorts/rule', body),
  remove: (body) => api.delete('/api/v1/students/cohorts', body),
}

export const roomApi = {
  list: (params) => api.get(`/api/v1/rooms${query(params)}`),
  listAll: (params = {}) =>
    collectPages((page, size) =>
      api.get(`/api/v1/rooms${query({ sortBy: 'hallName', ...params, page, size })}`),
    ),
  get: (id) => api.get(`/api/v1/rooms/${id}`),
  create: (body) => api.post('/api/v1/rooms', body),
  replace: (id, body) => api.put(`/api/v1/rooms/${id}`, body),
  patch: (id, body) => api.patch(`/api/v1/rooms/${id}`, body),
  remove: (id) => api.delete(`/api/v1/rooms/${id}`),
  benches: (id) => api.get(`/api/v1/rooms/${id}/benches`),
  import: (file) => {
    const form = new FormData()
    form.append('file', file)
    return api.upload('/api/v1/rooms/imports', form)
  },
}

export const seatingApi = {
  current: (params) => api.get(`/api/v1/seating-plans/current${query(params)}`),
  currentAll: async (params = {}) => {
    const first = await api.get(
      `/api/v1/seating-plans/current${query({ ...params, page: 0, size: 200 })}`,
    )
    if (!first || !Array.isArray(first.allocations)) return first
    const allAllocations = [...first.allocations]
    const totalPages = Number(first.totalPages || 1)
    for (let p = 1; p < totalPages; p += 1) {
      const next = await api.get(
        `/api/v1/seating-plans/current${query({ ...params, page: p, size: 200 })}`,
      )
      if (!next?.allocations?.length) break
      allAllocations.push(...next.allocations)
    }
    return { ...first, allocations: allAllocations }
  },
  analyze: (body) => api.post('/api/v1/seating-plans/analyze', body),
  arrange: (body) => api.post('/api/v1/seating-plans', body),
  clear: (scopeKey) => api.delete(`/api/v1/seating-plans/current${query({ scopeKey })}`),
  move: (allocationId, body) =>
    api.patch(`/api/v1/seating-plans/current/allocations/${allocationId}`, body),
  rounds: () => api.get('/api/v1/seating-rounds'),
  currentRound: (scopeKey) =>
    api.get(`/api/v1/seating-rounds/current${query({ scopeKey })}`),
}

export const exportApi = {
  workbookPath: (scopeKey) =>
    `/api/v1/seating-plans/current/workbook${query({ scopeKey: scopeKey || undefined })}`,
  doorNoticesPath: (scopeKey) =>
    `/api/v1/seating-plans/current/door-notices${query({ scopeKey: scopeKey || undefined })}`,
  invigilatorSheetsPath: (scopeKey) =>
    `/api/v1/seating-plans/current/invigilator-sheets${query({ scopeKey: scopeKey || undefined })}`,
}

export const adminApi = {
  status: () => api.get('/api/v1/admin/status'),
  plans: () => api.get('/api/v1/admin/plans'),
  createPlan: (body) => api.post('/api/v1/admin/plans', body),
  updatePlan: (code, body) =>
    api.put(`/api/v1/admin/plans/${encodeURIComponent(code)}`, body),
  institutions: () => api.get('/api/v1/admin/institutions'),
  institution: (id) => api.get(`/api/v1/admin/institutions/${id}`),
  changeSubscription: (id, body) =>
    api.put(`/api/v1/admin/institutions/${id}/subscription`, body),
  changeStatus: (id, status) =>
    api.put(`/api/v1/admin/institutions/${id}/status${query({ status })}`),
  users: () => api.get('/api/v1/admin/users'),
  user: (id) => api.get(`/api/v1/admin/users/${id}`),
  changeUser: (id, body) => api.put(`/api/v1/admin/users/${id}`, body),
  changeUserLimits: (id, limits) =>
    api.put(`/api/v1/admin/users/${id}/limits`, { limits }),
  activity: (limit = 100) => api.get(`/api/v1/admin/activity${query({ limit })}`),
  usage: () => api.get('/api/v1/admin/usage'),
  generations: (limit = 50) => api.get(`/api/v1/admin/generations${query({ limit })}`),
  paymentConfig: () => api.get('/api/v1/admin/payment-config'),
  updatePaymentConfig: (body) => api.put('/api/v1/admin/payment-config', body),
  uploadPaymentQr: (file) => {
    const form = new FormData()
    form.append('file', file)
    return api.upload('/api/v1/admin/payment-config/qr', form)
  },
  deletePaymentQr: () => api.delete('/api/v1/admin/payment-config/qr'),
  qrPath: () => '/api/v1/admin/payment-config/qr',
  subscriptionRequests: (status) =>
    api.get(`/api/v1/admin/subscription-requests${query({ status })}`),
  requestScreenshotPath: (id) => `/api/v1/admin/subscription-requests/${id}/screenshot`,
  approveRequest: (id, body = {}) =>
    api.post(`/api/v1/admin/subscription-requests/${id}/approve`, body),
  rejectRequest: (id, body = {}) =>
    api.post(`/api/v1/admin/subscription-requests/${id}/reject`, body),
  storage: () => api.get('/api/v1/admin/storage'),
  previewStoredFileCleanup: (body) =>
    api.post('/api/v1/admin/storage/stored-files/preview', body),
  cleanupStoredFiles: (body) =>
    api.post('/api/v1/admin/storage/stored-files/cleanup', body),
  previewScreenshotCleanup: (body) =>
    api.post('/api/v1/admin/storage/screenshots/preview', body),
  cleanupScreenshots: (body) =>
    api.post('/api/v1/admin/storage/screenshots/cleanup', body),
}
