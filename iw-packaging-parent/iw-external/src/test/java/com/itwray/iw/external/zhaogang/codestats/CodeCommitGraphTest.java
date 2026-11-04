package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.zhaogang.CodingOpenApiPort.*;
import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CodeCommitGraphTest {
    CodingCommit c(String sha,String... parents) { return new CodingCommit(sha,sha,"self","",1,"",List.of(parents)); }
    CodingCommitPage page(CodingCommit... items) { return new CodingCommitPage(List.of(items),100,1,2); }
    @Test void siblingForkReadsOnlyNewPageAndDoesNotBorrowUnreachableOwnCommit() {
        Map<String,CodeCommitGraph.Node> graph=new HashMap<>();
        graph.put("base",new CodeCommitGraph.Node(List.of(),false,Set.of()));
        graph.put("other",new CodeCommitGraph.Node(List.of("base"),true,Set.of()));
        AtomicInteger calls=new AtomicInteger();
        var result=CodeCommitGraph.read("new","2026-11-03",graph,Set.of("other"),2,100,p->{
            calls.incrementAndGet();return page(c("new","base"),c("base"));
        },v->true);
        assertEquals(1,calls.get());assertTrue(result.result().complete());
        assertEquals(Set.of("new"),result.result().head().matched());
        assertFalse(result.result().head().matched().contains("other"));
    }
    @Test void knownAncestorHeadNeedsNoRequestEvenForNewBranch() {
        var graph=Map.of("root",new CodeCommitGraph.Node(List.of(),true,Set.of()));
        var result=CodeCommitGraph.read("root","2026-11-03",graph,Set.of("root"),2,100,p->{throw new AssertionError("unexpected API read");},v->true);
        assertFalse(result.requested());assertEquals(Set.of("root"),result.result().head().matched());
    }
    @Test void mergeWalksBothParentsAndAddsOnlyReachableOwnHistory() {
        var graph=Map.of("a",new CodeCommitGraph.Node(List.of(),true,Set.of()),"b",new CodeCommitGraph.Node(List.of(),true,Set.of()));
        var result=CodeCommitGraph.read("m","2026-11-03",graph,Set.of("a","b"),2,100,p->page(c("m","a","b")),v->true);
        assertTrue(result.result().complete());assertEquals(Set.of("m","a","b"),result.result().head().matched());
    }
    @Test void legacyHeadSummaryCanBeReusedWithoutDownloadingHistoricalParents() {
        var prior=new CodeBranchSync.Checkpoint(Map.of("main","a"),Map.of("a",new CodeBranchSync.Head("a","2026-11-02",Set.of("a"),Set.of("old-own"))));
        var result=CodeCommitGraph.read("new","2026-11-03",CodeCommitGraph.initialize(prior),Set.of("old-own"),2,100,p->page(c("new","a")),v->true);
        assertTrue(result.result().complete());assertEquals(Set.of("new","old-own"),result.result().head().matched());
    }
    @Test void failedLaterPageCannotConvertUnknownAncestorToEmptyBoundary() {
        var calls=new AtomicInteger();
        var result=CodeCommitGraph.read("a","2026-11-03",Map.of(),Set.of(),2,10,p->{
            if(calls.incrementAndGet()>1)throw new CodingOpenApiException("REQUEST_FAILED","fixture");return page(c("a","b"),c("b","unknown"));
        },v->true);
        assertFalse(result.result().complete());assertEquals(2,result.result().added().size());
        assertFalse(result.learned().containsKey("unknown"));
    }
    @Test void missingMatchedMetadataIsRefetchedRatherThanReportedAsZero() {
        var graph=Map.of("a",new CodeCommitGraph.Node(List.of(),true,Set.of()));
        AtomicInteger calls=new AtomicInteger();
        var result=CodeCommitGraph.read("a","2026-11-03",graph,Set.of(),2,10,p->{calls.incrementAndGet();return page(c("a"));},v->true);
        assertEquals(1,calls.get());assertTrue(result.result().complete());assertEquals(1,result.result().added().size());
    }
    @Test void graphAndHeadSummarySurviveCompressedRoundTrip() throws Exception {
        var original=new CodeBranchSync.Checkpoint(Map.of("main","a"),Map.of(),Map.of("a",new CodeCommitGraph.Node(List.of(),true,Set.of())));
        var decoded=CodeSyncCheckpointCodec.decode(CodeSyncCheckpointCodec.encode(original));
        assertEquals(original,decoded);
    }
    @Test void missingAncestorIsFetchedByItsShaWithoutRereadingKnownPrefix() {
        List<String> refs=new ArrayList<>();
        var result=CodeCommitGraph.read("m","2026-11-03",Map.of(),Set.of(),2,20,(ref,page)->{
            refs.add(ref);
            if(ref.equals("m")) return page(c("m","a","b"),c("a","c"));
            return page(c(ref));
        },v->true);
        assertTrue(result.result().complete());assertEquals(Set.of("m","a","b","c"),result.result().head().matched());
        assertEquals(List.of("m","b","c"),refs);
    }
    @Test void filteredOutHeadStillIncludesEligibleAncestorsReturnedByTheFullRange() {
        var result=CodeCommitGraph.read("outside","2026-11-03",Map.of(),Set.of(),2,20,
                (ref,page)->page(c("inside")),v->true);
        assertTrue(result.result().complete());assertEquals(Set.of("inside"),result.result().head().matched());
        assertEquals(1,result.result().added().size());
    }
    @Test void forcePushCannotRetainUnreachableOwnRecordsFromAnotherHead() {
        var known=Map.of("old",new CodeCommitGraph.Node(List.of(),true,Set.of()),"base",new CodeCommitGraph.Node(List.of(),true,Set.of()));
        var result=CodeCommitGraph.read("new","2026-11-03",known,Set.of("old","base"),2,10,
                (ref,page)->page(c("new","base")),v->true);
        assertEquals(Set.of("new","base"),result.result().head().matched());assertFalse(result.result().head().matched().contains("old"));
    }

}
