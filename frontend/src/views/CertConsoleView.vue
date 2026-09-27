<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">
        证照有效期联锁台
        <em>司机证照 / 车辆证照分版本管理；旧版本永久保留，派车只认当前生效版本并固化快照</em>
      </h2>
      <div class="page-tools">
        <el-input v-model="operatorName" size="small" style="width:150px" placeholder="当前经办人/复核人">
          <template #prefix>👤</template>
        </el-input>
        <el-button size="small" @click="reload">刷新</el-button>
        <el-button size="small" type="primary" @click="openCreateDriver">登记司机</el-button>
        <el-button size="small" type="primary" plain @click="openNewCert()">录入资料 / 新版本</el-button>
      </div>
    </div>

    <el-tabs v-model="tab" @tab-change="reload">
      <!-- ========== Tab 1：证照列表 ========== -->
      <el-tab-pane name="series">
        <template #label>
          <span>📁 证照列表 <b class="tab-count">{{ seriesRows.length }}</b></span>
        </template>

        <section class="toolbar">
          <el-radio-group v-model="subjectFilter" size="small" @change="loadSeries">
            <el-radio-button label="ALL">全部</el-radio-button>
            <el-radio-button label="DRIVER">司机证照</el-radio-button>
            <el-radio-button label="VEHICLE">车辆证照</el-radio-button>
          </el-radio-group>
          <el-input v-model="keyword" size="small" style="width:260px" placeholder="按车牌 / 司机 / 证件号过滤" clearable>
            <template #prefix>🔎</template>
          </el-input>
          <span class="muted small">生效版本有 <b class="ok">{{ effectiveCount }}</b> 条 ·
            待复核 <b class="warn">{{ pendingCountInList }}</b> ·
            到期/撤销无生效 <b class="danger">{{ missingCount }}</b></span>
        </section>

        <el-table :data="filteredSeries" size="small" border stripe @row-click="openSeriesDrawer"
          :row-class-name="seriesRowClass" style="cursor:pointer">
          <el-table-column label="主体" min-width="150">
            <template #default="{ row }">
              <el-tag size="small" :type="row.subjectType === 'DRIVER' ? 'warning' : 'primary'" disable-transitions>
                {{ row.subjectTypeName }}
              </el-tag>
              <b style="margin-left:6px">{{ row.subjectName }}</b>
            </template>
          </el-table-column>
          <el-table-column prop="certTypeName" label="证照类型" width="180" />
          <el-table-column label="当前生效版本" min-width="260">
            <template #default="{ row }">
              <template v-if="row.current">
                <el-tag size="small" type="success" disable-transitions>V{{ row.current.versionNo }} 生效</el-tag>
                <span class="num" style="margin-left:6px">{{ row.current.certNo }}</span>
                <span v-if="row.current.licenseClassName" class="muted small">（{{ row.current.licenseClassName }}）</span>
                <div class="muted small num">
                  {{ row.current.validFrom }} ~ {{ row.current.validTo }}
                  <el-tag v-if="daysLeft(row.current.validTo) <= 15" size="small" type="danger" effect="plain"
                    disable-transitions style="margin-left:4px">
                    剩 {{ daysLeft(row.current.validTo) }} 天
                  </el-tag>
                </div>
              </template>
              <template v-else><span class="danger-text">⚠ 无生效版本</span></template>
            </template>
          </el-table-column>
          <el-table-column label="待复核" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.pending" size="small" type="warning" disable-transitions>
                V{{ row.pending.versionNo }} 待复核
              </el-tag>
              <span v-if="!row.pending" class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="历史版本" width="90">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click.stop="openSeriesDrawer(row)">
                {{ row.documents.length }} 版
              </el-button>
            </template>
          </el-table-column>
          <el-table-column label="在途影响" width="100">
            <template #default="{ row }">
              <el-badge v-if="row.affectedTrips.length" :value="row.affectedTrips.length" type="danger">
                <span class="muted small">辆车在用</span>
              </el-badge>
              <span v-else class="muted small">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click.stop="openNewCert(row)">录新版本</el-button>
              <el-button v-if="row.current" size="small" link type="danger" @click.stop="openRevoke(row)">撤销</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ========== Tab 2：待复核队列 ========== -->
      <el-tab-pane name="review">
        <template #label>
          <span>🧐 待复核队列 <b class="tab-count tab-warn">{{ reviewRows.length }}</b></span>
        </template>

        <el-alert type="info" :closable="false" show-icon class="rule-banner"
          title="复核规则：经办人不能复核自己录入的资料；驳回必须填写原因；新旧版本同一时刻只有一份能保持生效，过期操作会返回「资料版本已变化」。" />

        <el-empty v-if="!reviewRows.length" description="暂无待复核资料" />
        <div v-else class="review-grid">
          <article v-for="r in reviewRows" :key="r.documentId" class="review-card"
            :class="{ 'self-card': r.selfOperated }">
            <header>
              <el-tag size="small" :type="r.subjectType === 'DRIVER' ? 'warning' : 'primary'" disable-transitions>
                {{ r.subjectTypeName }}
              </el-tag>
              <b>{{ r.subjectName }}</b>
              <span class="muted small">{{ r.certTypeName }} · V{{ r.versionNo }}</span>
            </header>
            <div class="rv-line"><span>证件号码</span><b class="num">{{ r.certNo }}</b></div>
            <div v-if="r.licenseClassName" class="rv-line"><span>准驾车型</span><b>{{ r.licenseClassName }}</b></div>
            <div class="rv-line"><span>有效期</span>
              <b class="num">{{ r.validFrom }} ~ {{ r.validTo }}</b>
            </div>
            <div class="rv-line"><span>附件</span><span>{{ r.attachmentNote || '—' }}</span></div>
            <div class="rv-line"><span>经办人</span><b>{{ r.operator }}</b></div>
            <div class="rv-line muted small"><span>录入 / 提交</span><span>{{ r.updatedAt }}</span></div>
            <footer>
              <el-button size="small" type="success" :disabled="r.selfOperated || !operatorName"
                @click="approveOne(r)">复核生效</el-button>
              <el-button size="small" type="danger" plain :disabled="r.selfOperated || !operatorName"
                @click="openReject(r)">驳回</el-button>
              <el-button size="small" @click="openSeriesById(r.seriesId)">版本链</el-button>
            </footer>
            <div v-if="r.selfOperated" class="self-tip">经办人不能复核自己录入的资料，请换复核人</div>
          </article>
        </div>
      </el-tab-pane>

      <!-- ========== Tab 3：在途影响 / 出车快照 ========== -->
      <el-tab-pane name="trips">
        <template #label>
          <span>🚛 在途与资格快照 <b class="tab-count">{{ tripRows.length }}</b></span>
        </template>

        <section class="toolbar">
          <el-radio-group v-model="tripFilter" size="small" @change="loadTrips">
            <el-radio-button label="">全部</el-radio-button>
            <el-radio-button label="OPEN">在途</el-radio-button>
            <el-radio-button label="PENDING_DISPOSAL">待调度处置</el-radio-button>
            <el-radio-button label="FINISHED">已回场</el-radio-button>
          </el-radio-group>
        </section>

        <el-table :data="tripRows" size="small" border>
          <el-table-column prop="tripNo" label="出车单号" width="150" class-name="num" />
          <el-table-column label="车辆 / 司机" min-width="170">
            <template #default="{ row }">
              <b>{{ row.plateNo }}</b><span class="muted"> · {{ row.driverName }}</span>
            </template>
          </el-table-column>
          <el-table-column label="计划回场" width="160">
            <template #default="{ row }">
              <span class="num">{{ fmtDateTime(row.plannedReturnAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="出车 / 回场" min-width="200">
            <template #default="{ row }">
              <div class="num small">出 {{ fmtDateTime(row.dispatchedAt) }}</div>
              <div class="num small muted">回 {{ row.returnedAt ? fmtDateTime(row.returnedAt) : '—' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="tripTagType(row.status)" disable-transitions>{{ row.statusName }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="快照 / 处置" min-width="220">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click="openSnapshot(row)">
                资格快照 {{ row.certs.length }} 份
              </el-button>
              <el-button v-if="row.status === 'PENDING_DISPOSAL'" size="small" link type="warning"
                @click="openDisposition(row)">登记处置</el-button>
              <div v-if="row.disposalReason" class="danger-text small">{{ row.disposalReason }}</div>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- ========== 版本链抽屉 ========== -->
    <el-drawer v-model="seriesDrawer" size="62%" :title="seriesDrawerTitle">
      <template v-if="seriesDetail">
        <el-alert v-if="seriesDetail.affectedTrips.length" type="error" :closable="false" show-icon
          class="rule-banner"
          :title="`当前生效版本正被 ${seriesDetail.affectedTrips.length} 辆在途车使用，撤销后这些车将标记待调度处置（历史出车快照保留）`" />

        <div class="drawer-acts">
          <el-button size="small" type="primary" @click="openNewCert(seriesDetail)">录入新版本</el-button>
          <el-button size="small" @click="refreshSeries(seriesDetail.id)">刷新版本链</el-button>
        </div>

        <el-timeline class="version-timeline">
          <el-timeline-item v-for="d in seriesDetail.documents" :key="d.id"
            :type="timelineType(d.status)" :hollow="!d.currentEffective" size="large"
            :timestamp="`V${d.versionNo} · ${d.statusName} · 更新于 ${fmtDateTime(d.updatedAt)}`">
            <div class="version-card" :class="`st-${d.status.toLowerCase()}`">
              <div class="vc-head">
                <el-tag size="small" :type="statusTagType(d.status)" disable-transitions>{{ d.statusName }}</el-tag>
                <b class="num">{{ d.certNo }}</b>
                <span v-if="d.licenseClassName" class="muted">准驾 {{ d.licenseClassName }}</span>
                <el-tag v-if="d.currentEffective" size="small" type="success" effect="dark"
                  disable-transitions style="margin-left:auto">当前生效</el-tag>
              </div>
              <div class="vc-grid">
                <span>有效期：<b class="num">{{ d.validFrom }} ~ {{ d.validTo }}</b></span>
                <span>经办人：<b>{{ d.operator }}</b></span>
                <span v-if="d.reviewer">复核人：<b>{{ d.reviewer }}</b></span>
                <span>附件：{{ d.attachmentNote || '—' }}</span>
              </div>
              <div v-if="d.rejectReason" class="reason-box reject">驳回原因：{{ d.rejectReason }}</div>
              <div v-if="d.revokeReason" class="reason-box revoke">撤销原因：{{ d.revokeReason }}</div>
              <el-collapse v-if="d.events.length" class="event-collapse">
                <el-collapse-item :title="`状态轨迹 ${d.events.length} 条`" :name="String(d.id)">
                  <div v-for="e in d.events" :key="e.id" class="event-line">
                    <span class="num small muted">{{ fmtDateTime(e.createdAt) }}</span>
                    <el-tag size="small" effect="plain" disable-transitions>{{ e.eventName }}</el-tag>
                    <b>{{ e.operator }}</b>
                    <span class="muted small">{{ e.note }}</span>
                  </div>
                </el-collapse-item>
              </el-collapse>
              <div class="vc-acts" v-if="canActOn(d)">
                <el-button v-if="d.status === 'DRAFT'" size="small" type="warning"
                  @click="submitDraft(d)">提交复核</el-button>
                <el-button v-if="d.status === 'AWAIT_REVIEW'" size="small" type="success"
                  :disabled="!operatorName || d.operator === operatorName" @click="approveOne(d)">
                  复核生效
                </el-button>
                <el-button v-if="d.status === 'AWAIT_REVIEW'" size="small" type="danger" plain
                  :disabled="!operatorName || d.operator === operatorName" @click="openReject(d)">驳回</el-button>
                <el-button v-if="d.status === 'EFFECTIVE'" size="small" type="danger" plain
                  :disabled="!operatorName" @click="openRevoke(seriesDetail, d)">撤销</el-button>
                <span v-if="d.status === 'AWAIT_REVIEW' && d.operator === operatorName"
                  class="self-tip inline">经办人不能复核自己录入的资料</span>
              </div>
            </div>
          </el-timeline-item>
        </el-timeline>
      </template>
    </el-drawer>

    <!-- ========== 录入资料 / 新版本 ========== -->
    <el-dialog v-model="certDialog" :title="certForm.id ? `录入新版本（V${certForm._nextVersionNo}）` : '录入证照资料'"
      width="560px">
      <el-form :model="certForm" label-width="92px" size="small">
        <el-form-item label="主体类型">
          <el-radio-group v-model="certForm.subjectType" :disabled="!!certForm._fixedSeries"
            @change="onSubjectTypeChange">
            <el-radio label="DRIVER">司机证照</el-radio>
            <el-radio label="VEHICLE">车辆证照</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="适用主体">
          <el-select v-model="certForm.subjectId" filterable :disabled="!!certForm._fixedSeries"
            :placeholder="certForm.subjectType === 'DRIVER' ? '选择司机' : '选择车辆'" style="width:100%">
            <el-option v-for="o in subjectOptions" :key="o.value" :value="o.value" :label="o.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="证照类型">
          <el-select v-model="certForm.certType" :disabled="!!certForm._fixedSeries" style="width:100%">
            <el-option v-for="t in certTypeOptions" :key="t.value" :value="t.value" :label="t.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="证件号码">
          <el-input v-model="certForm.certNo" placeholder="如 3101xxxxxx / 保单号 / 道路运输证号" />
        </el-form-item>
        <el-form-item v-if="certForm.certType === 'DRIVER_LICENSE'" label="准驾车型">
          <el-radio-group v-model="certForm.licenseClass">
            <el-radio v-for="c in licenseClasses" :key="c" :label="c">{{ c }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="有效期起">
          <el-date-picker v-model="certForm.validFrom" type="date" value-format="YYYY-MM-DD" style="width:100%" />
        </el-form-item>
        <el-form-item label="有效期止">
          <el-date-picker v-model="certForm.validTo" type="date" value-format="YYYY-MM-DD" style="width:100%" />
        </el-form-item>
        <el-form-item label="附件说明">
          <el-input v-model="certForm.attachmentNote" type="textarea" :rows="2"
            placeholder="扫描件文件名 / 张数 / 存放位置，如 驾驶证正副本扫描件 2 页" />
        </el-form-item>
        <el-form-item label="经办人">
          <el-input v-model="certForm.operator" placeholder="经办人姓名（不能复核自己录的资料）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="certDialog = false">取消</el-button>
        <el-button size="small" @click="submitCert(false)">存草稿</el-button>
        <el-button size="small" type="primary" @click="submitCert(true)">录入并提交复核</el-button>
      </template>
    </el-dialog>

    <!-- ========== 驳回原因 ========== -->
    <el-dialog v-model="rejectDialog" title="复核驳回（原因必填，随资料永久留痕）" width="460px">
      <el-alert type="warning" :closable="false" show-icon
        :title="`V${rejectTarget?.versionNo} ${rejectTarget?.subjectName || ''} ${rejectTarget?.certTypeName || ''}`"
        style="margin-bottom:10px" />
      <el-input v-model="rejectReason" type="textarea" :rows="3" maxlength="200" show-word-limit
        placeholder="驳回原因，如 证件号码与原件不符 / 附件不清晰" />
      <template #footer>
        <el-button size="small" @click="rejectDialog = false">取消</el-button>
        <el-button size="small" type="danger" @click="confirmReject">确认驳回</el-button>
      </template>
    </el-dialog>

    <!-- ========== 撤销原因 ========== -->
    <el-dialog v-model="revokeDialog" title="撤销生效资料（原因必填）" width="480px">
      <el-alert type="error" :closable="false" show-icon class="rule-banner"
        :title="`将撤销 ${revokeSeries?.subjectName} 的${revokeDoc?.certTypeName} V${revokeDoc?.versionNo}（${revokeDoc?.certNo}）`"
        description="正在使用该资料的在途车辆会被列出并标记为待调度处置；历史出车的派车资格快照原样保留。撤销后该证照暂无生效版本，新派车将被拦截。" />
      <el-input v-model="revokeReason" type="textarea" :rows="3" maxlength="200" show-word-limit
        placeholder="撤销原因，如 接到保险公司协查通知，保单要素存疑" />
      <template #footer>
        <el-button size="small" @click="revokeDialog = false">取消</el-button>
        <el-button size="small" type="danger" @click="confirmRevoke">确认撤销</el-button>
      </template>
    </el-dialog>

    <!-- ========== 派车资格快照 ========== -->
    <el-dialog v-model="snapshotDialog" title="派车资格快照（出车当时固化，不可变）" width="720px">
      <template v-if="snapshotTrip">
        <el-descriptions :column="3" border size="small" class="snap-desc">
          <el-descriptions-item label="出车单号">{{ snapshotTrip.tripNo }}</el-descriptions-item>
          <el-descriptions-item label="车牌">{{ snapshotTrip.plateNo }}</el-descriptions-item>
          <el-descriptions-item label="本次司机">{{ snapshotTrip.driverName }}</el-descriptions-item>
          <el-descriptions-item label="计划回场">{{ fmtDateTime(snapshotTrip.plannedReturnAt) }}</el-descriptions-item>
          <el-descriptions-item label="出车时间">{{ fmtDateTime(snapshotTrip.dispatchedAt) }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="tripTagType(snapshotTrip.status)" disable-transitions>
              {{ snapshotTrip.statusName }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="snapshotTrip.disposalReason" type="error" :closable="false" show-icon
          :title="snapshotTrip.disposalReason" style="margin:8px 0" />
        <el-table :data="snapshotTrip.certs" size="small" border style="margin-top:8px">
          <el-table-column label="主体" min-width="140">
            <template #default="{ row }">
              <el-tag size="small" :type="row.subjectType === 'DRIVER' ? 'warning' : 'primary'"
                disable-transitions>{{ row.subjectTypeName }}</el-tag>
              <b style="margin-left:6px">{{ row.subjectName }}</b>
            </template>
          </el-table-column>
          <el-table-column prop="certTypeName" label="证照" width="110" />
          <el-table-column prop="certNo" label="号码" min-width="130" class-name="num" />
          <el-table-column prop="licenseClassName" label="准驾" width="70" />
          <el-table-column label="有效期（快照）" min-width="190">
            <template #default="{ row }">
              <span class="num">{{ row.validFrom }} ~ {{ row.validTo }}</span>
            </template>
          </el-table-column>
          <el-table-column label="版本" width="80">
            <template #default="{ row }">
              <el-tag size="small" type="success" effect="plain" disable-transitions>V{{ row.versionNo }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <p class="muted small" style="margin-top:8px">
          以上为该次出车实际采用的资料版本；之后证照换版 / 撤销 / 到期都不会改写本表。
        </p>
      </template>
    </el-dialog>

    <!-- ========== 登记司机 ========== -->
    <el-dialog v-model="driverDialog" title="登记司机（证照主体）" width="440px">
      <el-form :model="driverForm" label-width="78px" size="small">
        <el-form-item label="工号"><el-input v-model="driverForm.driverNo" /></el-form-item>
        <el-form-item label="姓名"><el-input v-model="driverForm.driverName" /></el-form-item>
        <el-form-item label="电话"><el-input v-model="driverForm.phone" /></el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="driverForm.status">
            <el-radio label="ACTIVE">在岗</el-radio>
            <el-radio label="RESTING">休息</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="driverDialog = false">取消</el-button>
        <el-button size="small" type="primary" @click="submitDriver">建档</el-button>
      </template>
    </el-dialog>

    <!-- ========== 待调度处置 ========== -->
    <el-dialog v-model="dispositionDialog" title="待调度处置登记" width="480px">
      <template v-if="dispositionTrip">
        <el-alert type="error" :closable="false" show-icon class="rule-banner"
          :title="`${dispositionTrip.plateNo} · ${dispositionTrip.driverName}（${dispositionTrip.tripNo}）`"
          :description="dispositionTrip.disposalReason" />
        <el-input v-model="dispositionNote" type="textarea" :rows="3" maxlength="200" show-word-limit
          placeholder="处置方式：立即召回 / 就近回场 / 派备用车接驳 等" />
      </template>
      <template #footer>
        <el-button size="small" @click="dispositionDialog = false">取消</el-button>
        <el-button size="small" type="warning" @click="confirmDisposition">登记处置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { certApi, vehicleApi } from '../api'

const tab = ref('series')
const operatorName = ref('赵复核')
const subjectFilter = ref('ALL')
const keyword = ref('')

const seriesRows = ref([])
const reviewRows = ref([])
const tripRows = ref([])
const drivers = ref([])
const vehicles = ref([])
const tripFilter = ref('')

const seriesDrawer = ref(false)
const seriesDetail = ref(null)
const certDialog = ref(false)
const rejectDialog = ref(false)
const revokeDialog = ref(false)
const snapshotDialog = ref(false)
const driverDialog = ref(false)
const dispositionDialog = ref(false)

const rejectTarget = ref(null)
const rejectReason = ref('')
const revokeSeries = ref(null)
const revokeDoc = ref(null)
const revokeReason = ref('')
const snapshotTrip = ref(null)
const dispositionTrip = ref(null)
const dispositionNote = ref('')

const emptyCertForm = () => ({
  subjectType: 'DRIVER', subjectId: null, certType: 'DRIVER_LICENSE',
  certNo: '', licenseClass: 'C1', validFrom: '', validTo: '',
  attachmentNote: '', operator: '王安全员', submit: true, _fixedSeries: null, _nextVersionNo: 1
})
const certForm = reactive(emptyCertForm())
const driverForm = reactive({ driverNo: '', driverName: '', phone: '', status: 'ACTIVE' })

// ---------------- 数据加载 ----------------
async function reload() {
  await Promise.all([loadSeries(), loadReview(), loadTrips(), loadSubjects()])
}

async function loadSeries() {
  const param = subjectFilter.value === 'ALL' ? undefined : subjectFilter.value
  seriesRows.value = await certApi.series(param)
}

async function loadReview() {
  reviewRows.value = await certApi.reviewQueue(operatorName.value || undefined)
}

async function loadTrips() {
  tripRows.value = await certApi.trips(tripFilter.value || undefined)
}

async function loadSubjects() {
  const [ds, vs] = await Promise.all([certApi.drivers('ACTIVE'), vehicleApi.list()])
  drivers.value = ds
  vehicles.value = vs
}

const subjectOptions = computed(() => {
  if (certForm.subjectType === 'DRIVER') {
    return drivers.value.map((d) => ({ value: d.id, label: `${d.driverName}（${d.driverNo}）` }))
  }
  return vehicles.value.map((v) => ({ value: v.id, label: `${v.plateNo} · 默认司机 ${v.driverName}` }))
})

const certTypeOptions = computed(() => {
  if (certForm.subjectType === 'DRIVER') {
    return [
      { value: 'DRIVER_LICENSE', label: '驾驶证' },
      { value: 'QUALIFICATION', label: '道路运输从业人员资格证' }
    ]
  }
  return [
    { value: 'ANNUAL_INSPECTION', label: '车辆年检' },
    { value: 'INSURANCE', label: '机动车保险' },
    { value: 'ROAD_TRANSPORT_PERMIT', label: '道路运输证' }
  ]
})

const licenseClasses = ['C2', 'C1', 'B2', 'A2']

const filteredSeries = computed(() => {
  const kw = keyword.value.trim()
  if (!kw) return seriesRows.value
  return seriesRows.value.filter((r) => {
    const hay = [r.subjectName, r.certTypeName, r.current?.certNo, r.pending?.certNo]
      .filter(Boolean).join(' ')
    return hay.includes(kw)
  })
})

const effectiveCount = computed(() => seriesRows.value.filter((r) => r.current).length)
const pendingCountInList = computed(() => seriesRows.value.filter((r) => r.pending).length)
const missingCount = computed(() => seriesRows.value.filter((r) => !r.current).length)

function seriesRowClass({ row }) {
  if (row.pending) return 'row-pending'
  if (!row.current) return 'row-missing'
  if (row.current && daysLeft(row.current.validTo) <= 15) return 'row-expiring'
  return ''
}

function daysLeft(iso) {
  const today = new Date(`${new Date().toISOString().slice(0, 10)}T00:00:00`)
  const end = new Date(`${iso}T00:00:00`)
  return Math.ceil((end - today) / 86400000)
}

function fmtDateTime(value) {
  return value ? value.replace('T', ' ').slice(0, 16) : '—'
}

function statusTagType(status) {
  return {
    DRAFT: 'info', AWAIT_REVIEW: 'warning', EFFECTIVE: 'success',
    REJECTED: 'danger', REVOKED: 'danger', EXPIRED: 'info', SUPERSEDED: 'info'
  }[status] || 'info'
}

function timelineType(status) {
  return {
    DRAFT: 'info', AWAIT_REVIEW: 'warning', EFFECTIVE: 'success',
    REJECTED: 'danger', REVOKED: 'danger', EXPIRED: 'info', SUPERSEDED: 'primary'
  }[status] || 'info'
}

function tripTagType(status) {
  return { OPEN: 'warning', FINISHED: 'success', PENDING_DISPOSAL: 'danger' }[status] || 'info'
}

// ---------------- 版本链抽屉 ----------------
const seriesDrawerTitle = computed(() => seriesDetail.value
  ? `${seriesDetail.value.subjectName} · ${seriesDetail.value.certTypeName}（版本链）` : '版本链')

async function openSeriesDrawer(row) {
  await refreshSeries(row.id)
  seriesDrawer.value = true
}

async function openSeriesById(id) {
  await refreshSeries(id)
  seriesDrawer.value = true
}

async function refreshSeries(id) {
  seriesDetail.value = await certApi.seriesDetail(id)
}

function canActOn(doc) {
  return ['DRAFT', 'AWAIT_REVIEW', 'EFFECTIVE'].includes(doc.status)
}

// ---------------- 录入新版本 ----------------
function openNewCert(seriesRow = null) {
  Object.assign(certForm, emptyCertForm())
  certForm.operator = operatorName.value === '赵复核' ? '王安全员' : operatorName.value
  if (seriesRow && seriesRow.id) {
    certForm._fixedSeries = seriesRow
    certForm.subjectType = seriesRow.subjectType
    certForm.subjectId = seriesRow.subjectId
    certForm.certType = seriesRow.certType
    certForm._nextVersionNo = seriesRow.nextVersionNo
    const latest = seriesRow.documents[0]
    if (latest) {
      certForm.certNo = latest.certNo
      certForm.licenseClass = latest.licenseClass || 'C1'
    }
  }
  certDialog.value = true
}

function onSubjectTypeChange() {
  certForm.subjectId = null
  certForm.certType = certForm.subjectType === 'DRIVER' ? 'DRIVER_LICENSE' : 'ANNUAL_INSPECTION'
}

async function submitCert(submit) {
  if (!certForm.subjectId) return ElMessage.warning('请选择适用主体')
  if (!certForm.certNo.trim()) return ElMessage.warning('请填写证件号码')
  if (certForm.certType === 'DRIVER_LICENSE' && !certForm.licenseClass) {
    return ElMessage.warning('请选择准驾车型')
  }
  if (!certForm.validFrom || !certForm.validTo) return ElMessage.warning('请选择有效期起止')
  if (!certForm.operator.trim()) return ElMessage.warning('请填写经办人')
  try {
    await certApi.saveDraft({ ...certForm, submit })
    ElMessage.success(submit ? '资料已提交复核' : '草稿已保存')
    certDialog.value = false
    await reload()
    if (certForm._fixedSeries) await refreshSeries(certForm._fixedSeries.id)
  } catch (e) {
    // 已统一弹错
  }
}

async function submitDraft(doc) {
  try {
    await certApi.submit({ documentId: doc.id, operator: doc.operator })
    ElMessage.success('已提交复核')
    await refreshSeries(doc.seriesId)
    await loadReview()
  } catch (e) { /* ignore */ }
}

// ---------------- 复核 / 驳回 / 撤销 ----------------
function openReject(doc) {
  rejectTarget.value = doc
  rejectReason.value = ''
  rejectDialog.value = true
}

async function approveOne(doc) {
  const reviewer = operatorName.value
  if (!reviewer) return ElMessage.warning('请先填写右上角复核人')
  if (doc.operator === reviewer) {
    return ElMessage.error('经办人不能复核自己录入的资料')
  }
  try {
    await ElMessageBox.confirm(
      `确认 ${reviewer} 复核生效 V${doc.versionNo}？若该证照已有旧生效版本，旧版本将标记为「被新版本替代」并永久保留。`,
      '复核生效', { type: 'warning', confirmButtonText: '复核生效', cancelButtonText: '取消' })
  } catch (e) { return }
  try {
    const res = await certApi.approve({
      documentId: doc.id, reviewer, expectedLockVersion: doc.lockVersion
    })
    ElMessage.success('已复核生效')
    rejectDialog.value = false
    seriesDrawer.value = false
    await reload()
    if (res?.id) await refreshSeries(res.id)
  } catch (e) {
    await reload()
    if (seriesDetail.value) await refreshSeries(seriesDetail.value.id)
  }
}

async function confirmReject() {
  if (!rejectReason.value.trim()) return ElMessage.warning('驳回原因必填')
  try {
    await certApi.reject({
      documentId: rejectTarget.value.id,
      reviewer: operatorName.value,
      reason: rejectReason.value.trim(),
      expectedLockVersion: rejectTarget.value.lockVersion
    })
    ElMessage.success('已驳回')
    rejectDialog.value = false
    await reload()
    if (seriesDetail.value) await refreshSeries(seriesDetail.value.id)
  } catch (e) { await reload() }
}

function openRevoke(seriesRow, doc = null) {
  revokeSeries.value = seriesRow
  revokeDoc.value = doc || seriesRow.current
  revokeReason.value = ''
  revokeDialog.value = true
}

async function confirmRevoke() {
  if (!revokeReason.value.trim()) return ElMessage.warning('撤销原因必填')
  const affected = revokeSeries.value.affectedTrips || []
  const msg = affected.length
    ? `有 ${affected.length} 辆在途车正在使用该资料，撤销后将标记待调度处置：${affected
        .map((t) => t.plateNo).join('、')}。确认撤销？`
    : '确认撤销该生效资料？撤销后新派车将被拦截，直到新版本复核生效。'
  try {
    await ElMessageBox.confirm(msg, '撤销生效资料', {
      type: 'error', confirmButtonText: '确认撤销', cancelButtonText: '取消'
    })
  } catch (e) { return }
  try {
    await certApi.revoke({
      documentId: revokeDoc.value.id,
      operator: operatorName.value,
      reason: revokeReason.value.trim(),
      expectedLockVersion: revokeDoc.value.lockVersion
    })
    ElMessage.success('已撤销，受影响在途车已标记待调度处置')
    revokeDialog.value = false
    seriesDrawer.value = false
    await reload()
  } catch (e) { await reload() }
}

// ---------------- 出车快照 / 处置 ----------------
async function openSnapshot(row) {
  snapshotTrip.value = await certApi.tripDetail(row.id)
  snapshotDialog.value = true
}

function openDisposition(row) {
  dispositionTrip.value = row
  dispositionNote.value = ''
  dispositionDialog.value = true
}

async function confirmDisposition() {
  if (!dispositionNote.value.trim()) return ElMessage.warning('处置说明必填')
  try {
    await certApi.disposition(dispositionTrip.value.id, {
      tripId: dispositionTrip.value.id,
      operator: operatorName.value,
      note: dispositionNote.value.trim()
    })
    ElMessage.success('处置已登记')
    dispositionDialog.value = false
    await loadTrips()
  } catch (e) { /* ignore */ }
}

// ---------------- 登记司机 ----------------
function openCreateDriver() {
  Object.assign(driverForm, { driverNo: '', driverName: '', phone: '', status: 'ACTIVE' })
  driverDialog.value = true
}

async function submitDriver() {
  if (!driverForm.driverNo.trim() || !driverForm.driverName.trim()) {
    return ElMessage.warning('工号和姓名必填')
  }
  try {
    await certApi.createDriver({ ...driverForm })
    ElMessage.success('司机已建档')
    driverDialog.value = false
    await loadSubjects()
  } catch (e) { /* ignore */ }
}

onMounted(reload)
</script>

<style scoped>
.tab-count {
  display: inline-block;
  margin-left: 2px;
  font-weight: 700;
}
.tab-warn { color: var(--el-color-warning); }

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}

.ok { color: var(--el-color-success); }
.warn { color: var(--el-color-warning); }
.danger, .danger-text { color: var(--el-color-danger); }

:deep(.row-pending) { background: #fffbeb; }
:deep(.row-missing) { background: #fef0f0; }
:deep(.row-expiring) { background: #fff7e6; }

.rule-banner { margin-bottom: 10px; }

.review-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(330px, 1fr));
  gap: 12px;
}

.review-card {
  background: #fff;
  border: 1px solid var(--recy-line);
  border-left: 4px solid var(--el-color-warning);
  border-radius: 8px;
  padding: 10px 12px;
}

.review-card.self-card { border-left-color: var(--el-color-info); opacity: 0.92; }
.review-card header { display: flex; align-items: center; gap: 6px; margin-bottom: 8px; }

.rv-line {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
  padding: 2px 0;
}
.rv-line > span:first-child { color: var(--recy-ink-soft); }

.review-card footer {
  display: flex;
  gap: 8px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed #e8e0d8;
}

.self-tip {
  margin-top: 6px;
  font-size: 11px;
  color: var(--el-color-info);
}
.self-tip.inline { margin: 0 0 0 8px; }

.drawer-acts { margin-bottom: 12px; display: flex; gap: 8px; }

.version-card {
  background: #fff;
  border: 1px solid var(--recy-line);
  border-radius: 8px;
  padding: 10px 12px;
}
.version-card.st-effective { border-left: 4px solid var(--el-color-success); }
.version-card.st-await_review { border-left: 4px solid var(--el-color-warning); }
.version-card.st-revoked, .version-card.st-rejected { border-left: 4px solid var(--el-color-danger); }

.vc-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; flex-wrap: wrap; }
.vc-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 3px 12px;
  font-size: 12px;
  color: var(--recy-ink-soft);
}
.reason-box {
  margin-top: 6px;
  padding: 6px 8px;
  border-radius: 6px;
  font-size: 12px;
}
.reason-box.reject { background: #fef0f0; color: var(--el-color-danger); }
.reason-box.revoke { background: #fdecea; color: #b42318; }

.event-collapse { margin-top: 6px; }
.event-line {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  padding: 2px 0;
  flex-wrap: wrap;
}

.vc-acts { margin-top: 8px; display: flex; align-items: center; flex-wrap: wrap; }

.page-tools { display: flex; gap: 8px; align-items: center; }
.snap-desc { margin-bottom: 4px; }
</style>
