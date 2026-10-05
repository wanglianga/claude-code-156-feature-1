#!/usr/bin/env python3
"""端到端业务链验证：到货→质检→分拣→上架→预付款→出库→投诉→温区故障转仓→报损→账单→追溯→看板；
以及到货温度超标处置链：降级入库/现场退货/预冷复检（不合格撤回出库计划→替代重排）→投诉责任回看。"""
import json
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8080"
token = None
failures = []


def call(method, path, body=None, expect=200):
    import urllib.parse
    url = BASE + urllib.parse.quote(path, safe="/?=&%:")
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json;charset=utf-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as r:
            txt = r.read().decode("utf-8")
            status = r.status
    except urllib.error.HTTPError as e:
        txt = e.read().decode("utf-8")
        status = e.code
    ok = status == expect
    if not ok:
        failures.append(f"{method} {path} -> {status} (expect {expect}): {txt[:300]}")
    try:
        parsed = json.loads(txt)
    except Exception:
        parsed = txt
    print(f"[{'OK' if ok else 'FAIL'}] {method} {path} -> {status}")
    return parsed


# ---------- 1. 登录 ----------
r = call("POST", "/api/auth/login", {"username": "admin", "password": "admin123"})
token = r["token"]

# ---------- 2. 批次A：葡萄，温度超标(12℃>8℃)+混批+锁价客户+正常分拣 ----------
b1 = call("POST", "/api/batches/arrival", {
    "ownerId": 1, "categoryId": 1, "origin": "卧龙镇沙沟村三组",
    "productionDate": "2026-10-03", "grossWeight": 1020, "tareWeight": 20,
    "arrivalTemp": 12, "packaging": "泡沫箱加冰",
    "certificateNo": "QY-PT-20261004-01", "issuingOrg": "县农检中心",
    "certIssueDate": "2026-10-04", "certificateVerified": True,
    "vehicleNo": "鲁Q·G8816", "mixedLoad": True,
    "plannedCustomerId": 1, "settleMethod": "CONSIGN", "lockedPrice": 11.5,
    "operator": "赵调度"
})
B1 = b1["id"]
assert b1["netWeight"] == 1000.0

# ---------- 3. 批次B：草莓 300kg，正常到货，质检直接入库 ----------
b2 = call("POST", "/api/batches/arrival", {
    "ownerId": 2, "categoryId": 2, "origin": "青山乡莓香村",
    "productionDate": "2026-10-04", "grossWeight": 300,
    "arrivalTemp": 3, "packaging": "纸盒",
    "certificateNo": "QY-CM-20261004-02", "issuingOrg": "县农检中心",
    "certIssueDate": "2026-10-04", "certificateVerified": True,
    "vehicleNo": "鲁Q·G8816", "mixedLoad": True,
    "plannedCustomerId": 2, "settleMethod": "BUYOUT",
    "operator": "赵调度"
})
B2 = b2["id"]

# ---------- 4. 批次C：叶菜 200kg，温度不高但质检查退 ----------
b3 = call("POST", "/api/batches/arrival", {
    "ownerId": 3, "categoryId": 3, "origin": "城北菜园",
    "productionDate": "2026-10-04", "grossWeight": 200,
    "arrivalTemp": 5, "packaging": "筐装",
    "certificateNo": "QY-YC-20261004-03", "issuingOrg": "县农检中心",
    "certIssueDate": "2026-10-04", "certificateVerified": True,
    "vehicleNo": "鲁Q·K2230", "mixedLoad": False,
    "plannedCustomerId": 4, "settleMethod": "CONSIGN",
    "operator": "赵调度"
})
B3 = b3["id"]

# ---------- 5. 批次A 质检转分拣 ----------
call("POST", "/api/inspections", {
    "batchId": B1, "inspector": "李检",
    "appearanceScore": 82, "maturityScore": 85, "rotRate": 0.02, "moistureRate": 0.88,
    "measuredTemp": 9, "certificateOk": True, "mixedConfirmed": True,
    "decision": "SORT", "remark": "温度偏高，需分拣分级后速入冰温库"
})

# ---------- 6. 批次B 质检直接入库 A级，核定损耗2% ----------
in2 = call("POST", "/api/inspections", {
    "batchId": B2, "inspector": "李检",
    "appearanceScore": 95, "maturityScore": 90, "rotRate": 0.01, "moistanceRate": 0.9,
    "moistureRate": 0.9, "measuredTemp": 3, "certificateOk": True,
    "decision": "ACCEPT", "suggestedGrade": "A", "estimatedLossRate": 0.02
})

# ---------- 7. 批次C 退货 ----------
call("POST", "/api/inspections", {
    "batchId": B3, "inspector": "李检",
    "appearanceScore": 40, "maturityScore": 70, "rotRate": 0.18, "moistureRate": 0.95,
    "measuredTemp": 5, "certificateOk": True,
    "decision": "RETURN", "returnWeight": 200,
    "remark": "烂叶超过15%，整批退回"
})

# ---------- 8. 批次A 分拣：A 600@12, B 330@8, 加工 20@3, 合计950，损耗50(5%)，工时90分钟，货主争议 ----------
sort1 = call("POST", "/api/sortings", {
    "batchId": B1, "sorter": "孙姐", "inputWeight": 1000, "laborMinutes": 90,
    "disputed": True, "disputeNote": "货主主张运输途中掉粒属正常，损耗应≤3%",
    "items": [
        {"grade": "A", "weight": 600, "unitPrice": 12.0, "shelfLifeDays": 6},
        {"grade": "B", "weight": 330, "unitPrice": 8.0, "shelfLifeDays": 4},
        {"grade": "PROCESS", "weight": 20, "unitPrice": 3.0, "shelfLifeDays": 2}
    ]
})
SID = sort1["id"]

# 争议裁定：冷库认可30kg损耗，20kg回补货主
call("POST", f"/api/sortings/{SID}/resolve-dispute", {
    "acceptLoss": False, "adjudicatedLoss": 30,
    "note": "20kg 为低温操作不当压伤，冷库承担"
})

# ---------- 9. 上架：批次A三个货位 → CW-01 三个库位；批次B待上架 lot ----------
pending = call("GET", "/api/stock/pending")
lots_b1 = sorted([p for p in pending if p["batchId"] == B1], key=lambda x: x["grade"])
# A -> loc1, B -> loc2, PROCESS -> loc3
for lot, loc_id in zip(lots_b1, [1, 2, 3]):
    call("POST", "/api/stock/put-away", {"lotId": lot["id"], "locationId": loc_id, "operator": "赵调度"})

lots_b2 = [p for p in call("GET", "/api/stock/pending") if p["batchId"] == B2]
call("POST", "/api/stock/put-away", {"lotId": lots_b2[0]["id"], "locationId": 1, "operator": "赵调度"})

# ---------- 10. 货主预付款放款 2000 ----------
call("POST", "/api/owners/1/advance", {"batchId": B1, "amount": 2000, "remark": "葡萄采收季预付款"})

# ---------- 11. 出库：锁价客户1 提 A级 500kg（优先） ----------
lot_a = [l for l in call("GET", "/api/stock/in-storage") if l["batchId"] == B1 and l["grade"] == "A"][0]
o1 = call("POST", "/api/outbounds/plan", {
    "customerId": 1, "batchId": B1, "priority": True,
    "operator": "赵调度", "remark": "锁价客户催货",
    "items": [{"lotId": lot_a["id"], "weight": 500}]
})
O1 = o1["id"]
assert o1["priceLocked"] is True, "应命中锁价协议"
assert o1["totalWeight"] == 500.0
# 锁价单价 11.5
assert float(o1["totalAmount"]) == 500 * 11.5
call("POST", f"/api/outbounds/{O1}/execute?operator=赵调度", None)

# ---------- 12. 出库：临时改客户4，提 B级 300kg ----------
lot_b = [l for l in call("GET", "/api/stock/in-storage") if l["batchId"] == B1 and l["grade"] == "B"][0]
o2 = call("POST", "/api/outbounds/plan", {
    "customerId": 4, "batchId": B1,
    "operator": "赵调度", "remark": "原客户暂缓，临时转给社区团购",
    "items": [{"lotId": lot_b["id"], "weight": 300}]
})
O2 = o2["id"]
assert o2["customerChanged"] is True, "应标记临时改客户"
call("POST", f"/api/outbounds/{O2}/execute?operator=赵调度", None)

# ---------- 13. 出库后投诉坏果（针对第一单），裁定货主责任赔付 300 ----------
c1 = call("POST", "/api/complaints", {
    "outboundId": O1, "type": "BAD_FRUIT", "problemWeight": 30,
    "claimAmount": 400, "description": "客户反馈底部三箱坏果约30kg"
})
CID = c1["id"]
call("POST", f"/api/complaints/{CID}/resolve", {
    "responsibility": "OWNER", "settledAmount": 300,
    "handler": "管理员", "handleResult": "查批次链为到货高温导致，货主承担300元"
})

# ---------- 14. 温区故障：冷冻库先报故障（无在库货，验证告警机制）；再让冰温库故障 → B级余货必须转仓 ----------
call("POST", "/api/stock/zones/3/status", {"fault": True, "faultNote": "压缩机告警停机"})
instore = call("GET", "/api/stock/in-storage")
lot_b_left = [l for l in instore if l["batchId"] == B1 and l["grade"] == "B"][0]
# 冰温库故障
call("POST", "/api/stock/zones/1/status", {"fault": True, "faultNote": "蒸发器结霜异常"})
# 转仓到冷藏库 loc4
call("POST", "/api/stock/transfer", {
    "lotId": lot_b_left["id"], "toLocationId": 4, "operator": "赵调度",
    "remark": "冰温库故障，B级葡萄暂转冷藏库"
})
# 故障温区禁止上架校验
call("POST", "/api/stock/put-away", {"lotId": lots_b2[0]["id"], "locationId": 2}, expect=400)
# 修复
call("POST", "/api/stock/zones/1/status", {"fault": False})
call("POST", "/api/stock/zones/3/status", {"fault": False})

# ---------- 15. 库内报损：加工级20kg 冷库责任 ----------
lot_p = [l for l in call("GET", "/api/stock/in-storage") if l["batchId"] == B1 and l["grade"] == "PROCESS"][0]
call("POST", "/api/stock/loss", {
    "lotId": lot_p["id"], "weight": 20, "responsibility": "WAREHOUSE",
    "operator": "赵调度", "remark": "搬运破损"
})

# ---------- 16. 账单：批次A生成→确认→结算 ----------
bill = call("POST", "/api/bills/generate", {"batchId": B1, "remark": "月末账单"})
FID = bill["id"]
items = call("GET", f"/api/bills/{FID}/items")
print("  账单明细：")
for it in items:
    print(f"    {it['type']:18s} {it['summary'][:46]:48s} 金额={it['amount']}")
call("POST", f"/api/bills/{FID}/confirm", None)
settled = call("POST", f"/api/bills/{FID}/settle", {})
print(f"  应付货主净额={settled['netPayable']} 预付款抵扣={settled['advanceDeducted']} 实付={settled['paidAmount']}")

# ---------- 17. 退货批次不允许账单 ----------
call("POST", "/api/bills/generate", {"batchId": B3}, expect=400)

# ==================== 到货温度超标处置（新增链路） ====================
# ---------- T1. 草莓批次D：预冷后复检 → 不合格 → 自动撤回出库计划 → 销售员替代重排 ----------
bD = call("POST", "/api/batches/arrival", {
    "ownerId": 2, "categoryId": 2, "origin": "青山乡莓香村",
    "productionDate": "2026-10-05", "grossWeight": 500,
    "arrivalTemp": 9, "packaging": "泡沫箱加冰",
    "certificateNo": "QY-CM-20261005-04", "issuingOrg": "县农检中心",
    "certIssueDate": "2026-10-05", "certificateVerified": True,
    "vehicleNo": "鲁Q·M5520", "mixedLoad": False,
    "plannedCustomerId": 2, "settleMethod": "CONSIGN",
    "operator": "赵调度"
})
BD = bD["id"]

# 质检员登记温度超标处置单（温度/到货时间/车厢照片/抽检腐损率）
te1 = call("POST", "/api/temp-exceptions", {
    "batchId": BD, "measuredTemp": 8.5,
    "cabinPhotos": ["https://example.com/cabin-d1.jpg", "https://example.com/cabin-d2.jpg"],
    "sampledRotRate": 0.06, "inspector": "李检",
    "remark": "车厢冰袋已融化，果面发软"
})
T1 = te1["id"]
assert te1["status"] == "PENDING_DECISION"
assert float(te1["contractMaxTemp"]) == 4.0

# 三种处置方式影响预估（保鲜期/仓储费/售价）
opts = call("GET", f"/api/temp-exceptions/{T1}/options")
by_decision = {o["decision"]: o for o in opts}
assert by_decision["DOWNGRADE_ACCEPT"]["shelfLifeDays"] == 2
assert float(by_decision["PRECOOL_RECHECK"]["expectedUnitPrice"]) == 22.5
assert float(by_decision["PRECOOL_RECHECK"]["precoolFeeRate"]) == 0.2
assert float(by_decision["ONSITE_RETURN"]["expectedUnitPrice"]) == 0.0

# 未办结前禁止重复登记
call("POST", "/api/temp-exceptions", {"batchId": BD, "measuredTemp": 8}, expect=400)

# 货主选择预冷后复检：安排复检时段与暂存温区，冻结对外销售
call("POST", f"/api/temp-exceptions/{T1}/decision", {
    "decision": "PRECOOL_RECHECK", "decidedBy": "周社长",
    "ownerConfirmNote": "已知悉温度超标，选择预冷后复检",
    "precoolZoneId": 1, "recheckSlotAt": "2026-10-05T14:00:00"
})
bd_mid = call("GET", f"/api/batches/{BD}")
assert bd_mid["status"] == "PRECOOLING" and bd_mid["saleFrozen"] is True
lot_d = [l for l in call("GET", "/api/stock/precool") if l["batchId"] == BD][0]
assert float(lot_d["currentWeight"]) == 500.0
occ_mid = [z for z in call("GET", "/api/masters/zones") if z["id"] == 1][0]["occupiedKg"]

# 复检前已分配给客户的出库计划（允许排单预售，但冻结发货）
oD = call("POST", "/api/outbounds/plan", {
    "customerId": 2, "batchId": BD, "operator": "赵调度",
    "items": [{"lotId": lot_d["id"], "weight": 100}]
})
OD = oD["id"]
call("POST", f"/api/outbounds/{OD}/execute?operator=赵调度", None, expect=400)  # 冻结拦截

# 复检不合格 → 整批退货 + 出库计划自动撤回
rk1 = call("POST", f"/api/temp-exceptions/{T1}/recheck", {
    "recheckTemp": 7, "recheckRotRate": 0.12, "recheckBy": "李检",
    "note": "预冷4小时后复测仍超标"
})
assert rk1["recheckResult"] == "FAILED" and rk1["status"] == "CLOSED"
bd_final = call("GET", f"/api/batches/{BD}")
assert bd_final["status"] == "RETURNED" and bd_final["saleFrozen"] is False
assert float(bd_final["returnedWeight"]) == 500.0
withdrawn = call("GET", "/api/outbounds?status=WITHDRAWN")
w_od = [o for o in withdrawn if o["id"] == OD][0]
assert "复检不合格" in w_od["withdrawReason"]
assert any(a["type"] == "ORDER_WITHDRAWN" for a in call("GET", "/api/alerts/open"))
occ_after = [z for z in call("GET", "/api/masters/zones") if z["id"] == 1][0]["occupiedKg"]
assert float(occ_after) == float(occ_mid) - 500.0, "暂存温区占用应释放"

# 销售员选择替代批次重排，并向客户说明到货差异
lot_b2 = [l for l in call("GET", "/api/stock/in-storage") if l["batchId"] == B2][0]
sub = call("POST", f"/api/outbounds/{OD}/substitute", {
    "batchId": B2, "operator": "销售员·陈小满",
    "items": [{"lotId": lot_b2["id"], "weight": 100}],
    "note": "原批次预冷复检不合格已退货，以同合作社在库A级草莓履约，复测温度合格，单价不变"
})
OS = sub["id"]
assert sub["substitutedFromOrderId"] == OD and sub["customerId"] == 2
assert float(sub["totalWeight"]) == 100.0
w_od2 = [o for o in call("GET", "/api/outbounds?status=WITHDRAWN") if o["id"] == OD][0]
assert w_od2["substitutedByOrderId"] == OS
# 重复替代 / 跨品类替代均拦截
call("POST", f"/api/outbounds/{OD}/substitute", {
    "batchId": B2, "items": [{"lotId": lot_b2["id"], "weight": 10}], "note": "x"}, expect=400)
call("POST", f"/api/outbounds/{OS}/execute?operator=赵调度", None)

# ---------- T2. 草莓批次E：降级入库 → 出库 → 客户投诉 → 平台回看温度超标与货主确认记录 ----------
bE = call("POST", "/api/batches/arrival", {
    "ownerId": 2, "categoryId": 2, "origin": "青山乡莓香村",
    "productionDate": "2026-10-05", "grossWeight": 200,
    "arrivalTemp": 8, "packaging": "纸盒",
    "certificateNo": "QY-CM-20261005-05", "issuingOrg": "县农检中心",
    "certIssueDate": "2026-10-05", "certificateVerified": True,
    "vehicleNo": "鲁Q·M5521", "mixedLoad": False,
    "plannedCustomerId": 2, "settleMethod": "CONSIGN",
    "operator": "赵调度"
})
BE = bE["id"]
te2 = call("POST", "/api/temp-exceptions", {
    "batchId": BE, "measuredTemp": 7.5, "cabinPhotos": ["https://example.com/cabin-e1.jpg"],
    "sampledRotRate": 0.08, "inspector": "李检"
})
T2 = te2["id"]
call("POST", f"/api/temp-exceptions/{T2}/decision", {
    "decision": "DOWNGRADE_ACCEPT", "downgradeGrade": "B", "decidedBy": "周社长",
    "ownerConfirmNote": "已知悉温度超标，同意按B级降级入库"
})
be = call("GET", f"/api/batches/{BE}")
assert be["status"] == "INSPECTED" and float(be["acceptedWeight"]) == 200.0
lot_e = [l for l in call("GET", "/api/stock/pending") if l["batchId"] == BE][0]
assert lot_e["grade"] == "B" and float(lot_e["unitPrice"]) == 15.0 and lot_e["shelfLifeDays"] == 2
call("POST", "/api/stock/put-away", {"lotId": lot_e["id"], "locationId": 2, "operator": "赵调度"})
oE = call("POST", "/api/outbounds/plan", {
    "customerId": 4, "batchId": BE, "operator": "赵调度",
    "items": [{"lotId": lot_e["id"], "weight": 50}]
})
assert float(oE["totalAmount"]) == 50 * 15.0
call("POST", f"/api/outbounds/{oE['id']}/execute?operator=赵调度", None)
cE = call("POST", "/api/complaints", {
    "outboundId": oE["id"], "type": "BAD_FRUIT", "problemWeight": 10,
    "claimAmount": 150, "description": "草莓到货次日软腐出水"
})
ev = call("GET", f"/api/complaints/{cE['id']}/evidence")
assert ev["tempExceeded"] is True
assert ev["suggestedResponsibility"] == "OWNER", "货主确认降级入库应建议货主责任"
assert ev["tempExceptions"][0]["decision"] == "DOWNGRADE_ACCEPT"
assert ev["tempExceptions"][0]["decidedBy"] == "周社长"
call("POST", f"/api/complaints/{cE['id']}/resolve", {
    "responsibility": "OWNER", "settledAmount": 100,
    "handler": "管理员", "handleResult": "平台回看：到货温度超标且货主确认降级入库，判定货主责任"
})
# 无处置记录但到货超标的批次（B1 葡萄 12℃）：只给证据不给建议
ev0 = call("GET", f"/api/complaints/{CID}/evidence")
assert ev0["tempExceeded"] is True and ev0["suggestedResponsibility"] is None

# ---------- T3. 草莓批次F：现场退货 ----------
bF = call("POST", "/api/batches/arrival", {
    "ownerId": 2, "categoryId": 2, "origin": "青山乡莓香村",
    "productionDate": "2026-10-05", "grossWeight": 150,
    "arrivalTemp": 10, "packaging": "纸盒",
    "certificateNo": "QY-CM-20261005-06", "issuingOrg": "县农检中心",
    "certIssueDate": "2026-10-05", "certificateVerified": True,
    "vehicleNo": "鲁Q·M5522", "mixedLoad": False,
    "settleMethod": "CONSIGN", "operator": "赵调度"
})
BF = bF["id"]
te3 = call("POST", "/api/temp-exceptions", {
    "batchId": BF, "measuredTemp": 9.5, "sampledRotRate": 0.15, "inspector": "李检"
})
call("POST", f"/api/temp-exceptions/{te3['id']}/decision", {
    "decision": "ONSITE_RETURN", "decidedBy": "周社长",
    "ownerConfirmNote": "腐损率过高，现场整批退回"
})
bf = call("GET", f"/api/batches/{BF}")
assert bf["status"] == "RETURNED" and float(bf["returnedWeight"]) == 150.0
call("POST", "/api/bills/generate", {"batchId": BF}, expect=400)

# ---------- T4. 草莓批次G：预冷复检合格 → 解冻上架 → 出库 → 账单含预冷处理费 ----------
bG = call("POST", "/api/batches/arrival", {
    "ownerId": 2, "categoryId": 2, "origin": "青山乡莓香村",
    "productionDate": "2026-10-05", "grossWeight": 400,
    "arrivalTemp": 7, "packaging": "泡沫箱加冰",
    "certificateNo": "QY-CM-20261005-07", "issuingOrg": "县农检中心",
    "certIssueDate": "2026-10-05", "certificateVerified": True,
    "vehicleNo": "鲁Q·M5523", "mixedLoad": False,
    "plannedCustomerId": 2, "settleMethod": "CONSIGN",
    "operator": "赵调度"
})
BG = bG["id"]
te4 = call("POST", "/api/temp-exceptions", {
    "batchId": BG, "measuredTemp": 6.5, "sampledRotRate": 0.03, "inspector": "李检"
})
T4 = te4["id"]
call("POST", f"/api/temp-exceptions/{T4}/decision", {
    "decision": "PRECOOL_RECHECK", "decidedBy": "周社长",
    "precoolZoneId": 1, "recheckSlotAt": "2026-10-05T16:00:00"
})
rk4 = call("POST", f"/api/temp-exceptions/{T4}/recheck", {
    "recheckTemp": 2, "recheckRotRate": 0.02, "recheckBy": "李检",
    "note": "预冷后果温回到合同区间"
})
assert rk4["recheckResult"] == "PASSED"
bg = call("GET", f"/api/batches/{BG}")
assert bg["status"] == "INSPECTED" and bg["saleFrozen"] is False
lot_g = [l for l in call("GET", "/api/stock/pending") if l["batchId"] == BG][0]
assert float(lot_g["unitPrice"]) == 22.5 and lot_g["shelfLifeDays"] == 3
call("POST", "/api/stock/put-away", {"lotId": lot_g["id"], "locationId": 3, "operator": "赵调度"})
oG = call("POST", "/api/outbounds/plan", {
    "customerId": 2, "batchId": BG, "operator": "赵调度",
    "items": [{"lotId": lot_g["id"], "weight": 100}]
})
assert float(oG["totalAmount"]) == 100 * 22.5
call("POST", f"/api/outbounds/{oG['id']}/execute?operator=赵调度", None)
billG = call("POST", "/api/bills/generate", {"batchId": BG})
itemsG = call("GET", f"/api/bills/{billG['id']}/items")
precool_items = [i for i in itemsG if i["type"] == "PRECOOL_FEE"]
assert len(precool_items) == 1 and float(precool_items[0]["amount"]) == -80.0, \
    f"预冷处理费应为 400kg×1天×0.2=80 元，实际 {precool_items}"
assert any(i["type"] == "SALE" and float(i["amount"]) == 2250.0 for i in itemsG)
print(f"  批次G账单：预冷处理费 {precool_items[0]['amount']} 元，销售收入 2250 元")

# 批次D追溯链应包含温度超标处置与出库撤回节点
chainD = call("GET", f"/api/trace/batch/{BD}")
stagesD = [e["stage"] for e in chainD["timeline"]]
assert "TEMP_EXCEPTION" in stagesD and "OUTBOUND" in stagesD
print(f"  批次D追溯节点={len(chainD['timeline'])}，含温度超标处置与出库计划撤回")

# ---------- 18. 追溯链 ----------
chain = call("GET", f"/api/trace/batch/{B1}")
print(f"  追溯时间线节点数={len(chain['timeline'])}，出库流向={[(f['customer'], f['weight']) for f in chain['finalFlow']]}")
stages = [e["stage"] for e in chain["timeline"]]
for s in ["ARRIVAL", "INSPECTION", "SORTING", "PUT_IN", "OUTBOUND", "COMPLAINT", "SETTLEMENT"]:
    assert s in stages, f"追溯链缺少 {s}"

# ---------- 19. 看板/调度 ----------
ov = call("GET", "/api/dashboard/overview")
print("  总览：", json.dumps(ov, ensure_ascii=False, default=str)[:300])
mon = call("GET", "/api/dashboard/storage-monitor")
print(f"  在仓监控批次行={len(mon)}")
sug = call("GET", "/api/dashboard/dispatch-suggestions")
print(f"  调度建议条数={len(sug)}：{[s['action'] for s in sug]}")
zones = call("GET", "/api/dashboard/zones")
print("  温区：", [(z['code'], z['occupiedKg'], z['fault']) for z in zones])
alerts = call("GET", "/api/alerts/open")
print(f"  未处理告警={len(alerts)}：{sorted(set(a['type'] for a in alerts))}")

# ---------- 20. 批次最终状态 ----------
final_b1 = call("GET", f"/api/batches/{B1}")
print(f"  批次A状态={final_b1['status']} 原净重={final_b1['netWeight']} 可售={final_b1['acceptedWeight']} "
      f"损耗={final_b1['lossWeight']} 已出={final_b1['outWeight']} 退货={final_b1['returnedWeight']}")

print("\n" + ("=" * 60))
if failures:
    print(f"存在 {len(failures)} 个失败：")
    for f in failures:
        print(" -", f)
    raise SystemExit(1)
print("全部业务链验证通过 ✅")
