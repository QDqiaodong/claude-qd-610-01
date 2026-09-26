<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">
        结算付款
        <em>只放行「未冻结 + 金额版本明确」的磅单；复称处理中的磅单不能付款</em>
      </h2>
      <div class="page-tools">
        <el-button size="small" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="two">
      <section class="panel">
        <h3 class="panel-title">可结算磅单 <span class="sql-badge">status = PENDING AND frozen = 0</span></h3>
        <el-table :data="settleable" border size="small" max-height="300" empty-text="没有可结算的磅单（可能全部在复称冻结中）">
          <el-table-column prop="ticketNo" label="磅单号" width="150" />
          <el-table-column prop="customerName" label="客户" min-width="120" />
          <el-table-column label="版本" width="70" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.activeVersionNo > 1 ? 'warning' : 'info'">V{{ row.activeVersionNo }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="有效净重" align="right" width="105">
            <template #default="{ row }"><span class="num">{{ fmtWeight(row.effectiveNetWeight) }}</span></template>
          </el-table-column>
          <el-table-column label="应付(有效版本)" align="right" width="125">
            <template #default="{ row }"><span class="num pay">{{ money(row.effectiveTotalAmount) }}</span></template>
          </el-table-column>
          <el-table-column label="操作" width="86" align="center" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" @click="openSettle(row)">结算</el-button>
            </template>
          </el-table-column>
        </el-table>

        <h3 class="panel-title sub">复称冻结中（不可结算 / 不可作废）</h3>
        <el-table :data="frozen" border size="small" max-height="180" empty-text="没有冻结中的磅单">
          <el-table-column prop="ticketNo" label="磅单号" width="150" />
          <el-table-column prop="customerName" label="客户" min-width="110" />
          <el-table-column label="冻结原因" min-width="220">
            <template #default="{ row }"><span class="freeze-reason">🔒 {{ row.frozenReason }}</span></template>
          </el-table-column>
          <el-table-column label="复称单" width="150">
            <template #default="{ row }"><span class="num">{{ row.frozenReweighNo }}</span></template>
          </el-table-column>
        </el-table>
      </section>

      <section class="panel">
        <h3 class="panel-title">结算流水 <span class="sql-badge">GET /api/settlements</span></h3>
        <el-table :data="settlements" border size="small" max-height="520" show-summary :summary-method="summaryOfRows">
          <el-table-column prop="settleNo" label="结算单号" width="150" />
          <el-table-column prop="ticketNo" label="磅单号" width="150" />
          <el-table-column prop="customerName" label="客户" min-width="100" show-overflow-tooltip />
          <el-table-column label="金额版本" width="80" align="center">
            <template #default="{ row }"><el-tag size="small" :type="row.amountVersion > 1 ? 'warning' : 'info'">V{{ row.amountVersion }}</el-tag></template>
          </el-table-column>
          <el-table-column label="方式" width="70">
            <template #default="{ row }">
              <span class="mode" :class="'mode-' + row.payMode.toLowerCase()">{{ row.payModeName }}</span>
            </template>
          </el-table-column>
          <el-table-column label="应付" align="right" width="100">
            <template #default="{ row }"><span class="num">{{ money(row.payableAmount) }}</span></template>
          </el-table-column>
          <el-table-column label="实付" align="right" width="100">
            <template #default="{ row }"><span class="num pay">{{ money(row.paidAmount) }}</span></template>
          </el-table-column>
          <el-table-column prop="operator" label="操作员" width="84" />
          <el-table-column prop="settleTime" label="结算时间" width="150" />
        </el-table>
      </section>
    </div>

    <el-dialog v-model="visible" title="磅单结算" width="470px">
      <div v-if="current" class="settle-head">
        <div><span class="muted">磅单号</span> <b class="num">{{ current.ticketNo }}</b>
          <el-tag size="small" :type="current.activeVersionNo > 1 ? 'warning' : 'info'">金额版本 V{{ current.activeVersionNo }}</el-tag>
        </div>
        <div><span class="muted">客户</span> {{ current.customerName }}（{{ current.customerTypeName }}）</div>
        <div v-if="current.activeVersionNo > 1" class="version-note">
          该单经复称放行，应付已按新版本重算（原金额 {{ money(current.totalAmount) }} → {{ money(current.effectiveTotalAmount) }}，单价沿用原单快照）
        </div>
        <div><span class="muted">应付合计</span> <b class="num pay">{{ money(current.effectiveTotalAmount) }}</b> 元</div>
      </div>
      <el-form :model="form" label-width="90px" size="small">
        <el-form-item label="结算方式">
          <el-radio-group v-model="form.payMode" @change="onModeChange">
            <el-radio-button value="CASH">现金</el-radio-button>
            <el-radio-button value="TRANSFER">转账</el-radio-button>
            <el-radio-button value="DEDUCT">抵扣</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="实付金额">
          <el-input-number v-model="form.paidAmount" :min="0" :precision="2" :step="1" :disabled="form.payMode === 'DEDUCT'" />
          <span class="muted unit">元</span>
        </el-form-item>
        <el-form-item label="抹零">
          <span class="num" :class="{ 'is-danger': discount > 5 }">{{ money(discount) }}</span>
          <span class="muted unit">元（上限 5 元）</span>
        </el-form-item>
        <el-form-item label="操作员">
          <el-input v-model="form.operator" placeholder="如 李会计" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" placeholder="选填，如转账流水号" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="visible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="submitting" @click="submit">确认付款</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { settleApi, ticketApi, money, weight as fmtWeight } from '../api'

// 待结算口径：pending 接口已由后端过滤掉复称冻结单；
// 为了把冻结单也展示出来（让付款人员看见为什么不能付），另取全量磅单自己分两组。
const allPending = ref([])
const settlements = ref([])
const visible = ref(false)
const submitting = ref(false)
const current = ref(null)

const form = reactive({ payMode: 'CASH', paidAmount: 0, operator: '李会计', remark: '' })

const settleable = computed(() => allPending.value.filter((t) => !t.frozen))
const frozen = computed(() => allPending.value.filter((t) => t.frozen))

const discount = computed(() => {
  const payable = Number(current.value?.effectiveTotalAmount || 0)
  const paid = Number(form.paidAmount || 0)
  return Math.max(0, Math.round((payable - paid) * 100) / 100)
})

async function load() {
  const [pending, list] = await Promise.all([ticketApi.list(), settleApi.list()])
  allPending.value = pending.filter((t) => t.status === 'PENDING')
  settlements.value = list
}

function openSettle(row) {
  // 双保险：冻结单不给开弹窗（后端还会再拦一次）
  if (row.frozen) {
    ElMessage.error(`磅单正在复称处理中，结算已冻结：${row.frozenReason}`)
    return
  }
  current.value = row
  form.payMode = 'CASH'
  form.paidAmount = Number(row.effectiveTotalAmount)
  form.remark = ''
  visible.value = true
}

function onModeChange(mode) {
  const payable = Number(current.value?.effectiveTotalAmount || 0)
  form.paidAmount = mode === 'DEDUCT' ? 0 : payable
}

async function submit() {
  submitting.value = true
  try {
    await settleApi.settle({
      ticketNo: current.value.ticketNo,
      payMode: form.payMode,
      paidAmount: form.paidAmount,
      operator: form.operator,
      remark: form.remark,
      // 金额版本快照：弹窗打开时看到的版本与金额，提交时服务端核对，
      // 若期间复称放行产生了新版本 → VERSION_CONFLICT，必须刷新按新版本付
      expectedVersion: current.value.activeVersionNo,
      expectedAmount: Number(current.value.effectiveTotalAmount)
    })
    ElMessage.success('结算完成，磅单已转为已结算')
    visible.value = false
    await load()
  } catch (e) {
    // 不同后果不同处理，绝不混为一个提示
    if (e.code === 'VERSION_CONFLICT' || e.code === 'REWEIGH_IN_PROGRESS'
        || e.code === 'SETTLEMENT_RACED' || e.code === 'TICKET_STATUS_DENIED') {
      visible.value = false
      await load()
    }
  } finally {
    submitting.value = false
  }
}

function summaryOfRows() {
  const sum = (key) => settlements.value.reduce((acc, row) => acc + Number(row[key] || 0), 0)
  return ['合计', '', '', '', '', money(sum('payableAmount')), money(sum('paidAmount')), '', '']
}

onMounted(load)
</script>

<style scoped>
.num {
  font-variant-numeric: tabular-nums;
}

.pay {
  color: var(--recy-money);
  font-weight: 600;
}

.two {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(0, 1.25fr);
  gap: 14px;
  align-items: start;
}

.panel-title.sub {
  margin-top: 16px;
}

.freeze-reason {
  color: #b26a00;
  font-size: 12px;
}

.mode {
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 999px;
  background: #efebe9;
  color: #6d4c41;
}

.mode-transfer {
  background: #e3f2fd;
  color: #1565c0;
}

.mode-deduct {
  background: #f3e5f5;
  color: #6a1b9a;
}

.settle-head {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px 12px;
  margin-bottom: 10px;
  background: #f7f4f2;
  border: 1px solid var(--recy-line);
  border-radius: 8px;
  font-size: 13px;
}

.version-note {
  font-size: 12px;
  color: #8d6e63;
  background: #fff8e1;
  border: 1px solid #ffe082;
  border-radius: 6px;
  padding: 4px 8px;
}

.unit {
  margin-left: 8px;
}

.is-danger {
  color: var(--recy-out);
}

@media (max-width: 1180px) {
  .two {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
