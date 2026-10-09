package org.example.auth.persistence;
import jakarta.persistence.*;
@Entity @Table(name="users")
public class UserEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(name="full_name",nullable=false,length=150) String fullName;
    @Column(nullable=false,length=254) String email;
    @Column(nullable=false,length=20) String phone;
    @Column(name="password_hash",nullable=false,length=60) String passwordHash;
    @Column(nullable=false,length=20) String role;
    @Column(nullable=false,length=20) String status;
    protected UserEntity() {}
}
