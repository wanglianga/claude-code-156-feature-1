package com.county.coldchain.domain;

/** 领域枚举集中定义，枚举值 code 存入数据库，desc 用于前端展示。 */
public final class DomainEnums {
    private DomainEnums() {}

    /** 货主类型 */
    public enum OwnerType { FARMER("农户"), COOPERATIVE("合作社"), WHOLESALER("批发商");
        public final String desc; OwnerType(String d) { this.desc = d; } }

    /** 质检结论 */
    public enum Decision {
        ACCEPT("准予入库"), SORT("转分拣"), DOWNGRADE("降级入库"), RETURN("退货");
        public final String desc; Decision(String d) { this.desc = d; } }

    /** 温区类型 */
    public enum ZoneType {
        CHILLED("冰温保鲜区"), REFRIGERATED("冷藏区"), FROZEN("冷冻区");
        public final String desc; ZoneType(String d) { this.desc = d; } }

    /** 分拣等级 */
    public enum Grade {
        A("A级精品"), B("B级通货"), PROCESS("加工级");
        public final String desc; Grade(String d) { this.desc = d; } }

    /** 结算方式 */
    public enum SettleMethod {
        CONSIGN("代销结算"), BUYOUT("保底收购"), SELF("货主自销代结");
        public final String desc; SettleMethod(String d) { this.desc = d; } }

    /** 库存移动原因 */
    public enum MoveReason {
        PUT_IN("入库上架"), TRANSFER("转仓"), LOSS("报损"),
        RETURN("退货出库"), OUTBOUND("销售出库");
        public final String desc; MoveReason(String d) { this.desc = d; } }

    /** 出库单状态 */
    public enum OrderStatus { PLANNED("已排单待出库"), OUT("已出库"), WITHDRAWN("已撤回");
        public final String desc; OrderStatus(String d) { this.desc = d; } }

    /** 温度超标货主处置选择 */
    public enum OwnerDecision {
        DOWNGRADE("降级入库"), RETURN("现场退货"), PRECOOL("预冷后复检");
        public final String desc; OwnerDecision(String d) { this.desc = d; } }

    /** 温度超标处置单状态 */
    public enum TempCaseStatus {
        PENDING("待货主决策"), PRECOOLING("预冷中"), RECHECK_SCHEDULED("已安排待复检"),
        RECHECK_PASS("复检合格"), RECHECK_FAIL("复检不合格"),
        DOWNGRADED("已降级入库"), RETURNED("已现场退货");
        public final String desc; TempCaseStatus(String d) { this.desc = d; } }

    /** 客户投诉类型 */
    public enum ComplaintType {
        BAD_FRUIT("坏果腐损"), SHORT_WEIGHT("短斤少两"), TEMP_ABUSE("温度失控"), OTHER("其他");
        public final String desc; ComplaintType(String d) { this.desc = d; } }

    /** 损耗/投诉责任方 */
    public enum Responsibility {
        OWNER("货主责任"), WAREHOUSE("冷库责任"), CARRIER("承运责任"), CUSTOMER("客户责任"), PENDING("待裁定");
        public final String desc; Responsibility(String d) { this.desc = d; } }

    /** 账单状态 */
    public enum BillStatus { DRAFT("待确认"), CONFIRMED("已确认"), PAID("已结算");
        public final String desc; BillStatus(String d) { this.desc = d; } }

    /** 账单明细类型 */
    public enum BillItemType {
        SALE("销售收入"), STORAGE_FEE("仓储费"), SORT_FEE("分拣费"),
        LABOR_FEE("分拣工时费"), PRECOOL_FEE("预冷费"), LOSS_DEDUCT("超阈损耗扣款"), COMPLAINT_DEDUCT("投诉赔付扣款"),
        ADVANCE_DEDUCT("预付款抵扣"), RETURN_DEDUCT("退货扣款"), REMAIN_VALUE("期末库存货值");
        public final String desc; BillItemType(String d) { this.desc = d; } }

    /** 风险告警级别 */
    public enum AlertLevel { WARN("预警"), CRITICAL("严重");
        public final String desc; AlertLevel(String d) { this.desc = d; } }

    /** 风险告警类型：覆盖题目中全部异常场景 */
    public enum AlertType {
        TEMP_EXCEEDED("到货温度超标"),
        MIXED_LOAD("混批混装"),
        CUSTOMER_CHANGED("临时改客户"),
        PRIORITY_OUT("客户要求优先出库"),
        ZONE_FAULT("冷库温区故障"),
        LOSS_DISPUTE("分拣损耗争议"),
        ORIGINAL_WEIGHT_SETTLE("货主要求按原重结算"),
        COMPLAINT("出库后客户投诉"),
        HIGH_STAY_DAYS("在仓超时高风险"),
        LOCK_PRICE("锁价客户待履约"),
        RECHECK_FAILED("预冷复检不合格"),
        PLAN_WITHDRAWN("出库计划撤回");
        public final String desc; AlertType(String d) { this.desc = d; } }

    /** 追溯链节点阶段 */
    public enum Stage {
        ARRIVAL("到货登记"), INSPECTION("质检验收"), TEMP_HANDLING("温度超标处置"), SORTING("分拣处理"),
        PUT_IN("入库上架"), TRANSFER("转仓移库"), LOSS("报损"),
        OUTBOUND("出库发运"), COMPLAINT("客户投诉"), SETTLEMENT("财务结算");
        public final String desc; Stage(String d) { this.desc = d; } }

    /** 平台用户角色 */
    public enum UserRole {
        ADMIN("管理端"), INSPECTOR("质检员"), SORTER("分拣员"),
        DISPATCHER("调度员"), FINANCE("财务"), VIEWER("监管只读");
        public final String desc; UserRole(String d) { this.desc = d; } }

    /** 批次全生命周期状态 */
    public enum BatchStatus {
        REGISTERED("待检"), INSPECTED("已质检"), SORTING("分拣中"),
        IN_STORAGE("在库"), PARTIAL_OUT("部分出库"), OUT("全部出库"),
        RETURNED("已退货");
        public final String desc; BatchStatus(String d) { this.desc = d; } }

    /** 锁价单状态 */
    public enum LockStatus { ACTIVE("履约中"), FULFILLED("已履约"), CANCELLED("已取消");
        public final String desc; LockStatus(String d) { this.desc = d; } }
}
