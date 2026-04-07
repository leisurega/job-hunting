package com.getjobs.application.service;

import com.getjobs.application.entity.AiEntity;
import com.getjobs.application.entity.JobWorkspaceEntity;
import com.getjobs.application.mapper.AiMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * AI 服务（Spring 管理）
 * 从数据库配置获取 BASE_URL、API_KEY、MODEL 并发起 AI 请求。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiService {
    private final ConfigService configService;
    private final AiMapper aiMapper;

    /**
     * 发送 AI 请求（非流式）并返回回复内容。
     * @param content 用户消息内容
     * @return AI 回复文本
     */
    public String sendRequest(String content) {
        // 读取并校验配置
        var cfg = configService.getAiConfigs();
        String baseUrl = cfg.get("BASE_URL");
        String apiKey = cfg.get("API_KEY");
        String model = cfg.get("MODEL");
        // 根据模型类型选择兼容的端点（部分“推理/Reasoning”模型需要使用 Responses API）
        String endpoint = isResponsesModel(model)
                ? buildResponsesEndpoint(baseUrl)
                : buildChatCompletionsEndpoint(baseUrl);

        int timeoutInSeconds = 60;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutInSeconds))
                .build();

        // 构建 JSON 请求体
        JSONObject requestData = new JSONObject();
        requestData.put("model", model);
        requestData.put("temperature", 0.5);
        if (endpoint.endsWith("/responses")) {
            // Responses API 采用 input 字段
            requestData.put("input", content);
            // 如需显式控制推理强度，可按需开启：
            // JSONObject reasoning = new JSONObject();
            // reasoning.put("effort", "medium");
            // requestData.put("reasoning", reasoning);
        } else {
            // Chat Completions API 使用 messages
            JSONArray messages = new JSONArray();
            JSONObject message = new JSONObject();
            message.put("role", "user");
            message.put("content", content);
            messages.put(message);
            requestData.put("messages", messages);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                // 某些服务（例如 Azure OpenAI）需要 api-key 头，额外加一层兼容
                .header("api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestData.toString()))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JSONObject responseObject = new JSONObject(response.body());

                String requestId = responseObject.optString("id");
                long created = responseObject.optLong("created", 0);
                String usedModel = responseObject.optString("model");

                String responseContent;
                if (endpoint.endsWith("/responses")) {
                    // Responses API：优先读取 output_text
                    responseContent = responseObject.optString("output_text", null);
                    if (responseContent == null || responseContent.isEmpty()) {
                        // 兜底：尝试从通用 choices/message 结构读取（部分代理/兼容层会返回该结构）
                        try {
                            JSONObject messageObject = responseObject.getJSONArray("choices")
                                    .getJSONObject(0)
                                    .getJSONObject("message");
                            responseContent = messageObject.getString("content");
                        } catch (Exception ignore) {
                            responseContent = response.body(); // 最后兜底：返回原始文本，避免空值
                        }
                    }
                } else {
                    // Chat Completions API
                    JSONObject messageObject = responseObject.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message");
                    responseContent = messageObject.getString("content");
                }

                JSONObject usageObject = responseObject.optJSONObject("usage");
                int promptTokens = usageObject != null ? usageObject.optInt("prompt_tokens", -1) : -1;
                int completionTokens = usageObject != null ? usageObject.optInt("completion_tokens", -1) : -1;
                int totalTokens = usageObject != null ? usageObject.optInt("total_tokens", -1) : -1;

                LocalDateTime createdTime = created > 0
                        ? Instant.ofEpochSecond(created).atZone(ZoneId.systemDefault()).toLocalDateTime()
                        : LocalDateTime.now();
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

                log.info("AI响应: id={}, time={}, model={}, promptTokens={}, completionTokens={}, totalTokens={}",
                        requestId, createdTime.format(formatter), usedModel, promptTokens, completionTokens, totalTokens);

                return responseContent;
            } else {
                // 更详细的错误日志，便于定位 400 问题
                log.error("AI请求失败: status={}, endpoint={}, body={}", response.statusCode(), endpoint, response.body());
                // 针对 Responses-only 模型误用 Chat Completions 的常见错误做一次自动重试
                if (!endpoint.endsWith("/responses") && containsReasoningParamError(response.body())) {
                    String fallbackEndpoint = buildResponsesEndpoint(baseUrl);
                    log.warn("检测到 reasoning 相关参数错误，自动切换到 Responses API 重试: {}", fallbackEndpoint);
                    return sendRequestViaResponses(content, apiKey, model, fallbackEndpoint);
                }
                throw new RuntimeException("AI请求失败，状态码: " + response.statusCode() + ", 详情: " + response.body());
            }
        } catch (Exception e) {
            log.error("调用AI服务异常", e);
            throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) return "";
        String trimmed = baseUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /**
     * 根据配置构造 chat/completions 端点，避免重复拼接 /v1
     */
    private String buildChatCompletionsEndpoint(String baseUrl) {
        String normalized = normalizeBaseUrl(baseUrl);
        // 如果 baseUrl 已经包含 /v1（常见配置为 https://api.openai.com/v1），则只拼接 /chat/completions
        if (normalized.endsWith("/v1") || normalized.contains("/v1/")) {
            return normalized + "/chat/completions";
        }
        return normalized + "/v1/chat/completions";
    }

    /**
     * 构造 Responses API 端点
     */
    private String buildResponsesEndpoint(String baseUrl) {
        String normalized = normalizeBaseUrl(baseUrl);
        if (normalized.endsWith("/v1") || normalized.contains("/v1/")) {
            return normalized + "/responses";
        }
        return normalized + "/v1/responses";
    }

    /**
     * 粗略识别需要使用 Responses API 的模型（o-系列、4.1、reasoner 等）
     */
    private boolean isResponsesModel(String model) {
        if (model == null) return false;
        String m = model.toLowerCase();
        return m.contains("o1") || m.contains("o3") || m.contains("o4")
                || m.contains("4.1") || m.contains("reasoner")
                || m.contains("4o-mini") || m.contains("gpt-4o-mini");
    }

    /**
     * 检查错误响应中是否包含 reasoning 相关参数错误（如 reasoning.summary unsupported_value）
     */
    private boolean containsReasoningParamError(String body) {
        if (body == null) return false;
        String s = body.toLowerCase();
        return (s.contains("reasoning") && s.contains("unsupported_value"))
                || s.contains("reasoning.summary");
    }

    /**
     * 使用 Responses API 发送一次请求（用于自动降级/重试）
     */
    private String sendRequestViaResponses(String content, String apiKey, String model, String endpoint) {
        int timeoutInSeconds = 60;
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutInSeconds))
                .build();

        JSONObject requestData = new JSONObject();
        requestData.put("model", model);
        requestData.put("temperature", 0.5);
        requestData.put("input", content);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestData.toString()))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JSONObject resp = new JSONObject(response.body());
                String outputText = resp.optString("output_text", null);
                if (outputText != null && !outputText.isEmpty()) {
                    return outputText;
                }
                // 兜底解析：部分兼容层可能返回 choices/message 结构
                try {
                    JSONObject messageObject = resp.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message");
                    return messageObject.getString("content");
                } catch (Exception ignore) {
                }
                // 无法解析则直接返回原始体，避免空值中断流程
                return response.body();
            }
            log.error("Responses API 调用失败: status={}, endpoint={}, body={}", response.statusCode(), endpoint, response.body());
            throw new RuntimeException("AI请求失败，状态码: " + response.statusCode() + ", 详情: " + response.body());
        } catch (Exception e) {
            log.error("Responses API 调用异常", e);
            throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
        }
    }

    // ================= 合并的 AI 配置管理方法 =================

    /**
     * 获取AI配置（获取最新一条，如果不存在则创建默认配置）
     */
    @Transactional(readOnly = true)
    public AiEntity getAiConfig() {
        var list = aiMapper.selectList(null);
        AiEntity aiEntity = (list == null || list.isEmpty()) ? null : list.get(list.size() - 1);
        if (aiEntity == null) {
            aiEntity = createDefaultConfig();
        }
        return aiEntity;
    }

    /**
     * 获取所有AI配置
     */
    @Transactional(readOnly = true)
    public java.util.List<AiEntity> getAllAiConfigs() {
        return aiMapper.selectList(null);
    }

    /**
     * 根据ID获取AI配置
     */
    @Transactional(readOnly = true)
    public AiEntity getAiConfigById(Long id) {
        return aiMapper.selectById(id);
    }

    /**
     * 保存或更新AI配置（introduce/prompt）
     */
    @Transactional
    public AiEntity saveOrUpdateAiConfig(String introduce, String prompt) {
        var list = aiMapper.selectList(null);
        AiEntity aiEntity = (list == null || list.isEmpty()) ? null : list.get(list.size() - 1);

        if (aiEntity == null) {
            aiEntity = new AiEntity();
            aiEntity.setIntroduce(introduce);
            aiEntity.setPrompt(prompt);
            aiEntity.setCreatedAt(java.time.LocalDateTime.now());
            aiEntity.setUpdatedAt(java.time.LocalDateTime.now());
            aiMapper.insert(aiEntity);
            log.info("创建新的AI配置，ID: {}", aiEntity.getId());
        } else {
            aiEntity.setIntroduce(introduce);
            aiEntity.setPrompt(prompt);
            aiEntity.setUpdatedAt(java.time.LocalDateTime.now());
            aiMapper.updateById(aiEntity);
            log.info("更新AI配置，ID: {}", aiEntity.getId());
        }

        return aiEntity;
    }

    /**
     * 删除AI配置
     */
    @Transactional
    public boolean deleteAiConfig(Long id) {
        int result = aiMapper.deleteById(id);
        if (result > 0) {
            log.info("删除AI配置成功，ID: {}", id);
            return true;
        }
        return false;
    }

    /**
     * 针对多条 JD 和个人介绍进行批量结构化分析
     * @param jobs 岗位列表
     * @return 包含分析结果的 JSON 数组字符串，格式：[{"id":1, "gap":"...", "plan":"..."}, ...]
     */
    public String analyzeGapAndPlanBatch(java.util.List<JobWorkspaceEntity> jobs) {
        if (jobs == null || jobs.isEmpty()) return "[]";

        AiEntity aiConfig = getAiConfig();
        String introduce = (aiConfig != null && aiConfig.getIntroduce() != null) ? aiConfig.getIntroduce() : "";

        StringBuilder jobsContent = new StringBuilder();
        for (int i = 0; i < jobs.size(); i++) {
            JobWorkspaceEntity job = jobs.get(i);
            // 对 JD 进行截断，防止超出上下文
            String jd = job.getJdText();
            if (jd != null && jd.length() > 3000) {
                jd = jd.substring(0, 3000) + "...(已截断)";
            }
            jobsContent.append(String.format("【岗位序号: %d, ID: %d】\n职位: %s @ %s\n描述: %s\n\n", 
                i + 1, job.getId(), job.getJobName(), job.getCompanyName(), jd));
        }

        String batchPrompt = String.format(
            "我的个人介绍如下：\n%s\n\n" +
            "以下是 %d 个招聘岗位的描述 (JD)：\n%s\n" +
            "请基于我的背景，分别为每个岗位分析【能力差距 (Gap)】并提出【提升计划 (Plan)】。\n" +
            "要求：\n" +
            "1. 必须返回一个纯 JSON 数组，不要包含任何说明文字。\n" +
            "2. 数组中每个对象包含五个字段: \"id\" (岗位ID), \"gap\" (Gap分析文本), \"plan\" (提升计划文本), \"relevance_score\" (相关性打分，0-100), \"relevance_reason\" (打分理由)。\n" +
            "3. 保持分析客观简洁。\n" +
            "4. 必须按此 JSON 格式输出：[{\"id\": %d, \"gap\": \"...\", \"plan\": \"...\", \"relevance_score\": 85, \"relevance_reason\": \"...\"}, ...]",
            introduce, jobs.size(), jobsContent.toString(), jobs.get(0).getId()
        );

        try {
            // 这里可以根据模型是否支持 json_mode 调整，目前简单处理
            String response = sendRequest(batchPrompt);
            if (response == null || response.trim().isEmpty()) {
                log.error("批量 AI 分析返回为空");
                return "[]";
            }

            // 简单清洗：去掉可能存在的 markdown 代码块包裹
            String jsonStr = response.trim();
            if (jsonStr.startsWith("```json")) {
                jsonStr = jsonStr.substring(7);
            } else if (jsonStr.startsWith("```")) {
                jsonStr = jsonStr.substring(3);
            }
            if (jsonStr.endsWith("```")) {
                jsonStr = jsonStr.substring(0, jsonStr.length() - 3);
            }
            jsonStr = jsonStr.trim();

            // 尝试校验是否为合法 JSON 数组
            new JSONArray(jsonStr); 
            return jsonStr;
        } catch (Exception e) {
            log.error("批量 AI 分析异常", e);
            return "[]";
        }
    }

    /**
     * 针对 JD 和个人介绍分析差距及提升计划
     * @param jd Job Description
     * @return String 数组，[0] 为 Gap, [1] 为 Plan
     */
    public String[] analyzeGapAndPlan(String jd) {
        AiEntity aiConfig = getAiConfig();
        String introduce = (aiConfig != null && aiConfig.getIntroduce() != null) ? aiConfig.getIntroduce() : "";

        // 构造提示词。要求 AI 按特定格式输出以便解析。
        String userPrompt = String.format(
            "我的个人介绍如下：\n%s\n\n" +
            "现在的招聘岗位描述 (JD) 如下：\n%s\n\n" +
            "请基于以上内容，分析我的【能力差距 (Gap)】并提出【提升计划 (Plan)】，同时给出【相关性打分】(0-100) 及【打分理由】。\n" +
            "回复格式必须严格遵守：\n" +
            "【Gap分析】: ...\n" +
            "【提升计划】: ...\n" +
            "【相关性打分】: ...\n" +
            "【打分理由】: ...\n" +
            "请直接输出分析结果，不要包含任何前言、后记或说明性文字。",
            introduce, jd
        );

        try {
            String response = sendRequest(userPrompt);
            if (response == null || response.trim().isEmpty()) {
                return new String[]{"AI 分析失败", "AI 分析失败"};
            }

            String gap = "未解析到 Gap 分析";
            String plan = "未解析到提升计划";

            // 解析逻辑，适配多种冒号
            String markerGap = "【Gap分析】";
            String markerPlan = "【提升计划】";
            int gapIdx = response.indexOf(markerGap);
            int planIdx = response.indexOf(markerPlan);

            if (gapIdx != -1 && planIdx != -1) {
                if (gapIdx < planIdx) {
                    gap = response.substring(gapIdx + markerGap.length(), planIdx).trim().replaceAll("^[:：\\s]+", "");
                    plan = response.substring(planIdx + markerPlan.length()).trim().replaceAll("^[:：\\s]+", "");
                } else {
                    plan = response.substring(planIdx + markerPlan.length(), gapIdx).trim().replaceAll("^[:：\\s]+", "");
                    gap = response.substring(gapIdx + markerGap.length()).trim().replaceAll("^[:：\\s]+", "");
                }
            } else {
                gap = response;
                plan = "AI 返回格式非预期，请查看 Gap 分析全文。";
            }

            return new String[]{gap, plan};
        } catch (Exception e) {
            log.error("AI 分析异常", e);
            return new String[]{"分析异常: " + e.getMessage(), "请检查网络或配置"};
        }
    }

    /**
     * 创建默认配置
     */
    @Transactional
    protected AiEntity createDefaultConfig() {
        AiEntity aiEntity = new AiEntity();
        aiEntity.setIntroduce("请在此填写您的技能介绍");
        aiEntity.setPrompt("请在此填写AI提示词模板");
        aiEntity.setCreatedAt(java.time.LocalDateTime.now());
        aiEntity.setUpdatedAt(java.time.LocalDateTime.now());
        aiMapper.insert(aiEntity);
        log.info("创建默认AI配置，ID: {}", aiEntity.getId());
        return aiEntity;
    }
}