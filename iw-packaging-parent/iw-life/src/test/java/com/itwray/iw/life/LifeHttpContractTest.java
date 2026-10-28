package com.itwray.iw.life;

import com.itwray.iw.web.core.webmvc.ExceptionHandlerInterceptor;
import com.itwray.iw.web.core.webmvc.GeneralResponseWrapperAdvice;
import com.itwray.iw.web.utils.UserUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** The real MVC response wrapper must receive concrete return types, not Object. */
class LifeHttpContractTest {
    static class Registry extends InterceptorRegistry {
        HandlerInterceptor[] all(){return getInterceptors().stream().map(x->(HandlerInterceptor)x).toArray(HandlerInterceptor[]::new);}
    }
    private MockMvc mvc(LifeToolsService service) {
        Registry registry=new Registry();new LifeAuthenticationConfiguration().addInterceptors(registry);
        return MockMvcBuilders.standaloneSetup(new WishController(service),new BookmarkController(service),new ImportantDateController(service,new LifeDates()))
                .setControllerAdvice(new GeneralResponseWrapperAdvice(),new ExceptionHandlerInterceptor()).addInterceptors(registry.all()).build();
    }
    @AfterEach void clear(){UserUtils.removeUserId();UserUtils.removeUserToken();}
    @Test void missingLoginIs401ForDataAndCalendarMetadata()throws Exception{
        var service=mock(LifeToolsService.class);var mvc=mvc(service);
        mvc.perform(post("/life/wish/page").contentType("application/json").content("{}"))
                .andExpect(jsonPath("$.code").value(401));
        mvc.perform(get("/life/important-date/calendar").param("year","2026"))
                .andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(service);
    }
    @Test void pageAndCalendarUseRealProjectResponseEnvelope()throws Exception{
        var service=mock(LifeToolsService.class);when(service.page(eq(LifeStore.Kind.WISH),any())).thenReturn(Map.of("records",List.of(),"total",0));
        var mvc=mvc(service);
        try(var user=mockStatic(UserUtils.class)) {
            user.when(()->UserUtils.getToken(false)).thenReturn("unit-only-session");user.when(UserUtils::getUserId).thenReturn(1);
            mvc.perform(post("/life/wish/page").contentType("application/json").content("{}"))
                    .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.total").value(0));
            mvc.perform(get("/life/important-date/calendar").param("year","2023"))
                    .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.length()").value(13));
        }
    }
}
