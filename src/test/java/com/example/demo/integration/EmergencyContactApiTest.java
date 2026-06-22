package com.example.demo.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 紧急联系人接口测试（TC-EC-01 ~ 03）
 *
 * 覆盖本次修复：
 *   - 本人读取电话返回明文（不再掩码）
 *   - PUT 更新 PATCH 语义（未传 phone 保留原值）
 *   - 新增场景 phone 必填
 */
class EmergencyContactApiTest extends BaseIntegrationTest {

    private static final String RAW_PHONE = "13800000099";

    /** 创建联系人并返回其 id */
    private Long createContact(String token, Long userId, String phone) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", "Bearer " + token);
        String body = String.format(
                "{\"name\":\"张三\",\"phone\":\"%s\",\"relationship\":\"家人\",\"isPrimary\":true}", phone);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/users/" + userId + "/emergency-contacts",
                HttpMethod.POST, new HttpEntity<>(body, h), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        try {
            return testHelper.extractJson(resp.getBody()).get("id").asLong();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode listContacts(String token, Long userId) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + token);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/users/" + userId + "/emergency-contacts",
                HttpMethod.GET, new HttpEntity<>(h), String.class);
        try {
            return testHelper.extractJson(resp.getBody());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== TC-EC-01 ====================

    /** TC-EC-01：本人读取电话返回明文（非 138****0099） */
    @Test
    @DisplayName("TC-EC-01: 本人读取紧急联系人电话为明文")
    void tcEC01_phoneReturnedInPlaintext_forOwner() {
        String token = testHelper.registerAndLoginWithRole("13800090001", "BLIND");
        Long userId = testHelper.extractUserId(token);
        // BaseIntegrationTest/registerAndLoginWithRole 会自动建一个紧急联系人，这里额外建一个用 RAW_PHONE
        createContact(token, userId, RAW_PHONE);

        JsonNode arr = listContacts(token, userId);
        boolean hasRaw = false;
        for (JsonNode node : arr) {
            if (RAW_PHONE.equals(node.get("phone").asText())) {
                hasRaw = true;
                break;
            }
        }
        assertThat(hasRaw)
                .as("本人读取应返回明文电话 %s，而非掩码形式", RAW_PHONE)
                .isTrue();

        System.out.println("✅ TC-EC-01 passed — 本人读取电话明文返回");
    }

    // ==================== TC-EC-02 ====================

    /** TC-EC-02：PUT 更新不传 phone → 保留原值（PATCH 语义） */
    @Test
    @DisplayName("TC-EC-02: PUT 更新未传 phone 保留原值")
    void tcEC02_putWithoutPhoneKeepsOriginal() {
        String token = testHelper.registerAndLoginWithRole("13800090002", "BLIND");
        Long userId = testHelper.extractUserId(token);
        Long contactId = createContact(token, userId, RAW_PHONE);

        // 只改 name，不传 phone
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", "Bearer " + token);
        String body = "{\"name\":\"李四\"}";
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/users/" + userId + "/emergency-contacts/" + contactId,
                HttpMethod.PUT, new HttpEntity<>(body, h), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 再次列表查询，phone 应仍为原值
        JsonNode arr = listContacts(token, userId);
        String phoneAfter = null;
        for (JsonNode node : arr) {
            if (node.get("id").asLong() == contactId) {
                phoneAfter = node.get("phone").asText();
                break;
            }
        }
        assertThat(phoneAfter).isEqualTo(RAW_PHONE);

        System.out.println("✅ TC-EC-02 passed — 未传 phone 保留原值");
    }

    // ==================== TC-EC-03 ====================

    /** TC-EC-03：新增联系人未传 phone → 400（手动校验，非 201） */
    @Test
    @DisplayName("TC-EC-03: 新增联系人电话为空返回400")
    void tcEC03_addWithoutPhoneReturns400() {
        String token = testHelper.registerAndLoginWithRole("13800090003", "BLIND");
        Long userId = testHelper.extractUserId(token);

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Authorization", "Bearer " + token);
        // 只传 name，不传 phone
        String body = "{\"name\":\"王五\"}";
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/users/" + userId + "/emergency-contacts",
                HttpMethod.POST, new HttpEntity<>(body, h), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        System.out.println("✅ TC-EC-03 passed — 新增联系人电话为空返回400");
    }
}
