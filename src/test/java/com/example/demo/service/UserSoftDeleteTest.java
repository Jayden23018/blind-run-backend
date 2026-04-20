package com.example.demo.service;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * User soft delete consistency tests
 */
@SpringBootTest
@Transactional
@AutoConfigureTestDatabase
@ActiveProfiles("test")
class UserSoftDeleteTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setPhone("13900000001");
        testUser.setName("Test User");
    }

    @Test
    void deletedUserNotReturnedById() {
        // Save a user
        User savedUser = userRepository.save(testUser);
        Long userId = savedUser.getId();

        // Soft delete the user
        savedUser.setDeletedAt(LocalDateTime.now());
        userRepository.save(savedUser);

        // Flush and clear to ensure @Where clause is applied
        entityManager.flush();
        entityManager.clear();

        // Find by ID should return empty
        assertThat(userRepository.findById(userId)).isEmpty();
    }

    @Test
    void deletedUserNotReturnedInList() {
        // Create two users
        User userA = new User();
        userA.setPhone("13900000002");
        userA.setName("User A");
        User savedUserA = userRepository.save(userA);

        User userB = new User();
        userB.setPhone("13900000003");
        userB.setName("User B");
        User savedUserB = userRepository.save(userB);

        // Soft delete userB
        savedUserB.setDeletedAt(LocalDateTime.now());
        userRepository.save(savedUserB);

        // Flush and clear to ensure @Where clause is applied
        entityManager.flush();
        entityManager.clear();

        // findAll() should only return userA
        List<User> allUsers = userRepository.findAll();
        assertThat(allUsers).hasSize(1);
        assertThat(allUsers.get(0).getId()).isEqualTo(savedUserA.getId());
        assertThat(allUsers.get(0).getPhone()).isEqualTo("13900000002");
    }

    @Test
    void activeUserStillReturned() {
        // Save an active user
        User savedUser = userRepository.save(testUser);
        Long userId = savedUser.getId();

        // Find by ID should return the user
        assertThat(userRepository.findById(userId)).isPresent();
        assertThat(userRepository.findById(userId).get().getPhone()).isEqualTo("13900000001");
    }
}
