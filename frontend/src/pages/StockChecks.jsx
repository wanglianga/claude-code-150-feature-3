import React, { useEffect, useState } from 'react'
import { api, getUser } from '../api.js'
import { Card, Modal, Field, Badge, fmt, fmtTime, useToast, useMeta,
  RESOLUTION_LABEL, CONCLUSION_LABEL, TRACE_TYPE, TRACE_RESULT } from '../ui.jsx'

const TRACE_OPTIONS = ['INBOUND', 'REQUISITION', 'RETURN', 'WASTE', 'MONITOR']

export default function StockChecks() {
  const toast = useToast()
  const { meta } = useMeta()
  const user = getUser()
  const isPM = user.role === 'ROLE_PM'
  const canOperate = ['ROLE_KEEPER', 'ROLE_PM'].includes(user.role)

  const [rows, setRows] = useState([])
  const [showCreate, setShowCreate] = useState(false)
  const [detail, setDetail] = useState(null)
  // 弹窗：trace 核查轨迹 / loss 公共损耗 / night 夜料补录 / progress 进度确认
  const [dlg, setDlg] = useState(null)
  const [createForm, setCreateForm] = useState({
    scope: '1#楼现场仓库', remark: '',
    items: [{ materialId: '', actualQty: '', responsibility: 'COMPANY', remark: '' }]
  })

  const load = () => api.get('/api/stock-checks').then(setRows)
  const reloadDetail = async (id) => {
    const all = await api.get('/api/stock-checks')
    setRows(all)
    const fresh = all.find((x) => x.id === id)
    if (fresh) setDetail(fresh)
  }
  useEffect(() => { load() }, [])

  const submitCreate = async () => {
    if (createForm.items.some((i) => !i.materialId || i.actualQty === '')) return toast('请完善盘点材料与实盘数量', 'err')
    try {
      await api.post('/api/stock-checks', {
        scope: createForm.scope, remark: createForm.remark,
        items: createForm.items.map((i) => ({
          materialId: Number(i.materialId), actualQty: Number(i.actualQty),
          responsibility: i.responsibility, remark: i.remark
        }))
      })
      toast('盘点单已登记：盘亏材料即刻冻结领用，开始追查')
      setShowCreate(false); load()
    } catch (e) { toast(e.message, 'err') }
  }

  const addTrace = async (f) => {
    if (!f.detail) return toast('请填写核查结论说明', 'err')
    try {
      await api.post(`/api/stock-checks/${detail.id}/traces`, {
        traceType: f.traceType, result: f.result,
        materialId: f.materialId ? Number(f.materialId) : null,
        refCode: f.refCode || null, qty: f.qty === '' ? null : Number(f.qty), detail: f.detail
      })
      toast('核查轨迹已登记'); setDlg(null); reloadDetail(detail.id)
    } catch (e) { toast(e.message, 'err') }
  }

  const resolveCommon = async (f) => {
    if (!f.qty || Number(f.qty) <= 0) return toast('请填写公共损耗数量', 'err')
    try {
      const res = await api.post(`/api/stock-checks/${detail.id}/common-loss`, {
        itemId: f.itemId, qty: Number(f.qty), remark: f.remark
      })
      toast(`已计入项目公共损耗（损耗单 ${res.wasteCode}），批次库存已核销`)
      setDlg(null); reloadDetail(detail.id)
    } catch (e) { toast(e.message, 'err') }
  }

  const supplement = async (f) => {
    if (!f.crewId || !f.process || !f.qty || !f.actualTime) return toast('请完善责任班组、工序、数量与夜间领料时间', 'err')
    try {
      const res = await api.post(`/api/stock-checks/${detail.id}/supplement-night`, {
        itemId: f.itemId, crewId: Number(f.crewId), building: f.building, area: f.area,
        process: f.process, drawingNo: f.drawingNo, qty: Number(f.qty),
        batchId: f.batchId ? Number(f.batchId) : null,
        actualTime: f.actualTime, remark: f.remark
      })
      toast(`夜料已补录（${res.requisitionCode}），计入班组成本，该班组后续领料须 PM 审批`)
      setDlg(null); reloadDetail(detail.id)
    } catch (e) { toast(e.message, 'err') }
  }

  const confirmProgress = async (f) => {
    try {
      await api.put(`/api/stock-checks/${detail.id}/progress`, { impact: f.impact, remark: f.remark })
      toast('项目经理已确认进度影响'); setDlg(null); reloadDetail(detail.id)
    } catch (e) { toast(e.message, 'err') }
  }

  const closeCheck = async () => {
    if (!window.confirm('确认关闭该盘点单？全部盘亏须已定性、且项目经理已确认进度影响，关闭后解除材料领用冻结。')) return
    try {
      await api.put(`/api/stock-checks/${detail.id}/close`, {})
      toast('盘点单已关闭，差异材料解除领用冻结')
      setDetail(null); load()
    } catch (e) { toast(e.message, 'err') }
  }

  return (
    <Card title="月底盘点 · 账实差异追查" hint="盘亏材料关闭前冻结领用；按入库/领用/退库/破损/监控交接追查，定性为公共损耗或班组夜料补录，PM 确认进度影响后关闭"
          actions={<button className="btn primary" onClick={() => setShowCreate(true)}>＋ 新建盘点</button>} flush>
      <div className="table-scroll">
        <table className="tbl">
          <thead><tr>
            <th>盘点单号</th><th>盘点范围</th><th>盘点项</th>
            <th className="num">盘亏数量</th><th className="num">盘亏金额(元)</th>
            <th className="num">已补录夜料</th><th className="num">公共损耗</th><th className="num">未解释</th>
            <th>定性结论</th><th>状态</th><th>操作</th>
          </tr></thead>
          <tbody>
          {rows.map((sc) => (
            <tr key={sc.id}>
              <td className="code-link" onClick={() => setDetail(sc)}>{sc.code}</td>
              <td>{sc.scope}</td>
              <td>{sc.items.length} 项</td>
              <td className="num pos">{fmt(sc.shortageTotal)}</td>
              <td className="num pos">{fmt(sc.shortageAmount)}</td>
              <td className="num">{fmt(sc.supplementedTotal)}</td>
              <td className="num">{fmt(sc.commonLossTotal)}</td>
              <td className={`num ${sc.unexplainedTotal > 0 ? 'neg' : ''}`}>{fmt(sc.unexplainedTotal)}</td>
              <td>{sc.conclusion ? CONCLUSION_LABEL[sc.conclusion] : <span className="muted">追查中</span>}</td>
              <td>
                <Badge status={sc.status} />
                {sc.frozen && <div style={{ marginTop: 4 }}><Badge status="PENDING" label="材料冻结" tone="red" /></div>}
              </td>
              <td><button className={`btn sm ${sc.status === 'COUNTING' ? 'primary' : 'ghost'}`}
                          onClick={() => setDetail(sc)}>
                {sc.status === 'COUNTING' ? '追查处理' : '查看'}
              </button></td>
            </tr>
          ))}
          </tbody>
        </table>
      </div>

      {/* 新建盘点 */}
      {showCreate && (
        <Modal title="新建盘点单" wide onClose={() => setShowCreate(false)}
               footer={<><button className="btn" onClick={() => setShowCreate(false)}>取消</button>
                 <button className="btn primary" onClick={submitCreate}>登记盘点并冻结盘亏材料</button></>}>
          <div className="grid2">
            <Field label="盘点范围"><input value={createForm.scope} onChange={(e) => setCreateForm({ ...createForm, scope: e.target.value })} /></Field>
            <Field label="备注"><input value={createForm.remark} onChange={(e) => setCreateForm({ ...createForm, remark: e.target.value })} /></Field>
          </div>
          <table className="tbl">
            <thead><tr><th>材料（取最早批次账面）</th><th style={{ width: 130 }}>实盘数量</th>
              <th style={{ width: 150 }}>初判责任归属</th><th>备注</th><th style={{ width: 60 }}></th></tr></thead>
            <tbody>
            {createForm.items.map((it, idx) => {
              const set = (patch) => {
                const items = createForm.items.slice()
                items[idx] = { ...items[idx], ...patch }
                setCreateForm({ ...createForm, items })
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
                      <option value="COMPANY">项目承担（待追查）</option>
                      <option value="CREW">疑似班组</option>
                    </select>
                  </td>
                  <td><input value={it.remark} onChange={(e) => set({ remark: e.target.value })} placeholder="差异描述" /></td>
                  <td>{createForm.items.length > 1 && <button className="btn sm danger"
                    onClick={() => setCreateForm({ ...createForm, items: createForm.items.filter((_, i) => i !== idx) })}>删</button>}</td>
                </tr>
              )
            })}
            </tbody>
          </table>
          <button className="btn" style={{ marginTop: 10 }}
            onClick={() => setCreateForm({ ...createForm, items: [...createForm.items, { materialId: '', actualQty: '', responsibility: 'COMPANY', remark: '' }] })}>
            ＋ 增加盘点项
          </button>
        </Modal>
      )}

      {/* 追查工作台 */}
      {detail && (
        <Modal title={`账实差异追查 · ${detail.code}`} wide onClose={() => setDetail(null)}
               footer={<><button className="btn" onClick={() => setDetail(null)}>关闭窗口</button>
                 {detail.status === 'COUNTING' && canOperate && (
                   <button className={detail.canClose ? 'btn primary' : 'btn'} disabled={!detail.canClose}
                           title={detail.unexplainedTotal > 0 ? '仍有盘亏未定性' : detail.progressImpact ? '' : '须 PM 先确认进度影响'}
                           onClick={closeCheck}>轧账关闭并解冻</button>
                 )}
               </>}>
          <div className="kpi-note" style={{ marginBottom: 10 }}>
            范围：{detail.scope} · 状态：<Badge status={detail.status} />
            {detail.frozen && <Badge status="PENDING" label="差异材料冻结领用中" tone="red" />}
            {detail.conclusion && <span> · 结论：<b>{CONCLUSION_LABEL[detail.conclusion]}</b></span>}
            <div style={{ marginTop: 4 }}>
              进度影响（PM 确认）：
              {detail.progressImpact === 'YES' ? <Badge status="FAILED" label="影响施工进度" />
                : detail.progressImpact === 'NO' ? <Badge status="PASSED" label="不影响施工进度" />
                : <Badge status="PENDING" label="待项目经理确认" tone="amber" />}
              {detail.progressConfirmedBy && <span className="muted"> {detail.progressConfirmedBy} · {fmtTime(detail.progressConfirmTime)}</span>}
            </div>
            {detail.progressRemark && <div className="muted" style={{ marginTop: 2 }}>进度说明：{detail.progressRemark}</div>}
          </div>

          {/* 差异明细与定性操作 */}
          <table className="tbl">
            <thead><tr>
              <th>材料</th><th className="num">账面</th><th className="num">实盘</th><th className="num">盘亏</th>
              <th className="num">已补录</th><th className="num">公共损耗</th><th className="num">未解释</th>
              <th>责任班组</th><th>定性状态</th>{detail.status === 'COUNTING' && canOperate && <th>操作</th>}
            </tr></thead>
            <tbody>
            {detail.items.map((i) => (
              <tr key={i.itemId}>
                <td><b>{i.materialName}</b><div className="muted" style={{ fontSize: 12 }}>{i.remark}</div></td>
                <td className="num">{fmt(i.bookQty)} {i.unit}</td>
                <td className="num">{fmt(i.actualQty)}</td>
                <td className="num pos">{fmt(i.shortageQty)}</td>
                <td className="num">{fmt(i.supplementedQty)}</td>
                <td className="num">{fmt(i.commonLossQty)}</td>
                <td className={`num ${i.unexplainedQty > 0 ? 'neg' : ''}`}>{fmt(i.unexplainedQty)}</td>
                <td>{i.responsibleCrewName || '—'}</td>
                <td><Badge status="DRAFT" tone={i.resolution === 'OPEN' ? 'amber' : 'green'}
                           label={RESOLUTION_LABEL[i.resolution]} /></td>
                {detail.status === 'COUNTING' && canOperate && (
                  <td>
                    {i.shortageQty > 0 && i.unexplainedQty > 0 && (
                      <div className="btn-row">
                        <button className="btn sm" onClick={() => setDlg({
                          kind: 'night', itemId: i.itemId, unit: i.unit,
                          crewId: '', building: '1#楼', area: '标准层', process: '', drawingNo: '',
                          qty: i.unexplainedQty, actualTime: '2026-10-02T23:40', remark: ''
                        })}>补录夜料</button>
                        <button className="btn sm ghost" onClick={() => setDlg({
                          kind: 'loss', itemId: i.itemId, unit: i.unit,
                          qty: i.unexplainedQty, remark: ''
                        })}>公共损耗</button>
                      </div>
                    )}
                    {i.supplementedReqCode && <div className="muted" style={{ fontSize: 12 }}>补录单 {i.supplementedReqCode}</div>}
                    {i.commonWasteCode && <div className="muted" style={{ fontSize: 12 }}>损耗单 {i.commonWasteCode}</div>}
                  </td>
                )}
              </tr>
            ))}
            </tbody>
          </table>

          {/* 操作按钮行 */}
          {detail.status === 'COUNTING' && (
            <div className="btn-row" style={{ marginTop: 10 }}>
              {canOperate && <button className="btn" onClick={() => setDlg({
                kind: 'trace', traceType: 'MONITOR', result: 'INFO',
                materialId: '', refCode: '', qty: '', detail: ''
              })}>＋ 登记核查轨迹</button>}
              {isPM && <button className="btn" onClick={() => setDlg({
                kind: 'progress', impact: 'NO', remark: ''
              })}>项目经理确认进度影响</button>}
            </div>
          )}

          {/* 追查轨迹时间线 */}
          <div style={{ margin: '12px 0 6px', fontWeight: 600 }}>追查轨迹（{detail.traces.length}）</div>
          <div className="trace-list">
            {detail.traces.map((t) => (
              <div key={t.id} className={`trace-item ${t.result === 'SUSPECT' ? 'suspect' : ''}`}>
                <div className="trace-hd">
                  <Badge status={t.result === 'MATCHED' ? 'PASSED' : t.result === 'SUSPECT' ? 'FAILED' : 'PENDING'}
                         label={TRACE_TYPE[t.traceType] || t.traceType} />
                  <span className="muted" style={{ fontSize: 12 }}>
                    {TRACE_RESULT[t.result]} · {t.refCode || '—'}{t.qty ? ` · 数量 ${fmt(t.qty)}` : ''} · {t.recorder} · {fmtTime(t.createdTime)}
                  </span>
                </div>
                <div className="trace-bd">{t.detail}</div>
              </div>
            ))}
          </div>
        </Modal>
      )}

      {/* 核查轨迹弹窗 */}
      {dlg?.kind === 'trace' && (
        <Modal title="登记核查轨迹" onClose={() => setDlg(null)}
               footer={<><button className="btn" onClick={() => setDlg(null)}>取消</button>
                 <button className="btn primary" onClick={() => addTrace(dlg)}>保存轨迹</button></>}>
          <div className="grid2">
            <Field label="核查方向">
              <select value={dlg.traceType} onChange={(e) => setDlg({ ...dlg, traceType: e.target.value })}>
                {TRACE_OPTIONS.map((k) => <option key={k} value={k}>{TRACE_TYPE[k]}</option>)}
              </select>
            </Field>
            <Field label="核查结论">
              <select value={dlg.result} onChange={(e) => setDlg({ ...dlg, result: e.target.value })}>
                {Object.entries(TRACE_RESULT).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
            </Field>
            <Field label="关联材料">
              <select value={dlg.materialId} onChange={(e) => setDlg({ ...dlg, materialId: e.target.value })}>
                <option value="">整单核查</option>
                {meta.materials.map((m) => <option key={m.id} value={m.id}>{m.name}</option>)}
              </select>
            </Field>
            <Field label="关联单据号"><input value={dlg.refCode} onChange={(e) => setDlg({ ...dlg, refCode: e.target.value })} placeholder="批次号/单号/监控交接编号" /></Field>
            <Field label="涉及数量"><input type="number" value={dlg.qty} onChange={(e) => setDlg({ ...dlg, qty: e.target.value })} /></Field>
          </div>
          <Field label="核查结论说明">
            <textarea rows={3} value={dlg.detail} onChange={(e) => setDlg({ ...dlg, detail: e.target.value })}
              placeholder="如：调阅 10-02 夜间监控，某班组运出材料无领料单…" />
          </Field>
        </Modal>
      )}

      {/* 公共损耗弹窗 */}
      {dlg?.kind === 'loss' && (
        <Modal title="定性：计入项目公共损耗" onClose={() => setDlg(null)}
               footer={<><button className="btn" onClick={() => setDlg(null)}>取消</button>
                 <button className="btn primary" onClick={() => resolveCommon(dlg)}>确认公共损耗并核库存</button></>}>
          <div className="kpi-note" style={{ marginBottom: 8 }}>
            适用于入库/领用/退库/破损/监控交接核查后仍无法定位责任的差异；生成项目承担损耗单，不计入班组扣款。
          </div>
          <Field label={`核销数量（${dlg.unit}，不超过未解释盘亏）`}>
            <input type="number" value={dlg.qty} onChange={(e) => setDlg({ ...dlg, qty: e.target.value })} />
          </Field>
          <Field label="说明"><input value={dlg.remark} onChange={(e) => setDlg({ ...dlg, remark: e.target.value })} /></Field>
        </Modal>
      )}

      {/* 夜料补录弹窗 */}
      {dlg?.kind === 'night' && (
        <Modal title="定性：班组未登记夜间领料 · 补录" wide onClose={() => setDlg(null)}
               footer={<><button className="btn" onClick={() => setDlg(null)}>取消</button>
                 <button className="btn primary" onClick={() => supplement(dlg)}>补录领料并加严该班组审批</button></>}>
          <div className="kpi-note" style={{ marginBottom: 8 }}>
            依据监控交接/门卫记录补录夜间领料：按批次单价计入该班组当月成本；补录后该班组后续领料单一律转项目经理逐单审批。
          </div>
          <div className="grid3">
            <Field label="责任班组 *">
              <select value={dlg.crewId} onChange={(e) => setDlg({ ...dlg, crewId: e.target.value })}>
                <option value="">请选择</option>
                {meta.crews.map((c) => <option key={c.id} value={c.id}>{c.name}（{c.leader}）</option>)}
              </select>
            </Field>
            <Field label="楼栋 *"><input value={dlg.building} onChange={(e) => setDlg({ ...dlg, building: e.target.value })} /></Field>
            <Field label="施工区域"><input value={dlg.area} onChange={(e) => setDlg({ ...dlg, area: e.target.value })} /></Field>
            <Field label="工序 *"><input value={dlg.process} onChange={(e) => setDlg({ ...dlg, process: e.target.value })} placeholder="墙地砖铺贴" /></Field>
            <Field label="图纸编号"><input value={dlg.drawingNo} onChange={(e) => setDlg({ ...dlg, drawingNo: e.target.value })} /></Field>
            <Field label={`补录数量（${dlg.unit}）*`}><input type="number" value={dlg.qty} onChange={(e) => setDlg({ ...dlg, qty: e.target.value })} /></Field>
            <Field label="夜间实际领料时间 *">
              <input type="datetime-local" value={dlg.actualTime} onChange={(e) => setDlg({ ...dlg, actualTime: e.target.value })} />
            </Field>
          </div>
          <Field label="核查依据/备注">
            <textarea rows={2} value={dlg.remark} onChange={(e) => setDlg({ ...dlg, remark: e.target.value })}
              placeholder="监控时段、门卫交接签字、班组确认情况" />
          </Field>
        </Modal>
      )}

      {/* PM 进度确认弹窗 */}
      {dlg?.kind === 'progress' && (
        <Modal title="项目经理确认：差异对现场施工进度的影响" onClose={() => setDlg(null)}
               footer={<><button className="btn" onClick={() => setDlg(null)}>取消</button>
                 <button className="btn primary" onClick={() => confirmProgress(dlg)}>提交确认</button></>}>
          <Field label="是否影响现场施工进度">
            <select value={dlg.impact} onChange={(e) => setDlg({ ...dlg, impact: e.target.value })}>
              <option value="NO">不影响（库存可满足后续施工）</option>
              <option value="YES">影响（需补货/调整工序衔接）</option>
            </select>
          </Field>
          <Field label="进度处置说明">
            <textarea rows={3} value={dlg.remark} onChange={(e) => setDlg({ ...dlg, remark: e.target.value })}
              placeholder="如：影响 1#楼标准层瓷砖铺贴 2 天，已通知预算员追加采购并调整工序穿插" />
          </Field>
        </Modal>
      )}
    </Card>
  )
}
