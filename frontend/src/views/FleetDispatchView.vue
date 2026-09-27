<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">
        车辆调度 · 派车联锁
        <em>待命→在途必须派车确认：选定本次司机与计划回场，证照有效期覆盖回场才放行，并固化派车资格快照</em>
      </h2>
      <div class="page-tools">
        <el-button size="small" @click="load">刷新</el-button>
        <el-button size="small" type="primary" @click="createVisible = true">登记车辆</el-button>
      </div>
    </div>

    <section class="stat-row">
      <span class="chip is-idle">待命 {{ count('IDLE') }}</span>
      <span class="chip is-road">在途 {{ count('ON_ROAD') }}</span>
      <span class="chip is-fix">维修 {{ count('REPAIR') }}</span>
      <span class="chip is-block">不可派 {{ blockedCount }}</span>
      <span class="muted">待调度处置 {{ pendingCount }} 台 · 在场 {{ rows.length }} 台</span>
    </section>

    <div class="fleet">
      <article v-for="b in rows" :key="b.vehicle.id" class="truck"
               :class="cardClass(b)">
        <header>
          <span class="plate num">{{ b.vehicle.plateNo }}</span>
          <span class="state">{{ b.vehicle.statusName }}
            <em v-if="b.pendingDispatch" class="state-pending">待处置</em>
          </span>
        </header>
        <div class="driver">
          <span class="avatar">{{ (b.vehicle.driverName || '?').charAt(0) }}</span>
          <div class="who">
            <strong>{{ b.vehicle.driverName }}</strong>
            <span class="muted">{{ b.vehicle.phone }} · 要求 {{ b.requiredLicenseClassName }}</span>
          </div>
          <div class="trips">
            <span class="num trips-no">{{ b.vehicle.todayTrips }}</span>
            <span class="trips-label">今日趟次</span>
          </div>
        </div>

        <!-- 不可派原因：调度员在车辆卡片上直接看到 -->
        <div v-if="b.reasons.length" class="blockers">
          <div v-for="(r, i) in b.reasons" :key="i" class="blocker">
            <span class="blocker-code" :class="codeClass(r.code)">{{ reasonLabel(r.code) }}</span>
            <span class="muted">{{ r.message }}</span>
          </div>
        </div>
        <div v-else-if="b.vehicle.status === 'IDLE'" class="ok-line">
          ✅ 证照联锁通过，可派车（默认司机准驾 {{ b.requiredLicenseClassName }}）
        </div>

        <div v-if="b.openTripNo" class="open-trip">
          在途单 <b class="num">{{ b.openTripNo }}</b> · 司机 {{ b.openDriverName }}
          <span v-if="b.plannedReturnAt"> · 计划回场 {{ fmtTime(b.plannedReturnAt) }}</span>
        </div>

        <footer>
          <template v-if="b.vehicle.status === 'ON_ROAD'">
            <el-button size="small" style="flex:1" @click="viewSnapshot(b.openTripId)">资格快照</el-button>
            <el-button size="small" type="warning" style="flex:1" @click="openReturn(b)">回场登记</el-button>
          </template>
          <template v-else-if="b.vehicle.status === 'IDLE'">
            <el-button size="small" @click="toggleStatus(b.vehicle, 'REPAIR')">转维修</el-button>
            <el-button size="small" type="primary" style="flex:2" :disabled="!b.dispatchable"
                       @click="openDispatch(b)">派车确认</el-button>
          </template>
          <template v-else>
            <el-button size="small" type="primary" style="flex:1" @click="toggleStatus(b.vehicle, 'IDLE')">修复待命</el-button>
            <el-button size="small" style="flex:1" disabled>维修中禁派</el-button>
          </template>
        </footer>
      </article>
    </div>

    <!-- 登记车辆 -->
    <el-dialog v-model="createVisible" title="登记车辆" width="420px">
      <el-form :model="createForm" label-width="90px" size="small">
        <el-form-item label="车牌"><el-input v-model="createForm.plateNo" placeholder="如 沪C·8H620" /></el-form-item>
        <el-form-item label="默认司机"><el-input v-model="createForm.driverName" /></el-form-item>
        <el-form-item label="随车电话"><el-input v-model="createForm.phone" /></el-form-item>
        <el-form-item label="额定载重">
          <el-input-number v-model="createForm.loadCapacity" :min="0.5" :precision="2" :step="0.5" />
          <span class="muted unit">吨（据此推定准驾车型）</span>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="createForm.remark" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="createVisible = false">取消</el-button>
        <el-button size="small" type="primary" @click="submitCreate">登记</el-button>
      </template>
    </el-dialog>

    <!-- 派车确认：选司机 + 计划回场，试算联锁，展示将固化的快照 -->
    <el-dialog v-model="dispatchVisible" title="派车确认（证照联锁 + 资格快照）" width="680px" @closed="resetDispatch">
      <div v-if="dispatchTarget">
        <el-descriptions :column="3" size="small" border>
          <el-descriptions-item label="车辆">{{ dispatchTarget.vehicle.plateNo }}</el-descriptions-item>
          <el-descriptions-item label="载重">{{ dispatchTarget.vehicle.loadCapacity }} 吨</el-descriptions-item>
          <el-descriptions-item label="要求准驾">{{ dispatchTarget.requiredLicenseClassName }}</el-descriptions-item>
        </el-descriptions>
        <el-form :model="dispatchForm" label-width="92px" size="small" class="dispatch-form">
          <el-form-item label="本次司机">
            <el-select v-model="dispatchForm.driverId" filterable placeholder="选择本次司机" style="width:260px"
                       @change="runCheck">
              <el-option v-for="d in drivers" :key="d.id" :value="d.id"
                         :label="`${d.driverName}（${d.licenseClassName || d.licenseClass || '-'}）${d.statusName}`" />
            </el-select>
          </el-form-item>
          <el-form-item label="计划回场">
            <el-date-picker v-model="dispatchForm.plannedReturnAt" type="datetime"
                            format="YYYY-MM-DD HH:mm" value-format="YYYY-MM-DD HH:mm"
                            placeholder="证照必须有效到回场之后" style="width:260px" @change="runCheck" />
          </el-form-item>
          <el-form-item label="调度员"><el-input v-model="dispatchForm.dispatcher" style="width:260px" /></el-form-item>
        </el-form>

        <div v-if="checkResult">
          <el-alert v-if="checkResult.passable" type="success" :closable="false" show-icon
                    title="联锁通过：以下 5 份生效证照将固化为本次派车资格快照（换版不影响本次出车）" />
          <el-alert v-else type="error" :closable="false" show-icon title="联锁不通过，不能派车：">
            <div v-for="(r, i) in checkResult.reasons" :key="i" class="check-reason">
              <span class="blocker-code" :class="codeClass(r.code)">{{ reasonLabel(r.code) }}</span>{{ r.message }}
            </div>
          </el-alert>
          <table v-if="checkResult.wouldFreeze && checkResult.wouldFreeze.length" class="freeze-table">
            <thead><tr><th>归属</th><th>证件</th><th>版本</th><th>证号/准驾</th><th>有效期止</th></tr></thead>
            <tbody>
              <tr v-for="s in checkResult.wouldFreeze" :key="s.docId">
                <td>{{ s.ownerName }}</td>
                <td>{{ s.docTypeName }}</td>
                <td class="num">V{{ s.docVersionNo }}</td>
                <td>{{ s.docNumber }}<em v-if="s.licenseClass"> · {{ s.licenseClassName }}</em></td>
                <td class="num">{{ s.validUntil }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <template #footer>
        <el-button size="small" @click="dispatchVisible = false">取消</el-button>
        <el-button size="small" type="primary" :disabled="!canDispatch" :loading="dispatching"
                   @click="confirmDispatch">确认派车并固化快照</el-button>
      </template>
    </el-dialog>

    <!-- 回场登记 -->
    <el-dialog v-model="returnVisible" title="在途回场登记" width="420px">
      <el-form label-width="92px" size="small">
        <el-form-item label="在途单号"><b class="num">{{ returnTarget?.openTripNo }}</b></el-form-item>
        <el-form-item label="确认人"><el-input v-model="returnOperator" /></el-form-item>
        <el-form-item label="回场时间">
          <el-date-picker v-model="returnAt" type="datetime" format="YYYY-MM-DD HH:mm"
                          value-format="YYYY-MM-DD HH:mm" placeholder="留空=当前时间" style="width:240px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="returnVisible = false">取消</el-button>
        <el-button size="small" type="warning" @click="confirmReturn">确认回场（趟次+1）</el-button>
      </template>
    </el-dialog>

    <!-- 单次出车资格快照 -->
    <el-dialog v-model="snapshotVisible" title="派车资格快照（本次出车当时的资料版本）" width="720px">
      <div v-if="snapshot">
        <el-descriptions :column="3" size="small" border>
          <el-descriptions-item label="派车单号">{{ snapshot.tripNo }}</el-descriptions-item>
          <el-descriptions-item label="司机">{{ snapshot.driverName }}（{{ snapshot.driverLicenseClassName }}）</el-descriptions-item>
          <el-descriptions-item label="车辆要求">{{ snapshot.requiredLicenseClassName }}</el-descriptions-item>
          <el-descriptions-item label="派车时间">{{ fmtTime(snapshot.dispatchedAt) }}</el-descriptions-item>
          <el-descriptions-item label="计划回场">{{ fmtTime(snapshot.plannedReturnAt) }}</el-descriptions-item>
          <el-descriptions-item label="实际回场">{{ snapshot.returnedAt ? fmtTime(snapshot.returnedAt) : '未回场' }}</el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="snapshot.pendingDispatch" type="warning" :closable="false" show-icon class="pend-alert"
                  :title="'待调度处置：' + snapshot.pendingReason" />
        <p class="muted rule-tip">下列证照版本在派车确认瞬间固化；之后复核换版 / 撤销 / 到期都不改写本次快照，
          新派车只认当前生效版本。</p>
        <table class="freeze-table">
          <thead><tr><th>归属</th><th>证件</th><th>采用版本</th><th>证号/准驾</th><th>有效期</th><th>经办/复核</th></tr></thead>
          <tbody>
            <tr v-for="s in snapshot.snapshots" :key="s.docId">
              <td>{{ s.ownerType === 'DRIVER' ? '司机·' : '车辆·' }}{{ s.ownerName }}</td>
              <td>{{ s.docTypeName }}</td>
              <td class="num">V{{ s.docVersionNo }}</td>
              <td>{{ s.docNumber }}<em v-if="s.licenseClass"> · {{ s.licenseClassName }}</em></td>
              <td class="num">{{ s.validFrom }} ~ {{ s.validUntil }}</td>
              <td>{{ s.handler }} / {{ s.reviewer }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { vehicleApi, credentialApi, dispatchApi } from '../api'

const rows = ref([])
const drivers = ref([])
const createVisible = ref(false)
const createForm = reactive({ plateNo: '', driverName: '', phone: '', loadCapacity: 5, status: 'IDLE', remark: '' })

const dispatchVisible = ref(false)
const dispatching = ref(false)
const dispatchTarget = ref(null)
const dispatchForm = reactive({ driverId: null, plannedReturnAt: '', dispatcher: '王调度' })
const checkResult = ref(null)

const returnVisible = ref(false)
const returnTarget = ref(null)
const returnOperator = ref('王调度')
const returnAt = ref('')

const snapshotVisible = ref(false)
const snapshot = ref(null)

const REASON_TEXT = {
  CRED_PENDING_REVIEW: '资料仍待复核',
  CRED_EXPIRED: '证照已过期',
  LICENSE_CLASS_MISMATCH: '准驾车型不匹配',
  RETURN_BEYOND_VALID_UNTIL: '计划回场晚于有效期',
  TRIP_ALREADY_OPEN: '车辆已有未结束出车',
  VEHICLE_UNDER_REPAIR: '车辆维修中',
  PENDING_DISPATCH: '待调度处置',
  DRIVER_NOT_REGISTERED: '司机未登记'
}

async function load() {
  rows.value = await dispatchApi.board()
}
async function loadDrivers() {
  drivers.value = await credentialApi.drivers()
}

function count(status) {
  return rows.value.filter((b) => b.vehicle.status === status).length
}
const blockedCount = computed(() => rows.value.filter((b) => !b.dispatchable).length)
const pendingCount = computed(() => rows.value.filter((b) => b.pendingDispatch).length)

function cardClass(b) {
  if (b.vehicle.status === 'REPAIR') return 'truck-repair'
  if (b.vehicle.status === 'ON_ROAD') return b.pendingDispatch ? 'truck-pending' : 'truck-on_road'
  return b.dispatchable ? 'truck-idle' : 'truck-blocked'
}
function reasonLabel(code) {
  return REASON_TEXT[code] || code
}
function codeClass(code) {
  if (code === 'TRIP_ALREADY_OPEN' || code === 'PENDING_DISPATCH') return 'code-warn'
  return 'code-err'
}
function fmtTime(t) {
  return t ? t.replace('T', ' ').slice(0, 16) : ''
}

async function toggleStatus(vehicle, status) {
  try {
    await vehicleApi.changeStatus(vehicle.id, status)
    ElMessage.success('状态已更新')
    await load()
  } catch (e) {
    await load()
  }
}

async function submitCreate() {
  try {
    await vehicleApi.create({ ...createForm })
    ElMessage.success('车辆已登记（准驾车型按载重推定）')
    createVisible.value = false
    Object.assign(createForm, { plateNo: '', driverName: '', phone: '', remark: '' })
    await load()
  } catch (e) {
    // 提示已由拦截器弹出
  }
}

function openDispatch(b) {
  dispatchTarget.value = b
  dispatchForm.driverId = drivers.value.find((d) => d.driverName === b.vehicle.driverName)?.id || null
  dispatchForm.plannedReturnAt = ''
  checkResult.value = null
  dispatchVisible.value = true
}
function resetDispatch() {
  dispatchTarget.value = null
  checkResult.value = null
}

let checkSeq = 0
async function runCheck() {
  if (!dispatchForm.driverId || !dispatchForm.plannedReturnAt) {
    checkResult.value = null
    return
  }
  const seq = ++checkSeq
  try {
    const data = await dispatchApi.check({
      vehicleId: dispatchTarget.value.vehicle.id,
      driverId: dispatchForm.driverId,
      plannedReturnAt: dispatchForm.plannedReturnAt
    })
    if (seq === checkSeq) checkResult.value = data
  } catch (e) {
    // 拦截器已提示
  }
}

const canDispatch = computed(() =>
  checkResult.value?.passable &&
  dispatchForm.driverId && dispatchForm.plannedReturnAt && dispatchForm.dispatcher)

async function confirmDispatch() {
  dispatching.value = true
  try {
    const trip = await dispatchApi.create({
      vehicleId: dispatchTarget.value.vehicle.id,
      driverId: dispatchForm.driverId,
      plannedReturnAt: dispatchForm.plannedReturnAt,
      dispatcher: dispatchForm.dispatcher,
      expectedVehicleVersion: dispatchTarget.value.lockVersion
    })
    ElMessage.success(`派车成功，${trip.snapshots.length} 份证照版本已固化为资格快照`)
    dispatchVisible.value = false
    await load()
  } catch (e) {
    // 资料版本已变化等错误已弹出；刷新看板保证页面不长期过期
    await load()
    if (dispatchTarget.value) {
      dispatchTarget.value = rows.value.find((b) => b.vehicle.id === dispatchTarget.value.vehicle.id)
    }
  } finally {
    dispatching.value = false
  }
}

function openReturn(b) {
  returnTarget.value = b
  returnAt.value = ''
  returnVisible.value = true
}
async function confirmReturn() {
  try {
    await dispatchApi.returnTrip(returnTarget.value.openTripId, returnOperator.value || '王调度', returnAt.value || null)
    ElMessage.success('已登记回场，今日趟次 +1')
    returnVisible.value = false
    await load()
  } catch (e) {
    await load()
  }
}

async function viewSnapshot(tripId) {
  snapshot.value = await dispatchApi.trip(tripId)
  snapshotVisible.value = true
}

onMounted(() => {
  load()
  loadDrivers()
})
</script>

<style scoped>
.stat-row { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.chip { padding: 3px 12px; border-radius: 999px; font-size: 12px; border: 1px solid transparent; }
.chip.is-idle { background: #efebe9; color: #5d4037; border-color: #d7ccc8; }
.chip.is-road { background: #ffe0b2; color: #e65100; border-color: #ffcc80; }
.chip.is-fix { background: #eceff1; color: #546e7a; border-color: #cfd8dc; }
.chip.is-block { background: #ffebee; color: #c62828; border-color: #ef9a9a; }

.fleet { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
.truck { background: #fff; border: 1px solid var(--recy-line); border-top: 4px solid #8d6e63; border-radius: 10px; padding: 12px 14px; }
.truck-on_road, .truck-pending { border-top-color: #ef6c00; background: #fffdf9; }
.truck-pending { box-shadow: 0 0 0 2px #ffcc80 inset; }
.truck-repair { border-top-color: #90a4ae; background: #fafbfb; }
.truck-blocked { border-top-color: #e53935; }
.truck header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.plate { font-size: 16px; font-weight: 700; letter-spacing: .5px; }
.state { font-size: 11px; padding: 1px 8px; border-radius: 999px; background: #efebe9; color: #6d4c41; }
.state-pending { background: #c62828; color: #fff; margin-left: 6px; font-style: normal; padding: 0 6px; border-radius: 6px; }
.truck-on_road .state, .truck-pending .state { background: #ffe0b2; color: #e65100; }
.truck-repair .state { background: #eceff1; color: #546e7a; }

.driver { display: flex; align-items: center; gap: 10px; padding-bottom: 8px; border-bottom: 1px dashed #efe7e2; }
.avatar { width: 32px; height: 32px; flex: 0 0 32px; border-radius: 50%; background: var(--el-color-primary); color: #fff; display: flex; align-items: center; justify-content: center; font-weight: 700; }
.who { display: flex; flex-direction: column; line-height: 1.35; min-width: 0; }
.trips { margin-left: auto; text-align: right; }
.trips-no { font-size: 20px; font-weight: 700; color: var(--el-color-primary-dark-2); }
.trips-label { display: block; font-size: 11px; color: var(--recy-ink-soft); }

.blockers { margin: 8px 0; display: flex; flex-direction: column; gap: 5px; }
.blocker { font-size: 12px; line-height: 1.5; display: flex; gap: 6px; align-items: flex-start; }
.blocker-code { flex: 0 0 auto; font-size: 10px; padding: 1px 6px; border-radius: 4px; background: #ffebee; color: #c62828; border: 1px solid #ef9a9a; margin-top: 1px; }
.blocker-code.code-warn { background: #fff8e1; color: #ef6c00; border-color: #ffcc80; }
.ok-line { font-size: 12px; color: #2e7d32; padding: 8px 0; }
.open-trip { font-size: 12px; color: #e65100; background: #fff3e0; border-radius: 6px; padding: 5px 8px; margin-bottom: 8px; }
.truck footer { border-top: 1px dashed #efe7e2; padding-top: 8px; display: flex; gap: 8px; }
.unit { margin-left: 6px; }
.dispatch-form { margin-top: 12px; }
.check-reason { font-size: 12px; line-height: 1.8; }
.freeze-table { width: 100%; border-collapse: collapse; margin-top: 10px; font-size: 12px; }
.freeze-table th, .freeze-table td { border: 1px solid #e8e0da; padding: 5px 8px; text-align: left; }
.freeze-table th { background: #faf6f2; }
.freeze-table em { color: #8d6e63; font-style: normal; margin-left: 4px; }
.pend-alert { margin: 10px 0; }
</style>
