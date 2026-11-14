package com.example.shop.web.api;

import com.example.shop.stock.AvailableStockSynchronizer;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/admin/stock/resync (REQ-IF-001 6, REQ-F-013 4). 409 while another resync holds the lock.
 *
 * POST /api/admin/stock/resync (REQ-IF-001 6, REQ-F-013 4). 다른 재동기화가 락을 잡고 있으면 409.
 */
@RestController
public class AdminStockApiController {

    private final AvailableStockSynchronizer synchronizer;

    public AdminStockApiController(AvailableStockSynchronizer synchronizer) {
        this.synchronizer = synchronizer;
    }

    @PostMapping("/api/admin/stock/resync")
    public ResyncResponse resync() {
        return new ResyncResponse(synchronizer.synchronizeFromDatabase());
    }

    public record ResyncResponse(int synchronizedProducts) {
    }
}
