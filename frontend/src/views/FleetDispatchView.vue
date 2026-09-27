<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">
        车辆调度
        <em>待命 → 派车（选本次司机 + 计划回场，证照联锁核对并固化资格快照）→ 在途 → 回场</em>
      </h2>
      <div class="page-tools">
        <el-input v-model="operatorName" size="small" style="width:130px" placeholder="调度员">
          <template #prefix>👤</template>
        </el-input>
        <el-button size="small" @click="load">刷新</el-button>
        <el-button size="small" type="primary" @click="createVisible = true">登记车辆</el-button>
      </div>
    </div>

    <section class="stat-row">
      <span class="chip is-idle">待命 {{ count('IDLE') }}</span>
      <span class="chip is-road">在途 {{ count('ON_ROAD') }}</span>
      <span class="chip is-fix">维修 {{ count('REPAIR') }}</span>
      <span class="chip is-warn">待调度处置 {{ pendingDisposal }}</span>
      <span class="chip is-ok">当前可派 {{ dispatchableCount }}</span>
      <span class="muted">今日累计趟次 {{ totalTrips }} 趟 · 在场 {{ rows.length }} 台</span>
    </section>

    <div class="fleet">
      <article v-for="v in rows" :key="v.id" class="truck" :class="'truck-' + v.status.toLowerCase()">
        <header>
          <span class="plate num">{{ v.plateNo }}</span>
          <span class="state">{{ v.statusName }}</span>
        </header>
        <div class="driver">
          <span class="avatar">{{ v.driverName.charAt(0) }}</span>
          <div class="who">
            <strong>{{ v.driverName }}</strong>
            <span class="muted">{{ v.phone }}</span>
          </div>
          <div class="trips">
            <span class="num trips-no">{{ v.todayTrips }}</span>
            <span class="trips-label">今日趟次</span>
          </div>
        </div>
        <div class="spec">
          <span>额定载重 <b class="num">{{ Number(v.loadCapacity).toFixed(2) }}</b> 吨
            · 要求准驾 <el-tag size="small" type="info" disable-transitions>{{ v.requiredLicenseClassName }}</el-tag>
          </span>
        </div>

        <!-- 不可派原因直接列在车辆卡片上（调度员不用开弹窗就能看到） -->
        <div v-if="hardBlocks(v).length" class="blocks">
          <div v-for="(b, i) in hardBlocks(v)" :key="i" class="block-line">
            <span class="block-dot">●</span>{{ b.message }}
          </div>
        </div>
        <div v-if="softBlocks(v).length" class="blocks warn-blocks">
          <div v-for="(b, i) in softBlocks(v)" :key="'s'+i" class="block-line warn-text">
            <span class="block-dot warn-dot">●</span>{{ b.message }}
          </div>
        </div>
        <div v-else-if="v.status === 'IDLE' && !hardBlocks(v).length" class="blocks ok-blocks">
          <span class="block-dot ok">●</span>默认司机证照在今日口径下齐备可派（以派车核对为准）
        </div>

        <div v-if="v.remark" class="remark-line muted">{{ v.remark }}</div>

        <footer>
          <!-- 待命：派车 / 维修 -->
          <template v-if="v.status === 'IDLE'">
            <el-button size="small" type="primary" @click="openDispatch(v)">派车</el-button>
            <el-button size="small" @click="changeStatus(v, 'REPAIR')">转维修</el-button>
            <el-button size="small" @click="openTrips(v)">出车记录</el-button>
          </template>

          <!-- 在途 / 待调度处置：回场 + 快照 -->
          <template v-else-if="v.status === 'ON_ROAD'">
            <el-button v-if="v.openTripStatus === 'PENDING_DISPOSAL'" size="small" type="danger"
              @click="openOpenTrip(v)">⚠ 待调度处置</el-button>
            <el-button size="small" type="success" @click="openReturn(v)">确认回场</el-button>
            <el-button size="small" @click="openOpenTrip(v)">本次资格快照</el-button>
          </template>

          <!-- 维修：改回待命 -->
          <template v-else>
            <el-button size="small" type="primary" @click="changeStatus(v, 'IDLE')">修复待命</el-button>
            <el-button size="small" @click="openTrips(v)">出车记录</el-button>
          </template>
        </footer>
      </article>
    </div>

    <!-- ========== 派车弹窗 ========== -->
    <el-dialog v-model="dispatchVisible" title="派车确认" width="680px">
      <template v-if="dispatchTarget">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="车牌">{{ dispatchTarget.plateNo }}</el-descriptions-item>
          <el-descriptions-item label="额定载重">{{ dispatchTarget.loadCapacity }} 吨</el-descriptions-item>
          <el-descriptions-item label="要求准驾">{{ dispatchTarget.requiredLicenseClassName }}</el-descriptions-item>
        </el-descriptions>

        <el-form label-width="96px" size="small" class="dispatch-form">
          <el-form-item label="本次司机">
            <el-select v-model="dispatchForm.driverId" filterable style="width:100%"
               placeholder="选择本次司机（可不同于默认司机）" @change="runCheck">
              <el-option v-for="d in activeDrivers" :key="d.id" :value="d.id"
                :label="`${d.driverName}（${d.driverNo}）${d.statusName !== '在岗' ? ' · ' + d.statusName : ''}`" />
            </el-select>
          </el-form-item>
          <el-form-item label="计划回场">
            <el-date-picker v-model="dispatchForm.plannedReturnAt" type="datetime"
              format="YYYY-MM-DD HH:mm" value-format="YYYY-MM-DD HH:mm" style="width:100%"
              :disabled-date="disablePastDates" @change="runCheck" />
          </el-form-item>
          <el-form-item label="调度员">
            <el-input v-model="dispatchForm.operator" style="width:220px" />
          </el-form-item>
        </el-form>

        <div v-if="checkResult" class="check-panel">
          <el-alert v-if="checkResult.pass" type="success" :closable="false" show-icon
            :title="`资格核对通过：准驾车型覆盖，司机证照与车辆全部必需证照在计划回场前持续有效。下面 ${checkResult.snapshot.length} 份资料版本将固化为本次派车资格快照。`" />
          <el-alert v-else type="error" :closable="false" show-icon
            :title="`核对未通过（${checkResult.blockReasons.length} 项），不能派车`" />
          <div v-for="(b, i) in (checkResult.blockReasons || [])" :key="i" class="block-line">
            <span class="block-dot">●</span>{{ b.message }}
          </div>

          <div v-if="checkResult.snapshot && checkResult.snapshot.length" class="snap-preview">
            <div class="snap-title">将固化的派车资格快照（{{ checkResult.snapshot.length }} 份）</div>
            <el-table :data="checkResult.snapshot" size="small" border>
              <el-table-column label="主体" min-width="150">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.subjectType === 'DRIVER' ? 'warning' : 'primary'"
                    disable-transitions>{{ row.subjectTypeName }}</el-tag>
                  <b style="margin-left:6px">{{ row.subjectName }}</b>
                </template>
              </el-table-column>
              <el-table-column prop="certTypeName" label="证照" width="105" />
              <el-table-column prop="certNo" label="号码" min-width="120" class-name="num" />
              <el-table-column prop="licenseClassName" label="准驾" width="60" />
              <el-table-column label="有效期覆盖到计划回场" min-width="200">
                <template #default="{ row }">
                  <span class="num">{{ row.validFrom }} ~ {{ row.validTo }}</span>
                  <el-tag size="small" type="success" effect="plain" disable-transitions style="margin-left:4px">
                    V{{ row.versionNo }}
                  </el-tag>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </template>
      <template #footer>
        <el-button size="small" @click="dispatchVisible = false">取消</el-button>
        <el-button size="small" @click="runCheck">重新核对</el-button>
        <el-button size="small" type="primary" :disabled="!canDispatch" @click="confirmDispatch">
          确认派车并固化快照
        </el-button>
      </template>
    </el-dialog>

    <!-- ========== 回场确认 ========== -->
    <el-dialog v-model="returnVisible" title="在途回场" width="420px">
      <template v-if="returnTrip">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="出车单号">{{ returnTrip.tripNo }}</el-descriptions-item>
          <el-descriptions-item label="车辆 / 司机">{{ returnTrip.plateNo }} · {{ returnTrip.driverName }}</el-descriptions-item>
          <el-descriptions-item label="计划回场">{{ fmtDateTime(returnTrip.plannedReturnAt) }}</el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="returnTrip.status === 'PENDING_DISPOSAL'" type="error" :closable="false" show-icon
          style="margin-top:8px" title="本车处于待调度处置" :description="returnTrip.disposalReason" />
        <p class="muted small" style="margin-top:8px">回场后车辆转待命、今日趟次 +1；本次派车资格快照永久保留。</p>
      </template>
      <template #footer>
        <el-button size="small" @click="returnVisible = false">取消</el-button>
        <el-button size="small" type="success" @click="confirmReturn">确认回场</el-button>
      </template>
    </el-dialog>

    <!-- ========== 出车记录 / 快照 ========== -->
    <el-dialog v-model="tripsVisible" :title="`${tripsVehicle ? tripsVehicle.plateNo : ''} 出车记录与资格快照`"
      width="760px">
      <el-table :data="vehicleTripList" size="small" border max-height="520">
        <el-table-column prop="tripNo" label="出车单号" width="140" class-name="num" />
        <el-table-column label="司机" width="90">
          <template #default="{ row }">{{ row.driverName }}</template>
        </el-table-column>
        <el-table-column label="计划回场" width="150">
          <template #default="{ row }">{{ fmtDateTime(row.plannedReturnAt) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="tripTagType(row.status)" disable-transitions>{{ row.statusName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="资格快照" width="110">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="openSnapshotFromList(row)">
              {{ row.certs.length }} 份
            </el-button>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="100">
          <template #default="{ row }">
            <el-button v-if="row.status !== 'FINISHED'" size="small" link type="warning"
              @click="returnSpecific(row)">回场</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 单车快照弹窗（从出车记录打开） -->
    <el-dialog v-model="snapshotVisible" title="派车资格快照（不可变）" width="700px" append-to-body>
      <template v-if="snapshotTrip">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="单号">{{ snapshotTrip.tripNo }}</el-descriptions-item>
          <el-descriptions-item label="计划回场">{{ fmtDateTime(snapshotTrip.plannedReturnAt) }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="tripTagType(snapshotTrip.status)" disable-transitions>
              {{ snapshotTrip.statusName }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="snapshotTrip.disposalReason" type="error" :closable="false" show-icon
          style="margin:8px 0" :title="snapshotTrip.disposalReason" />
        <el-table :data="snapshotTrip.certs" size="small" border style="margin-top:8px">
          <el-table-column label="主体" min-width="140">
            <template #default="{ row }">
              <el-tag size="small" :type="row.subjectType === 'DRIVER' ? 'warning' : 'primary'"
                disable-transitions>{{ row.subjectTypeName }}</el-tag>
              <b style="margin-left:6px">{{ row.subjectName }}</b>
            </template>
          </el-table-column>
          <el-table-column prop="certTypeName" label="证照" width="105" />
          <el-table-column prop="certNo" label="号码" min-width="120" class-name="num" />
          <el-table-column prop="licenseClassName" label="准驾" width="60" />
          <el-table-column label="有效期 / 版本" min-width="200">
            <template #default="{ row }">
              <span class="num">{{ row.validFrom }} ~ {{ row.validTo }}</span>
              <el-tag size="small" type="success" effect="plain" disable-transitions style="margin-left:4px">
                V{{ row.versionNo }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-dialog>

    <!-- ========== 登记车辆 ========== -->
    <el-dialog v-model="createVisible" title="登记车辆" width="420px">
      <el-form :model="createForm" label-width="90px" size="small">
        <el-form-item label="车牌">
          <el-input v-model="createForm.plateNo" placeholder="如 沪C·8H620" />
        </el-form-item>
        <el-form-item label="默认司机">
          <el-input v-model="createForm.driverName" placeholder="司机姓名（档案名）" />
        </el-form-item>
        <el-form-item label="选择名册司机">
          <el-select v-model="createForm.defaultDriverId" filterable clearable style="width:100%"
            placeholder="可选：与证照联锁台司机名册挂钩">
            <el-option v-for="d in activeDrivers" :key="d.id" :value="d.id"
              :label="`${d.driverName}（${d.driverNo}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="随车电话">
          <el-input v-model="createForm.phone" />
        </el-form-item>
        <el-form-item label="额定载重">
          <el-input-number v-model="createForm.loadCapacity" :min="0.5" :precision="2" :step="0.5" />
          <span class="muted unit">吨</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="createVisible = false">取消</el-button>
        <el-button size="small" type="primary" @click="submitCreate">登记</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { vehicleApi, certApi } from '../api'

const rows = ref([])
const activeDrivers = ref([])
const createVisible = ref(false)
const createForm = reactive({
  plateNo: '', driverName: '', phone: '', loadCapacity: 5, remark: '', defaultDriverId: null
})
const operatorName = ref('钱调度')

// 派车弹窗
const dispatchVisible = ref(false)
const dispatchTarget = ref(null)
const dispatchForm = reactive({ driverId: null, plannedReturnAt: '', operator: '' })
const checkResult = ref(null)

// 回场
const returnVisible = ref(false)
const returnTrip = ref(null)

// 出车记录 / 快照
const tripsVisible = ref(false)
const tripsVehicle = ref(null)
const vehicleTripList = ref([])
const snapshotVisible = ref(false)
const snapshotTrip = ref(null)

async function load() {
  rows.value = await vehicleApi.list()
  activeDrivers.value = await certApi.drivers('ACTIVE')
}

function count(status) {
  return rows.value.filter((v) => v.status === status).length
}
function hardBlocks(v) {
  return (v.blockReasons || []).filter((b) => b.warning !== 'true' && b.warning !== true)
}
function softBlocks(v) {
  return (v.blockReasons || []).filter((b) => b.warning === 'true' || b.warning === true)
}
const totalTrips = computed(() => rows.value.reduce((s, v) => s + (v.todayTrips || 0), 0))
const pendingDisposal = computed(() => rows.value.filter((v) => v.openTripStatus === 'PENDING_DISPOSAL').length)
const dispatchableCount = computed(() => rows.value.filter((v) => v.dispatchable).length)

function fmtDateTime(value) {
  return value ? value.replace('T', ' ').slice(0, 16) : '—'
}

function tripTagType(status) {
  return { OPEN: 'warning', FINISHED: 'success', PENDING_DISPOSAL: 'danger' }[status] || 'info'
}

function disablePastDates(date) {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return date.getTime() < today.getTime()
}

// ---------------- 状态切换（仅待命⇄维修） ----------------
async function changeStatus(vehicle, status) {
  if (status === vehicle.status) return
  try {
    await vehicleApi.changeStatus(vehicle.id, status)
    ElMessage.success(`${vehicle.plateNo} 已转为${status === 'REPAIR' ? '维修' : '待命'}`)
    await load()
  } catch (e) {
    await load()
  }
}

// ---------------- 派车 ----------------
function defaultPlannedReturn() {
  const d = new Date()
  d.setHours(18, 0, 0, 0)
  if (d.getTime() < Date.now()) d.setDate(d.getDate() + 1)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} 18:00`
}

function openDispatch(vehicle) {
  dispatchTarget.value = vehicle
  dispatchForm.driverId = vehicle.defaultDriverId
  dispatchForm.plannedReturnAt = defaultPlannedReturn()
  dispatchForm.operator = operatorName.value
  checkResult.value = null
  dispatchVisible.value = true
  setTimeout(runCheck, 50)
}

let checkSeq = 0
async function runCheck() {
  if (!dispatchTarget.value || !dispatchForm.driverId || !dispatchForm.plannedReturnAt) {
    checkResult.value = null
    return
  }
  const seq = ++checkSeq
  try {
    const res = await certApi.dispatchCheck({
      vehicleId: dispatchTarget.value.id,
      driverId: dispatchForm.driverId,
      plannedReturnAt: dispatchForm.plannedReturnAt
    })
    if (seq === checkSeq) checkResult.value = res
  } catch (e) {
    if (seq === checkSeq) checkResult.value = null
  }
}

const canDispatch = computed(() =>
  checkResult.value?.pass && dispatchForm.operator.trim() && dispatchTarget.value)

async function confirmDispatch() {
  try {
    const trip = await vehicleApi.dispatch({
      vehicleId: dispatchTarget.value.id,
      driverId: dispatchForm.driverId,
      operator: dispatchForm.operator.trim(),
      plannedReturnAt: dispatchForm.plannedReturnAt,
      expectedVehicleVersion: dispatchTarget.value.lockVersion
    })
    ElMessage.success(`派车已确认：${trip.tripNo}，${trip.certs.length} 份证照版本已固化为派车资格快照`)
    dispatchVisible.value = false
    await load()
  } catch (e) {
    // 五类错误码由拦截器弹明确消息；这里刷新一次状态并重核对，防止页面长期未更新
    await load()
    const target = rows.value.find((v) => v.id === dispatchTarget.value?.id)
    if (target) {
      dispatchTarget.value = target
      await runCheck()
    }
  }
}

// ---------------- 回场 ----------------
async function loadTripById(tripId) {
  return certApi.tripDetail(tripId)
}

async function openReturn(vehicle) {
  if (!vehicle.openTripId) {
    ElMessage.warning('未找到该车辆的未结束出车单，请刷新列表')
    return
  }
  returnTrip.value = await loadTripById(vehicle.openTripId)
  returnVisible.value = true
}

async function returnSpecific(row) {
  returnTrip.value = row
  tripsVisible.value = false
  returnVisible.value = true
}

async function confirmReturn() {
  try {
    await vehicleApi.finishTrip({ tripId: returnTrip.value.id, operator: operatorName.value })
    ElMessage.success('已确认回场，今日趟次 +1')
    returnVisible.value = false
    await load()
  } catch (e) {
    await load()
  }
}

// ---------------- 出车记录 + 快照 ----------------
async function openTrips(vehicle) {
  tripsVehicle.value = vehicle
  vehicleTripList.value = await certApi.vehicleTrips(vehicle.id)
  tripsVisible.value = true
}

async function openOpenTrip(vehicle) {
  if (!vehicle.openTripId) return
  snapshotTrip.value = await loadTripById(vehicle.openTripId)
  snapshotVisible.value = true
}

async function openSnapshotFromList(row) {
  snapshotTrip.value = await loadTripById(row.id)
  snapshotVisible.value = true
}

// ---------------- 登记车辆 ----------------
async function submitCreate() {
  try {
    await vehicleApi.create({ ...createForm })
    ElMessage.success('车辆已登记')
    createVisible.value = false
    Object.assign(createForm, {
      plateNo: '', driverName: '', phone: '', loadCapacity: 5, remark: '', defaultDriverId: null
    })
    await load()
  } catch (e) { /* ignore */ }
}

onMounted(load)
</script>

<style scoped>
.stat-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.chip {
  padding: 3px 12px;
  border-radius: 999px;
  font-size: 12px;
  border: 1px solid transparent;
}

.chip.is-idle { background: #efebe9; color: #5d4037; border-color: #d7ccc8; }
.chip.is-road { background: #ffe0b2; color: #e65100; border-color: #ffcc80; }
.chip.is-fix { background: #eceff1; color: #546e7a; border-color: #cfd8dc; }
.chip.is-warn { background: #fdecea; color: #b42318; border-color: #f3b8b0; }
.chip.is-ok { background: #e8f5e9; color: #2e7d32; border-color: #bfe3c4; }

.fleet {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(290px, 1fr));
  gap: 12px;
}

.truck {
  background: #fff;
  border: 1px solid var(--recy-line);
  border-top: 4px solid var(--el-color-primary-light-5);
  border-radius: 10px;
  padding: 12px 14px;
}

.truck-idle { border-top-color: #8d6e63; }
.truck-on_road { border-top-color: #ef6c00; background: #fffdf9; }
.truck-repair { border-top-color: #90a4ae; background: #fafbfb; }

.truck header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.plate { font-size: 16px; font-weight: 700; letter-spacing: 0.5px; }

.state {
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 999px;
  background: #efebe9;
  color: #6d4c41;
}
.truck-on_road .state { background: #ffe0b2; color: #e65100; }
.truck-repair .state { background: #eceff1; color: #546e7a; }

.driver {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 8px;
  border-bottom: 1px dashed #efe7e2;
}

.avatar {
  width: 32px;
  height: 32px;
  flex: 0 0 32px;
  border-radius: 50%;
  background: var(--el-color-primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
}

.who { display: flex; flex-direction: column; line-height: 1.35; min-width: 0; }

.trips { margin-left: auto; text-align: right; }
.trips-no { font-size: 20px; font-weight: 700; color: var(--el-color-primary-dark-2); }
.trips-label { display: block; font-size: 11px; color: var(--recy-ink-soft); }

.spec {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  padding: 8px 0 4px;
  min-width: 0;
  flex-wrap: wrap;
}

.remark-line { font-size: 11px; padding: 2px 0 6px; }

.blocks {
  background: #fef0f0;
  border: 1px dashed #f3b8b0;
  border-radius: 6px;
  padding: 6px 8px;
  margin: 4px 0 6px;
}

.block-line {
  font-size: 11.5px;
  color: #b42318;
  line-height: 1.5;
}

.block-dot {
  color: #e34d42;
  margin-right: 4px;
  font-size: 9px;
}
.block-dot.ok { color: #4caf50; }

.ok-blocks {
  background: #f1f8f2;
  border-color: #bfe3c4;
  padding: 5px 8px;
  font-size: 11.5px;
  color: #2e7d32;
}

.warn-blocks {
  background: #fff8e1;
  border-color: #ffe082;
}
.warn-text { color: #b26a00; }
.block-dot.warn-dot { color: #f9a825; }

.truck footer {
  border-top: 1px dashed #efe7e2;
  padding-top: 8px;
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.unit { margin-left: 6px; }

.dispatch-form { margin-top: 12px; }

.check-panel {
  margin-top: 10px;
  border-top: 1px dashed #e8e0d8;
  padding-top: 10px;
}

.check-panel .block-line { margin-bottom: 2px; }

.snap-preview { margin-top: 10px; }
.snap-title {
  font-size: 12px;
  font-weight: 700;
  margin-bottom: 6px;
  color: var(--recy-ink);
}

.page-tools { display: flex; gap: 8px; align-items: center; }
</style>
