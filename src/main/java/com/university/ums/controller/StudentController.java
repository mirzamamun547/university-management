package com.university.ums.controller;

import com.university.ums.model.Role;
import com.university.ums.service.UserService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /api/students - ADMIN and STUDENT. */
@RestController
@RequestMapping("/api/students")
public class StudentController extends AbstractUserController {
    public StudentController(UserService service) {
        super(service, Role.STUDENT);
    }
}
