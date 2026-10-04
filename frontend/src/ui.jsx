import React, { createContext, useContext, useEffect, useState, useCallback } from 'react'
import { api } from './api.js'

/* ---------------- 常量标签 ---------------- */
export const ROLE_LABEL = {
  ROLE_PM: '项目经理',
  ROLE_KEEPER: '仓管/材料员',
  ROLE_BUDGET: '预算员',
  ROLE_FINANCE: '财务',
  ROLE_FOREMAN: '班组长'
}

export const STATUS_LABEL = {
  DRAFT: '草稿', SUBMITTED: '待处理', ISSUED: '已发料', REJECTED: '已驳回',
  CONFIRMED: '已确认', CLAIMED: '已索赔', COUNTING: '追查中', ADJUSTED: '已调账',
  CLOSED: '已关闭', PENDING_PM: '待PM审批'
}
export const STATUS_TONE = {
  DRAFT: 'gray', SUBMITTED: 'amber', ISSUED: 'green', REJECTED: 'red',
  CONFIRMED: 'green', CLAIMED: 'blue', COUNTING: 'amber', ADJUSTED: 'blue',
  CLOSED: 'blue', PENDING_PM: 'red',
  PASSED: 'green', PENDING: 'amber', FAILED: 'red', CONDITIONAL: 'blue'
}

export const RESOLUTION_LABEL = {
  OPEN: '未定性', SUPPLEMENTED: '已补录夜料', COMMON_LOSS: '公共损耗', MIXED: '部分补录+部分公共损耗'
}
export const CONCLUSION_LABEL = {
  COMMON_LOSS: '项目公共损耗', CREW_NIGHT: '班组夜料补录', MIXED: '混合定性'
}
export const TRACE_TYPE = {
  INBOUND: '入库核对', REQUISITION: '领用核对', RETURN: '退库核对',
  WASTE: '破损登记核对', MONITOR: '监控交接',
  COMMON_LOSS: '公共损耗定性', SUPPLEMENT: '夜料补录', PROGRESS: '进度确认', CLOSE: '关闭盘点'
}
export const TRACE_RESULT = {
  MATCHED: '核对相符', SUSPECT: '发现疑点', CONFIRMED: '确认', INFO: '说明'
}

export const QC_LABEL = { PENDING: '待检', PASSED: '合格', FAILED: '不合格', CONDITIONAL: '让步接收' }
export const WASTE_TYPE = {
  BREAKAGE: '瓷砖/材料破损', EXPIRED: '砂浆过期', STORAGE: '保管不当',
  CONSTRUCTION: '施工错误', QUALITY: '供应商质量', OTHER: '其他'
}
export const RESPONSIBILITY = {
  CREW: '班组责任', SUPPLIER: '供应商责任', DESIGN: '设计变更', COMPANY: '项目承担', NATURE: '不可抗力'
}
export const RETURN_REASON = {
  SPEC_WRONG: '规格领错', SURPLUS: '临时追加多余', CHANGE: '施工变更', OTHER: '其他'
}
export const CHANGE_TYPE = { DESIGN: '设计变更', CONSTRUCTION: '施工变更', OWNER: '业主要求' }
export const CONDITION = { GOOD: '完好可再发', DAMAGED: '损坏(班组责任)' }
export const CAUSE_LABEL = {
  DESIGN: '设计变更', CONSTRUCTION: '施工错误', STORAGE: '保管不当', QUALITY: '供应商质量', OTHER: '其他'
}

export const fmt = (v, d = 2) =>
  v === null || v === undefined || v === '' ? '—'
    : Number(v).toLocaleString('zh-CN', { minimumFractionDigits: d, maximumFractionDigits: d })
export const fmt0 = (v) => fmt(v, 0)
export const fmtTime = (t) => t ? t.replace('T', ' ').slice(0, 16) : '—'

/* ---------------- Toast ---------------- */
const ToastCtx = createContext(null)
export function ToastProvider({ children }) {
  const [items, setItems] = useState([])
  const push = useCallback((message, type = 'ok') => {
    const id = Math.random().toString(36).slice(2)
    setItems((xs) => [...xs, { id, message, type }])
    setTimeout(() => setItems((xs) => xs.filter((x) => x.id !== id)), 3200)
  }, [])
  return (
    <ToastCtx.Provider value={push}>
      {children}
      <div className="toast-wrap">
        {items.map((t) => <div key={t.id} className={`toast ${t.type}`}>{t.message}</div>)}
      </div>
    </ToastCtx.Provider>
  )
}
export const useToast = () => useContext(ToastCtx)

/* ---------------- 基础组件 ---------------- */
export function Card({ title, hint, actions, children, flush }) {
  return (
    <div className="card">
      {(title || actions) && (
        <div className="card-hd">
          <div>
            {title && <h3>{title}</h3>}
            {hint && <div className="hint">{hint}</div>}
          </div>
          {actions}
        </div>
      )}
      <div className={`card-bd${flush ? ' flush' : ''}`}>{children}</div>
    </div>
  )
}

export function Stats({ items }) {
  return (
    <div className="stats">
      {items.map((s, i) => (
        <div key={i} className={`stat ${s.tone ? 'tone-' + s.tone : ''}`}>
          <div className="lab">{s.label}</div>
          <div className="val">{s.value}</div>
          {s.sub && <div className="sub">{s.sub}</div>}
        </div>
      ))}
    </div>
  )
}

export function Badge({ status, label, tone }) {
  const t = tone || STATUS_TONE[status] || 'gray'
  const text = label || STATUS_LABEL[status] || status
  return <span className={`badge ${t}`}>{text}</span>
}

export function Modal({ title, wide, onClose, children, footer }) {
  return (
    <div className="modal-mask" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose() }}>
      <div className={`modal${wide ? ' wide' : ''}`}>
        <div className="modal-hd">
          <h3>{title}</h3>
          <button className="x" onClick={onClose}>✕</button>
        </div>
        <div className="modal-bd">{children}</div>
        {footer && <div className="modal-ft">{footer}</div>}
      </div>
    </div>
  )
}

export function Field({ label, children }) {
  return (
    <label className="fld">
      <span style={{ display: 'block', fontSize: 12.5, color: 'var(--ink-2)', marginBottom: 5 }}>{label}</span>
      {children}
    </label>
  )
}

export function Empty() {
  return <div className="empty">暂无数据</div>
}

/* ---------------- 元数据上下文 ---------------- */
const MetaCtx = createContext(null)
export function MetaProvider({ children }) {
  const [meta, setMeta] = useState({ materials: [], suppliers: [], crews: [], buildings: [], processes: [] })
  const load = useCallback(() => {
    api.get('/api/meta/options').then(setMeta).catch(() => {})
  }, [])
  useEffect(load, [load])
  return <MetaCtx.Provider value={{ meta, reload: load }}>{children}</MetaCtx.Provider>
}
export const useMeta = () => useContext(MetaCtx)

export const matName = (meta, id) => meta.materials.find((m) => m.id === id)?.name || `#${id}`
export const matUnit = (meta, id) => meta.materials.find((m) => m.id === id)?.unit || ''
export const crewName = (meta, id) => meta.crews.find((c) => c.id === id)?.name || '—'
export const supplierName = (meta, id) => meta.suppliers.find((s) => s.id === id)?.name || '—'
