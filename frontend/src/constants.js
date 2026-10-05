// 与后端 DomainEnums 对齐的展示字典
export const ENUMS = {
  BatchStatus: {
    REGISTERED: '待检', INSPECTED: '已质检', SORTING: '分拣中',
    IN_STORAGE: '在库', PARTIAL_OUT: '部分出库', OUT: '全部出库', RETURNED: '已退货'
  },
  Decision: { ACCEPT: '准予入库', SORT: '转分拣', DOWNGRADE: '降级入库', RETURN: '退货' },
  OwnerDecision: { DOWNGRADE: '降级入库', RETURN: '现场退货', PRECOOL: '预冷后复检' },
  TempCaseStatus: {
    PENDING: '待货主决策', PRECOOLING: '预冷中', RECHECK_SCHEDULED: '已安排待复检',
    RECHECK_PASS: '复检合格', RECHECK_FAIL: '复检不合格',
    DOWNGRADED: '已降级入库', RETURNED: '已现场退货'
  },
  Grade: { A: 'A级精品', B: 'B级通货', PROCESS: '加工级' },
  ZoneType: { CHILLED: '冰温保鲜区', REFRIGERATED: '冷藏区', FROZEN: '冷冻区' },
  SettleMethod: { CONSIGN: '代销结算', BUYOUT: '保底收购', SELF: '货主自销代结' },
  OrderStatus: { PLANNED: '已排单待出库', OUT: '已出库', WITHDRAWN: '已撤回' },
  BillStatus: { DRAFT: '待确认', CONFIRMED: '已确认', PAID: '已结算' },
  Responsibility: {
    OWNER: '货主责任', WAREHOUSE: '冷库责任', CARRIER: '承运责任', CUSTOMER: '客户责任', PENDING: '待裁定'
  },
  ComplaintType: { BAD_FRUIT: '坏果腐损', SHORT_WEIGHT: '短斤少两', TEMP_ABUSE: '温度失控', OTHER: '其他' },
  AlertType: {
    TEMP_EXCEEDED: '到货温度超标', MIXED_LOAD: '混批混装', CUSTOMER_CHANGED: '临时改客户',
    PRIORITY_OUT: '客户要求优先出库', ZONE_FAULT: '冷库温区故障', LOSS_DISPUTE: '分拣损耗争议',
    ORIGINAL_WEIGHT_SETTLE: '按原重结算争议', COMPLAINT: '出库后客户投诉',
    HIGH_STAY_DAYS: '在仓超时高风险', LOCK_PRICE: '锁价客户待履约',
    RECHECK_FAILED: '预冷复检不合格', PLAN_WITHDRAWN: '出库计划撤回'
  },
  Stage: {
    ARRIVAL: '到货登记', INSPECTION: '质检验收', TEMP_HANDLING: '温度超标处置', SORTING: '分拣处理',
    PUT_IN: '入库上架', TRANSFER: '转仓移库', LOSS: '报损', OUTBOUND: '出库发运',
    COMPLAINT: '客户投诉', SETTLEMENT: '财务结算'
  },
  BillItemType: {
    SALE: '销售收入', STORAGE_FEE: '仓储费', SORT_FEE: '分拣费', LABOR_FEE: '分拣工时费',
    PRECOOL_FEE: '预冷费',
    LOSS_DEDUCT: '损耗补偿/扣款', COMPLAINT_DEDUCT: '投诉赔付扣款', ADVANCE_DEDUCT: '预付款抵扣',
    RETURN_DEDUCT: '退货扣款', REMAIN_VALUE: '期末库存货值'
  }
}

export const STATUS_TAG = {
  REGISTERED: 'info', INSPECTED: 'primary', SORTING: 'warning', IN_STORAGE: 'success',
  PARTIAL_OUT: 'warning', OUT: 'success', RETURNED: 'danger',
  PLANNED: 'warning', OUT_ORDER: 'success', WITHDRAWN: 'danger',
  DRAFT: 'info', CONFIRMED: 'warning', PAID: 'success',
  PENDING: 'danger', PRECOOLING: 'warning', RECHECK_SCHEDULED: 'warning',
  RECHECK_PASS: 'success', RECHECK_FAIL: 'danger', DOWNGRADED: 'warning'
}

export function desc(group, key) {
  return ENUMS[group]?.[key] || key || '-'
}
