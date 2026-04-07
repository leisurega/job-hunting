package com.getjobs.application.init;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * 应用启动时确保 job_workspace 表存在
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobWorkspaceInitializer implements CommandLineRunner {

    private final DataSource dataSource;

    @Override
    public void run(String... args) throws Exception {
        ensureTableExists();
    }

    private void ensureTableExists() {
        String ddl = "CREATE TABLE IF NOT EXISTS job_workspace (" +
                " id INTEGER PRIMARY KEY AUTOINCREMENT," +
                " platform VARCHAR(50)," +
                " external_id VARCHAR(100)," +
                " job_url TEXT," +
                " job_name VARCHAR(200)," +
                " company_name VARCHAR(200)," +
                " salary VARCHAR(100)," +
                " location VARCHAR(100)," +
                " jd_text TEXT," +
                " jd_fingerprint VARCHAR(100)," +
                " ai_gap TEXT," +
                " ai_plan TEXT," +
                " delivery_status INTEGER DEFAULT 0," +
                " created_at DATETIME," +
                " updated_at DATETIME," +
                " analysis_status VARCHAR(50) DEFAULT 'PENDING'," +
                " analysis_error TEXT," +
                " analysis_batch_no VARCHAR(100)," +
                " analysis_attempt INTEGER DEFAULT 0," +
                " analyzed_at DATETIME," +
                " relevance_score INTEGER," +
                " relevance_reason TEXT" +
                ")";
        
        String indexFingerprint = "CREATE INDEX IF NOT EXISTS idx_jd_fingerprint ON job_workspace(jd_fingerprint)";
        String indexPlatformId = "CREATE INDEX IF NOT EXISTS idx_platform_extid ON job_workspace(platform, external_id)";
        String indexStatus = "CREATE INDEX IF NOT EXISTS idx_analysis_status ON job_workspace(analysis_status)";

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);

            // 升级已有表：添加缺失列（必须在创建索引前，否则索引创建会因为列不存在而失败）
            addColumnIfNotExists(stmt, "job_workspace", "analysis_status", "VARCHAR(50) DEFAULT 'PENDING'");
            addColumnIfNotExists(stmt, "job_workspace", "analysis_error", "TEXT");
            addColumnIfNotExists(stmt, "job_workspace", "analysis_batch_no", "VARCHAR(100)");
            addColumnIfNotExists(stmt, "job_workspace", "analysis_attempt", "INTEGER DEFAULT 0");
            addColumnIfNotExists(stmt, "job_workspace", "analyzed_at", "DATETIME");
            addColumnIfNotExists(stmt, "job_workspace", "relevance_score", "INTEGER");
            addColumnIfNotExists(stmt, "job_workspace", "relevance_reason", "TEXT");

            stmt.execute(indexFingerprint);
            stmt.execute(indexPlatformId);
            stmt.execute(indexStatus);

            log.info("确保 job_workspace 表及其索引已存在，并已同步新列");
        } catch (Exception e) {
            log.error("创建 job_workspace 表失败: {}", e.getMessage(), e);
        }
    }

    private void addColumnIfNotExists(Statement stmt, String tableName, String columnName, String type) {
        try {
            stmt.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + type);
            log.info("已向表 {} 添加新列 {}", tableName, columnName);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("duplicate column name") || msg.contains("already exists"))) {
                return;
            }
            log.warn("尝试向表 {} 添加列 {} 时出现异常: {}", tableName, columnName, msg);
        }
    }
}
