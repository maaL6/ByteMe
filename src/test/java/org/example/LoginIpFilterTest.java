package org.example;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.example.platform.security.LoginIpFilter;
import org.example.platform.security.LoginRateLimiter;
import static org.junit.jupiter.api.Assertions.*;

class LoginIpFilterTest {
    @Test void countsMalformedBodiesBeforeControllerAndDoesNotTrustForwardedIp() throws Exception {
        var filter = new LoginIpFilter(new LoginRateLimiter(Clock.systemUTC(),10,100,900),new ObjectMapper());
        var calls = new AtomicInteger();
        for (int i=0;i<101;i++) {
            var request = new MockHttpServletRequest("POST","/api/auth/token");
            request.setServletPath("/api/auth/token");
            request.setRemoteAddr("127.0.0.9");
            request.addHeader("X-Forwarded-For","192.0.2."+i);
            request.setContent("{bad".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var response = new MockHttpServletResponse();
            filter.doFilter(request,response,(r,s)->calls.incrementAndGet());
            assertEquals(i<100?200:429,response.getStatus());
            if(i==100) {
                assertTrue(Long.parseLong(response.getHeader("Retry-After"))>0);
                assertEquals("no-store",response.getHeader("Cache-Control"));
            }
        }
        assertEquals(100,calls.get());
    }
}
