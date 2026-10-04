import React, { useEffect, useState } from 'react'
import { api, getUser } from '../api.js'
import {
  Card, Modal, Field, Badge, Stats, fmt, fmtTime, useToast, useMeta,
  RESPONSIBILITY, WASTE_TYPE, IMPACT_LABEL, IMPACT_TONE, QC_LABEL
} from '../ui.jsx'

export default function StockChecks() {
  const toast = useToast()
  const { meta } = useMeta()
  const user = getUser()
  const isPM = user.role === 'ROLE_PM'
  const canHandle = ['ROLE_KEEPER', 'ROLE_PM'].includes(user.role)

  const [rows, setRows] = useState([])
  const [handovers, setHandovers] = useState([])
  const [show, setShow] = useState(false)
  const [trace, setTrace] = useState(null)          // 追查工作台数据
  const [impactFor, setImpactFor] = useState(null)  // PM 确认进度影响
  const [backfillFor, setBackfillFor] = useState(null) // {checkId, item}
  const [lossFor, setLossFor] = useState(null)      // {checkId, item}
  const [showHandover, setShowHandover] = useState(false)

  const [f, setF] = useState({ scope: '现场仓库', remark: '', items: [{ materialId: '', actualQty: '', responsibility: 'COMPANY', remark: '' }] })
  const [impactF, setImpactF] = useState({ impactLevel: 'NONE', impactNote: '' })
  const [bfF, setBfF] = useState({ crewId: '', qty: '', building: '1#楼', area: '', process: '', remark: '' })
  const [clF, setClF] = useState({ qty: '', building: '1#楼', remark: '' })
  const [hF, setHF] = useState({ fromKeeper: '', toKeeper: '', cctvRef: '', note: '', relatedCrewId: '', relatedMaterialId: '' })

  const load = () => api.get('/api/stock-checks').then(setRows)
  const loadHandovers = () => api.get('/api/handovers').then(setHandovers)
  useEffect(() => { load(); loadHandovers() }, [])

  const openTrace = async (sc) => {
    try { setTrace(await api.get(`/api/stock-checks/${sc.id}/trace`)) } catch (e) { toast(e.message, 'err') }
  }
  const reloadTrace = async (id) => {
    try { setTrace(await api.get(`/api/stock-checks/${id}/trace`)) } catch (e) { /* 已关闭也允许查看 */ }
  }

  const submit = async () => {
    if (f.items.some((i) => !i.materialId || i.actualQty === '')) return toast('请完善盘点材料与实盘数量', 'err')
    try {
      await api.post('/api/stock-checks', {
        scope: f.scope, remark: f.remark,
        items: f.items.map((i) => ({
          materialId: Number(i.materialId), actualQty: Number(i.actualQty),
          responsibility: i.responsibility, remark: i.remark
        }))
      })
      toast('盘点单已登记，盘亏材料已限制领用，请追查差异'); setShow(false); load()
    } catch (e) { toast(e.message, 'err') }
  }

  const doBackfill = async () => {
    if (!bfF.crewId || !bfF.qty || !bfF.process) return toast('请选择班组并填写补录数量与工序', 'err')
    try {
      await api.post(`/api/stock-checks/${backfillFor.checkId}/items/${backfillFor.item.itemId}/backfill`, {
        crewId: Number(bfF.crewId), qty: Number(bfF.qty), building: bfF.building,
        area: bfF.area, process: bfF.process, remark: bfF.remark
      })
      toast('已补录领料并计入班组成本，该班组后续领料需项目经理审批')
      setBackfillFor(null); load(); reloadTrace(backfillFor.checkId)
    } catch (e) { toast(e.message, 'err') }
  }

  const doCommonLoss = async () => {
    if (!clF.qty) return toast('请填写转公共损耗数量', 'err')
    try {
      await api.post(`/api/stock-checks/${lossFor.checkId}/items/${lossFor.item.itemId}/common-loss`, {
        qty: Number(clF.qty), building: clF.building, remark: clF.remark
      })
      toast('已转入项目公共损耗（损耗台账留痕）')
      setLossFor(null); load(); reloadTrace(lossFor.checkId)
    } catch (e) { toast(e.message, 'err') }
  }

  const doImpact = async () => {
    try {
      await api.put(`/api/stock-checks/${impactFor.id}/impact`, impactF)
      toast('进度影响已确认'); setImpactFor(null); load()
      if (trace?.check?.id === impactFor.id) reloadTrace(impactFor.id)
    } catch (e) { toast(e.message, 'err') }
  }

  const close = async (sc) => {
    if (!window.confirm(`确认关闭盘点单 ${sc.code}？将按实盘调整账面库存并解除材料领用限制。`)) return
    try {
      await api.put(`/api/stock-checks/${sc.id}/close`, {})
      toast('盘点已关闭，账面库存已调整'); setTrace(null); load()
    } catch (e) { toast(e.message, 'err') }
  }

  const addHandover = async () => {
    if (!hF.fromKeeper || !hF.toKeeper || !hF.note) return toast('请填写交班人、接班人与交接事项', 'err')
    try {
      await api.post('/api/handovers', {
        ...hF,
        relatedCrewId: hF.relatedCrewId ? Number(hF.relatedCrewId) : null,
        relatedMaterialId: hF.relatedMaterialId ? Number(hF.relatedMaterialId) : null
      })
      toast('交接记录已登记'); setShowHandover(false)
      setHF({ fromKeeper: '', toKeeper: '', cctvRef: '', note: '', relatedCrewId: '', relatedMaterialId: '' })
      loadHandovers()
    } catch (e) { toast(e.message, 'err') }
  }

  const openBackfill = (checkId, item) => {
    setBfF({ crewId: '', qty: item.remainingQty, building: '1#楼', area: '', process: '', remark: '' })
    setBackfillFor({ checkId, item })
  }
  const openCommonLoss = (checkId, item) => {
    setClF({ qty: item.remainingQty, building: '1#楼', remark: '' })
    setLossFor({ checkId, item })
  }

  return (
    <div>
      <Card title="盘点与账实差异追查"
            hint="盘亏差异按 入库/领用/退库/损耗/监控交接 追查：补录班组未登记领料或转项目公共损耗；差异关闭前相关材料限制领用，项目经理确认进度影响后关闭"
            actions={<button className="btn primary" onClick={() => setShow(true)}>＋ 新建盘点</button>} flush>
        <div className="table-scroll">
          <table className="tbl">
            <thead><tr>
              <th>盘点单号</th><th>盘点范围</th><th>盘点项</th>
              <th className="num">盘盈/盘亏金额</th><th>状态</th><th>进度影响</th>
              <th>盘点人/关闭人</th><th>备注</th><th>操作</th>
            </tr></thead>
            <tbody>
            {rows.map((sc) => (
              <tr key={sc.id}>
                <td className="code-link" onClick={() => openTrace(sc)}>{sc.code}</td>
                <td>{sc.scope}</td>
                <td>{sc.items.length} 项{sc.hasLoss && <div><Badge status="FAILED" label="含盘亏" /></div>}</td>
                <td className={`num ${sc.diffTotal < 0 ? 'pos' : 'neg'}`}>{fmt(sc.diffTotal)}</td>
                <td><Badge status={sc.status} /></td>
                <td>
                  {sc.impactLevel
                    ? <Badge status="PASSED" label={IMPACT_LABEL[sc.impactLevel]} tone={IMPACT_TONE[sc.impactLevel]} />
                    : (sc.hasLoss && sc.status === 'COUNTING' ? <Badge status="PENDING" label="待PM确认" /> : '—')}
                </td>
                <td style={{ fontSize: 12 }}>{sc.recorder}<div className="muted">{sc.confirmer || '—'}</div></td>
                <td className="desc-cell">{sc.remark}</td>
                <td>
                  <div className="btn-row">
                    <button className="btn sm" onClick={() => openTrace(sc)}>
                      {sc.status === 'COUNTING' ? '追查差异' : '查看'}
                    </button>
                    {sc.status === 'COUNTING' && isPM && sc.hasLoss && !sc.impactLevel && (
                      <button className="btn sm primary" onClick={() => { setImpactF({ impactLevel: 'NONE', impactNote: '' }); setImpactFor(sc) }}>确认影响</button>
                    )}
                    {sc.status === 'COUNTING' && canHandle && (
                      <button className="btn sm primary" disabled={!sc.canClose}
                              title={sc.canClose ? '' : '需先处理全部盘亏差异并由项目经理确认进度影响'}
                              onClick={() => close(sc)}>关闭盘点</button>
                    )}
                  </div>
                </td>
              </tr>
            ))}
            </tbody>
          </table>
        </div>
      </Card>

      {/* 监控交接记录 */}
      <div style={{ marginTop: 14 }}>
      <Card title="监控交接记录" hint="班次交接时登记监控核看到的出入库异常，是盘点差异追查的线索来源"
            actions={canHandle && <button className="btn" onClick={() => setShowHandover(true)}>＋ 登记交接</button>}
            flush>
        <div className="table-scroll">
          <table className="tbl">
            <thead><tr>
              <th>交接时间</th><th>交班/接班</th><th>监控点位</th><th>交接事项</th><th>涉及班组/材料</th><th>登记人</th>
            </tr></thead>
            <tbody>
            {handovers.map((h) => (
              <tr key={h.id}>
                <td style={{ fontSize: 12 }}>{fmtTime(h.handoverTime)}</td>
                <td style={{ fontSize: 12 }}>{h.fromKeeper} → {h.toKeeper}</td>
                <td className="desc-cell">{h.cctvRef || '—'}</td>
                <td className="desc-cell">{h.note}</td>
                <td style={{ fontSize: 12 }}>{h.relatedCrewName || '—'}<div className="muted">{h.relatedMaterialName || ''}</div></td>
                <td style={{ fontSize: 12 }}>{h.recorder}</td>
              </tr>
            ))}
            </tbody>
          </table>
        </div>
      </Card>
      </div>

      {/* 新建盘点单 */}
      {show && (
        <Modal title="新建盘点单" wide onClose={() => setShow(false)}
               footer={<><button className="btn" onClick={() => setShow(false)}>取消</button>
                 <button className="btn primary" onClick={submit}>登记盘点（盘亏材料将限制领用）</button></>}>
          <div className="grid2">
            <Field label="盘点范围"><input value={f.scope} onChange={(e) => setF({ ...f, scope: e.target.value })} /></Field>
            <Field label="备注"><input value={f.remark} onChange={(e) => setF({ ...f, remark: e.target.value })} /></Field>
          </div>
          <table className="tbl">
            <thead><tr><th>材料（默认取最早批次）</th><th style={{ width: 130 }}>实盘数量</th>
              <th style={{ width: 150 }}>盘亏责任归属</th><th>备注</th><th style={{ width: 60 }}></th></tr></thead>
            <tbody>
            {f.items.map((it, idx) => {
              const set = (patch) => {
                const items = f.items.slice()
                items[idx] = { ...items[idx], ...patch }
                setF({ ...f, items })
              }
              return (
                <tr key={idx}>
                  <td>
                    <select value={it.materialId} onChange={(e) => set({ materialId: e.target.value })}>
                      <option value="">请选择材料</option>
                      {meta.materials.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
                    </select>
                  </td>
                  <td><input type="number" value={it.actualQty} onChange={(e) => set({ actualQty: e.target.value })} /></td>
                  <td>
                    <select value={it.responsibility} onChange={(e) => set({ responsibility: e.target.value })}>
                      {Object.entries(RESPONSIBILITY).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                    </select>
                  </td>
                  <td><input value={it.remark} onChange={(e) => set({ remark: e.target.value })} placeholder="差异原因" /></td>
                  <td>{f.items.length > 1 && <button className="btn sm danger"
                    onClick={() => setF({ ...f, items: f.items.filter((_, i) => i !== idx) })}>删</button>}</td>
                </tr>
              )
            })}
            </tbody>
          </table>
          <button className="btn" style={{ marginTop: 10 }}
            onClick={() => setF({ ...f, items: [...f.items, { materialId: '', actualQty: '', responsibility: 'COMPANY', remark: '' }] })}>
            ＋ 增加盘点项
          </button>
        </Modal>
      )}

      {/* 差异追查工作台 */}
      {trace && (
        <Modal title={`差异追查 · ${trace.check.code}`} wide onClose={() => setTrace(null)}
               footer={<><button className="btn" onClick={() => setTrace(null)}>关闭窗口</button>
                 {trace.check.status === 'COUNTING' && isPM && trace.check.hasLoss && !trace.check.impactLevel && (
                   <button className="btn primary" onClick={() => { setImpactF({ impactLevel: 'NONE', impactNote: '' }); setImpactFor(trace.check) }}>确认进度影响</button>
                 )}
                 {trace.check.status === 'COUNTING' && canHandle && (
                   <button className="btn primary" disabled={!trace.check.canClose}
                           title={trace.check.canClose ? '' : '需先处理全部盘亏差异并由项目经理确认进度影响'}
                           onClick={() => close(trace.check)}>关闭盘点并调账</button>
                 )}</>}>
          <div className="kpi-note" style={{ marginBottom: 10 }}>
            范围：{trace.check.scope} · 状态：<Badge status={trace.check.status} /> ·
            差异合计 <b className={trace.check.diffTotal < 0 ? 'pos' : 'neg'}>{fmt(trace.check.diffTotal)} 元</b>
            {trace.check.impactLevel && (
              <span> · 进度影响：<Badge status="PASSED" label={IMPACT_LABEL[trace.check.impactLevel]}
                tone={IMPACT_TONE[trace.check.impactLevel]} />（{trace.check.impactConfirmer}：{trace.check.impactNote || '无说明'}）</span>
            )}
            {trace.check.status === 'COUNTING' && trace.check.hasLoss && !trace.check.impactLevel && (
              <span className="neg"> · 项目经理尚未确认施工进度影响</span>
            )}
          </div>

          {trace.items.map((it) => (
            <div key={it.itemId} className="card" style={{ marginBottom: 14 }}>
              <div className="card-bd">
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 8 }}>
                  <b>{it.materialName}</b>
                  {it.lossQty > 0
                    ? <Badge status="FAILED" label={`盘亏 ${fmt(it.lossQty)} ${it.unit}`} />
                    : <Badge status="PASSED" label={it.diffQty > 0 ? `盘盈 ${fmt(it.diffQty)} ${it.unit}` : '账实相符'} />}
                </div>
                <Stats items={[
                  { label: '账面数量', value: `${fmt(it.bookQty)} ${it.unit}` },
                  { label: '实盘数量', value: fmt(it.actualQty) },
                  { label: '差异数量', value: fmt(it.diffQty), tone: it.diffQty < 0 ? 'red' : 'green' },
                  { label: '已补录领料', value: fmt(it.backfillQty) },
                  { label: '已转公共损耗', value: fmt(it.commonLossQty) },
                  { label: '待处理差异', value: fmt(it.remainingQty), tone: it.remainingQty > 0 ? 'amber' : 'green' }
                ]} />
                {it.lossQty > 0 && trace.check.status === 'COUNTING' && canHandle && it.remainingQty > 0 && (
                  <div className="btn-row" style={{ margin: '4px 0 10px' }}>
                    <button className="btn sm primary" onClick={() => openBackfill(trace.check.id, it)}>补录班组领料</button>
                    <button className="btn sm" onClick={() => openCommonLoss(trace.check.id, it)}>无法定位 · 转项目公共损耗</button>
                  </div>
                )}
                {it.remark && <div className="kpi-note" style={{ marginBottom: 8 }}>登记备注：{it.remark}</div>}

                <div className="grid2">
                  <div>
                    <div className="sect-title">入库批次</div>
                    <table className="tbl">
                      <thead><tr><th>批次号</th><th>供应商</th><th className="num">入库</th><th className="num">已领</th>
                        <th className="num">退库</th><th className="num">损耗</th><th className="num">调整</th><th className="num">账面</th><th>质检</th></tr></thead>
                      <tbody>
                      {it.inbound.map((b) => (
                        <tr key={b.batchId}>
                          <td style={{ fontSize: 12 }}>{b.batchNo}</td>
                          <td style={{ fontSize: 12 }}>{b.supplier || '—'}</td>
                          <td className="num">{fmt(b.qtyInbound)}</td>
                          <td className="num">{fmt(b.qtyIssued)}</td>
                          <td className="num">{fmt(b.qtyReturned)}</td>
                          <td className="num">{fmt(b.qtyWasted)}</td>
                          <td className="num">{fmt(b.qtyAdjust)}</td>
                          <td className="num"><b>{fmt(b.onHand)}</b></td>
                          <td><Badge status={b.qcStatus} label={QC_LABEL[b.qcStatus]} /></td>
                        </tr>
                      ))}
                      </tbody>
                    </table>
                    <div className="sect-title">退库记录</div>
                    <table className="tbl">
                      <thead><tr><th>单号</th><th>班组</th><th className="num">数量</th><th>确认时间</th></tr></thead>
                      <tbody>
                      {it.returns.length === 0 && <tr><td colSpan="4" className="muted">无</td></tr>}
                      {it.returns.map((r) => (
                        <tr key={r.code}><td style={{ fontSize: 12 }}>{r.code}</td><td>{r.crewName}</td>
                          <td className="num">{fmt(r.qty)}</td><td style={{ fontSize: 12 }}>{fmtTime(r.confirmTime)}</td></tr>
                      ))}
                      </tbody>
                    </table>
                  </div>
                  <div>
                    <div className="sect-title">领用记录（已发料）</div>
                    <table className="tbl">
                      <thead><tr><th>单号</th><th>班组</th><th>工序</th><th className="num">数量</th><th>标记</th><th>发料时间</th></tr></thead>
                      <tbody>
                      {it.requisitions.length === 0 && <tr><td colSpan="6" className="muted">无</td></tr>}
                      {it.requisitions.map((r) => (
                        <tr key={r.code}>
                          <td style={{ fontSize: 12 }}>{r.code}</td><td>{r.crewName}</td>
                          <td style={{ fontSize: 12 }}>{r.process}</td><td className="num">{fmt(r.qty)}</td>
                          <td>{r.backfill && <Badge status="CLAIMED" label="盘点补录" />}
                            {r.urgent && !r.backfill && <Badge status="SUBMITTED" label="夜间紧急" tone="red" />}</td>
                          <td style={{ fontSize: 12 }}>{fmtTime(r.issueTime)}</td>
                        </tr>
                      ))}
                      </tbody>
                    </table>
                    <div className="sect-title">损耗登记</div>
                    <table className="tbl">
                      <thead><tr><th>单号</th><th>班组</th><th className="num">数量</th><th>类型/责任</th></tr></thead>
                      <tbody>
                      {it.wastes.length === 0 && <tr><td colSpan="4" className="muted">无</td></tr>}
                      {it.wastes.map((w) => (
                        <tr key={w.code}><td style={{ fontSize: 12 }}>{w.code}</td><td>{w.crewName || '项目'}</td>
                          <td className="num">{fmt(w.qty)}</td>
                          <td style={{ fontSize: 12 }}>{WASTE_TYPE[w.wasteType] || w.wasteType} / {RESPONSIBILITY[w.responsibility]}</td></tr>
                      ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              </div>
            </div>
          ))}

          <div className="sect-title">监控交接线索</div>
          <table className="tbl">
            <thead><tr><th>交接时间</th><th>交班/接班</th><th>监控点位</th><th>交接事项</th><th>涉及班组/材料</th></tr></thead>
            <tbody>
            {trace.handovers.map((h) => (
              <tr key={h.id}>
                <td style={{ fontSize: 12 }}>{fmtTime(h.handoverTime)}</td>
                <td style={{ fontSize: 12 }}>{h.fromKeeper} → {h.toKeeper}</td>
                <td className="desc-cell">{h.cctvRef || '—'}</td>
                <td className="desc-cell">{h.note}</td>
                <td style={{ fontSize: 12 }}>{h.relatedCrewName || '—'}<div className="muted">{h.relatedMaterialName || ''}</div></td>
              </tr>
            ))}
            </tbody>
          </table>
        </Modal>
      )}

      {/* 补录班组领料 */}
      {backfillFor && (
        <Modal title={`补录班组领料 · ${backfillFor.item.materialName}`} onClose={() => setBackfillFor(null)}
               footer={<><button className="btn" onClick={() => setBackfillFor(null)}>取消</button>
                 <button className="btn primary" onClick={doBackfill}>补录并限制班组审批</button></>}>
          <div className="kpi-note" style={{ marginBottom: 10 }}>
            待处理差异 <b className="pos">{fmt(backfillFor.item.remainingQty)} {backfillFor.item.unit}</b>。
            补录后生成已发料领料单（夜间紧急·盘点补录），计入班组成本，该班组后续领料需项目经理审批。
          </div>
          <div className="grid2">
            <Field label="责任班组 *">
              <select value={bfF.crewId} onChange={(e) => setBfF({ ...bfF, crewId: e.target.value })}>
                <option value="">请选择</option>
                {meta.crews.map((c) => <option key={c.id} value={c.id}>{c.name}（{c.leader}）</option>)}
              </select>
            </Field>
            <Field label="补录数量 *"><input type="number" value={bfF.qty} onChange={(e) => setBfF({ ...bfF, qty: e.target.value })} /></Field>
            <Field label="楼栋 *"><input value={bfF.building} onChange={(e) => setBfF({ ...bfF, building: e.target.value })} /></Field>
            <Field label="施工区域"><input value={bfF.area} onChange={(e) => setBfF({ ...bfF, area: e.target.value })} placeholder="标准层" /></Field>
            <Field label="工序 *"><input value={bfF.process} onChange={(e) => setBfF({ ...bfF, process: e.target.value })} placeholder="墙地砖铺贴" /></Field>
            <Field label="备注"><input value={bfF.remark} onChange={(e) => setBfF({ ...bfF, remark: e.target.value })} placeholder="如：监控核实夜间未登记领料" /></Field>
          </div>
        </Modal>
      )}

      {/* 转项目公共损耗 */}
      {lossFor && (
        <Modal title={`转入项目公共损耗 · ${lossFor.item.materialName}`} onClose={() => setLossFor(null)}
               footer={<><button className="btn" onClick={() => setLossFor(null)}>取消</button>
                 <button className="btn primary" onClick={doCommonLoss}>确认转入</button></>}>
          <div className="kpi-note" style={{ marginBottom: 10 }}>
            无法定位责任的差异计入项目公共损耗（项目承担，不归集到班组），
            在损耗台账留痕并进入月底复盘；库存在盘点关闭调账时统一核销。剩余待处理
            <b className="pos"> {fmt(lossFor.item.remainingQty)} {lossFor.item.unit}</b>。
          </div>
          <div className="grid2">
            <Field label="数量 *"><input type="number" value={clF.qty} onChange={(e) => setClF({ ...clF, qty: e.target.value })} /></Field>
            <Field label="楼栋 *"><input value={clF.building} onChange={(e) => setClF({ ...clF, building: e.target.value })} /></Field>
          </div>
          <Field label="备注"><input value={clF.remark} onChange={(e) => setClF({ ...clF, remark: e.target.value })} placeholder="如：尾差无法定位责任" /></Field>
        </Modal>
      )}

      {/* PM 确认进度影响 */}
      {impactFor && (
        <Modal title={`确认施工进度影响 · ${impactFor.code}`} onClose={() => setImpactFor(null)}
               footer={<><button className="btn" onClick={() => setImpactFor(null)}>取消</button>
                 <button className="btn primary" onClick={doImpact}>确认</button></>}>
          <div className="kpi-note" style={{ marginBottom: 10 }}>
            差异关闭前相关材料限制继续领用。请评估限制领用对现场施工进度的影响，确认后方可关闭盘点。
          </div>
          <Field label="影响程度 *">
            <select value={impactF.impactLevel} onChange={(e) => setImpactF({ ...impactF, impactLevel: e.target.value })}>
              {Object.entries(IMPACT_LABEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </Field>
          <Field label="影响说明 / 应对措施">
            <input value={impactF.impactNote} onChange={(e) => setImpactF({ ...impactF, impactNote: e.target.value })}
                   placeholder="如：瓷砖暂停领用影响标准层铺贴 1 天，已协调调拨" />
          </Field>
        </Modal>
      )}

      {/* 登记监控交接 */}
      {showHandover && (
        <Modal title="登记监控交接" onClose={() => setShowHandover(false)}
               footer={<><button className="btn" onClick={() => setShowHandover(false)}>取消</button>
                 <button className="btn primary" onClick={addHandover}>登记</button></>}>
          <div className="grid2">
            <Field label="交班人 *"><input value={hF.fromKeeper} onChange={(e) => setHF({ ...hF, fromKeeper: e.target.value })} /></Field>
            <Field label="接班人 *"><input value={hF.toKeeper} onChange={(e) => setHF({ ...hF, toKeeper: e.target.value })} /></Field>
            <Field label="监控点位/录像时段"><input value={hF.cctvRef} onChange={(e) => setHF({ ...hF, cctvRef: e.target.value })} placeholder="A库-西门 CAM-03 23:36-23:52" /></Field>
            <Field label="涉及班组">
              <select value={hF.relatedCrewId} onChange={(e) => setHF({ ...hF, relatedCrewId: e.target.value })}>
                <option value="">无</option>
                {meta.crews.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </Field>
            <Field label="涉及材料">
              <select value={hF.relatedMaterialId} onChange={(e) => setHF({ ...hF, relatedMaterialId: e.target.value })}>
                <option value="">无</option>
                {meta.materials.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
              </select>
            </Field>
          </div>
          <Field label="交接事项 *"><input value={hF.note} onChange={(e) => setHF({ ...hF, note: e.target.value })}
            placeholder="如：监控显示某班组夜间领料未登记，待核实" /></Field>
        </Modal>
      )}
    </div>
  )
}
