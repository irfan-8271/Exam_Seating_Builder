/**
 * Every backend call the frontend makes, in one place.
 *
 * The paths mirror the OpenAPI contract (`/openapi.yaml` of the running
 * backend) — nothing else in the app builds a URL by hand.
 */
import { api, query } from './client'

export const authApi = {
  login: (username, password) =>
    api.post('/api/v1/auth/login', { username, password }),
  register: (body) => api.post('/api/v1/auth/register', body),
  me: () => api.get('/api/v1/auth/me'),
  logout: () => api.post('/api/v1/auth/logout'),
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
  identity: () => api.get('/api/v1/students/identity'),
  saveIdentity: (body) => api.put('/api/v1/students/identity', body),
  clearIdentity: () => api.delete('/api/v1/students/identity'),
  preview: (file) => {
    const form = new FormData()
    form.append('file', file)
    return api.upload('/api/v1/students/imports/preview', form)
  },
  import: (file, identityAttributes) => {
    const form = new FormData()
    form.append('file', file)
    if (identityAttributes && identityAttributes.length) {
      form.append('identityAttributes', identityAttributes.join(','))
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
  plan: (params) => api.get(`/api/v1/seating-plans${query(params)}`),
  arrange: (body) => api.post('/api/v1/seating-plans', body),
  clear: (subjectCode) => api.delete(`/api/v1/seating-plans${query({ subjectCode })}`),
  allocations: (params) => api.get(`/api/v1/seating-plans/allocations${query(params)}`),
  move: (allocationId, body) =>
    api.patch(`/api/v1/seating-plans/allocations/${allocationId}`, body),
  rounds: () => api.get('/api/v1/seating-rounds'),
  currentRound: (subjectCode) =>
    api.get(`/api/v1/seating-rounds/current${query({ subjectCode })}`),
}

export const exportApi = {
  excel: () => api.get('/api/v1/exports/excel'),
  doorNotices: (subjectCode) =>
    api.get(`/api/v1/exports/pdf/door-notices${query({ subjectCode })}`),
  invigilator: (subjectCode) =>
    api.get(`/api/v1/exports/pdf/invigilator${query({ subjectCode })}`),
  list: () => api.get('/api/v1/exports'),
}

/** The operator's API — behind the admin screen. */
export const adminApi = {
  status: () => api.get('/api/v1/admin/status'),
  institutions: () => api.get('/api/v1/admin/institutions'),
  institution: (id) => api.get(`/api/v1/admin/institutions/${id}`),
  changeSubscription: (id, body) =>
    api.put(`/api/v1/admin/institutions/${id}/subscription`, body),
  changeStatus: (id, status) =>
    api.put(`/api/v1/admin/institutions/${id}/status${query({ status })}`),
  users: () => api.get('/api/v1/admin/users'),
  changeUser: (id, body) => api.put(`/api/v1/admin/users/${id}`, body),
  usage: () => api.get('/api/v1/admin/usage'),
  generations: (limit = 50) => api.get(`/api/v1/admin/generations${query({ limit })}`),
}
