package ru.leyman.thoth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.leyman.thoth.domain.User;

public interface UserRepository extends JpaRepository<User, Long> {
}
