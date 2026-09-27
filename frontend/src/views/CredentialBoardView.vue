<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">
        证照有效期联锁台
        <em>司机证照 / 车辆证照分开复核；草稿→待复核→生效，驳回·撤销·到期·换版全程留痕，旧版本永久保留</em>
      </h2>
      <div class="page-tools">
        <el-button size="small" @click="loadAll">刷新</el-button>
        <el-button size="small" @click="driverVisible = true">司机花名册</el-button>
        <el-button size="small" type="primary" @click="openCreate('DRIVER')">录司机证照</el-button>
        <el-button size="small" type="success" @click="openCreate('VEHICLE')">录车辆证照</el-button>
      </div>
    </div>

    <el-tabs v-model="tab" @tab-change="loadAll">
      <!-- 待复核队列 -->
      <el-tab-pane name="queue">
        <template #label>
          <span>待复核队列
            <el-badge v-if="queue.length" :value="queue.length" class="tab-badge" type="danger" />
          </span>
        </template>
        <el-table :data="queue" size="small" border class="cred-table">
          <el-table-column label="归属" min-width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="row.ownerType === 'DRIVER' ? 'warning' : 'success'">
                {{ row.ownerType === 'DRIVER' ? '司机' : '车辆' }}
              </el-tag>
              {{ row.ownerName }}
            </template>
          </el-table-column>
          <el-table-column prop="docTypeName" label="证件类型" width="130" />
          <el-table-column label="版本/证号" min-width="170">
            <template #default="{ row }">
              <b class="num">V{{ row.doc.versionNo }}</b> · {{ row.doc.docNumber }}
              <span v-if="row.doc.licenseClass" class="muted">（{{ row.doc.licenseClassName }}）</span>
            </template>
          </el-table-column>
          <el-table-column label="有效期" min-width="180">
            <template #default="{ row }">
              <span class="num">{{ row.doc.validFrom }} ~ {{ row.doc.validUntil }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="handler" label="经办人" width="90" />
          <el-table-column label="提交时间" width="150">
            <template #default="{ row }">{{ fmtTime(row.submittedAt) }}</template>
          </el-table-column>
          <el-table-column label="复核操作" width="240" fixed="right">
            <template #default="{ row }">
              <el-button size="small" @click="openChain(row.doc.groupUuid)">版本链/差异</el-button>
              <el-button size="small" type="primary" @click="openApprove(row.doc)">通过</el-button>
              <el-button size="small" type="danger" @click="openReject(row.doc)">驳回</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!queue.length" description="暂无待复核资料" />
      </el-tab-pane>

      <!-- 司机证照 -->
      <el-tab-pane label="司机证照" name="driver">
        <owner-panel owner-type="DRIVER" :owners="drivers" owner-label="司机"
                     :docs="driverDocs" @create="openCreate('DRIVER')" @chain="openChain"
                     @new-version="openNewVersion" @edit="openEdit" @submit-doc="openSubmit"
                     @approve="openApprove" @reject="openReject" @revoke="openRevoke" />
      </el-tab-pane>

      <!-- 车辆证照 -->
      <el-tab-pane label="车辆证照" name="vehicle">
        <owner-panel owner-type="VEHICLE" :owners="vehicles" owner-label="车辆"
                     :docs="vehicleDocs" @create="openCreate('VEHICLE')" @chain="openChain"
                     @new-version="openNewVersion" @edit="openEdit" @submit-doc="openSubmit"
                     @approve="openApprove" @reject="openReject" @revoke="openRevoke" />
      </el-tab-pane>

      <!-- 出车资格快照 -->
      <el-tab-pane label="出车资格快照" name="trips">
        <el-table :data="trips" size="small" border class="cred-table">
          <el-table-column prop="tripNo" label="派车单号" width="140" />
          <el-table-column prop="plateNo" label="车辆" width="110" />
          <el-table-column label="本次司机" min-width="130">
            <template #default="{ row }">{{ row.driverName }}（{{ row.driverLicenseClass }}）</template>
          </el-table-column>
          <el-table-column label="派车/计划回场" min-width="230">
            <template #default="{ row }">
              <span class="num">{{ fmtTime(row.dispatchedAt) }}</span><br />
              <span class="muted">计划 {{ fmtTime(row.plannedReturnAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="快照" width="70">
            <template #default="{ row }">{{ row.snapshots.length }} 份</template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'ON_ROAD' ? 'warning' : 'info'">{{ row.statusName }}</el-tag>
              <el-tag v-if="row.pendingDispatch" size="small" type="danger" style="margin-left:4px">待处置</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button size="small" @click="viewTrip(row.id)">资格快照</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 证照录入/新版本 -->
    <el-dialog v-model="formVisible" :title="formTitle" width="560px">
      <el-form :model="form" label-width="104px" size="small">
        <el-form-item label="证照归属">
          <el-select v-model="form.ownerType" :disabled="!!form.basedOnId" style="width:120px">
            <el-option label="司机证照" value="DRIVER" />
            <el-option label="车辆证照" value="VEHICLE" />
          </el-select>
          <el-select v-model="form.ownerId" filterable :disabled="!!form.basedOnId" style="width:240px;margin-left:8px">
            <el-option v-for="o in form.ownerType === 'DRIVER' ? drivers : vehicles" :key="o.id"
                       :value="o.id" :label="form.ownerType === 'DRIVER' ? o.driverName : o.plateNo" />
          </el-select>
        </el-form-item>
        <el-form-item label="证件类型">
          <el-select v-model="form.docType" :disabled="!!form.basedOnId" style="width:260px">
            <el-option v-for="t in docTypeOptions" :key="t.value" :value="t.value" :label="t.label" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.docType === 'CRED_DRIVER_LICENSE'" label="准驾车型">
          <el-select v-model="form.licenseClass" style="width:200px">
            <el-option v-for="c in licenseClasses" :key="c" :value="c" :label="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="证件号码"><el-input v-model="form.docNumber" style="width:300px" /></el-form-item>
        <el-form-item label="适用车型/车辆"><el-input v-model="form.subjectDesc" style="width:300px"
          :placeholder="form.ownerType === 'VEHICLE' ? '如 重型厢式货车 8 吨' : '如 道路货物运输驾驶员'" /></el-form-item>
        <el-form-item label="有效期起">
          <el-date-picker v-model="form.validFrom" type="date" value-format="YYYY-MM-DD" style="width:200px" />
        </el-form-item>
        <el-form-item label="有效期止">
          <el-date-picker v-model="form.validUntil" type="date" value-format="YYYY-MM-DD" style="width:200px" />
        </el-form-item>
        <el-form-item label="附件说明">
          <el-input v-model="form.attachmentNote" type="textarea" :rows="2" style="width:360px"
                    placeholder="扫描件文件名、份数、纸质存放位置" />
        </el-form-item>
        <el-form-item label="经办人"><el-input v-model="form.handler" style="width:200px" :disabled="!!form.basedOnId" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="formVisible = false">取消</el-button>
        <el-button size="small" type="primary" @click="submitForm">保存草稿</el-button>
      </template>
    </el-dialog>

    <!-- 提交确认（带经办人） -->
    <el-dialog v-model="submitVisible" title="提交复核" width="420px">
      <el-form label-width="90px" size="small">
        <el-form-item label="资料">V{{ submitDoc?.versionNo }} {{ submitDoc?.docTypeName }}（{{ submitDoc?.ownerName }}）</el-form-item>
        <el-form-item label="经办人"><el-input v-model="submitHandler" /></el-form-item>
        <p class="muted">提交后进入待复核队列，须由非经办人复核通过才能生效。</p>
      </el-form>
      <template #footer>
        <el-button size="small" @click="submitVisible = false">取消</el-button>
        <el-button size="small" type="primary" @click="confirmSubmit">提交复核</el-button>
      </template>
    </el-dialog>

    <!-- 通过 -->
    <el-dialog v-model="approveVisible" title="复核通过（资料生效）" width="460px">
      <el-form label-width="90px" size="small">
        <el-form-item label="资料">V{{ approveDoc?.versionNo }} {{ approveDoc?.docTypeName }}（{{ approveDoc?.ownerName }}）</el-form-item>
        <el-form-item label="复核人"><el-input v-model="reviewer" style="width:200px" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="reviewNote" style="width:300px" /></el-form-item>
        <el-alert type="info" :closable="false" show-icon
                  title="复核人不能是经办人；通过后旧生效版本转为“被新版本替代”并永久保留，正在使用旧版的在途车会标记待调度处置。" />
      </el-form>
      <template #footer>
        <el-button size="small" @click="approveVisible = false">取消</el-button>
        <el-button size="small" type="primary" @click="confirmApprove">复核通过</el-button>
      </template>
    </el-dialog>

    <!-- 驳回 -->
    <el-dialog v-model="rejectVisible" title="驳回资料" width="460px">
      <el-form label-width="90px" size="small">
        <el-form-item label="资料">V{{ rejectDoc?.versionNo }} {{ rejectDoc?.docTypeName }}（{{ rejectDoc?.ownerName }}）</el-form-item>
        <el-form-item label="复核人"><el-input v-model="rejectReviewer" style="width:200px" /></el-form-item>
        <el-form-item label="驳回原因">
          <el-input v-model="rejectReason" type="textarea" :rows="3" style="width:320px" placeholder="驳回必须填写原因，随版本永久留痕" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="rejectVisible = false">取消</el-button>
        <el-button size="small" type="danger" @click="confirmReject">确认驳回</el-button>
      </template>
    </el-dialog>

    <!-- 撤销 -->
    <el-dialog v-model="revokeVisible" title="撤销生效资料" width="520px">
      <el-form label-width="90px" size="small">
        <el-form-item label="资料">V{{ revokeDoc?.versionNo }} {{ revokeDoc?.docTypeName }}（{{ revokeDoc?.ownerName }}）</el-form-item>
        <el-form-item label="操作人"><el-input v-model="revokeOperator" style="width:200px" /></el-form-item>
        <el-form-item label="撤销原因">
          <el-input v-model="revokeReason" type="textarea" :rows="3" style="width:380px" placeholder="撤销必须填写原因" />
        </el-form-item>
      </el-form>
      <div v-if="affected.length" class="affected-box">
        <b>正在使用该资料的在途车辆（{{ affected.length }}），撤销后将标记“待调度处置”，历史快照保留：</b>
        <el-table :data="affected" size="small" border style="margin-top:6px">
          <el-table-column prop="plateNo" label="车牌" width="110" />
          <el-table-column prop="driverName" label="司机" width="90" />
          <el-table-column prop="tripNo" label="在途单号" width="140" />
          <el-table-column label="计划回场"><template #default="{ row }">{{ fmtTime(row.plannedReturnAt) }}</template></el-table-column>
        </el-table>
      </div>
      <template #footer>
        <el-button size="small" @click="revokeVisible = false">取消</el-button>
        <el-button size="small" type="danger" @click="confirmRevoke">确认撤销并标记在途车</el-button>
      </template>
    </el-dialog>

    <!-- 版本链抽屉：新旧版本差异 + 轨迹 + 在途影响 -->
    <el-drawer v-model="chainVisible" title="证照版本链 · 新旧版本差异" size="640px">
      <div v-if="chain">
        <p class="muted">{{ chain.ownerName }} · {{ chain.docTypeName }}（链号 {{ chain.groupUuid.slice(0, 8) }}…）</p>
        <h4>全部版本（旧版永久保留）</h4>
        <el-timeline class="chain-timeline">
          <el-timeline-item v-for="v in [...chain.versions].reverse()" :key="v.id"
                            :type="statusDot(v.status)" :timestamp="fmtTime(v.reviewedAt || v.submittedAt || v.createdAt)"
                            placement="top">
            <div class="chain-ver">
              <b class="num">V{{ v.versionNo }}</b>
              <el-tag size="small" :type="statusTag(v.status)">{{ v.statusName }}</el-tag>
              <span class="muted">{{ v.docNumber }}<template v-if="v.licenseClass"> · {{ v.licenseClassName }}</template></span>
            </div>
            <div class="muted small">有效期 {{ v.validFrom }} ~ {{ v.validUntil }} · 经办 {{ v.handler }}
              <template v-if="v.reviewer"> / 复核 {{ v.reviewer }}</template></div>
            <div v-if="v.reason" class="chain-reason">原因：{{ v.reason }}</div>
            <div v-if="v.status === 'EFFECTIVE'" class="chain-actions">
              <el-button size="small" @click="loadAffected(v.id); affectedDoc = v">查在途影响</el-button>
              <el-button size="small" type="danger" @click="openRevoke(v)">撤销生效版</el-button>
            </div>
            <el-table v-if="affectedDoc && affectedDoc.id === v.id" :data="affected" size="small" border style="margin-top:6px">
              <el-table-column prop="plateNo" label="车牌" width="100" />
              <el-table-column prop="tripNo" label="在途单" width="135" />
              <el-table-column prop="driverName" label="司机" width="80" />
              <el-table-column label="计划回场"><template #default="{ row }">{{ fmtTime(row.plannedReturnAt) }}</template></el-table-column>
            </el-table>
          </el-timeline-item>
        </el-timeline>

        <h4>新旧版本逐版差异</h4>
        <div v-for="(pair, i) in chain.diffs" :key="i" class="diff-box">
          <b class="num">V{{ pair.fromVersion }} → V{{ pair.toVersion }}</b>
          <table class="diff-table">
            <thead><tr><th>字段</th><th>旧版本</th><th>新版本</th></tr></thead>
            <tbody>
              <tr v-for="f in pair.fields" :key="f.field" :class="{ changed: f.changed }">
                <td>{{ f.fieldName }}</td>
                <td>{{ f.oldValue || '—' }}</td>
                <td>{{ f.newValue || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <el-empty v-if="!chain.diffs.length" description="仅一个版本，暂无差异" />

        <h4>变化轨迹</h4>
        <el-timeline>
          <el-timeline-item v-for="e in chain.events" :key="e.id" :timestamp="fmtTime(e.createdAt)" placement="top">
            <b>{{ e.actionName }}</b>
            <span class="muted small"> · {{ e.operator }}
              <template v-if="e.fromStatusName">（{{ e.fromStatusName }}→{{ e.toStatusName }}）</template>
            </span>
            <div v-if="e.reason" class="chain-reason">{{ e.reason }}</div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-drawer>

    <!-- 司机花名册 -->
    <el-dialog v-model="driverVisible" title="司机花名册" width="640px">
      <el-table :data="drivers" size="small" border>
        <el-table-column prop="driverName" label="姓名" width="90" />
        <el-table-column prop="phone" label="电话" width="130" />
        <el-table-column prop="licenseClassName" label="登记准驾" width="130" />
        <el-table-column prop="statusName" label="状态" width="80" />
        <el-table-column prop="remark" label="备注" />
      </el-table>
    </el-dialog>

    <!-- 出车快照弹窗复用车辆页同款简表 -->
    <el-dialog v-model="tripVisible" title="派车资格快照" width="720px">
      <div v-if="currentTrip">
        <el-descriptions :column="3" size="small" border>
          <el-descriptions-item label="派车单号">{{ currentTrip.tripNo }}</el-descriptions-item>
          <el-descriptions-item label="司机">{{ currentTrip.driverName }}（{{ currentTrip.driverLicenseClassName }}）</el-descriptions-item>
          <el-descriptions-item label="状态">{{ currentTrip.statusName }}
            <el-tag v-if="currentTrip.pendingDispatch" size="small" type="danger" style="margin-left:6px">待处置</el-tag>
          </el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="currentTrip.pendingDispatch" type="warning" :closable="false" :title="currentTrip.pendingReason" style="margin:8px 0" />
        <table class="freeze-table">
          <thead><tr><th>归属</th><th>证件</th><th>版本</th><th>证号/准驾</th><th>有效期止</th></tr></thead>
          <tbody>
            <tr v-for="s in currentTrip.snapshots" :key="s.docId">
              <td>{{ s.ownerType === 'DRIVER' ? '司机·' : '车辆·' }}{{ s.ownerName }}</td>
              <td>{{ s.docTypeName }}</td><td class="num">V{{ s.docVersionNo }}</td>
              <td>{{ s.docNumber }}<em v-if="s.licenseClass"> · {{ s.licenseClassName }}</em></td>
              <td class="num">{{ s.validUntil }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, h, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { credentialApi, dispatchApi, vehicleApi } from '../api'

const tab = ref('queue')
const queue = ref([])
const drivers = ref([])
const vehicles = ref([])
const allDocs = ref([])
const trips = ref([])

const docTypeOptions = computed(() => form.value.ownerType === 'VEHICLE'
  ? [
      { value: 'CRED_ANNUAL_INSPECTION', label: '车辆年检' },
      { value: 'CRED_INSURANCE', label: '机动车交强险' },
      { value: 'CRED_TRANSPORT_PERMIT', label: '道路运输证' }
    ]
  : [
      { value: 'CRED_DRIVER_LICENSE', label: '驾驶证' },
      { value: 'CRED_QUALIFICATION', label: '货运从业资格证' }
    ])
const licenseClasses = ['C1', 'B2', 'A2', 'A1']

const driverDocs = computed(() => groupDocs('DRIVER', drivers.value))
const vehicleDocs = computed(() => groupDocs('VEHICLE', vehicles.value))
function groupDocs(ownerType, owners) {
  const map = {}
  for (const o of owners) {
    map[o.id] = { owner: o, groups: {} }
  }
  for (const d of allDocs.value.filter((x) => x.ownerType === ownerType)) {
    const bucket = map[d.ownerId]
    if (!bucket) continue
    if (!bucket.groups[d.docType]) bucket.groups[d.docType] = []
    bucket.groups[d.docType].push(d)
  }
  return Object.values(map).map((b) => ({
    ...b,
    list: Object.entries(b.groups)
      .map(([docType, versions]) => ({ docType, versions: versions.sort((a, z) => a.versionNo - z.versionNo) }))
      .sort((a, z) => a.docType.localeCompare(z.docType))
  }))
}

// ---------- 表单/弹窗状态 ----------
const formVisible = ref(false)
const editingDoc = ref(null)
const form = reactive({
  id: null, ownerType: 'DRIVER', ownerId: null, docType: '', docNumber: '', licenseClass: '',
  subjectDesc: '', validFrom: '', validUntil: '', attachmentNote: '', handler: '', basedOnId: null
})
const formTitle = computed(() =>
  editingDoc.value ? `编辑 V${editingDoc.value.versionNo}（仅草稿/驳回可改）`
    : form.basedOnId ? '基于生效版本提交新版本' : '录入证照草稿')

const submitVisible = ref(false)
const submitDoc = ref(null)
const submitHandler = ref('')
const approveVisible = ref(false)
const approveDoc = ref(null)
const reviewer = ref('')
const reviewNote = ref('')
const rejectVisible = ref(false)
const rejectDoc = ref(null)
const rejectReviewer = ref('')
const rejectReason = ref('')
const revokeVisible = ref(false)
const revokeDoc = ref(null)
const revokeOperator = ref('')
const revokeReason = ref('')
const affected = ref([])

const chainVisible = ref(false)
const chain = ref(null)
const affectedDoc = ref(null)
const driverVisible = ref(false)
const tripVisible = ref(false)
const currentTrip = ref(null)

function fmtTime(t) {
  return t ? t.replace('T', ' ').slice(0, 16) : ''
}
function statusTag(s) {
  return { EFFECTIVE: 'success', PENDING_REVIEW: 'warning', DRAFT: 'info',
    REJECTED: 'danger', REVOKED: 'danger', EXPIRED: 'danger', SUPERSEDED: 'info' }[s] || 'info'
}
function statusDot(s) {
  return { EFFECTIVE: 'success', PENDING_REVIEW: 'warning', DRAFT: 'info',
    REJECTED: 'danger', REVOKED: 'danger', EXPIRED: 'danger', SUPERSEDED: 'info' }[s] || 'info'
}

// ---------- 数据加载 ----------
async function loadAll() {
  const [q, ds, vs, docs, ts] = await Promise.all([
    credentialApi.reviewQueue(),
    credentialApi.drivers(),
    vehicleApi.list(),
    credentialApi.docs(),
    dispatchApi.trips()
  ])
  queue.value = q
  drivers.value = ds
  vehicles.value = vs
  allDocs.value = docs
  trips.value = ts
}

// ---------- 录入/编辑 ----------
function resetForm(ownerType) {
  Object.assign(form, {
    id: null, ownerType: ownerType || 'DRIVER', ownerId: null, docType: '', docNumber: '',
    licenseClass: '', subjectDesc: '', validFrom: '', validUntil: '', attachmentNote: '',
    handler: '', basedOnId: null
  })
  editingDoc.value = null
}
function openCreate(ownerType) {
  resetForm(ownerType)
  form.docType = docTypeOptions.value[0]?.value
  formVisible.value = true
}
function openNewVersion(doc) {
  resetForm(doc.ownerType)
  Object.assign(form, {
    ownerType: doc.ownerType, ownerId: doc.ownerId, docType: doc.docType, basedOnId: doc.id,
    docNumber: doc.docNumber, licenseClass: doc.licenseClass || '', subjectDesc: doc.subjectDesc || '',
    validFrom: doc.validUntil >= todayStr() ? todayStr() : doc.validFrom,
    validUntil: '', attachmentNote: doc.attachmentNote || '', handler: doc.handler
  })
  editingDoc.value = { versionNo: doc.versionNo + 1 }
  formVisible.value = true
}
function openEdit(doc) {
  resetForm(doc.ownerType)
  Object.assign(form, {
    id: doc.id, ownerId: doc.ownerId, docType: doc.docType, docNumber: doc.docNumber,
    licenseClass: doc.licenseClass || '', subjectDesc: doc.subjectDesc || '',
    validFrom: doc.validFrom, validUntil: doc.validUntil, attachmentNote: doc.attachmentNote || ''
  })
  editingDoc.value = doc
  formVisible.value = true
}
function todayStr() {
  return new Date().toISOString().slice(0, 10)
}

async function submitForm() {
  try {
    if (form.id) {
      await credentialApi.updateDraft(form.id, {
        docNumber: form.docNumber, licenseClass: form.licenseClass, subjectDesc: form.subjectDesc,
        validFrom: form.validFrom, validUntil: form.validUntil, attachmentNote: form.attachmentNote
      })
      ElMessage.success('草稿已更新')
    } else {
      await credentialApi.saveDraft({ ...form })
      ElMessage.success('草稿已保存，可提交复核')
    }
    formVisible.value = false
    await loadAll()
  } catch (e) {
    // 拦截器提示
  }
}

// ---------- 流转 ----------
function openSubmit(doc) {
  submitDoc.value = doc
  submitHandler.value = doc.handler
  submitVisible.value = true
}
async function confirmSubmit() {
  try {
    await credentialApi.submit(submitDoc.value.id, submitHandler.value)
    ElMessage.success('已提交复核')
    submitVisible.value = false
    tab.value = 'queue'
    await loadAll()
  } catch (e) { /* ignore */ }
}

function openApprove(doc) {
  approveDoc.value = doc
  reviewer.value = ''
  reviewNote.value = ''
  approveVisible.value = true
}
async function confirmApprove() {
  try {
    const d = await credentialApi.approve(approveDoc.value.id, reviewer.value, reviewNote.value)
    ElMessage.success(`V${d.versionNo} 已生效，旧版本保留为“被新版本替代”`)
    approveVisible.value = false
    await loadAll()
    if (chain.value) await openChain(chain.value.groupUuid)
  } catch (e) { /* ignore */ }
}

function openReject(doc) {
  rejectDoc.value = doc
  rejectReviewer.value = ''
  rejectReason.value = ''
  rejectVisible.value = true
}
async function confirmReject() {
  try {
    await credentialApi.reject(rejectDoc.value.id, rejectReviewer.value, rejectReason.value)
    ElMessage.success('已驳回，经办人修改后可重新提交')
    rejectVisible.value = false
    await loadAll()
  } catch (e) { /* ignore */ }
}

async function openRevoke(doc) {
  revokeDoc.value = doc
  revokeOperator.value = ''
  revokeReason.value = ''
  await loadAffected(doc.id)
  revokeVisible.value = true
}
async function loadAffected(docId) {
  affected.value = await credentialApi.affectedTrips(docId)
}
async function confirmRevoke() {
  try {
    const list = await credentialApi.revoke(revokeDoc.value.id, revokeOperator.value, revokeReason.value)
    ElMessage.success(`已撤销，${list.length} 辆在途车已标记待调度处置（历史快照保留）`)
    revokeVisible.value = false
    await loadAll()
    if (chain.value) await openChain(chain.value.groupUuid)
  } catch (e) { /* ignore */ }
}

async function openChain(groupUuid) {
  chain.value = await credentialApi.group(groupUuid)
  affectedDoc.value = null
  chainVisible.value = true
}

async function viewTrip(id) {
  currentTrip.value = await dispatchApi.trip(id)
  tripVisible.value = true
}

// ---------- 归属面板（内联函数式组件，避免再拆文件） ----------
const OwnerPanel = {
  props: {
    ownerType: { type: String, required: true },
    owners: { type: Array, default: () => [] },
    ownerLabel: { type: String, default: '' },
    docs: { type: Array, default: () => [] }
  },
  emits: ['create', 'chain', 'new-version', 'edit', 'submit-doc', 'approve', 'reject', 'revoke'],
  setup(props, { emit }) {
    return () =>
      h('div', { class: 'owner-list' },
        props.docs.length ? props.docs.map((bucket) =>
          h('div', { class: 'owner-card', key: bucket.owner.id }, [
            h('div', { class: 'owner-head' }, [
              h('b', null, props.ownerType === 'DRIVER'
                ? `${bucket.owner.driverName}（${bucket.owner.licenseClassName || '-'}）`
                : bucket.owner.plateNo),
              h('span', { class: 'muted' }, props.ownerType === 'DRIVER'
                ? bucket.owner.phone
                : `载重 ${bucket.owner.loadCapacity} 吨 · 要求 ${bucket.owner.requiredLicenseClassName || '-'}`),
              h('el-button', { size: 'small', onClick: () => emit('create', props.ownerType) }, () => '录新证件')
            ]),
            h('table', { class: 'grid-table cred-grid' }, [
              h('thead', null, h('tr', null, [
                h('th', null, '证件类型'), h('th', null, '版本链'), h('th', null, '当前状态'),
                h('th', null, '有效期止'), h('th', null, '经办/复核'), h('th', null, '操作')
              ])),
              h('tbody', null, bucket.list.length ? bucket.list.map((g) => {
                const latest = g.versions[g.versions.length - 1]
                const active = g.versions.find((v) => v.status === 'EFFECTIVE')
                const shown = active || latest
                const actionBtns = []
                if (shown.status === 'DRAFT' || shown.status === 'REJECTED') {
                  actionBtns.push(h('el-button', { size: 'small', onClick: () => emit('edit', shown) }, () => '编辑'))
                  actionBtns.push(h('el-button', { size: 'small', type: 'primary', onClick: () => emit('submit-doc', shown) }, () => '提交复核'))
                }
                if (shown.status === 'PENDING_REVIEW') {
                  actionBtns.push(h('el-button', { size: 'small', type: 'primary', onClick: () => emit('approve', shown) }, () => '通过'))
                  actionBtns.push(h('el-button', { size: 'small', type: 'danger', onClick: () => emit('reject', shown) }, () => '驳回'))
                }
                if (shown.status === 'EFFECTIVE') {
                  actionBtns.push(h('el-button', { size: 'small', onClick: () => emit('new-version', shown) }, () => '提交新版本'))
                  actionBtns.push(h('el-button', { size: 'small', type: 'danger', onClick: () => emit('revoke', shown) }, () => '撤销'))
                }
                actionBtns.push(h('el-button', { size: 'small', onClick: () => emit('chain', shown.groupUuid) }, () => '版本链'))
                return h('tr', { key: g.docType }, [
                  h('td', null, docTypeName(g.docType)),
                  h('td', null, h('span', { class: 'num' }, `V1 ~ V${g.versions.length}（共 ${g.versions.length} 版）`)),
                  h('td', null, h('el-tag', { size: 'small', type: statusTag(shown.status) }, () => shown.statusName)),
                  h('td', { class: 'num' }, shown.validUntil),
                  h('td', null, `${shown.handler}${shown.reviewer ? ' / ' + shown.reviewer : ''}`),
                  h('td', null, h('div', { class: 'row-btns' }, actionBtns))
                ])
              }) : h('tr', null, h('td', { colspan: 6, class: 'muted' }, '尚未录入任何版本')))
            ])
          ])
        ) : h('el-empty', { description: '暂无数据' })
      )
  }
}
function docTypeName(t) {
  return { CRED_DRIVER_LICENSE: '驾驶证', CRED_QUALIFICATION: '从业资格证',
    CRED_ANNUAL_INSPECTION: '车辆年检', CRED_INSURANCE: '交强险',
    CRED_TRANSPORT_PERMIT: '道路运输证' }[t] || t
}

onMounted(loadAll)
</script>

<style scoped>
.tab-badge { margin-left: 6px; }
.cred-table { width: 100%; }
.cred-table :deep(.num) { font-variant-numeric: tabular-nums; }
.owner-list { display: flex; flex-direction: column; gap: 10px; }
.owner-card { background: #fff; border: 1px solid var(--recy-line); border-radius: 10px; padding: 10px 12px; }
.owner-head { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.owner-head .el-button { margin-left: auto; }
.cred-grid { font-size: 12px; }
.row-btns { display: flex; gap: 4px; flex-wrap: wrap; }
.chain-ver { display: flex; align-items: center; gap: 8px; margin-bottom: 2px; }
.chain-reason { font-size: 12px; color: #c62828; margin-top: 2px; }
.chain-actions { margin-top: 6px; display: flex; gap: 6px; }
.small { font-size: 12px; }
.diff-box { margin-bottom: 14px; }
.diff-table { width: 100%; border-collapse: collapse; font-size: 12px; margin-top: 4px; }
.diff-table th, .diff-table td { border: 1px solid #e8e0da; padding: 4px 8px; }
.diff-table th { background: #faf6f2; }
.diff-table tr.changed td { background: #fff8e1; }
.affected-box { background: #fff3e0; border-radius: 8px; padding: 10px; margin: 10px 0; font-size: 12px; }
.freeze-table { width: 100%; border-collapse: collapse; margin-top: 10px; font-size: 12px; }
.freeze-table th, .freeze-table td { border: 1px solid #e8e0da; padding: 5px 8px; text-align: left; }
.freeze-table th { background: #faf6f2; }
.freeze-table em { color: #8d6e63; font-style: normal; margin-left: 4px; }
h4 { margin: 16px 0 8px; }
</style>
