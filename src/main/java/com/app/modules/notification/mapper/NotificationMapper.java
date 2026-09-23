package com.app.modules.notification.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.app.common.response.UserSummaryResponse;
import com.app.modules.notification.dto.response.NotificationResponse;
import com.app.modules.notification.entity.Notification;

@Mapper(componentModel = "spring")
public interface NotificationMapper {

    /**
     * Builds the notification response from the entity and its separately resolved actor summary.
     *
     * @param notification the source notification; scalar fields map by name
     * @param actor the triggering user's public summary, resolved in a batch by the caller; null
     *     when the notification has no actor
     * @return the notification response with the actor embedded
     */
    @Mapping(source = "notification.id", target = "id")
    @Mapping(source = "actor", target = "actor")
    @Mapping(target = "isRead", expression = "java(notification.getReadAt() != null)")
    NotificationResponse toResponse(Notification notification, UserSummaryResponse actor);
}
