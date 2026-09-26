<template>
  <div class="page">
    <div class="head page-head">
      <h2 class="page-title">
        复称复核台
        <em>发起 → 补录 → 现场放行 / 复核 → 恢复结算；复称期间结算全程冻结</em>
      </h2>
      <div class="page-tools">
        <el-button size="small" @click="reload">刷新</el-button>
      </div>
    </div>

    <div class="desk">
      <!-- 左：复称队列 -->
      <section class="panel queue-panel">
        <h3 class="panel-title">
          复称任务队列
          <span class="sql-badge">同一磅单任一时刻仅一条未结束复称</span>
        </h3>
        <el-input v-model="filter" size="small" placeholder="按磅单号 / 客户 / 复称单号过滤" clearable class="filter">
          <template #prefix>🔎</template>
        </el-input>
        <el-scrollbar max-height="640px">
          <div v-if="!filteredOrders.length" class="muted empty">暂无复称记录</div>
          <article
            v-for="o in filteredOrders"
            :key="o.reweighNo"
            class="rw-card"
            :class="[`st-${o.status.toLowerCase()}`, { 'is-open': o.open, 'is-current': current && current.reweighNo === o.reweighNo }]"
            @click="select(o)"
          >
            <header>
              <span class="no num">{{ o.reweighNo }}</span>
              <span class="tag" :class="`tag-${o.status.toLowerCase()}`">{{ o.statusName }}</span>
            </header>
            <div class="row"><span class="who">{{ o.customerName }}</span><span class="muted num">{{ o.ticketNo }}</span></div>
            <div class="row muted">
              <span>原净重 {{ fmtWeight(o.originNetWeight) }} → 复称 {{ o.reNetWeight != null ? fmtWeight(o.reNetWeight) : '待补录' }}</span>
            </div>
            <div class="row" v-if="o.open">
              <span class="owner">👤 当前责任人：{{ o.currentOwner }}</span>
            </div>
            <div class="row muted small">
              <span>发起人 {{ o.initiator }} · {{ o.initiatedAt }}</span>
            </div>
          </article>
        </el-scrollbar>
      </section>

      <!-- 右：处理区 -->
      <section class="panel detail-panel">
        <div v-if="!current" class="muted empty big">
          <p>左侧选择一条复称记录查看处理链</p>
          <p class="small">或在下面按磅单号发起新的复称</p>
          <el-button type="primary" size="small" @click="openInitiate">发起复称</el-button>
        </div>

        <template v-else>
          <div class="detail-head">
            <div>
              <h3 class="panel-title" style="margin:0">
                {{ current.reweighNo }}
                <span class="tag" :class="`tag-${current.status.toLowerCase()}`">{{ current.statusName }}</span>
              </h3>
              <div class="muted small" style="margin-top:4px">
                磅单 <b class="num">{{ current.ticketNo }}</b> · {{ current.customerName }}（{{ current.customerTypeName }}）· {{ current.plateNo || '散户自送' }}
              </div>
            </div>
            <div class="head-acts">
              <el-button v-if="canInitiateNew" size="small" type="primary" plain @click="openInitiate(current.ticketNo)">对该单再次复称</el-button>
              <el-button size="small" @click="refreshCurrent">刷新详情</el-button>
            </div>
          </div>

          <!-- 冻结横幅：过磅人员必须看见冻结原因与当前责任人 -->
          <el-alert
            v-if="current.open"
            type="warning"
            :closable="false"
            show-icon
            class="freeze-banner"
            :title="`结算冻结中 · 当前责任人：${current.currentOwner}（${current.currentOwnerRole}）`"
            :description="`复称未结束前，该磅单不能付款、不能作废。发起人：${current.initiator}，发起时间：${current.initiatedAt}`"
          />
          <el-alert
            v-else
            type="success"
            :closable="false"
            show-icon
            class="freeze-banner"
            :title="closedBannerTitle"
            :description="`已采用 V${current.adoptedVersionNo || 1} 有效金额版本，磅单恢复结算资格`"
          />

          <!-- 重量对比 + 差异判定 -->
          <div class="weight-grid">
            <div class="wbox">
              <div class="wbox-title">原建档（V1 快照，不可覆盖）</div>
              <div class="wline"><span>毛重</span><b class="num">{{ fmtWeight(current.originGrossWeight) }}</b></div>
              <div class="wline"><span>皮重</span><b class="num">{{ fmtWeight(current.originTareWeight) }}</b></div>
              <div class="wline"><span>净重</span><b class="num">{{ fmtWeight(current.originNetWeight) }}</b></div>
              <div class="wline"><span>原金额</span><b class="num pay">{{ money(current.originTotalAmount) }}</b></div>
            </div>
            <div class="warrow">→</div>
            <div class="wbox" :class="{ pending: current.reNetWeight == null }">
              <div class="wbox-title">复称后</div>
              <div class="wline"><span>毛重</span><b class="num">{{ current.reGrossWeight != null ? fmtWeight(current.reGrossWeight) : '—' }}</b></div>
              <div class="wline"><span>皮重</span><b class="num">{{ current.reTareWeight != null ? fmtWeight(current.reTareWeight) : '—' }}</b></div>
              <div class="wline"><span>净重</span><b class="num">{{ current.reNetWeight != null ? fmtWeight(current.reNetWeight) : '待补录' }}</b></div>
              <div class="wline">
                <span>差异</span>
                <b class="num" :class="diffClass">{{ current.diffNetWeight != null ? fmtDiff(current.diffNetWeight) : '—' }}</b>
              </div>
            </div>
            <div class="threshold">
              <div class="wbox-title">放行阈值（双条件同时满足）</div>
              <p>净重差异 ≤ 2 公斤</p>
              <p>且 ≤ 原净重的 5‰（≤ {{ fmtWeight(thresholdRatioCap) }} 公斤）</p>
              <el-tag v-if="current.diffWithinThreshold === true" type="success" size="small">阈值内 · 可现场放行</el-tag>
              <el-tag v-else-if="current.diffWithinThreshold === false" type="danger" size="small">超出阈值 · 必须待复核</el-tag>
              <el-tag v-else type="info" size="small">补录后判定</el-tag>
            </div>
          </div>

          <div v-if="current.diffReason" class="reason-box">差异原因：{{ current.diffReason }}</div>
          <div v-if="current.status === 'rejected'" class="reason-box reject">驳回原因：{{ current.rejectReason }}</div>

          <!-- 明细重分配（补录） -->
          <div v-if="showSupplement" class="lines-block">
            <h4>补录复称重量 + 明细重新分配（单价沿用原单快照，不套现行牌价）</h4>
            <el-form :model="suppForm" label-width="76px" size="small" class="supp-form">
              <el-form-item label="复称毛重">
                <el-input-number v-model="suppForm.reGrossWeight" :min="0" :precision="2" :step="10" controls-position="right" />
              </el-form-item>
              <el-form-item label="复称皮重">
                <el-input-number v-model="suppForm.reTareWeight" :min="0" :precision="2" :step="10" controls-position="right" />
              </el-form-item>
              <el-form-item label="复称净重">
                <span class="num net-read">{{ suppNet != null ? fmtWeight(suppNet) : '—' }}</span>
                <span class="muted small">（= 毛重 - 皮重）</span>
              </el-form-item>
              <el-form-item label="差异原因">
                <el-input v-model="suppForm.diffReason" placeholder="如：扣杂争议，纸塑含水扣 80kg" maxlength="200" />
              </el-form-item>
            </el-form>
            <table class="grid-table">
              <thead>
                <tr><th style="width:44px">行</th><th>品类</th><th style="width:90px">单位</th><th style="width:110px">原数量</th><th style="width:110px">原单价</th><th style="width:170px">重分配数量</th><th style="width:110px">重算小计</th></tr>
              </thead>
              <tbody>
                <tr v-for="l in suppLines" :key="l.lineNo">
                  <td class="num">{{ l.lineNo }}</td>
                  <td>{{ l.categoryName }}</td>
                  <td>{{ l.unitName }}</td>
                  <td class="num">{{ fmtWeight(l.originWeight) }}</td>
                  <td class="num">{{ money(l.unitPrice) }}</td>
                  <td>
                    <el-input-number v-model="l.newWeight" :min="0" :precision="2" :step="10" size="small" controls-position="right" class="grow" />
                  </td>
                  <td class="num pay">{{ money((Number(l.newWeight) || 0) * Number(l.unitPrice)) }}</td>
                </tr>
              </tbody>
              <tfoot>
                <tr>
                  <td colspan="3">合计</td>
                  <td class="num">{{ fmtWeight(suppOriginTotal) }}</td>
                  <td></td>
                  <td class="num" :class="{ over: suppOver }">{{ fmtWeight(suppNewTotal) }} / 净重 {{ suppNet != null ? fmtWeight(suppNet) : '—' }}</td>
                  <td class="num pay">{{ money(suppAmount) }}</td>
                </tr>
              </tfoot>
            </table>
            <div class="act-row">
              <span v-if="suppOver" class="warn-text">重分配合计超过复称净重，请核对扣杂</span>
              <el-button type="primary" size="small" :loading="acting" @click="submitSupplement">提交补录</el-button>
            </div>
          </div>

          <!-- 已补录待复核 / 已终审：展示原数量快照与重分配/采用结果 -->
          <div v-else-if="current.lines && current.lines.length" class="lines-block">
            <h4>{{ current.status === 'AWAIT_REVIEW' ? '已补录的复称数据与重分配方案（待复核）' : '明细快照与重分配结果' }}</h4>
            <table class="grid-table">
              <thead>
                <tr><th style="width:44px">行</th><th>品类</th><th style="width:90px">单位</th><th style="width:120px">原数量</th><th style="width:110px">单价快照</th><th style="width:120px">采用数量</th><th style="width:120px">小计</th></tr>
              </thead>
              <tbody>
                <tr v-for="l in current.lines" :key="l.lineNo">
                  <td class="num">{{ l.lineNo }}</td>
                  <td>{{ l.categoryName }}</td>
                  <td>{{ l.unitName }}</td>
                  <td class="num muted">{{ fmtWeight(l.originWeight) }}</td>
                  <td class="num">{{ money(l.unitPrice) }}</td>
                  <td class="num">{{ l.newWeight != null ? fmtWeight(l.newWeight) : fmtWeight(l.originWeight) }}</td>
                  <td class="num pay">{{ l.subtotal != null ? money(l.subtotal) : money(l.originWeight * l.unitPrice) }}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <!-- 操作区 -->
          <div class="ops">
            <!-- 待现场放行：另一名当班人员 -->
            <template v-if="current.status === 'AWAIT_RELEASE'">
              <el-divider content-position="left">现场放行（须发起人以外的另一名当班人员）</el-divider>
              <div class="op-line">
                <span>当班确认人</span>
                <el-input v-model="actorName" size="small" placeholder="输入当班人员姓名" style="width:180px" />
                <el-button type="success" size="small" :loading="acting" @click="doRelease">确认现场放行，采用新版本并恢复结算</el-button>
              </div>
              <p class="muted small">若发现差异有争议，也可升级为复核：<el-button size="small" text type="warning" @click="escalate">升级为待复核</el-button></p>
            </template>

            <!-- 待复核 -->
            <template v-if="current.status === 'AWAIT_REVIEW'">
              <el-divider content-position="left">复核（复核人不得是发起人 {{ current.initiator }}）</el-divider>
              <div class="op-line">
                <span>复核人</span>
                <el-input v-model="actorName" size="small" placeholder="输入复核人姓名" style="width:180px" />
              </div>
              <div class="op-line top">
                <span>驳回原因</span>
                <el-input v-model="reviewRejectReason" type="textarea" :rows="2" placeholder="驳回时必填" style="width:420px" maxlength="200" />
              </div>
              <div class="op-line">
                <el-button type="primary" size="small" :loading="acting" @click="doReview('APPROVED')">复核通过（生成新版本，恢复结算）</el-button>
                <el-button type="danger" plain size="small" :loading="acting" @click="doReview('REJECTED')">复核驳回（沿用原重量原金额，恢复结算）</el-button>
              </div>
            </template>
          </div>

          <!-- 状态轨迹 -->
          <div class="timeline-block">
            <h4>完整状态轨迹</h4>
            <el-timeline>
              <el-timeline-item
                v-for="e in current.events"
                :key="e.id"
                :timestamp="`${e.createdAt} · ${e.operator}`"
                :type="timelineType(e.eventType)"
              >
                <b>{{ e.eventName }}</b>
                <span v-if="e.fromStatusName || e.toStatusName" class="muted small">
                  （{{ e.fromStatusName || '—' }} → {{ e.toStatusName || '冻结解除' }}）
                </span>
                <div class="muted small">{{ e.note }}</div>
              </el-timeline-item>
            </el-timeline>
          </div>
        </template>
      </section>
    </div>

    <!-- 发起复称弹窗 -->
    <el-dialog v-model="initVisible" title="发起复称" width="520px">
      <el-form :model="initForm" label-width="92px" size="small">
        <el-form-item label="磅单号">
          <el-input v-model="initForm.ticketNo" placeholder="如 WB20260926001" />
        </el-form-item>
        <el-form-item label="发起人">
          <el-input v-model="initForm.initiator" placeholder="过磅人员姓名" />
        </el-form-item>
        <el-form-item label="差异原因">
          <el-input v-model="initForm.diffReason" type="textarea" :rows="2" placeholder="如：客户对扣杂有异议，车辆回皮复称" maxlength="200" />
        </el-form-item>
        <el-form-item label="复称毛重">
          <el-input-number v-model="initForm.reGrossWeight" :min="0" :precision="2" :step="10" controls-position="right" />
          <span class="muted small unit">选填，可先发起后补录</span>
        </el-form-item>
        <el-form-item label="复称皮重">
          <el-input-number v-model="initForm.reTareWeight" :min="0" :precision="2" :step="10" controls-position="right" />
        </el-form-item>
      </el-form>
      <p class="muted small">提交后该磅单立即进入结算冻结，付款台无法结算；只有待结算磅单可发起。</p>
      <template #footer>
        <el-button size="small" @click="initVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="acting" @click="submitInitiate">发起复称</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { reweighApi, money, weight as fmtWeight } from '../api'

const route = useRoute()
const orders = ref([])
const current = ref(null)
const filter = ref('')
const acting = ref(false)
const actorName = ref('')
const reviewRejectReason = ref('')

const initVisible = ref(false)
const initForm = reactive({ ticketNo: '', initiator: '', diffReason: '', reGrossWeight: null, reTareWeight: null })

const suppForm = reactive({ reGrossWeight: 0, reTareWeight: 0, diffReason: '' })
const suppLines = ref([])

function genRequestId() {
  return `rw-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

const filteredOrders = computed(() => {
  const kw = filter.value.trim().toLowerCase()
  if (!kw) return orders.value
  return orders.value.filter((o) =>
    [o.ticketNo, o.customerName, o.reweighNo, o.initiator].some((v) => String(v || '').toLowerCase().includes(kw)))
})

const canInitiateNew = computed(() => current.value && !current.value.open)

const showSupplement = computed(() =>
  current.value && ['INITIATED', 'AWAIT_RELEASE'].includes(current.value.status)
    && current.value.open)

const suppNet = computed(() => {
  const g = Number(suppForm.reGrossWeight || 0)
  const t = Number(suppForm.reTareWeight || 0)
  return g > 0 && g > t ? Math.round((g - t) * 100) / 100 : null
})

const suppNewTotal = computed(() => suppLines.value.reduce((s, l) => s + (Number(l.newWeight) || 0), 0))
const suppOriginTotal = computed(() => current.value ? current.value.lines.reduce((s, l) => s + Number(l.originWeight || 0), 0) : 0)
const suppAmount = computed(() =>
  suppLines.value.reduce((s, l) => s + (Number(l.newWeight) || 0) * Number(l.unitPrice || 0), 0))
const suppOver = computed(() => suppNet.value != null && suppNewTotal.value > suppNet.value + 0.5)

const thresholdRatioCap = computed(() =>
  current.value ? Math.round(Number(current.value.originNetWeight) * 0.005 * 100) / 100 : 0)

const diffClass = computed(() => {
  const d = Number(current.value?.diffNetWeight || 0)
  if (d === 0) return ''
  return d < 0 ? 'diff-down' : 'diff-up'
})

const closedBannerTitle = computed(() => {
  if (!current.value) return ''
  if (current.value.status === 'RELEASED') return `现场放行 · 放行人 ${current.value.releaser}`
  if (current.value.status === 'APPROVED') return `复核通过 · 复核人 ${current.value.reviewer}`
  return `复核驳回 · 复核人 ${current.value.reviewer}（沿用原版本）`
})

function fmtDiff(v) {
  const n = Number(v || 0)
  return `${n > 0 ? '+' : ''}${fmtWeight(n)}`
}

function timelineType(type) {
  return {
    INITIATED: 'primary',
    SUPPLEMENTED: 'primary',
    RELEASED: 'success',
    APPROVED: 'success',
    REJECTED: 'danger',
    SETTLEMENT_FROZEN: 'warning',
    SETTLEMENT_RESUMED: 'success'
  }[type] || ''
}

async function reload(selectNo) {
  orders.value = await reweighApi.board()
  const want = selectNo || current.value?.reweighNo
  if (want) {
    const hit = orders.value.find((o) => o.reweighNo === want)
    if (hit) current.value = hit
  }
}

async function select(o) {
  try {
    current.value = await reweighApi.detail(o.reweighNo)
    hydrateSupplement(current.value)
  } catch (e) {
    current.value = o
  }
}

async function refreshCurrent() {
  if (!current.value) return
  current.value = await reweighApi.detail(current.value.reweighNo)
  hydrateSupplement(current.value)
}

function hydrateSupplement(o) {
  actorName.value = ''
  reviewRejectReason.value = ''
  suppForm.reGrossWeight = o.reGrossWeight != null ? Number(o.reGrossWeight) : 0
  suppForm.reTareWeight = o.reTareWeight != null ? Number(o.reTareWeight) : 0
  suppForm.diffReason = o.diffReason || ''
  suppLines.value = o.lines.map((l) => ({
    lineNo: l.lineNo,
    categoryName: l.categoryName,
    unitName: l.unitName,
    originWeight: l.originWeight,
    unitPrice: l.unitPrice,
    newWeight: l.newWeight != null ? Number(l.newWeight) : Number(l.originWeight)
  }))
}

function openInitiate(ticketNo) {
  initForm.ticketNo = ticketNo || ''
  initForm.initiator = ''
  initForm.diffReason = ''
  initForm.reGrossWeight = null
  initForm.reTareWeight = null
  initVisible.value = true
}

async function submitInitiate() {
  if (!initForm.ticketNo.trim()) return ElMessage.warning('请填写磅单号')
  if (!initForm.initiator.trim()) return ElMessage.warning('请填写发起人')
  acting.value = true
  try {
    const payload = {
      ticketNo: initForm.ticketNo.trim(),
      initiator: initForm.initiator.trim(),
      diffReason: initForm.diffReason,
      clientRequestId: genRequestId()
    }
    if (initForm.reGrossWeight != null) payload.reGrossWeight = Number(initForm.reGrossWeight)
    if (initForm.reTareWeight != null) payload.reTareWeight = Number(initForm.reTareWeight)
    const created = await reweighApi.initiate(payload)
    ElMessage.success('复称已发起，磅单进入结算冻结')
    initVisible.value = false
    await reload(created.reweighNo)
    await select(created)
  } catch (e) {
    // VERSION_CONFLICT / SETTLEMENT_RACED 等已弹明确提示；并发抢到的情况下刷新队列
    if (['REWEIGH_IN_PROGRESS', 'SETTLEMENT_RACED', 'TICKET_STATUS_DENIED'].includes(e.code)) reload()
  } finally {
    acting.value = false
  }
}

async function submitSupplement() {
  if (!suppForm.reGrossWeight || Number(suppForm.reGrossWeight) <= 0) return ElMessage.warning('请填写复称毛重')
  if (!suppForm.diffReason.trim()) return ElMessage.warning('请填写差异原因')
  if (suppOver.value) return ElMessage.warning('重分配合计超过复称净重，请核对')
  acting.value = true
  try {
    const saved = await reweighApi.supplement({
      reweighNo: current.value.reweighNo,
      reGrossWeight: Number(suppForm.reGrossWeight),
      reTareWeight: Number(suppForm.reTareWeight),
      diffReason: suppForm.diffReason.trim(),
      lines: suppLines.value.map((l) => ({ lineNo: l.lineNo, newWeight: Number(l.newWeight || 0) })),
      clientRequestId: genRequestId()
    })
    ElMessage.success(saved.status === 'AWAIT_RELEASE' ? '补录完成，差异在阈值内，待现场放行' : '补录完成，差异超阈值，进入待复核')
    await reload(saved.reweighNo)
    await select(saved)
  } catch (e) {
    if (e.code === 'VERSION_CONFLICT' || e.code === 'TICKET_STATUS_DENIED') refreshCurrent()
  } finally {
    acting.value = false
  }
}

async function doRelease() {
  if (!actorName.value.trim()) return ElMessage.warning('请输入当班确认人')
  if (actorName.value.trim() === current.value.initiator) {
    return ElMessage.error('本人无权放行：必须由发起人以外的另一名当班人员操作')
  }
  acting.value = true
  try {
    const saved = await reweighApi.release({
      reweighNo: current.value.reweighNo,
      operator: actorName.value.trim()
    })
    ElMessage.success('现场放行完成，新版本已生效，磅单恢复结算')
    await reload(saved.reweighNo)
    await select(saved)
  } catch (e) {
    if (['SELF_REVIEW_FORBIDDEN', 'VERSION_CONFLICT', 'TICKET_STATUS_DENIED'].includes(e.code)) refreshCurrent()
  } finally {
    acting.value = false
  }
}

async function escalate() {
  try {
    const { value } = await ElMessageBox.prompt('差异虽在阈值内，但现场有争议时可升级为待复核，请说明争议点', '升级为待复核', {
      confirmButtonText: '升级复核',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputPlaceholder: '如：客户坚持含水率判定有误',
      inputValidator: (v) => (v && v.trim()) || '请填写争议原因'
    })
    acting.value = true
    const saved = await reweighApi.escalate({
      reweighNo: current.value.reweighNo,
      operator: actorName.value.trim() || current.value.initiator,
      reason: value.trim()
    })
    ElMessage.success('已升级为待复核，须由非发起人复核')
    await reload(saved.reweighNo)
    await select(saved)
  } catch (e) {
    if (e && e.code) refreshCurrent()
  } finally {
    acting.value = false
  }
}

async function doReview(result) {
  if (!actorName.value.trim()) return ElMessage.warning('请输入复核人')
  if (actorName.value.trim() === current.value.initiator) {
    return ElMessage.error('本人无权复核：复核人不能是发起人本人')
  }
  if (result === 'REJECTED' && !reviewRejectReason.value.trim()) {
    return ElMessage.warning('驳回时必须填写驳回原因')
  }
  acting.value = true
  try {
    const saved = await reweighApi.review({
      reweighNo: current.value.reweighNo,
      reviewer: actorName.value.trim(),
      result,
      rejectReason: reviewRejectReason.value.trim()
    })
    ElMessage.success(result === 'APPROVED' ? '复核通过，新版本已生效' : '复核已驳回，磅单沿用原版本恢复结算')
    await reload(saved.reweighNo)
    await select(saved)
  } catch (e) {
    if (['SELF_REVIEW_FORBIDDEN', 'VERSION_CONFLICT', 'TICKET_STATUS_DENIED'].includes(e.code)) refreshCurrent()
  } finally {
    acting.value = false
  }
}

onMounted(async () => {
  await reload()
  // 从过磅台卡片跳转：?initiate=WB... 打开发起弹窗；?reweighNo=FC... 直接选中处理中复称
  if (route.query.initiate) {
    openInitiate(String(route.query.initiate))
  } else if (route.query.reweighNo) {
    const hit = orders.value.find((o) => o.reweighNo === route.query.reweighNo)
    if (hit) await select(hit)
  }
})
</script>

<style scoped>
.desk {
  display: grid;
  grid-template-columns: minmax(0, 0.85fr) minmax(0, 1.4fr);
  gap: 14px;
  align-items: start;
}

.filter { margin-bottom: 10px; }

.empty { padding: 24px; text-align: center; }
.empty.big { padding: 60px 20px; }
.empty.big p { margin: 6px 0; }

.rw-card {
  border: 1px solid var(--recy-line);
  border-left: 4px solid var(--el-color-primary);
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 8px;
  background: #fffdfc;
  cursor: pointer;
  transition: box-shadow .15s;
}
.rw-card:hover { box-shadow: 0 2px 10px rgba(0,0,0,.08); }
.rw-card.is-current { outline: 2px solid var(--el-color-primary-light-5); }
.rw-card.st-released, .rw-card.st-approved { border-left-color: #00897b; opacity: .92; }
.rw-card.st-rejected { border-left-color: #e53935; opacity: .92; }
.rw-card.st-initiated { border-left-color: #fb8c00; }
.rw-card.st-await_review { border-left-color: #8e24aa; }
.rw-card.st-await_release { border-left-color: #6d4c41; }

.rw-card header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.rw-card .no { font-weight: 700; font-size: 13px; }
.rw-card .row { display: flex; justify-content: space-between; gap: 8px; font-size: 12px; margin-bottom: 2px; }
.rw-card .who { font-weight: 600; }
.rw-card .owner { color: #8d6e63; font-weight: 600; }
.small { font-size: 11px; }

.tag { font-size: 11px; padding: 1px 8px; border-radius: 999px; background: #efebe9; color: #6d4c41; border: 1px solid #d7ccc8; }
.tag-initiated { background: #fff3e0; color: #e65100; border-color: #ffcc80; }
.tag-await_release { background: #efebe9; color: #4e342e; border-color: #bcaaa4; }
.tag-await_review { background: #f3e5f5; color: #6a1b9a; border-color: #ce93d8; }
.tag-released, .tag-approved { background: #e0f2f1; color: #00695c; border-color: #b2dfdb; }
.tag-rejected { background: #ffebee; color: #b71c1c; border-color: #ef9a9a; }

.detail-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 10px; margin-bottom: 10px; }
.head-acts { display: flex; gap: 6px; }
.freeze-banner { margin-bottom: 12px; }

.weight-grid { display: grid; grid-template-columns: 1fr auto 1fr 0.9fr; gap: 10px; align-items: stretch; }
.wbox, .threshold {
  border: 1px solid var(--recy-line);
  border-radius: 8px;
  padding: 10px 12px;
  background: #faf7f5;
}
.wbox.pending { background: #fff8e1; }
.wbox-title { font-size: 12px; font-weight: 700; color: var(--recy-ink-soft); margin-bottom: 6px; }
.wline { display: flex; justify-content: space-between; font-size: 12.5px; padding: 2px 0; }
.warrow { align-self: center; font-size: 20px; color: var(--el-color-primary); }
.threshold p { margin: 4px 0; font-size: 12px; }
.threshold { display: flex; flex-direction: column; gap: 4px; }
.threshold .el-tag { align-self: flex-start; margin-top: 4px; }

.pay { color: var(--recy-money); font-weight: 600; }
.diff-up { color: #e65100; }
.diff-down { color: #1565c0; }
.net-read { font-weight: 700; color: var(--el-color-primary-dark-2); font-size: 15px; margin-right: 8px; }

.reason-box {
  margin-top: 10px; padding: 8px 12px; border-radius: 8px; font-size: 12.5px;
  background: #fff8e1; border: 1px solid #ffe082; color: #6d4c41;
}
.reason-box.reject { background: #ffebee; border-color: #ef9a9a; color: #b71c1c; }

.lines-block { margin-top: 14px; }
.lines-block h4, .timeline-block h4 { margin: 0 0 8px; font-size: 13.5px; }
.grow { width: 100%; }
.grid-table { width: 100%; border-collapse: collapse; font-size: 12px; border: 1px solid var(--recy-line); border-radius: 8px; overflow: hidden; }
.grid-table th, .grid-table td { padding: 6px 8px; border-bottom: 1px solid #f0e8e3; text-align: left; }
.grid-table thead th { background: #f7f2ef; color: #5d4037; font-weight: 600; }
.grid-table tfoot td { background: #faf6f3; font-weight: 600; }
.grid-table .num { text-align: right; font-variant-numeric: tabular-nums; }
.over { color: var(--recy-out); }
.supp-form { margin-top: 8px; display: grid; grid-template-columns: 1fr 1fr; gap: 0 12px; }
.act-row { display: flex; justify-content: flex-end; align-items: center; gap: 12px; margin-top: 10px; }
.warn-text { color: var(--recy-out); font-size: 12px; margin-right: auto; }

.ops { margin-top: 6px; }
.op-line { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; font-size: 13px; }
.op-line.top { align-items: flex-start; }
.unit { margin-left: 8px; }

.timeline-block { margin-top: 16px; border-top: 1px dashed var(--recy-line); padding-top: 10px; }

@media (max-width: 1240px) {
  .desk { grid-template-columns: minmax(0, 1fr); }
  .weight-grid { grid-template-columns: 1fr; }
  .warrow { transform: rotate(90deg); }
}
</style>
