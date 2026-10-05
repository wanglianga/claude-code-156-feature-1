<template>
  <div class="page">
    <el-row :gutter="12">
      <el-col :span="15">
        <el-card shadow="never">
          <div class="card-title">出库排单 / 执行</div>
          <el-radio-group v-model="tab" size="small" style="margin-bottom:10px" @change="loadOrders">
            <el-radio-button value="PLANNED">待出库（{{ orders.length }}）</el-radio-button>
            <el-radio-button value="OUT">已出库</el-radio-button>
            <el-radio-button value="WITHDRAWN">已撤回</el-radio-button>
            <el-radio-button value="">全部</el-radio-button>
          </el-radio-group>
          <el-table :data="orders" size="small">
            <el-table-column prop="orderNo" label="出库单号" width="170" class="mono" />
            <el-table-column label="客户" width="150">
              <template #default="{ row }">
                {{ customerName(row.customerId) }}
                <el-tag v-if="row.customerChanged" type="warning" size="small">临时改</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="批次" width="160">
              <template #default="{ row }">
                {{ batchNo(row.batchId) }}
                <el-tag v-if="frozenOf(row.batchId)" type="danger" size="small" effect="plain">冻结</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="totalWeight" label="重量kg" width="85" />
            <el-table-column prop="totalAmount" label="金额元" width="100" />
            <el-table-column label="标记" width="140">
              <template #default="{ row }">
                <el-tag v-if="row.priority" type="danger" size="small">优先</el-tag>
                <el-tag v-if="row.priceLocked" type="warning" size="small">
                  锁价{{ row.lockedPrice }}
                </el-tag>
                <el-tag v-if="row.substitutedFromOrderId" type="success" size="small" effect="plain">替代单</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态/操作" min-width="180">
              <template #default="{ row }">
                <template v-if="row.status === 'PLANNED'">
                  <el-tag type="warning" size="small">待出库</el-tag>
                  <el-tooltip v-if="frozenOf(row.batchId)" content="批次预冷待复检，已冻结对外销售"
                              placement="top">
                    <el-button link type="info" disabled>执行出库</el-button>
                  </el-tooltip>
                  <el-button v-else link type="primary" @click="execute(row)">执行出库</el-button>
                </template>
                <template v-else-if="row.status === 'WITHDRAWN'">
                  <el-tooltip :content="row.withdrawReason" placement="top">
                    <el-tag type="info" size="small">已撤回</el-tag>
                  </el-tooltip>
                  <el-button v-if="!row.substitutedByOrderId" link type="danger"
                             @click="openSubstitute(row)">替代重排</el-button>
                  <el-tag v-else size="small" type="success" effect="plain">已重排</el-tag>
                </template>
                <template v-else>
                  <el-tag type="success" size="small">已出库</el-tag>
                  <el-button link type="primary" @click="openComplaint(row)">投诉</el-button>
                </template>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <el-col :span="9">
        <el-card shadow="never">
          <div class="card-title">新建出库排单</div>
          <el-form :model="form" label-width="92px">
            <el-form-item label="批次">
              <el-select v-model="form.batchId" filterable style="width:100%" @change="onBatchChange">
                <el-option v-for="b in outBatches" :key="b.id"
                           :label="`${b.batchNo} ${catName(b.categoryId)} 可售${b.netWeight - b.outWeight - b.lossWeight}kg${b.status === 'PRECOOLING' ? '（预冷待复检·冻结发货）' : ''}`"
                           :value="b.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="客户">
              <el-select v-model="form.customerId" filterable style="width:100%">
                <el-option v-for="c in customers" :key="c.id"
                           :label="c.name + (c.processingChannel ? '（加工渠道）' : '')"
                           :value="c.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="优先出库">
              <el-switch v-model="form.priority" active-text="客户催货，优先排单" />
            </el-form-item>

            <el-table :data="form.items" size="small" border style="margin-bottom:10px">
              <el-table-column label="货位/等级" min-width="150">
                <template #default="{ row }">
                  <el-select v-model="row.lotId" size="small" @change="onLotChange(row)">
                    <el-option v-for="l in availableLots" :key="l.id"
                               :label="`${lotZone(l.locationId)} ${$dict('Grade', l.grade)} ${l.currentWeight}kg@${l.unitPrice}${l.status === 'PRECOOL' ? '（预冷暂存）' : ''}`"
                               :value="l.id" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="重量kg" width="130">
                <template #default="{ row }">
                  <el-input-number v-model="row.weight" :min="0" :precision="2" size="small"
                                   :max="lotWeight(row.lotId)" />
                </template>
              </el-table-column>
              <el-table-column label="单价" width="120">
                <template #default="{ row }">
                  <el-input-number v-model="row.unitPrice" :min="0" :precision="2" size="small"
                                   placeholder="锁价" />
                </template>
              </el-table-column>
            </el-table>
            <el-button size="small" @click="form.items.push({ lotId: null, weight: 0, unitPrice: null })">
              添加货位
            </el-button>

            <el-form-item label="备注" style="margin-top:10px">
              <el-input v-model="form.remark" />
            </el-form-item>
            <el-button type="primary" :loading="saving" @click="plan">排单</el-button>
          </el-form>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="complaintVisible" title="出库后客户投诉登记" width="520px">
      <el-form :model="complaintForm" label-width="100px" v-if="complaintOrder">
        <el-form-item label="出库单">{{ complaintOrder.orderNo }} → {{ customerName(complaintOrder.customerId) }}</el-form-item>
        <el-form-item label="投诉类型">
          <el-select v-model="complaintForm.type" style="width:100%">
            <el-option label="坏果腐损" value="BAD_FRUIT" />
            <el-option label="短斤少两" value="SHORT_WEIGHT" />
            <el-option label="温度失控" value="TEMP_ABUSE" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="问题重量kg">
          <el-input-number v-model="complaintForm.problemWeight" :min="0" :precision="2" />
        </el-form-item>
        <el-form-item label="索赔金额元">
          <el-input-number v-model="complaintForm.claimAmount" :min="0" :precision="2" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="complaintForm.description" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="complaintVisible = false">取消</el-button>
        <el-button type="primary" @click="submitComplaint">提交投诉</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="substituteVisible" title="撤回单替代重排（销售员）" width="640px">
      <template v-if="substituteOrder">
        <el-alert type="warning" :closable="false" show-icon style="margin-bottom:12px"
                  :title="`原单 ${substituteOrder.orderNo} → ${customerName(substituteOrder.customerId)}（${substituteOrder.totalWeight}kg）已撤回：${substituteOrder.withdrawReason || ''}`" />
        <el-form :model="substituteForm" label-width="110px">
          <el-form-item label="替代批次" required>
            <el-select v-model="substituteForm.batchId" filterable style="width:100%"
                       @change="substituteForm.items = [{ lotId: null, weight: substituteOrder.totalWeight, unitPrice: null }]">
              <el-option v-for="b in substituteBatches" :key="b.id"
                         :label="`${b.batchNo} ${catName(b.categoryId)} 在库可售`"
                         :value="b.id" />
            </el-select>
            <div class="hint">须与原批次同品类、在库可售且未冻结</div>
          </el-form-item>
          <el-form-item label="出库货位">
            <el-table :data="substituteForm.items" size="small" border>
              <el-table-column label="货位/等级" min-width="180">
                <template #default="{ row }">
                  <el-select v-model="row.lotId" size="small" @change="onSubstituteLotChange(row)">
                    <el-option v-for="l in substituteLots" :key="l.id"
                               :label="`${lotZone(l.locationId)} ${$dict('Grade', l.grade)} ${l.currentWeight}kg@${l.unitPrice}`"
                               :value="l.id" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="重量kg" width="140">
                <template #default="{ row }">
                  <el-input-number v-model="row.weight" :min="0" :precision="2"
                                   :max="lotWeight(row.lotId)" size="small" />
                </template>
              </el-table-column>
              <el-table-column label="单价" width="120">
                <template #default="{ row }">
                  <el-input-number v-model="row.unitPrice" :min="0" :precision="2" size="small" />
                </template>
              </el-table-column>
            </el-table>
          </el-form-item>
          <el-form-item label="到货差异说明" required>
            <el-input v-model="substituteForm.note" type="textarea" :rows="3"
                      placeholder="向客户说明：原批次到货温度超标复检不合格已退货，现以同品类替代批次履约，规格/价格差异……" />
          </el-form-item>
          <el-form-item label="销售员">
            <el-input v-model="substituteForm.operator" />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="substituteVisible = false">取消</el-button>
        <el-button type="danger" :loading="saving" @click="submitSubstitute">确认替代重排</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'

const tab = ref('PLANNED')
const orders = ref([])
const customers = ref([])
const batches = ref([])
const categories = ref([])
const inLots = ref([])
const precoolLots = ref([])
const locations = ref([])
const zones = ref([])
const saving = ref(false)

const form = reactive({
  batchId: null, customerId: null, priority: false, remark: '',
  operator: '调度员', items: [{ lotId: null, weight: 0, unitPrice: null }]
})

const allLots = computed(() => [...inLots.value, ...precoolLots.value])
const outBatches = computed(() =>
  batches.value.filter(b => ['IN_STORAGE', 'PARTIAL_OUT', 'PRECOOLING'].includes(b.status)))
const availableLots = computed(() =>
  allLots.value.filter(l => l.batchId === form.batchId && Number(l.currentWeight) > 0))

function customerName(id) { return customers.value.find(c => c.id === id)?.name || '#' + id }
function batchNo(id) { return batches.value.find(b => b.id === id)?.batchNo || '#' + id }
function catName(id) { return categories.value.find(c => c.id === id)?.name || id }
function frozenOf(batchId) { return batches.value.find(b => b.id === batchId)?.saleFrozen === true }
function lotZone(locId) {
  const loc = locations.value.find(l => l.id === locId)
  return loc ? loc.code : '暂存区'
}
function lotWeight(lotId) {
  return allLots.value.find(l => l.id === lotId)?.currentWeight || 0
}
function onLotChange(row) {
  if (!row.unitPrice) {
    const lot = allLots.value.find(l => l.id === row.lotId)
    row.unitPrice = lot ? Number(lot.unitPrice) : null
  }
}
function onBatchChange() {
  form.items = [{ lotId: null, weight: 0, unitPrice: null }]
}

async function plan() {
  if (!form.batchId || !form.customerId) {
    ElMessage.warning('请选择批次与客户')
    return
  }
  const items = form.items.filter(i => i.lotId && Number(i.weight) > 0)
  if (!items.length) {
    ElMessage.warning('请添加出库货位与重量')
    return
  }
  saving.value = true
  try {
    const o = await api.post('/api/outbounds/plan', { ...form, items })
    ElMessage.success('排单成功 ' + o.orderNo
      + (o.customerChanged ? '（已触发临时改客户告警）' : '')
      + (o.priceLocked ? '（命中锁价）' : ''))
    Object.assign(form, { batchId: null, customerId: null, priority: false, remark: '',
      items: [{ lotId: null, weight: 0, unitPrice: null }] })
    await reload()
  } finally {
    saving.value = false
  }
}

async function execute(row) {
  await ElMessageBox.confirm(`确认执行出库 ${row.orderNo}？将扣减可售重量并释放库位。`, '执行出库', {
    type: 'warning'
  })
  const o = await api.post(`/api/outbounds/${row.id}/execute?operator=${encodeURIComponent('调度员')}`)
  ElMessage.success(`出库完成 ${o.totalWeight}kg`)
  reload()
}

const complaintVisible = ref(false)
const complaintOrder = ref(null)
const complaintForm = reactive({ type: 'BAD_FRUIT', problemWeight: 0, claimAmount: 0, description: '' })
function openComplaint(row) {
  complaintOrder.value = row
  Object.assign(complaintForm, { type: 'BAD_FRUIT', problemWeight: 0, claimAmount: 0, description: '' })
  complaintVisible.value = true
}
async function submitComplaint() {
  await api.post('/api/complaints', { outboundId: complaintOrder.value.id, ...complaintForm })
  ElMessage.success('投诉已登记，批次链已追加投诉节点')
  complaintVisible.value = false
}

// ---- 撤回单替代重排 ----
const substituteVisible = ref(false)
const substituteOrder = ref(null)
const substituteForm = reactive({
  batchId: null, items: [{ lotId: null, weight: 0, unitPrice: null }], note: '', operator: '销售员·陈小满'
})
const substituteBatches = computed(() => {
  if (!substituteOrder.value) return []
  const orig = batches.value.find(b => b.id === substituteOrder.value.batchId)
  if (!orig) return []
  return batches.value.filter(b =>
    b.categoryId === orig.categoryId && b.id !== orig.id && !b.saleFrozen
    && ['IN_STORAGE', 'PARTIAL_OUT'].includes(b.status))
})
const substituteLots = computed(() =>
  inLots.value.filter(l => l.batchId === substituteForm.batchId && Number(l.currentWeight) > 0))

function openSubstitute(row) {
  substituteOrder.value = row
  Object.assign(substituteForm, {
    batchId: null,
    items: [{ lotId: null, weight: Number(row.totalWeight) || 0, unitPrice: null }],
    note: '', operator: '销售员·陈小满'
  })
  substituteVisible.value = true
}
function onSubstituteLotChange(row) {
  if (!row.unitPrice) {
    const lot = inLots.value.find(l => l.id === row.lotId)
    row.unitPrice = lot ? Number(lot.unitPrice) : null
  }
}
async function submitSubstitute() {
  if (!substituteForm.batchId) {
    ElMessage.warning('请选择替代批次')
    return
  }
  const items = substituteForm.items.filter(i => i.lotId && Number(i.weight) > 0)
  if (!items.length) {
    ElMessage.warning('请选择替代货位与重量')
    return
  }
  if (!substituteForm.note.trim()) {
    ElMessage.warning('请填写向客户说明的到货差异')
    return
  }
  saving.value = true
  try {
    const o = await api.post(`/api/outbounds/${substituteOrder.value.id}/substitute`, {
      batchId: substituteForm.batchId, items,
      note: substituteForm.note, operator: substituteForm.operator
    })
    ElMessage.success('替代重排成功 ' + o.orderNo + '，原撤回单已关联')
    substituteVisible.value = false
    tab.value = 'PLANNED'
    await reload()
  } finally {
    saving.value = false
  }
}

async function loadOrders() {
  orders.value = await api.get('/api/outbounds' + (tab.value ? '?status=' + tab.value : ''))
}

async function reload() {
  await Promise.all([
    loadOrders(),
    api.get('/api/batches').then(d => (batches.value = d)),
    api.get('/api/stock/in-storage').then(d => (inLots.value = d)),
    api.get('/api/stock/precool').then(d => (precoolLots.value = d))
  ])
}

onMounted(async () => {
  const [c, cat, loc] = await Promise.all([
    api.get('/api/masters/customers'),
    api.get('/api/masters/categories'),
    api.get('/api/masters/locations')
  ])
  customers.value = c
  categories.value = cat
  locations.value = loc
  reload()
})
</script>

<style scoped>
.hint { color: #909daa; font-size: 12px; }
</style>
