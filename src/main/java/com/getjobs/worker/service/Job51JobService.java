package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.job51.Job51;
import com.getjobs.worker.job51.Job51Config;
import com.getjobs.worker.manager.PlaywrightManager;
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
 * 51job任务服务
 * 管理51job平台的投递任务执行和状态
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Job51JobService implements JobPlatformService, ApplicationEventPublisherAware {
    private static final String PLATFORM = "51job";

    private final PlaywrightManager playwrightManager;
    private final ObjectProvider<Job51> job51Provider;
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
    private volatile Job51 currentWorker = null;

    @Override
    public void executeDelivery(Consumer<JobProgressMessage> progressCallback) {
        runJob51Task(configService.getJob51Config(), job51 -> {
        }, progressCallback, count -> String.format("投递任务完成，共投递%d个职位", count));
    }

    public void executeCrawl(Consumer<JobProgressMessage> progressCallback) {
        runJob51Task(configService.getJob51Config(), job51 -> {
            job51.setFetchOnly(true);
        }, progressCallback, count -> String.format("抓取任务完成，共处理%d个职位", count), null, "crawl");
    }

    public Map<String, Object> refreshFirstPage() {
        Map<String, Object> summary = new HashMap<>();
        List<String> logs = new ArrayList<>();
        Job51Config config = configService.getJob51Config();
        config.setKeywords(limitList(config.getKeywords(), 1));
        config.setJobArea(limitList(config.getJobArea(), 1));
        int maxItems = Math.min(80, Math.max(1, config.getMaxItems() != null ? config.getMaxItems() : 30));
        runJob51Task(config, job51 -> {
            job51.setFetchOnly(true);
            job51.setMaxInitialItems(maxItems);
            // 51job 1 页 50 条，如果 maxItems <= 50，限制 1 页即可
            job51.setMaxPageLimit(maxItems <= 50 ? 1 : (int) Math.ceil(maxItems / 50.0));
        }, msg -> logs.add(msg.getMessage()), count -> String.format("增量刷新完成，本次处理%d个职位", count), summary, "crawl");
        summary.put("logs", logs);
        return summary;
    }

    public Map<String, Object> deliverTargetJobs(Set<Long> targetJobIds) {
        Map<String, Object> summary = new HashMap<>();
        List<String> logs = new ArrayList<>();
        runJob51Task(configService.getJob51Config(), job51 -> {
            job51.setTargetJobIds(targetJobIds);
        }, msg -> logs.add(msg.getMessage()), count -> String.format("定向投递完成，共处理%d个职位", count), summary, "execute");
        summary.put("logs", logs);
        return summary;
    }

    private void runJob51Task(Job51Config config,
                              Consumer<Job51> customizer,
                              Consumer<JobProgressMessage> progressCallback,
                              java.util.function.IntFunction<String> successMessageBuilder) {
        runJob51Task(config, customizer, progressCallback, successMessageBuilder, null, "execute");
    }

    private void runJob51Task(Job51Config config,
                              Consumer<Job51> customizer,
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
            Page page = playwrightManager.getJob51Page();
            if (page == null) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "51job页面未初始化"));
                if (summary != null) {
                    summary.put("success", false);
                    summary.put("message", "51job页面未初始化");
                }
                return;
            }

            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "请先登录51job"));
                if (summary != null) {
                    summary.put("success", false);
                    summary.put("message", "请先登录51job");
                    summary.put("needLogin", true);
                }
                return;
            }

            isRunning = true;
            shouldStop = false;
            playwrightManager.pause51jobMonitoring();
            progressCallback.accept(JobProgressMessage.info(PLATFORM, "配置加载成功"));
            progressCallback.accept(JobProgressMessage.info(PLATFORM, "开始执行任务..."));

            Job51.ProgressCallback job51Callback = (message, current, total) -> {
                if (message != null && message.contains("当前页未采集到任何 jobId")) {
                    progressCallback.accept(JobProgressMessage.warning(PLATFORM, "检测到当前页无岗位ID，疑似达到上限或页面变化，任务已停止"));
                    stopDelivery();
                    return;
                }
                if (current != null && total != null) {
                    progressCallback.accept(JobProgressMessage.progress(PLATFORM, message, current, total));
                } else {
                    progressCallback.accept(JobProgressMessage.info(PLATFORM, message));
                }
            };

            Job51 job51 = job51Provider.getObject();
            this.currentWorker = job51;
            job51.setPage(page);
            job51.setConfig(config);
            job51.setProgressCallback(job51Callback);
            job51.setShouldStopCallback(this::shouldStop);
            customizer.accept(job51);
            job51.prepare();

            int resultCount = "crawl".equals(action) ? job51.crawl() : job51.execute();
            progressCallback.accept(JobProgressMessage.success(PLATFORM, successMessageBuilder.apply(resultCount)));
            if (summary != null) {
                summary.put("success", true);
                summary.put("message", successMessageBuilder.apply(resultCount));
                summary.put("scannedCount", job51.getScannedCount());
                summary.put("resultCount", resultCount);
            }
            if ("crawl".equals(action) && resultCount > 0) {
                eventPublisher.publishEvent(new CrawlCompletedEvent(this, PLATFORM, resultCount));
            }
        } catch (Exception e) {
            log.error("51job任务执行失败", e);
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
                playwrightManager.resume51jobMonitoring();
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void stopDelivery() {
        if (isRunning) {
            log.info("收到停止51job投递任务的请求");
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
