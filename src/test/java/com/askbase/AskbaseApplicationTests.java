package com.askbase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端冒烟测试：上下文能起、文档能开、认证链路能走通。
 *
 * <p>这几条用例是基础链路的冒烟测试。它们跑绿了，说明地基是好的；
 * 后面加功能时如果它们红了，说明你改坏了基础链路——比手工点页面可靠得多。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AskbaseApplicationTests {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void healthEndpointIsPublic() throws Exception {
        JsonNode body = getJson("/api/health");
        assertThat(body.get("code").asInt()).isZero();
        assertThat(body.get("data").get("status").asText()).isEqualTo("UP");
    }

    @Test
    void openApiDocsAreReachable() {
        // 这条用例专门守住 springdoc 与 Knife4j 的版本兼容。
        // Boot 3.5 上直接引入 Knife4j 4.5 会因自带 springdoc 2.3 而报 NoSuchMethodError，
        // 表现为 /v3/api-docs 返回 500。靠这条用例第一时间发现，而不是等打开页面才发现。
        ResponseEntity<String> resp = rest.getForEntity("/v3/api-docs", String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).contains("Askbase API");
    }

    @Test
    void protectedEndpointRejectsAnonymous() throws Exception {
        JsonNode body = getJson("/api/auth/me");
        assertThat(body.get("code").asInt()).isEqualTo(40100);
    }

    @Test
    void loginThenCallProtectedEndpoint() throws Exception {
        String token = login("admin", "admin123");

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> resp = rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("code").asInt()).isZero();
        assertThat(body.get("data").get("username").asText()).isEqualTo("admin");
    }

    @Test
    void loginWithWrongPasswordFails() throws Exception {
        ResponseEntity<String> resp = rest.postForEntity("/api/auth/login",
                Map.of("username", "admin", "password", "wrong-password"), String.class);
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("code").asInt()).isEqualTo(40100);
    }

    @Test
    void malformedTokenIsRejected() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("this-is-not-a-jwt");
        ResponseEntity<String> resp = rest.exchange("/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("code").asInt()).isEqualTo(40100);
    }

    private JsonNode getJson(String path) throws Exception {
        return objectMapper.readTree(rest.getForEntity(path, String.class).getBody());
    }

    private String login(String username, String password) throws Exception {
        ResponseEntity<String> resp = rest.postForEntity("/api/auth/login",
                Map.of("username", username, "password", password), String.class);
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.get("code").asInt()).as("登录应成功: " + resp.getBody()).isZero();
        return body.get("data").get("token").asText();
    }
}
