package com.eventflow.model;

import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;


@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "users")
public class User {

    @Id
    private String id;

    private String fullName;

    @Indexed(unique = true)
    private String email;

    private String password;

    @Indexed
    private String role;

    @Builder.Default
    private boolean enabled = true;

    private String profileImageUrl;

    /** Public organizer profile (all optional; only shown on /organizers/{id}). */
    private String bio;
    private String website;
    private String publicContactEmail;

    /** Notification preferences: essential booking emails are always sent; these two are optional. */
    @Builder.Default
    private boolean emailReminders = true;
    @Builder.Default
    private boolean emailAnnouncements = true;



}
