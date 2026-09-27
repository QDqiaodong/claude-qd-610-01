import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一走 nginx 反代到后端 /api：
 *   成功 → 后端包成 { ok:true, message, data }，这里直接把 data 交给页面
 *   失败 → 后端包成 { ok:false, message:"中文业务提示" }，这里统一弹提示并 reject
 */
const http = axios.create({ baseURL: '/api', timeout: 15000 })

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body === 'object' && Object.prototype.hasOwnProperty.call(body, 'ok')) {
      if (body.ok) return body.data
      const error = new Error(body.message || '操作失败')
      // 错误码一并抛出：TICKET_STATUS_DENIED / REWEIGH_IN_PROGRESS /
      // SELF_REVIEW_FORBIDDEN / VERSION_CONFLICT / SETTLEMENT_RACED，页面按码分支处理
      error.code = body.code
      ElMessage.error(body.message || '操作失败')
      return Promise.reject(error)
    }
    return body
  },
  (err) => {
    const body = err.response && err.response.data
    const message = (body && body.message) || err.message || '网络异常，请稍后再试'
    const error = new Error(message)
    error.code = body && body.code
    ElMessage.error(message)
    return Promise.reject(error)
  }
)

/** 模块一：回收品类与回收价 */
export const categoryApi = {
  list: () => http.get('/categories'),
  options: () => http.get('/categories/options'),
  create: (data) => http.post('/categories', data),
  adjustPrice: (id, data) => http.post(`/categories/${id}/price`, data),
  toggleStored: (id) => http.post(`/categories/${id}/stored`)
}

/** 模块二：过磅单（主表 + 明细行） */
export const ticketApi = {
  list: () => http.get('/tickets'),
  pending: () => http.get('/tickets/pending'),
  detail: (ticketNo) => http.get(`/tickets/${ticketNo}`),
  create: (data) => http.post('/tickets', data),
  changeStatus: (ticketNo, status) => http.post(`/tickets/${ticketNo}/status`, { status })
}

/** 模块三：车辆与司机调度 */
export const vehicleApi = {
  list: () => http.get('/vehicles'),
  create: (data) => http.post('/vehicles', data),
  changeStatus: (id, status) => http.post(`/vehicles/${id}/status`, { status })
}

/** 模块七：证照有效期联锁台（司机证照 / 车辆证照） */
export const credentialApi = {
  drivers: () => http.get('/credentials/drivers'),
  createDriver: (data) => http.post('/credentials/drivers', data),
  updateDriver: (id, data) => http.post(`/credentials/drivers/${id}`, data),
  docs: (ownerType, ownerId) =>
    http.get('/credentials/docs', { params: ownerType ? { ownerType, ownerId } : {} }),
  reviewQueue: () => http.get('/credentials/review-queue'),
  group: (groupUuid) => http.get(`/credentials/groups/${groupUuid}`),
  affectedTrips: (id) => http.get(`/credentials/docs/${id}/affected-trips`),
  saveDraft: (data) => http.post('/credentials/docs', data),
  updateDraft: (id, data) => http.post(`/credentials/docs/${id}`, data),
  submit: (id, handler) => http.post(`/credentials/docs/${id}/submit`, { handler }),
  approve: (id, reviewer, note) => http.post(`/credentials/docs/${id}/approve`, { reviewer, note }),
  reject: (id, reviewer, reason) => http.post(`/credentials/docs/${id}/reject`, { reviewer, reason }),
  revoke: (id, operator, reason) => http.post(`/credentials/docs/${id}/revoke`, { operator, reason })
}

/** 模块七：派车联锁 + 派车资格快照 */
export const dispatchApi = {
  board: () => http.get('/dispatch/board'),
  check: (data) => http.post('/dispatch/check', data),
  create: (data) => http.post('/dispatch/trips', data),
  returnTrip: (id, operator, actualReturnAt) =>
    http.post(`/dispatch/trips/${id}/return`, { operator, actualReturnAt }),
  trip: (id) => http.get(`/dispatch/trips/${id}`),
  trips: () => http.get('/dispatch/trips'),
  onRoad: () => http.get('/dispatch/on-road')
}

/** 模块四：结算单 */
export const settleApi = {
  list: () => http.get('/settlements'),
  settle: (data) => http.post('/settlements', data)
}

/** 模块六：复称复核台（发起 → 补录 → 现场放行 / 复核 → 恢复结算） */
export const reweighApi = {
  board: () => http.get('/reweigh/orders'),
  detail: (reweighNo) => http.get(`/reweigh/orders/${reweighNo}`),
  ticketView: (ticketNo) => http.get(`/reweigh/ticket/${ticketNo}`),
  initiate: (data) => http.post('/reweigh/initiate', data),
  supplement: (data) => http.post('/reweigh/supplement', data),
  release: (data) => http.post('/reweigh/release', data),
  escalate: (data) => http.post('/reweigh/escalate', data),
  review: (data) => http.post('/reweigh/review', data)
}

/** 模块五：日结对账报表（JPQL 聚合） */
export const statsApi = {
  overview: () => http.get('/stats/overview'),
  board: (from, to, limit) => http.get('/stats/board', { params: { from, to, limit } }),
  dailyCategory: (from, to) => http.get('/stats/daily-category', { params: { from, to } }),
  dailySummary: (from, to) => http.get('/stats/daily-summary', { params: { from, to } }),
  categoryTotal: (from, to) => http.get('/stats/category-total', { params: { from, to } }),
  customerTop: (from, to, limit) => http.get('/stats/customer-top', { params: { from, to, limit } })
}

/** yyyy-MM-dd */
export const isoDate = (date) => {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

export const shiftDay = (iso, delta) => {
  const date = new Date(`${iso}T00:00:00`)
  date.setDate(date.getDate() + delta)
  return isoDate(date)
}

/** 金额/重量展示：千分位，金额固定两位 */
export const money = (value) => {
  const num = Number(value || 0)
  return num.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

export const weight = (value) => {
  const num = Number(value || 0)
  return num.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}

export default http
