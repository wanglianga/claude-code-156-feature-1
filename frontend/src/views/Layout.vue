<template>
  <el-container class="layout">
    <el-aside width="218px" class="aside">
      <div class="logo-row">
        <span class="logo">❄</span>
        <span class="logo-text">县域冷库平台</span>
      </div>
      <el-menu :default-active="$route.path" router background-color="#153f32"
               text-color="#cfe3db" active-text-color="#ffffff" class="menu">
        <el-menu-item index="/dashboard">
          <el-icon><DataAnalysis /></el-icon><span>管理看板</span>
        </el-menu-item>
        <el-menu-item index="/arrival">
          <el-icon><Van /></el-icon><span>到货登记</span>
        </el-menu-item>
        <el-menu-item index="/batches">
          <el-icon><List /></el-icon><span>批次管理</span>
        </el-menu-item>
        <el-menu-item index="/inspection">
          <el-icon><CircleCheck /></el-icon><span>质检验收</span>
        </el-menu-item>
        <el-menu-item index="/temp-cases">
          <el-icon><Sunny /></el-icon><span>温度超标处置</span>
        </el-menu-item>
        <el-menu-item index="/sorting">
          <el-icon><ScaleToOriginal /></el-icon><span>分拣处理</span>
        </el-menu-item>
        <el-menu-item index="/stock">
          <el-icon><Box /></el-icon><span>库位温区</span>
        </el-menu-item>
        <el-menu-item index="/outbound">
          <el-icon><Promotion /></el-icon><span>出库调度</span>
        </el-menu-item>
        <el-menu-item index="/complaints">
          <el-icon><Warning /></el-icon><span>客户投诉</span>
        </el-menu-item>
        <el-menu-item index="/bills">
          <el-icon><Money /></el-icon><span>财务结算</span>
        </el-menu-item>
        <el-menu-item index="/trace">
          <el-icon><Connection /></el-icon><span>监管追溯</span>
        </el-menu-item>
        <el-menu-item index="/masters">
          <el-icon><Setting /></el-icon><span>基础资料</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <div class="crumb">{{ $route.name }}</div>
        <div class="right">
          <el-badge :value="alertCount" :hidden="alertCount === 0" class="bell">
            <el-button circle @click="$router.push('/dashboard')">
              <el-icon><Bell /></el-icon>
            </el-button>
          </el-badge>
          <el-dropdown @command="onCommand">
            <span class="user">
              <el-avatar :size="30" style="background:#1f6f54">
                {{ user?.displayName?.[0] || 'U' }}
              </el-avatar>
              <span class="uname">{{ user?.displayName }}</span>
              <el-tag size="small" type="success">{{ user?.roleDesc }}</el-tag>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../store/user'
import api from '../api'

const router = useRouter()
const store = useUserStore()
const user = computed(() => store.user)
const alertCount = ref(0)

async function loadAlerts() {
  try {
    const list = await api.get('/api/alerts/open')
    alertCount.value = list.length
  } catch (e) { /* ignore */ }
}

function onCommand(cmd) {
  if (cmd === 'logout') {
    api.post('/api/auth/logout').catch(() => {})
    store.logout()
    router.push('/login')
  }
}

onMounted(loadAlerts)
setInterval(loadAlerts, 30000)
</script>

<style scoped>
.layout { height: 100vh; }
.aside {
  background: #153f32;
  overflow-y: auto;
}
.logo-row {
  height: 60px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 18px;
  color: #fff;
}
.logo { font-size: 24px; }
.logo-text { font-size: 16px; font-weight: 700; }
.menu { border-right: none; }
.header {
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #eef1f4;
}
.crumb { font-weight: 600; color: #1f2d3d; }
.right { display: flex; align-items: center; gap: 16px; }
.user { display: flex; align-items: center; gap: 8px; cursor: pointer; }
.uname { font-size: 14px; color: #303a45; }
.main { background: #f4f6f8; padding: 0; overflow-y: auto; }
</style>
