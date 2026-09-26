<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">
        品类牌价
        <em>当日回收单价（付给客户）与当日出售单价（卖给下游厂家），调价必须留理由</em>
      </h2>
      <div class="page-tools">
        <el-button size="small" @click="load">刷新</el-button>
        <el-button size="small" type="primary" @click="createVisible = true">新建品类</el-button>
      </div>
    </div>

    <div class="panel">
      <el-table :data="rows" border stripe size="small" empty-text="还没有品类">
        <el-table-column prop="categoryNo" label="品类编号" width="110" />
        <el-table-column prop="categoryName" label="品类名称" min-width="140" />
        <el-table-column label="单位" width="80">
          <template #default="{ row }">{{ row.unitName }}</template>
        </el-table-column>
        <el-table-column label="当日回收单价" width="130" align="right">
          <template #default="{ row }">
            <span class="num buy">{{ money(row.buyPrice) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="当日出售单价" width="130" align="right">
          <template #default="{ row }">
            <span class="num sell">{{ money(row.sellPrice) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="吨毛利" width="120" align="right">
          <template #default="{ row }">
            <span class="num">{{ money(row.margin) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="收储" width="90" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.stored"
              size="small"
              inline-prompt
              active-text="收"
              inactive-text="停"
              @change="toggleStored(row)"
            />
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip />
        <el-table-column label="操作" width="90" fixed="right" align="center">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click="openAdjust(row)">调价</el-button>
          </template>
        </el-table-column>
      </el-table>

      <p class="muted foot-note">
        停收的品类不会出现在过磅台的明细行下拉里；历史磅单上已经固化的单价快照不受调价影响。
      </p>
    </div>

    <!-- 调价弹窗 -->
    <el-dialog v-model="adjustVisible" title="行内调价" width="420px">
      <el-form :model="adjustForm" label-width="100px" size="small">
        <el-form-item label="品类">
          <span>{{ current?.categoryName }}（{{ current?.categoryNo }}）</span>
        </el-form-item>
        <el-form-item label="原回收单价">
          <span class="num">{{ money(current?.buyPrice) }} 元 / {{ current?.unitName }}</span>
        </el-form-item>
        <el-form-item label="新回收单价">
          <el-input-number v-model="adjustForm.buyPrice" :min="0" :precision="2" :step="0.05" />
        </el-form-item>
        <el-form-item label="新出售单价">
          <el-input-number v-model="adjustForm.sellPrice" :min="0" :precision="2" :step="0.05" />
        </el-form-item>
        <el-form-item label="调价理由">
          <el-input v-model="adjustForm.reason" placeholder="如：下游纸厂今日上调 60 元/吨" />
        </el-form-item>
      </el-form>
      <p class="rule-tip">出售单价不能低于回收单价（倒挂当天就亏），调价理由至少 2 个字，便于日结对账追溯。</p>
      <template #footer>
        <el-button size="small" @click="adjustVisible = false">取消</el-button>
        <el-button size="small" type="primary" @click="submitAdjust">保存牌价</el-button>
      </template>
    </el-dialog>

    <!-- 新建品类 -->
    <el-dialog v-model="createVisible" title="新建品类" width="420px">
      <el-form :model="createForm" label-width="100px" size="small">
        <el-form-item label="品类编号">
          <el-input v-model="createForm.categoryNo" placeholder="如 RC014" />
        </el-form-item>
        <el-form-item label="品类名称">
          <el-input v-model="createForm.categoryName" placeholder="如 ABS 工程塑料" />
        </el-form-item>
        <el-form-item label="计量单位">
          <el-radio-group v-model="createForm.unit">
            <el-radio-button value="KG">公斤</el-radio-button>
            <el-radio-button value="SET">台</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="回收单价">
          <el-input-number v-model="createForm.buyPrice" :min="0" :precision="2" :step="0.05" />
        </el-form-item>
        <el-form-item label="出售单价">
          <el-input-number v-model="createForm.sellPrice" :min="0" :precision="2" :step="0.05" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="createVisible = false">取消</el-button>
        <el-button size="small" type="primary" @click="submitCreate">建档</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { categoryApi, money } from '../api'

const rows = ref([])
const adjustVisible = ref(false)
const createVisible = ref(false)
const current = ref(null)

const adjustForm = reactive({ buyPrice: 0, sellPrice: 0, reason: '' })
const createForm = reactive({ categoryNo: '', categoryName: '', unit: 'KG', buyPrice: 1, sellPrice: 1.5, remark: '', stored: true })

async function load() {
  rows.value = await categoryApi.list()
}

function openAdjust(row) {
  current.value = row
  adjustForm.buyPrice = Number(row.buyPrice)
  adjustForm.sellPrice = Number(row.sellPrice)
  adjustForm.reason = ''
  adjustVisible.value = true
}

async function submitAdjust() {
  try {
    await categoryApi.adjustPrice(current.value.id, {
      buyPrice: adjustForm.buyPrice,
      sellPrice: adjustForm.sellPrice,
      reason: adjustForm.reason
    })
    ElMessage.success('牌价已更新')
    adjustVisible.value = false
    await load()
  } catch (e) {
    // ignored
  }
}

async function toggleStored(row) {
  try {
    await categoryApi.toggleStored(row.id)
    ElMessage.success(`${row.categoryName} 已${row.stored ? '停收' : '恢复收储'}`)
    await load()
  } catch (e) {
    // ignored
  }
}

async function submitCreate() {
  try {
    await categoryApi.create({ ...createForm })
    ElMessage.success('品类已建档')
    createVisible.value = false
    createForm.categoryNo = ''
    createForm.categoryName = ''
    createForm.remark = ''
    await load()
  } catch (e) {
    // ignored
  }
}

onMounted(load)
</script>

<style scoped>
.num {
  font-variant-numeric: tabular-nums;
}

.buy {
  color: var(--recy-money);
  font-weight: 600;
}

.sell {
  color: var(--recy-in);
  font-weight: 600;
}

.foot-note {
  margin: 10px 0 0;
}
</style>
