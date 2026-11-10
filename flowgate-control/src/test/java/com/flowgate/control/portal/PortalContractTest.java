package com.flowgate.control.portal;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class PortalContractTest {
    @Test void normalizedNamesCannotBypassUniqueLogin(){assertEquals("alice",PortalIdentityService.normalize(" ＡLICE "));assertThrows(RuntimeException.class,()->PortalIdentityService.normalize("a/b"));assertThrows(RuntimeException.class,()->PortalIdentityService.normalize("a"));}
    @Test void v12EventsCannotEnterLegacyConsumer(){var e=new ApiUsageGrantedEvent(12,"USAGE_GRANTED_V12","3c7c32a7-ddb1-4d71-882e-6c09697a2716","7a19563e-717f-44c7-8f70-ef9249c99217",1,2,"202611",1,Instant.parse("2026-11-10T00:00:00Z"));assertEquals(e,ApiUsageGrantedEvent.parse(e.canonical()));assertThrows(IllegalArgumentException.class,()->com.flowgate.control.async.UsageGrantedEvent.parse(e.canonical()));assertThrows(IllegalArgumentException.class,()->ApiUsageGrantedEvent.parse(e.canonical().replace("202611","202612")));assertThrows(IllegalArgumentException.class,()->ApiUsageGrantedEvent.parse(e.canonical().replace("USAGE_GRANTED_V12","USAGE_GRANTED")));}
}
