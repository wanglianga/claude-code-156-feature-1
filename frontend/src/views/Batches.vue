<template>
  <div class="page">
    <el-card shadow="never">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px">
        <div class="card-title" style="margin:0">批次列表</div>
        <div>
          <el-radio-group v-model="statusFilter" size="small" @change="load">
            <el-radio-button value="">全部</el-radio-button>
            <el-radio-button value="REGISTERED">待检</el-radio-button>
            <el-radio-button value="SORTING">分拣中</el-radio-button>
            <el-radio-button value="IN_STORAGE">在库</el-radio-button>
            <el-radio-button value="PARTIAL_OUT">部分出库</el-radio-button>
            <el-radio-button value="OUT">全部出库</el-radio-button>
            <el-radio-button value="RETURNED">已退货</el-radio-button>
          </el-radio-group>
          <el-button size="small" @click="load" style="margin-left:8px">刷新</el-button>
        </div>
      </div>
      <el-table :data="rows" size="small" stripe @row-click="(r) => $router.push('/batches/' + r.id)">
        <el-table-column prop="batchNo" label="批次号" width="175" class="mono" />
        <el-table-column label="货主" width="150">
          <template #default="{ row }">{{ ownerName(row.ownerId) }}</template>
        </el-table-column>
        <el-table-column label="品类" width="140">
          <template #default="{ row }">{{ catName(row.categoryId) }}</template>
        </el-table-column>
        <el-table-column prop="origin" label="产地" width="150" />
        <el-table-column prop="netWeight" label="净重kg" width="85" />
        <el-table-column label="到货温度" width="90">
          <template #default="{ row }">
            <span :class="tempBad(row) ? 'risk-critical' : ''">{{ row.arrivalTemp }}℃</span>
          </template>
        </el-table-column>
        <el-table-column prop="packaging" label="包装" width="100" />
        <el-table-column label="预计客户" width="150">
          <template #default="{ row }">{{ customerName(row.plannedCustomerId) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ $dict('BatchStatus', row.status) }}</el-tag>
            <el-tooltip v-if="row.saleFrozen" content="温度超标预冷复检中，复检合格前冻结对外销售" placement="top">
              <el-tag size="small" type="danger" style="margin-left:4px">冻结</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="可售/损耗/已出" width="170">
          <template #default="{ row }">
            <span class="ok-text">{{ row.acceptedWeight }}</span> /
            <span class="risk-warn">{{ row.lossWeight }}</span> /
            {{ row.outWeight }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="$router.push('/batches/' + row.id)">批次链</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import api from '../api'
import { STATUS_TAG } from '../constants'

const rows = ref([])
const owners = ref([])
const customers = ref([])
const categories = ref([])
const statusFilter = ref('')

const ownerName = (id) => owners.value.find(o => o.id === id)?.name || id
const customerName = (id) => customers.value.find(c => c.id === id)?.name || '-'
const catName = (id) => categories.value.find(c => c.id === id)?.name || id
const statusTag = (s) => STATUS_TAG[s] || 'info'

function tempBad(row) {
  const cat = categories.value.find(c => c.id === row.categoryId)
  if (!cat) return false
  return Number(row.arrivalTemp) > Number(cat.maxArrivalTemp)
      || Number(row.arrivalTemp) < Number(cat.minArrivalTemp)
}

async function load() {
  rows.value = await api.get('/api/batches' + (statusFilter.value ? '?status=' + statusFilter.value : ''))
}

onMounted(async () => {
  const [o, c, cat] = await Promise.all([
    api.get('/api/masters/owners'),
    api.get('/api/masters/customers'),
    api.get('/api/masters/categories')
  ])
  owners.value = o
  customers.value = c
  categories.value = cat
  load()
})
</script>
