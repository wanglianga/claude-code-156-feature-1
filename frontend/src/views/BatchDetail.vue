<template>
  <div class="page">
    <el-page-header @back="$router.back()" title="返回" :content="`批次链 ${data?.batch?.batchNo || ''}`"
                    style="margin-bottom:12px" />
    <div v-if="data">
      <el-row :gutter="12">
        <el-col :span="16">
          <el-card shadow="never" style="margin-bottom:12px">
            <div class="card-title">批次链时间线（入库 → 质检 → 分拣 → 库位 → 出库 → 损耗 → 结算 → 客户）</div>
            <el-timeline>
              <el-timeline-item v-for="e in data.timeline" :key="e.id"
                                :type="stageType(e.stage)" :timestamp="fmt(e.eventAt)" placement="top">
                <el-card shadow="never" class="evt-card">
                  <div class="evt-title">
                    <el-tag size="small" :type="stageType(e.stage)">{{ $dict('Stage', e.stage) }}</el-tag>
                    <strong style="margin-left:8px">{{ e.title }}</strong>
                    <span class="op">操作人：{{ e.operator }}</span>
                  </div>
                  <div class="evt-detail">{{ e.detail }}</div>
                </el-card>
              </el-timeline-item>
            </el-timeline>
          </el-card>

          <el-card shadow="never" style="margin-bottom:12px">
            <div class="card-title">库存移动流水</div>
            <el-table :data="data.moves" size="small">
              <el-table-column prop="movedAt" label="时间" width="170">
                <template #default="{ row }">{{ fmt(row.movedAt) }}</template>
              </el-table-column>
              <el-table-column label="类型" width="100">
                <template #default="{ row }">
                  <el-tag size="small">{{ moveReason(row.reason) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="weight" label="重量kg" width="90" />
              <el-table-column label="库位移动" min-width="180">
                <template #default="{ row }">
                  {{ locName(row.fromLocationId) }} <span v-if="row.toLocationId">→ {{ locName(row.toLocationId) }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="operator" label="操作人" width="100" />
              <el-table-column prop="remark" label="备注" min-width="200" />
            </el-table>
          </el-card>

          <el-card shadow="never">
            <div class="card-title">最终客户流向（监管视角）</div>
            <el-table :data="data.finalFlow" size="small">
              <el-table-column prop="orderNo" label="出库单号" width="180" class="mono" />
              <el-table-column prop="customer" label="最终客户" width="170" />
              <el-table-column label="改客户" width="90">
                <template #default="{ row }">
                  <el-tag v-if="row.customerChanged" type="warning" size="small">临时改</el-tag>
                  <span v-else class="ok-text">原计划</span>
                </template>
              </el-table-column>
              <el-table-column prop="weight" label="重量kg" width="90" />
              <el-table-column prop="amount" label="金额元" width="100" />
              <el-table-column prop="outAt" label="出库时间" width="170">
                <template #default="{ row }">{{ fmt(row.outAt) }}</template>
              </el-table-column>
              <el-table-column label="投诉">
                <template #default="{ row }">
                  <el-tag v-for="c in row.complaints" :key="c.id" type="danger" size="small">
                    {{ $dict('ComplaintType', c.type) }} 赔付{{ c.settledAmount }}
                  </el-tag>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-col>

        <el-col :span="8">
          <el-card shadow="never" style="margin-bottom:12px">
            <div class="card-title">批次档案</div>
            <el-descriptions :column="1" size="small" border>
              <el-descriptions-item label="批次号">{{ data.batch.batchNo }}</el-descriptions-item>
              <el-descriptions-item label="货主">{{ data.batch.owner }}</el-descriptions-item>
              <el-descriptions-item label="品类">{{ data.batch.category }}</el-descriptions-item>
              <el-descriptions-item label="产地">{{ data.batch.origin }}</el-descriptions-item>
              <el-descriptions-item label="净重">{{ data.batch.netWeight }} kg</el-descriptions-item>
              <el-descriptions-item label="到货温度">{{ data.batch.arrivalTemp }}℃</el-descriptions-item>
              <el-descriptions-item label="包装">{{ data.batch.packaging }}</el-descriptions-item>
              <el-descriptions-item label="车辆/混装">
                {{ data.batch.vehicleNo }}
                <el-tag v-if="data.batch.mixedLoad" size="small" type="warning">混批混装</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="检疫证">
                {{ data.batch.quarantine?.certificateNo }}
                <el-tag size="small" :type="data.batch.quarantine?.verified ? 'success' : 'danger'">
                  {{ data.batch.quarantine?.verified ? '已核验' : '未核验' }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="预计客户">{{ data.batch.plannedCustomer }}</el-descriptions-item>
              <el-descriptions-item label="结算方式">{{ $dict('SettleMethod', data.batch.settleMethod) }}</el-descriptions-item>
              <el-descriptions-item label="锁价">{{ data.batch.lockedPrice || '-' }} 元/kg</el-descriptions-item>
              <el-descriptions-item label="状态">
                <el-tag size="small">{{ $dict('BatchStatus', data.batch.status) }}</el-tag>
                <el-tag v-if="data.batch.saleFrozen" size="small" type="danger" effect="plain"
                        style="margin-left:6px">冻结销售</el-tag>
              </el-descriptions-item>
            </el-descriptions>
            <div class="weight-bar">
              <div>可售 <b class="ok-text">{{ data.batch.acceptedWeight }}</b></div>
              <div>降级 <b>{{ data.batch.downgradeWeight }}</b></div>
              <div>损耗 <b class="risk-warn">{{ data.batch.lossWeight }}</b></div>
              <div>退货 <b class="risk-critical">{{ data.batch.returnedWeight }}</b></div>
              <div>已出 <b>{{ data.batch.outWeight }}</b></div>
            </div>
          </el-card>

          <el-card shadow="never" style="margin-bottom:12px">
            <div class="card-title">货位批次（库位/温区/等级）</div>
            <el-table :data="data.stockLots" size="small">
              <el-table-column label="等级" width="80">
                <template #default="{ row }">{{ $dict('Grade', row.grade) }}</template>
              </el-table-column>
              <el-table-column prop="currentWeight" label="现重kg" width="75" />
              <el-table-column label="库位">
                <template #default="{ row }">{{ locName(row.locationId) }}</template>
              </el-table-column>
              <el-table-column prop="status" label="状态" width="60" />
            </el-table>
          </el-card>

          <el-card shadow="never" style="margin-bottom:12px">
            <div class="card-title">分拣记录</div>
            <el-timeline>
              <el-timeline-item v-for="s in data.sortings" :key="s.record.id" placement="top">
                <div>
                  <span class="mono">{{ s.record.sortingNo }}</span>
                  投入 {{ s.record.inputWeight }} / 产出 {{ s.record.outputWeight }}
                  / 损耗 {{ s.record.lossWeight }}（{{ pct(s.record.lossRate) }}）
                  工时 {{ s.record.laborMinutes }}min
                </div>
                <el-tag v-if="s.record.disputed && !s.record.disputeResolved" type="danger" size="small">
                  损耗争议待裁定
                </el-tag>
                <el-tag v-else-if="s.record.disputed" type="success" size="small">争议已裁定</el-tag>
                <div v-for="it in s.items" :key="it.id" class="sort-item">
                  {{ $dict('Grade', it.grade) }}：{{ it.weight }}kg @ {{ it.unitPrice }}元
                  → {{ $dict('ZoneType', it.zoneCode) }}
                </div>
              </el-timeline-item>
            </el-timeline>
          </el-card>

          <el-card shadow="never" style="margin-bottom:12px">
            <div class="card-title">风险告警</div>
            <el-table :data="data.alerts" size="small">
              <el-table-column label="级别" width="56">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.level === 'CRITICAL' ? 'danger' : 'warning'">
                    {{ row.level === 'CRITICAL' ? '严重' : '预警' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="title" label="标题" />
              <el-table-column label="状态" width="60">
                <template #default="{ row }">
                  {{ row.resolved ? '已处理' : '未处理' }}
                </template>
              </el-table-column>
            </el-table>
          </el-card>

          <el-card shadow="never">
            <div class="card-title">账单</div>
            <el-table :data="data.bills" size="small">
              <el-table-column prop="billNo" label="账单号" width="170" class="mono" />
              <el-table-column prop="netPayable" label="应付" width="90" />
              <el-table-column label="状态" width="70">
                <template #default="{ row }">
                  <el-tag size="small">{{ $dict('BillStatus', row.status) }}</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-col>
      </el-row>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import api from '../api'

const route = useRoute()
const data = ref(null)
const locations = ref([])

function fmt(t) {
  return t ? t.replace('T', ' ').slice(0, 19) : ''
}
function pct(v) {
  return (Number(v) * 100).toFixed(1) + '%'
}
function locName(id) {
  return locations.value.find(l => l.id === id)?.code || (id == null ? '-' : '#' + id)
}
function moveReason(r) {
  return { PUT_IN: '上架', TRANSFER: '转仓', LOSS: '报损', RETURN: '退货出库', OUTBOUND: '销售出库' }[r] || r
}
function stageType(s) {
  return {
    ARRIVAL: 'primary', INSPECTION: 'primary', SORTING: 'warning', PUT_IN: 'success',
    TRANSFER: 'warning', LOSS: 'danger', OUTBOUND: 'success',
    COMPLAINT: 'danger', SETTLEMENT: 'success'
  }[s] || 'info'
}

onMounted(async () => {
  locations.value = await api.get('/api/masters/locations')
  data.value = await api.get('/api/trace/batch/' + route.params.id)
})
</script>

<style scoped>
.evt-card { margin-bottom: 0; }
.evt-title { display: flex; align-items: center; }
.op { margin-left: auto; color: #909daa; font-size: 12px; }
.evt-detail { color: #4d5a67; font-size: 13px; margin-top: 6px; line-height: 1.7; }
.weight-bar {
  display: flex; justify-content: space-between; margin-top: 12px;
  font-size: 12px; color: #5a6673; background: #f7f9fa; padding: 8px; border-radius: 6px;
}
.sort-item { font-size: 12px; color: #5a6673; margin-top: 2px; }
</style>
