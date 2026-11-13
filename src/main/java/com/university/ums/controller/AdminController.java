package com.university.ums.controller;

import com.university.ums.model.Role;
import com.university.ums.service.UserService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /api/admins - ADMIN only. */
@RestController
@RequestMapping("/api/admins")
public class AdminController extends AbstractUserController {
    public AdminController(UserService service) {
        super(service, Role.ADMIN);
    }
}
