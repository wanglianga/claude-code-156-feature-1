<template>
  <div class="page">
    <el-card shadow="never">
      <div class="card-title">客户投诉与责任裁定（坏果 / 短重 / 温度失控）</div>
      <el-table :data="list" size="small">
        <el-table-column prop="complaintNo" label="投诉单号" width="170" class="mono" />
        <el-table-column label="客户" width="160">
          <template #default="{ row }">{{ customerName(row.customerId) }}</template>
        </el-table-column>
        <el-table-column label="批次" width="170">
          <template #default="{ row }">{{ batchNo(row.batchId) }}</template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag size="small" type="danger">{{ $dict('ComplaintType', row.type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="problemWeight" label="问题kg" width="80" />
        <el-table-column prop="claimAmount" label="索赔元" width="80" />
        <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
        <el-table-column label="责任裁定" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.resolved" size="small" :type="row.responsibility === 'WAREHOUSE' ? 'danger' : 'warning'">
              {{ $dict('Responsibility', row.responsibility) }}
            </el-tag>
            <el-tag v-else size="small" type="info">待裁定</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="settledAmount" label="赔付元" width="80" />
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button v-if="!row.resolved" link type="primary" @click="open(row)">裁定</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="visible" title="投诉责任裁定" width="640px">
      <el-form :model="form" label-width="110px" v-if="target">
        <el-form-item label="索赔情况">
          {{ target.problemWeight }}kg / 索赔 {{ target.claimAmount }} 元 — {{ target.description }}
        </el-form-item>

        <!-- 责任判定辅助：回看温度超标与货主确认记录 -->
        <el-card v-if="evidence" shadow="never" class="evidence-box">
          <div class="evidence-title">责任判定辅助（平台回看批次链记录）</div>
          <div class="evidence-line">
            到货温度 <b :class="evidence.tempExceeded ? 'risk-critical' : 'ok-text'">{{ evidence.arrivalTemp }}℃</b>
            （合同区间 {{ evidence.contractMinTemp }}~{{ evidence.contractMaxTemp }}℃）
            <el-tag v-if="evidence.tempExceeded" size="small" type="danger">到货温度超标</el-tag>
            <el-tag v-else size="small" type="success">到货温度合规</el-tag>
            <el-tag v-if="evidence.zoneFaultAlerts > 0" size="small" type="warning" style="margin-left:6px">
              在仓温区故障 {{ evidence.zoneFaultAlerts }} 次
            </el-tag>
          </div>
          <template v-if="evidence.tempExceptions?.length">
            <div v-for="e in evidence.tempExceptions" :key="e.recordNo" class="evidence-record">
              <el-tag size="small" effect="plain">{{ e.recordNo }}</el-tag>
              复测 {{ e.measuredTemp }}℃，抽检腐损 {{ e.sampledRotRate != null ? (e.sampledRotRate * 100).toFixed(1) + '%' : '—' }}
              → 货主 <b>{{ e.decidedBy || '—' }}</b> 确认【{{ e.decisionDesc }}】
              <el-tag v-if="e.recheckResultDesc" size="small"
                      :type="e.recheckResult === 'PASSED' ? 'success' : 'danger'">
                {{ e.recheckResultDesc }}
              </el-tag>
              <div class="hint" v-if="e.ownerConfirmNote">确认备注：{{ e.ownerConfirmNote }}</div>
              <div class="hint" v-if="e.effectNote">{{ e.effectNote }}</div>
            </div>
          </template>
          <div v-else class="hint">该批次无温度超标处置记录</div>
          <el-alert :title="evidence.suggestionReason" type="info" :closable="false"
                    style="margin-top:8px" />
          <div v-if="evidence.suggestedResponsibility" style="margin-top:8px">
            平台建议：<el-tag type="danger" effect="dark">
              {{ $dict('Responsibility', evidence.suggestedResponsibility) }}
            </el-tag>
            <el-button link type="primary" @click="form.responsibility = evidence.suggestedResponsibility">
              采纳建议
            </el-button>
          </div>
        </el-card>

        <el-form-item label="责任方" style="margin-top:12px">
          <el-radio-group v-model="form.responsibility">
            <el-radio value="OWNER">货主责任（货款扣赔）</el-radio>
            <el-radio value="WAREHOUSE">冷库责任（冷库对客赔付）</el-radio>
            <el-radio value="CARRIER">承运责任</el-radio>
            <el-radio value="CUSTOMER">客户责任（免赔）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="裁定赔付元">
          <el-input-number v-model="form.settledAmount" :min="0" :precision="2" />
        </el-form-item>
        <el-form-item label="处理人">
          <el-input v-model="form.handler" />
        </el-form-item>
        <el-form-item label="处理结果">
          <el-input v-model="form.handleResult" type="textarea" :rows="3"
                    placeholder="追溯结论：到货温度/在仓温控/出库交接情况" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" @click="submit">确认裁定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const list = ref([])
const customers = ref([])
const batches = ref([])
const visible = ref(false)
const target = ref(null)
const evidence = ref(null)
const form = reactive({ responsibility: 'OWNER', settledAmount: 0, handler: '', handleResult: '' })

function customerName(id) { return customers.value.find(c => c.id === id)?.name || '#' + id }
function batchNo(id) { return batches.value.find(b => b.id === id)?.batchNo || '#' + id }

async function open(row) {
  target.value = row
  Object.assign(form, {
    responsibility: 'OWNER', settledAmount: Number(row.claimAmount || 0),
    handler: '', handleResult: ''
  })
  evidence.value = null
  visible.value = true
  try {
    evidence.value = await api.get(`/api/complaints/${row.id}/evidence`)
    if (evidence.value.suggestedResponsibility) {
      form.responsibility = evidence.value.suggestedResponsibility
    }
  } catch (e) { /* 辅助信息加载失败不影响裁定 */ }
}

async function submit() {
  await api.post(`/api/complaints/${target.value.id}/resolve`, form)
  ElMessage.success('裁定完成，账单与追溯链已更新')
  visible.value = false
  load()
}

async function load() {
  list.value = await api.get('/api/complaints')
}

onMounted(async () => {
  const [c, b] = await Promise.all([
    api.get('/api/masters/customers'),
    api.get('/api/batches')
  ])
  customers.value = c
  batches.value = b
  load()
})
</script>

<style scoped>
.evidence-box { background: #f7f9fa; margin-bottom: 4px; }
.evidence-title { font-weight: 600; margin-bottom: 6px; }
.evidence-line { font-size: 13px; margin-bottom: 6px; }
.evidence-record {
  font-size: 12px; color: #4a5560; border-top: 1px dashed #dde4ea;
  padding: 6px 0; line-height: 1.8;
}
.hint { color: #909daa; font-size: 12px; }
.risk-critical { color: #c0392b; font-weight: 700; }
.ok-text { color: #1f6f54; }
</style>
