import React, { useEffect, useState } from 'react'
import { api, getUser } from '../api.js'
import { Card, Field, Badge, useToast, useMeta } from '../ui.jsx'

export default function MasterData() {
  const toast = useToast()
  const { meta, reload } = useMeta()
  const user = getUser()
  const [tab, setTab] = useState('material')
  const [mForm, setMForm] = useState({ name: '', category: '钢筋辅材', spec: '', unit: '', safetyStock: 0 })
  const [sForm, setSForm] = useState({ name: '', contact: '', phone: '' })
  const [cForm, setCForm] = useState({ name: '', leader: '', trade: '' })

  const cats = ['钢筋辅材', '防水卷材', '瓷砖', '线管', '砂浆', '五金', '安全防护用品']

  const addMaterial = async () => {
    if (!mForm.name || !mForm.unit) return toast('请填写名称与单位', 'err')
    try {
      await api.post('/api/materials', { ...mForm, safetyStock: Number(mForm.safetyStock) || 0 })
      toast('材料已新增'); setMForm({ name: '', category: '钢筋辅材', spec: '', unit: '', safetyStock: 0 }); reload()
    } catch (e) { toast(e.message, 'err') }
  }
  const addSupplier = async () => {
    if (!sForm.name) return toast('请填写供应商名称', 'err')
    try {
      await api.post('/api/suppliers', sForm)
      toast('供应商已新增'); setSForm({ name: '', contact: '', phone: '' }); reload()
    } catch (e) { toast(e.message, 'err') }
  }
  const addCrew = async () => {
    if (!cForm.name) return toast('请填写班组名称', 'err')
    try {
      await api.post('/api/crews', cForm)
      toast('班组已新增'); setCForm({ name: '', leader: '', trade: '' }); reload()
    } catch (e) { toast(e.message, 'err') }
  }
  const releaseRestriction = async (c) => {
    if (!window.confirm(`确认解除「${c.name}」的领料加严限制？解除后该班组领料恢复正常流程。`)) return
    try {
      await api.put(`/api/crews/${c.id}/release-restriction`, {})
      toast('已解除该班组的领料限制'); reload()
    } catch (e) { toast(e.message, 'err') }
  }

  return (
    <div>
      <div className="btn-row" style={{ marginBottom: 14 }}>
        {[['material', '材料目录'], ['supplier', '供应商'], ['crew', '班组']].map(([k, l]) => (
          <button key={k} className={`btn ${tab === k ? 'primary' : ''}`} onClick={() => setTab(k)}>{l}</button>
        ))}
      </div>

      {tab === 'material' && (
        <div className="grid2">
          <Card title="新增材料" hint="覆盖钢筋辅材/防水卷材/瓷砖/线管/砂浆/五金/安全防护用品">
            <div className="grid2">
              <Field label="材料名称"><input value={mForm.name} onChange={(e) => setMForm({ ...mForm, name: e.target.value })} /></Field>
              <Field label="类别">
                <select value={mForm.category} onChange={(e) => setMForm({ ...mForm, category: e.target.value })}>
                  {cats.map((c) => <option key={c}>{c}</option>)}
                </select>
              </Field>
              <Field label="规格型号"><input value={mForm.spec} onChange={(e) => setMForm({ ...mForm, spec: e.target.value })} /></Field>
              <Field label="计量单位"><input value={mForm.unit} onChange={(e) => setMForm({ ...mForm, unit: e.target.value })} placeholder="吨/卷/㎡/箱…" /></Field>
              <Field label="安全库存下限"><input type="number" value={mForm.safetyStock}
                onChange={(e) => setMForm({ ...mForm, safetyStock: e.target.value })} /></Field>
            </div>
            <button className="btn primary" onClick={addMaterial}>新增材料</button>
          </Card>
          <Card title={`材料目录（${meta.materials.length}）`} flush>
            <div className="table-scroll">
              <table className="tbl">
                <thead><tr><th>名称</th><th>类别</th><th>规格</th><th>单位</th><th className="num">安全库存</th></tr></thead>
                <tbody>{meta.materials.map((m) => (
                  <tr key={m.id}><td><b>{m.name}</b></td><td>{m.category}</td><td>{m.spec}</td><td>{m.unit}</td><td className="num">{m.safetyStock}</td></tr>
                ))}</tbody>
              </table>
            </div>
          </Card>
        </div>
      )}

      {tab === 'supplier' && (
        <div className="grid2">
          <Card title="新增供应商">
            <Field label="供应商名称"><input value={sForm.name} onChange={(e) => setSForm({ ...sForm, name: e.target.value })} /></Field>
            <Field label="联系人"><input value={sForm.contact} onChange={(e) => setSForm({ ...sForm, contact: e.target.value })} /></Field>
            <Field label="电话"><input value={sForm.phone} onChange={(e) => setSForm({ ...sForm, phone: e.target.value })} /></Field>
            <button className="btn primary" onClick={addSupplier}>新增供应商</button>
          </Card>
          <Card title={`供应商（${meta.suppliers.length}）`} flush>
            <div className="table-scroll">
              <table className="tbl">
                <thead><tr><th>名称</th><th>联系人</th><th>电话</th></tr></thead>
                <tbody>{meta.suppliers.map((s) => (
                  <tr key={s.id}><td><b>{s.name}</b></td><td>{s.contact}</td><td>{s.phone}</td></tr>
                ))}</tbody>
              </table>
            </div>
          </Card>
        </div>
      )}

      {tab === 'crew' && (
        <div className="grid2">
          <Card title="新增班组">
            <Field label="班组名称"><input value={cForm.name} onChange={(e) => setCForm({ ...cForm, name: e.target.value })} placeholder="如 钢筋班" /></Field>
            <Field label="班组长"><input value={cForm.leader} onChange={(e) => setCForm({ ...cForm, leader: e.target.value })} /></Field>
            <Field label="工种"><input value={cForm.trade} onChange={(e) => setCForm({ ...cForm, trade: e.target.value })} /></Field>
            <button className="btn primary" onClick={addCrew}>新增班组</button>
          </Card>
          <Card title={`班组（${meta.crews.length}）`} flush>
            <div className="table-scroll">
              <table className="tbl">
                <thead><tr><th>班组</th><th>班组长</th><th>工种</th><th>领料权限</th></tr></thead>
                <tbody>{meta.crews.map((c) => (
                  <tr key={c.id}>
                    <td><b>{c.name}</b></td>
                    <td>{c.leader}</td>
                    <td>{c.trade}</td>
                    <td>
                      {c.requisitionRestricted
                        ? <div>
                            <Badge status="PENDING_PM" label="加严审批（PM逐单批准）" />
                            <div className="muted" style={{ fontSize: 12 }}>{c.restrictReason}</div>
                            {user.role === 'ROLE_PM' && (
                              <button className="btn sm ghost" style={{ marginTop: 4 }} onClick={() => releaseRestriction(c)}>解除限制</button>
                            )}
                          </div>
                        : <Badge status="PASSED" label="正常" />}
                    </td>
                  </tr>
                ))}</tbody>
              </table>
            </div>
          </Card>
        </div>
      )}
    </div>
  )
}
