package com.maidsmart.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 文本向量化客户端（移植自 maidsoulcore v3 {@code vector/HttpEmbeddingClient + EmbeddingProvider}）。
 *
 * <p>对接 **OpenAI 兼容的 embeddings 接口**：{@code POST {url}}，body
 * {@code {"model":..,"input":["文本", ...]}}，回包 {@code {"data":[{"embedding":[...]}, ...]}}
 * （按 {@code index} 排序还原输入顺序）。用于给记忆段落与查询各算一个向量，做语义相似召回——
 * "我们上次挖的那个矿"这种**同义改写**是纯词面匹配（BM25/n-gram）永远召不回的，只有向量能。
 *
 * <p>默认关闭（memory.vectorEnable=false）：需要一个额外的 embeddings 端点，会带来配额与网络
 * 依赖，所以做成**显式开启**的可选能力；关掉时整条语义路不参与检索，其它一切照旧。
 *
 * <p>纯 Java（java.net.http + Gson），不碰 MC API。两树逐字相同。
 */
public final class AiMemoryEmbeddingClient {

    /** 一批文本的向量化结果。 */
    public record Result(boolean ok, List<float[]> vectors, String error) {
        public static Result fail(String err) {
            return new Result(false, List.of(), err);
        }
    }

    private final String url;
    private final String apiKey;
    private final String model;
    private final int expectedDim;
    private final long timeoutMs;
    private final int maxBatch;

    private final HttpClient client;

    public AiMemoryEmbeddingClient(String url, String apiKey, String model,
                                   int expectedDim, long timeoutMs, int maxBatch) {
        this.url = url == null ? "" : url.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
        this.expectedDim = Math.max(0, expectedDim);
        this.timeoutMs = timeoutMs <= 0 ? 5000L : timeoutMs;
        this.maxBatch = Math.max(1, maxBatch);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.timeoutMs))
                .build();
    }

    /** 是否可用（配了 URL 且模型名非空）。 */
    public boolean usable() {
        return !this.url.isEmpty() && !this.model.isEmpty();
    }

    public int expectedDim() {
        return this.expectedDim;
    }

    /** 批量向量化；失败时 ok=false、error 说明原因（绝不抛异常）。 */
    public Result embed(List<String> texts) {
        if (!usable()) {
            return Result.fail("embedding endpoint not configured");
        }
        if (texts == null || texts.isEmpty()) {
            return new Result(true, List.of(), "");
        }
        List<JsonArray> rows = new ArrayList<>();
        try {
            for (int start = 0; start < texts.size(); start += this.maxBatch) {
                int end = Math.min(texts.size(), start + this.maxBatch);
                Result part = embedOnce(texts.subList(start, end));
                if (!part.ok()) {
                    return part;
                }
                for (float[] v : part.vectors()) {
                    JsonArray a = new JsonArray();
                    for (float f : v) {
                        a.add(f);
                    }
                    rows.add(a);
                }
            }
        } catch (Exception e) {
            return Result.fail(e.toString());
        }
        List<float[]> out = new ArrayList<>(rows.size());
        for (JsonArray a : rows) {
            float[] v = new float[a.size()];
            for (int i = 0; i < a.size(); i++) {
                v[i] = a.get(i).getAsFloat();
            }
            out.add(v);
        }
        return new Result(true, out, "");
    }

    private Result embedOnce(List<String> texts) {
        try {
            String body = buildRequest(texts);
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(this.url))
                    .timeout(Duration.ofMillis(this.timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (!this.apiKey.isEmpty()) {
                b = b.header("Authorization", "Bearer " + this.apiKey);
            }
            HttpResponse<String> resp = this.client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                return Result.fail("http " + resp.statusCode());
            }
            return parse(resp.body(), texts.size());
        } catch (Exception e) {
            return Result.fail(e.toString());
        }
    }

    private String buildRequest(List<String> texts) {
        JsonObject root = new JsonObject();
        root.addProperty("model", this.model);
        JsonArray input = new JsonArray();
        for (String t : texts) {
            input.add(t == null ? "" : t);
        }
        root.add("input", input);
        return AiMemoryModels.GSON.toJson(root);
    }

    private Result parse(String body, int expected) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray data = root.getAsJsonArray("data");
            if (data == null) {
                return Result.fail("no data field");
            }
            float[][] ordered = new float[data.size()][];
            int seq = 0;
            for (JsonElement el : data) {
                JsonObject item = el.getAsJsonObject();
                int idx = item.has("index") ? item.get("index").getAsInt() : seq;
                JsonArray emb = item.getAsJsonArray("embedding");
                if (emb == null) {
                    continue;
                }
                float[] v = new float[emb.size()];
                for (int i = 0; i < emb.size(); i++) {
                    v[i] = emb.get(i).getAsFloat();
                }
                if (idx >= 0 && idx < ordered.length) {
                    ordered[idx] = v;
                }
                seq++;
            }
            List<float[]> out = new ArrayList<>();
            for (float[] v : ordered) {
                if (v != null) {
                    out.add(v);
                }
            }
            if (out.isEmpty()) {
                return Result.fail("empty embeddings");
            }
            return new Result(true, out, "");
        } catch (Exception e) {
            return Result.fail(e.toString());
        }
    }
}
