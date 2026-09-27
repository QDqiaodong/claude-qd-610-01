import { createRouter, createWebHistory } from 'vue-router'

/**
 * 导航形态：顶部一条【回收站作业带】（横向页签 + 右侧日期），下面一排【今日指标卡片】，
 * 再往下才是业务区。图标用 emoji，不引图标库。
 */
export const navItems = [
  { key: 'weigh', path: '/weigh', icon: '⚖️', label: '过磅台', hint: '毛重皮重过磅，明细行一行一个品类' },
  { key: 'reweigh', path: '/reweigh', icon: '🔁', label: '复称复核台', hint: '复称发起、补录、现场放行 / 复核，结算冻结全链路' },
  { key: 'price', path: '/price', icon: '🏷️', label: '品类牌价', hint: '当日回收价 / 出售价，行内调价留理由' },
  { key: 'fleet', path: '/fleet', icon: '🚚', label: '车辆调度', hint: '派车确认：选司机+计划回场，证照联锁，固化资格快照' },
  { key: 'credential', path: '/credential', icon: '🪪', label: '证照联锁台', hint: '司机/车辆证照版本复核、有效期、在途影响一条链' },
  { key: 'settle', path: '/settle', icon: '💰', label: '结算付款', hint: '只放行已解冻且金额版本明确的磅单' },
  { key: 'report', path: '/report', icon: '📊', label: '日结对账', hint: 'JPQL 聚合报表 + 纯 SVG 条形图' }
]

const routes = [
  { path: '/', redirect: '/weigh' },
  { path: '/weigh', name: 'weigh', component: () => import('../views/WeighDeskView.vue') },
  { path: '/reweigh', name: 'reweigh', component: () => import('../views/ReweighBoardView.vue') },
  { path: '/price', name: 'price', component: () => import('../views/CategoryPriceView.vue') },
  { path: '/fleet', name: 'fleet', component: () => import('../views/FleetDispatchView.vue') },
  { path: '/credential', name: 'credential', component: () => import('../views/CredentialBoardView.vue') },
  { path: '/settle', name: 'settle', component: () => import('../views/SettlementView.vue') },
  { path: '/report', name: 'report', component: () => import('../views/DailyReportView.vue') },
  { path: '/:pathMatch(.*)*', redirect: '/weigh' }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
