package com.getjobs.application.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 抓取任务完成事件
 */
@Getter
public class CrawlCompletedEvent extends ApplicationEvent {
    private final String platform;
    private final int count;

    public CrawlCompletedEvent(Object source, String platform, int count) {
        super(source);
        this.platform = platform;
        this.count = count;
    }
}
