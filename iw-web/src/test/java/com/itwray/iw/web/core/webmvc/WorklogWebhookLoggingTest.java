package com.itwray.iw.web.core.webmvc;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class WorklogWebhookLoggingTest {
    @Test void webhookPayloadReachesControllerButIsNeverLogged() throws Exception {
        Logger logger=(Logger)LoggerFactory.getLogger(RequestLoggingFilter.class);
        var capture=new ListAppender<ILoggingEvent>();capture.start();logger.addAppender(capture);
        try {
            var request=new MockHttpServletRequest("PUT","/external-service/api/zhaogang/teams/1/worklog-notification");
            String body="{\"webhook\":\"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=test-only-secret\"}";
            request.setContent(body.getBytes(StandardCharsets.UTF_8));
            new RequestLoggingFilter().doFilter(request,new MockHttpServletResponse(),(req,res)->assertEquals(body,new String(req.getInputStream().readAllBytes(),StandardCharsets.UTF_8)));
            assertTrue(capture.list.isEmpty());
        } finally {logger.detachAppender(capture);capture.stop();}
    }
}
