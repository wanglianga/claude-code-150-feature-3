import React, { useEffect, useState } from 'react'
import { api } from '../api.js'
import { Card, Stats, Badge, fmt, useMeta, ROLE_LABEL } from '../ui.jsx'
import { getUser } from '../api.js'

export default function Dashboard() {
  const user = getUser()
  const { meta } = useMeta()
  const [inv, setInv] = useState(null)
  const [pending, setPending] = useState([])
  const [finance, setFinance] = useState(null)

  useEffect(() => {
    api.get('/api/inventory').then(setInv).catch(() => {})
    api.get('/api/requisitions?status=SUBMITTED').then(setPending).catch(() => {})
    if (user.role === 'ROLE_FINANCE' || user.role === 'ROLE_PM') {
      api.get('/api/analytics/finance').then(setFinance).catch(() => {})
    }
  }, [])

  const go = (p) => { window.location.hash = '#' + p }

  return (
    <div>
      <Stats items={[
        { label: '在库材料种类', value: inv?.rows?.length ?? '—', sub: '七大类主材与劳保', tone: 'brand' },
        { label: '库存总价值（元）', value: fmt(inv?.totalStockValue), sub: '按批次单价×现场库存', tone: 'blue' },
        { label: '待发料领料单', value: pending.length, sub: '含夜间紧急领料单', tone: pending.length ? 'red' : 'green' },
        { label: '库存预警 / 待质检', value: `${inv?.lowStockCount ?? 0} / ${inv?.pendingQcCount ?? 0}`,
          sub: '低于安全库存 / 质检待处理', tone: inv?.lowStockCount ? 'red' : 'green' }
      ]} />

      <Card title="同一条材料链路"
            hint="从采购入库到班组核算，所有单据通过楼栋/工序/班组/材料串接">
        <div className="flow-steps" style={{ fontSize: 13 }}>
          {['采购入库登记', '批次质检', '班组申请领料', '仓管核对发料', '班长确认领用',
            '退库/损耗/签证', '盘点差异追查', '月底班组核算'].map((s, i, arr) => (
            <React.Fragment key={s}>
              <span className={`step ${i === 2 || i === 5 ? 'on' : ''}`}>{s}</span>
              {i < arr.length - 1 && <span className="arr">→</span>}
            </React.Fragment>
          ))}
        </div>
        <div className="kpi-note" style={{ marginTop: 14 }}>
          <div>· <b>仓管发料</b>时核对：施工进度（楼栋/工序/图纸）、预算量、已领量、现场库存与批次质检状态。</div>
          <div>· <b>异常全部留痕</b>：临时追加、规格领错、施工变更退库、瓷砖破损、砂浆过期、夜间紧急领料、供应商补货延迟、盘点账实不符。</div>
          <div>· <b>月底核算</b>：按 预算 / 实际领用 / 退库 / 损耗 / 责任归属 生成班组材料成本、扣款与变更索赔。</div>
        </div>
      </Card>

      <div className="grid2">
        <Card title="待处理领料单" hint="仓管核对预算与库存后发料"
              actions={<button className="btn sm" onClick={() => go('/requisitions')}>去处理</button>}>
          {pending.length === 0 && <div className="empty">暂无待发料单据</div>}
          {pending.map((r) => (
            <div key={r.id} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center',
              padding: '9px 0', borderBottom: '1px solid var(--line)' }}>
              <div>
                <b>{r.code}</b>
                {r.urgent && <span className="tag-mini" style={{ marginLeft: 8 }}>夜间紧急</span>}
                <div className="muted" style={{ fontSize: 12.5, marginTop: 2 }}>
                  {r.crewName} · {r.building} {r.area} · {r.process} · {r.items.length} 项材料
                </div>
              </div>
              <button className="btn sm primary" onClick={() => go('/requisitions')}>核对发料</button>
            </div>
          ))}
        </Card>

        <Card title={`${ROLE_LABEL[user.role]}工作台`} hint="按角色关注的核心指标">
          <div className="kpi-note">
            {user.role === 'ROLE_PM' && <>
              <div>项目经理关注：某楼栋/工序的<b>真实材料消耗</b>与预算偏差。</div>
              <div className="btn-row" style={{ marginTop: 10 }}>
                <button className="btn" onClick={() => go('/consumption')}>楼栋消耗与偏差</button>
                <button className="btn" onClick={() => go('/settlements')}>班组月度核算</button>
              </div>
            </>}
            {user.role === 'ROLE_KEEPER' && <>
              <div>材料员关注：入库质检、发料核对、退库入库、损耗核销、盘点账实与<b>月底损耗复盘</b>。</div>
              <div className="btn-row" style={{ marginTop: 10 }}>
                <button className="btn" onClick={() => go('/batches')}>入库与质检</button>
                <button className="btn" onClick={() => go('/stock-checks')}>差异追查</button>
                <button className="btn" onClick={() => go('/waste-review')}>损耗复盘</button>
              </div>
            </>}
            {user.role === 'ROLE_BUDGET' && <>
              <div>预算员关注：用实际消耗<b>反推后续采购计划</b>与分包结算量。</div>
              <div className="btn-row" style={{ marginTop: 10 }}>
                <button className="btn" onClick={() => go('/procurement')}>采购计划反推</button>
                <button className="btn" onClick={() => go('/budgets')}>工程预算</button>
              </div>
            </>}
            {user.role === 'ROLE_FINANCE' && <>
              <div>财务关注：供应商结算、班组扣款、变更索赔。</div>
              {finance && <div style={{ marginTop: 8 }}>
                应付供应商 <b style={{ color: 'var(--brand-dark)' }}>{fmt(finance.supplierTotalPayable)} 元</b>，
                班组扣款 <b style={{ color: 'var(--red)' }}>{fmt(finance.crewTotalDeduction)} 元</b>，
                变更索赔 <b style={{ color: 'var(--blue)' }}>{fmt(finance.visaTotalClaim)} 元</b>。
              </div>}
              <div className="btn-row" style={{ marginTop: 10 }}>
                <button className="btn" onClick={() => go('/finance')}>财务结算与索赔</button>
                <button className="btn" onClick={() => go('/settlements')}>月度核算确认</button>
              </div>
            </>}
            {user.role === 'ROLE_FOREMAN' && <>
              <div>班组长可提交领料申请（含夜间紧急）、办理退库，并查看本班组材料成本。</div>
              <div className="btn-row" style={{ marginTop: 10 }}>
                <button className="btn primary" onClick={() => go('/requisitions')}>申请领料</button>
                <button className="btn" onClick={() => go('/returns')}>办理退库</button>
                <button className="btn" onClick={() => go('/settlements')}>本班组核算</button>
              </div>
            </>}
          </div>
        </Card>
      </div>

      <Card title="库存预警与质检提醒" flush>
        <div className="table-scroll">
          <table className="tbl">
            <thead><tr>
              <th>材料</th><th>类别</th><th className="num">可用库存</th>
              <th className="num">安全库存</th><th className="num">库存价值</th>
              <th>状态</th>
            </tr></thead>
            <tbody>
            {(inv?.rows || []).filter((r) => r.lowStock || r.pendingQc > 0).map((r) => (
              <tr key={r.materialId}>
                <td><b>{r.materialName}</b><div className="muted" style={{ fontSize: 12 }}>{r.spec}</div></td>
                <td>{r.category}</td>
                <td className="num">{fmt(r.usableQty)} {r.unit}</td>
                <td className="num">{fmt(r.safetyStock)} {r.unit}</td>
                <td className="num">{fmt(r.stockValue)}</td>
                <td>
                  {r.lowStock && <Badge status="PENDING" label="库存预警" tone="red" />}
                  {r.pendingQc > 0 && <span style={{ marginLeft: 6 }}><Badge status="PENDING" label={`待质检 ${r.pendingQc}`} /></span>}
                </td>
              </tr>
            ))}
            {inv && inv.rows.every((r) => !r.lowStock && r.pendingQc === 0) && (
              <tr><td colSpan={6}><div className="empty">库存充足，无待质检批次</div></td></tr>
            )}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  )
}
