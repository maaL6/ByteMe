package org.example.auth.persistence;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
interface UserJpaRepository extends JpaRepository<UserEntity,Long> {
    boolean existsByEmailOrPhone(String email,String phone);
    Optional<UserEntity> findByEmail(String email);
}
