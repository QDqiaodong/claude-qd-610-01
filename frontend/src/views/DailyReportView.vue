<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">
        日结对账
        <em>JPQL 聚合报表：SELECT new DailyCategoryStats(...) + GROUP BY，不装任何图表库</em>
      </h2>
      <div class="page-tools">
        <el-button v-for="q in quick" :key="q.label" size="small" :type="q.from === range.from && q.to === range.to ? 'primary' : 'default'" plain @click="pick(q)">
          {{ q.label }}
        </el-button>
        <el-date-picker
          v-model="picker"
          type="daterange"
          size="small"
          value-format="YYYY-MM-DD"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          @change="onPickerChange"
        />
        <el-button size="small" type="primary" @click="query">查询</el-button>
      </div>
    </div>

    <!-- 日期汇总 -->
    <section class="panel">
      <h3 class="panel-title">按日期汇总 <span class="sql-badge">SUM / GROUP BY statDate</span></h3>
      <el-table :data="daily" border size="small" show-summary :summary-method="summaryOfDaily" empty-text="该区间没有数据">
        <el-table-column prop="statDate" label="日期" width="130" />
        <el-table-column label="回收量" align="right" width="140">
          <template #default="{ row }"><span class="num">{{ fmtWeight(row.totalWeight) }}</span></template>
        </el-table-column>
        <el-table-column label="回收支出（元）" align="right">
          <template #default="{ row }"><span class="num money-cell">{{ money(row.buyAmount) }}</span></template>
        </el-table-column>
        <el-table-column label="出售收入（元）" align="right">
          <template #default="{ row }"><span class="num income">{{ money(row.sellAmount) }}</span></template>
        </el-table-column>
        <el-table-column label="毛利（元）" align="right">
          <template #default="{ row }">
            <span class="num" :class="Number(row.grossProfit) >= 0 ? 'income' : 'money-cell'">{{ money(row.grossProfit) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="毛利率" align="right" width="100">
          <template #default="{ row }"><span class="num">{{ rate(row) }}%</span></template>
        </el-table-column>
        <el-table-column prop="ticketCount" label="磅单张数" align="right" width="100" />
      </el-table>
    </section>

    <!-- 纯 SVG 手绘横向条形图：按品类回收量排序 -->
    <section class="panel">
      <h3 class="panel-title">品类回收量排行 <span class="sql-badge">纯 SVG，无图表库</span></h3>
      <p v-if="!bars.length" class="muted">该区间没有可回收数据</p>
      <svg v-else :viewBox="`0 0 760 ${bars.length * 34 + 16}`" class="chart" role="img" aria-label="品类回收量排行">
        <defs>
          <linearGradient id="recyBar" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stop-color="#a1887f" />
            <stop offset="55%" stop-color="#795548" />
            <stop offset="100%" stop-color="#4e342e" />
          </linearGradient>
          <linearGradient id="recyTrack" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stop-color="#f1ebe7" />
            <stop offset="100%" stop-color="#e4dbd5" />
          </linearGradient>
        </defs>
        <!-- 纵向刻度线 -->
        <line v-for="tick in ticks" :key="tick.x" :x1="tick.x" :y1="6" :x2="tick.x" :y2="bars.length * 34 + 4"
              stroke="#e0d7d1" stroke-width="1" />
        <g v-for="(bar, index) in bars" :key="bar.categoryNo">
          <text :x="118" :y="index * 34 + 21" text-anchor="end" class="bar-label">{{ bar.categoryName }}</text>
          <rect :x="124" :y="index * 34 + 8" :width="trackWidth" height="16" rx="8" fill="url(#recyTrack)" />
          <rect :x="124" :y="index * 34 + 8" :width="bar.width" height="16" rx="8" fill="url(#recyBar)" />
          <text :x="124 + Math.max(bar.width, 46) + 8" :y="index * 34 + 21" class="bar-value">
            {{ fmtWeight(bar.totalWeight) }} {{ bar.unitName }}
          </text>
        </g>
      </svg>
      <p class="muted chart-note">
        条形长度按回收量等比缩放，单位不同的品类放在同一量纲下做相对比较；毛利偏低的品类下面表格里有具体金额。
      </p>
    </section>

    <div class="two">
      <!-- 明细：日期 + 品类 -->
      <section class="panel">
        <h3 class="panel-title">日期 × 品类明细 <span class="sql-badge">DailyCategoryStats</span></h3>
        <el-table :data="detail" border size="small" max-height="420" empty-text="该区间没有数据">
          <el-table-column prop="statDate" label="日期" width="110" />
          <el-table-column prop="categoryName" label="品类" width="120" />
          <el-table-column label="回收量" align="right" width="120">
            <template #default="{ row }">
              <span class="num">{{ fmtWeight(row.totalWeight) }} {{ unitText(row.unit) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="支出" align="right">
            <template #default="{ row }"><span class="num money-cell">{{ money(row.buyAmount) }}</span></template>
          </el-table-column>
          <el-table-column label="收入" align="right">
            <template #default="{ row }"><span class="num income">{{ money(row.sellAmount) }}</span></template>
          </el-table-column>
          <el-table-column label="毛利" align="right">
            <template #default="{ row }">
              <span class="num" :class="Number(row.grossProfit) >= 0 ? 'income' : 'money-cell'">{{ money(row.grossProfit) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="lineCount" label="行数" align="right" width="70" />
        </el-table>
      </section>

      <!-- 客户 TOP 榜 -->
      <section class="panel">
        <h3 class="panel-title">客户供货 TOP 榜 <span class="sql-badge">CustomerRankRow</span></h3>
        <ol class="rank">
          <li v-for="(row, index) in customers" :key="row.customerName">
            <span class="rank-no" :class="{ 'is-top': index < 3 }">{{ index + 1 }}</span>
            <span class="rank-name">
              {{ row.customerName }}
              <em>{{ row.customerType === 'MERCHANT' ? '商户' : '个人' }} · {{ row.ticketCount }} 单</em>
            </span>
            <span class="rank-bar">
              <i :style="{ width: barOf(row.totalWeight) + '%' }" />
            </span>
            <span class="num rank-val">{{ fmtWeight(row.totalWeight) }} 公斤</span>
            <span class="num rank-money">{{ money(row.totalAmount) }}</span>
          </li>
        </ol>
        <p v-if="!customers.length" class="muted">该区间没有数据</p>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { statsApi, isoDate, shiftDay, money, weight as fmtWeight } from '../api'

const today = isoDate(new Date())
const range = ref({ from: shiftDay(today, -6), to: today })
const picker = ref([range.value.from, range.value.to])

const daily = ref([])
const detail = ref([])
const categories = ref([])
const customers = ref([])

const quick = [
  { label: '今天', from: today, to: today },
  { label: '近 7 天', from: shiftDay(today, -6), to: today },
  { label: '近 30 天', from: shiftDay(today, -29), to: today }
]

const TRACK_MAX = 520

const maxWeight = computed(() => Math.max(1, ...categories.value.map((c) => Number(c.totalWeight || 0))))

const bars = computed(() =>
  categories.value.map((c) => ({
    categoryNo: c.categoryNo,
    categoryName: c.categoryName,
    unitName: c.unit === 'SET' ? '台' : '公斤',
    totalWeight: c.totalWeight,
    width: Math.max(6, Math.round((Number(c.totalWeight || 0) / maxWeight.value) * TRACK_MAX))
  }))
)

const trackWidth = TRACK_MAX

const ticks = computed(() => {
  const points = [0, 0.25, 0.5, 0.75, 1]
  return points.map((p) => ({ x: 124 + Math.round(p * TRACK_MAX) }))
})

const maxCustomerWeight = computed(() => Math.max(1, ...customers.value.map((c) => Number(c.totalWeight || 0))))

function barOf(value) {
  return Math.max(4, Math.round((Number(value || 0) / maxCustomerWeight.value) * 100))
}

function unitText(unit) {
  return unit === 'SET' ? '台' : '公斤'
}

function rate(row) {
  const sell = Number(row.sellAmount || 0)
  const profit = Number(row.grossProfit || 0)
  return sell > 0 ? ((profit / sell) * 100).toFixed(1) : '0.0'
}

function pick(item) {
  range.value = { from: item.from, to: item.to }
  picker.value = [item.from, item.to]
  query()
}

function summaryOfDaily({ columns }) {
  const sum = (key) => daily.value.reduce((acc, row) => acc + Number(row[key] || 0), 0)
  return [
    '合计',
    '',
    fmtWeight(sum('totalWeight')),
    money(sum('buyAmount')),
    money(sum('sellAmount')),
    money(sum('grossProfit')),
    rate({ sellAmount: sum('sellAmount'), grossProfit: sum('grossProfit') }) + '%',
    daily.value.reduce((acc, row) => acc + Number(row.ticketCount || 0), 0)
  ]
}

async function query() {
  const { from, to } = range.value
  const board = await statsApi.board(from, to, 8)
  daily.value = board.daily || []
  detail.value = board.rows || []
  categories.value = board.categories || []
  customers.value = board.customers || []
}

function onPickerChange(value) {
  if (!value) return
  range.value = { from: value[0], to: value[1] }
  query()
}

onMounted(query)
</script>

<style scoped>
.num {
  font-variant-numeric: tabular-nums;
}

.money-cell {
  color: var(--recy-money);
}

.income {
  color: var(--recy-in);
}

.chart {
  width: 100%;
  height: auto;
}

.bar-label {
  font-size: 12px;
  fill: #5d4037;
}

.bar-value {
  font-size: 11px;
  fill: #6d4c41;
  font-variant-numeric: tabular-nums;
}

.chart-note {
  margin: 6px 0 0;
}

.two {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
  gap: 14px;
  align-items: start;
  margin-top: 14px;
}

/* 客户 TOP 榜 */
.rank {
  list-style: none;
  margin: 0;
  padding: 0;
}

.rank li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 0;
  border-bottom: 1px dashed #efe7e2;
  font-size: 12.5px;
}

.rank-no {
  width: 20px;
  height: 20px;
  flex: 0 0 20px;
  border-radius: 50%;
  background: #efebe9;
  color: #6d4c41;
  font-size: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.rank-no.is-top {
  background: var(--el-color-primary);
  color: #fff;
}

.rank-name {
  width: 132px;
  flex: 0 0 132px;
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}

.rank-name em {
  font-style: normal;
  font-size: 10.5px;
  color: var(--recy-ink-soft);
}

.rank-bar {
  flex: 1;
  height: 10px;
  border-radius: 5px;
  background: #f1ebe7;
  overflow: hidden;
}

.rank-bar i {
  display: block;
  height: 100%;
  border-radius: 5px;
  background: linear-gradient(90deg, #a1887f, #5d4037);
}

.rank-val {
  width: 96px;
  text-align: right;
}

.rank-money {
  width: 96px;
  text-align: right;
  color: var(--recy-money);
}

@media (max-width: 1180px) {
  .two {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
