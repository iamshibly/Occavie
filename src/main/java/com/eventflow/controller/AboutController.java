package com.eventflow.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Public "About Occavie" page: what the platform is, who it is for, and what makes it different. */
@Controller
public class AboutController {
    @GetMapping("/about")
    public String about() {
        return "about";
    }
}
