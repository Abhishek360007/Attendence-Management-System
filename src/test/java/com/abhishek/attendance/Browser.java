package com.abhishek.attendance;

import jakarta.servlet.http.Cookie;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Behaves like the dashboard in a browser: keeps cookies between requests and echoes the XSRF-TOKEN cookie
 * back as the X-XSRF-TOKEN header.
 */
public class Browser {

    private final MockMvc mvc;
    private final Map<String, Cookie> cookies = new LinkedHashMap<>();

    Browser(MockMvc mvc) {
        this.mvc = mvc;
    }

    public ResultActions get(String url, Object... vars) throws Exception {
        return send(MockMvcRequestBuilders.get(url, vars), true);
    }

    public ResultActions post(String url, String json, Object... vars) throws Exception {
        return send(json(MockMvcRequestBuilders.post(url, vars), json), true);
    }

    public ResultActions put(String url, String json, Object... vars) throws Exception {
        return send(json(MockMvcRequestBuilders.put(url, vars), json), true);
    }

    public ResultActions delete(String url, Object... vars) throws Exception {
        return send(MockMvcRequestBuilders.delete(url, vars), true);
    }

    /** Sends a POST without the CSRF header, to check that it is rejected. */
    public ResultActions postWithoutCsrf(String url, String json) throws Exception {
        return send(json(MockMvcRequestBuilders.post(url), json), false);
    }

    /** Signs in, failing the test if the credentials are rejected. */
    public Browser login(String email, String password) throws Exception {
        post("/api/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password))
                .andExpect(status().isOk());
        return this;
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body == null ? "{}" : body);
    }

    private ResultActions send(MockHttpServletRequestBuilder request, boolean withCsrf) throws Exception {
        if (withCsrf && !cookies.containsKey("XSRF-TOKEN")) {
            // First visit: any request hands out the CSRF cookie.
            absorb(mvc.perform(withCookies(MockMvcRequestBuilders.get("/api/auth/setup"))));
        }
        Cookie xsrf = cookies.get("XSRF-TOKEN");
        if (withCsrf && xsrf != null) {
            request.header("X-XSRF-TOKEN", xsrf.getValue());
        }
        return absorb(mvc.perform(withCookies(request)));
    }

    private MockHttpServletRequestBuilder withCookies(MockHttpServletRequestBuilder request) {
        if (!cookies.isEmpty()) {
            request.cookie(cookies.values().toArray(Cookie[]::new));
        }
        return request;
    }

    private ResultActions absorb(ResultActions result) {
        for (Cookie c : result.andReturn().getResponse().getCookies()) {
            if (c.getMaxAge() == 0) {
                cookies.remove(c.getName());
            } else {
                cookies.put(c.getName(), c);
            }
        }
        return result;
    }
}
