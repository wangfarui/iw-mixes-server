package com.itwray.iw.external.zhaogang.notification;

import com.itwray.iw.external.zhaogang.worklog.WorklogDailyReader.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** WeCom text has a UTF-8 byte budget, including the repeated continuation header. */
final class WorklogMessageFormatter {
    static final int LIMIT = 2048;
    private static final BigDecimal TARGET = BigDecimal.valueOf(8);
    List<String> format(Snapshot snapshot) {
        return format(snapshot, null, Map.of()).stream().map(NotificationMessage::content).toList();
    }
    List<NotificationMessage> format(Snapshot snapshot, Long summaryMemberId, Map<Long, String> mobiles) {
        List<NotificationMessage> messages = new ArrayList<>();
        int met = 0;
        List<String> leave = new ArrayList<>(), insufficient = new ArrayList<>(), empty = new ArrayList<>(), abnormal = new ArrayList<>();
        for (var member : snapshot.members()) {
            if (member.leave()) leave.add(member.name());
            else if (!member.complete()) abnormal.add(member.name());
            else if (member.hours().compareTo(TARGET) >= 0) met++;
            else if (member.hours().signum() > 0) insufficient.add(member.name());
            else empty.add(member.name());
        }
        StringBuilder report = new StringBuilder("团队成员：").append(snapshot.members().size())
                .append("人；应登记：").append(snapshot.members().size() - leave.size())
                .append("人\n已达8h：").append(met).append("人\n");
        category(report, "已登记但不足8h", insufficient);
        category(report, "未登记", empty);
        category(report, "数据异常", abnormal);
        category(report, "请假", leave);
        report.append(snapshot.members().isEmpty() ? "\n当前团队没有参与工时统计的成员。" : "\n接下来逐人发送今日登记明细。")
                .append("\n本报告为采集时的登记进度，非最终缺勤结果。\n数据更新：")
                .append(safe(snapshot.collectedAt()));
        String heading = safe(snapshot.teamName()) + " ｜ " + snapshot.date() + " ｜ ";
        append(messages, split(heading + "今日工时报告汇总", report.toString()), summaryMemberId == null ? null : mobiles.get(summaryMemberId));
        for (var member : snapshot.members()) {
            StringBuilder body = new StringBuilder(status(member)).append("\n");
            if (!member.complete()) body.append(safe(member.warning())).append("\n");
            Map<String, List<Entry>> groups = new LinkedHashMap<>();
            // Group by the real project identifier, even when different projects share a display name.
            member.entries().forEach(e -> groups.computeIfAbsent(e.project() + "\u0000" + e.issueCode(), k -> new ArrayList<>()).add(e));
            for (var group : groups.values()) {
                Entry first = group.get(0);
                // WeCom can collapse empty Markdown lines; keep a visually blank, nonempty spacer.
                body.append("\u200B\n项目：").append(safe(first.projectDisplayName())).append("\n");
                first.parents().forEach(parent -> body.append(safe(parent)).append("\n"));
                body.append(safe(first.issueType())).append("：#").append(first.issueCode()).append(" ")
                        .append(safe(first.title())).append("\n登记工时：");
                body.append(group.stream().map(e -> hours(e.hours()) + "h").collect(java.util.stream.Collectors.joining("、"))).append("\n");
            }
            boolean remind = !member.leave() && member.complete() && member.hours().compareTo(TARGET) < 0;
            append(messages, split(heading + safe(member.name()), body.toString()), remind ? mobiles.get(member.userId()) : null);
        }
        return List.copyOf(messages);
    }
    private void append(List<NotificationMessage> messages, List<String> parts, String mobile) {
        for (int i = 0; i < parts.size(); i++) {
            messages.add(new NotificationMessage("text", parts.get(i),
                    i == 0 && mobile != null && !mobile.isBlank() ? List.of(mobile) : List.of()));
        }
    }
    private void category(StringBuilder report, String label, List<String> members) {
        if (members.isEmpty()) return;
        report.append(label).append("：").append(members.size()).append("人（")
                .append(members.stream().map(WorklogMessageFormatter::safe).collect(java.util.stream.Collectors.joining("、")))
                .append("）\n");
    }
    private String status(Member m) {
        if (m.leave()) return "今日请假，不计不足；已获取" + hours(m.hours()) + "h" + (m.complete() ? "" : "（数据不完整）");
        if (!m.complete()) return m.entries().isEmpty() ? "数据未获取，无法判断今日登记情况" : "已获取" + hours(m.hours()) + "h，数据不完整，暂不判断是否达到8h";
        if (m.hours().compareTo(TARGET) >= 0) return "已登记" + hours(m.hours()) + "h，已达8h";
        return (m.hours().signum() == 0 ? "今日暂无工时登记" : "已登记" + hours(m.hours()) + "h") + "，距8h还差" + hours(TARGET.subtract(m.hours())) + "h";
    }
    static String hours(BigDecimal h) { return h.stripTrailingZeros().toPlainString(); }
    // Fullwidth punctuation prevents untrusted issue names from injecting Markdown, links or mentions.
    static String safe(String value) {
        if (value == null) return "";
        return value.replace('&', '＆').replace('<', '＜').replace('>', '＞').replace('*', '＊')
                .replace('_', '＿').replace('`', '｀').replace('[', '［').replace(']', '］')
                .replace('\\', '＼').replace('@', '＠').replace('#', '＃')
                .replace('\r', ' ').replace('\n', ' ').replace('\u0000', ' ');
    }
    static List<String> split(String title, String body) {
        // Keep unusually long names as content too; repeated headers remain compact.
        String header = title;
        if (bytes(header) > 480) { header = "工作台助手｜工时明细"; body = title + "\n" + body; }
        String prefix = header + "\n\n";
        if (bytes(prefix + body) <= LIMIT) return List.of(prefix + body);
        int budget = LIMIT - bytes(prefix) - 48;
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        int size = 0;
        for (int cp : body.codePoints().toArray()) {
            String ch = new String(Character.toChars(cp)); int length = bytes(ch);
            if (size + length > budget) { parts.add(part.toString()); part.setLength(0); size = 0; }
            part.append(ch); size += length;
        }
        if (!part.isEmpty()) parts.add(part.toString());
        List<String> result = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) result.add(header + "（" + (i + 1) + "/" + parts.size() + "）\n\n" + parts.get(i));
        return result;
    }
    static int bytes(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
}
