package com.example.demo;

import java.time.LocalDateTime;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
@Entity
@Table(name="drivers")
public class Driver {
    @Id
    @GeneratedValue(strategy=GenerationType.AUTO)
    @Column(name="id")
    private Integer id;
    @Column(unique = true)
    private String unid;
    @Column
    private String firstName;
    @Column
    private String lastName;
    @Column
    private String middleInitial;
    @Column(unique = true)
    private String personId;
    @Column
    private LocalDateTime effectiveDate;
    @Column
    private LocalDateTime expirationDate;
    @Column
    private String status;

    @OneToMany(orphanRemoval=true,fetch = FetchType.EAGER)
    @JoinColumn( name="driverId", insertable = false, updatable = false)
    private Set<CarrierDriver> carrierDrivers;

}



@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
@Entity
@IdClass(CarrierDriverId.class)
@Table(name="carrier-assignments")
class CarrierDriver {
    @Id
    @Column
    private Integer badgeId;
    @Id
    @Column
    private String carrierId;
    @Column
    private Integer driverId;
    @Column
    private LocalDateTime effectiveDate;
    @Column
    private LocalDateTime expirationDate;
    @Column
    private String status;

}

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
class CarrierDriverId {
    private Integer badgeId;
    private String carrierId;
}
