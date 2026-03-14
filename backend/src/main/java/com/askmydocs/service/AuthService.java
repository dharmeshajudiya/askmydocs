package com.askmydocs.service;

import com.askmydocs.dto.TokenResponse;
import com.askmydocs.entity.User;
import com.askmydocs.repository.UserRepository;
import com.askmydocs.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Transactional
    public TokenResponse register(String email, String password) {
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }
        var user = User.builder()
            .email(email)
            .hashedPassword(passwordEncoder.encode(password))
            .build();
        userRepository.save(user);
        return buildTokens(user.getId());
    }

    public TokenResponse login(String email, String password) {
        var user = userRepository.findByEmail(email)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if (!passwordEncoder.matches(password, user.getHashedPassword())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        return buildTokens(user.getId());
    }

    public TokenResponse refresh(String refreshToken) {
        if (!jwtUtil.isValid(refreshToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        }
        String userId = jwtUtil.extractUserId(refreshToken);
        UUID id = UUID.fromString(userId);
        if (!userRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found");
        }
        return buildTokens(id);
    }

    private TokenResponse buildTokens(UUID userId) {
        return TokenResponse.of(
            jwtUtil.generateAccessToken(userId),
            jwtUtil.generateRefreshToken(userId)
        );
    }
}
