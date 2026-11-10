package com.tanerinal.education;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest(properties = {
    "spring.kafka.listener.auto-startup=false",
    "education.outbox.delay=3600000",
    "education.reconciliation.delay=3600000"
})
@AutoConfigureMockMvc
@Import(EducationBackendIntegrationTest.TestSessions.class)
class EducationBackendIntegrationTest {

  @TestConfiguration
  static class TestSessions {
    @Bean
    MapSessionRepository sessionRepository() {
      return new MapSessionRepository(new ConcurrentHashMap<>());
    }
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  @Test
  void publicCatalogSupportsLocalesAndAuthenticationReturnsStableErrors() throws Exception {
    mvc.perform(get("/api/products?lang=en")).andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Learning Laptop"));
    mvc.perform(get("/api/products?lang=zh-CN")).andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("学习笔记本电脑"));
    mvc.perform(post("/api/auth/login").contentType("application/json")
            .content("{\"email\":\"demo@example.com\",\"password\":\"demo-password\",\"remember\":false}"))
        .andExpect(status().isForbidden());
    CsrfContext csrf = csrfContext();
    mvc.perform(post("/api/auth/login").session(csrf.session()).cookie(csrf.cookie())
            .header("X-XSRF-TOKEN", csrf.token()).contentType("application/json")
            .content("{\"email\":\"demo@example.com\",\"password\":\"wrong-password\",\"remember\":false}"))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_INVALID"));
  }

  @Test
  void customerCartDeduplicationQuantityRemovalAndAuthorizationWork() throws Exception {
    CsrfContext csrf = login("demo@example.com", true);
    mvc.perform(get("/api/auth/me").session(csrf.session())).andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("CUSTOMER"));
    mvc.perform(post("/api/cart/items").session(csrf.session()).cookie(csrf.cookie())
            .header("X-XSRF-TOKEN", csrf.token()).contentType("application/json")
            .content("{\"productId\":1,\"quantity\":2,\"note\":\"first\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.quantity").value(2));
    MvcResult merged = mvc.perform(post("/api/cart/items").session(csrf.session()).cookie(csrf.cookie())
            .header("X-XSRF-TOKEN", csrf.token()).contentType("application/json")
            .content("{\"productId\":1,\"quantity\":3,\"note\":\"merged\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.quantity").value(5)).andReturn();
    long itemId = json.readTree(merged.getResponse().getContentAsString()).get("id").asLong();
    mvc.perform(get("/api/cart").session(csrf.session())).andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
    mvc.perform(put("/api/cart/items/{id}", itemId).session(csrf.session()).cookie(csrf.cookie())
            .header("X-XSRF-TOKEN", csrf.token()).contentType("application/json")
            .content("{\"quantity\":0}"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/merchant/orders").session(csrf.session())).andExpect(status().isForbidden());
    mvc.perform(get("/api/users").session(csrf.session())).andExpect(status().isForbidden());
    mvc.perform(delete("/api/cart/items/{id}", itemId).session(csrf.session()).cookie(csrf.cookie())
            .header("X-XSRF-TOKEN", csrf.token()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/cart").session(csrf.session())).andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void adminCanListUsers() throws Exception {
    CsrfContext csrf = login("admin@example.com", false);
    mvc.perform(get("/api/users").session(csrf.session())).andExpect(status().isOk())
        .andExpect(jsonPath("$[0].email").isNotEmpty());
  }

  private CsrfContext login(String email, boolean rememberMe) throws Exception {
    CsrfContext csrf = csrfContext();
    var loginResult = mvc.perform(post("/api/auth/login").session(csrf.session()).cookie(csrf.cookie())
            .header("X-XSRF-TOKEN", csrf.token()).contentType("application/json")
            .content("{\"email\":\"" + email
                + "\",\"password\":\"demo-password\",\"remember\":" + rememberMe + "}"))
        .andExpect(status().isOk());
    if (rememberMe) loginResult.andExpect(cookie().exists("EDUCATION_REMEMBER_ME"));
    return csrf;
  }

  private CsrfContext csrfContext() throws Exception {
    MockHttpSession session = new MockHttpSession();
    MvcResult result = mvc.perform(get("/api/auth/csrf").session(session))
        .andExpect(status().isOk()).andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
        .andReturn();
    String token = json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
    if (cookie == null) throw new IllegalStateException("XSRF-TOKEN cookie was not issued");
    return new CsrfContext(session, cookie, token);
  }

  private record CsrfContext(MockHttpSession session, Cookie cookie, String token) {}
}
