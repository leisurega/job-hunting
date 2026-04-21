"use client"

import { useEffect, useMemo, useRef, useState } from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import PageHeader from "@/app/components/PageHeader"
import { BiRefresh, BiDownload, BiBarChart, BiLineChart, BiPieChart, BiBriefcase, BiLayout } from "react-icons/bi"
import JobWorkspaceTable from "@/app/components/JobWorkspaceTable"

type NameValue = { name: string; value: number }
type BucketValue = { bucket: string; value: number }

type StatsResponse = {
  kpi: {
    total: number
    delivered: number
    pending: number
    filtered: number
    failed: number
    avgMonthlyK?: number | null
  }
  charts: {
    byStatus: NameValue[]
    byCity: NameValue[]
    byIndustry: NameValue[]
    byCompany: NameValue[]
    byExperience: NameValue[]
    byDegree: NameValue[]
    salaryBuckets: BucketValue[]
    dailyTrend: NameValue[]
    hrActivity: NameValue[]
  }
}

const API_BASE = "http://localhost:8888"
const CATEGORY_COLORS = [
  "#3b82f6",
  "#10b981",
  "#f59e0b",
  "#ef4444",
  "#6366f1",
  "#22c55e",
  "#fb7185",
  "#a78bfa",
  "#f97316",
  "#06b6d4",
  "#4ade80",
  "#2dd4bf",
  "#f472b6",
  "#64748b",
]

function ChartCanvas({
  type,
  labels,
  data,
  title,
  color = "#3b82f6",
  colors,
}: {
  type: "pie" | "bar" | "line"
  labels: string[]
  data: number[]
  title?: string
  color?: string
  colors?: string[]
}) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const chartRef = useRef<any | null>(null)
  const toSolid = (hex: string) => hex

  async function ensureChart(): Promise<any> {
    if (typeof window !== "undefined" && (window as any).Chart) return (window as any).Chart
    return new Promise((resolve, reject) => {
      const existing = document.querySelector("script[data-chartjs-cdn='true']") as HTMLScriptElement | null
      if (existing) {
        existing.addEventListener("load", () => resolve((window as any).Chart))
        existing.addEventListener("error", () => reject(new Error("Chart.js CDN load error")))
        return
      }
      const script = document.createElement("script")
      script.src = "https://cdn.jsdelivr.net/npm/chart.js@4.4.4/dist/chart.umd.min.js"
      script.async = true
      script.setAttribute("data-chartjs-cdn", "true")
      script.addEventListener("load", () => resolve((window as any).Chart))
      script.addEventListener("error", () => reject(new Error("Chart.js CDN load error")))
      document.head.appendChild(script)
    })
  }

  useEffect(() => {
    const ctx = canvasRef.current?.getContext("2d")
    if (!ctx) return

    if (chartRef.current) {
      chartRef.current.destroy()
      chartRef.current = null
    }

    let cancelled = false

    const pieColorsBase = [
      "#3b82f6",
      "#10b981",
      "#f59e0b",
      "#ef4444",
      "#6366f1",
      "#22c55e",
      "#fb7185",
      "#a78bfa",
      "#f97316",
      "#06b6d4",
    ]

    const backgroundColor = (() => {
      if (type === "pie") {
        const arr = (colors && colors.length ? colors : pieColorsBase).slice(0, labels.length)
        return arr
      }
      if (type === "bar" && colors && colors.length) {
        return colors.slice(0, data.length).map((c) => toSolid(c))
      }
      return toSolid(color ?? "#3b82f6")
    })()

    const borderColor = (() => {
      if (type === "pie") return undefined
      if (type === "bar" && colors && colors.length) return colors.slice(0, data.length)
      return color
    })()

    const dataset: any = {
      label: title || "",
      data,
      backgroundColor,
      borderColor,
    }

    if (type === "line") {
      dataset.fill = false
      dataset.pointBackgroundColor = toSolid(color)
      dataset.pointBorderColor = toSolid(color)
    }

    ;(async () => {
      try {
        const Chart = await ensureChart()
        if (cancelled) return
        chartRef.current = new Chart(ctx, {
          type,
          data: { labels, datasets: [dataset] },
          options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
              legend: { display: type === "pie" },
              title: { display: !!title, text: title },
            },
            scales: type !== "pie" ? { x: { ticks: { autoSkip: true } }, y: { beginAtZero: true } } : undefined,
          },
        })
      } catch (error) {
        console.error("Failed to create chart:", error)
      }
    })()

    return () => {
      cancelled = true
      if (chartRef.current) {
        chartRef.current.destroy()
        chartRef.current = null
      }
    }
  }, [type, labels, data, title, color, colors])

  return <canvas ref={canvasRef} className="w-full h-64" />
}

export default function AnalysisContent({ showHeader = false }: { showHeader?: boolean }) {
  const [stats, setStats] = useState<StatsResponse | null>(null)
  const [loadingStats, setLoadingStats] = useState(true)
  const [statsError, setStatsError] = useState<string | null>(null)

  const [statuses, setStatuses] = useState<string[]>([])
  const [location, setLocation] = useState<string>("")
  const [experience, setExperience] = useState<string>("")
  const [degree, setDegree] = useState<string>("")
  const [minK, setMinK] = useState<string>("")
  const [maxK, setMaxK] = useState<string>("")
  const [keyword, setKeyword] = useState<string>("")

  const statusOptions = ["未投递", "已投递", "已忽略", "投递失败"]

  const loadStats = async (attempt = 0) => {
    try {
      setLoadingStats(true)
      setStatsError(null)
      const params = new URLSearchParams()
      params.set("platform", "boss")
      if (statuses.length) params.set("statuses", statuses.join(","))
      if (location) params.set("location", location)
      if (experience) params.set("experience", experience)
      if (degree) params.set("degree", degree)
      if (minK) params.set("minK", String(Number(minK)))
      if (maxK) params.set("maxK", String(Number(maxK)))
      if (keyword) params.set("keyword", keyword)
      
      const res = await fetch(`${API_BASE}/api/workspace/stats?${params.toString()}`)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data: StatsResponse = await res.json()
      setStats(data)
    } catch (e) {
      console.error("fetch stats failed", e)
      if (attempt < 2) {
        const delay = 1000 * Math.pow(2, attempt)
        setTimeout(() => loadStats(attempt + 1), delay)
        return
      }
      setStatsError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoadingStats(false)
    }
  }

  const renderChartPlaceholder = () => {
    if (statsError) {
      return (
        <div className="h-64 flex flex-col items-center justify-center border border-dashed rounded-lg text-muted-foreground gap-2">
          <span className="text-sm text-red-500">加载失败: {statsError}</span>
          <Button size="sm" variant="outline" onClick={() => loadStats()}>重试</Button>
        </div>
      )
    }
    return <div className="h-64 flex items-center justify-center border border-dashed rounded-lg text-muted-foreground">{loadingStats ? "加载中..." : "暂无数据"}</div>
  }

  useEffect(() => {
    loadStats()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [statuses.join(","), location, experience, degree, minK, maxK, keyword])

  const kpiCards = useMemo(() => {
    const k = stats?.kpi
    return [
      { title: "总岗位数", value: k?.total ?? 0 },
      { title: "已投递", value: k?.delivered ?? 0 },
      { title: "未投递", value: k?.pending ?? 0 },
      { title: "已忽略", value: k?.filtered ?? 0 },
      { title: "投递失败", value: k?.failed ?? 0 },
      { title: "平均月薪(K)", value: k?.avgMonthlyK ?? 0 },
    ]
  }, [stats])

  return (
    <div className="space-y-8">
      {showHeader && (
        <PageHeader
          icon={<BiLayout className="text-2xl" />}
          title="Boss 直聘分析工作台"
          subtitle="全平台 JD 深度分析，精准匹配，一键管理"
          iconClass="text-white"
          accentBgClass="bg-primary"
        />
      )}

      {/* KPI 卡片 */}
      <div className="grid grid-cols-2 md:grid-cols-6 gap-4">
        {kpiCards.map((c, idx) => (
          <Card key={idx} className="border">
            <CardHeader className="p-4">
              <CardTitle className="text-sm font-medium text-muted-foreground">{c.title}</CardTitle>
              <CardDescription className="text-2xl font-bold text-foreground">{c.value}</CardDescription>
            </CardHeader>
          </Card>
        ))}
      </div>

      {/* 筛选栏 */}
      <Card>
        <CardHeader>
          <div className="flex flex-wrap items-center justify-between gap-4">
            <div>
              <CardTitle className="text-base font-semibold">筛选条件</CardTitle>
              <CardDescription>按状态、地区、经验、学历与薪资区间过滤数据</CardDescription>
            </div>
            <div className="flex flex-wrap gap-2">
              {statusOptions.map((s) => (
                <Button
                  key={s}
                  size="sm"
                  variant={statuses.includes(s) ? "default" : "outline"}
                  onClick={() => setStatuses((prev) => (prev.includes(s) ? prev.filter((x) => x !== s) : [...prev, s]))}
                  className="rounded-full"
                >
                  {s}
                </Button>
              ))}
              <Button size="sm" variant="ghost" onClick={() => setStatuses([])}>重置</Button>
            </div>
          </div>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-1 md:grid-cols-6 gap-4">
            <div className="space-y-2">
              <Label>城市</Label>
              <Input value={location} onChange={(e) => setLocation(e.target.value)} placeholder="如：深圳" />
            </div>
            <div className="space-y-2">
              <Label>经验</Label>
              <Input value={experience} onChange={(e) => setExperience(e.target.value)} placeholder="如：3-5年" />
            </div>
            <div className="space-y-2">
              <Label>学历</Label>
              <Input value={degree} onChange={(e) => setDegree(e.target.value)} placeholder="如：本科" />
            </div>
            <div className="space-y-2">
              <Label>最低月薪(K)</Label>
              <Input type="number" value={minK} onChange={(e) => setMinK(e.target.value)} placeholder="10" />
            </div>
            <div className="space-y-2">
              <Label>最高月薪(K)</Label>
              <Input type="number" value={maxK} onChange={(e) => setMaxK(e.target.value)} placeholder="30" />
            </div>
            <div className="space-y-2">
              <Label>关键词</Label>
              <Input value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder="公司/岗位" />
            </div>
          </div>
        </CardContent>
      </Card>

      {/* 图表区 */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2"><BiPieChart /> 投递状态分布</CardTitle>
          </CardHeader>
          <CardContent>
            {stats && stats.charts ? (
              <ChartCanvas type="pie" labels={stats.charts.byStatus.map((x) => x.name)} data={stats.charts.byStatus.map((x) => x.value)} colors={CATEGORY_COLORS} />
            ) : renderChartPlaceholder()}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2"><BiBarChart /> 行业TOP10</CardTitle>
          </CardHeader>
          <CardContent>
            {stats && stats.charts ? (
              <ChartCanvas type="bar" labels={stats.charts.byIndustry.map((x) => x.name)} data={stats.charts.byIndustry.map((x) => x.value)} colors={CATEGORY_COLORS} />
            ) : renderChartPlaceholder()}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2"><BiBarChart /> 城市分布</CardTitle>
          </CardHeader>
          <CardContent>
            {stats && stats.charts ? (
              <ChartCanvas type="bar" labels={stats.charts.byCity.map((x) => x.name)} data={stats.charts.byCity.map((x) => x.value)} color="#3b82f6" />
            ) : renderChartPlaceholder()}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base flex items-center gap-2"><BiLineChart /> 薪资区间分布</CardTitle>
          </CardHeader>
          <CardContent>
            {stats && stats.charts ? (
              <ChartCanvas type="line" labels={stats.charts.salaryBuckets.map((x) => x.bucket)} data={stats.charts.salaryBuckets.map((x) => x.value)} color="#ef4444" />
            ) : renderChartPlaceholder()}
          </CardContent>
        </Card>
      </div>

      {/* 岗位列表 */}
      <Card className="border-none shadow-none bg-transparent">
        <CardContent className="px-0">
          <JobWorkspaceTable
            platform="boss"
            filters={{ statuses, location, experience, degree, minK, maxK, keyword }}
          />
        </CardContent>
      </Card>
    </div>
  )
}
