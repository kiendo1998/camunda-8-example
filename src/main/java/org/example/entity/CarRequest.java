package org.example.entity;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;

@Entity
@Table(name = "car_requests")
@Data
public class CarRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String username; // director, guard, staff, etc.

    private String role; // GIÁM ĐỐC, TRƯỞNG PHÒNG, etc.

    private String carName; // Ví dụ: Toyota

    private LocalDate startDate; // Ngày đi

    private LocalDate endDate; // Ngày về

    @Column(length = 500)
    private String purpose; // Mục đích sử dụng xe

    private String status; // pending, approved, rejected (nếu cần workflow)
}
