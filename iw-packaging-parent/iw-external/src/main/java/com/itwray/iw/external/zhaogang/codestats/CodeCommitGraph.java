package com.itwray.iw.external.zhaogang.codestats;

import com.itwray.iw.external.zhaogang.CodingOpenApiException;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingCommit;
import com.itwray.iw.external.zhaogang.CodingOpenApiPort.CodingCommitPage;
import java.util.*;
import java.util.function.IntFunction;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/** 仓库内共享、只含 SHA/父链及命中标志的图；不保存未命中作者元数据。 */
final class CodeCommitGraph {
    record Node(List<String> parents,boolean matched,Set<String> summarizedMatches,boolean boundary) {
        Node(List<String> parents,boolean matched,Set<String> summarizedMatches) { this(parents,matched,summarizedMatches,false); }
        Node { parents=parents==null?List.of():List.copyOf(parents); summarizedMatches=summarizedMatches==null?Set.of():Set.copyOf(summarizedMatches); }
    }
    record Read(CodeBranchSync.Result result, Map<String,Node> learned, boolean requested) {}
    record Walk(Set<String> visited, Set<String> matched, Set<String> unknown, Set<String> missingMetadata) {}

    static Map<String,Node> initialize(CodeBranchSync.Checkpoint checkpoint) {
        Map<String,Node> graph=new LinkedHashMap<>();
        if(checkpoint==null) return graph;
        if(checkpoint.graph()!=null) checkpoint.graph().forEach((sha,node)->{if(sha!=null && node!=null)graph.put(sha,node);});
        // 旧头已完整统计的命中集合是一段已验证祖先闭包，升级无需重读全部历史。
        if(checkpoint.heads()!=null) checkpoint.heads().forEach((sha,head)-> {
            if(head!=null && head.matched()!=null) graph.putIfAbsent(sha,new Node(List.of(),false,head.matched()));
        });
        return graph;
    }

    static Read read(String head,String through,Map<String,Node> known,Set<String> available,int pageSize,int maxPages,
                     IntFunction<CodingCommitPage> fetch,Predicate<CodingCommit> matches) {
        return read(head,through,known,available,pageSize,maxPages,(ref,page)->fetch.apply(page),matches);
    }

    static Read read(String head,String through,Map<String,Node> known,Set<String> available,int pageSize,int maxPages,
                     BiFunction<String,Integer,CodingCommitPage> fetch,Predicate<CodingCommit> matches) {
        Map<String,Node> learned=new LinkedHashMap<>();
        Map<String,CodingCommit> added=new LinkedHashMap<>();
        Map<String,Integer> pageByRef=new HashMap<>();
        Map<String,Set<String>> ownedByRef=new HashMap<>();
        Walk state=walk(head,known,learned,available,added.keySet());
        if(closed(state)) return result(head,through,state,added,learned,true,true,null,false);
        for(int calls=0;calls<maxPages;calls++) {
            // 直接读取缺失节点的不可变 SHA，既避免重复扫前缀，也避免 ref 在分页期间移动。
            String ref=!state.unknown().isEmpty()?state.unknown().iterator().next():state.missingMetadata().iterator().next();
            int page=pageByRef.merge(ref,1,Integer::sum);
            CodingCommitPage value;
            try { value=fetch.apply(ref,page); }
            catch(CodingOpenApiException failure) {
                return result(head,through,state,added,learned,false,false,
                        failure.isPermissionDenied()?"PERMISSION_DENIED":"REQUEST_FAILED",true);
            }
            Set<String> segmentOwned=ownedByRef.computeIfAbsent(ref,ignored->new LinkedHashSet<>());
            for(CodingCommit commit:value.items()) {
                if(commit.sha()==null || commit.sha().isBlank()) continue;
                boolean owned=matches.test(commit);
                Node prior=learned.containsKey(commit.sha())?learned.get(commit.sha()):known.get(commit.sha());
                if(prior==null) learned.put(commit.sha(),new Node(commit.parents(),owned,Set.of()));
                if(owned) {
                    segmentOwned.add(commit.sha());
                    if(!available.contains(commit.sha())) added.put(commit.sha(),commit);
                }
            }
            state=walk(head,known,learned,available,added.keySet());
            if(closed(state)) return result(head,through,state,added,learned,true,true,null,true);
            if(value.items().size()<pageSize || (value.totalCount()>0 && (long)page*value.pageSize()>=value.totalCount())) {
                // 完整读到该 SHA 的日期范围末尾后，可将其准确的命中集合收敛为闭包。
                // 不把另一条尚未读取的合并父链误当范围外节点；根提交不在日期内时也不丢失范围内祖先。
                learned.put(ref,new Node(List.of(),false,segmentOwned));
                state=walk(head,known,learned,available,added.keySet());
                if(closed(state)) return result(head,through,state,added,learned,true,false,null,true);
                if(state.missingMetadata().contains(ref) && pageByRef.get(ref)>1)
                    return result(head,through,state,added,learned,false,false,"STATE_DATA_MISSING",true);
            }
        }
        return result(head,through,state,added,learned,false,false,null,true);
    }

    private static boolean closed(Walk state) { return state.unknown().isEmpty() && state.missingMetadata().isEmpty(); }
    private static Walk walk(String head,Map<String,Node> known,Map<String,Node> learned,Set<String> available,Set<String> added) {
        Set<String> visited=new LinkedHashSet<>(),matched=new LinkedHashSet<>(),unknown=new LinkedHashSet<>();
        Deque<String> pending=new ArrayDeque<>();pending.add(head);
        while(!pending.isEmpty()) {
            String sha=pending.removeFirst();if(!visited.add(sha))continue;
            Node node=learned.containsKey(sha)?learned.get(sha):known.get(sha);
            if(node==null) { unknown.add(sha);continue; }
            if(node.matched()) matched.add(sha);
            matched.addAll(node.summarizedMatches());pending.addAll(node.parents());
        }
        Set<String> missing=new HashSet<>(matched);missing.removeAll(available);missing.removeAll(added);
        return new Walk(visited,matched,unknown,missing);
    }
    private static Read result(String sha,String through,Walk walk,Map<String,CodingCommit> added,Map<String,Node> learned,
                               boolean complete,boolean incremental,String error,boolean requested) {
        Set<String> frontier=new LinkedHashSet<>();walk.visited().stream().limit(256).forEach(frontier::add);
        return new Read(new CodeBranchSync.Result(new CodeBranchSync.Head(sha,through,frontier,walk.matched()),
                new ArrayList<>(added.values()),complete,incremental,error),learned,requested);
    }
    private CodeCommitGraph() {}
}
