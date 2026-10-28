package com.itwray.iw.life;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itwray.iw.web.exception.BusinessException;
import com.itwray.iw.web.utils.UserUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static com.itwray.iw.life.LifeStore.Kind;

@Service
public class LifeToolsService {
    private final LifeStore store;
    private final LifeDates dates;
    private final ObjectMapper json;
    public LifeToolsService(LifeStore store,LifeDates dates,ObjectMapper json) {this.store=store;this.dates=dates;this.json=json;}
    public Map<String,Object> page(Kind kind,LifeRequests.Page query) {
        List<Map<String,Object>> all=store.rows(kind,UserUtils.getUserId());
        String keyword=Objects.toString(query.getKeyword(),"").trim().toLowerCase(Locale.ROOT);
        if(kind==Kind.DATE)all.forEach(dates::decorate);
        String status=choice(query.getStatus(),Set.of("PENDING","FULFILLED","ARCHIVED"),"PENDING");
        String category=query.getCategory()==null||query.getCategory().isBlank()?null:choice(query.getCategory(),Set.of("WEBSITE","ARTICLE","VIDEO","OTHER"),"OTHER");
        String view=choice(query.getView(),Set.of("UPCOMING","ALL"),"UPCOMING");
        List<Map<String,Object>> selected=all.stream().filter(row->{
            if(!keyword.isEmpty()&&!((String)row.get("title")+" "+Objects.toString(row.get("remark"),"")+" "+Objects.toString(row.get("url"),"")).toLowerCase(Locale.ROOT).contains(keyword))return false;
            if(kind==Kind.WISH&&!status.equals(row.get("status")))return false;
            if(kind==Kind.BOOKMARK&&category!=null&&!category.equals(row.get("category")))return false;
            if(kind==Kind.DATE&&"UPCOMING".equals(view)){Long days=(Long)row.get("daysUntil");return days!=null&&days>=0&&days<=30;}
            return true;
        }).sorted(comparator(kind)).toList();
        long start=((long)query.getCurrentPage()-1)*query.getPageSize();
        int from=(int)Math.min(start,selected.size()),to=(int)Math.min(start+query.getPageSize(),selected.size());
        Map<String,Object> result=new LinkedHashMap<>();result.put("current",query.getCurrentPage());result.put("size",query.getPageSize());result.put("total",selected.size());
        result.put("records",selected.subList(from,to).stream().map(this::publicRow).toList());result.put("serverToday",dates.today());
        if(kind==Kind.WISH)result.put("pendingCount",all.stream().filter(r->"PENDING".equals(r.get("status"))).count());
        return result;
    }
    private Comparator<Map<String,Object>> comparator(Kind kind) {
        Comparator<Map<String,Object>> newest=Comparator.comparingInt((Map<String,Object> r)->((Number)r.get("id")).intValue()).reversed();
        if(kind==Kind.WISH)return Comparator.comparingInt((Map<String,Object> r)->"HIGH".equals(r.get("priority"))?0:1)
                .thenComparing(r->(LocalDate)r.get("targetDate"),Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(newest);
        if(kind==Kind.BOOKMARK)return Comparator.comparingInt((Map<String,Object> r)->truth(r.get("pinned"))?0:1).thenComparing(newest);
        return Comparator.comparingInt((Map<String,Object> r)->"UPCOMING".equals(r.get("occurrenceStatus"))?0:"EXPIRED".equals(r.get("occurrenceStatus"))?1:2)
                .thenComparing(r->(LocalDate)r.get("nextOccurrenceDate"),Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(newest);
    }
    public Map<String,Object> detail(Kind kind,int id) {return publicRow(owned(kind,id));}
    private Map<String,Object> owned(Kind kind,int id) {
        Map<String,Object> row=store.find(kind,UserUtils.getUserId(),id);
        if(row==null)throw new BusinessException("记录不存在或无权访问");
        if(kind==Kind.DATE)dates.decorate(row);
        return row;
    }
    @Transactional
    public Map<String,Object> add(Kind kind,LifeRequests.Write input) {
        Map<String,Object> values=values(kind,input);
        String requestId=input.getClientRequestId();
        if(requestId==null||!requestId.matches("[A-Za-z0-9_-]{1,64}"))throw new BusinessException("新增请求标识无效，请重新进入页面");
        int user=UserUtils.getUserId();String hash=hash(values);
        Map<String,Object> old=store.request(kind,user,requestId,false);
        if(old!=null)return repeated(old,hash);
        values.put("user_id",user);values.put("client_request_id",requestId);values.put("create_hash",hash);
        int id;
        try {id=store.insert(kind,values);}catch(DuplicateKeyException ex){old=store.request(kind,user,requestId,true);if(old==null)throw ex;return repeated(old,hash);}
        return Map.of("id",id,"revision",1);
    }
    private Map<String,Object> repeated(Map<String,Object> old,String hash) {
        if(truth(old.get("deleted")))throw new BusinessException("该新增请求对应记录已删除，请重新进入新增页面");
        if(!hash.equals(old.get("createHash")))throw new BusinessException("该请求已保存其他内容，请重新进入新增页面");
        return Map.of("id",old.get("id"),"revision",old.get("revision"));
    }
    @Transactional
    public Map<String,Object> update(Kind kind,LifeRequests.Write input) {
        requireVersion(input.getId(),input.getRevision());owned(kind,input.getId());
        write(kind,input.getId(),input.getRevision(),values(kind,input));
        return Map.of("revision",input.getRevision()+1);
    }
    @Transactional
    public void delete(Kind kind,int id,int revision) {owned(kind,id);write(kind,id,revision,new LinkedHashMap<>(Map.of("deleted",true)));}
    @Transactional
    public Map<String,Object> changeStatus(LifeRequests.State request) {
        var old=owned(Kind.WISH,request.id());String status=(String)old.get("status"),next;
        if("PENDING".equals(status)&&"FULFILL".equals(request.action()))next="FULFILLED";
        else if("PENDING".equals(status)&&"ARCHIVE".equals(request.action()))next="ARCHIVED";
        else if(!"PENDING".equals(status)&&"RESTORE".equals(request.action()))next="PENDING";
        else throw new BusinessException("当前状态不支持此操作，请刷新记录");
        Map<String,Object> values=new LinkedHashMap<>();values.put("status",next);values.put("fulfilled_at","FULFILLED".equals(next)?LocalDateTime.now(ZoneId.of("Asia/Shanghai")):null);
        write(Kind.WISH,request.id(),request.revision(),values);
        return publicRow(owned(Kind.WISH,request.id()));
    }
    @Transactional
    public Map<String,Object> pin(LifeRequests.Pin request) {
        owned(Kind.BOOKMARK,request.id());write(Kind.BOOKMARK,request.id(),request.revision(),new LinkedHashMap<>(Map.of("pinned",request.pinned())));
        return Map.of("revision",request.revision()+1);
    }
    public Map<String,Object> reminders() {
        long count=store.rows(Kind.DATE,UserUtils.getUserId()).stream().map(dates::decorate).filter(r->Boolean.TRUE.equals(r.get("reminderActive"))).count();
        return Map.of("serverToday",dates.today(),"activeReminderCount",count);
    }
    private void write(Kind kind,int id,int revision,Map<String,Object> values) {
        if(store.update(kind,UserUtils.getUserId(),id,revision,values)!=1)throw new BusinessException("记录已更新，请重新加载后再操作");
    }
    private void requireVersion(Integer id,Integer revision) {if(id==null||id<1||revision==null||revision<1)throw new BusinessException("缺少记录版本，请重新加载");}
    private Map<String,Object> values(Kind kind,LifeRequests.Write input) {
        Map<String,Object> values=new LinkedHashMap<>();
        String title=Objects.toString(input.getTitle(),"").trim();if(title.isEmpty())throw new BusinessException("请输入名称");
        values.put("title",title);values.put("remark",Objects.toString(input.getRemark(),""));
        if(kind==Kind.WISH) {
            LifeRequests.Wish f=(LifeRequests.Wish)input;
            if(f.getTargetDate()!=null&&(f.getTargetDate().getYear()<1901||f.getTargetDate().getYear()>2099))throw new BusinessException("目标日期年份支持1901–2099年");
            values.put("estimated_amount",f.getEstimatedAmount()==null?null:f.getEstimatedAmount().setScale(2));values.put("target_date",f.getTargetDate());
            values.put("priority",choice(f.getPriority(),Set.of("NORMAL","HIGH"),"NORMAL"));values.put("reference_url",url(f.getReferenceUrl(),false));
        }else if(kind==Kind.DATE) {
            LifeRequests.ImportantDate f=(LifeRequests.ImportantDate)input;dates.validate(f);
            if(f.getRemindDays()!=null&&!Set.of(0,1,3,7,30).contains(f.getRemindDays()))throw new BusinessException("提示天数无效");
            values.put("date_type",choice(f.getDateType(),Set.of("BIRTHDAY","ANNIVERSARY","EXPIRY","OTHER"),"OTHER"));
            values.put("calendar_type",f.getCalendarType());values.put("event_year",f.getEventYear());values.put("event_month",f.getEventMonth());values.put("event_day",f.getEventDay());values.put("leap_month",Boolean.TRUE.equals(f.getLeapMonth()));
            values.put("repeat_type",choice(f.getRepeatType(),Set.of("NONE","YEARLY"),"NONE"));values.put("remind_days",f.getRemindDays());
        }else {
            LifeRequests.Bookmark f=(LifeRequests.Bookmark)input;values.put("url",url(f.getUrl(),false));values.put("category",choice(f.getCategory(),Set.of("WEBSITE","ARTICLE","VIDEO","OTHER"),"OTHER"));
        }
        return values;
    }
    private String choice(String value,Set<String> allowed,String fallback) {if(value==null||value.isBlank())return fallback;if(!allowed.contains(value))throw new BusinessException("选项无效，请重新选择");return value;}
    private String url(String input,boolean required) {
        if(input==null||input.isBlank()){if(required)throw new BusinessException("请输入链接");return null;}
        String value=input.trim();
        try {URI uri=new URI(value);if(!Set.of("http","https").contains(Objects.toString(uri.getScheme(),"").toLowerCase(Locale.ROOT))||uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException();return value;}
        catch(Exception ex){throw new BusinessException("请输入有效的HTTP或HTTPS链接，且不要包含登录密码");}
    }
    private String hash(Map<String,Object> values) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(values).getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException("无法生成新增请求摘要",ex);}}
    private Map<String,Object> publicRow(Map<String,Object> row) {
        Map<String,Object> copy=new LinkedHashMap<>(row);for(String key:List.of("createHash","clientRequestId","userId","deleted"))copy.remove(key);
        for(String key:List.of("pinned","leapMonth"))if(copy.containsKey(key))copy.put(key,truth(copy.get(key)));
        return copy;
    }
    private boolean truth(Object value){return Boolean.TRUE.equals(value)||(value instanceof Number n&&n.intValue()==1);}
}
