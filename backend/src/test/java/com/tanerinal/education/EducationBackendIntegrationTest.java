package com.tanerinal.education;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tanerinal.education.domain.Product;
import com.tanerinal.education.mapper.ProductMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.session.MapSessionRepository;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.security.test.context.support.WithMockUser;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(EducationBackendIntegrationTest.InMemorySessionConfiguration.class)
class EducationBackendIntegrationTest {
  @TestConfiguration
  static class InMemorySessionConfiguration {
    @Bean
    MapSessionRepository sessionRepository() {
      return new MapSessionRepository(new ConcurrentHashMap<>());
    }
  }
  @Autowired
  MockMvc mvc;

  @Autowired
  ProductMapper products;

  @Autowired
  ObjectMapper objectMapper;

  @Test
  void authenticationProductsOrdersAndLogoutWorkTogether() throws Exception {
    mvc.perform(get("/api/products"))
        .andExpect(status().isUnauthorized());

    CsrfContext csrf = csrfContext();

    mvc.perform(post("/api/auth/login")
            .session(csrf.session()).cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token())
            .contentType("application/json")
            .content("{\"email\":\"demo@example.com\",\"password\":\"demo-password\",\"remember\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.email").value("demo@example.com"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie()
            .exists("EDUCATION_REMEMBER_ME"));

    mvc.perform(get("/api/products").session(csrf.session()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").isNotEmpty());

    Long productId = products.selectList(new LambdaQueryWrapper<Product>().orderByAsc(Product::getId))
        .get(0).getId();
    MvcResult createdOrder = mvc.perform(post("/api/cart/items").session(csrf.session()).cookie(csrf.cookie())
            .header("X-XSRF-TOKEN", csrf.token())
            .contentType("application/json")
            .content("{\"productId\":" + productId + ",\"quantity\":3,\"note\":\"integration test\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.productName").isNotEmpty())
        .andExpect(jsonPath("$.quantity").value(3))
        .andExpect(jsonPath("$.totalPrice").isNumber())
        .andReturn();

    long orderId = objectMapper.readTree(createdOrder.getResponse().getContentAsString()).get("id").asLong();
    mvc.perform(put("/api/cart/items/{orderId}", orderId)
            .session(csrf.session()).cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token())
            .contentType("application/json")
            .content("{\"quantity\":5}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.quantity").value(5));

    mvc.perform(get("/api/cart").session(csrf.session()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].note").value(""))
        .andExpect(jsonPath("$[0].quantity").value(5));

    mvc.perform(put("/api/cart/items/{orderId}", orderId)
            .session(csrf.session()).cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token())
            .contentType("application/json")
            .content("{\"quantity\":0}"))
        .andExpect(status().isBadRequest());

    mvc.perform(get("/api/users").session(csrf.session()))
        .andExpect(status().isForbidden());

    mvc.perform(post("/api/auth/logout").session(csrf.session()).cookie(csrf.cookie())
            .header("X-XSRF-TOKEN", csrf.token()))
        .andExpect(status().isNoContent());

    mvc.perform(get("/api/products").session(csrf.session()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(username = "admin@example.com", roles = "ADMIN")
  void adminCanListUsers() throws Exception {
    mvc.perform(get("/api/users"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].email").isNotEmpty());
  }

  @Test
  void badPasswordAndMissingCsrfAreRejected() throws Exception {
    mvc.perform(post("/api/auth/login")
            .contentType("application/json")
            .content("{\"email\":\"demo@example.com\",\"password\":\"wrong\"}"))
        .andExpect(status().isForbidden());

    CsrfContext csrf = csrfContext();
    mvc.perform(post("/api/auth/login")
            .session(csrf.session()).cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token())
            .contentType("application/json")
            .content("{\"email\":\"demo@example.com\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized());
  }

  private CsrfContext csrfContext() throws Exception {
    MockHttpSession session = new MockHttpSession();
    MvcResult result = mvc.perform(get("/api/auth/csrf").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
        .andReturn();
    String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
    if (cookie == null) {
      throw new IllegalStateException("XSRF-TOKEN cookie was not issued");
    }
    return new CsrfContext(session, cookie, token);
  }

  private record CsrfContext(MockHttpSession session, Cookie cookie, String token) {
  }
}
