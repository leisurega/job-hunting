package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.dto.JobProgressMessage;
import com.getjobs.worker.liepin.Liepin;
import com.getjobs.worker.liepin.LiepinConfig;
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
 * 猎聘任务服务
 * 负责猎聘平台的自动投递任务管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiepinJobService implements JobPlatformService, ApplicationEventPublisherAware {

    private static final String PLATFORM = "liepin";

    private final PlaywrightManager playwrightManager;
    private final ConfigService configService;
    private final ObjectProvider<Liepin> liepinProvider;
    private ApplicationEventPublisher eventPublisher;

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.eventPublisher = applicationEventPublisher;
    }

    // 运行状态标志
    private volatile boolean isRunning = false;

    // 停止请求标志
    private volatile boolean shouldStop = false;

    // 当前运行的 worker 实例
    private volatile Liepin currentWorker = null;

    @Override
    public void executeDelivery(Consumer<JobProgressMessage> progressCallback) {
        runLiepinTask(configService.getLiepinConfig(), liepin -> {
        }, progressCallback, count -> String.format("投递任务完成，共发起%d个聊天", count));
    }

    public void executeCrawl(Consumer<JobProgressMessage> progressCallback) {
        runLiepinTask(configService.getLiepinConfig(), liepin -> {
            liepin.setFetchOnly(true);
        }, progressCallback, count -> String.format("抓取任务完成，共处理%d个职位", count), null, "crawl");
    }

    public Map<String, Object> refreshFirstPage() {
        Map<String, Object> summary = new HashMap<>();
        List<String> logs = new ArrayList<>();
        LiepinConfig config = configService.getLiepinConfig();
        config.setKeywords(limitList(config.getKeywords(), 1));
        int maxItems = Math.min(80, Math.max(1, config.getMaxItems() != null ? config.getMaxItems() : 30));
        runLiepinTask(config, liepin -> {
            liepin.setFetchOnly(true);
            liepin.setMaxInitialItems(maxItems);
            // 猎聘 1 页 40 条，如果 maxItems <= 40，限制 1 页即可
            liepin.setMaxPageLimit(maxItems <= 40 ? 1 : (int) Math.ceil(maxItems / 40.0));
        }, msg -> logs.add(msg.getMessage()), count -> String.format("增量刷新完成，本次处理%d个职位", count), summary, "crawl");
        summary.put("logs", logs);
        return summary;
    }

    public Map<String, Object> deliverTargetJobs(Set<Long> targetJobIds) {
        Map<String, Object> summary = new HashMap<>();
        List<String> logs = new ArrayList<>();
        runLiepinTask(configService.getLiepinConfig(), liepin -> {
            liepin.setTargetJobIds(targetJobIds);
        }, msg -> logs.add(msg.getMessage()), count -> String.format("定向投递完成，共处理%d个职位", count), summary, "execute");
        summary.put("logs", logs);
        return summary;
    }

    private void runLiepinTask(LiepinConfig config,
                               Consumer<Liepin> customizer,
                               Consumer<JobProgressMessage> progressCallback,
                               java.util.function.IntFunction<String> successMessageBuilder) {
        runLiepinTask(config, customizer, progressCallback, successMessageBuilder, null, "execute");
    }

    private void runLiepinTask(LiepinConfig config,
                               Consumer<Liepin> customizer,
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
            Page page = playwrightManager.getLiepinPage();
            if (page == null) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "猎聘页面未初始化"));
                if (summary != null) {
                    summary.put("success", false);
                    summary.put("message", "猎聘页面未初始化");
                }
                return;
            }

            if (!playwrightManager.isLoggedIn(PLATFORM)) {
                progressCallback.accept(JobProgressMessage.error(PLATFORM, "请先登录猎聘"));
                if (summary != null) {
                    summary.put("success", false);
                    summary.put("message", "请先登录猎聘");
                    summary.put("needLogin", true);
                }
                return;
            }

            isRunning = true;
            shouldStop = false;
            playwrightManager.pauseLiepinMonitoring();
            progressCallback.accept(JobProgressMessage.info(PLATFORM, "配置加载成功"));
            progressCallback.accept(JobProgressMessage.info(PLATFORM, "开始执行任务..."));

            Liepin.ProgressCallback cb = (message, current, total) -> {
                if (current != null && total != null) {
                    progressCallback.accept(JobProgressMessage.progress(PLATFORM, message, current, total));
                } else {
                    progressCallback.accept(JobProgressMessage.info(PLATFORM, message));
                }
            };

            Liepin liepin = liepinProvider.getObject();
            this.currentWorker = liepin;
            liepin.setPage(page);
            liepin.setConfig(config);
            liepin.setProgressCallback(cb);
            liepin.setShouldStopCallback(this::shouldStop);
            customizer.accept(liepin);

            // 抓取前的 live 登录校验：如果当前 URL 属于登录页，主动抛 NEED_LOGIN 异常
            String currentUrl = page.url();
            if (currentUrl != null && (currentUrl.contains("passport.liepin.com/login") || page.title().contains("登录"))) {
                throw new IllegalStateException("NEED_LOGIN: 会话失效，请在 Playwright 窗口重新登录猎聘");
            }

            int resultCount = "crawl".equals(action) ? liepin.crawl() : liepin.execute();
            progressCallback.accept(JobProgressMessage.success(PLATFORM, successMessageBuilder.apply(resultCount)));
            if (summary != null) {
                summary.put("success", true);
                summary.put("message", successMessageBuilder.apply(resultCount));
                summary.put("scannedCount", liepin.getScannedCount());
                summary.put("resultCount", resultCount);
            }
            if ("crawl".equals(action) && resultCount > 0) {
                eventPublisher.publishEvent(new CrawlCompletedEvent(this, PLATFORM, resultCount));
            }
        } catch (Exception e) {
            log.error("猎聘任务执行失败", e);
            String errorMsg = e.getMessage();
            boolean needLogin = errorMsg != null && errorMsg.contains("NEED_LOGIN");
            boolean needCaptcha = errorMsg != null && errorMsg.contains("NEED_CAPTCHA");
            progressCallback.accept(JobProgressMessage.error(PLATFORM, "任务失败: " + errorMsg));
            if (summary != null) {
                summary.put("success", false);
                summary.put("message", "任务失败: " + errorMsg);
                if (needLogin) {
                    summary.put("needLogin", true);
                }
                if (needCaptcha) {
                    summary.put("needCaptcha", true);
                    // 把 Playwright 窗口置前让用户能看到 captcha
                    try {
                        playwrightManager.getLiepinPage().bringToFront();
                    } catch (Exception ignore) {
                    }
                }
            }
        } finally {
            isRunning = false;
            shouldStop = false;
            currentWorker = null;
            try {
                playwrightManager.resumeLiepinMonitoring();
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void stopDelivery() {
        if (!isRunning) {
            log.warn("猎聘任务未在运行，无需停止");
            return;
        }
        log.info("收到停止猎聘任务请求");
        shouldStop = true;
        if (currentWorker != null) {
            currentWorker.cancel();
        }
    }

    /**
     * 获取任务状态
     */
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

    public boolean shouldStop() {
        return shouldStop;
    }

    private <T> List<T> limitList(List<T> source, int max) {
        if (source == null || source.isEmpty()) return source;
        return new ArrayList<>(source.subList(0, Math.min(max, source.size())));
    }
}
