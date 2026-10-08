package org.example;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.example.platform.security.LoginRateLimiter;
import static org.junit.jupiter.api.Assertions.*;
class RateLimiterTest {
    static final class MutableClock extends Clock {
        Instant time=Instant.parse("2026-10-07T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return time;}
    }
    @Test void fixedWindowAndIndependentBuckets() {
        var clock=new MutableClock();var quota=new LoginRateLimiter(clock,10,100,900);
        for(int i=0;i<10;i++)quota.email("a@example.test");
        assertEquals(900,assertThrows(LoginRateLimiter.LimitExceeded.class,()->quota.email("a@example.test")).retryAfter());
        quota.email("b@example.test");for(int i=0;i<100;i++)quota.ip("127.0.0.1");
        assertThrows(LoginRateLimiter.LimitExceeded.class,()->quota.ip("127.0.0.1"));
        clock.time=clock.time.plusSeconds(900);quota.email("a@example.test");quota.ip("127.0.0.1");
    }
    @Test void concurrentRequestsCannotOverrunQuota() throws Exception {
        var quota=new LoginRateLimiter(new MutableClock(),10,100,900);var accepted=new AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(8)) {
            var work=new java.util.ArrayList<Future<?>>();
            for(int i=0;i<40;i++)work.add(pool.submit(()->{try {quota.email("same@example.test");accepted.incrementAndGet();}catch(LoginRateLimiter.LimitExceeded ignored) {}}));
            for(var f:work)f.get(5,TimeUnit.SECONDS);
        }
        assertEquals(10,accepted.get());
    }
}
