import React, { useEffect, useMemo, useState } from 'react'
import { api, getToken, getUser, setSession, clearSession } from './api.js'
import { ToastProvider, useToast, ROLE_LABEL, MetaProvider } from './ui.jsx'

import Dashboard from './pages/Dashboard.jsx'
import InventoryPage from './pages/Inventory.jsx'
import BatchesPage from './pages/Batches.jsx'
import StockChecksPage from './pages/StockChecks.jsx'
import BudgetsPage from './pages/Budgets.jsx'
import RequisitionsPage from './pages/Requisitions.jsx'
import ReturnsPage from './pages/Returns.jsx'
import WastesPage from './pages/Wastes.jsx'
import VisasPage from './pages/Visas.jsx'
import SettlementsPage from './pages/Settlements.jsx'
import ConsumptionPage from './pages/Consumption.jsx'
import WasteReviewPage from './pages/WasteReview.jsx'
import ProcurementPage from './pages/Procurement.jsx'
import FinancePage from './pages/Finance.jsx'
import MasterDataPage from './pages/MasterData.jsx'

const NAV = [
  { group: '总览' },
  { path: '/dashboard', label: '工作台', icon: '🏗️', roles: ['*'], el: <Dashboard /> },
  { group: '仓储管理' },
  { path: '/inventory', label: '现场库存总览', icon: '📦', roles: ['*'], el: <InventoryPage /> },
  { path: '/batches', label: '入库批次与质检', icon: '🧾', roles: ['ROLE_PM', 'ROLE_KEEPER'], el: <BatchesPage /> },
  { path: '/stock-checks', label: '盘点差异追查', icon: '🔍', roles: ['ROLE_PM', 'ROLE_KEEPER'], el: <StockChecksPage /> },
  { path: '/master', label: '材料/供应商/班组', icon: '🗂️', roles: ['ROLE_PM', 'ROLE_KEEPER'], el: <MasterDataPage /> },
  { group: '业务单据' },
  { path: '/requisitions', label: '领料单', icon: '📝', roles: ['*'], el: <RequisitionsPage /> },
  { path: '/returns', label: '退库单', icon: '↩️', roles: ['*'], el: <ReturnsPage /> },
  { path: '/wastes', label: '损耗登记', icon: '⚠️', roles: ['ROLE_PM', 'ROLE_KEEPER', 'ROLE_FINANCE'], el: <WastesPage /> },
  { path: '/visas', label: '变更签证', icon: '🛠️', roles: ['ROLE_PM', 'ROLE_BUDGET', 'ROLE_KEEPER', 'ROLE_FINANCE'], el: <VisasPage /> },
  { path: '/budgets', label: '工程预算', icon: '📐', roles: ['ROLE_PM', 'ROLE_BUDGET', 'ROLE_KEEPER'], el: <BudgetsPage /> },
  { group: '核算与分析' },
  { path: '/consumption', label: '楼栋消耗与偏差', icon: '📊', roles: ['*'], el: <ConsumptionPage /> },
  { path: '/settlements', label: '班组月度核算', icon: '💰', roles: ['*'], el: <SettlementsPage /> },
  { path: '/waste-review', label: '损耗月底复盘', icon: '🔎', roles: ['ROLE_PM', 'ROLE_KEEPER', 'ROLE_FINANCE'], el: <WasteReviewPage /> },
  { path: '/procurement', label: '采购计划反推', icon: '🛒', roles: ['ROLE_PM', 'ROLE_BUDGET'], el: <ProcurementPage /> },
  { path: '/finance', label: '财务结算与索赔', icon: '🏦', roles: ['ROLE_PM', 'ROLE_FINANCE'], el: <FinancePage /> }
]

function useHashRoute() {
  const [hash, setHash] = useState(window.location.hash || '#/dashboard')
  useEffect(() => {
    const onChange = () => setHash(window.location.hash || '#/dashboard')
    window.addEventListener('hashchange', onChange)
    return () => window.removeEventListener('hashchange', onChange)
  }, [])
  return hash.replace(/^#/, '')
}

function Login() {
  const toast = useToast()
  const [username, setUsername] = useState('pm')
  const [password, setPassword] = useState('pm123')
  const [loading, setLoading] = useState(false)

  const submit = async (u, p) => {
    setLoading(true)
    try {
      const data = await api.post('/api/auth/login', { username: u, password: p })
      setSession(data.token, {
        username: data.username, displayName: data.displayName, role: data.role, crewId: data.crewId
      })
      window.location.hash = '#/dashboard'
      window.location.reload()
    } catch (e) {
      toast(e.message, 'err')
    } finally {
      setLoading(false)
    }
  }

  const accounts = [
    ['pm', 'pm123', '项目经理 张项目'],
    ['keeper', 'keeper123', '仓管/材料员 周材料'],
    ['budget', 'budget123', '预算员 吴预算'],
    ['finance', 'finance123', '财务 郑财务'],
    ['foreman1', 'foreman123', '钢筋班班组长 王强'],
    ['foreman5', 'foreman123', '水电班班组长 孙明']
  ]

  return (
    <div className="login-wrap">
      <div className="login-card">
        <div className="login-logo">材</div>
        <h1>工地临时材料领用退库损耗与班组核算平台</h1>
        <div className="sub">钢筋辅材 · 防水卷材 · 瓷砖 · 线管 · 砂浆 · 五金 · 安全防护用品 全链路管理</div>
        <form onSubmit={(e) => { e.preventDefault(); submit(username, password) }}>
          <label className="fld">
            <span style={{ display: 'block', fontSize: 12.5, color: '#5b6675', marginBottom: 5 }}>用户名</span>
            <input value={username} onChange={(e) => setUsername(e.target.value)} placeholder="如 pm" />
          </label>
          <label className="fld">
            <span style={{ display: 'block', fontSize: 12.5, color: '#5b6675', marginBottom: 5 }}>密码</span>
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
          </label>
          <button className="btn primary" style={{ width: '100%', justifyContent: 'center', padding: '10px' }} disabled={loading}>
            {loading ? '登录中…' : '登 录'}
          </button>
        </form>
        <div className="login-accounts">
          <div className="muted" style={{ fontSize: 12, marginBottom: 6 }}>演示账号（点击快速填充）</div>
          {accounts.map(([u, p, label]) => (
            <div key={u} className="acc" onClick={() => { setUsername(u); setPassword(p) }}>
              <b>{label}</b>
              <span>{u} / {p}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}

function Shell({ user }) {
  const route = useHashRoute()
  const allowed = (item) => item.roles.includes('*') || item.roles.includes(user.role)
  const current = NAV.find((n) => n.path && route.startsWith(n.path))
  const title = current?.label || '工作台'

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          <div className="logo">材</div>
          <div className="t">
            <b>材料核算平台</b>
            <span>领用 · 退库 · 损耗 · 核算</span>
          </div>
        </div>
        <nav className="nav">
          {NAV.filter((n) => n.group || allowed(n)).map((n, i) =>
            n.group ? (
              <div key={i} className="group">{n.group}</div>
            ) : (
              <a key={n.path} className={route.startsWith(n.path) ? 'active' : ''}
                 onClick={() => { window.location.hash = '#' + n.path }}>
                <span>{n.icon}</span>{n.label}
              </a>
            )
          )}
        </nav>
        <div className="me">
          <div className="who">
            <b>{user.displayName}</b>
            <span>{ROLE_LABEL[user.role]}{user.crewId ? ` · 班组#${user.crewId}` : ''}</span>
          </div>
          <button onClick={() => { clearSession(); window.location.hash = '#/dashboard'; window.location.reload() }}>
            退出
          </button>
        </div>
      </aside>
      <div className="main">
        <div className="topbar">
          <div>
            <h2>{title}</h2>
            <div className="crumb">
              {ROLE_LABEL[user.role]}视图 · 数据日期 2026-10（演示库，重启后重置）
            </div>
          </div>
        </div>
        <div className="content">
          {current?.el || <Dashboard />}
        </div>
      </div>
    </div>
  )
}

export default function App() {
  const token = getToken()
  const user = getUser()
  const authed = Boolean(token && user)
  return (
    <ToastProvider>
      <MetaProvider>
        {authed ? <Shell user={user} /> : <Login />}
      </MetaProvider>
    </ToastProvider>
  )
}
