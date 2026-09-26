<template>
  <div class="shell">
    <!-- 顶部作业带：站名 + 横向页签 + 今日日期 -->
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">♻</span>
        <span class="brand-text">
          <strong>宏源再生资源回收中转站</strong>
          <em>收料 · 过磅 · 结算 · 日结</em>
        </span>
      </div>
      <nav class="tabs">
        <button
          v-for="item in navItems"
          :key="item.key"
          class="tab"
          :class="{ 'is-on': activeKey === item.key }"
          type="button"
          :title="item.hint"
          @click="go(item)"
        >
          <span class="tab-ico">{{ item.icon }}</span>
          <span class="tab-label">{{ item.label }}</span>
        </button>
      </nav>
      <div class="today">
        <span class="today-date num">{{ shortDate }}</span>
        <span class="today-week">{{ weekday }}</span>
      </div>
    </header>

    <!-- 指标卡片条：五个当日关键数，全站共用 -->
    <section class="metrics">
      <div class="metric" v-for="card in cards" :key="card.key" :class="{ 'is-main': card.key === 'weight' }">
        <div class="metric-top">
          <span class="metric-ico">{{ card.icon }}</span>
          <span class="metric-label">{{ card.label }}</span>
        </div>
        <div class="metric-value num">
          {{ card.value }}<i class="metric-unit">{{ card.unit }}</i>
        </div>
        <div class="metric-foot" :class="card.tone">{{ card.foot }}</div>
      </div>
    </section>

    <main class="stage">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { computed, getCurrentInstance, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import router, { navItems } from './router'
import { statsApi, money, weight as fmtWeight } from './api'

/** 模板自带的 main.js 只装了 Element Plus，没装 router（那份文件不动它），所以在这里装 */
getCurrentInstance().appContext.app.use(router)

const route = useRoute()
const nav = useRouter()

const overview = ref(null)
const now = new Date()

const weekNames = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
const shortDate = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
const weekday = weekNames[now.getDay()]

const activeKey = computed(() => {
  const hit = navItems.find((item) => route.path.startsWith(item.path))
  return hit ? hit.key : 'weigh'
})

const cards = computed(() => {
  const o = overview.value || {}
  const todayWeight = Number(o.todayWeight || 0)
  const todayBuy = Number(o.todayBuyAmount || 0)
  const todaySell = Number(o.todaySellAmount || 0)
  const todayProfit = Number(o.todayGrossProfit || 0)
  const rate = todaySell > 0 ? ((todayProfit / todaySell) * 100).toFixed(1) : '0.0'
  const units = (o.unitBreakdown || []).map((u) => `${fmtWeight(u.weight)} ${u.unitName}`).join(' · ')
  return [
    {
      key: 'weight',
      icon: '⚖️',
      label: '今日回收总重量',
      value: fmtWeight(todayWeight),
      unit: '公斤',
      tone: '',
      foot: units || '今日还没有过磅数据'
    },
    {
      key: 'buy',
      icon: '📤',
      label: '今日回收支出',
      value: money(todayBuy),
      unit: '元',
      tone: 'is-out',
      foot: `今日开单 ${o.todayTicketCount || 0} 张`
    },
    {
      key: 'sell',
      icon: '📥',
      label: '今日出售收入',
      value: money(todaySell),
      unit: '元',
      tone: 'is-in',
      foot: '按下游厂家当日收购价估算'
    },
    {
      key: 'profit',
      icon: '📈',
      label: '毛利',
      value: money(todayProfit),
      unit: '元',
      tone: todayProfit >= 0 ? 'is-in' : 'is-out',
      foot: `毛利率 ${rate}%`
    },
    {
      key: 'fleet',
      icon: '🚚',
      label: '在途车辆',
      value: String(o.vehicleOnRoad || 0),
      unit: ` / ${o.vehicleTotal || 0} 台`,
      tone: '',
      foot: `待结算磅单 ${o.pendingTicketCount || 0} 张`
    }
  ]
})

function go(item) {
  if (route.path !== item.path) nav.push({ path: item.path })
}

async function loadOverview() {
  try {
    overview.value = await statsApi.overview()
  } catch (e) {
    overview.value = null
  }
}

onMounted(loadOverview)
</script>

<style scoped>
.shell {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

/* ---------- 顶部作业带 ---------- */
.topbar {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 0 18px;
  background: linear-gradient(90deg, #4e342e 0%, #795548 62%, #8d6e63 100%);
  color: #efebe9;
  box-shadow: 0 2px 8px rgba(62, 39, 35, 0.25);
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 0;
}

.brand-mark {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: rgba(255, 255, 255, 0.16);
  border: 1px solid rgba(255, 255, 255, 0.28);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 19px;
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}

.brand-text strong {
  font-size: 15px;
  letter-spacing: 0.5px;
}

.brand-text em {
  font-style: normal;
  font-size: 11px;
  opacity: 0.72;
}

.tabs {
  display: flex;
  align-items: stretch;
  gap: 4px;
  margin-left: auto;
  height: 100%;
}

.tab {
  display: flex;
  align-items: center;
  gap: 6px;
  border: 0;
  background: transparent;
  color: #d7ccc8;
  cursor: pointer;
  padding: 14px 14px 12px;
  font-size: 13px;
  border-bottom: 3px solid transparent;
  transition: background 0.15s, color 0.15s;
}

.tab:hover {
  color: #fff;
  background: rgba(255, 255, 255, 0.1);
}

.tab.is-on {
  color: #fff;
  border-bottom-color: #ffb74d;
  background: rgba(255, 255, 255, 0.14);
}

.tab-ico {
  font-size: 15px;
}

.today {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  line-height: 1.3;
  padding: 12px 0;
}

.today-date {
  font-size: 15px;
  font-weight: 700;
}

.today-week {
  font-size: 11px;
  opacity: 0.75;
}

/* ---------- 指标卡片条 ---------- */
.metrics {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
  padding: 12px 18px 0;
}

.metric {
  background: #fff;
  border: 1px solid var(--recy-line);
  border-left: 4px solid var(--el-color-primary-light-5);
  border-radius: 8px;
  padding: 10px 14px 11px;
}

.metric.is-main {
  border-left-color: var(--el-color-primary-dark-2);
  background: linear-gradient(135deg, #efebe9 0%, #fff 70%);
}

.metric-top {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--recy-ink-soft);
  font-size: 12px;
}

.metric-ico {
  font-size: 14px;
}

.metric-value {
  margin-top: 4px;
  font-size: 27px;
  font-weight: 700;
  line-height: 1.2;
  color: var(--recy-ink);
}

.metric-unit {
  margin-left: 4px;
  font-style: normal;
  font-size: 12px;
  font-weight: 400;
  color: var(--recy-ink-soft);
}

.metric-foot {
  margin-top: 3px;
  font-size: 11px;
  color: var(--recy-ink-soft);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.metric-foot.is-out {
  color: var(--recy-out);
}

.metric-foot.is-in {
  color: var(--recy-in);
}

/* ---------- 业务区 ---------- */
.stage {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

@media (max-width: 1180px) {
  .brand-text em,
  .today-week {
    display: none;
  }

  .metrics {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
</style>
