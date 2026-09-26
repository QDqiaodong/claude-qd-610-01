<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">
        车辆调度
        <em>待命 → 在途 → 回场（趟次 +1）；维修中的车不允许直接派出去</em>
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
          <span>额定载重 <b class="num">{{ Number(v.loadCapacity).toFixed(2) }}</b> 吨</span>
          <span v-if="v.remark" class="muted">{{ v.remark }}</span>
        </div>
        <footer>
          <el-radio-group :model-value="v.status" size="small" @change="(val) => changeStatus(v, val)">
            <el-radio-button v-for="opt in v.statusOptions" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </el-radio-button>
          </el-radio-group>
        </footer>
      </article>
    </div>

    <el-dialog v-model="createVisible" title="登记车辆" width="420px">
      <el-form :model="createForm" label-width="90px" size="small">
        <el-form-item label="车牌">
          <el-input v-model="createForm.plateNo" placeholder="如 沪C·8H620" />
        </el-form-item>
        <el-form-item label="司机">
          <el-input v-model="createForm.driverName" />
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
import { ElMessage } from 'element-plus'
import { vehicleApi } from '../api'

const rows = ref([])
const createVisible = ref(false)
const createForm = reactive({ plateNo: '', driverName: '', phone: '', loadCapacity: 5, status: 'IDLE', remark: '' })

async function load() {
  rows.value = await vehicleApi.list()
}

function count(status) {
  return rows.value.filter((v) => v.status === status).length
}

const totalTrips = computed(() => rows.value.reduce((sum, v) => sum + (v.todayTrips || 0), 0))

async function changeStatus(vehicle, status) {
  if (status === vehicle.status) return
  try {
    await vehicleApi.changeStatus(vehicle.id, status)
    ElMessage.success(`${vehicle.plateNo} 已转为${vehicle.statusOptions.find((o) => o.value === status).label}`)
    await load()
  } catch (e) {
    await load()
  }
}

async function submitCreate() {
  try {
    await vehicleApi.create({ ...createForm })
    ElMessage.success('车辆已登记')
    createVisible.value = false
    createForm.plateNo = ''
    createForm.driverName = ''
    createForm.phone = ''
    createForm.remark = ''
    await load()
  } catch (e) {
    // ignored
  }
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

.chip.is-idle {
  background: #efebe9;
  color: #5d4037;
  border-color: #d7ccc8;
}

.chip.is-road {
  background: #ffe0b2;
  color: #e65100;
  border-color: #ffcc80;
}

.chip.is-fix {
  background: #eceff1;
  color: #546e7a;
  border-color: #cfd8dc;
}

.fleet {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(268px, 1fr));
  gap: 12px;
}

.truck {
  background: #fff;
  border: 1px solid var(--recy-line);
  border-top: 4px solid var(--el-color-primary-light-5);
  border-radius: 10px;
  padding: 12px 14px;
}

.truck-idle {
  border-top-color: #8d6e63;
}

.truck-on_road {
  border-top-color: #ef6c00;
  background: #fffdf9;
}

.truck-repair {
  border-top-color: #90a4ae;
  background: #fafbfb;
}

.truck header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.plate {
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0.5px;
}

.state {
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 999px;
  background: #efebe9;
  color: #6d4c41;
}

.truck-on_road .state {
  background: #ffe0b2;
  color: #e65100;
}

.truck-repair .state {
  background: #eceff1;
  color: #546e7a;
}

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

.who {
  display: flex;
  flex-direction: column;
  line-height: 1.35;
  min-width: 0;
}

.trips {
  margin-left: auto;
  text-align: right;
}

.trips-no {
  font-size: 20px;
  font-weight: 700;
  color: var(--el-color-primary-dark-2);
}

.trips-label {
  display: block;
  font-size: 11px;
  color: var(--recy-ink-soft);
}

.spec {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
  padding: 8px 0 10px;
  min-width: 0;
}

.truck footer {
  border-top: 1px dashed #efe7e2;
  padding-top: 8px;
}

.truck footer :deep(.el-radio-button) {
  flex: 1;
}

.truck footer :deep(.el-radio-group) {
  display: flex;
  width: 100%;
}

.unit {
  margin-left: 6px;
}
</style>
