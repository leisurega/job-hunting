"use client"

import { useEffect, useMemo, useRef, useState } from "react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import PageHeader from "@/app/components/PageHeader"
import { BiRefresh, BiDownload, BiBarChart, BiLineChart, BiPieChart, BiBriefcase, BiLayout } from "react-icons/bi"
import JobWorkspaceTable from "@/app/components/JobWorkspaceTable"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"

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
  }
}

type JobWorkspaceItem = {
  id: number
  platform: string
  jobUrl?: string
  jobName?: string
  companyName?: string
  salary?: string
  location?: string
  aiGap?: string
  aiPlan?: string
  deliveryStatus?: number
  analysisStatus?: string
  relevanceScore?: number
  relevanceReason?: string
  createdAt?: string
}

export default function AnalysisContent({ showHeader = false }: { showHeader?: boolean }) {
  const [activeTab, setActiveTab] = useState("workspace")

  return (
    <div className="space-y-8">
      {showHeader && (
        <PageHeader
          icon={<BiLayout className="text-2xl" />}
          title="AI 智能分析工作台"
          subtitle="全平台 JD 深度分析，精准匹配，一键管理"
          iconClass="text-white"
          accentBgClass="bg-primary"
        />
      )}

      <Card className="border-none shadow-none bg-transparent">
        <CardContent className="px-0">
          <JobWorkspaceTable platform="51job" />
        </CardContent>
      </Card>
    </div>
  )
}