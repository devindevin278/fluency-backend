package com.skripsi.Fluency.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Entity
@Table
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectDetail {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "status_id")
    private Status status;

    @ManyToOne
    @JoinColumn(name = "project_header_id")
    private ProjectHeader projectHeader;

    @ManyToOne
    @JoinColumn(name = "media_type_id")
    private MediaType mediaType;

    @Column
    private LocalDate deadlineDate;
    @Column
    private LocalTime deadlineTime;

    @Column(length = 255)
    private String note;
    private String link;

    @Column
    private Double nominal;

    @Column(length = 55)
    private String paymentMethod;

    @Column
    private String instagramMediaId;

    @Column
    private LocalDateTime analyticsLastUpdated;

    @Column(columnDefinition = "TEXT")
    private String mediaUrl;

    @Column(columnDefinition = "TEXT")
    private String analyticsCaption;

    @Column
    private Integer analyticsLikes;
    private Integer analyticsComments;
    private Integer analyticsSaved;
    private Integer analyticsShared;
    private Integer analyticsAccountsEngaged;
    private Integer analyticsAccountsReached;
    private Double sentimentPositive;
    private Double sentimentNegative;
    private Double sentimentNeutral;

    @Column(length = 55)
    private String label;

    //    tambahan
    @OneToMany(mappedBy = "projectDetail")
    private List<TopComment> topComments;
}
