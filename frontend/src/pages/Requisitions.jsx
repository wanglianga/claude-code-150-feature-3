import React, { useEffect, useMemo, useState } from 'react'
import { api, getUser } from '../api.js'
import { Card, Modal, Field, Badge, fmt, fmtTime, useToast, useMeta } from '../ui.jsx'

export default function Requisitions() {
  const toast = useToast()
  const { meta } = useMeta()
  const user = getUser()
  const canCreate = ['ROLE_FOREMAN', 'ROLE_KEEPER', 'ROLE_PM'].includes(user.role)
  const canIssue = ['ROLE_KEEPER', 'ROLE_PM'].includes(user.role)

  const [rows, setRows] = useState([])
  const [status, setStatus] = useState('')
  const [crewFilter, setCrewFilter] = useState('')
  const [showCreate, setShowCreate] = useState(false)
  const [pre, setPre] = useState(null)
  const [detail, setDetail] = useState(null)
  const [issueRows, setIssueRows] = useState({})

  // 新建领料单
  const blankItem = () => ({ materialId: '', applyQty: '' })
  const [form, setForm] = useState({
    crewId: user.crewId || '', building: '1#楼', area: '', process: '', drawingNo: '',
    urgent: false, remark: '', items: [blankItem()]
  })

  const load = () => {
    const q = new URLSearchParams()
    if (status) q.set('status', status)
    const cid = user.role === 'ROLE_FOREMAN' ? user.crewId : (crewFilter || '')
    if (cid) q.set('crewId', cid)
    api.get('/api/requisitions?' + q.toString()).then(setRows)
  }
  useEffect(() => { load() }, [status, crewFilter])

  const openPrecheck = async (r) => {
    try {
      const data = await api.get(`/api/requisitions/${r.id}/precheck`)
      setPre(data)
      const init = {}
      data.checks.forEach((c) => {
        init[c.itemId] = {
          batchId: c.batches[0]?.batchId || '',
          issuedQty: c.applyQty
        }
      })
      setIssueRows(init)
    } catch (e) { toast(e.message, 'err') }
  }

  const doIssue = async () => {
    const blocked = pre?.checks?.find((c) => c.restricted)
    if (blocked) return toast(`${blocked.materialName}：${blocked.restrictReason}`, 'err')
    try {
      const items = Object.entries(issueRows).map(([itemId, v]) => ({
        itemId: Number(itemId), batchId: Number(v.batchId), issuedQty: Number(v.issuedQty)
      }))
      if (items.some((i) => !i.batchId || !i.issuedQty)) return toast('请为每条明细选择批次并填写实发数量', 'err')
      const res = await api.put(`/api/requisitions/${pre.requisition.id}/issue`, { items })
      toast('发料成功，班组长已确认领用')
      if (res.warnings?.length) res.warnings.forEach((w) => toast(w, 'info'))
      setPre(null); load()
    } catch (e) { toast(e.message, 'err') }
  }

  const doApprove = async (r) => {
    try {
      await api.put(`/api/requisitions/${r.id}/approve`, {})
      toast('已审批通过，进入待发料'); load()
    } catch (e) { toast(e.message, 'err') }
  }

  const doReject = async (r) => {
    if (!window.confirm(`确认驳回领料单 ${r.code}？`)) return
    try {
      await api.put(`/api/requisitions/${r.id}/reject`, {})
      toast('已驳回'); load()
    } catch (e) { toast(e.message, 'err') }
  }

  const submitCreate = async () => {
    if (!form.crewId || !form.process) return toast('请选择班组并填写工序', 'err')
    if (form.items.some((i) => !i.materialId || !i.applyQty)) return toast('请完善领料明细', 'err')
    try {
      const saved = await api.post('/api/requisitions', {
        ...form,
        crewId: Number(form.crewId),
        items: form.items.map((i) => ({ materialId: Number(i.materialId), applyQty: Number(i.applyQty) }))
      })
      toast(saved.status === 'PENDING_PM'
        ? '该班组处于领料限制期，申请已提交，需项目经理审批' : '领料申请已提交')
      setShowCreate(false)
      setForm({ ...form, items: [blankItem()] })
      load()
    } catch (e) { toast(e.message, 'err') }
  }

  // 当前登录班组长所属班组的限制状态
  const myCrew = user.role === 'ROLE_FOREMAN' ? meta.crews.find((c) => c.id === user.crewId) : null

  return (
    <div>
      <Card title="领料单" hint="班组按施工区域/楼栋/工序/图纸申请；仓管核对预算量、已领量、现场库存后发料"
            actions={
              <div className="filters">
                {user.role !== 'ROLE_FOREMAN' && (
                  <div className="f">
                    <label>班组</label>
                    <select value={crewFilter} onChange={(e) => setCrewFilter(e.target.value)}>
                      <option value="">全部班组</option>
                      {meta.crews.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
                    </select>
                  </div>
                )}
                <div className="f">
                  <label>状态</label>
                  <select value={status} onChange={(e) => setStatus(e.target.value)}>
                    <option value="">全部</option>
                    <option value="SUBMITTED">待发料</option>
                    <option value="PENDING_PM">待项目经理审批</option>
                    <option value="ISSUED">已发料</option>
                    <option value="REJECTED">已驳回</option>
                  </select>
                </div>
                {canCreate && <button className="btn primary" onClick={() => setShowCreate(true)}>＋ 申请领料</button>}
              </div>
            } flush>
        <div className="table-scroll">
          <table className="tbl">
            <thead><tr>
              <th>单号</th><th>班组</th><th>楼栋/区域</th><th>工序/图纸</th>
              <th>材料</th><th className="num">金额(元)</th><th>标记</th><th>状态</th>
              <th>申请人/仓管</th><th>时间</th><th>操作</th>
            </tr></thead>
            <tbody>
            {rows.map((r) => (
              <tr key={r.id}>
                <td className="code-link" onClick={() => setDetail(r)}>{r.code}</td>
                <td>{r.crewName}</td>
                <td>{r.building}<div className="muted" style={{ fontSize: 12 }}>{r.area}</div></td>
                <td>{r.process}<div className="muted" style={{ fontSize: 12 }}>{r.drawingNo}</div></td>
                <td>{r.items.map((i) => `${i.materialName}×${fmt(i.issuedQty || i.applyQty)}`).join('，')}</td>
                <td className="num">{fmt(r.totalAmount)}</td>
                <td>
                  {r.urgent && <Badge status="SUBMITTED" label="夜间紧急" tone="red" />}
                  {r.backfill && <Badge status="CLAIMED" label="盘点补录" />}
                </td>
                <td><Badge status={r.status} /></td>
                <td style={{ fontSize: 12 }}>{r.applicant}<div className="muted">{r.issuedBy || '—'}</div></td>
                <td style={{ fontSize: 12 }}>{fmtTime(r.applyTime)}<div className="muted">{fmtTime(r.issueTime)}</div></td>
                <td>
                  {r.status === 'SUBMITTED' && canIssue && (
                    <div className="btn-row">
                      <button className="btn sm primary" onClick={() => openPrecheck(r)}>核对发料</button>
                      <button className="btn sm danger" onClick={() => doReject(r)}>驳回</button>
                    </div>
                  )}
                  {r.status === 'SUBMITTED' && !canIssue && <span className="muted" style={{ fontSize: 12 }}>待仓管发料</span>}
                  {r.status === 'PENDING_PM' && user.role === 'ROLE_PM' && (
                    <div className="btn-row">
                      <button className="btn sm primary" onClick={() => doApprove(r)}>审批通过</button>
                      <button className="btn sm danger" onClick={() => doReject(r)}>驳回</button>
                    </div>
                  )}
                  {r.status === 'PENDING_PM' && user.role !== 'ROLE_PM' && (
                    <span className="muted" style={{ fontSize: 12 }}>班组受限 · 待项目经理审批</span>
                  )}
                  {r.status === 'ISSUED' && <button className="btn sm ghost" onClick={() => setDetail(r)}>查看</button>}
                  {r.status === 'REJECTED' && <span className="muted" style={{ fontSize: 12 }}>已驳回</span>}
                </td>
              </tr>
            ))}
            </tbody>
          </table>
        </div>
      </Card>

      {/* 新建领料单 */}
      {showCreate && (
        <Modal title="班组领料申请" wide onClose={() => setShowCreate(false)}
               footer={<><button className="btn" onClick={() => setShowCreate(false)}>取消</button>
                 <button className="btn primary" onClick={submitCreate}>提交申请</button></>}>
          {myCrew?.requisitionRestricted && (
            <div className="kpi-note neg" style={{ marginBottom: 10 }}>
              本班组处于领料限制期（{myCrew.restrictReason || '盘点补录触发'}），本次申请需项目经理审批后方可发料。
            </div>
          )}
          <div className="grid3">
            <Field label="班组 *">
              <select value={form.crewId} disabled={user.role === 'ROLE_FOREMAN'}
                      onChange={(e) => setForm({ ...form, crewId: e.target.value })}>
                <option value="">请选择</option>
                {meta.crews.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </Field>
            <Field label="楼栋 *"><input value={form.building} onChange={(e) => setForm({ ...form, building: e.target.value })} /></Field>
            <Field label="施工区域"><input value={form.area} onChange={(e) => setForm({ ...form, area: e.target.value })} placeholder="A区/地下室" /></Field>
            <Field label="工序 *"><input value={form.process} onChange={(e) => setForm({ ...form, process: e.target.value })} /></Field>
            <Field label="图纸编号"><input value={form.drawingNo} onChange={(e) => setForm({ ...form, drawingNo: e.target.value })} placeholder="结施-101" /></Field>
            <Field label="紧急程度">
              <select value={form.urgent ? '1' : '0'} onChange={(e) => setForm({ ...form, urgent: e.target.value === '1' })}>
                <option value="0">正常领料</option>
                <option value="1">夜间紧急领料（先发后补）</option>
              </select>
            </Field>
          </div>
          <Field label="备注"><input value={form.remark} onChange={(e) => setForm({ ...form, remark: e.target.value })}
            placeholder="临时追加原因 / 施工进度说明" /></Field>

          <div style={{ margin: '8px 0 6px', fontWeight: 600 }}>领料明细</div>
          <table className="tbl">
            <thead><tr><th>材料</th><th style={{ width: 150 }}>申请数量</th><th style={{ width: 70 }}></th></tr></thead>
            <tbody>
            {form.items.map((it, idx) => (
              <tr key={idx}>
                <td>
                  <select value={it.materialId} onChange={(e) => {
                    const items = form.items.slice()
                    items[idx] = { ...items[idx], materialId: e.target.value }
                    setForm({ ...form, items })
                  }}>
                    <option value="">请选择材料</option>
                    {meta.materials.map((m) => <option key={m.id} value={m.id}>[{m.category}] {m.name}（{m.unit}）</option>)}
                  </select>
                </td>
                <td><input type="number" value={it.applyQty} onChange={(e) => {
                  const items = form.items.slice()
                  items[idx] = { ...items[idx], applyQty: e.target.value }
                  setForm({ ...form, items })
                }} /></td>
                <td>{form.items.length > 1 && (
                  <button className="btn sm danger" onClick={() => setForm({ ...form, items: form.items.filter((_, i) => i !== idx) })}>删</button>
                )}</td>
              </tr>
            ))}
            </tbody>
          </table>
          <button className="btn" style={{ marginTop: 10 }} onClick={() => setForm({ ...form, items: [...form.items, blankItem()] })}>＋ 增加材料</button>
        </Modal>
      )}

      {/* 发料核对 */}
      {pre && (
        <Modal title={`发料核对 · ${pre.requisition.code}`} wide onClose={() => setPre(null)}
               footer={<><button className="btn" onClick={() => setPre(null)}>取消</button>
                 <button className="btn primary" onClick={doIssue}>确认发料（班长同步确认）</button></>}>
          <div className="kpi-note" style={{ marginBottom: 10 }}>
            {pre.requisition.crewName} · {pre.requisition.building} {pre.requisition.area} ·
            <b> {pre.requisition.process}</b> · 图纸 {pre.requisition.drawingNo || '—'}
            {pre.requisition.urgent && <span className="tag-mini" style={{ marginLeft: 8 }}>夜间紧急</span>}
          </div>
          <div className="table-scroll">
            <table className="tbl">
              <thead><tr>
                <th>材料</th><th className="num">申请</th><th className="num">预算量</th>
                <th className="num">已领量</th><th className="num">现场库存</th><th>核对</th>
                <th>发料批次</th><th className="num">实发数量</th>
              </tr></thead>
              <tbody>
              {pre.checks.map((c) => (
                <tr key={c.itemId}>
                  <td><b>{c.materialName}</b><div className="muted" style={{ fontSize: 12 }}>单位：{c.unit}</div></td>
                  <td className="num">{fmt(c.applyQty)}</td>
                  <td className="num">{fmt(c.budgetQty)}</td>
                  <td className="num">{fmt(c.alreadyIssued)}</td>
                  <td className="num">{fmt(c.onHandQty)}</td>
                  <td>
                    {c.restricted
                      ? <Badge status="FAILED" label="盘点差异未关闭·限制领用" />
                      : (!c.stockEnough ? <Badge status="FAILED" label="库存不足" /> : <Badge status="PASSED" label="库存充足" />)}
                    {c.overBudget && <div style={{ marginTop: 4 }}><Badge status="SUBMITTED" label="超预算" tone="red" /></div>}
                    {c.restricted && <div className="muted" style={{ fontSize: 12, marginTop: 4 }}>{c.restrictReason}</div>}
                  </td>
                  <td style={{ minWidth: 200 }}>
                    <select value={issueRows[c.itemId]?.batchId || ''}
                            onChange={(e) => setIssueRows({ ...issueRows, [c.itemId]: { ...issueRows[c.itemId], batchId: e.target.value } })}>
                      <option value="">选择批次</option>
                      {c.batches.map((b) => (
                        <option key={b.batchId} value={b.batchId}>
                          {b.batchNo}｜库存{fmt(b.onHand)}｜{fmt(b.unitPrice)}元｜{b.qcStatus === 'CONDITIONAL' ? '让步接收' : '合格'}
                        </option>
                      ))}
                    </select>
                  </td>
                  <td><input type="number" value={issueRows[c.itemId]?.issuedQty ?? ''}
                    onChange={(e) => setIssueRows({ ...issueRows, [c.itemId]: { ...issueRows[c.itemId], issuedQty: e.target.value } })} /></td>
                </tr>
              ))}
              </tbody>
            </table>
          </div>
          <div className="kpi-note" style={{ marginTop: 10 }}>
            核对口径：施工进度（楼栋/工序/图纸）→ 预算量 → 该班组已领量 → 现场库存与批次质检状态；
            不合格/待检批次不参与发料；超预算时非紧急单将给出预警；盘点差异未关闭的材料限制发料。
          </div>
        </Modal>
      )}

      {/* 详情 */}
      {detail && (
        <Modal title={`领料单 ${detail.code}`} wide onClose={() => setDetail(null)}
               footer={<button className="btn primary" onClick={() => setDetail(null)}>关闭</button>}>
          <div className="kpi-note" style={{ marginBottom: 10 }}>
            {detail.crewName} · {detail.building} {detail.area} · {detail.process} · {detail.drawingNo}<br/>
            状态：<Badge status={detail.status} />　申请人：{detail.applicant}　仓管：{detail.issuedBy || '—'}　班长确认：{detail.confirmedBy || '—'}
            {detail.urgent && <span className="tag-mini" style={{ marginLeft: 8 }}>夜间紧急</span>}
            {detail.backfill && <span className="tag-mini" style={{ marginLeft: 8 }}>盘点补录</span>}
          </div>
          <table className="tbl">
            <thead><tr><th>材料</th><th className="num">申请量</th><th>批次</th><th className="num">实发量</th>
              <th className="num">发料单价</th><th className="num">金额</th></tr></thead>
            <tbody>
            {detail.items.map((i) => (
              <tr key={i.itemId}>
                <td>{i.materialName}</td>
                <td className="num">{fmt(i.applyQty)} {i.unit}</td>
                <td>{i.batchNo || '—'}</td>
                <td className="num">{fmt(i.issuedQty)} {i.unit}</td>
                <td className="num">{fmt(i.issuePrice)}</td>
                <td className="num">{fmt(i.amount)}</td>
              </tr>
            ))}
            </tbody>
          </table>
          <div className="right" style={{ marginTop: 10, fontWeight: 700 }}>合计：{fmt(detail.totalAmount)} 元</div>
          {detail.remark && <div className="kpi-note" style={{ marginTop: 8 }}>备注：{detail.remark}</div>}
        </Modal>
      )}
    </div>
  )
}
