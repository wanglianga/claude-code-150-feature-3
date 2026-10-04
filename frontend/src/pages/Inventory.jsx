import React, { useEffect, useMemo, useState } from 'react'
import { api } from '../api.js'
import { Card, Stats, Badge, fmt } from '../ui.jsx'

export default function Inventory() {
  const [data, setData] = useState(null)
  const [cat, setCat] = useState('')
  const [kw, setKw] = useState('')

  const load = () => api.get('/api/inventory').then(setData)
  useEffect(() => { load() }, [])

  const rows = useMemo(() => (data?.rows || []).filter((r) =>
    (!cat || r.category === cat) && (!kw || r.materialName.includes(kw))
  ), [data, cat, kw])

  const cats = useMemo(() => [...new Set((data?.rows || []).map((r) => r.category))], [data])
  const maxVal = Math.max(1, ...(data?.rows || []).map((r) => r.stockValue))

  return (
    <div>
      <Stats items={[
        { label: '库存总价值', value: fmt(data?.totalStockValue), tone: 'brand' },
        { label: '库存预警材料', value: data?.lowStockCount ?? '—', tone: data?.lowStockCount ? 'red' : 'green' },
        { label: '盘点冻结材料', value: data?.frozenCount ?? '—', tone: data?.frozenCount ? 'red' : 'green' },
        { label: '待质检批次', value: data?.pendingQcCount ?? '—', tone: data?.pendingQcCount ? 'red' : 'green' }
      ]} />

      <Card title="现场库存总览" hint="库存=入库−已领+退库−损耗+盘点调整；不合格批次不计入可用量；盘点追查中的材料冻结领用"
            actions={
              <div className="filters">
                <div className="f">
                  <label>类别</label>
                  <select value={cat} onChange={(e) => setCat(e.target.value)}>
                    <option value="">全部</option>
                    {cats.map((c) => <option key={c}>{c}</option>)}
                  </select>
                </div>
                <div className="f">
                  <label>材料名称</label>
                  <input value={kw} onChange={(e) => setKw(e.target.value)} placeholder="搜索" />
                </div>
                <button className="btn" onClick={load}>刷新</button>
              </div>
            } flush>
        <div className="table-scroll">
          <table className="tbl">
            <thead><tr>
              <th>材料 / 规格</th><th>类别</th>
              <th className="num">账面库存</th><th className="num">可用库存</th>
              <th className="num">安全库存</th><th className="num">批次数</th>
              <th>库存价值占比</th><th className="num">库存价值(元)</th><th>状态</th>
            </tr></thead>
            <tbody>
            {rows.map((r) => (
              <tr key={r.materialId} className={r.frozenByCheck ? 'row-frozen' : ''}>
                <td><b>{r.materialName}</b>
                  {r.frozenByCheck && <div style={{ marginTop: 2 }}><Badge status="PENDING" label={`盘点冻结·${r.frozenByCheck}`} tone="red" /></div>}
                </td>
                <td>{r.category}</td>
                <td className="num">{fmt(r.onHand)} {r.unit}</td>
                <td className="num"><b>{fmt(r.usableQty)}</b> {r.unit}</td>
                <td className="num">{fmt(r.safetyStock)}</td>
                <td className="num">{r.batchCount}{r.pendingQc > 0 && <span className="tag-mini" style={{ marginLeft: 6 }}>待检{r.pendingQc}</span>}</td>
                <td style={{ minWidth: 130 }}>
                  <div className={r.lowStock ? 'bar red' : 'bar'}><i style={{ width: `${Math.max(2, r.stockValue / maxVal * 100)}%` }} /></div>
                </td>
                <td className="num">{fmt(r.stockValue)}</td>
                <td>{r.frozenByCheck
                  ? <Badge status="FAILED" label="暂停领用" />
                  : r.lowStock
                    ? <Badge status="PENDING" label="库存预警" tone="red" />
                    : <Badge status="PASSED" label="正常" />}</td>
              </tr>
            ))}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  )
}
