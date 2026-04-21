"use client"

import { useEffect, useState, Fragment, useMemo } from "react"
import { createSSEWithBackoff } from "@/lib/sse"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Checkbox } from "@/components/ui/checkbox"
import { Label } from "@/components/ui/label"
import { Select } from "@/components/ui/select"
import { BiChevronDown, BiChevronUp, BiLinkExternal, BiTrash, BiSend, BiCheckCircle, BiFilterAlt, BiRefresh } from "react-icons/bi"

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
  industry?: string
  experience?: string
  degree?: string
  sourceDeliveryStatus?: string
  deliveryStatus: number // 0:待处理, 1:已投递, 2:已忽略
  analysisStatus: string // PENDING / PROCESSING / DONE / FAILED
  analysisError?: string
  createdAt: string
  updatedAt: string
}

const API_BASE = "http://localhost:8888"

type WorkspaceFilters = {
  statuses?: string[]
  location?: string
  experience?: string
  degree?: string
  minK?: string
  maxK?: string
  keyword?: string
}

export default function JobWorkspaceTable({
  platform,
  filters,
}: {
  platform?: string
  filters?: WorkspaceFilters
}) {
  const [items, setItems] = useState<JobWorkspaceItem[]>([])
  const [loading, setLoading] = useState(true)
  const [initialLoading, setInitialLoading] = useState(true)
  const [expandedIds, setExpandedIds] = useState<Set<number>>(new Set())
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set())
  const [showOnlyLow, setShowOnlyLow] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [delivering, setDelivering] = useState(false)
  const [analyzing, setAnalyzing] = useState(false)
  const [analysisFilter, setAnalysisFilter] = useState<string>("ALL") // ALL, PENDING, PROCESSING, DONE, FAILED
  const [deliveryFilter, setDeliveryFilter] = useState<string>("ALL") // ALL, NOT_SENT, SENT, IGNORED
  const [platformLoginStatus, setPlatformLoginStatus] = useState<Record<string, boolean>>({
    boss: false, liepin: false, job51: false, zhilian: false
  })
  const [crawlProgress, setCrawlProgress] = useState<{ platform: string, state: string, processed: number, added: number, skipped: number, total: number } | null>(null)
  const [analyzeProgress, setAnalyzeProgress] = useState({ done: 0, total: 0 })
  const [isAnalyzing, setIsAnalyzing] = useState(false)
  const [loginModal, setLoginModal] = useState<{ platform: string, mode?: "login" | "captcha" } | null>(null)
  const [recheckingLogin, setRecheckingLogin] = useState(false)

  const filteredItems = useMemo(() => {
    let result = [...items]

    // 1. 分析状态过滤
    if (analysisFilter !== "ALL") {
      result = result.filter(it => {
        if (analysisFilter === "PENDING") return it.analysisStatus === "PENDING"
        if (analysisFilter === "PROCESSING") return it.analysisStatus === "PROCESSING"
        if (analysisFilter === "DONE") return it.analysisStatus === "DONE"
        if (analysisFilter === "FAILED") return it.analysisStatus === "FAILED"
        return true
      })
    }

    // 2. 投递状态过滤
    if (deliveryFilter !== "ALL") {
      result = result.filter(it => {
        if (deliveryFilter === "NOT_SENT") return it.deliveryStatus === 0 && it.sourceDeliveryStatus !== "已投递"
        if (deliveryFilter === "SENT") return it.deliveryStatus === 1 || it.sourceDeliveryStatus === "已投递"
        if (deliveryFilter === "IGNORED") return it.deliveryStatus === 2
        return true
      })
    }

    // 3. 匹配度过滤逻辑
    if (showOnlyLow) {
      // 仅显示低相关：已分析完成且分数在 (0, 60) 之间
      result = result.filter(it => {
        return it.analysisStatus === 'DONE' && it.relevanceScore !== undefined && it.relevanceScore !== null && it.relevanceScore > 0 && it.relevanceScore < 60
      })
    } else {
      // 默认过滤：显示未分析的，或者已分析且分数 >= 60 的
      result = result.filter(it => {
        if (it.analysisStatus !== 'DONE') return true
        if (it.relevanceScore === undefined || it.relevanceScore === null || it.relevanceScore === 0) return false
        return it.relevanceScore >= 60
      })
    }

    // 4. 默认按更新时间倒序排序
    result.sort((a, b) => {
      return new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime()
    })

    return result
  }, [items, showOnlyLow, analysisFilter, deliveryFilter])

  const selectHighScores = () => {
    const highScores = filteredItems.filter(it => (it.relevanceScore || 0) >= 60)
    const highScoresIds = highScores.map(it => it.id)
    
    const allHighSelected = highScoresIds.length > 0 && highScoresIds.every(id => selectedIds.has(id))
    
    const newSelected = new Set(selectedIds)
    if (allHighSelected) {
      // 如果高分已全选，则取消选择所有高分
      highScoresIds.forEach(id => newSelected.delete(id))
    } else {
      // 否则，添加所有高分到当前选择中
      highScoresIds.forEach(id => newSelected.add(id))
    }
    setSelectedIds(newSelected)
  }

  const batchAnalyze = async () => {
    try {
      setAnalyzing(true)
      const res = await fetch(`${API_BASE}/api/workspace/analyze`, { method: "POST" })
      if (res.ok) {
        alert("批量分析任务已启动")
        loadData()
      }
    } catch (e) {
      alert("启动分析失败")
    } finally {
      setAnalyzing(false)
    }
  }

  const loadData = async () => {
    try {
      setLoading(true)
      if (items.length === 0) setInitialLoading(true)
      
      const params = new URLSearchParams()
      if (platform) params.set("platform", platform)
      if (filters?.statuses?.length) params.set("statuses", filters.statuses.join(","))
      if (filters?.location) params.set("location", filters.location)
      if (filters?.experience) params.set("experience", filters.experience)
      if (filters?.degree) params.set("degree", filters.degree)
      if (filters?.minK) params.set("minK", String(Number(filters.minK)))
      if (filters?.maxK) params.set("maxK", String(Number(filters.maxK)))
      if (filters?.keyword) params.set("keyword", filters.keyword)
      const url = `${API_BASE}/api/workspace/list${params.toString() ? `?${params.toString()}` : ""}`
      const res = await fetch(url)
      const data = await res.json()
      setItems(data)
    } catch (e) {
      console.error("加载工作台数据失败", e)
    } finally {
      setLoading(false)
      setInitialLoading(false)
    }
  }

  useEffect(() => {
    loadData()
  }, [
    platform,
    filters?.statuses?.join(","),
    filters?.location,
    filters?.experience,
    filters?.degree,
    filters?.minK,
    filters?.maxK,
    filters?.keyword,
  ])

  useEffect(() => {
    // 订阅工作台事件（抓取进度、分析进度）
    const client = createSSEWithBackoff(`${API_BASE}/api/workspace/stream`, {
      listeners: [
        {
          name: "crawl-progress",
                        handler: (event) => {
                          try {
                            const data = JSON.parse(event.data)
                            setCrawlProgress(data)
                            if (data.state === "done" && data.added > 0) {
                              loadData()
                            }
                          } catch (e) {
              console.error("解析抓取进度失败", e)
            }
          }
        },
        {
          name: "analyze-progress",
          handler: (event) => {
            try {
              const data = JSON.parse(event.data)
              setAnalyzeProgress(data)
              setIsAnalyzing(data.running)
              if (!data.running) {
                loadData()
              }
            } catch (e) {
              console.error("解析分析进度失败", e)
            }
          }
        }
      ]
    })

    // 订阅登录状态
    const loginClient = createSSEWithBackoff(`${API_BASE}/api/jobs/login-status/stream`, {
      listeners: [
        {
          name: "connected",
          handler: (event) => {
            try {
              const data = JSON.parse(event.data)
              setPlatformLoginStatus({
                boss: data.bossLoggedIn,
                liepin: data.liepinLoggedIn,
                job51: data.job51LoggedIn,
                zhilian: data.zhilianLoggedIn
              })
            } catch (e) {
              console.error("解析登录状态连接消息失败", e)
            }
          }
        },
        {
          name: "login-status",
          handler: (event) => {
            try {
              const data = JSON.parse(event.data)
              setPlatformLoginStatus(prev => ({
                ...prev,
                [data.platform]: data.isLoggedIn
              }))
            } catch (e) {
              console.error("解析登录状态消息失败", e)
            }
          }
        }
      ]
    })

    return () => {
      client.close()
      loginClient.close()
    }
  }, [])

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

  const refreshPlatform = async () => {
    if (!platform) {
      loadData()
      return
    }
    try {
      setRefreshing(true)
      // 立即反馈：预置 start 状态，让进度条出现
      setCrawlProgress({ platform, state: "start", processed: 0, added: 0, skipped: 0, total: 0 })
      
      const res = await fetch(`${API_BASE}/api/workspace/refresh`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ platform }),
      })
      const data = await res.json()
      const needsLogin = data.needLogin
        || (!data.success && typeof data.message === "string" && data.message.includes("请先登录"))
      const needsCaptcha = data.needCaptcha
        || (!data.success && typeof data.message === "string" && data.message.includes("NEED_CAPTCHA"))
      const isCooldown = data.cooldown

      if (needsCaptcha) {
        setLoginModal({ platform: platform!, mode: "captcha" })
        return
      }
      if (needsLogin) {
        openLogin(platform!)
        return
      }
      if (isCooldown) {
        alert(data.message)
        return
      }

      if (!data.success && data.message) {
        alert(data.message)
      }
    } catch (e) {
      console.error("刷新请求失败", e)
      alert(`刷新请求失败: ${e instanceof Error ? e.message : String(e)}`)
    } finally {
      setRefreshing(false)
    }
  }

  const cancelCrawl = async () => {
    setCrawlProgress((prev) =>
      prev && prev.platform === platform && prev.state !== "done"
        ? { ...prev, state: "cancelling" }
        : prev
    )
    try {
      await fetch(`${API_BASE}/api/workspace/crawl/cancel`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ platform }),
      })
    } catch (e) {
      console.error("取消抓取失败", e)
      alert("取消请求发送失败，请重试")
      // 回滚：如果仍处在 cancelling，恢复到 processing，避免用户以为已取消
      setCrawlProgress((prev) =>
        prev && prev.platform === platform && prev.state === "cancelling"
          ? { ...prev, state: "processing" }
          : prev
      )
    }
  }

  const openLogin = async (p: string) => {
    try {
      // 打开登录引导 Modal
      setLoginModal({ platform: p })
      await fetch(`${API_BASE}/api/workspace/open-login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ platform: p }),
      })
    } catch (e) {
      console.error("打开登录页失败", e)
    }
  }

  const recheckLogin = async () => {
    try {
      setRecheckingLogin(true)
      const res = await fetch(`${API_BASE}/api/workspace/login-status`)
      const json = await res.json()
      if (json.success) {
        setPlatformLoginStatus(json.data)
        if (loginModal && json.data[loginModal.platform]) {
          // 登录成功，关闭 Modal
          setLoginModal(null)
        }
      }
    } catch (e) {
      console.error("重新检测登录状态失败", e)
    } finally {
      setRecheckingLogin(false)
    }
  }

  const deliver = async (ids: number[]) => {
    if (!ids.length) return
    try {
      setDelivering(true)
      const res = await fetch(`${API_BASE}/api/workspace/deliver`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ids }),
      })
      const data = await res.json()
      await loadData()
      if (ids.length > 1) setSelectedIds(new Set())
      alert(data?.message || "投递已执行")
    } catch (e) {
      alert("投递失败")
    } finally {
      setDelivering(false)
    }
  }

  const getStatusBadge = (status: number) => {
    switch (status) {
      case 1: return <Badge className="bg-green-100 text-green-700">已投递</Badge>
      case 2: return <Badge className="bg-gray-100 text-gray-700">已忽略</Badge>
      default: return <Badge className="bg-blue-100 text-blue-700">未投递</Badge>
    }
  }

  const getAnalysisBadge = (it: JobWorkspaceItem) => {
    switch (it.analysisStatus) {
      case 'DONE': return <Badge className="bg-emerald-100 text-emerald-700 w-fit">分析完成</Badge>
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

  const getScoreBadge = (it: JobWorkspaceItem) => {
    if (it.analysisStatus !== 'DONE') return <span className="text-gray-400">-</span>
    
    if (it.relevanceScore !== undefined && it.relevanceScore !== null && it.relevanceScore !== 0) {
      return (
        <Badge className={`${
          it.relevanceScore >= 80 ? 'bg-green-100 text-green-700' :
          it.relevanceScore >= 40 ? 'bg-blue-100 text-blue-700' :
          'bg-red-100 text-red-700'
        } w-fit`}>
          {it.relevanceScore}
        </Badge>
      )
    }
    
    return (
      <Badge variant="outline" className="text-orange-500 border-orange-200 w-fit animate-pulse">
        待评分
      </Badge>
    )
  }

  return (
    <div className="space-y-4">
      {/* 登录状态灯 */}
      <div className="flex items-center gap-4 bg-white dark:bg-neutral-900 p-3 rounded-xl border border-gray-200 dark:border-neutral-800 shadow-sm overflow-x-auto">
        <span className="text-xs font-bold text-gray-400 uppercase tracking-wider mr-2">平台状态:</span>
        {['boss', 'liepin', '51job', 'zhilian'].map(p => (
          <div key={p} className="flex items-center gap-2 px-3 py-1 rounded-full border border-gray-100 dark:border-neutral-800 bg-gray-50/50 dark:bg-neutral-800/30">
            <div className={`w-2 h-2 rounded-full ${platformLoginStatus[p] ? 'bg-green-500 shadow-[0_0_8px_rgba(34,197,94,0.6)]' : 'bg-gray-300'}`} />
            <span className={`text-xs font-medium ${platformLoginStatus[p] ? 'text-gray-700 dark:text-gray-200' : 'text-gray-400'}`}>{p}</span>
            {!platformLoginStatus[p] && (
              <button 
                onClick={() => openLogin(p)}
                className="text-[10px] text-blue-500 hover:underline ml-1"
              >
                去登录
              </button>
            )}
          </div>
        ))}
      </div>

      <div className="flex flex-col gap-4 bg-white dark:bg-neutral-900 p-4 rounded-xl border border-gray-200 dark:border-neutral-800 shadow-sm">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-4">
            <div className="flex items-center gap-2">
              <Checkbox 
                checked={filteredItems.length > 0 && selectedIds.size === filteredItems.length}
                onCheckedChange={toggleSelectAll}
              />
              <span className="text-sm font-medium">全选</span>
            </div>
            
            <div className="h-4 w-px bg-gray-200" />
            
            <div className="flex items-center gap-2">
              <Label className="text-xs text-gray-500">分析状态:</Label>
              <Select 
                value={analysisFilter} 
                onChange={(e) => setAnalysisFilter(e.target.value)} 
                className="h-8 text-xs w-[100px] rounded-md"
              >
                <option value="ALL">全部</option>
                <option value="PENDING">待分析</option>
                <option value="PROCESSING">分析中</option>
                <option value="DONE">分析完成</option>
                <option value="FAILED">分析失败</option>
              </Select>
            </div>

            <div className="flex items-center gap-2 border-l pl-4">
              <Label className="text-xs text-gray-500">投递状态:</Label>
              <Select 
                value={deliveryFilter} 
                onChange={(e) => setDeliveryFilter(e.target.value)} 
                className="h-8 text-xs w-[100px] rounded-md"
              >
                <option value="ALL">全部</option>
                <option value="NOT_SENT">未投递</option>
                <option value="SENT">已投递</option>
                <option value="IGNORED">已忽略</option>
              </Select>
            </div>

            <div className="flex items-center gap-2 border-l pl-4">
              <Checkbox 
                id="show-only-low" 
                checked={showOnlyLow} 
                onCheckedChange={(checked) => setShowOnlyLow(!!checked)}
              />
              <Label htmlFor="show-only-low" className="text-sm cursor-pointer flex items-center gap-1">
                <BiFilterAlt className={showOnlyLow ? "text-blue-500" : "text-gray-400"} />
                仅显示低相关(&lt;60)
              </Label>
            </div>
          </div>

          <div className="flex gap-2">
            <Button size="sm" variant="outline" onClick={selectHighScores}>一键选高分</Button>
            {crawlProgress && crawlProgress.platform === platform && crawlProgress.state !== 'done' ? (
              <div className="flex items-center gap-2 bg-blue-50 dark:bg-blue-900/20 px-3 py-1 rounded-md border border-blue-100 dark:border-blue-800">
                <div className="flex flex-col">
                  <span className="text-[10px] text-blue-600 dark:text-blue-400 font-medium">
                    {crawlProgress.state === 'cancelling' ? "正在取消抓取…" : "正在抓取新岗位..."}
                  </span>
                  <div className="w-24 h-1 bg-blue-100 dark:bg-blue-800 rounded-full overflow-hidden">
                    <div className={`h-full ${crawlProgress.state === 'cancelling' ? "bg-gray-400" : "bg-blue-500 animate-pulse"}`} style={{ width: '60%' }} />
                  </div>
                </div>
                {crawlProgress.state === 'cancelling' ? (
                  <Button size="sm" variant="ghost" className="h-7 px-2 text-gray-400" disabled>取消中</Button>
                ) : (
                  <Button size="sm" variant="ghost" className="h-7 px-2 text-red-500 hover:text-red-700 hover:bg-red-50" onClick={cancelCrawl}>取消</Button>
                )}
              </div>
            ) : (
              <Button 
                size="sm" 
                variant="outline" 
                onClick={refreshPlatform} 
                disabled={loading || refreshing || (crawlProgress?.state === 'start' || crawlProgress?.state === 'processing')}
                title="增量抓取首页关键词，只新增未入库岗位，避免平台风控"
              >
                {refreshing ? "启动中..." : "抓取新岗位"}
              </Button>
            )}
            <Button 
              variant="ghost" 
              size="icon"
              onClick={loadData} 
              disabled={loading}
              title="重新加载表格数据"
            >
              <BiRefresh className={loading ? "animate-spin" : ""} />
            </Button>
          </div>
        </div>

        {crawlProgress && crawlProgress.platform === platform && crawlProgress.state === 'done' && (
          <div className="bg-green-50 dark:bg-green-900/10 border border-green-100 dark:border-green-800 p-2 rounded-lg flex items-center justify-between animate-in fade-in slide-in-from-top-1">
            <div className="flex items-center gap-4 text-xs text-green-700 dark:text-green-400">
              <span className="flex items-center gap-1"><BiCheckCircle /> 抓取完成</span>
              <span>发现: {crawlProgress.total}</span>
              <span className="font-bold">新增: {crawlProgress.added}</span>
              <span>跳过: {crawlProgress.skipped}</span>
            </div>
            <button onClick={() => setCrawlProgress(null)} className="text-green-400 hover:text-green-600">×</button>
          </div>
        )}

        <div className="flex items-center justify-between border-t pt-4">
          <div className="flex flex-col gap-1">
            <div className="flex items-center gap-2">
              <span className="text-sm text-muted-foreground">已选 {selectedIds.size} 项 / 共 {filteredItems.length} 项</span>
              {loading && !initialLoading && <span className="w-3 h-3 border-2 border-blue-500/30 border-t-blue-500 rounded-full animate-spin" />}
            </div>
            <span className="text-[10px] text-gray-400">提示：默认仅显示相关度 ≥ 60 的岗位，低分岗位请勾选「显示低相关」查看</span>
          </div>
          <div className="flex gap-2">
            <Button 
              size="sm" 
              variant="outline" 
              className="text-blue-600 border-blue-200 hover:bg-blue-50"
              disabled={selectedIds.size === 0 || analyzing}
              onClick={batchAnalyze}
            >
              批量分析
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
              className="bg-green-600 hover:bg-green-700 text-white"
              disabled={selectedIds.size === 0 || delivering || refreshing}
              onClick={() => deliver(Array.from(selectedIds))}
            >
              <BiCheckCircle className="mr-1" /> 批量投递
            </Button>
          </div>
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
              <th className="px-4 py-3 w-24">分析状态</th>
              <th className="px-4 py-3 w-20">相关度</th>
              <th className="px-4 py-3 w-32">发布/更新时间</th>
              <th className="px-4 py-3 w-24">状态</th>
              <th className="px-4 py-3 w-32 text-right">操作</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100 dark:divide-neutral-800">
            {initialLoading ? (
              <tr><td colSpan={8} className="px-4 py-8 text-center text-muted-foreground">加载中...</td></tr>
            ) : filteredItems.length === 0 ? (
              <tr><td colSpan={8} className="px-4 py-8 text-center text-muted-foreground">暂无分析数据，请先运行爬虫抓取</td></tr>
            ) : (
              filteredItems.map(it => (
                <Fragment key={it.id}>
                  <tr className={`hover:bg-gray-50/50 dark:hover:bg-neutral-800/50 transition-colors ${it.relevanceScore !== undefined && it.relevanceScore < 40 ? 'opacity-60 bg-gray-50/30' : ''}`}>
                    <td className="px-4 py-3">
                      <Checkbox 
                        checked={selectedIds.has(it.id)}
                        onCheckedChange={(checked) => {
                          const newSelected = new Set(selectedIds)
                          if (checked) {
                            newSelected.add(it.id)
                          } else {
                            newSelected.delete(it.id)
                          }
                          setSelectedIds(newSelected)
                        }}
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
                      {getAnalysisBadge(it)}
                    </td>
                    <td className="px-4 py-3">
                      {getScoreBadge(it)}
                    </td>
                    <td className="px-4 py-3">
                      <div className="text-[10px] text-gray-500 space-y-0.5">
                        <div title="发布时间">发: {new Date(it.createdAt).toLocaleString('zh-CN', { hour12: false, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })}</div>
                        <div title="更新时间" className="text-blue-500/80">更: {new Date(it.updatedAt).toLocaleString('zh-CN', { hour12: false, month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })}</div>
                      </div>
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
                      <td colSpan={8} className="px-12 py-4 border-t border-blue-100 dark:border-blue-900/30">
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
                          <Button
                            size="sm"
                            className="bg-blue-600 hover:bg-blue-700 text-white"
                            disabled={delivering || refreshing}
                            onClick={() => deliver([it.id])}
                          >
                            {it.sourceDeliveryStatus === "已投递" || it.deliveryStatus === 1 ? "重新投递" : "立即投递"}
                          </Button>
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

      {/* 登录引导 Modal - 新浏览器窗口 */}
      {loginModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" role="dialog" aria-modal="true">
          <div className="w-[460px] max-w-[92vw] bg-white dark:bg-neutral-900 rounded-xl shadow-2xl border border-gray-200 dark:border-neutral-800 overflow-hidden">
            <div className="px-5 py-4 border-b border-gray-100 dark:border-neutral-800 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="w-2 h-2 rounded-full bg-amber-500 animate-pulse" />
                <span className="text-sm font-bold">
                  {loginModal.mode === "captcha" ? `请完成${loginModal.platform === 'liepin' ? '猎聘' : loginModal.platform}安全验证` : `请在 Playwright 浏览器中扫码登录`}
                </span>
              </div>
              <button onClick={() => setLoginModal(null)} className="text-gray-400 hover:text-gray-700 text-xl leading-none">×</button>
            </div>
            <div className="p-5 space-y-3 text-sm">
              <div className="bg-amber-50 dark:bg-amber-900/10 border border-amber-200 dark:border-amber-800/40 p-3 rounded-lg">
                <p className="text-amber-800 dark:text-amber-300 font-medium mb-1">重要提示</p>
                <p className="text-xs text-amber-700 dark:text-amber-400 leading-relaxed">
                  {loginModal.mode === "captcha" 
                    ? "Playwright 窗口已跳转到验证码页面，请手动完成滑块/点选验证后点下方按钮重试。"
                    : `本系统使用独立的 Playwright 浏览器进行自动化操作，它不会复用你当前浏览器的登录态。`}
                </p>
              </div>
              <div className="space-y-2 text-gray-700 dark:text-gray-300">
                <p>请按以下步骤操作：</p>
                <ol className="list-decimal list-inside space-y-1 text-xs pl-2">
                  <li>切换到自动弹出的 <span className="font-bold text-blue-600">Playwright Chrome 窗口</span></li>
                  <li>{loginModal.mode === "captcha" ? "手动完成页面上的验证码校验" : "使用手机 App 扫码完成登录"}</li>
                  <li>完成后，回到本页点击下方「我已完成」按钮</li>
                </ol>
              </div>
              <div className="pt-2 flex gap-2 justify-end">
                <Button size="sm" variant="outline" onClick={() => setLoginModal(null)}>
                  稍后再说
                </Button>
                <Button 
                  size="sm" 
                  className="bg-blue-600 hover:bg-blue-700 text-white" 
                  onClick={recheckLogin}
                  disabled={recheckingLogin}
                >
                  {recheckingLogin ? "检测中..." : "我已完成"}
                </Button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
