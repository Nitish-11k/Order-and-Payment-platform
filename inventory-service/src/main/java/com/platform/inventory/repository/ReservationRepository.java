package com.platform.inventory.repository;

import com.platform.inventory.entity.InventoryReservation;
import com.platform.inventory.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReservationRepository extends JpaRepository<InventoryReservation, UUID> {

    List<InventoryReservation> findByOrderId(String orderId);

    Optional<InventoryReservation> findByOrderIdAndSku(String orderId, String sku);

    List<InventoryReservation> findByStatus(ReservationStatus status);
}
