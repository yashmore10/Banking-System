package com.banking.accountservice.controller;

import com.banking.accountservice.dto.AuthResponse;
import com.banking.accountservice.dto.LoginRequest;
import com.banking.accountservice.dto.RegisterRequest;
import com.banking.accountservice.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @PostMapping("/register")
    public ResponseEntity<String> register(
            @Valid @RequestBody RegisterRequest request){

        userService.register(request);

        return ResponseEntity.status(HttpStatus.CREATED).body("Registered Successfully");
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request){

        String token=userService.login(request);

        return ResponseEntity.ok(new AuthResponse(token));
    }
}
