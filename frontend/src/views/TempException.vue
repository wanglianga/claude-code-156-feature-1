<template>
  <div class="page">
    <el-row :gutter="12">
      <el-col :span="9">
        <el-card shadow="never">
          <div class="card-title">到货温度超标批次</div>
          <el-table :data="exceededBatches" size="small" highlight-current-row
                    @current-change="onSelectBatch" :row-class-name="rowClass">
            <el-table-column prop="batchNo" label="批次号" width="165" class="mono" />
            <el-table-column label="品类" width="90">
              <template #default="{ row }">{{ catName(row.categoryId) }}</template>
            </el-table-column>
            <el-table-column label="到货温度" width="80">
              <template #default="{ row }"><span class="risk-critical">{{ row.arrivalTemp }}℃</span></template>
            </el-table-column>
            <el-table-column label="状态" width="95">
              <template #default="{ row }">
                <el-tag size="small" :type="STATUS_TAG[row.status] || 'info'">
                  {{ $dict('BatchStatus', row.status) }}
                </el-tag>
                <el-tag v-if="row.saleFrozen" size="small" type="danger" effect="plain">冻结</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="处置单" width="90">
              <template #default="{ row }">
                <el-tag v-if="recordOf(row.id)" size="small"
                        :type="STATUS_TAG[recordOf(row.id).status] || 'info'">
                  {{ $dict('TempExceptionStatus', recordOf(row.id).status) }}
                </el-tag>
                <el-button v-else-if="row.status === 'REGISTERED'" link type="primary"
                           @click.stop="openCreate(row)">登记</el-button>
                <span v-else class="hint">未登记</span>
              </template>
            </el-table-column>
          </el-table>
          <div class="hint" style="margin-top:8px">
            仅列出让到货温度超出品类合同区间的批次；待检批次可登记温度超标处置单。
          </div>
        </el-card>

        <el-card shadow="never" style="margin-top:12px">
          <div class="card-title">全部处置单</div>
          <el-table :data="records" size="small" highlight-current-row
                    @current-change="onSelectRecord" :row-class-name="r => current && r.row.id === current.id ? 'sel' : ''">
            <el-table-column prop="recordNo" label="单号" width="165" class="mono" />
            <el-table-column label="批次" width="165">
              <template #default="{ row }">{{ batchNo(row.batchId) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="STATUS_TAG[row.status] || 'info'">
                  {{ $dict('TempExceptionStatus', row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="抉择" width="100">
              <template #default="{ row }">
                {{ row.decision ? $dict('TempDecision', row.decision) : '—' }}
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <el-col :span="15">
        <el-card shadow="never" v-if="current">
          <div class="card-title">
            处置单 {{ current.recordNo }} — {{ batchNo(current.batchId) }}
            <el-tag size="small" :type="STATUS_TAG[current.status] || 'info'">
              {{ $dict('TempExceptionStatus', current.status) }}
            </el-tag>
          </div>
          <el-descriptions :column="3" size="small" border>
            <el-descriptions-item label="复测温度">
              <span class="risk-critical">{{ current.measuredTemp }}℃</span>
            </el-descriptions-item>
            <el-descriptions-item label="合同区间">
              {{ current.contractMinTemp }}~{{ current.contractMaxTemp }}℃
            </el-descriptions-item>
            <el-descriptions-item label="到货时间">{{ fmt(current.arrivedAt) }}</el-descriptions-item>
            <el-descriptions-item label="抽检腐损率">
              {{ current.sampledRotRate != null ? (current.sampledRotRate * 100).toFixed(1) + '%' : '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="质检员">{{ current.inspector }}</el-descriptions-item>
            <el-descriptions-item label="备注">{{ current.remark || '—' }}</el-descriptions-item>
            <el-descriptions-item label="车厢照片" :span="3">
              <template v-if="photos.length">
                <el-image v-for="(p, i) in photos" :key="i" :src="p" :preview-src-list="photos"
                          :initial-index="i" fit="cover" preview-teleported
                          style="width:72px;height:72px;border-radius:6px;margin-right:8px" />
              </template>
              <span v-else class="hint">未上传</span>
            </el-descriptions-item>
          </el-descriptions>

          <!-- 货主处置抉择 -->
          <template v-if="current.status === 'PENDING_DECISION'">
            <el-divider content-position="left">货主处置抉择（不同选择影响保鲜期 / 仓储费 / 后续售价）</el-divider>
            <el-row :gutter="10">
              <el-col :span="8" v-for="opt in options" :key="opt.decision">
                <div class="opt-card" :class="{ active: decisionForm.decision === opt.decision }"
                     @click="decisionForm.decision = opt.decision">
                  <div class="opt-title">
                    <el-radio :model-value="decisionForm.decision" :value="opt.decision"
                              @change="decisionForm.decision = opt.decision">{{ opt.decisionDesc }}</el-radio>
                  </div>
                  <div class="opt-line">保鲜期：<b>{{ opt.shelfLifeDays }}</b> 天</div>
                  <div class="opt-line">仓储费：<b>{{ opt.storageFeeRate }}</b> 元/kg·天</div>
                  <div class="opt-line" v-if="opt.precoolFeeRate">
                    预冷费：+<b>{{ opt.precoolFeeRate }}</b> 元/kg·天
                  </div>
                  <div class="opt-line">预计售价：<b>{{ opt.expectedUnitPrice }}</b> 元/kg</div>
                  <div class="opt-note">{{ opt.effectNote }}</div>
                </div>
              </el-col>
            </el-row>

            <el-form :model="decisionForm" label-width="110px" style="margin-top:12px">
              <el-row :gutter="10">
                <el-col :span="12" v-if="decisionForm.decision === 'DOWNGRADE_ACCEPT'">
                  <el-form-item label="降级等级">
                    <el-radio-group v-model="decisionForm.downgradeGrade">
                      <el-radio-button value="B">B级通货</el-radio-button>
                      <el-radio-button value="PROCESS">加工级</el-radio-button>
                    </el-radio-group>
                  </el-form-item>
                </el-col>
                <template v-if="decisionForm.decision === 'PRECOOL_RECHECK'">
                  <el-col :span="12">
                    <el-form-item label="暂存温区" required>
                      <el-select v-model="decisionForm.precoolZoneId" style="width:100%">
                        <el-option v-for="z in zones.filter(z => !z.fault)" :key="z.id"
                                   :label="`${z.name}（${$dict('ZoneType', z.type)} 余${z.capacityKg - z.occupiedKg}kg）`"
                                   :value="z.id" />
                      </el-select>
                    </el-form-item>
                  </el-col>
                  <el-col :span="12">
                    <el-form-item label="复检时段" required>
                      <el-date-picker v-model="decisionForm.recheckSlotAt" type="datetime"
                                      value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" />
                    </el-form-item>
                  </el-col>
                </template>
              </el-row>
              <el-row :gutter="10">
                <el-col :span="12">
                  <el-form-item label="货主确认人" required>
                    <el-input v-model="decisionForm.decidedBy" placeholder="货主签字/姓名" />
                  </el-form-item>
                </el-col>
                <el-col :span="12">
                  <el-form-item label="确认备注">
                    <el-input v-model="decisionForm.ownerConfirmNote"
                              placeholder="如：已知悉温度超标，同意降级入库" />
                  </el-form-item>
                </el-col>
              </el-row>
              <el-button type="primary" :loading="saving" @click="submitDecision">
                确认处置（{{ $dict('TempDecision', decisionForm.decision) }}）
              </el-button>
            </el-form>
          </template>

          <!-- 预冷待复检 -->
          <template v-if="current.status === 'PRECOOLING'">
            <el-divider content-position="left">预冷暂存 · 复检安排</el-divider>
            <el-alert type="warning" :closable="false" show-icon
                      :title="`批次已冻结对外销售：暂存 ${zoneName(current.precoolZoneId)}/${locCode(current.precoolLocationId)}，复检时段 ${fmt(current.recheckSlotAt)}`" />
            <el-form :model="recheckForm" label-width="110px" style="margin-top:14px">
              <el-row :gutter="10">
                <el-col :span="8">
                  <el-form-item label="复测温度℃" required>
                    <el-input-number v-model="recheckForm.recheckTemp" :precision="1" style="width:100%" />
                  </el-form-item>
                </el-col>
                <el-col :span="8">
                  <el-form-item label="复检腐损率%">
                    <el-input-number v-model="recheckRotPct" :min="0" :max="100" :precision="1"
                                     style="width:100%" />
                  </el-form-item>
                </el-col>
                <el-col :span="8">
                  <el-form-item label="复检人">
                    <el-input v-model="recheckForm.recheckBy" placeholder="质检员" />
                  </el-form-item>
                </el-col>
              </el-row>
              <el-form-item label="判定结果">
                <el-tag :type="autoPass ? 'success' : 'danger'" effect="dark">
                  {{ autoPass ? '复检合格（温度回到合同区间）' : '复检不合格（温度仍超标）' }}
                </el-tag>
                <el-switch v-model="manualResult" active-text="人工指定"
                           style="margin-left:12px" />
                <el-radio-group v-if="manualResult" v-model="recheckForm.passed" style="margin-left:12px">
                  <el-radio :value="true">合格</el-radio>
                  <el-radio :value="false">不合格</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-form-item label="复检备注">
                <el-input v-model="recheckForm.note" placeholder="预冷效果、果面状态等" />
              </el-form-item>
              <el-button type="primary" :loading="saving" @click="submitRecheck">提交复检结果</el-button>
              <span class="hint" v-if="!autoPass">
                不合格将整批退货，并自动撤回该批次已排单的出库计划
              </span>
            </el-form>
          </template>

          <!-- 已办结 -->
          <template v-if="current.status === 'CLOSED'">
            <el-divider content-position="left">处置结果</el-divider>
            <el-descriptions :column="2" size="small" border>
              <el-descriptions-item label="货主抉择">
                <el-tag size="small">{{ $dict('TempDecision', current.decision) }}</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="确认人">{{ current.decidedBy }}（{{ fmt(current.decidedAt) }}）</el-descriptions-item>
              <el-descriptions-item label="确认备注" :span="2">{{ current.ownerConfirmNote || '—' }}</el-descriptions-item>
              <el-descriptions-item label="影响口径" :span="2">{{ current.effectNote || '—' }}</el-descriptions-item>
              <template v-if="current.decision === 'PRECOOL_RECHECK'">
                <el-descriptions-item label="复检结果">
                  <el-tag size="small" :type="current.recheckResult === 'PASSED' ? 'success' : 'danger'">
                    {{ $dict('RecheckResult', current.recheckResult) }}
                  </el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="复测温度">{{ current.recheckTemp }}℃（{{ fmt(current.recheckedAt) }}）</el-descriptions-item>
                <el-descriptions-item label="复检备注" :span="2">{{ current.recheckNote || '—' }}</el-descriptions-item>
              </template>
            </el-descriptions>
          </template>
        </el-card>
        <el-card shadow="never" v-else>
          <el-empty description="选择左侧超标批次或处置单" :image-size="80" />
        </el-card>
      </el-col>
    </el-row>

    <!-- 登记处置单 -->
    <el-dialog v-model="createVisible" title="登记温度超标处置单" width="560px">
      <el-form :model="createForm" label-width="110px" v-if="createBatch">
        <el-form-item label="批次">
          {{ createBatch.batchNo }}（{{ catName(createBatch.categoryId) }} / 净重 {{ createBatch.netWeight }}kg）
        </el-form-item>
        <el-form-item label="复测温度℃" required>
          <el-input-number v-model="createForm.measuredTemp" :precision="1" style="width:200px" />
          <span class="hint">合同区间 {{ catOf(createBatch)?.minArrivalTemp }}~{{ catOf(createBatch)?.maxArrivalTemp }}℃</span>
        </el-form-item>
        <el-form-item label="到货时间">
          <el-date-picker v-model="createForm.arrivedAt" type="datetime"
                          value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" />
        </el-form-item>
        <el-form-item label="抽检腐损率%">
          <el-input-number v-model="createRotPct" :min="0" :max="100" :precision="1" style="width:200px" />
        </el-form-item>
        <el-form-item label="车厢照片">
          <el-input v-model="createForm.cabinPhotosText" type="textarea" :rows="3"
                    placeholder="车厢照片链接，每行一个（演示环境直接填写图片 URL）" />
        </el-form-item>
        <el-form-item label="质检员">
          <el-input v-model="createForm.inspector" placeholder="姓名" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.remark" placeholder="车厢温度记录、冰袋融化情况等" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitCreate">登记并通知货主处置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { STATUS_TAG } from '../constants'

const batches = ref([])
const categories = ref([])
const zones = ref([])
const locations = ref([])
const records = ref([])
const current = ref(null)
const options = ref([])
const saving = ref(false)

const createVisible = ref(false)
const createBatch = ref(null)
const createRotPct = ref(5)
const createForm = reactive({
  measuredTemp: null, arrivedAt: null, cabinPhotosText: '', inspector: '', remark: ''
})

const decisionForm = reactive({
  decision: 'DOWNGRADE_ACCEPT', downgradeGrade: 'B',
  precoolZoneId: null, recheckSlotAt: null, decidedBy: '', ownerConfirmNote: ''
})

const recheckForm = reactive({ recheckTemp: null, recheckRotRate: null, recheckBy: '', note: '', passed: null })
const recheckRotPct = ref(3)
const manualResult = ref(false)

watch(recheckRotPct, v => recheckForm.recheckRotRate = Number((v / 100).toFixed(4)))

const exceededBatches = computed(() =>
  batches.value.filter(b => {
    const c = categories.value.find(x => x.id === b.categoryId)
    if (!c) return false
    return Number(b.arrivalTemp) > Number(c.maxArrivalTemp) || Number(b.arrivalTemp) < Number(c.minArrivalTemp)
  }))

const photos = computed(() =>
  current.value?.cabinPhotos ? current.value.cabinPhotos.split(',').filter(Boolean) : [])

const autoPass = computed(() => {
  if (!current.value || recheckForm.recheckTemp == null) return false
  const t = Number(recheckForm.recheckTemp)
  return t >= Number(current.value.contractMinTemp) && t <= Number(current.value.contractMaxTemp)
})

function catOf(b) { return categories.value.find(c => c.id === b?.categoryId) }
function catName(id) { return categories.value.find(c => c.id === id)?.name || id }
function batchNo(id) { return batches.value.find(b => b.id === id)?.batchNo || '#' + id }
function recordOf(batchId) { return records.value.find(r => r.batchId === batchId) }
function zoneName(id) { return zones.value.find(z => z.id === id)?.name || '#' + id }
function locCode(id) { return locations.value.find(l => l.id === id)?.code || '#' + id }
function fmt(t) { return t ? String(t).replace('T', ' ').slice(0, 16) : '—' }
function rowClass({ row }) { return current.value && recordOf(row.id)?.id === current.value.id ? 'sel' : '' }

async function onSelectBatch(row) {
  if (!row) return
  const rec = recordOf(row.id)
  if (rec) await selectRecord(rec)
}

async function onSelectRecord(row) {
  if (row) await selectRecord(row)
}

async function selectRecord(rec) {
  current.value = await api.get('/api/temp-exceptions/' + rec.id)
  if (current.value.status === 'PENDING_DECISION') {
    options.value = await api.get(`/api/temp-exceptions/${rec.id}/options`)
    Object.assign(decisionForm, {
      decision: 'DOWNGRADE_ACCEPT', downgradeGrade: 'B',
      precoolZoneId: zones.value.find(z => !z.fault)?.id ?? null,
      recheckSlotAt: defaultRecheckSlot(), decidedBy: '', ownerConfirmNote: ''
    })
  }
  if (current.value.status === 'PRECOOLING') {
    recheckForm.recheckTemp = Number(current.value.measuredTemp)
    recheckForm.recheckBy = current.value.inspector || ''
    recheckForm.note = ''
    recheckForm.passed = null
    manualResult.value = false
  }
}

function defaultRecheckSlot() {
  const d = new Date(Date.now() + 4 * 3600 * 1000)
  const p = n => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:00`
}

function openCreate(batch) {
  createBatch.value = batch
  Object.assign(createForm, {
    measuredTemp: Number(batch.arrivalTemp),
    arrivedAt: batch.arrivedAt ? String(batch.arrivedAt).slice(0, 19) : null,
    cabinPhotosText: '', inspector: '', remark: ''
  })
  createRotPct.value = 5
  createVisible.value = true
}

async function submitCreate() {
  if (!createForm.inspector) {
    ElMessage.warning('请填写质检员姓名')
    return
  }
  saving.value = true
  try {
    const photos = createForm.cabinPhotosText.split('\n').map(s => s.trim()).filter(Boolean)
    const rec = await api.post('/api/temp-exceptions', {
      batchId: createBatch.value.id,
      measuredTemp: createForm.measuredTemp,
      arrivedAt: createForm.arrivedAt || null,
      cabinPhotos: photos,
      sampledRotRate: Number((createRotPct.value / 100).toFixed(4)),
      inspector: createForm.inspector,
      remark: createForm.remark
    })
    ElMessage.success('处置单已登记 ' + rec.recordNo)
    createVisible.value = false
    await reload()
    await selectRecord(rec)
  } finally {
    saving.value = false
  }
}

async function submitDecision() {
  if (!decisionForm.decidedBy) {
    ElMessage.warning('请填写货主确认人')
    return
  }
  saving.value = true
  try {
    const rec = await api.post(`/api/temp-exceptions/${current.value.id}/decision`, { ...decisionForm })
    ElMessage.success('货主处置已确认：' + rec.effectNote)
    await reload()
    await selectRecord(rec)
  } finally {
    saving.value = false
  }
}

async function submitRecheck() {
  if (recheckForm.recheckTemp == null) {
    ElMessage.warning('请填写复测温度')
    return
  }
  saving.value = true
  try {
    const body = { ...recheckForm, passed: manualResult.value ? recheckForm.passed : null }
    const rec = await api.post(`/api/temp-exceptions/${current.value.id}/recheck`, body)
    ElMessage[rec.recheckResult === 'PASSED' ? 'success' : 'warning'](
      rec.recheckResult === 'PASSED'
        ? '复检合格，批次已解除冻结并转待上架'
        : '复检不合格，批次整批退货，已排出库计划自动撤回')
    await reload()
    await selectRecord(rec)
  } finally {
    saving.value = false
  }
}

async function reload() {
  const [b, r] = await Promise.all([
    api.get('/api/batches'),
    api.get('/api/temp-exceptions')
  ])
  batches.value = b
  records.value = r
}

onMounted(async () => {
  const [cat, z, loc] = await Promise.all([
    api.get('/api/masters/categories'),
    api.get('/api/masters/zones'),
    api.get('/api/masters/locations')
  ])
  categories.value = cat
  zones.value = z
  locations.value = loc
  await reload()
  if (records.value.length) await selectRecord(records.value[0])
})
</script>

<style scoped>
.hint { color: #909daa; font-size: 12px; margin-left: 8px; }
.opt-card {
  border: 1px solid #e3e9ee; border-radius: 8px; padding: 10px 12px; cursor: pointer;
  transition: all .15s;
}
.opt-card.active { border-color: #1f6f54; background: #eef7f2; }
.opt-title { font-weight: 600; margin-bottom: 6px; }
.opt-line { font-size: 12px; color: #4a5560; line-height: 1.9; }
.opt-note { font-size: 12px; color: #909daa; margin-top: 6px; line-height: 1.6; }
:deep(.sel > td) { background: #e8f4ef !important; }
</style>
