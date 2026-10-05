import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', component: () => import('../views/Login.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('../views/Layout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: '看板', component: () => import('../views/Dashboard.vue') },
      { path: 'arrival', name: '到货登记', component: () => import('../views/Arrival.vue') },
      { path: 'batches', name: '批次列表', component: () => import('../views/Batches.vue') },
      { path: 'batches/:id', name: '批次链详情', component: () => import('../views/BatchDetail.vue') },
      { path: 'inspection', name: '质检验收', component: () => import('../views/Inspection.vue') },
      { path: 'temp-cases', name: '温度超标处置', component: () => import('../views/TempCases.vue') },
      { path: 'sorting', name: '分拣处理', component: () => import('../views/Sorting.vue') },
      { path: 'stock', name: '库位温区', component: () => import('../views/Stock.vue') },
      { path: 'outbound', name: '出库调度', component: () => import('../views/Outbound.vue') },
      { path: 'complaints', name: '客户投诉', component: () => import('../views/Complaints.vue') },
      { path: 'bills', name: '财务结算', component: () => import('../views/Bills.vue') },
      { path: 'trace', name: '监管追溯', component: () => import('../views/Trace.vue') },
      { path: 'masters', name: '基础资料', component: () => import('../views/Masters.vue') }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const token = localStorage.getItem('token')
  if (!to.meta.public && !token) {
    return '/login'
  }
  if (to.path === '/login' && token) {
    return '/dashboard'
  }
})

export default router
