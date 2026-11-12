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
  changePlan: (planCode) => api.put('/api/v1/subscriptions/current', { planCode }),
}

export const usageApi = {
  usage: () => api.get('/api/v1/usage'),
  generations: () => api.get('/api/v1/generations'),
}

export const studentApi = {
  list: (params) => api.get(`/api/v1/students${query(params)}`),
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

export const roomApi = {
  list: (params) => api.get(`/api/v1/rooms${query(params)}`),
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
}
