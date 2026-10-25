package ru.leyman.thoth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.leyman.thoth.domain.Guest;

import java.util.List;
import java.util.UUID;

public interface GuestRepository extends JpaRepository<Guest, Long> {

    List<Guest> findAllBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    long countBySessionId(UUID sessionId);
}
