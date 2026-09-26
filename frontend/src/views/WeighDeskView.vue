<template>
  <div class="page">
    <div class="head page-head">
      <h2 class="page-title">
        过磅台
        <em>毛重 - 皮重 = 净重；净重按品类拆成明细行，一行一个品类</em>
      </h2>
      <div class="page-tools">
        <el-button size="small" @click="reload">刷新</el-button>
      </div>
    </div>

    <div class="desk">
      <!-- 左：开票区 -->
      <section class="panel">
        <h3 class="panel-title">磅单录入 <span class="sql-badge">POST /api/tickets</span></h3>
        <p class="rule-tip">
          明细行的单价一律按当日牌价在后端重算；明细重量合计不能超过净重，超出部分必须先在站里扣杂。
        </p>

        <el-form :model="form" label-width="76px" size="small" class="ticket-form">
          <el-form-item label="客户">
            <el-input v-model="form.customerName" placeholder="个人或商户名称" clearable />
          </el-form-item>
          <el-form-item label="客户类型">
            <el-radio-group v-model="form.customerType">
              <el-radio-button value="PERSONAL">个人</el-radio-button>
              <el-radio-button value="MERCHANT">商户</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="车牌">
            <el-select v-model="form.plateNo" filterable allow-create clearable placeholder="选车或直接录入散户" class="grow">
              <el-option v-for="v in vehicles" :key="v.plateNo" :label="v.plateNo + ' · ' + v.driverName" :value="v.plateNo" />
            </el-select>
          </el-form-item>
          <el-form-item label="过磅时间">
            <el-date-picker
              v-model="form.weighTime"
              type="datetime"
              value-format="YYYY-MM-DD HH:mm:ss"
              format="YYYY-MM-DD HH:mm"
              placeholder="过磅时间"
              class="grow"
            />
          </el-form-item>
          <el-form-item label="毛重">
            <el-input-number v-model="form.grossWeight" :min="0" :precision="2" :step="10" controls-position="right" class="grow" />
          </el-form-item>
          <el-form-item label="皮重">
            <el-input-number v-model="form.tareWeight" :min="0" :precision="2" :step="10" controls-position="right" class="grow" />
          </el-form-item>
          <el-form-item label="净重">
            <div class="net-box num">
              {{ fmtWeight(netWeight) }}
              <i>公斤</i>
              <span class="net-diff" :class="{ 'is-over': linesOver }">
                明细合计 {{ fmtWeight(linesWeight) }} {{ unitLabel }}
              </span>
            </div>
          </el-form-item>
        </el-form>

        <!-- 明细行：行内可编辑表格 -->
        <div class="lines">
          <table class="grid-table">
            <thead>
              <tr>
                <th style="width: 44px">行</th>
                <th>品类</th>
                <th style="width: 110px">回收单价</th>
                <th style="width: 150px">重量</th>
                <th style="width: 110px">小计</th>
                <th style="width: 56px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="!lines.length" class="is-empty">
                <td colspan="6">还没有明细行，点下面「加一行」开始录</td>
              </tr>
              <tr v-for="(line, index) in lines" :key="index">
                <td class="num">{{ index + 1 }}</td>
                <td>
                  <el-select
                    v-model="line.categoryNo"
                    placeholder="选品类"
                    size="small"
                    filterable
                    class="grow"
                    @change="onCategoryChange(line)"
                  >
                    <el-option
                      v-for="c in categories"
                      :key="c.categoryNo"
                      :label="`${c.categoryName}（${unitText(c.unit)} ${money(c.buyPrice)}）`"
                      :value="c.categoryNo"
                    />
                  </el-select>
                </td>
                <td class="num">{{ money(line.unitPrice) }} 元</td>
                <td>
                  <el-input-number
                    v-model="line.weight"
                    :min="0"
                    :precision="2"
                    :step="10"
                    size="small"
                    controls-position="right"
                    class="grow"
                  />
                  <span class="unit-tag">{{ line.unit ? unitText(line.unit) : '' }}</span>
                </td>
                <td class="num money-cell">{{ money(lineSubtotal(line)) }}</td>
                <td>
                  <el-button size="small" type="danger" plain @click="removeLine(index)">删</el-button>
                </td>
              </tr>
            </tbody>
            <tfoot>
              <tr>
                <td colspan="4">合计（{{ lines.length }} 行）</td>
                <td class="num money-cell">{{ money(linesAmount) }}</td>
                <td />
              </tr>
            </tfoot>
          </table>
        </div>

        <div class="lines-foot">
          <div class="foot-left">
            <el-button size="small" @click="addLine" :disabled="lines.length >= 8">加一行</el-button>
            <el-button size="small" plain @click="clearLines">清空明细</el-button>
            <span class="muted">最多 8 行</span>
          </div>
          <div class="foot-right">
            <span class="muted">本单应付</span>
            <strong class="num pay">{{ money(linesAmount) }}</strong>
            <el-button type="primary" size="small" :loading="submitting" @click="submit">提交过磅单</el-button>
          </div>
        </div>
      </section>

      <!-- 右：最近磅单 -->
      <section class="panel list-panel">
        <h3 class="panel-title">最近磅单 <span class="sql-badge">GET /api/tickets</span></h3>
        <el-scrollbar max-height="620px">
          <div v-if="!tickets.length" class="muted empty">暂无磅单</div>
          <article v-for="t in tickets" :key="t.ticketNo" class="ticket-card" :class="['status-' + t.status.toLowerCase(), { 'is-frozen': t.frozen }]">
            <header>
              <span class="no num">{{ t.ticketNo }}</span>
              <span class="tag" :class="'tag-' + t.status.toLowerCase()">{{ t.statusName }}</span>
            </header>
            <div class="row">
              <span class="who">{{ t.customerName }}</span>
              <span class="muted">{{ t.customerTypeName }} · {{ t.plateNo || '散户自送' }}</span>
            </div>
            <div class="row">
              <span class="muted">{{ t.weighTime }}</span>
              <el-tag v-if="t.activeVersionNo > 1" size="small" type="warning">有效 V{{ t.activeVersionNo }}（复称后）</el-tag>
            </div>

            <!-- 复称冻结横幅：冻结原因 + 复称单号，过磅人员一眼看到 -->
            <div v-if="t.frozen" class="freeze-strip" @click="goReweigh(t)">
              🔒 {{ t.frozenReason }}
              <el-button size="small" text type="warning">去处理 →</el-button>
            </div>

            <table class="mini-lines">
              <tr v-for="l in t.lines" :key="l.lineNo">
                <td>{{ l.categoryName }}</td>
                <td class="num">{{ fmtWeight(l.weight) }} {{ l.unitName }}</td>
                <td class="num">{{ money(l.subtotal) }}</td>
              </tr>
            </table>
            <footer>
              <span class="num">原净重 {{ fmtWeight(t.netWeight) }}</span>
              <span class="num" v-if="t.activeVersionNo > 1">有效净重 {{ fmtWeight(t.effectiveNetWeight) }}</span>
              <span class="num pay">
                {{ t.activeVersionNo > 1 ? '有效应付 ' : '应付 ' }}{{ money(t.activeVersionNo > 1 ? t.effectiveTotalAmount : t.totalAmount) }}
              </span>
              <span class="acts">
                <el-button
                  v-if="t.status === 'PENDING' && !t.frozen"
                  size="small"
                  type="warning"
                  plain
                  @click="goReweigh(t)"
                >
                  发起复称
                </el-button>
                <el-button
                  v-for="opt in allowedNext(t)"
                  :key="opt.value"
                  size="small"
                  :type="opt.value === 'SETTLED' ? 'primary' : 'danger'"
                  plain
                  @click="switchStatus(t, opt.value)"
                >
                  {{ opt.label }}
                </el-button>
              </span>
            </footer>
          </article>
        </el-scrollbar>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { categoryApi, ticketApi, vehicleApi, money, weight as fmtWeight } from '../api'

const router = useRouter()
const categories = ref([])
const vehicles = ref([])
const tickets = ref([])
const submitting = ref(false)

/** 复称冻结中的磅单不允许从过磅台直接置为已结算 / 作废（后端也会拦） */
function allowedNext(t) {
  if (t.frozen) return []
  return t.nextStatuses || []
}

/** 跳到复称复核台并带上磅单号（复称台据此预开发起弹窗由用户确认） */
function goReweigh(t) {
  router.push({ path: '/reweigh', query: t.frozen ? { ticketNo: t.ticketNo, reweighNo: t.frozenReweighNo } : { initiate: t.ticketNo } })
}

const form = reactive({
  customerName: '',
  customerType: 'PERSONAL',
  plateNo: '',
  weighTime: '',
  grossWeight: 0,
  tareWeight: 0
})

const lines = ref([])

const netWeight = computed(() => Math.max(0, Number(form.grossWeight || 0) - Number(form.tareWeight || 0)))

const linesWeight = computed(() => lines.value.reduce((sum, l) => sum + Number(l.weight || 0), 0))

const linesAmount = computed(() => lines.value.reduce((sum, l) => sum + lineSubtotal(l), 0))

const linesOver = computed(() => linesWeight.value > netWeight.value + 0.5)

const unitLabel = computed(() => {
  const units = [...new Set(lines.value.filter((l) => l.unit).map((l) => l.unit))]
  if (!units.length) return '公斤'
  if (units.length > 1) return '混合单位'
  return units[0] === 'SET' ? '台' : '公斤'
})

function unitText(unit) {
  return unit === 'SET' ? '台' : '公斤'
}

function lineSubtotal(line) {
  return Number(line.weight || 0) * Number(line.unitPrice || 0)
}

function addLine() {
  if (lines.value.length >= 8) {
    ElMessage.warning('一张磅单最多 8 行明细')
    return
  }
  lines.value.push({ categoryNo: '', unit: 'KG', unitPrice: 0, weight: 0 })
}

function removeLine(index) {
  lines.value.splice(index, 1)
}

function clearLines() {
  lines.value = []
}

function onCategoryChange(line) {
  const hit = categories.value.find((c) => c.categoryNo === line.categoryNo)
  if (hit) {
    line.unitPrice = Number(hit.buyPrice)
    line.unit = hit.unit
  }
}

async function submit() {
  if (!form.customerName) {
    ElMessage.warning('先填客户名称')
    return
  }
  if (!form.grossWeight || Number(form.grossWeight) <= 0) {
    ElMessage.warning('毛重必须大于 0')
    return
  }
  if (Number(form.grossWeight) <= Number(form.tareWeight)) {
    ElMessage.warning('毛重必须大于皮重')
    return
  }
  const detail = lines.value.filter((l) => l.categoryNo && Number(l.weight || 0) > 0)
  if (!detail.length) {
    ElMessage.warning('至少录入一行明细：品类 + 重量')
    return
  }
  if (linesOver.value) {
    ElMessage.warning('明细重量合计超过净重，请先扣杂再提交')
    return
  }
  submitting.value = true
  try {
    await ticketApi.create({
      customerName: form.customerName,
      customerType: form.customerType,
      weighTime: form.weighTime || undefined,
      plateNo: form.plateNo,
      grossWeight: Number(form.grossWeight),
      tareWeight: Number(form.tareWeight),
      lines: detail.map((l) => ({ categoryNo: l.categoryNo, weight: Number(l.weight), unitPrice: Number(l.unitPrice) }))
    })
    ElMessage.success('过磅单已生成')
    form.customerName = ''
    form.plateNo = ''
    form.grossWeight = 0
    form.tareWeight = 0
    lines.value = []
    await reload()
  } catch (e) {
    // 业务提示已由 axios 拦截器弹出
  } finally {
    submitting.value = false
  }
}

async function switchStatus(ticket, status) {
  try {
    await ticketApi.changeStatus(ticket.ticketNo, status)
    ElMessage.success(`磅单 ${ticket.ticketNo} 已${status === 'VOID' ? '作废' : '结算'}`)
    await reload()
  } catch (e) {
    // ignored
  }
}

async function reload() {
  tickets.value = await ticketApi.list()
}

onMounted(async () => {
  categories.value = await categoryApi.options()
  vehicles.value = await vehicleApi.list()
  await reload()
  if (!form.weighTime) {
    const now = new Date()
    const pad = (n) => String(n).padStart(2, '0')
    form.weighTime = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}:00`
  }
  addLine()
})
</script>

<style scoped>
.desk {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}

.grow {
  width: 100%;
}

.ticket-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}

.ticket-form :deep(.el-form-item) {
  margin-bottom: 10px;
}

.net-box {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 3px 10px;
  background: #efebe9;
  border: 1px solid var(--recy-line);
  border-radius: 6px;
  font-size: 18px;
  font-weight: 700;
  color: var(--el-color-primary-dark-2);
}

.net-box i {
  font-style: normal;
  font-size: 12px;
  font-weight: 400;
  color: var(--recy-ink-soft);
}

.net-diff {
  margin-left: auto;
  font-size: 11px;
  font-weight: 400;
  color: var(--recy-ink-soft);
}

.net-diff.is-over {
  color: var(--recy-out);
}

.lines {
  margin-top: 4px;
  border: 1px solid var(--recy-line);
  border-radius: 8px;
  overflow: hidden;
}

.money-cell {
  color: var(--recy-money);
  font-weight: 600;
}

.unit-tag {
  margin-left: 6px;
  font-size: 11px;
  color: var(--recy-ink-soft);
}

.lines-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
  flex-wrap: wrap;
}

.foot-left,
.foot-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pay {
  color: var(--recy-money);
  font-size: 15px;
}

/* ---------- 右侧磅单列表 ---------- */
.list-panel {
  min-width: 0;
}

.empty {
  padding: 20px;
  text-align: center;
}

.ticket-card {
  border: 1px solid var(--recy-line);
  border-left: 4px solid var(--el-color-primary);
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 10px;
  background: #fffdfc;
}

.ticket-card.status-settled {
  border-left-color: #00897b;
}

.ticket-card.status-void {
  border-left-color: #bdbdbd;
  background: #fafafa;
  opacity: 0.86;
}

.ticket-card.is-frozen {
  background: #fffaf2;
  box-shadow: inset 0 0 0 1px #ffe0b2;
}

.freeze-strip {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 6px 0;
  padding: 5px 8px;
  background: #fff3e0;
  border: 1px solid #ffcc80;
  border-radius: 6px;
  color: #e65100;
  font-size: 11.5px;
  cursor: pointer;
}

.freeze-strip .el-button {
  margin-left: auto;
}

.ticket-card header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}

.ticket-card .no {
  font-size: 13px;
  font-weight: 700;
}

.tag {
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 999px;
  background: #efebe9;
  color: #6d4c41;
  border: 1px solid #d7ccc8;
}

.tag-settled {
  background: #e0f2f1;
  color: #00695c;
  border-color: #b2dfdb;
}

.tag-void {
  background: #eeeeee;
  color: #757575;
}

.ticket-card .row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
  margin-bottom: 2px;
}

.ticket-card .who {
  font-weight: 600;
}

.mini-lines {
  width: 100%;
  border-collapse: collapse;
  font-size: 11.5px;
  margin: 6px 0;
}

.mini-lines td {
  padding: 2px 0;
  border-top: 1px dashed #efe7e2;
  color: #6d4c41;
}

.mini-lines td:nth-child(2),
.mini-lines td:nth-child(3) {
  text-align: right;
}

.ticket-card footer {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  border-top: 1px dashed #efe7e2;
  padding-top: 6px;
}

.ticket-card footer .acts {
  margin-left: auto;
  display: flex;
  gap: 4px;
}

@media (max-width: 1240px) {
  .desk {
    grid-template-columns: minmax(0, 1fr);
  }

  .ticket-form {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
