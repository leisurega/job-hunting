package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.zhilian.ZhiLian;
import com.getjobs.worker.zhilian.ZhilianConfig;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.getjobs.application.event.CrawlCompletedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;

/**
 * 智联招聘任务服务
 * 管理智联招聘平台的投递任务执行和状态
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZhilianJobService implements JobPlatformService, ApplicationEventPublisherAware {
    private static final String PLATFORM = "zhilian";

    private final PlaywrightManager playwrightManager;
    private final ObjectProvider<ZhiLian> zhilianProvider;
    private final ConfigService configService;
    private ApplicationEventPublisher eventPublisher;

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.eventPublisher = applicationEventPublisher;
    }

    // 任务运行状态
    private volatile boolean isRunning = false;
    // 停止标志
    private volatile boolean shouldStop = false;
    // 当前运行的 worker 实例
    private volatile ZhiLian currentWorker = null;

    @Override
    public void executeDelivery(Consumer<JobProgressMessage> progressCallback) {
        runZhilianTask(configService.getZhilianConfig(), zhilian -> {
        }, progressCallback, count -> String.format("投递任务完成，共投递%d个职位", count));
    }

    public void executeCrawl(Consumer<JobProgressMessage> progressCallback) {
        runZhilianTask(configService.getZhilianConfig(), zhilian -> {
            zhilian.setFetchOnly(true);
        }, progressCallback, count -> String.format("抓取任务完成，共处理%d个职位", count), null, "crawl");
    }

    public Map<String, Object> refreshFirstPage() {
        Map<String, Object> summary = new HashMap<>();
        List<String> logs = new ArrayList<>();
        ZhilianConfig config = configService.getZhilianConfig();
        config.setKeywords(limitList(config.getKeywords(), 1));
        int maxItems = Math.min(80, Math.max(1, config.getMaxItems() != null ? config.getMaxItems() : 30));
        runZhilianTask(config, zhilian -> {
            zhilian.setFetchOnly(true);
            zhilian.setMaxInitialItems(maxItems);
            // 智联 1 页 30 条，如果 maxItems <= 30，限制 1 页即可
            zhilian.setMaxPageLimit(maxItems <= 30 ? 1 : (int) Math.ceil(maxItems / 30.0));
        }, msg -> logs.add(msg.getMessage()), count -> String.format("增量刷新完成，本次处理%d个职位", count), summary, "crawl");
        summary.put("logs", logs);
        return summary;
    }

    public Map<String, Object> deliverTargetJobs(Set<String> targetJobIds) {
        Map<String, Object> summary = new HashMap<>();
        List<String> logs = new ArrayList<>();
        runZhilianTask(configService.getZhilianConfig(), zhilian -> {
            zhilian.setTargetJobIds(targetJobIds);
        }, msg -> logs.add(msg.getMessage()), count -> String.format("定向投递完成，共处理%d个职位", count), summary, "execute");
        summary.put("logs", logs);
        return summary;
    }

    private void runZhilianTask(ZhilianConfig config,
                                Consumer<ZhiLian> customizer,
                                Consumer<JobProgressMessage> progressCallback,
                                java.util.function.IntFunction<String> successMessageBuilder) {
        runZhilianTask(config, customizer, progressCallback, successMessageBuilder, null, "execute");
    }

    private void runZhilianTask(ZhilianConfig config,
                                Consumer<ZhiLian> customizer,
                                Consumer<JobProgressMessage> progressCallback,
                                java.util.function.IntFunction<String> successMessageBuilder,
                                Map<String, Object> summary,
                                String action) {
        if (isRunning) {
            progressCallback.accept(JobProgressMessage.warning(PLATFORM, "任务已在运行中"));
            if (summary != null) {
                summary.put("success", false);
                summary.put("message", "任务已在运行中");
            }
            return;
        }

        try {
            Page page = playwrightManager.getZhilianPage();
            if (page == null) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "智联招聘页面未初始化"));
                if (summary != null) {
                    summary.put("success", false);
                    summary.put("message", "智联招聘页面未初始化");
                }
                return;
            }

            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "请先登录智联招聘"));
                if (summary != null) {
                    summary.put("success", false);
                    summary.put("message", "请先登录智联招聘");
                    summary.put("needLogin", true);
                }
                return;
            }

            isRunning = true;
            shouldStop = false;
            playwrightManager.pauseZhilianMonitoring();
            progressCallback.accept(JobProgressMessage.info(PLATFORM, "配置加载成功"));
            progressCallback.accept(JobProgressMessage.info(PLATFORM, "开始执行任务..."));

            ZhiLian.ProgressCallback zhilianCallback = (message, current, total) -> {
                if (current != null && total != null) {
                    progressCallback.accept(JobProgressMessage.progress(PLATFORM, message, current, total));
                } else {
                    progressCallback.accept(JobProgressMessage.info(PLATFORM, message));
                }
            };

            ZhiLian zhilian = zhilianProvider.getObject();
            this.currentWorker = zhilian;
            zhilian.setPage(page);
            zhilian.setConfig(config);
            zhilian.setProgressCallback(zhilianCallback);
            zhilian.setShouldStopCallback(this::shouldStop);
            customizer.accept(zhilian);
            zhilian.prepare();

            int resultCount = "crawl".equals(action) ? zhilian.crawl() : zhilian.execute();
            progressCallback.accept(JobProgressMessage.success(PLATFORM, successMessageBuilder.apply(resultCount)));
            if (summary != null) {
                summary.put("success", true);
                summary.put("message", successMessageBuilder.apply(resultCount));
                summary.put("scannedCount", zhilian.getScannedCount());
                summary.put("resultCount", resultCount);
            }
            if ("crawl".equals(action) && resultCount > 0) {
                eventPublisher.publishEvent(new CrawlCompletedEvent(this, PLATFORM, resultCount));
            }
        } catch (Exception e) {
            log.error("智联招聘任务执行失败", e);
            progressCallback.accept(JobProgressMessage.error(PLATFORM, "任务失败: " + e.getMessage()));
            if (summary != null) {
                summary.put("success", false);
                summary.put("message", "任务失败: " + e.getMessage());
            }
        } finally {
            isRunning = false;
            shouldStop = false;
            currentWorker = null;
            try {
                playwrightManager.resumeZhilianMonitoring();
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void stopDelivery() {
        if (isRunning) {
            log.info("收到停止智联招聘投递任务的请求");
            shouldStop = true;
            if (currentWorker != null) {
                currentWorker.cancel();
            }
        }
    }

    @Override
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("platform", PLATFORM);
        status.put("isRunning", isRunning);
        status.put("isLoggedIn", playwrightManager.isLoggedIn(PLATFORM));
        return status;
    }

    @Override
    public String getPlatformName() {
        return PLATFORM;
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * 检查是否应该停止
     */
    public boolean shouldStop() {
        return shouldStop;
    }

    private <T> List<T> limitList(List<T> source, int max) {
        if (source == null || source.isEmpty()) return source;
        return new ArrayList<>(source.subList(0, Math.min(max, source.size())));
    }
}
