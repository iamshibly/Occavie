package com.eventflow.dto;

import org.springframework.web.multipart.MultipartFile;

/** Partial event data for one wizard step. Every field is optional; blank values are ignored. */
public record EventDraftForm(
        String title,
        String category,
        String capacity,
        String date,
        String time,
        String location,
        String description,
        String pricingType,
        String basePrice,
        MultipartFile image,
        String address,
        String onlineUrl
) {}
