package com.app.modules.recommendation.entity;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Composite primary key for {@link UserHashtagAffinity} {@code (user_id, hashtag_id)}. */
@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class UserHashtagAffinityId implements Serializable {

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "hashtag_id")
    private UUID hashtagId;
}
