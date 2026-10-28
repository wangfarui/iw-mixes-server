package com.itwray.iw.life;

import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Explicit request types; ownership is always supplied by authentication. */
public final class LifeRequests {
    private LifeRequests() {}
    @Data public static class Write {
        private Integer id;
        @Min(1) private Integer revision;
        @NotBlank @Size(max=80) private String title;
        @Size(max=1000) private String remark;
        @Size(max=64) private String clientRequestId;
    }
    @Data @EqualsAndHashCode(callSuper=true) public static class Wish extends Write {
        @DecimalMin("0") @DecimalMax("99999999.99") @Digits(integer=8,fraction=2) private BigDecimal estimatedAmount;
        private LocalDate targetDate;
        private String priority;
        @Size(max=2048) private String referenceUrl;
    }
    @Data @EqualsAndHashCode(callSuper=true) public static class ImportantDate extends Write {
        private String dateType;
        @NotNull private String calendarType;
        @NotNull @Min(1901) @Max(2099) private Integer eventYear;
        @NotNull @Min(1) @Max(12) private Integer eventMonth;
        @NotNull @Min(1) @Max(31) private Integer eventDay;
        private Boolean leapMonth;
        private String repeatType;
        private Integer remindDays;
    }
    @Data @EqualsAndHashCode(callSuper=true) public static class Bookmark extends Write {
        @Size(max=2048) private String url;
        private String category;
    }
    @Data public static class Page {
        @Min(1) private int currentPage=1;
        @Min(1) @Max(50) private int pageSize=20;
        @Size(max=100) private String keyword;
        private String status;
        private String category;
        private String view;
    }
    public record State(@NotNull @Min(1) Integer id, @NotNull @Min(1) Integer revision, @NotNull String action) {}
    public record Pin(@NotNull @Min(1) Integer id, @NotNull @Min(1) Integer revision, @NotNull Boolean pinned) {}
}
