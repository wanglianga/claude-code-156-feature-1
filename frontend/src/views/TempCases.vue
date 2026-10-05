<template>
  <div class="page">
    <el-row :gutter="12">
      <el-col :span="15">
        <el-card shadow="never">
          <div class="card-title">温度超标处置单</div>
          <el-table :data="cases" size="small" highlight-current-row
                    @current-change="row => (selected = row)">
            <el-table-column prop="caseNo" label="处置单号" width="165" class="mono" />
            <el-table-column label="批次" width="160">
              <template #default="{ row }">{{ batchNo(row.batchId) }}</template>
            </el-table-column>
            <el-table-column label="到库温度" width="105">
              <template #default="{ row }">
                <span class="risk-critical">{{ row.arrivalTemp }}℃</span>
                <span class="hint">（合同 {{ row.contractMinTemp }}~{{ row.contractMaxTemp }}）</span>
              </template>
            </el-table-column>
            <el-table-column label="抽检腐损" width="80">
              <template #default="{ row }">{{ pct(row.sampleRotRate) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="105">
              <template #default="{ row }">
                <el-tag size="small" :type="tag(row.status)">{{ $dict('TempCaseStatus', row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="货主决策" width="100">
              <template #default="{ row }">
                <span v-if="row.ownerDecision">{{ $dict('OwnerDecision', row.ownerDecision) }}</span>
                <span v-else class="hint">待确认</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.status === 'PENDING'" link type="primary"
                           @click="openDecide(row)">货主决策</el-button>
                <el-button v-if="row.status === 'PRECOOLING'" link type="primary"
                           @click="openSchedule(row)">安排复检</el-button>
                <el-button v-if="row.status === 'RECHECK_SCHEDULED'" link type="danger"
                           @click="openRecheck(row)">复检登记</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>

        <el-card shadow="never" style="margin-top:12px" v-if="selected">
          <div class="card-title">
            处置单详情 — {{ selected.caseNo }}
            <el-tag size="small" :type="tag(selected.status)">{{ $dict('TempCaseStatus', selected.status) }}</el-tag>
          </div>
          <el-descriptions :column="2" size="small" border>
            <el-descriptions-item label="批次">{{ batchNo(selected.batchId) }}</el-descriptions-item>
            <el-descriptions-item label="记录质检员">{{ selected.inspector }}</el-descriptions-item>
            <el-descriptions-item label="到库温度">{{ selected.arrivalTemp }}℃（合同 {{ selected.contractMinTemp }}~{{ selected.contractMaxTemp }}℃）</el-descriptions-item>
            <el-descriptions-item label="到货时间">{{ fmt(selected.arrivedAt) }}</el-descriptions-item>
            <el-descriptions-item label="抽检腐损率">{{ pct(selected.sampleRotRate) }}</el-descriptions-item>
            <el-descriptions-item label="记录时间">{{ fmt(selected.recordedAt) }}</el-descriptions-item>
            <el-descriptions-item label="车厢照片" :span="2">
              <template v-if="photos.length">
                <div v-for="(p, i) in photos" :key="i">
                  <el-link :href="p" target="_blank" type="primary">照片 {{ i + 1 }}：{{ p }}</el-link>
                </div>
              </template>
              <span v-else class="hint">未上传</span>
            </el-descriptions-item>
            <template v-if="selected.ownerDecision">
              <el-descriptions-item label="货主决策">
                <el-tag size="small" type="warning">{{ $dict('OwnerDecision', selected.ownerDecision) }}</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="货主确认人">{{ selected.decidedBy }}（{{ fmt(selected.decidedAt) }}）</el-descriptions-item>
              <el-descriptions-item label="确认意见" :span="2">{{ selected.decisionNote || '-' }}</el-descriptions-item>
            </template>
            <template v-if="selected.ownerDecision === 'DOWNGRADE'">
              <el-descriptions-item label="降级等级">{{ $dict('Grade', selected.downgradeGrade) }}</el-descriptions-item>
              <el-descriptions-item label="售价折让/保鲜期">
                折让 {{ pct(selected.priceDiscount, 0) }} / {{ selected.shelfLifeDays }} 天
              </el-descriptions-item>
            </template>
            <template v-if="selected.precoolZoneId">
              <el-descriptions-item label="暂存温区">{{ zoneName(selected.precoolZoneId) }}</el-descriptions-item>
              <el-descriptions-item label="复检时段">{{ fmt(selected.recheckSlot) }}</el-descriptions-item>
              <el-descriptions-item label="暂存重量">{{ selected.precoolWeight }} kg</el-descriptions-item>
              <el-descriptions-item label="预冷费">{{ selected.precoolFee }} 元</el-descriptions-item>
            </template>
            <template v-if="selected.recheckResult">
              <el-descriptions-item label="复检结果">
                <el-tag size="small" :type="selected.recheckResult === 'PASS' ? 'success' : 'danger'">
                  {{ selected.recheckResult === 'PASS' ? '合格' : '不合格' }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="复检温度/腐损">
                {{ selected.recheckTemp }}℃ / {{ pct(selected.recheckRotRate) }}
              </el-descriptions-item>
              <el-descriptions-item label="复检说明" :span="2">{{ selected.recheckRemark || '-' }}</el-descriptions-item>
            </template>
          </el-descriptions>
        </el-card>
      </el-col>

      <el-col :span="9">
        <el-card shadow="never">
          <div class="card-title">质检员登记温度超标</div>
          <el-form :model="recordForm" label-width="100px">
            <el-form-item label="到货批次">
              <el-select v-model="recordForm.batchId" filterable style="width:100%" @change="onBatchPick">
                <el-option v-for="b in recordableBatches" :key="b.id" :value="b.id"
                           :label="`${b.batchNo} ${catName(b.categoryId)} ${b.arrivalTemp}℃${tempBad(b) ? '（超标）' : ''}`">
                  <span :class="tempBad(b) ? 'risk-critical' : ''">
                    {{ b.batchNo }} {{ catName(b.categoryId) }} 到库 {{ b.arrivalTemp }}℃
                    {{ tempBad(b) ? '（超标）' : '' }}
                  </span>
                </el-option>
              </el-select>
            </el-form-item>
            <el-alert v-if="pickedBatch && !tempBad(pickedBatch)" type="warning" :closable="false"
                      title="该批次到库温度未超合同区间，提交将被后端拒绝" style="margin-bottom:10px" />
            <el-row :gutter="10">
              <el-col :span="12">
                <el-form-item label="到库温度℃">
                  <el-input-number v-model="recordForm.arrivalTemp" :precision="1" style="width:100%" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="抽检腐损率%">
                  <el-input-number v-model="rotPct" :min="0" :max="100" :precision="1" style="width:100%" />
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item label="到货时间">
              <el-date-picker v-model="recordForm.arrivedAt" type="datetime" style="width:100%"
                              value-format="YYYY-MM-DDTHH:mm:ss" placeholder="默认为当前时间" />
            </el-form-item>
            <el-form-item label="车厢照片">
              <el-input v-model="recordForm.photoText" type="textarea" :rows="2"
                        placeholder="车厢照片 URL，每行一条（如监管平台/对象存储链接）" />
            </el-form-item>
            <el-form-item label="质检员">
              <el-input v-model="recordForm.inspector" placeholder="姓名" />
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="recordForm.remark" placeholder="车厢冷机故障、装卸等待时长等" />
            </el-form-item>
            <el-button type="primary" :loading="saving" @click="submitRecord">登记超标并通知货主决策</el-button>
          </el-form>
        </el-card>

        <el-card shadow="never" style="margin-top:12px">
          <div class="card-title">处置方式影响速查</div>
          <el-table :data="effectRows" size="small">
            <el-table-column prop="way" label="处置" width="90" />
            <el-table-column prop="effect" label="对保鲜期 / 仓储费 / 售价的影响" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- 货主决策 -->
    <el-dialog v-model="decideVisible" title="货主处置决策确认" width="560px">
      <template v-if="acting">
        <el-alert type="error" :closable="false" style="margin-bottom:12px"
                  :title="`批次 ${batchNo(acting.batchId)} 到库 ${acting.arrivalTemp}℃，超合同区间 [${acting.contractMinTemp}, ${acting.contractMaxTemp}]℃`" />
        <el-form :model="decideForm" label-width="110px">
          <el-form-item label="处置方式">
            <el-radio-group v-model="decideForm.decision">
              <el-radio-button value="DOWNGRADE">降级入库</el-radio-button>
              <el-radio-button value="RETURN">现场退货</el-radio-button>
              <el-radio-button value="PRECOOL">预冷后复检</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-alert :type="decideForm.decision === 'RETURN' ? 'warning' : 'info'" :closable="false"
                    style="margin-bottom:12px" :title="decideHint" />
          <template v-if="decideForm.decision === 'DOWNGRADE'">
            <el-row :gutter="10">
              <el-col :span="8">
                <el-form-item label="降级等级">
                  <el-select v-model="decideForm.downgradeGrade">
                    <el-option label="B级通货" value="B" />
                    <el-option label="加工级" value="PROCESS" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="售价折让%">
                  <el-input-number v-model="discountPct" :min="0" :max="99" :precision="0" style="width:100%" />
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="保鲜期(天)">
                  <el-input-number v-model="decideForm.shelfLifeDays" :min="1" :max="30" style="width:100%" />
                </el-form-item>
              </el-col>
            </el-row>
          </template>
          <el-form-item label="货主确认人">
            <el-input v-model="decideForm.decidedBy" placeholder="货主/合作社联系人姓名（责任判定依据，必填）" />
          </el-form-item>
          <el-form-item label="确认意见">
            <el-input v-model="decideForm.decisionNote" type="textarea" :rows="2"
                      placeholder="货主对温度超标的确认意见，将作为投诉责任判定依据" />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="decideVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitDecide">确认决策</el-button>
      </template>
    </el-dialog>

    <!-- 安排复检 -->
    <el-dialog v-model="scheduleVisible" title="安排预冷复检（复检前批次冻结销售）" width="480px">
      <el-form :model="scheduleForm" label-width="100px" v-if="acting">
        <el-form-item label="复检时段">
          <el-date-picker v-model="scheduleForm.recheckSlot" type="datetime" style="width:100%"
                          value-format="YYYY-MM-DDTHH:mm:ss" placeholder="选择复检时间" />
        </el-form-item>
        <el-form-item label="暂存温区">
          <el-select v-model="scheduleForm.zoneId" style="width:100%">
            <el-option v-for="z in usableZones" :key="z.id" :value="z.id"
                       :label="`${z.name}（${$dict('ZoneType', z.type)} 设定${z.targetTemp}℃ 可用${z.capacityKg - z.occupiedKg}kg）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="经办人">
          <el-input v-model="scheduleForm.operator" placeholder="调度员" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scheduleVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitSchedule">确认安排</el-button>
      </template>
    </el-dialog>

    <!-- 复检登记 -->
    <el-dialog v-model="recheckVisible" title="预冷后复检登记" width="500px">
      <template v-if="acting">
        <el-alert type="warning" :closable="false" style="margin-bottom:12px"
                  title="复检不合格将：批次退货关闭、自动撤回已分配客户的出库计划，销售员需重新选择替代批次并向客户说明到货差异" />
        <el-form :model="recheckForm" label-width="100px">
          <el-row :gutter="10">
            <el-col :span="12">
              <el-form-item label="复检温度℃">
                <el-input-number v-model="recheckForm.recheckTemp" :precision="1" style="width:100%" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="复检腐损率%">
                <el-input-number v-model="recheckRotPct" :min="0" :max="100" :precision="1" style="width:100%" />
              </el-form-item>
            </el-col>
          </el-row>
          <el-form-item label="复检结论">
            <el-radio-group v-model="recheckForm.result">
              <el-radio-button value="PASS">合格（解除冻结）</el-radio-button>
              <el-radio-button value="FAIL">不合格（撤回出库计划）</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="复检员">
            <el-input v-model="recheckForm.inspector" />
          </el-form-item>
          <el-form-item label="说明">
            <el-input v-model="recheckForm.remark" type="textarea" :rows="2" />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="recheckVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitRecheck">提交复检结果</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { STATUS_TAG } from '../constants'

const cases = ref([])
const batches = ref([])
const categories = ref([])
const zones = ref([])
const selected = ref(null)
const saving = ref(false)

const recordForm = reactive({
  batchId: null, arrivalTemp: null, arrivedAt: null,
  photoText: '', inspector: '', remark: ''
})
const rotPct = ref(5)

const decideVisible = ref(false)
const scheduleVisible = ref(false)
const recheckVisible = ref(false)
const acting = ref(null)
const decideForm = reactive({
  decision: 'PRECOOL', decidedBy: '', decisionNote: '',
  downgradeGrade: 'B', shelfLifeDays: 2
})
const discountPct = ref(20)
const scheduleForm = reactive({ recheckSlot: null, zoneId: null, operator: '调度员' })
const recheckForm = reactive({ recheckTemp: null, result: 'PASS', inspector: '', remark: '' })
const recheckRotPct = ref(5)

const effectRows = [
  { way: '降级入库', effect: '保鲜期缩短（默认 2 天）；仓储费正常计；售价按基准价折让' },
  { way: '现场退货', effect: '批次关闭：无保鲜期、不计仓储费、无后续售价' },
  { way: '预冷复检', effect: '复检前冻结销售；按暂存重量计预冷费；合格恢复正常售价，不合格撤回出库计划' }
]

const recordableBatches = computed(() =>
  batches.value.filter(b => !['RETURNED', 'OUT'].includes(b.status)))
const pickedBatch = computed(() => batches.value.find(b => b.id === recordForm.batchId))
const usableZones = computed(() => zones.value.filter(z => !z.fault))
const photos = computed(() =>
  (selected.value?.photoUrls || '').split(',').map(s => s.trim()).filter(Boolean))

const decideHint = computed(() => ({
  DOWNGRADE: '降级入库：保鲜期缩短、售价按基准价折让，仓储费按正常费率计收',
  RETURN: '现场退货：批次关闭，不产生保鲜期/仓储费/售价；已分配出库计划将自动撤回',
  PRECOOL: '预冷后复检：平台将安排复检时段与暂存温区，复检前冻结该批次对外销售，并按暂存重量计收预冷费'
}[decideForm.decision]))

watch(discountPct, v => { decideForm.priceDiscount = Number((v / 100).toFixed(4)) })

function batchNo(id) { return batches.value.find(b => b.id === id)?.batchNo || '#' + id }
function catName(id) { return categories.value.find(c => c.id === id)?.name || id }
function zoneName(id) { return zones.value.find(z => z.id === id)?.name || '#' + id }
function tag(s) { return STATUS_TAG[s] || 'info' }
function fmt(t) { return t ? t.replace('T', ' ').slice(0, 16) : '-' }
function pct(v, digits = 1) {
  return v == null ? '-' : (Number(v) * 100).toFixed(digits) + '%'
}
function tempBad(b) {
  const cat = categories.value.find(c => c.id === b.categoryId)
  if (!cat) return false
  return Number(b.arrivalTemp) > Number(cat.maxArrivalTemp)
      || Number(b.arrivalTemp) < Number(cat.minArrivalTemp)
}

function onBatchPick(id) {
  const b = batches.value.find(x => x.id === id)
  if (b) recordForm.arrivalTemp = Number(b.arrivalTemp)
}

async function submitRecord() {
  if (!recordForm.batchId || recordForm.arrivalTemp == null) {
    ElMessage.warning('请选择批次并填写到库温度')
    return
  }
  if (!recordForm.inspector) {
    ElMessage.warning('请填写质检员姓名')
    return
  }
  saving.value = true
  try {
    const body = {
      batchId: recordForm.batchId,
      arrivalTemp: recordForm.arrivalTemp,
      arrivedAt: recordForm.arrivedAt || null,
      photoUrls: recordForm.photoText.split('\n').map(s => s.trim()).filter(Boolean),
      sampleRotRate: Number((rotPct.value / 100).toFixed(4)),
      inspector: recordForm.inspector,
      remark: recordForm.remark
    }
    const c = await api.post('/api/temp-cases', body)
    ElMessage.success('已登记 ' + c.caseNo + '，等待货主决策')
    Object.assign(recordForm, { batchId: null, arrivalTemp: null, arrivedAt: null, photoText: '', remark: '' })
    await load()
    selected.value = cases.value.find(x => x.id === c.id) || null
  } finally {
    saving.value = false
  }
}

function openDecide(row) {
  acting.value = row
  Object.assign(decideForm, {
    decision: 'PRECOOL', decidedBy: '', decisionNote: '',
    downgradeGrade: 'B', shelfLifeDays: 2, priceDiscount: 0.2
  })
  discountPct.value = 20
  decideVisible.value = true
}

async function submitDecide() {
  if (!decideForm.decidedBy) {
    ElMessage.warning('请填写货主确认人（责任判定依据）')
    return
  }
  saving.value = true
  try {
    await api.post(`/api/temp-cases/${acting.value.id}/decide`, decideForm)
    ElMessage.success('货主决策已确认')
    decideVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

function openSchedule(row) {
  acting.value = row
  Object.assign(scheduleForm, { recheckSlot: null, zoneId: usableZones.value[0]?.id ?? null, operator: '调度员' })
  scheduleVisible.value = true
}

async function submitSchedule() {
  if (!scheduleForm.recheckSlot || !scheduleForm.zoneId) {
    ElMessage.warning('请选择复检时段与暂存温区')
    return
  }
  saving.value = true
  try {
    await api.post(`/api/temp-cases/${acting.value.id}/schedule`, scheduleForm)
    ElMessage.success('复检已安排，暂存温区容量已占用')
    scheduleVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

function openRecheck(row) {
  acting.value = row
  Object.assign(recheckForm, { recheckTemp: null, result: 'PASS', inspector: '', remark: '' })
  recheckRotPct.value = 3
  recheckVisible.value = true
}

async function submitRecheck() {
  if (recheckForm.recheckTemp == null) {
    ElMessage.warning('请填写复检温度')
    return
  }
  saving.value = true
  try {
    const c = await api.post(`/api/temp-cases/${acting.value.id}/recheck`, {
      ...recheckForm,
      recheckRotRate: Number((recheckRotPct.value / 100).toFixed(4))
    })
    ElMessage[c.recheckResult === 'PASS' ? 'success' : 'warning'](
      c.recheckResult === 'PASS' ? '复检合格，批次已解除销售冻结' : '复检不合格：批次退货关闭，出库计划已自动撤回')
    recheckVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function load() {
  const keepId = selected.value?.id
  const [cs, bs, zs] = await Promise.all([
    api.get('/api/temp-cases'),
    api.get('/api/batches'),
    api.get('/api/masters/zones')
  ])
  cases.value = cs
  batches.value = bs
  zones.value = zs
  selected.value = cases.value.find(x => x.id === keepId) || cases.value[0] || null
}

onMounted(async () => {
  categories.value = await api.get('/api/masters/categories')
  await load()
})
</script>

<style scoped>
.hint { color: #909daa; font-size: 12px; }
</style>
