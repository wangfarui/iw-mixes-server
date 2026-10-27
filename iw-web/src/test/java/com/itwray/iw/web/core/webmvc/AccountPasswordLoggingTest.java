package com.itwray.iw.web.core.webmvc;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class AccountPasswordLoggingTest {
    @Test void accountPasswordResponseIsPreservedWithoutBodyLogging() throws Exception {
        Logger logger=(Logger)LoggerFactory.getLogger(ResponseLoggingFilter.class);
        ListAppender<ILoggingEvent> capture=new ListAppender<>(); capture.start(); logger.addAppender(capture);
        try {
            MockHttpServletRequest request=new MockHttpServletRequest("GET","/application/account/viewPassword");
            MockHttpServletResponse response=new MockHttpServletResponse();
            new ResponseLoggingFilter().doFilter(request,response,(req,res)->res.getWriter().write("test-only-secret"));
            assertEquals("test-only-secret",response.getContentAsString());
            assertTrue(capture.list.isEmpty());
        } finally { logger.detachAppender(capture); capture.stop(); }
    }
    @Test void accountInputReachesControllerWithoutBodyLogging() throws Exception {
        Logger logger=(Logger)LoggerFactory.getLogger(RequestLoggingFilter.class);
        ListAppender<ILoggingEvent> capture=new ListAppender<>(); capture.start(); logger.addAppender(capture);
        try {
            MockHttpServletRequest request=new MockHttpServletRequest("POST","/auth-service/application/account/add");
            request.setContent("test-only-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            new RequestLoggingFilter().doFilter(request,new MockHttpServletResponse(),(req,res)->
                    assertEquals("test-only-secret",new String(req.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));
            assertTrue(capture.list.isEmpty());
        } finally { logger.detachAppender(capture); capture.stop(); }
    }
}
