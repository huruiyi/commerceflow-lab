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

    String addressJson = """
        {"recipientName":"Demo Student","phone":"13800000000","countryCode":"CN",
         "stateProvince":"Shanghai","city":"Shanghai","district":"Pudong",
         "addressLine1":"100 Learning Road","addressLine2":"Room 2022",
         "postalCode":"200120","label":"Home","isDefault":false}
        """;
    MvcResult createdAddress = mvc.perform(post("/api/addresses").session(csrf.session()).cookie(csrf.cookie())
            .header("X-XSRF-TOKEN", csrf.token()).contentType("application/json").content(addressJson))
        .andExpect(status().isOk()).andExpect(jsonPath("$.isDefault").value(true)).andReturn();
    long addressId = objectMapper.readTree(createdAddress.getResponse().getContentAsString()).get("id").asLong();
    mvc.perform(put("/api/addresses/{id}/select", addressId).session(csrf.session()).cookie(csrf.cookie())
            .header("X-XSRF-TOKEN", csrf.token()).contentType("application/json").content("{}"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/addresses/selected").session(csrf.session()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.addressId").value(addressId));

    MvcResult checkout = mvc.perform(post("/api/purchase-orders/checkout").session(csrf.session()).cookie(csrf.cookie())
            .header("X-XSRF-TOKEN", csrf.token()).contentType("application/json").content("{}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
        .andExpect(jsonPath("$.items[0].quantity").value(5)).andExpect(jsonPath("$.address.label").value("Home"))
        .andReturn();
    long purchaseOrderId = objectMapper.readTree(checkout.getResponse().getContentAsString()).get("id").asLong();
    mvc.perform(get("/api/cart").session(csrf.session())).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());

    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/addresses/{id}", addressId)
            .session(csrf.session()).cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/purchase-orders/{id}", purchaseOrderId).session(csrf.session()))
        .andExpect(status().isOk()).andExpect(jsonPath("$.address.addressLine1").value("100 Learning Road"));

    String failedPayment = "{\"idempotencyKey\":\"test-failed-payment\",\"simulateFailure\":true}";
    MvcResult failed = mvc.perform(post("/api/purchase-orders/{id}/payments", purchaseOrderId)
            .session(csrf.session()).cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token())
            .contentType("application/json").content(failedPayment))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
        .andExpect(jsonPath("$.payment.status").value("FAILED")).andReturn();
    String failedPaymentNumber = objectMapper.readTree(failed.getResponse().getContentAsString()).path("payment").path("paymentNumber").asText();
    mvc.perform(post("/api/purchase-orders/{id}/payments", purchaseOrderId)
            .session(csrf.session()).cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token())
            .contentType("application/json").content(failedPayment))
        .andExpect(status().isOk()).andExpect(jsonPath("$.payment.paymentNumber").value(failedPaymentNumber));

    mvc.perform(post("/api/purchase-orders/{id}/payments", purchaseOrderId)
            .session(csrf.session()).cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token())
            .contentType("application/json")
            .content("{\"idempotencyKey\":\"test-success-payment\",\"simulateFailure\":false}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"))
        .andExpect(jsonPath("$.payment.status").value("SUCCESS"));
    mvc.perform(post("/api/purchase-orders/{id}/payments", purchaseOrderId)
            .session(csrf.session()).cookie(csrf.cookie()).header("X-XSRF-TOKEN", csrf.token())
            .contentType("application/json")
            .content("{\"idempotencyKey\":\"another-payment\",\"simulateFailure\":false}"))
        .andExpect(status().isConflict());

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
