package com.softschool.backend.controller;

import com.softschool.backend.model.LoginRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*") // Allows your frontend to connect
public class AuthController {

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        String dummyPhone = "03181909541";
        String dummyPass = "1234567";

        Map<String, String> response = new HashMap<>();

        if (dummyPhone.equals(request.getPhone()) && dummyPass.equals(request.getPassword())) {
            response.put("status", "success");
            response.put("message", "Login Successful");
            response.put("redirectUrl", "/main.html"); // Where the user goes after login
            return ResponseEntity.ok(response);
        } else {
            response.put("status", "error");
            response.put("message", "Invalid phone number or password");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        }
    }
}