package com.skripsi.Fluency.model.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table
@Data
public class TopComment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne
    @JoinColumn(name = "project_detail_id")
    private ProjectDetail projectDetail;

    @Column(length = 55)
    private String username;

    @Column(length = 255)
    private String comment;

    @Column
    private Integer likes;
    private LocalDateTime dateTime;
}
