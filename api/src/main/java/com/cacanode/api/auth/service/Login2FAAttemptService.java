package com.cacanode.api.auth.service;

import com.cacanode.api.auth.repository.Login2FAStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class Login2FAAttemptService {

    private final Login2FAStateRepository login2FAStateRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIncorrectAttempt(UUID stateId, LocalDateTime attemptedAt, int maximumAttempts) {
        login2FAStateRepository.recordIncorrectAttempt(stateId, attemptedAt, maximumAttempts);
    }
}
