"use client"

import { useEffect, useState, Fragment, useMemo } from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Checkbox } from "@/components/ui/checkbox"
import { Label } from "@/components/ui/label"
import { BiChevronDown, BiChevronUp, BiLinkExternal, BiTrash, BiSend, BiCheckCircle, BiFilterAlt } from "react-icons/bi"

type JobWorkspaceItem = {
  id: number
  platform: string
  externalId: string
  jobUrl: string
  jobName: string
  companyName: string
  salary: string
  location: string
  jdText: string
  jdFingerprint: string
  aiGap: string
  aiPlan: string
  relevanceScore?: number
  relevanceReason?: string
  deliveryStatus: number // 0:待处理, 1:已投递, 2:已忽略
  analysisStatus: string // PENDING / PROCESSING / DONE / FAILED
  analysisError?: string
  createdAt: string
  updatedAt: string
}

const API_BASE = "http://localhost:8888"

export default function JobWorkspaceTable({ platform }: { platform?: string }) {
  const [items, setItems] = useState<JobWorkspaceItem[]>([])
  const [loading, setLoading] = useState(true)
  const [expandedIds, setExpandedIds] = useState<Set<number>>(new Set())
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set())
  const [showAll, setShowAll] = useState(false)

  const filteredItems = useMemo(() => {
    if (showAll) return items
    return items.filter(it => {
      // 1. 还没分析完的，或者分析失败的，暂时显示（等待回填或重试）
      if (it.analysisStatus !== 'DONE') return true
      
      // 2. 已经 DONE 但缺失相关度数据的（旧数据），在回填完成前默认隐藏，避免干扰
      if (it.relevanceScore === undefined || it.relevanceScore === null || it.relevanceScore === 0) {
        return false
      }

      // 3. 明确分数 >= 40 的显示
      return it.relevanceScore >= 40
    })
  }, [items, showAll])

  const loadData = async () => {
    try {
      setLoading(true)
      const url = platform 
        ? `${API_BASE}/api/workspace/list?platform=${platform}`
        : `${API_BASE}/api/workspace/list`
      const res = await fetch(url)
      const data = await res.json()
      setItems(data)
    } catch (e) {
      console.error("加载工作台数据失败", e)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    loadData()
  }, [platform])

  const toggleExpand = (id: number) => {
    const next = new Set(expandedIds)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    setExpandedIds(next)
  }

  const toggleSelect = (id: number) => {
    const next = new Set(selectedIds)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    setSelectedIds(next)
  }

  const toggleSelectAll = () => {
    if (selectedIds.size === filteredItems.length) {
      setSelectedIds(new Set())
    } else {
      setSelectedIds(new Set(filteredItems.map(it => it.id)))
    }
  }

  const deleteItem = async (id: number) => {
    if (!confirm("确定要删除该岗位吗？")) return
    try {
      const res = await fetch(`${API_BASE}/api/workspace/${id}`, { method: "DELETE" })
      if (res.ok) loadData()
      else alert("删除失败")
    } catch (e) {
      alert("删除失败")
    }
  }

  const batchDelete = async () => {
    if (selectedIds.size === 0) return
    if (!confirm(`确定要删除选中的 ${selectedIds.size} 个岗位吗？`)) return
    try {
      const res = await fetch(`${API_BASE}/api/workspace/batch-delete`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(Array.from(selectedIds))
      })
      if (res.ok) {
        setSelectedIds(new Set())
        loadData()
      } else alert("批量删除失败")
    } catch (e) {
      alert("批量删除失败")
    }
  }

  const updateStatus = async (id: number, status: number) => {
    try {
      await fetch(`${API_BASE}/api/workspace/update-status`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ id, status })
      })
      loadData()
    } catch (e) {
      alert("操作失败")
    }
  }

  const batchUpdateStatus = async (status: number) => {
    if (selectedIds.size === 0) return
    try {
      await fetch(`${API_BASE}/api/workspace/batch-update-status`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ids: Array.from(selectedIds), status })
      })
      setSelectedIds(new Set())
      loadData()
    } catch (e) {
      alert("批量操作失败")
    }
  }

  const getStatusBadge = (status: number) => {
    switch (status) {
      case 1: return <Badge className="bg-green-100 text-green-700">已投递</Badge>
      case 2: return <Badge className="bg-gray-100 text-gray-700">已忽略</Badge>
      default: return <Badge className="bg-blue-100 text-blue-700">待处理</Badge>
    }
  }

  const getAnalysisStatusBadge = (it: JobWorkspaceItem) => {
    switch (it.analysisStatus) {
      case 'DONE': return (
        <div className="flex flex-col gap-1">
          <Badge className="bg-emerald-100 text-emerald-700 w-fit">分析完成</Badge>
          {(it.relevanceScore !== undefined && it.relevanceScore !== null && it.relevanceScore !== 0) ? (
            <Badge className={`${
              it.relevanceScore >= 80 ? 'bg-green-100 text-green-700' :
              it.relevanceScore >= 40 ? 'bg-blue-100 text-blue-700' :
              'bg-red-100 text-red-700'
            } w-fit`}>
              相关度: {it.relevanceScore}
            </Badge>
          ) : (
            <Badge variant="outline" className="text-orange-500 border-orange-200 w-fit animate-pulse">
              待回填评分
            </Badge>
          )}
        </div>
      )
      case 'PROCESSING': return <Badge className="bg-amber-100 text-amber-700 animate-pulse">分析中...</Badge>
      case 'FAILED': return (
        <div className="group relative inline-block">
          <Badge className="bg-red-100 text-red-700 cursor-help">分析失败</Badge>
          {it.analysisError && (
            <div className="hidden group-hover:block absolute z-10 w-48 p-2 mt-1 text-xs text-white bg-black rounded shadow-lg">
              {it.analysisError}
            </div>
          )}
        </div>
      )
      default: return <Badge variant="outline" className="text-gray-400">待分析</Badge>
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between bg-white dark:bg-neutral-900 p-4 rounded-xl border border-gray-200 dark:border-neutral-800 shadow-sm">
        <div className="flex items-center gap-6">
          <div className="flex items-center gap-2">
            <Checkbox 
              checked={filteredItems.length > 0 && selectedIds.size === filteredItems.length}
              onCheckedChange={toggleSelectAll}
            />
            <span className="text-sm font-medium">全选</span>
          </div>
          <div className="flex items-center gap-2 border-l pl-6">
            <Checkbox 
              id="show-all" 
              checked={showAll} 
              onCheckedChange={(checked) => setShowAll(!!checked)}
            />
            <Label htmlFor="show-all" className="text-sm cursor-pointer flex items-center gap-1">
              <BiFilterAlt className={showAll ? "text-gray-400" : "text-blue-500"} />
              显示低相关岗位
            </Label>
          </div>
          <span className="text-sm text-muted-foreground">已选 {selectedIds.size} 项 / 共 {filteredItems.length} 项</span>
        </div>
        <div className="flex gap-2">
          <Button 
            size="sm" 
            variant="outline" 
            onClick={loadData}
            disabled={loading}
          >
            刷新列表
          </Button>
          <Button 
            size="sm" 
            variant="outline" 
            className="text-red-600 border-red-200 hover:bg-red-50"
            disabled={selectedIds.size === 0}
            onClick={batchDelete}
          >
            <BiTrash className="mr-1" /> 批量删除
          </Button>
          <Button 
            size="sm" 
            variant="outline" 
            className="text-orange-600 border-orange-200 hover:bg-orange-50"
            disabled={selectedIds.size === 0}
            onClick={() => batchUpdateStatus(2)}
          >
            批量忽略
          </Button>
          <Button 
            size="sm" 
            className="bg-green-600 hover:bg-green-700 text-white"
            disabled={selectedIds.size === 0}
            onClick={() => batchUpdateStatus(1)}
          >
            <BiCheckCircle className="mr-1" /> 批量标记已投
          </Button>
        </div>
      </div>

      <div className="rounded-xl border border-gray-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 overflow-hidden shadow-sm">
        <table className="w-full text-sm text-left">
          <thead className="bg-gray-50 dark:bg-neutral-800 text-gray-700 dark:text-gray-300 border-b border-gray-200 dark:border-neutral-700">
            <tr>
              <th className="px-4 py-3 w-10"></th>
              <th className="px-4 py-3 w-40">职位</th>
              <th className="px-4 py-3 w-40">公司</th>
              <th className="px-4 py-3 w-32">薪资/地点</th>
              <th className="px-4 py-3 w-24">AI分析 / 相关度</th>
              <th className="px-4 py-3 w-24">状态</th>
              <th className="px-4 py-3 w-32 text-right">操作</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100 dark:divide-neutral-800">
            {loading ? (
              <tr><td colSpan={7} className="px-4 py-8 text-center text-muted-foreground">加载中...</td></tr>
            ) : filteredItems.length === 0 ? (
              <tr><td colSpan={7} className="px-4 py-8 text-center text-muted-foreground">暂无分析数据，请先运行爬虫抓取</td></tr>
            ) : (
              filteredItems.map(it => (
                <Fragment key={it.id}>
                  <tr className={`hover:bg-gray-50/50 dark:hover:bg-neutral-800/50 transition-colors ${it.relevanceScore !== undefined && it.relevanceScore < 40 ? 'opacity-60 bg-gray-50/30' : ''}`}>
                    <td className="px-4 py-3">
                      <Checkbox 
                        checked={selectedIds.has(it.id)}
                        onCheckedChange={() => toggleSelect(it.id)}
                      />
                    </td>
                    <td className="px-4 py-3 font-medium">
                      <div className="flex flex-col">
                        <span>{it.jobName}</span>
                        <a href={it.jobUrl} target="_blank" className="text-xs text-blue-500 hover:underline flex items-center gap-1 mt-1">
                          查看链接 <BiLinkExternal />
                        </a>
                      </div>
                    </td>
                    <td className="px-4 py-3">{it.companyName}</td>
                    <td className="px-4 py-3">
                      <div className="text-xs space-y-1">
                        <div className="text-orange-600 font-semibold">{it.salary}</div>
                        <div className="text-gray-500">{it.location}</div>
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      {getAnalysisStatusBadge(it)}
                    </td>
                    <td className="px-4 py-3">{getStatusBadge(it.deliveryStatus)}</td>
                    <td className="px-4 py-3 text-right space-x-1">
                      <Button size="sm" variant="ghost" className="text-red-500 hover:text-red-700 hover:bg-red-50" onClick={() => deleteItem(it.id)}>
                        <BiTrash />
                      </Button>
                      <Button size="sm" variant="ghost" onClick={() => toggleExpand(it.id)}>
                        {expandedIds.has(it.id) ? <BiChevronUp /> : <BiChevronDown />}
                      </Button>
                    </td>
                  </tr>
                  {expandedIds.has(it.id) && (
                    <tr className="bg-blue-50/30 dark:bg-blue-900/10">
                      <td colSpan={7} className="px-12 py-4 border-t border-blue-100 dark:border-blue-900/30">
                        <div className="grid grid-cols-1 md:grid-cols-4 gap-6">
                          <div className="space-y-2">
                            <h4 className="text-xs font-bold text-gray-500 uppercase tracking-wider">岗位描述 (JD)</h4>
                            <div className="text-xs leading-relaxed max-h-64 overflow-y-auto whitespace-pre-wrap p-3 rounded-lg border bg-white dark:bg-neutral-900">
                              {it.jdText}
                            </div>
                          </div>
                          <div className="space-y-2">
                            <h4 className="text-xs font-bold text-cyan-600 uppercase tracking-wider">能力差距 (AI Gap)</h4>
                            <div className="text-xs leading-relaxed max-h-64 overflow-y-auto whitespace-pre-wrap p-3 rounded-lg border border-cyan-100 bg-cyan-50/30 dark:bg-neutral-900">
                              {it.analysisStatus === 'DONE' ? it.aiGap : it.analysisStatus === 'PROCESSING' ? 'AI 正在分析中，请稍后...' : '等待 AI 分析...'}
                            </div>
                          </div>
                          <div className="space-y-2">
                            <h4 className="text-xs font-bold text-emerald-600 uppercase tracking-wider">提升计划 (AI Plan)</h4>
                            <div className="text-xs leading-relaxed max-h-64 overflow-y-auto whitespace-pre-wrap p-3 rounded-lg border border-emerald-100 bg-emerald-50/30 dark:bg-neutral-900">
                              {it.analysisStatus === 'DONE' ? it.aiPlan : it.analysisStatus === 'PROCESSING' ? 'AI 正在分析中，请稍后...' : '等待 AI 分析...'}
                            </div>
                          </div>
                          <div className="space-y-2">
                            <h4 className="text-xs font-bold text-indigo-600 uppercase tracking-wider">打分理由 (AI Reason)</h4>
                            <div className="text-xs leading-relaxed max-h-64 overflow-y-auto whitespace-pre-wrap p-3 rounded-lg border border-indigo-100 bg-indigo-50/30 dark:bg-neutral-900">
                              {it.analysisStatus === 'DONE' ? (it.relevanceReason || '无打分理由') : '等待 AI 分析...'}
                            </div>
                          </div>
                        </div>
                        <div className="flex justify-end gap-2 mt-4">
                          <Button size="sm" variant="outline" onClick={() => updateStatus(it.id, 2)}>忽略</Button>
                          <Button size="sm" variant="outline" onClick={() => updateStatus(it.id, 1)}>标记已投</Button>
                          <Button size="sm" className="bg-blue-600 hover:bg-blue-700 text-white">立即投递</Button>
                        </div>
                      </td>
                    </tr>
                  )}
                </Fragment>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
