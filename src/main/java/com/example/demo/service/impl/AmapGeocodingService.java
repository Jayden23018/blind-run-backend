package com.example.demo.service.impl;

import com.example.demo.service.GeocodingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * 高德地图逆地理编码实现。
 *
 * <p>紧急场景下的稳健性约定：
 * <ul>
 *   <li>连接 / 请求超时 2 秒（紧急短信不能久等）</li>
 *   <li>未配置 key、HTTP 非 200、业务 status != "1"、无结果、任何异常 → 一律返回 empty 触发降级</li>
 * </ul>
 * key 未配置时直接返回 empty，因此本服务可在拿到高德 key 之前先行上线（短信走可读经纬度降级）。
 */
@Slf4j
@Service
public class AmapGeocodingService implements GeocodingService {

    private static final String REGEO_URL = "https://restapi.amap.com/v3/geocode/regeo";
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final String amapKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AmapGeocodingService(@Value("${amap.web.key:}") String amapKey,
                                ObjectMapper objectMapper) {
        this.amapKey = amapKey;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
    }

    @Override
    public Optional<String> reverseGeocode(BigDecimal lat, BigDecimal lng) {
        if (amapKey == null || amapKey.isBlank() || lat == null || lng == null) {
            return Optional.empty();
        }
        try {
            // 高德 location 参数格式：经度,纬度
            String location = lng.toPlainString() + "," + lat.toPlainString();
            String url = REGEO_URL
                    + "?key=" + URLEncoder.encode(amapKey, StandardCharsets.UTF_8)
                    + "&location=" + URLEncoder.encode(location, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("高德逆地理编码 HTTP 状态异常: {}", response.statusCode());
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(response.body());
            if (!"1".equals(root.path("status").asText())) {
                log.warn("高德逆地理编码业务失败: {}", root.path("info").asText());
                return Optional.empty();
            }

            // 高德无结果时 formatted_address 返回空数组 []，仅当为非空文本时采用
            JsonNode addressNode = root.path("regeocode").path("formatted_address");
            if (addressNode.isTextual() && !addressNode.asText().isBlank()) {
                return Optional.of(addressNode.asText());
            }
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("高德逆地理编码被中断，降级处理");
            return Optional.empty();
        } catch (Exception e) {
            // 紧急场景：任何异常（含超时）都降级，绝不向上抛
            log.warn("高德逆地理编码调用失败，降级处理: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
