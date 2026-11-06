package com.flowgate.demo.experiment;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

/** 实验计数观测真实下游入口，异常不能泄漏在途数，观测接口自身不计入业务。 */
class DemoObservationTest {
    @Test void countsActualCallsAndReleasesOnFailure() throws Exception {
        var observation=new DemoObservation();
        var request=new MockHttpServletRequest("GET","/demo/slow");
        observation.doFilter(request,new MockHttpServletResponse(),(req,res)->{
            assertEquals(1,observation.observation().get("inFlight"));
        });
        assertEquals(1L,observation.observation().get("slow"));
        assertEquals(0,observation.observation().get("inFlight"));
        assertThrows(jakarta.servlet.ServletException.class,()->observation.doFilter(new MockHttpServletRequest("GET","/demo/slow"),
                new MockHttpServletResponse(),(req,res)->{throw new jakarta.servlet.ServletException("expected");}));
        assertEquals(0,observation.observation().get("inFlight"));
        observation.doFilter(new MockHttpServletRequest("GET","/demo/observation"),new MockHttpServletResponse(),new MockFilterChain());
        assertEquals(2L,observation.observation().get("slow"));
    }
}
