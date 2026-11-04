package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.zhaogang.CodingOpenApiPort.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CodeBranchSyncTest {
    CodingCommit c(String sha, String... parents) { return new CodingCommit(sha,sha,"author","",1,"",List.of(parents)); }
    CodingCommitPage page(CodingCommit... rows) { return new CodingCommitPage(List.of(rows),100,1,2); }
    CodeBranchSync.Head old() { return new CodeBranchSync.Head("b","2026-11-02",Set.of("b","a"),Set.of("b")); }

    @Test void fastForwardStopsAtClosedParentFrontierAndReusesMatchedHistory() {
        AtomicInteger calls=new AtomicInteger();
        var result=CodeBranchSync.read("c","2026-11-03",old(),2,10,p->{calls.incrementAndGet();return page(c("c","b"),c("b","a"));},v->true);
        assertTrue(result.incremental()); assertTrue(result.complete()); assertEquals(1,calls.get());
        assertEquals(Set.of("b","c"),result.head().matched());assertEquals(1,result.added().size());
    }
    @Test void mergeMustReadOtherParentEvenAfterReachingOldHead() {
        AtomicInteger calls=new AtomicInteger();
        var result=CodeBranchSync.read("m","2026-11-03",old(),2,10,p->{calls.incrementAndGet();
            return p==1?page(c("m","b","s"),c("b","a")):page(c("s","a"));},v->true);
        assertTrue(result.incremental()); assertEquals(2,calls.get());
        assertEquals(Set.of("m","s","b"),result.head().matched());
    }
    @Test void forcePushRebuildsReachableSetInsteadOfRetainingUnreachableOldCommits() {
        AtomicInteger calls=new AtomicInteger();
        var result=CodeBranchSync.read("x","2026-11-03",old(),2,10,p->{calls.incrementAndGet();return page(c("x","a"));},v->true);
        assertFalse(result.incremental()); assertTrue(result.complete());assertEquals(1,calls.get());
        assertEquals(Set.of("x"),result.head().matched());assertFalse(result.head().reachable().contains("b"));
    }
    @Test void keepsOnlyMatchedMetadataButTracksUnmatchedParentHashes() {
        var result=CodeBranchSync.read("m","2026-11-03",null,2,10,p->page(c("m")),v->false);
        assertTrue(result.complete()); assertEquals(Set.of("m"),result.head().reachable());
        assertTrue(result.added().isEmpty()); assertTrue(result.head().matched().isEmpty());
    }
    @Test void truncatedReadNeverPublishesACompleteCheckpoint() {
        var result=CodeBranchSync.read("z","2026-11-03",null,2,1,p->page(c("z","y"),c("y","x")),v->true);
        assertFalse(result.complete());
    }
    @Test void failedLaterPageRetainsSuccessfulMatchedMetadataWithoutPublishingCheckpoint() {
        var result=CodeBranchSync.read("z","2026-11-03",null,2,10,p->{
            if(p>1) throw new com.itwray.iw.external.zhaogang.CodingOpenApiException("REQUEST_FAILED","fixture");
            return page(c("z","y"),c("y","x"));
        },v->true);
        assertFalse(result.complete());assertEquals("REQUEST_FAILED",result.errorCode());assertEquals(2,result.added().size());
    }

}
